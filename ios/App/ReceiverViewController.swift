import AVFoundation
import Network
import NetworkExtension
import UIKit

/// 摩投 主界面（复刻 Android 摩投 三卡片架构）：
/// 1 · 连接思骑仪表（一键复制默认密码 12345678 + 自动记住仪表热点名，无需繁琐预设管理）
/// 2 · 本机 CARPLAY（一键启动/断开 + 全屏预览 + 刷新仪表画面）
/// 3 · 蓝牙按键控制器（支持蓝牙 HID 方向键/手柄控制器操控 CarPlay）
final class ReceiverViewController: UIViewController, SiqiSettingsDelegate {
    private static let defaultSiqiPassword = "12345678"

    private let receiver = NativeReceiver()
    private let sameDevice = SameDeviceProbe()
    private let controlBrowser = CarPlayControlBrowser()
    private let keepAlive = BackgroundKeepAlive()
    private let handlebar = SiqiHandlebarController()
    private let wifiPathMonitor = NWPathMonitor(requiredInterfaceType: .wifi)

    private var identity: AccessoryIdentity?
    private var running = false
    private var waitingForNetwork = false
    private var displayedVideo = false
    private var skippedLocalVideo = false
    private var restartingAutomatically = false
    private var networkRestartCount = 0
    private var manualStopSuppressesAutoStart = false
    private var autoStartWorkItem: DispatchWorkItem?
    private var wifiLeaveStopWorkItem: DispatchWorkItem?
    private var wifiLeavePollTimer: Timer?
    private var networkStartupTask: Task<Void, Never>?
    private var localStartupID: UUID?
    private var startupHintTask: DispatchWorkItem?
    private var startupHintGeneration = UUID()
    private var clusterSnapshot: SiqiClusterSnapshot = .idle
    private var controllerSnapshot: SiqiControllerSnapshot = SiqiControllerSnapshot(
        connected: false,
        controllerName: nil,
        mediaRemoteEnabled: false,
        lastActionSummary: nil
    )
    private var liveCanvasSize: (width: Int, height: Int) = (1280, 560)
    private var liveStreamSize: (width: Int, height: Int) = (1280, 640)
    private var sessionHasVehicleButton = false
    private var lines: [String] = []

    // Persisted settings (Kove 450RR calibrated preset + custom resolution tuner)
    var selectedBikePreset: SiqiBikePreset = .kove450RR {
        didSet { UserDefaults.standard.set(selectedBikePreset.rawValue, forKey: "siqiBikePreset") }
    }
    var customDisplayProfile: SiqiCustomDisplayProfile = SiqiCustomDisplayProfile.loadSaved() {
        didSet { customDisplayProfile.save() }
    }
    var selectedIconPreset: SiqiIconSizePreset = .large {
        didSet { UserDefaults.standard.set(selectedIconPreset.rawValue, forKey: "siqiIconSizePresetV2") }
    }
    var selectedVideoMode: SiqiVideoMode = .transcode {
        didSet { UserDefaults.standard.set(selectedVideoMode.rawValue, forKey: "siqiKoveVideoMode") }
    }
    var selectedAppearance: ReceiverConfiguration.Appearance = .system {
        didSet {
            UserDefaults.standard.set(selectedAppearance.rawValue, forKey: "carPlayAppearance")
            applyAppearance()
        }
    }
    var isKeepAliveEnabled: Bool = true {
        didSet {
            UserDefaults.standard.set(isKeepAliveEnabled, forKey: "backgroundKeepAlive")
            if !isKeepAliveEnabled { keepAlive.stop() }
            else if running, UIApplication.shared.applicationState == .active { keepAlive.start() }
        }
    }
    var isAutoStartOnWiFiEnabled: Bool = true {
        didSet {
            UserDefaults.standard.set(isAutoStartOnWiFiEnabled, forKey: "siqiAutoStartOnWiFi")
        }
    }
    var isAutoStopOnWiFiLeaveEnabled: Bool = true {
        didSet {
            UserDefaults.standard.set(isAutoStopOnWiFiLeaveEnabled, forKey: "siqiAutoStopOnWiFiLeave")
            if !isAutoStopOnWiFiLeaveEnabled {
                cancelWiFiLeaveStopTimer()
            } else {
                evaluateWiFiLeaveAutoStop()
            }
        }
    }
    private var clusterWiFiSSID: String {
        get { SiqiWiFiPresetStore.shared.activeSSID }
        set { SiqiWiFiPresetStore.shared.syncSSIDFromHomeInput(newValue) }
    }

    var isCarPlayRunning: Bool { running || waitingForNetwork }
    var isMFiIdentityLoaded: Bool { identity != nil }
    var currentLogLines: [String] { lines }
    var lastControllerActionText: String? { controllerSnapshot.lastActionSummary }

    // UI elements
    private let scrollView = UIScrollView()
    private let contentStack = UIStackView()

    // Header
    private let headerStatusChip = SiqiChipView()
    private let settingsButton = SiqiButton(title: "设置", variant: .secondary, fontSize: 14, height: 40)

    // Card 1: Cluster Wi-Fi
    private let clusterChip = SiqiChipView()
    private let clusterTitleLabel = UILabel()
    private let clusterDescLabel = SiqiStyle.makeSecondaryLabel("", size: 13)
    private let clusterStatsLabel = SiqiStyle.makeSecondaryLabel("", size: 12)
    private let wifiSSIDField = UITextField()
    private let wifiSpaceWarningRow = UIStackView()
    private let wifiSpaceWarningLabel = SiqiStyle.makeSecondaryLabel("⚠️ 检测到热点名称包含空格，思骑仪表热点通常不含空格", size: 12)
    private let fixWifiSpaceButton = SiqiButton(title: "一键去空格", variant: .secondary, fontSize: 11, height: 28)
    private let connectWiFiButton = SiqiButton(title: "复制密码 12345678 · 连仪表 Wi-Fi", variant: .secondary, fontSize: 13, height: 42)
    private let wifiPresetsButton = SiqiButton(title: "Wi-Fi 预设 · 切换/保存多个热点", variant: .secondary, fontSize: 13, height: 40)

    // Card 2: Self-hosted CarPlay
    private let audioChip = SiqiChipView()
    private let sessionTitleLabel = UILabel()
    private let sessionDescLabel = SiqiStyle.makeSecondaryLabel("", size: 13)
    private let startButton = SiqiButton(title: "启动无线 CarPlay", variant: .primary, fontSize: 15, height: 52)
    private let fullscreenButton = SiqiButton(title: "全屏预览 / 触控", variant: .secondary, fontSize: 13, height: 44)
    private let refreshFrameButton = SiqiButton(title: "刷新仪表画面", variant: .secondary, fontSize: 13, height: 44)
    private let inlineVideoContainer = UIView()
    private let videoSurface = H264Surface()
    private var inlineVideoConstraints: [NSLayoutConstraint] = []
    private var videoHeightConstraint: NSLayoutConstraint?
    private let controlBar = UIStackView()

    // Card 3: Bluetooth Handlebar Key Controller (matching Android card_controller)
    private let controllerChip = SiqiChipView()
    private let controllerNameLabel = UILabel()
    private let controllerKeyLogLabel = SiqiStyle.makeSecondaryLabel("支持蓝牙手把方向键 / 游戏手柄（上 / 下 / 确认 / 返回 / 长按 Siri）", size: 13)
    private let pairControllerButton = SiqiButton(title: "连接蓝牙按键控制器", variant: .secondary, fontSize: 13, height: 44)

    // Fullscreen overlay
    private var fullscreenContainer: UIView?
    private var fullscreenConstraints: [NSLayoutConstraint] = []
    private weak var fullscreenExitButton: UIButton?
    private var fullscreenFadeTimer: Timer?
    override var prefersStatusBarHidden: Bool { fullscreenContainer != nil }
    override var prefersHomeIndicatorAutoHidden: Bool { fullscreenContainer != nil }
    override var canBecomeFirstResponder: Bool { true }

