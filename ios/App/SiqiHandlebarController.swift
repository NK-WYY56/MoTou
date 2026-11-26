import Foundation
import GameController
import MediaPlayer
import UIKit

/// 蓝牙手把按键映射动作（与 Android 思骑CarPlay HandlebarKeyBridge 保持一致）。
enum SiqiKeyAction: Sendable {
    case up
    case down
    case confirm
    case back
    case home
    case siri
    case next
    case prev
    case playPause

    var title: String {
        switch self {
        case .up: return "向上 (旋钮前翻)"
        case .down: return "向下 (旋钮后翻)"
        case .confirm: return "确认 (旋钮按下)"
        case .back: return "返回 (上一层)"
        case .home: return "主页"
        case .siri: return "Siri / 语音"
        case .next: return "下一曲"
        case .prev: return "上一曲"
        case .playPause: return "播放 / 暂停"
        }
    }

    var carPlayControl: CarPlayControl {
        switch self {
        case .up: return .knobPrevious
        case .down: return .knobNext
        case .confirm: return .select
        case .back: return .back
        case .home: return .home
        case .siri: return .siri
        case .next: return .next
        case .prev: return .previous
        case .playPause: return .playPause
        }
    }
}

/// 蓝牙控制器连接状态快照。
struct SiqiControllerSnapshot: Equatable, Sendable {
    let connected: Bool
    let controllerName: String?
    let mediaRemoteEnabled: Bool
    let lastActionSummary: String?
}

/// iPhone 蓝牙手把按键控制器管理器：
/// 1) 蓝牙 HID 键盘 / 五向键 / 游戏手柄（GameController + UIResponder presses）：
///    支持 上/下/确认/返回/主页，以及 确认键长按 ≥0.7s 呼出 Siri（前台生效）。
/// 2) 可选「锁屏媒体按键接管」（MPRemoteCommandCenter）：
///    兼容市面防水蓝牙媒体遥控器（上一曲→上、下一曲→下、播放暂停→确认），
///    配合后台保活可在手机锁屏放兜里时操控 CarPlay 光标。
final class SiqiHandlebarController {
    var onAction: ((SiqiKeyAction, Bool) -> Void)?
    var onSnapshotChanged: ((SiqiControllerSnapshot) -> Void)?
    var onLog: ((String) -> Void)?

    private var observers: [NSObjectProtocol] = []
    private var confirmLongPressTimer: Timer?
    private var confirmLongHandled = false
    private var lastDispatchedAction: SiqiKeyAction?
    private var lastDispatchedAt: TimeInterval = -Double.infinity
    private var sessionActive = false
    private var mediaRemoteRegistered = false
    private var lastActionSummary: String?

    var mediaRemoteEnabled: Bool {
        didSet {
            UserDefaults.standard.set(mediaRemoteEnabled, forKey: "siqiMediaRemoteCursorEnabled")
            syncMediaRemoteRegistration()
            publishSnapshot()
        }
    }

    init() {
        self.mediaRemoteEnabled = UserDefaults.standard.bool(forKey: "siqiMediaRemoteCursorEnabled")
    }

    deinit {
        stop()
    }