    private static let barControls: [CarPlayControl] = [.home, .back, .siri, .previous, .playPause, .next, .hookSwitch]

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = SiqiStyle.windowBackground
        loadPersistedSettings()
        buildUI()
        wireCallbacks()
        loadIdentity()
        applyAppearance()
        handlebar.mediaRemoteEnabled = false
        handlebar.start()
        controllerSnapshot = handlebar.currentSnapshot()
        tryAutoFillSSIDIfEmpty()
        refreshAllUI()

        if #available(iOS 17.0, *) {
            registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (self: Self, _) in
                self.applyAppearance()
            }
        }

        wifiPathMonitor.pathUpdateHandler = { [weak self] _ in
            DispatchQueue.main.async {
                guard let self else { return }
                if ReceiverConfiguration.wifiIPv4Address() == nil {
                    self.manualStopSuppressesAutoStart = false
                }
                self.refreshClusterCard()
                self.evaluateWiFiLeaveAutoStop()
                self.scheduleAutoStartCheck(trigger: "Wi-Fi 状态变化")
            }
        }
        wifiPathMonitor.start(queue: DispatchQueue(label: "MoTou.wifiPath"))

        NotificationCenter.default.addObserver(self, selector: #selector(willResignActive), name: UIApplication.willResignActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(becameActive), name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(foregrounded), name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(backgrounded), name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(audioRouteChanged), name: AVAudioSession.routeChangeNotification, object: nil)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: false)
        controllerSnapshot = handlebar.currentSnapshot()
        tryAutoFillSSIDIfEmpty()
        refreshAllUI()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        becomeFirstResponder()
        scheduleAutoStartCheck(trigger: "进入页面")
    }

    deinit {
        wifiPathMonitor.cancel()
        networkStartupTask?.cancel()
        autoStartWorkItem?.cancel()
        cancelWiFiLeaveStopTimer()
        handlebar.stop()
        NotificationCenter.default.removeObserver(self)
    }

    // Hardware Bluetooth HID keyboard / handlebar key fallback
    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if running && handlebar.handlePressesBegan(presses) { return }
        super.pressesBegan(presses, with: event)
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if running && handlebar.handlePressesEnded(presses) { return }
        super.pressesEnded(presses, with: event)
    }

    private func loadPersistedSettings() {
        let d = UserDefaults.standard
        if let rawBike = d.object(forKey: "siqiBikePreset") as? Int,
           let bike = SiqiBikePreset(rawValue: rawBike) {
            selectedBikePreset = bike
        }
        if let rawIcon = d.object(forKey: "siqiIconSizePresetV2") as? Int,
           let preset = SiqiIconSizePreset(rawValue: rawIcon) {
            selectedIconPreset = preset
        } else {
            selectedIconPreset = .large
        }
        if let rawMode = d.object(forKey: "siqiKoveVideoMode") as? Int,
           let mode = SiqiVideoMode(rawValue: rawMode) {
            selectedVideoMode = mode
        } else {
            selectedVideoMode = .transcode
        }
        selectedAppearance = ReceiverConfiguration.Appearance(rawValue: d.integer(forKey: "carPlayAppearance")) ?? .system
        isKeepAliveEnabled = d.object(forKey: "backgroundKeepAlive") as? Bool ?? true
        isAutoStartOnWiFiEnabled = d.object(forKey: "siqiAutoStartOnWiFi") as? Bool ?? true
        isAutoStopOnWiFiLeaveEnabled = d.object(forKey: "siqiAutoStopOnWiFiLeave") as? Bool ?? true
        SiqiWiFiPresetStore.shared.reload()
    }

    // MARK: - UI Construction (Android 摩投 3-card layout)

    private func buildUI() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.alwaysBounceVertical = true
        scrollView.keyboardDismissMode = .interactive
        scrollView.panGestureRecognizer.require(toFail: videoSurface.touchOwnershipGestureRecognizer)
        view.addSubview(scrollView)

        contentStack.axis = .vertical
        contentStack.spacing = 14
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(contentStack)

        let widthConstraint = contentStack.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor, constant: -36)
        widthConstraint.priority = UILayoutPriority(999)
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            contentStack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 16),
            contentStack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -28),
            contentStack.centerXAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerXAnchor),
            widthConstraint,
            contentStack.widthAnchor.constraint(lessThanOrEqualToConstant: 640)
        ])

        contentStack.addArrangedSubview(buildHeaderBar())
        contentStack.addArrangedSubview(buildClusterCard())
        contentStack.addArrangedSubview(buildCarPlayCard())
        contentStack.addArrangedSubview(buildControllerCard())

        let footer = SiqiStyle.makeSecondaryLabel("车型预设、画面参数、息屏骑行与开源致谢已整合至右上角「设置」", size: 12)
        footer.textAlignment = .center
        contentStack.addArrangedSubview(footer)
    }

    private func buildHeaderBar() -> UIView {
        let titleLabel = UILabel()
        titleLabel.text = "摩投"
        titleLabel.font = .systemFont(ofSize: 24, weight: .bold)
        titleLabel.textColor = SiqiStyle.textPrimary

        let subLabel = SiqiStyle.makeSecondaryLabel("无线 CarPlay 仪表互联", size: 13)
        let titleCol = UIStackView(arrangedSubviews: [titleLabel, subLabel])
        titleCol.axis = .vertical
        titleCol.spacing = 2

        settingsButton.setContentHuggingPriority(.required, for: .horizontal)
        settingsButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 70).isActive = true
        settingsButton.addAction(UIAction { [weak self] _ in self?.openSettings() }, for: .touchUpInside)

        let row = UIStackView(arrangedSubviews: [titleCol, headerStatusChip, settingsButton])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 10
        row.layoutMargins = UIEdgeInsets(top: 0, left: 0, bottom: 4, right: 0)
        row.isLayoutMarginsRelativeArrangement = true
        return row
    }

    private func buildClusterCard() -> SiqiCardView {
        let card = SiqiCardView()
        card.stack.spacing = 8

        let topRow = UIStackView(arrangedSubviews: [SiqiStyle.makeKicker("1 · 连接车机"), UIView(), clusterChip])
        topRow.alignment = .center
        card.stack.addArrangedSubview(topRow)

        clusterTitleLabel.font = .systemFont(ofSize: 19, weight: .bold)
        clusterTitleLabel.textColor = SiqiStyle.textPrimary
        clusterTitleLabel.numberOfLines = 0
        card.stack.addArrangedSubview(clusterTitleLabel)
        card.stack.addArrangedSubview(clusterDescLabel)

        clusterStatsLabel.isHidden = true
        card.stack.addArrangedSubview(clusterStatsLabel)

        wifiSSIDField.text = clusterWiFiSSID
        wifiSSIDField.placeholder = "当前仪表 Wi-Fi 名称（如 CQKY_xxxx，自动记住）"
        wifiSSIDField.autocorrectionType = .no
        wifiSSIDField.autocapitalizationType = .none
        wifiSSIDField.textColor = SiqiStyle.textPrimary
        wifiSSIDField.font = .systemFont(ofSize: 14)
        wifiSSIDField.backgroundColor = SiqiStyle.button.withAlphaComponent(0.75)
        wifiSSIDField.layer.cornerRadius = 11
        wifiSSIDField.layer.cornerCurve = .continuous
        wifiSSIDField.layer.borderWidth = 1
        wifiSSIDField.layer.borderColor = SiqiStyle.outline.cgColor
        wifiSSIDField.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 40))
        wifiSSIDField.leftViewMode = .always
        wifiSSIDField.clearButtonMode = .whileEditing
        wifiSSIDField.returnKeyType = .done
        wifiSSIDField.heightAnchor.constraint(equalToConstant: 42).isActive = true
        wifiSSIDField.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            let raw = self.wifiSSIDField.text ?? ""
            self.clusterWiFiSSID = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            self.updateWiFiSpaceWarning(for: raw)
        }, for: .editingChanged)
        wifiSSIDField.addTarget(wifiSSIDField, action: #selector(UIResponder.resignFirstResponder), for: .editingDidEndOnExit)
        card.stack.setCustomSpacing(10, after: clusterStatsLabel)
        card.stack.addArrangedSubview(wifiSSIDField)

        wifiSpaceWarningLabel.textColor = SiqiStyle.warning
        fixWifiSpaceButton.setContentHuggingPriority(.required, for: .horizontal)
        fixWifiSpaceButton.setContentCompressionResistancePriority(.required, for: .horizontal)
        fixWifiSpaceButton.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            let cleaned = Self.removingSpaces(self.wifiSSIDField.text ?? "")
            self.wifiSSIDField.text = cleaned
            self.clusterWiFiSSID = cleaned
            self.updateWiFiSpaceWarning(for: cleaned)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }, for: .touchUpInside)
        wifiSpaceWarningRow.axis = .horizontal
        wifiSpaceWarningRow.alignment = .center
        wifiSpaceWarningRow.spacing = 8
        wifiSpaceWarningRow.addArrangedSubview(wifiSpaceWarningLabel)
        wifiSpaceWarningRow.addArrangedSubview(fixWifiSpaceButton)
        wifiSpaceWarningRow.isHidden = !Self.containsSpace(clusterWiFiSSID)
        card.stack.addArrangedSubview(wifiSpaceWarningRow)

        connectWiFiButton.addAction(UIAction { [weak self] _ in
            self?.showWiFiConnectionGuide()
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(connectWiFiButton)

        wifiPresetsButton.addAction(UIAction { [weak self] _ in
            self?.openWiFiPresetsFromHome()
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(wifiPresetsButton)
        return card
    }

    private func buildCarPlayCard() -> SiqiCardView {
        let card = SiqiCardView()
        card.stack.spacing = 10

        audioChip.addAction(UIAction { [weak self] _ in self?.openSettings() }, for: .touchUpInside)
        let topRow = UIStackView(arrangedSubviews: [SiqiStyle.makeKicker("2 · 本机 CARPLAY"), UIView(), audioChip])
        topRow.alignment = .center
        card.stack.addArrangedSubview(topRow)

        sessionTitleLabel.font = .systemFont(ofSize: 19, weight: .bold)
        sessionTitleLabel.textColor = SiqiStyle.textPrimary
        sessionTitleLabel.numberOfLines = 0
        card.stack.addArrangedSubview(sessionTitleLabel)
        card.stack.addArrangedSubview(sessionDescLabel)

        startButton.addAction(UIAction { [weak self] _ in self?.toggleReceiver() }, for: .touchUpInside)
        card.stack.addArrangedSubview(startButton)

        fullscreenButton.addAction(UIAction { [weak self] _ in self?.showFullscreen() }, for: .touchUpInside)
        refreshFrameButton.addAction(UIAction { [weak self] _ in self?.forceClusterKeyframe() }, for: .touchUpInside)
        let actionRow = UIStackView(arrangedSubviews: [fullscreenButton, refreshFrameButton])
        actionRow.axis = .horizontal
        actionRow.distribution = .fillEqually
        actionRow.spacing = 10
        card.stack.addArrangedSubview(actionRow)

        inlineVideoContainer.isHidden = true
        inlineVideoContainer.clipsToBounds = true
        inlineVideoContainer.layer.cornerRadius = 14
        inlineVideoContainer.layer.cornerCurve = .continuous
        inlineVideoContainer.backgroundColor = .black
        videoSurface.translatesAutoresizingMaskIntoConstraints = false
        restoreInlineVideoSurface()
        updateInlineVideoAspect()
        card.stack.addArrangedSubview(inlineVideoContainer)

        Self.barControls.forEach { controlBar.addArrangedSubview(makeControlButton($0)) }
        controlBar.axis = .horizontal
        controlBar.distribution = .fillEqually
        controlBar.spacing = 6
        controlBar.isHidden = true
        card.stack.addArrangedSubview(controlBar)

        return card
    }

    private func buildControllerCard() -> SiqiCardView {
        let card = SiqiCardView()
        card.stack.spacing = 8

        let topRow = UIStackView(arrangedSubviews: [SiqiStyle.makeKicker("3 · 蓝牙按键控制器"), UIView(), controllerChip])
        topRow.alignment = .center
        card.stack.addArrangedSubview(topRow)

        controllerNameLabel.font = .systemFont(ofSize: 19, weight: .bold)
        controllerNameLabel.textColor = SiqiStyle.textPrimary
        controllerNameLabel.numberOfLines = 0
        card.stack.addArrangedSubview(controllerNameLabel)
        card.stack.addArrangedSubview(controllerKeyLogLabel)

        pairControllerButton.addAction(UIAction { [weak self] _ in
            self?.showBluetoothConnectionGuide()
        }, for: .touchUpInside)

        card.stack.setCustomSpacing(12, after: controllerKeyLogLabel)
        card.stack.addArrangedSubview(pairControllerButton)
        return card
    }

    private func makeControlButton(_ control: CarPlayControl) -> UIButton {
        var config = UIButton.Configuration.filled()
        config.baseBackgroundColor = SiqiStyle.button
        config.baseForegroundColor = SiqiStyle.textPrimary
        config.image = UIImage(systemName: control.symbol)
        config.cornerStyle = .capsule
        config.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold)
        let button = UIButton(configuration: config, primaryAction: UIAction { [weak self] _ in
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            self?.receiver.press(control)
        })
        button.heightAnchor.constraint(equalToConstant: 36).isActive = true
        button.accessibilityLabel = control.title
        return button
    }

    // MARK: - Wiring & Identity

    private func wireCallbacks() {
        receiver.onLog = { [weak self] in self?.log($0) }
        receiver.onControlConnected = { [weak self] in self?.sameDevice.didConnectControl() }
        receiver.onFailure = { [weak self] in self?.stop() }
        receiver.onSiqiSnapshot = { [weak self] snapshot in
            guard let self else { return }
            self.clusterSnapshot = snapshot
            self.refreshClusterCard()
            self.refreshHeaderAndCarPlayCard()
        }
        receiver.onCarUIRequested = { [weak self] in
            guard let self else { return }
            self.log("在 CarPlay 中点按了 KOVE 车标按钮")
            self.hideFullscreen()
            self.openSettings()
        }
        receiver.onVideo = { [weak self] data, codec in
            guard let self, self.running else { return }
            if UIApplication.shared.applicationState != .active {
                self.skippedLocalVideo = true
                return
            }
            do {
                try self.videoSurface.enqueue(accessUnit: data, codec: codec)
                if !self.displayedVideo {
                    self.displayedVideo = true
                    self.inlineVideoContainer.isHidden = false
                    self.controlBar.isHidden = false
                    self.clearStartupHint()
                    self.networkStartupTask?.cancel()
                    self.networkStartupTask = nil
                    self.log("CarPlay 视频流已就绪并送入显示层")
                    self.refreshHeaderAndCarPlayCard()
                }
            } catch {
                self.log("本机预览显示异常：\(error.localizedDescription)")
            }
        }

        videoSurface.onLog = { [weak self] in self?.log($0) }
        videoSurface.onKeyframeNeeded = { [weak self] reason in
            guard let self, UIApplication.shared.applicationState == .active else { return }
            self.receiver.requestKeyFrame(reason: reason)
        }
        videoSurface.onTouches = { [weak self] points in
            self?.receiver.touches(source: "local", points)
            self?.refreshFullscreenExitButton()
        }

        sameDevice.onLog = { [weak self] in self?.log($0) }
        sameDevice.onStage = { [weak self] message in
            guard let self, self.running, !self.displayedVideo else { return }
            self.sessionTitleLabel.text = "正在建立本机 CarPlay…"
            self.sessionTitleLabel.textColor = SiqiStyle.warning
            self.sessionDescLabel.text = message
            self.scheduleStartupHint()
        }
        sameDevice.onFinished = { [weak self] message in
            guard let self else { return }
            self.stop()
            self.sessionTitleLabel.text = message
            self.sessionTitleLabel.textColor = SiqiStyle.danger
            self.sessionDescLabel.text = "请核对上方仪表 Wi-Fi 名称是否与系统当前已连接热点完全一致（可进「设置 › 查看运行日志」排查）。"
        }

        handlebar.onLog = { [weak self] in self?.log($0) }
        handlebar.onAction = { [weak self] action, _ in
            guard let self, self.running else { return }
            self.receiver.press(action.carPlayControl)
        }
        handlebar.onSnapshotChanged = { [weak self] snap in
            guard let self else { return }
            self.controllerSnapshot = snap
            self.refreshControllerCard()
        }

        controlBrowser.onLog = { [weak self] in self?.log($0) }
        keepAlive.onLog = { [weak self] in self?.log($0) }
    }

    private func loadIdentity() {
        do {
            if let keychainIdentity = try? AccessoryIdentityStore.load() {
                identity = keychainIdentity
                log("已从 Keychain 载入 MFi 身份，ECDSA 自检通过")
            } else {
                identity = try AccessoryIdentityStore.loadFromBundle()
                log("已从内置资源载入 MFi 身份，ECDSA 自检通过")
            }
        } catch {
            identity = nil
            log("MFi 身份载入失败：\(error.localizedDescription)")
        }
    }

    // MARK: - Live UI Refresh

    private func refreshAllUI() {
        refreshClusterCard()
        refreshHeaderAndCarPlayCard()
        refreshControllerCard()
    }

    private func refreshClusterCard() {
        let wifiIP = ReceiverConfiguration.wifiIPv4Address()

        if running && clusterSnapshot.videoActive {
            clusterChip.apply(text: "投屏中", color: SiqiStyle.success)
            clusterTitleLabel.text = "车机仪表已连接"
            clusterTitleLabel.textColor = SiqiStyle.success
            let ipText = clusterSnapshot.peerIP.map { "（\($0)）" } ?? ""
            clusterDescLabel.text = "已建立无线投屏通道\(ipText)，仪表画面实时同步中"
            if clusterSnapshot.framesStreamed > 0 {
                let kb = clusterSnapshot.bytesStreamed / 1024
                clusterStatsLabel.text = "输出 \(liveStreamSize.width)×\(liveStreamSize.height) @ 30 FPS · 已推 \(clusterSnapshot.framesStreamed) 帧（\(kb) KB）"
                clusterStatsLabel.isHidden = false
            } else {
                clusterStatsLabel.text = "视频通道已连接，等待 CarPlay 首帧…"
                clusterStatsLabel.isHidden = false
            }
        } else if running && clusterSnapshot.controlConnected {
            clusterChip.apply(text: "已连接", color: SiqiStyle.accent)
            clusterTitleLabel.text = "车机仪表已握手"
            clusterTitleLabel.textColor = SiqiStyle.accent
            clusterDescLabel.text = "已与车机建立互联（17818），请在仪表端打开投屏界面"
            clusterStatsLabel.isHidden = true
        } else if running {
            clusterChip.apply(text: "监听中", color: SiqiStyle.warning)
            clusterTitleLabel.text = "等待思骑仪表接入"
            clusterTitleLabel.textColor = SiqiStyle.textPrimary
            let ipInfo = wifiIP.map { "本机 Wi-Fi IP：\($0)" } ?? "等待 Wi-Fi 地址"
            clusterDescLabel.text = "服务已监听 17818 / 15457 / 15456 · \(ipInfo)"
            clusterStatsLabel.isHidden = true
        } else {
            clusterChip.apply(text: "待机", color: SiqiStyle.textSecondary)
            clusterTitleLabel.text = "等待思骑仪表接入"
            clusterTitleLabel.textColor = SiqiStyle.textPrimary
            if let wifiIP {
                clusterDescLabel.text = "手机 Wi-Fi 已就绪（\(wifiIP)），直接点击下方启动无线 CarPlay 即可"
            } else {
                clusterDescLabel.text = "请开启车辆电源并连接思骑仪表 Wi-Fi（默认密码 12345678）"
            }
            clusterStatsLabel.isHidden = true
        }

        wifiSSIDField.isEnabled = !running
        wifiSSIDField.alpha = running ? 0.65 : 1.0
        wifiPresetsButton.isEnabled = !running
        wifiPresetsButton.alpha = running ? 0.65 : 1.0
        let store = SiqiWiFiPresetStore.shared
        let pwdHint = store.isOpenNetwork ? "无密码" : "密码 \(store.activePassword)"
        connectWiFiButton.setTitle(
            wifiIP == nil
                ? (store.isOpenNetwork ? "连仪表 Wi-Fi（开放网络）" : "复制密码 \(store.activePassword) · 连仪表 Wi-Fi")
                : (store.isOpenNetwork ? "当前预设：开放网络 · 查看连接指引" : "复制\(pwdHint) · 查看连接指引"),
            for: .normal
        )
        if let activePreset = store.selectedPreset, !activePreset.ssid.isEmpty {
            wifiPresetsButton.setTitle("Wi-Fi 预设：\(activePreset.name)（信道 \(activePreset.wifiChannel) · 共 \(store.presets.count) 个）  ›", for: .normal)
        } else {
            wifiPresetsButton.setTitle("Wi-Fi 预设 · 保存与切换多个仪表热点（\(store.presets.count)）  ›", for: .normal)
        }
    }

    private func refreshHeaderAndCarPlayCard() {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        let hasBluetooth = outputs.contains { $0.portType == .bluetoothA2DP || $0.portType == .bluetoothHFP || $0.portType == .bluetoothLE }
        audioChip.apply(
            text: hasBluetooth ? "音频: 蓝牙耳机" : "音频: iPhone 输出",
            color: hasBluetooth ? SiqiStyle.accent : SiqiStyle.warning
        )

        if identity == nil {
            headerStatusChip.apply(text: "证书缺失", color: SiqiStyle.danger)
            sessionTitleLabel.text = "未加载 MFi 身份"
            sessionTitleLabel.textColor = SiqiStyle.danger
            sessionDescLabel.text = "应用内缺少内置证书资源，请重新打包安装。"
            startButton.isEnabled = false
            startButton.setTitle("启动无线 CarPlay", for: .normal)
            startButton.apply(variant: .primary)
        } else if waitingForNetwork {
            headerStatusChip.apply(text: "等待 Wi-Fi", color: SiqiStyle.warning)
            sessionTitleLabel.text = "正在等待仪表 Wi-Fi 分配 IP…"
            sessionTitleLabel.textColor = SiqiStyle.warning
            sessionDescLabel.text = "请点上方「连接仪表 Wi-Fi」连入仪表热点（默认密码 12345678）。"
            startButton.isEnabled = true
            startButton.setTitle("取消连接", for: .normal)
            startButton.apply(variant: .danger)
        } else if running && displayedVideo {
            headerStatusChip.apply(text: clusterSnapshot.videoActive ? "投屏中" : "运行中", color: SiqiStyle.success)
            sessionTitleLabel.text = clusterSnapshot.videoActive ? "CarPlay 正在向仪表投屏" : "本机 CarPlay 已启动"
            sessionTitleLabel.textColor = SiqiStyle.success
            sessionDescLabel.text = isKeepAliveEnabled
                ? "锁屏后将暂停手机预览并保持仪表推流（当前预设：\(selectedBikePreset.shortTitle)）。"
                : "提示：当前未开启后台保活，锁屏后系统将挂起连接。"
            startButton.isEnabled = true
            startButton.setTitle("断开无线 CarPlay", for: .normal)
            startButton.apply(variant: .danger)
        } else if running {
            headerStatusChip.apply(text: "连接中", color: SiqiStyle.warning)
            sessionTitleLabel.text = "正在启动本机 CarPlay…"
            sessionTitleLabel.textColor = SiqiStyle.warning
            sessionDescLabel.text = "正在完成本机 iAP2 握手与 AirPlay 通道建立…"
            startButton.isEnabled = true
            startButton.setTitle("断开无线 CarPlay", for: .normal)
            startButton.apply(variant: .danger)
        } else {
            headerStatusChip.apply(text: "已就绪", color: SiqiStyle.success)
            sessionTitleLabel.text = "本机无线 CarPlay 就绪"
            sessionTitleLabel.textColor = SiqiStyle.textPrimary
            sessionDescLabel.text = "连上仪表 Wi-Fi 后一键启动；当前车型预设：\(selectedBikePreset.shortTitle)（可在设置切换）"
            startButton.isEnabled = true
            startButton.setTitle("启动无线 CarPlay", for: .normal)
            startButton.apply(variant: .primary)
        }

        fullscreenButton.isEnabled = running && displayedVideo
        refreshFrameButton.isEnabled = running
    }

    private func refreshControllerCard() {
        let snap = controllerSnapshot
        if snap.connected {
            controllerNameLabel.text = snap.controllerName ?? "蓝牙按键控制器"
            controllerNameLabel.textColor = SiqiStyle.success
            controllerChip.apply(text: "已连接", color: SiqiStyle.success)
        } else {
            controllerNameLabel.text = "未检测到蓝牙按键控制器"
            controllerNameLabel.textColor = SiqiStyle.textPrimary
            controllerChip.apply(text: "未连接", color: SiqiStyle.textSecondary)
        }

        if let last = snap.lastActionSummary {
            controllerKeyLogLabel.text = last
            controllerKeyLogLabel.textColor = SiqiStyle.accent
        } else {
            controllerKeyLogLabel.text = "支持蓝牙手把方向键 / 游戏手柄（上 / 下 / 确认 / 返回 / 长按 Siri）"
            controllerKeyLogLabel.textColor = SiqiStyle.textSecondary
        }
    }

    // MARK: - Start / Stop Session & Shortcuts Automation

    func handleIncomingURL(_ url: URL) {
        let host = (url.host ?? "").lowercased()
        let path = url.path.lowercased()
        if host == "stop" || path == "/stop" {
            log("收到快捷指令 motou://stop，正在停止无线 CarPlay")
            if isCarPlayRunning {
                stop(userInitiated: true)
            }
            return
        }
        if host == "start" || host.isEmpty || path == "/start" {
            log("收到快捷指令 motou://start，准备启动无线 CarPlay")
            manualStopSuppressesAutoStart = false
            navigationController?.popToRootViewController(animated: false)
            if running { return }
            guard let identity else {
                refreshHeaderAndCarPlayCard()
                return
            }
            if let address = ReceiverConfiguration.wifiIPv4Address() {
                if !clusterWiFiSSID.isEmpty && !Self.containsSpace(clusterWiFiSSID) {
                    let store = SiqiWiFiPresetStore.shared
                    startSessionWithResolvedWiFi(
                        address: address,
                        ssid: clusterWiFiSSID,
                        password: store.activePassword,
                        channel: store.activeChannel,
                        identity: identity
                    )
                } else {
                    resolveSSIDThenStart(address: address, identity: identity)
                }
            } else {
                waitForLocalNetwork()
            }
        }
    }

    private func toggleReceiver() {
        if running || waitingForNetwork {
            stop(userInitiated: true)
            return
        }
        manualStopSuppressesAutoStart = false
        if !restartingAutomatically { networkRestartCount = 0 }
        clearStartupHint()

        guard let identity else {
            refreshHeaderAndCarPlayCard()
            return
        }
        guard let address = ReceiverConfiguration.wifiIPv4Address() else {
            waitForLocalNetwork()
            return
        }

        // 本机 iAP2 无线握手（0x4301）必须携带当前已连接的 Wi-Fi 热点名称。
        // 优先使用已选预设/输入框的热点名；若为空则尝试系统自动读取，无权限时弹窗引导一次输入并保存预设。
        resolveSSIDThenStart(address: address, identity: identity)
    }

    private func scheduleAutoStartCheck(trigger: String) {
        autoStartWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            self?.maybeAutoStartIfEligible(trigger: trigger)
        }
        autoStartWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6, execute: item)
    }

    private func maybeAutoStartIfEligible(trigger: String) {
        guard isAutoStartOnWiFiEnabled,
              !running,
              !waitingForNetwork,
              !manualStopSuppressesAutoStart,
              UIApplication.shared.applicationState == .active,
              let identity,
              let address = ReceiverConfiguration.wifiIPv4Address() else { return }

        let ssid = (wifiSSIDField.text ?? clusterWiFiSSID).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !ssid.isEmpty, !Self.containsSpace(ssid) else { return }

        let store = SiqiWiFiPresetStore.shared
        log("检测到仪表 Wi-Fi「\(ssid)」已就绪（\(trigger)），自动启动本机 CarPlay")
        startSessionWithResolvedWiFi(
            address: address,
            ssid: ssid,
            password: store.activePassword,
            channel: store.activeChannel,
            identity: identity
        )
    }

    private func tryAutoFillSSIDIfEmpty() {
        guard clusterWiFiSSID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        NEHotspotNetwork.fetchCurrent { [weak self] network in
            guard let ssid = network?.ssid.trimmingCharacters(in: .whitespacesAndNewlines), !ssid.isEmpty else { return }
            DispatchQueue.main.async {
                guard let self, self.clusterWiFiSSID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                self.clusterWiFiSSID = ssid
                self.wifiSSIDField.text = ssid
                self.log("已自动识别当前仪表 Wi-Fi：\(ssid)")
            }
        }
    }

    private static func containsSpace(_ text: String) -> Bool {
        text.contains(" ") || text.contains("\u{3000}") || text.contains("\t")
    }

    private static func removingSpaces(_ text: String) -> String {
        text.replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "\u{3000}", with: "")
            .replacingOccurrences(of: "\t", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func updateWiFiSpaceWarning(for text: String) {
        wifiSpaceWarningRow.isHidden = !Self.containsSpace(text)
    }

    private func resolveSSIDThenStart(address: String, identity: AccessoryIdentity) {
        view.endEditing(true)
        let store = SiqiWiFiPresetStore.shared
        let rawInput = wifiSSIDField.text ?? clusterWiFiSSID
        let typedSSID = rawInput.trimmingCharacters(in: .whitespacesAndNewlines)
        if !typedSSID.isEmpty {
            if Self.containsSpace(rawInput) {
                confirmSpaceInSSID(address: address, ssid: typedSSID, identity: identity)
                return
            }
            clusterWiFiSSID = typedSSID
            wifiSSIDField.text = typedSSID
            updateWiFiSpaceWarning(for: typedSSID)
            startSessionWithResolvedWiFi(
                address: address,
                ssid: typedSSID,
                password: store.activePassword,
                channel: store.activeChannel,
                identity: identity
            )
            return
        }

        var completed = false
        let finish: (String) -> Void = { [weak self] detectedSSID in
            guard !completed else { return }
            completed = true
            guard let self else { return }
            let clean = detectedSSID.trimmingCharacters(in: .whitespacesAndNewlines)
            if !clean.isEmpty {
                self.clusterWiFiSSID = clean
                self.wifiSSIDField.text = clean
                self.updateWiFiSpaceWarning(for: clean)
                self.startSessionWithResolvedWiFi(
                    address: address,
                    ssid: clean,
                    password: store.activePassword,
                    channel: store.activeChannel,
                    identity: identity
                )
            } else {
                self.promptForClusterSSID(address: address, identity: identity)
            }
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            finish("")
        }
        NEHotspotNetwork.fetchCurrent { network in
            DispatchQueue.main.async {
                finish(network?.ssid ?? "")
            }
        }
    }

    private func confirmSpaceInSSID(address: String, ssid: String, identity: AccessoryIdentity) {
        let cleaned = Self.removingSpaces(ssid)
        let store = SiqiWiFiPresetStore.shared
        let alert = UIAlertController(
            title: "检测到 Wi-Fi 名称含空格",
            message: "当前填写的热点名为「\(ssid)」，中间包含空格（常见于输入法打完下划线后自动补空格）。\n\n思骑仪表热点通常不含空格（如「\(cleaned)」），多一个空格会导致 45 秒后提示「未收到 CarPlay 控制连接」。",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "去除空格并启动（推荐）", style: .default) { [weak self] _ in
            guard let self else { return }
            self.clusterWiFiSSID = cleaned
            self.wifiSSIDField.text = cleaned
            self.updateWiFiSpaceWarning(for: cleaned)
            self.startSessionWithResolvedWiFi(
                address: address,
                ssid: cleaned,
                password: store.activePassword,
                channel: store.activeChannel,
                identity: identity
            )
        })
        alert.addAction(UIAlertAction(title: "保留空格继续启动", style: .default) { [weak self] _ in
            guard let self else { return }
            self.clusterWiFiSSID = ssid
            self.wifiSSIDField.text = ssid
            self.updateWiFiSpaceWarning(for: ssid)
            self.startSessionWithResolvedWiFi(
                address: address,
                ssid: ssid,
                password: store.activePassword,
                channel: store.activeChannel,
                identity: identity
            )
        })
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        present(alert, animated: true)
    }

    private func promptForClusterSSID(address: String, identity: AccessoryIdentity) {
        let alert = UIAlertController(
            title: "请输入当前仪表 Wi-Fi 名称",
            message: "受 iOS / LiveContainer 限制无法自动读取当前 Wi-Fi 名称，但本机 iAP2 无线 CarPlay 握手必须携带热点名。\n\n仅需输入一次（如 CQKY_xxxx，注意不要有空格），后续会自动保存为预设。",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "例如 CQKY_xxxx"
            field.autocorrectionType = .no
            field.autocapitalizationType = .none
            field.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "确认并启动", style: .default) { [weak self] _ in
            guard let self else { return }
            let raw = alert.textFields?.first?.text ?? ""
            let input = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !input.isEmpty else {
                self.sessionTitleLabel.text = "请输入当前已连接的仪表 Wi-Fi 名称"
                self.sessionTitleLabel.textColor = SiqiStyle.danger
                return
            }
            if Self.containsSpace(raw) {
                self.confirmSpaceInSSID(address: address, ssid: input, identity: identity)
                return
            }
            let store = SiqiWiFiPresetStore.shared
            self.clusterWiFiSSID = input
            self.wifiSSIDField.text = input
            self.updateWiFiSpaceWarning(for: input)
            self.startSessionWithResolvedWiFi(
                address: address,
                ssid: input,
                password: store.activePassword,
                channel: store.activeChannel,
                identity: identity
            )
        })
        present(alert, animated: true)
    }

    private func startSessionWithResolvedWiFi(address: String, ssid: String, password: String, channel: UInt8, identity: AccessoryIdentity) {
        guard !running else { return }

        let plan = selectedBikePreset.resolvePlan(
            icon: selectedIconPreset,
            videoMode: selectedVideoMode,
            customProfile: customDisplayProfile
        )
        let config = ReceiverConfiguration(
            name: "摩投",
            manufacturer: selectedBikePreset.manufacturer,
            modelName: selectedBikePreset.modelName,
            address: address,
            ssid: ssid,
            password: password,
            width: plan.carPlayWidth,
            height: plan.carPlayHeight,
            maxFPS: 30,
            videoCodec: .h264,
            wifiChannel: channel,
            audioEnabled: false,
            rightHandDrive: false,
            deviceID: ReceiverConfiguration.persistentDeviceID(),
            siqiKoveEnabled: true,
            siqiKoveVideoMode: selectedVideoMode,
            siqiStreamWidth: plan.streamWidth,
            siqiStreamHeight: plan.streamHeight,
            disableSafeAreaSquareCorners: plan.disableSafeAreaSquareCorners,
            safeAreaTopPixels: plan.safeAreaTop,
            safeAreaBottomPixels: plan.safeAreaBottom,
            drawUIOutsideSafeArea: plan.drawOutside,
            viewportTopPixels: plan.viewportTop,
            viewportHeightPixels: plan.viewportHeight,
            overscanX: selectedBikePreset.overscanX,
            widthPhysicalMM: plan.widthPhysicalMM,
            oemIconPNG: Self.koveOEMIconPNG,
            oemIconLabel: selectedBikePreset.oemLabel
        )
        do {
            try config.validate(requiresLocalAddress: true, requiresWiFiCredentials: true)
        } catch {
            sessionTitleLabel.text = error.localizedDescription
            sessionTitleLabel.textColor = SiqiStyle.danger
            return
        }

        let pwdLabel = password.isEmpty ? "开放网络(无密码)" : (password == Self.defaultSiqiPassword ? "默认密码 12345678" : "自定义密码")
        log("使用仪表热点「\(ssid)」启动本机 CarPlay（地址 \(address)，\(pwdLabel)，信道 \(channel)，推流 \(plan.streamWidth)×\(plan.streamHeight)）")

        liveCanvasSize = (plan.carPlayWidth, plan.carPlayHeight)
        liveStreamSize = (plan.streamWidth, plan.streamHeight)
        updateInlineVideoAspect()
        sessionHasVehicleButton = CarPlayInfo.vehicleButtonSize(in: config) != nil
        if isKeepAliveEnabled { keepAlive.start() }
        running = true
        cancelWiFiLeaveStopTimer()
        startWiFiLeavePollingIfNeeded()
        handlebar.setSessionActive(true)
        clusterSnapshot = .idle
        UIApplication.shared.isIdleTimerDisabled = true
        refreshAllUI()

        let startupID = UUID()
        localStartupID = startupID
        receiver.onListening = { [weak self] port in
            guard let self, self.running, self.localStartupID == startupID else { return }
            var live = config
            live.port = port
            self.controlBrowser.start()
            self.sameDevice.start(configuration: live, identity: identity)
        }
        receiver.start(configuration: config, identity: identity)
        watchStartupAddress(address)
    }

    private func stop(userInitiated: Bool = false) {
        if userInitiated {
            manualStopSuppressesAutoStart = true
        }
        localStartupID = nil
        networkStartupTask?.cancel()
        networkStartupTask = nil
        autoStartWorkItem?.cancel()
        autoStartWorkItem = nil
        cancelWiFiLeaveStopTimer()
        waitingForNetwork = false
        clearStartupHint()
        running = false
        handlebar.setSessionActive(false)
        displayedVideo = false
        skippedLocalVideo = false
        sessionHasVehicleButton = false
        clusterSnapshot = .idle
        keepAlive.stop()
        hideFullscreen()
        videoSurface.reset()
        inlineVideoContainer.isHidden = true
        controlBar.isHidden = true
        receiver.onListening = nil
        sameDevice.stop()
        controlBrowser.stop()
        receiver.stop()
        UIApplication.shared.isIdleTimerDisabled = false
        log("无线 CarPlay 已停止")
        refreshAllUI()
    }

    private func startWiFiLeavePollingIfNeeded() {
        wifiLeavePollTimer?.invalidate()
        let timer = Timer(timeInterval: 15, repeats: true) { [weak self] _ in
            self?.evaluateWiFiLeaveAutoStop()
        }
        RunLoop.main.add(timer, forMode: .common)
        wifiLeavePollTimer = timer
    }

    private func cancelWiFiLeaveStopTimer() {
        wifiLeaveStopWorkItem?.cancel()
        wifiLeaveStopWorkItem = nil
        wifiLeavePollTimer?.invalidate()
        wifiLeavePollTimer = nil
    }

    private func evaluateWiFiLeaveAutoStop() {
        guard running, isAutoStopOnWiFiLeaveEnabled else {
            wifiLeaveStopWorkItem?.cancel()
            wifiLeaveStopWorkItem = nil
            return
        }
        let hasWiFi = ReceiverConfiguration.wifiIPv4Address() != nil
        if hasWiFi || clusterSnapshot.videoActive {
            if wifiLeaveStopWorkItem != nil {
                wifiLeaveStopWorkItem?.cancel()
                wifiLeaveStopWorkItem = nil
                log("仪表 Wi-Fi 已恢复，取消 3 分钟自动停止倒计时")
            }
            return
        }
        guard wifiLeaveStopWorkItem == nil else { return }
        log("检测到已离开仪表 Wi-Fi，若 3 分钟内未重连将自动停止释放后台")
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.running, self.isAutoStopOnWiFiLeaveEnabled else { return }
            guard ReceiverConfiguration.wifiIPv4Address() == nil && !self.clusterSnapshot.videoActive else { return }
            self.log("离开仪表 Wi-Fi 已满 3 分钟，自动停止无线 CarPlay 并释放后台")
            self.stop(userInitiated: false)
        }
        wifiLeaveStopWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 180, execute: work)
    }

    private func waitForLocalNetwork() {
        waitingForNetwork = true
        refreshHeaderAndCarPlayCard()
        networkStartupTask?.cancel()
        networkStartupTask = Task { [weak self] in
            var candidate: String?
            for _ in 0..<20 {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard !Task.isCancelled, let self else { return }
                let address = ReceiverConfiguration.wifiIPv4Address()
                if let address, address == candidate, UIApplication.shared.applicationState == .active {
                    self.waitingForNetwork = false
                    self.restartingAutomatically = self.networkRestartCount > 0
                    self.toggleReceiver()
                    self.restartingAutomatically = false
                    return
                }
                candidate = address
            }
            guard let self else { return }
            self.stop()
            self.sessionTitleLabel.text = "未获取到仪表 Wi-Fi，请点上方跳转连接"
            self.sessionTitleLabel.textColor = SiqiStyle.danger
        }
    }

    private func watchStartupAddress(_ initialAddress: String) {
        networkStartupTask?.cancel()
        networkStartupTask = Task { [weak self] in
            var candidate: String?
            var stableSamples = 0
            var lostAddress = false
            for _ in 0..<90 {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard !Task.isCancelled, let self, self.running, !self.displayedVideo else { return }
                let address = ReceiverConfiguration.wifiIPv4Address()
                self.refreshClusterCard()
                if address == nil { lostAddress = true }
                if address != candidate { candidate = address; stableSamples = 0 }
                stableSamples += 1
                guard let address, address != initialAddress || lostAddress, stableSamples >= 3,
                      self.networkRestartCount < 2, UIApplication.shared.applicationState == .active else { continue }
                self.networkRestartCount += 1
                self.log("启动期间 Wi-Fi 恢复，重新发起本机 CarPlay 连接")
                self.stop()
                self.restartingAutomatically = true
                self.toggleReceiver()
                self.restartingAutomatically = false
                return
            }
        }
    }

    private func clearStartupHint() {
        startupHintTask?.cancel()
        startupHintTask = nil
        startupHintGeneration = UUID()
    }

    private func scheduleStartupHint() {
        guard startupHintTask == nil else { return }
        let gen = startupHintGeneration
        let task = DispatchWorkItem { [weak self] in
            guard let self, self.startupHintGeneration == gen, self.running, !self.displayedVideo else { return }
            self.sessionDescLabel.text = "若等待较久，请核对上方仪表 Wi-Fi 名称是否与当前连接热点完全一致（区分大小写），或开关一次系统 Wi-Fi 后重试。"
        }
        startupHintTask = task
        DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: task)
    }

    // MARK: - Settings & System Navigation

    func applyDisplaySettingsAndRestartIfNeeded() {
        refreshAllUI()
        updateInlineVideoAspect()
        guard running else { return }
        log("应用新的仪表画面参数，正在重启 CarPlay 会话…")
        stop()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            guard let self, !self.running else { return }
            self.restartingAutomatically = true
            self.toggleReceiver()
            self.restartingAutomatically = false
        }
    }

    func forceClusterKeyframe() {
        guard running else { return }
        receiver.forceSiqiKeyframe()
        log("已手动刷新思骑仪表关键帧")
    }

    func triggerSimulatedKey(_ action: SiqiKeyAction) {
        handlebar.triggerSimulated(action)
    }

    func didUpdateWiFiPresets() {
        SiqiWiFiPresetStore.shared.reload()
        wifiSSIDField.text = clusterWiFiSSID
        updateWiFiSpaceWarning(for: clusterWiFiSSID)
        refreshAllUI()
    }

    private func openWiFiPresetsFromHome() {
        let vc = SiqiWiFiPresetsViewController { [weak self] in
            self?.didUpdateWiFiPresets()
        }
        navigationController?.pushViewController(vc, animated: true)
    }

    private func showWiFiConnectionGuide() {
        let store = SiqiWiFiPresetStore.shared
        let pwd = store.activePassword
        let title: String
        if store.isOpenNetwork {
            title = "当前预设为开放热点（无需密码）"
        } else {
            UIPasteboard.general.string = pwd
            title = "已复制热点密码：\(pwd)"
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        let alert = UIAlertController(
            title: title,
            message: "受系统限制，应用无法直接跳转系统「无线局域网」页面。\n\n请从屏幕右上角下拉「控制中心」（长按 Wi-Fi 图标）或手动打开系统「设置 › 无线局域网」连接思骑仪表热点，连上后返回本页（开启「自动启动」后可自动拉起，或点击下方「Wi-Fi 预设」切换保存的热点）。",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        present(alert, animated: true)
    }

    private func showBluetoothConnectionGuide() {
        let alert = UIAlertController(
            title: "连接蓝牙按键控制器",
            message: "受系统限制，应用无法直接跳转系统「蓝牙」页面。\n\n请从屏幕右上角下拉「控制中心」或手动打开系统「设置 › 蓝牙」配对手把蓝牙方向键 / 游戏手柄控制器，配对后返回本页会自动识别。",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        present(alert, animated: true)
    }

    func showGuideSheet(from presenter: UIViewController) {
        let msg =
            "一、连接仪表同时保住 5G 上网：\n" +
            "1. 前往系统设置连上思骑仪表热点（如 CQKY_xxxx，默认密码 12345678）。\n" +
            "2. 点该 Wi-Fi 右侧「i」→「配置 IP」改为「手动」。\n" +
            "3. 填好与仪表同网段的 IP 地址和子网掩码（如 255.255.255.0），将「路由器」和「DNS」全部留空并存储。\n" +
            "4. 此时高德/音乐继续走 5G，思骑仪表走局域网 Wi-Fi。\n\n" +
            "二、手机息屏放兜里继续投屏：\n" +
            "1. 首次连通后，前往 iPhone「设置 › 通用 › CarPlay › 摩投」。\n" +
            "2. 打开「锁定时允许 CarPlay」。\n" +
            "3. 保持本 App 设置中「锁屏与后台继续运行」开启即可。\n\n" +
            "三、进阶：快捷指令全自动拉起（解锁状态）：\n" +
            "1. 打开 iPhone 自带「快捷指令 › 自动化 › + › 无线局域网」。\n" +
            "2. 选中你的仪表 Wi-Fi（勾选「加入网络」+「立即运行」）。\n" +
            "3. 添加操作「打开 URL」→ 填入 motou://start → 完成。\n" +
            "4. 以后解锁状态下连上仪表 Wi-Fi 即可自动拉起并投屏；骑完离开仪表 Wi-Fi 满 3 分钟将自动停止释放后台。"
        let alert = UIAlertController(title: "5G 联网、息屏与自动化教程", message: msg, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "复制 motou://start", style: .default) { _ in
            UIPasteboard.general.string = "motou://start"
        })
        alert.addAction(UIAlertAction(title: "知道了", style: .cancel))
        presenter.present(alert, animated: true)
    }

    private func openSettings() {
        let vc = SiqiSettingsViewController()
        vc.delegate = self
        navigationController?.pushViewController(vc, animated: true)
    }

    // MARK: - Inline & Fullscreen Video Preview

    private func updateInlineVideoAspect() {
        let plan = selectedBikePreset.resolvePlan(
            icon: selectedIconPreset,
            videoMode: selectedVideoMode,
            customProfile: customDisplayProfile
        )
        let size: (width: Int, height: Int) = running
            ? liveCanvasSize
            : (width: plan.carPlayWidth, height: plan.carPlayHeight)
        videoHeightConstraint?.isActive = false
        let c = inlineVideoContainer.heightAnchor.constraint(
            equalTo: inlineVideoContainer.widthAnchor,
            multiplier: CGFloat(size.height) / CGFloat(size.width)
        )
        c.priority = .defaultHigh
        c.isActive = true
        videoHeightConstraint = c
    }

    private func restoreInlineVideoSurface() {
        inlineVideoContainer.addSubview(videoSurface)
        inlineVideoConstraints = [
            videoSurface.leadingAnchor.constraint(equalTo: inlineVideoContainer.leadingAnchor),
            videoSurface.trailingAnchor.constraint(equalTo: inlineVideoContainer.trailingAnchor),
            videoSurface.topAnchor.constraint(equalTo: inlineVideoContainer.topAnchor),
            videoSurface.bottomAnchor.constraint(equalTo: inlineVideoContainer.bottomAnchor)
        ]
        NSLayoutConstraint.activate(inlineVideoConstraints)
    }

    private func showFullscreen() {
        guard displayedVideo, fullscreenContainer == nil else { return }
        view.endEditing(true)
        NSLayoutConstraint.deactivate(inlineVideoConstraints)
        videoSurface.removeFromSuperview()

        let container = UIView()
        container.backgroundColor = .black
        container.translatesAutoresizingMaskIntoConstraints = false
        fullscreenContainer = container
        view.addSubview(container)
        container.addSubview(videoSurface)
        videoSurface.translatesAutoresizingMaskIntoConstraints = false

        fullscreenConstraints = [
            container.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            container.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            container.topAnchor.constraint(equalTo: view.topAnchor),
            container.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            videoSurface.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            videoSurface.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            videoSurface.topAnchor.constraint(equalTo: container.topAnchor),
            videoSurface.bottomAnchor.constraint(equalTo: container.bottomAnchor)
        ]
        NSLayoutConstraint.activate(fullscreenConstraints)
        addFullscreenExitButton(to: container)
        setNeedsStatusBarAppearanceUpdate()
        setNeedsUpdateOfHomeIndicatorAutoHidden()
    }

    private func addFullscreenExitButton(to container: UIView) {
        let exit = UIButton(type: .system)
        let icon = UIImage(systemName: "arrow.down.right.and.arrow.up.left",
                           withConfiguration: UIImage.SymbolConfiguration(pointSize: 16, weight: .medium))
        exit.setImage(icon, for: .normal)
        exit.tintColor = .white
        exit.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        exit.layer.cornerRadius = 22
        exit.layer.borderWidth = 1
        exit.layer.borderColor = UIColor.white.withAlphaComponent(0.35).cgColor
        exit.accessibilityLabel = "退出全屏"
        exit.addAction(UIAction { [weak self] _ in self?.hideFullscreen() }, for: .touchUpInside)
        exit.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(exit)
        fullscreenExitButton = exit

        let c = [
            exit.widthAnchor.constraint(equalToConstant: 44),
            exit.heightAnchor.constraint(equalToConstant: 44),
            exit.topAnchor.constraint(equalTo: container.safeAreaLayoutGuide.topAnchor, constant: 10),
            exit.leadingAnchor.constraint(equalTo: container.safeAreaLayoutGuide.leadingAnchor, constant: 10)
        ]
        NSLayoutConstraint.activate(c)
        fullscreenConstraints += c
        refreshFullscreenExitButton()
    }

    private func refreshFullscreenExitButton() {
        guard let exit = fullscreenExitButton else { return }
        if exit.alpha < 1 {
            UIView.animate(withDuration: 0.18, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
                exit.alpha = 1
            }
        }
        if let fullscreenFadeTimer {
            fullscreenFadeTimer.fireDate = Date(timeIntervalSinceNow: 4)
            return
        }
        let timer = Timer(timeInterval: 4, repeats: false) { [weak self, weak exit] _ in
            self?.fullscreenFadeTimer = nil
            UIView.animate(withDuration: 0.3, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction]) {
                exit?.alpha = 0.3
            }
        }
        fullscreenFadeTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func hideFullscreen() {
        guard let container = fullscreenContainer else { return }
        fullscreenFadeTimer?.invalidate()
        fullscreenFadeTimer = nil
        fullscreenExitButton = nil
        UIView.performWithoutAnimation {
            NSLayoutConstraint.deactivate(fullscreenConstraints)
            fullscreenConstraints = []
            videoSurface.removeFromSuperview()
            container.removeFromSuperview()
            fullscreenContainer = nil
            restoreInlineVideoSurface()
            setNeedsStatusBarAppearanceUpdate()
            setNeedsUpdateOfHomeIndicatorAutoHidden()
            view.layoutIfNeeded()
        }
    }

    // MARK: - Lifecycle & Appearance

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if #unavailable(iOS 17.0) { applyAppearance() }
    }

    private func applyAppearance() {
        guard UIApplication.shared.applicationState != .background else { return }
        let night: Bool
        switch selectedAppearance {
        case .system: night = traitCollection.userInterfaceStyle == .dark
        case .light: night = false
        case .dark: night = true
        }
        receiver.setNightMode(night)
    }

    @objc private func audioRouteChanged() {
        DispatchQueue.main.async { [weak self] in
            self?.refreshHeaderAndCarPlayCard()
        }
    }

    @objc private func willResignActive() {
        guard running else { return }
        if isKeepAliveEnabled { keepAlive.start() }
        receiver.setLocalVideoEnabled(false)
        skippedLocalVideo = true
        videoSurface.suspendForExternalOutput()
        log("思骑仪表继续推流，手机端预览已暂停（锁屏后保持投屏）")
    }

    @objc private func becameActive() {
        receiver.setLocalVideoEnabled(true)
        if running {
            UIApplication.shared.isIdleTimerDisabled = true
            videoSurface.resumeAfterBackground(skippedFrames: skippedLocalVideo)
            skippedLocalVideo = false
            if isKeepAliveEnabled { keepAlive.start() }
        }
        controllerSnapshot = handlebar.currentSnapshot()
        tryAutoFillSSIDIfEmpty()
        refreshAllUI()
        evaluateWiFiLeaveAutoStop()
        scheduleAutoStartCheck(trigger: "回到前台")
    }

    @objc private func foregrounded() {
        manualStopSuppressesAutoStart = false
        controllerSnapshot = handlebar.currentSnapshot()
        refreshAllUI()
        if running {
            DispatchQueue.main.async { [weak self] in self?.applyAppearance() }
        }
    }

    @objc private func backgrounded() {
        guard running else { return }
        if isKeepAliveEnabled, !keepAlive.isRunning, keepAlive.start() {
            log("后台保持在进入后台时重试成功")
        }
        if keepAlive.isRunning {
            log("App 已进入后台，摩投 继续向仪表推流")
        } else {
            log("未开启后台保持，iOS 将挂起应用，已停止连接")
            stop()
        }
    }

    private func log(_ message: String) {
        let ts = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        lines.append("\(ts)  \(message)")
        if lines.count > 200 { lines.removeFirst(lines.count - 200) }
        NSLog("[MoTou] %@", message)
    }

    /// 优先加载打包的凯越 KOVE 车标（120×120 PNG），兜底绘制纯色圆角图标。
    private static let koveOEMIconPNG: Data? = {
        if let url = Bundle.main.url(forResource: "kove-badge", withExtension: "png"),
           let data = try? Data(contentsOf: url), !data.isEmpty {
            return data
        }
        let size = CGSize(width: 120, height: 120)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).pngData { ctx in
            let colors = [
                UIColor(red: 36 / 255, green: 90 / 255, blue: 156 / 255, alpha: 1).cgColor,
                UIColor(red: 18 / 255, green: 45 / 255, blue: 88 / 255, alpha: 1).cgColor
            ]
            if let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 1]) {
                ctx.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            }
            let attrs: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 30, weight: .heavy),
                .foregroundColor: UIColor.white
            ]
            let text = NSString(string: "KOVE")
            let textSize = text.size(withAttributes: attrs)
            text.draw(at: CGPoint(x: (size.width - textSize.width) / 2, y: (size.height - textSize.height) / 2), withAttributes: attrs)
        }
    }()
}