    func start() {
        guard observers.isEmpty else {
            attachCurrentDevices()
            publishSnapshot()
            return
        }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: .GCKeyboardDidConnect, object: nil, queue: .main) { [weak self] _ in
                self?.onLog?("检测到蓝牙 HID 按键/键盘已连接")
                self?.attachCurrentDevices()
                self?.publishSnapshot()
            },
            center.addObserver(forName: .GCKeyboardDidDisconnect, object: nil, queue: .main) { [weak self] _ in
                self?.onLog?("蓝牙 HID 按键/键盘已断开")
                self?.publishSnapshot()
            },
            center.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { [weak self] note in
                let name = (note.object as? GCController)?.vendorName ?? "蓝牙手柄控制器"
                self?.onLog?("检测到蓝牙控制器已连接：\(name)")
                self?.attachCurrentDevices()
                self?.publishSnapshot()
            },
            center.addObserver(forName: .GCControllerDidDisconnect, object: nil, queue: .main) { [weak self] _ in
                self?.onLog?("蓝牙控制器已断开")
                self?.publishSnapshot()
            }
        ]
        attachCurrentDevices()
        syncMediaRemoteRegistration()
        publishSnapshot()
    }

    func stop() {
        confirmLongPressTimer?.invalidate()
        confirmLongPressTimer = nil
        observers.forEach(NotificationCenter.default.removeObserver)
        observers.removeAll()
        unregisterMediaRemote()
    }

    func setSessionActive(_ active: Bool) {
        sessionActive = active
        syncMediaRemoteRegistration()
        publishSnapshot()
    }

    func currentSnapshot() -> SiqiControllerSnapshot {
        let controller = GCController.controllers().first
        let keyboard = GCKeyboard.coalesced
        let connected = (controller != nil) || (keyboard != nil)
        let name: String?
        if let c = controller {
            name = c.vendorName?.isEmpty == false ? c.vendorName : "蓝牙手柄按键控制器"
        } else if let k = keyboard {
            name = k.vendorName?.isEmpty == false ? k.vendorName : "蓝牙 HID 按键控制器"
        } else {
            name = nil
        }
        return SiqiControllerSnapshot(
            connected: connected,
            controllerName: name,
            mediaRemoteEnabled: mediaRemoteEnabled,
            lastActionSummary: lastActionSummary
        )
    }

    func triggerSimulated(_ action: SiqiKeyAction, isLongPress: Bool = false) {
        dispatch(action, isLongPress: isLongPress, deduplicate: false)
    }

    /// UIResponder 物理按键兜底（处理不经过 GCKeyboard 的通用蓝牙 HID 遥控器）。
    @discardableResult
    func handlePressesBegan(_ presses: Set<UIPress>) -> Bool {
        var handled = false
        for press in presses {
            guard let key = press.key else { continue }
            guard let action = mapUIKey(key.keyCode) else { continue }
            if action == .confirm {
                beginConfirmPress()
            } else {
                dispatch(action, isLongPress: false, deduplicate: true)
            }
            handled = true
        }
        return handled
    }

    @discardableResult
    func handlePressesEnded(_ presses: Set<UIPress>) -> Bool {
        var handled = false
        for press in presses {
            guard let key = press.key else { continue }
            guard let action = mapUIKey(key.keyCode) else { continue }
            if action == .confirm {
                endConfirmPress()
            }
            handled = true
        }
        return handled
    }

    private func attachCurrentDevices() {
        if let keyboard = GCKeyboard.coalesced?.keyboardInput {
            keyboard.keyChangedHandler = { [weak self] _, _, keyCode, pressed in
                DispatchQueue.main.async {
                    self?.handleGCKey(keyCode, pressed: pressed)
                }
            }
        }
        for controller in GCController.controllers() {
            if let ext = controller.extendedGamepad {
                ext.dpad.up.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.up, isLongPress: false, deduplicate: true) } }
                }
                ext.dpad.left.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.up, isLongPress: false, deduplicate: true) } }
                }
                ext.dpad.down.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.down, isLongPress: false, deduplicate: true) } }
                }
                ext.dpad.right.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.down, isLongPress: false, deduplicate: true) } }
                }
                ext.buttonA.pressedChangedHandler = { [weak self] _, _, pressed in
                    DispatchQueue.main.async {
                        if pressed { self?.beginConfirmPress() } else { self?.endConfirmPress() }
                    }
                }
                ext.buttonB.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.back, isLongPress: false, deduplicate: true) } }
                }
                ext.buttonMenu.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.home, isLongPress: false, deduplicate: true) } }
                }
                ext.leftShoulder.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.prev, isLongPress: false, deduplicate: true) } }
                }
                ext.rightShoulder.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.next, isLongPress: false, deduplicate: true) } }
                }
            } else if let micro = controller.microGamepad {
                micro.dpad.up.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.up, isLongPress: false, deduplicate: true) } }
                }
                micro.dpad.down.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.down, isLongPress: false, deduplicate: true) } }
                }
                micro.buttonA.pressedChangedHandler = { [weak self] _, _, pressed in
                    DispatchQueue.main.async {
                        if pressed { self?.beginConfirmPress() } else { self?.endConfirmPress() }
                    }
                }
                micro.buttonX.pressedChangedHandler = { [weak self] _, _, pressed in
                    if pressed { DispatchQueue.main.async { self?.dispatch(.back, isLongPress: false, deduplicate: true) } }
                }
            }
        }
    }

    private func handleGCKey(_ keyCode: GCKeyCode, pressed: Bool) {
        guard let action = mapGCKey(keyCode) else { return }
        if action == .confirm {
            if pressed { beginConfirmPress() } else { endConfirmPress() }
        } else if pressed {
            dispatch(action, isLongPress: false, deduplicate: true)
        }
    }

    private func beginConfirmPress() {
        guard confirmLongPressTimer == nil else { return }
        confirmLongHandled = false
        let timer = Timer(timeInterval: 0.7, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.confirmLongPressTimer = nil
            self.confirmLongHandled = true
            self.dispatch(.siri, isLongPress: true, deduplicate: false)
        }
        confirmLongPressTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func endConfirmPress() {
        let hadTimer = confirmLongPressTimer != nil
        confirmLongPressTimer?.invalidate()
        confirmLongPressTimer = nil
        if confirmLongHandled {
            confirmLongHandled = false
            return
        }
        if hadTimer {
            dispatch(.confirm, isLongPress: false, deduplicate: true)
        }
    }

    private func dispatch(_ action: SiqiKeyAction, isLongPress: Bool, deduplicate: Bool) {
        let now = ProcessInfo.processInfo.systemUptime
        if deduplicate, lastDispatchedAction == action, now - lastDispatchedAt < 0.06 {
            return
        }
        lastDispatchedAction = action
        lastDispatchedAt = now
        lastActionSummary = "最近按键：\(action.title)\(isLongPress ? " · 长按呼出 Siri" : "")"
        onLog?("[手把按键] \(action.title)\(isLongPress ? "（长按）" : "")")
        onAction?(action, isLongPress)
        publishSnapshot()
    }

    private func publishSnapshot() {
        onSnapshotChanged?(currentSnapshot())
    }

    // MARK: - Lock-Screen Media Remote (MPRemoteCommandCenter)

    private func syncMediaRemoteRegistration() {
        if mediaRemoteEnabled && sessionActive {
            registerMediaRemote()
        } else {
            unregisterMediaRemote()
        }
    }

    private func registerMediaRemote() {
        guard !mediaRemoteRegistered else { return }
        mediaRemoteRegistered = true
        let center = MPRemoteCommandCenter.shared()
        center.previousTrackCommand.isEnabled = true
        center.nextTrackCommand.isEnabled = true
        center.togglePlayPauseCommand.isEnabled = true
        center.playCommand.isEnabled = true
        center.pauseCommand.isEnabled = true
        center.skipBackwardCommand.isEnabled = true

        center.previousTrackCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.dispatch(.up, isLongPress: false, deduplicate: false) }
            return .success
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.dispatch(.down, isLongPress: false, deduplicate: false) }
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.dispatch(.confirm, isLongPress: false, deduplicate: false) }
            return .success
        }
        center.playCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.dispatch(.confirm, isLongPress: false, deduplicate: false) }
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.dispatch(.confirm, isLongPress: false, deduplicate: false) }
            return .success
        }
        center.skipBackwardCommand.addTarget { [weak self] _ in
            DispatchQueue.main.async { self?.dispatch(.back, isLongPress: false, deduplicate: false) }
            return .success
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: "思骑CarPlay · 手把媒体按键控制",
            MPMediaItemPropertyArtist: "上一曲=上 · 下一曲=下 · 播放=确认",
            MPNowPlayingInfoPropertyPlaybackRate: 1.0
        ]
        onLog?("已开启锁屏蓝牙媒体按键接管（上一曲/下一曲/播放暂停 → CarPlay 光标控制）")
    }

    private func unregisterMediaRemote() {
        guard mediaRemoteRegistered else { return }
        mediaRemoteRegistered = false
        let center = MPRemoteCommandCenter.shared()
        [
            center.previousTrackCommand,
            center.nextTrackCommand,
            center.togglePlayPauseCommand,
            center.playCommand,
            center.pauseCommand,
            center.skipBackwardCommand
        ].forEach {
            $0.removeTarget(nil)
            $0.isEnabled = false
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        onLog?("已关闭锁屏蓝牙媒体按键接管")
    }

    private func mapGCKey(_ code: GCKeyCode) -> SiqiKeyAction? {
        switch code {
        case .upArrow, .leftArrow, .pageUp, .keyW: return .up
        case .downArrow, .rightArrow, .pageDown, .keyS: return .down
        case .returnOrEnter, .keypadEnter, .spacebar: return .confirm
        case .escape, .deleteOrBackspace: return .back
        case .keyH, .home: return .home
        default: return nil
        }
    }

    private func mapUIKey(_ code: UIKeyboardHIDUsage) -> SiqiKeyAction? {
        switch code {
        case .keyboardUpArrow, .keyboardLeftArrow, .keyboardPageUp, .keyboardW: return .up
        case .keyboardDownArrow, .keyboardRightArrow, .keyboardPageDown, .keyboardS: return .down
        case .keyboardReturnOrEnter, .keypadEnter, .keyboardSpacebar: return .confirm
        case .keyboardEscape, .keyboardDeleteOrBackspace: return .back
        case .keyboardH, .keyboardHome: return .home
        default: return nil
        }
    }
}
