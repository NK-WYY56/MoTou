import AVKit
import UIKit

/// Bridge between Settings UI and ReceiverViewController runtime state.
protocol SiqiSettingsDelegate: AnyObject {
    var isCarPlayRunning: Bool { get }
    var isMFiIdentityLoaded: Bool { get }
    var selectedBikePreset: SiqiBikePreset { get set }
    var customDisplayProfile: SiqiCustomDisplayProfile { get set }
    var selectedIconPreset: SiqiIconSizePreset { get set }
    var selectedVideoMode: SiqiVideoMode { get set }
    var selectedAppearance: ReceiverConfiguration.Appearance { get set }
    var isKeepAliveEnabled: Bool { get set }
    var isAutoStartOnWiFiEnabled: Bool { get set }
    var isAutoStopOnWiFiLeaveEnabled: Bool { get set }
    var currentLogLines: [String] { get }
    var lastControllerActionText: String? { get }

    func applyDisplaySettingsAndRestartIfNeeded()
    func forceClusterKeyframe()
    func triggerSimulatedKey(_ action: SiqiKeyAction)
    func showGuideSheet(from presenter: UIViewController)
    func didUpdateWiFiPresets()
}

/// Unified settings screen mirroring Android 摩投 SettingsActivity:
/// 1) 仪表画面参数 (二级菜单切换车型仪表预设 [当前仅思骑出行/凯越450RR] + 图标大小 + 编码模式 + 外观)
/// 2) 连接与后台模式 (锁屏保活)
/// 3) 音频与 Siri
/// 4) 骑行与调试工具 (刷新画面 + 日志 + 5G/息屏教程 + 蓝牙手把按键模拟测试)
/// 5) 关于与开源项目声明 (含 CarWithin / DiPlay / xcertplay / DiAuto / PlayPort / BigInt 致谢)
final class SiqiSettingsViewController: UIViewController {
    weak var delegate: SiqiSettingsDelegate?

    private let scrollView = UIScrollView()
    private let contentStack = UIStackView()

    // Display preset controls
    private let bikePresetMenuButton = SiqiButton(title: "", variant: .secondary, fontSize: 14, height: 46)
    private let bikeDescLabel = SiqiStyle.makeSecondaryLabel("", size: 12)
    private var iconButtons: [SiqiButton] = []
    private var videoModeButtons: [SiqiButton] = []
    private var appearanceButtons: [SiqiButton] = []
    private let applyButton = SiqiButton(title: "应用当前画面参数", variant: .primary, fontSize: 14, height: 46)
    private let applyHintLabel = SiqiStyle.makeSecondaryLabel("", size: 12)

    // Connection & background controls
    private let wifiPresetManageButton = SiqiButton(title: "仪表 Wi-Fi 预设管理  ›", variant: .secondary, fontSize: 13, height: 44)
    private let wifiPresetSummaryLabel = SiqiStyle.makeSecondaryLabel("", size: 12)
    private let autoStartSwitch = UISwitch()
    private let autoStopSwitch = UISwitch()
    private let keepAliveSwitch = UISwitch()

    // Audio controls
    private let audioRouteLabel = SiqiStyle.makeSecondaryLabel("", size: 13)
    private let audioRoutePicker = AVRoutePickerView()

    // Tools & key simulator
    private let settingsKeyLogLabel = SiqiStyle.makeSecondaryLabel("按键监听就绪", size: 12)
    private let diagInfoLabel = SiqiStyle.makeSecondaryLabel("", size: 12)

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = SiqiStyle.windowBackground
        buildLayout()
        refreshState()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(audioRouteChanged),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: false)
        refreshState()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    private func buildLayout() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.alwaysBounceVertical = true
        view.addSubview(scrollView)

        contentStack.axis = .vertical
        contentStack.spacing = 14
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(contentStack)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            contentStack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 16),
            contentStack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -28),
            contentStack.leadingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.leadingAnchor, constant: 18),
            contentStack.trailingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.trailingAnchor, constant: -18)
        ])

        contentStack.addArrangedSubview(buildTopBar())
        contentStack.addArrangedSubview(buildDisplayCard())
        contentStack.addArrangedSubview(buildConnectionCard())
        contentStack.addArrangedSubview(buildAudioCard())
        contentStack.addArrangedSubview(buildToolsCard())
        contentStack.addArrangedSubview(buildCreditsCard())
    }

    private func buildTopBar() -> UIView {
        let backBtn = SiqiButton(title: "‹ 返回", variant: .secondary, fontSize: 14, height: 40)
        backBtn.setContentHuggingPriority(.required, for: .horizontal)
        backBtn.addAction(UIAction { [weak self] _ in
            self?.navigationController?.popViewController(animated: true)
        }, for: .touchUpInside)

        let titleLabel = UILabel()
        titleLabel.text = "设置"
        titleLabel.font = .systemFont(ofSize: 22, weight: .bold)
        titleLabel.textColor = SiqiStyle.textPrimary

        let bar = UIStackView(arrangedSubviews: [backBtn, titleLabel, UIView()])
        bar.axis = .horizontal
        bar.alignment = .center
        bar.spacing = 14
        bar.layoutMargins = UIEdgeInsets(top: 0, left: 0, bottom: 4, right: 0)
        bar.isLayoutMarginsRelativeArrangement = true
        return bar
    }

    private func buildDisplayCard() -> SiqiCardView {
        let card = SiqiCardView()
        card.stack.spacing = 10
        card.stack.addArrangedSubview(SiqiStyle.makeKicker("仪表画面参数"))

        // 车型仪表预设：点击进入二级菜单切换（凯越 450RR / 自定义分辨率）
        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("车型仪表预设"))
        bikePresetMenuButton.contentHorizontalAlignment = .fill
        bikePresetMenuButton.addAction(UIAction { [weak self] _ in
            self?.openBikePresetSubmenu()
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(bikePresetMenuButton)
        card.stack.addArrangedSubview(bikeDescLabel)

        // 手动调节分辨率 / 保存与导出（解决其他思骑仪表黑屏与拉伸）
        let tunerBtn = SiqiButton(title: "手动调节分辨率 / 保存与导出（黑屏适配）  ›", variant: .secondary, fontSize: 13, height: 42)
        tunerBtn.addAction(UIAction { [weak self] _ in
            self?.openResolutionTuner()
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(tunerBtn)

        // Icon size (3 options matching Android: 1024×448 / 1280×560 / 1536×672)
        card.stack.addArrangedSubview(spacer(4))
        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("CarPlay 图标大小"))
        iconButtons = SiqiIconSizePreset.allCases.map { preset in
            let btn = SiqiButton(title: preset.title, variant: .segmented(selected: false), fontSize: 12, height: 40)
            btn.addAction(UIAction { [weak self] _ in
                self?.delegate?.selectedIconPreset = preset
                self?.refreshState()
            }, for: .touchUpInside)
            return btn
        }
        card.stack.addArrangedSubview(horizontalRow(iconButtons, spacing: 8))

        // Stream encoding mode
        card.stack.addArrangedSubview(spacer(4))
        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("视频推流模式"))
        videoModeButtons = SiqiVideoMode.allCases.map { mode in
            let btn = SiqiButton(title: mode.title, variant: .segmented(selected: false), fontSize: 12, height: 40)
            btn.addAction(UIAction { [weak self] _ in
                self?.delegate?.selectedVideoMode = mode
                self?.refreshState()
            }, for: .touchUpInside)
            return btn
        }
        card.stack.addArrangedSubview(horizontalRow(videoModeButtons, spacing: 8))

        // CarPlay Day/Night appearance
        card.stack.addArrangedSubview(spacer(4))
        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("CarPlay 外观"))
        appearanceButtons = ReceiverConfiguration.Appearance.allCases.map { appearance in
            let btn = SiqiButton(title: appearance.title, variant: .segmented(selected: false), fontSize: 12, height: 38)
            btn.addAction(UIAction { [weak self] _ in
                self?.delegate?.selectedAppearance = appearance
                self?.refreshState()
            }, for: .touchUpInside)
            return btn
        }
        card.stack.addArrangedSubview(horizontalRow(appearanceButtons, spacing: 8))

        card.stack.addArrangedSubview(spacer(4))
        applyButton.addAction(UIAction { [weak self] _ in
            guard let self, let delegate = self.delegate else { return }
            let wasRunning = delegate.isCarPlayRunning
            delegate.applyDisplaySettingsAndRestartIfNeeded()
            self.applyHintLabel.text = wasRunning
                ? "已应用参数，正在重连 CarPlay 使新画面立即生效…"
                : "已保存画面参数，下次启动无线 CarPlay 生效。"
            self.applyHintLabel.textColor = SiqiStyle.success
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(applyButton)
        card.stack.addArrangedSubview(applyHintLabel)
        return card
    }

    private func buildConnectionCard() -> SiqiCardView {
        let card = SiqiCardView()
        card.stack.spacing = 12
        card.stack.addArrangedSubview(SiqiStyle.makeKicker("连接与自动化模式"))

        wifiPresetManageButton.contentHorizontalAlignment = .fill
        wifiPresetManageButton.addAction(UIAction { [weak self] _ in
            self?.openWiFiPresetsManager()
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(wifiPresetManageButton)
        card.stack.addArrangedSubview(wifiPresetSummaryLabel)

        SiqiStyle.styleSwitch(autoStartSwitch)
        autoStartSwitch.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.delegate?.isAutoStartOnWiFiEnabled = self.autoStartSwitch.isOn
        }, for: .valueChanged)
        card.stack.addArrangedSubview(makeSwitchRow(
            title: "检测到仪表 Wi-Fi 自动启动 CarPlay",
            subtitle: "App 处于前台（或通过 iPhone 快捷指令 motou://start 拉起）且已连上有效仪表 Wi-Fi 时自动启动无线 CarPlay。",
            toggle: autoStartSwitch
        ))

        SiqiStyle.styleSwitch(autoStopSwitch)
        autoStopSwitch.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.delegate?.isAutoStopOnWiFiLeaveEnabled = self.autoStopSwitch.isOn
        }, for: .valueChanged)
        card.stack.addArrangedSubview(makeSwitchRow(
            title: "离开仪表 Wi-Fi 满 3 分钟自动停止",
            subtitle: "骑完熄火下车后，若断开仪表 Wi-Fi 超过 3 分钟未恢复，自动停止 CarPlay 并释放后台保活。",
            toggle: autoStopSwitch
        ))

        SiqiStyle.styleSwitch(keepAliveSwitch)
        keepAliveSwitch.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.delegate?.isKeepAliveEnabled = self.keepAliveSwitch.isOn
        }, for: .valueChanged)
        card.stack.addArrangedSubview(makeSwitchRow(
            title: "锁屏与后台继续运行",
            subtitle: "手机息屏放兜里或切出应用时保持 CarPlay 推流。请在系统「设置 › 通用 › CarPlay › 摩投」开启「锁定时允许 CarPlay」。",
            toggle: keepAliveSwitch
        ))
        return card
    }

    private func buildAudioCard() -> SiqiCardView {
        let card = SiqiCardView()
        card.stack.spacing = 10
        card.stack.addArrangedSubview(SiqiStyle.makeKicker("音频与 SIRI"))

        let title = UILabel()
        title.text = "声音与 Siri 走 iPhone 蓝牙耳机"
        title.font = .systemFont(ofSize: 16, weight: .bold)
        title.textColor = SiqiStyle.textPrimary
        card.stack.addArrangedSubview(title)

        let desc = SiqiStyle.makeSecondaryLabel(
            "本机 CarPlay 模式下，音乐、导航语音、电话与 Siri 均直接由 iPhone 输出至已连接的头盔蓝牙耳机，不占用仪表通道。",
            size: 13
        )
        card.stack.addArrangedSubview(desc)

        audioRoutePicker.prioritizesVideoDevices = false
        audioRoutePicker.tintColor = SiqiStyle.accent
        audioRoutePicker.activeTintColor = SiqiStyle.success
        audioRoutePicker.backgroundColor = SiqiStyle.button
        audioRoutePicker.layer.cornerRadius = 12
        audioRoutePicker.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            audioRoutePicker.widthAnchor.constraint(equalToConstant: 44),
            audioRoutePicker.heightAnchor.constraint(equalToConstant: 40)
        ])

        let routeRow = UIStackView(arrangedSubviews: [audioRouteLabel, audioRoutePicker])
        routeRow.axis = .horizontal
        routeRow.alignment = .center
        routeRow.spacing = 10
        card.stack.addArrangedSubview(routeRow)
        return card
    }

    private func buildToolsCard() -> SiqiCardView {
        let card = SiqiCardView()
        card.stack.spacing = 10
        card.stack.addArrangedSubview(SiqiStyle.makeKicker("骑行与调试工具"))

        let refreshBtn = SiqiButton(title: "刷新仪表画面", variant: .secondary, fontSize: 13, height: 42)
        refreshBtn.addAction(UIAction { [weak self] _ in
            self?.delegate?.forceClusterKeyframe()
            self?.showSimpleAlert(title: "已刷新仪表画面", message: "已向 CarPlay 与思骑仪表请求即时 IDR 关键帧。")
        }, for: .touchUpInside)

        let logBtn = SiqiButton(title: "查看运行日志", variant: .secondary, fontSize: 13, height: 42)
        logBtn.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            let vc = SiqiLogViewController(linesProvider: { [weak self] in self?.delegate?.currentLogLines ?? [] })
            self.navigationController?.pushViewController(vc, animated: true)
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(horizontalRow([refreshBtn, logBtn], spacing: 10))

        let guideBtn = SiqiButton(title: "5G 联网与息屏投屏设置教程", variant: .secondary, fontSize: 13, height: 42)
        guideBtn.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.delegate?.showGuideSheet(from: self)
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(guideBtn)

        // Handlebar key simulator matching Android activity_settings.xml
        card.stack.addArrangedSubview(spacer(4))
        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("蓝牙手把按键模拟测试"))
        let btnUp = SiqiButton(title: "▲ 上", variant: .secondary, fontSize: 12, height: 40)
        let btnDown = SiqiButton(title: "▼ 下", variant: .secondary, fontSize: 12, height: 40)
        let btnConfirm = SiqiButton(title: "✔ 确认", variant: .secondary, fontSize: 12, height: 40)
        let btnBack = SiqiButton(title: "↩ 返回", variant: .secondary, fontSize: 12, height: 40)
        btnUp.addAction(UIAction { [weak self] _ in self?.simulate(.up) }, for: .touchUpInside)
        btnDown.addAction(UIAction { [weak self] _ in self?.simulate(.down) }, for: .touchUpInside)
        btnConfirm.addAction(UIAction { [weak self] _ in self?.simulate(.confirm) }, for: .touchUpInside)
        btnBack.addAction(UIAction { [weak self] _ in self?.simulate(.back) }, for: .touchUpInside)
        card.stack.addArrangedSubview(horizontalRow([btnUp, btnDown, btnConfirm, btnBack], spacing: 6))
        card.stack.addArrangedSubview(settingsKeyLogLabel)

        card.stack.addArrangedSubview(diagInfoLabel)
        return card
    }

    private func simulate(_ action: SiqiKeyAction) {
        delegate?.triggerSimulatedKey(action)
        settingsKeyLogLabel.text = "最近触发：\(action.title)"
        settingsKeyLogLabel.textColor = SiqiStyle.accent
    }

    private func buildCreditsCard() -> SiqiCardView {
        let card = SiqiCardView()
        card.stack.spacing = 10
        card.stack.addArrangedSubview(SiqiStyle.makeKicker("关于与开源项目声明"))

        let summary = SiqiStyle.makeSecondaryLabel(
            "摩投 (iOS) 遵循原始开源协议并致敬以下开源项目：\n" +
            "• CarWithin (github.com/xmz28/CarWithin, GPL-3.0 / AGPL-3.0)\n" +
            "• DiPlay (github.com/shihabal3amri/DiPlay, GPL-3.0)\n" +
            "• xcertplay (github.com/shilapi/xcertplay, GPL-3.0)\n" +
            "• DiAuto UI (github.com/shihabal3amri/DiAuto, AGPL-3.0)\n" +
            "• PlayPort (github.com/youcci/playport, GPL-3.0) · BigInt (MIT)",
            size: 12
        )
        card.stack.addArrangedSubview(summary)

        let detailBtn = SiqiButton(title: "查看完整开源声明与作者仓库", variant: .secondary, fontSize: 13, height: 42)
        detailBtn.addAction(UIAction { [weak self] _ in
            self?.showFullCreditsAlert()
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(detailBtn)

        let githubBtn = SiqiButton(title: "GitHub 开源主页", variant: .secondary, fontSize: 13, height: 42)
        githubBtn.addAction(UIAction { [weak self] _ in
            self?.openExternalURL("https://github.com/NK-WYY56/MoTou")
        }, for: .touchUpInside)

        let douyinBtn = SiqiButton(title: "作者抖音主页", variant: .secondary, fontSize: 13, height: 42)
        douyinBtn.addAction(UIAction { [weak self] _ in
            self?.openExternalURL("https://v.douyin.com/1BVqQqBYtuc/")
        }, for: .touchUpInside)

        card.stack.addArrangedSubview(horizontalRow([githubBtn, douyinBtn], spacing: 10))
        return card
    }

    private func openExternalURL(_ raw: String) {
        guard let url = URL(string: raw) else { return }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }

    private func refreshState() {
        guard let d = delegate else { return }
        bikePresetMenuButton.setTitle("当前车型：\(d.selectedBikePreset.title)   ›", for: .normal)
        bikeDescLabel.text = d.selectedBikePreset.description

        for (idx, btn) in iconButtons.enumerated() {
            btn.apply(variant: .segmented(selected: idx == d.selectedIconPreset.rawValue))
        }
        for (idx, btn) in videoModeButtons.enumerated() {
            btn.apply(variant: .segmented(selected: idx == d.selectedVideoMode.rawValue))
        }
        for (idx, btn) in appearanceButtons.enumerated() {
            btn.apply(variant: .segmented(selected: idx == d.selectedAppearance.rawValue))
        }

        let plan = d.selectedBikePreset.resolvePlan(
            icon: d.selectedIconPreset,
            videoMode: d.selectedVideoMode,
            customProfile: d.customDisplayProfile
        )
        if plan.disableSafeAreaSquareCorners {
            applyHintLabel.text = "当前参数：CarPlay \(plan.carPlayWidth)×\(plan.carPlayHeight) → 仪表推流 \(plan.streamWidth)×\(plan.streamHeight)（视口 y=\(plan.viewportTop), h=\(plan.viewportHeight)）· \(plan.widthPhysicalMM)mm"
        } else {
            applyHintLabel.text = "当前参数：CarPlay \(plan.carPlayWidth)×\(plan.carPlayHeight) → 仪表推流 \(plan.streamWidth)×\(plan.streamHeight)（安全区 上\(plan.safeAreaTop)/下\(plan.safeAreaBottom)）· \(plan.widthPhysicalMM)mm"
        }
        applyHintLabel.textColor = SiqiStyle.textSecondary

        keepAliveSwitch.isOn = d.isKeepAliveEnabled
        autoStartSwitch.isOn = d.isAutoStartOnWiFiEnabled
        autoStopSwitch.isOn = d.isAutoStopOnWiFiLeaveEnabled

        let store = SiqiWiFiPresetStore.shared
        store.reload()
        if let active = store.selectedPreset, !active.ssid.isEmpty {
            wifiPresetManageButton.setTitle("当前 Wi-Fi 预设：\(active.name)（共 \(store.presets.count) 个）  ›", for: .normal)
            wifiPresetSummaryLabel.text = "热点 \(active.ssid) · \(active.passwordSummary) · 信道 \(active.wifiChannel)"
            wifiPresetSummaryLabel.textColor = SiqiWiFiPresetStore.containsSpace(active.ssid) ? SiqiStyle.warning : SiqiStyle.textSecondary
        } else {
            wifiPresetManageButton.setTitle("仪表 Wi-Fi 预设管理（当前 \(store.presets.count) 个）  ›", for: .normal)
            wifiPresetSummaryLabel.text = "可保存多台车辆的热点名称、密码与信道，切换骑行车辆免重输。"
            wifiPresetSummaryLabel.textColor = SiqiStyle.textSecondary
        }

        if let lastKey = d.lastControllerActionText {
            settingsKeyLogLabel.text = lastKey
            settingsKeyLogLabel.textColor = SiqiStyle.accent
        } else {
            settingsKeyLogLabel.text = "按键监听就绪（可点上方按钮或按蓝牙控制器测试）"
            settingsKeyLogLabel.textColor = SiqiStyle.textSecondary
        }

        updateAudioRouteLabel()

        var sys = utsname(); uname(&sys)
        let machine = withUnsafeBytes(of: &sys.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        let mfi = d.isMFiIdentityLoaded ? "MFi 证书已内置" : "MFi 证书缺失"
        diagInfoLabel.text = "设备：\(machine) · iOS \(UIDevice.current.systemVersion) · \(mfi) · 端口 17818 / 15457 / 15456"
    }

    @objc private func audioRouteChanged() {
        DispatchQueue.main.async { [weak self] in
            self?.updateAudioRouteLabel()
        }
    }

    private func updateAudioRouteLabel() {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portName).joined(separator: "、")
        audioRouteLabel.text = outputs.isEmpty ? "当前音频输出：默认扬声器（点右侧图标切换）" : "当前音频输出：\(outputs)"
    }

    private func openBikePresetSubmenu() {
        guard let d = delegate else { return }
        let vc = SiqiBikePresetPickerViewController(selected: d.selectedBikePreset) { [weak self] chosen in
            self?.delegate?.selectedBikePreset = chosen
            self?.refreshState()
        }
        navigationController?.pushViewController(vc, animated: true)
    }

    private func openWiFiPresetsManager() {
        let vc = SiqiWiFiPresetsViewController { [weak self] in
            self?.delegate?.didUpdateWiFiPresets()
            self?.refreshState()
        }
        navigationController?.pushViewController(vc, animated: true)
    }

    private func openResolutionTuner() {
        guard let d = delegate else { return }
        let vc = SiqiResolutionTunerViewController(
            initialProfile: d.customDisplayProfile,
            videoMode: d.selectedVideoMode,
            isRunning: d.isCarPlayRunning
        ) { [weak self] savedProfile in
            guard let self, let delegate = self.delegate else { return }
            delegate.customDisplayProfile = savedProfile
            delegate.selectedBikePreset = .custom
            delegate.applyDisplaySettingsAndRestartIfNeeded()
            self.refreshState()
        }
        navigationController?.pushViewController(vc, animated: true)
    }

    private func showFullCreditsAlert() {
        let msg =
            "摩投 (iOS) 遵循以下开源项目原始协议并致谢：\n\n" +
            "1. CarWithin (GNU GPL-3.0 / AGPL-3.0)\n" +
            "   • 仓库: https://github.com/xmz28/CarWithin\n" +
            "   • 作用: 提供 iPhone 本机自托管 CarPlay 接收端、CoreAccessories iAP2 直连传输、锁屏静音保活与 VideoToolbox 编解码架构\n\n" +
            "2. DiPlay (GNU GPL-3.0)\n" +
            "   • 仓库: https://github.com/shihabal3amri/DiPlay\n" +
            "   • 作用: 无线 CarPlay 协议研究、安全区标定思路与 Android 版同源引擎\n\n" +
            "3. xcertplay (GNU GPL-3.0)\n" +
            "   • 仓库: https://github.com/shilapi/xcertplay\n" +
            "   • 作用: 上游核心 iAP2 / AirPlay / MFi-SAP 认证与 HID 协议栈实现\n\n" +
            "4. DiAuto (GNU AGPL-3.0)\n" +
            "   • 仓库: https://github.com/shihabal3amri/DiAuto\n" +
            "   • 作用: 简洁卡片界面配色与设置页交互风格参考\n\n" +
            "5. PlayPort & 第三方组件致谢：\n" +
            "   • PlayPort (github.com/youcci/playport, GPL-3.0)\n" +
            "   • BigInt v5.4.0 (github.com/attaswift/BigInt, MIT)\n\n" +
            "CarPlay 为 Apple Inc. 注册商标；KOVE 标识仅作个人摩托车机互联界面展示。"
        showSimpleAlert(title: "开源项目声明与致谢", message: msg)
    }

    private func showSimpleAlert(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "确定", style: .default))
        present(alert, animated: true)
    }

    private func horizontalRow(_ views: [UIView], spacing: CGFloat) -> UIStackView {
        let row = UIStackView(arrangedSubviews: views)
        row.axis = .horizontal
        row.distribution = .fillEqually
        row.spacing = spacing
        return row
    }

    private func makeSwitchRow(title: String, subtitle: String, toggle: UISwitch) -> UIView {
        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = .systemFont(ofSize: 15, weight: .bold)
        titleLabel.textColor = SiqiStyle.textPrimary
        titleLabel.numberOfLines = 0

        let subLabel = SiqiStyle.makeSecondaryLabel(subtitle, size: 12)
        let textCol = UIStackView(arrangedSubviews: [titleLabel, subLabel])
        textCol.axis = .vertical
        textCol.spacing = 4

        toggle.setContentHuggingPriority(.required, for: .horizontal)
        toggle.setContentCompressionResistancePriority(.required, for: .horizontal)

        let row = UIStackView(arrangedSubviews: [textCol, toggle])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 12
        return row
    }

    private func spacer(_ height: CGFloat) -> UIView {
        let view = UIView()
        view.heightAnchor.constraint(equalToConstant: height).isActive = true
        return view
    }
}

/// 二级菜单：车型仪表预设选择页（目前仅保留思骑出行 / 凯越 450RR，后期在此扩展更多思骑车型）。
final class SiqiBikePresetPickerViewController: UIViewController {
    private var selected: SiqiBikePreset
    private let onSelect: (SiqiBikePreset) -> Void

    init(selected: SiqiBikePreset, onSelect: @escaping (SiqiBikePreset) -> Void) {
        self.selected = selected
        self.onSelect = onSelect
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = SiqiStyle.windowBackground

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.alwaysBounceVertical = true
        view.addSubview(scroll)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -28),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -18)
        ])

        let backBtn = SiqiButton(title: "‹ 返回", variant: .secondary, fontSize: 14, height: 40)
        backBtn.setContentHuggingPriority(.required, for: .horizontal)
        backBtn.addAction(UIAction { [weak self] _ in
            self?.navigationController?.popViewController(animated: true)
        }, for: .touchUpInside)

        let titleLabel = UILabel()
        titleLabel.text = "车型仪表预设"
        titleLabel.font = .systemFont(ofSize: 21, weight: .bold)
        titleLabel.textColor = SiqiStyle.textPrimary

        let bar = UIStackView(arrangedSubviews: [backBtn, titleLabel, UIView()])
        bar.spacing = 14
        bar.alignment = .center
        stack.addArrangedSubview(bar)

        let listCard = SiqiCardView()
        listCard.stack.spacing = 10
        listCard.stack.addArrangedSubview(SiqiStyle.makeKicker("已适配思骑出行车型"))

        for preset in SiqiBikePreset.allCases {
            let isSelected = preset == selected
            let btn = SiqiButton(
                title: (isSelected ? "✓ " : "") + preset.title,
                variant: .segmented(selected: isSelected),
                fontSize: 14,
                height: 46
            )
            btn.addAction(UIAction { [weak self] _ in
                self?.selected = preset
                self?.onSelect(preset)
                self?.navigationController?.popViewController(animated: true)
            }, for: .touchUpInside)
            listCard.stack.addArrangedSubview(btn)
            listCard.stack.addArrangedSubview(SiqiStyle.makeSecondaryLabel(preset.description, size: 12))
        }
        stack.addArrangedSubview(listCard)

        let futureCard = SiqiCardView()
        futureCard.stack.spacing = 6
        futureCard.stack.addArrangedSubview(SiqiStyle.makeKicker("后续车型扩展"))
        futureCard.stack.addArrangedSubview(SiqiStyle.makeSecondaryLabel(
            "内置「思骑出行 / 凯越 450RR」实测预设，并新增「自定义分辨率」用于其他思骑仪表黑屏/拉伸调试。如需手动微调分辨率并导出实测参数，请返回上一页点「手动调节分辨率 / 保存与导出」。",
            size: 13
        ))
        stack.addArrangedSubview(futureCard)
    }
}

/// 手动调节思骑仪表分辨率、视口偏移、保存生效与导出配置的调试页。
final class SiqiResolutionTunerViewController: UIViewController {
    private var profile: SiqiCustomDisplayProfile
    private let videoMode: SiqiVideoMode
    private let isRunning: Bool
    private let onSaveAndApply: (SiqiCustomDisplayProfile) -> Void

    private let bikeNameField = UITextField()
    private let streamWidthField = UITextField()
    private let streamHeightField = UITextField()
    private let carPlayWidthField = UITextField()
    private let carPlayHeightField = UITextField()
    private let viewportTopField = UITextField()
    private let viewportHeightField = UITextField()
    private let physicalMMField = UITextField()
    private let squareCornersSwitch = UISwitch()
    private let previewLabel = SiqiStyle.makeSecondaryLabel("", size: 12)
    private let statusLabel = SiqiStyle.makeSecondaryLabel("", size: 12)

    init(
        initialProfile: SiqiCustomDisplayProfile,
        videoMode: SiqiVideoMode,
        isRunning: Bool,
        onSaveAndApply: @escaping (SiqiCustomDisplayProfile) -> Void
    ) {
        self.profile = initialProfile.sanitized()
        self.videoMode = videoMode
        self.isRunning = isRunning
        self.onSaveAndApply = onSaveAndApply
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = SiqiStyle.windowBackground
        buildLayout()
        syncFieldsFromProfile()
    }

    private func buildLayout() {
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.alwaysBounceVertical = true
        scroll.keyboardDismissMode = .interactive
        view.addSubview(scroll)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -32),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -18)
        ])

        // Top Bar
        let backBtn = SiqiButton(title: "‹ 返回", variant: .secondary, fontSize: 14, height: 40)
        backBtn.setContentHuggingPriority(.required, for: .horizontal)
        backBtn.addAction(UIAction { [weak self] _ in
            self?.navigationController?.popViewController(animated: true)
        }, for: .touchUpInside)
        let titleLabel = UILabel()
        titleLabel.text = "手动调节分辨率"
        titleLabel.font = .systemFont(ofSize: 20, weight: .bold)
        titleLabel.textColor = SiqiStyle.textPrimary
        let bar = UIStackView(arrangedSubviews: [backBtn, titleLabel, UIView()])
        bar.spacing = 12
        bar.alignment = .center
        stack.addArrangedSubview(bar)

        // Card 1: Quick Templates (anti-black-screen)
        let templateCard = SiqiCardView()
        templateCard.stack.spacing = 8
        templateCard.stack.addArrangedSubview(SiqiStyle.makeKicker("快捷模板（解决不同仪表黑屏）"))
        templateCard.stack.addArrangedSubview(SiqiStyle.makeSecondaryLabel(
            "其他车型连上 15456 但黑屏时，通常是仪表解码器只认特定推流分辨率（常见 800×480 或 1024×600）。先点下方常见模板测试出画，再微调比例与上下留白。",
            size: 12
        ))
        let templates = SiqiCustomDisplayProfile.commonTemplates
        for chunkStart in stride(from: 0, to: templates.count, by: 2) {
            let chunk = Array(templates[chunkStart..<min(chunkStart + 2, templates.count)])
            let buttons: [UIView] = chunk.map { item in
                let btn = SiqiButton(title: item.label, variant: .secondary, fontSize: 12, height: 38)
                btn.addAction(UIAction { [weak self] _ in
                    guard let self else { return }
                    self.profile = item.profile.sanitized()
                    self.syncFieldsFromProfile()
                    self.statusLabel.text = "已填入「\(item.label)」模板，点底部「保存并应用」生效。"
                    self.statusLabel.textColor = SiqiStyle.accent
                }, for: .touchUpInside)
                return btn
            }
            let row = UIStackView(arrangedSubviews: buttons)
            row.axis = .horizontal
            row.distribution = .fillEqually
            row.spacing = 8
            templateCard.stack.addArrangedSubview(row)
        }
        stack.addArrangedSubview(templateCard)

        // Card 2: Stream & CarPlay & Viewport fine-tuning
        let tuneCard = SiqiCardView()
        tuneCard.stack.spacing = 10
        tuneCard.stack.addArrangedSubview(SiqiStyle.makeKicker("详细参数微调"))

        styleTextField(bikeNameField, placeholder: "车型备注（如 凯越 321RR）", numeric: false)
        tuneCard.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("车型备注名（导出时附带）"))
        tuneCard.stack.addArrangedSubview(bikeNameField)

        tuneCard.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("1. 仪表推流分辨率（69B头与 H.264，防黑屏核心）"))
        tuneCard.stack.addArrangedSubview(makeStepperRow(label: "推流宽度", field: streamWidthField, step: 16))
        tuneCard.stack.addArrangedSubview(makeStepperRow(label: "推流高度", field: streamHeightField, step: 16))

        tuneCard.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("2. CarPlay 画布分辨率（防图标拉伸）"))
        tuneCard.stack.addArrangedSubview(makeStepperRow(label: "画布宽度", field: carPlayWidthField, step: 16))
        tuneCard.stack.addArrangedSubview(makeStepperRow(label: "画布高度", field: carPlayHeightField, step: 16))
        let syncCanvasBtn = SiqiButton(title: "画布尺寸同步等于推流分辨率", variant: .secondary, fontSize: 12, height: 34)
        syncCanvasBtn.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.readFieldsIntoProfile()
            self.profile.carPlayWidth = self.profile.streamWidth
            self.profile.carPlayHeight = self.profile.streamHeight
            self.syncFieldsFromProfile()
        }, for: .touchUpInside)
        tuneCard.stack.addArrangedSubview(syncCanvasBtn)

        tuneCard.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("3. 有效视口范围（避让上下仪表栏）"))
        tuneCard.stack.addArrangedSubview(makeStepperRow(label: "顶部偏移 y", field: viewportTopField, step: 2))
        tuneCard.stack.addArrangedSubview(makeStepperRow(label: "有效高度 h", field: viewportHeightField, step: 2))
        let fullViewportBtn = SiqiButton(title: "视口铺满整个推流高度（y=0, h=推流高）", variant: .secondary, fontSize: 12, height: 34)
        fullViewportBtn.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.readFieldsIntoProfile()
            self.profile.viewportTop = 0
            self.profile.viewportHeight = self.profile.streamHeight
            self.syncFieldsFromProfile()
        }, for: .touchUpInside)
        tuneCard.stack.addArrangedSubview(fullViewportBtn)

        tuneCard.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("4. 图标大小与直角边角"))
        tuneCard.stack.addArrangedSubview(makeStepperRow(label: "物理宽 mm", field: physicalMMField, step: 10))

        SiqiStyle.styleSwitch(squareCornersSwitch)
        squareCornersSwitch.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.readFieldsIntoProfile()
            self.profile.disableSafeAreaSquareCorners = self.squareCornersSwitch.isOn
            self.updatePreviewText()
        }, for: .valueChanged)
        let cornerTitle = UILabel()
        cornerTitle.text = "直角无圆角黑边（cornerMasks）"
        cornerTitle.font = .systemFont(ofSize: 14, weight: .semibold)
        cornerTitle.textColor = SiqiStyle.textPrimary
        let cornerRow = UIStackView(arrangedSubviews: [cornerTitle, UIView(), squareCornersSwitch])
        cornerRow.alignment = .center
        tuneCard.stack.addArrangedSubview(cornerRow)

        previewLabel.numberOfLines = 0
        tuneCard.stack.addArrangedSubview(previewLabel)
        stack.addArrangedSubview(tuneCard)

        // Card 3: Save, Apply & Export
        let actionCard = SiqiCardView()
        actionCard.stack.spacing = 10
        actionCard.stack.addArrangedSubview(SiqiStyle.makeKicker("保存生效与导出分享"))

        let saveApplyBtn = SiqiButton(
            title: isRunning ? "保存并立即重连生效" : "保存并启用自定义分辨率",
            variant: .primary,
            fontSize: 14,
            height: 46
        )
        saveApplyBtn.addAction(UIAction { [weak self] _ in
            self?.saveAndApply()
        }, for: .touchUpInside)
        actionCard.stack.addArrangedSubview(saveApplyBtn)

        let exportBtn = SiqiButton(title: "导出最终分辨率配置（复制并分享）", variant: .secondary, fontSize: 14, height: 44)
        exportBtn.addAction(UIAction { [weak self] _ in
            self?.exportProfile(sourceView: exportBtn)
        }, for: .touchUpInside)
        actionCard.stack.addArrangedSubview(exportBtn)

        let resetBtn = SiqiButton(title: "恢复凯越 450RR 实测基准", variant: .secondary, fontSize: 13, height: 40)
        resetBtn.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.profile = .kove450RRDefault
            self.syncFieldsFromProfile()
            self.statusLabel.text = "已恢复凯越 450RR 默认参数（点上方保存生效）。"
            self.statusLabel.textColor = SiqiStyle.textSecondary
        }, for: .touchUpInside)
        actionCard.stack.addArrangedSubview(resetBtn)

        actionCard.stack.addArrangedSubview(statusLabel)
        stack.addArrangedSubview(actionCard)
    }

    private func styleTextField(_ field: UITextField, placeholder: String, numeric: Bool) {
        field.placeholder = placeholder
        field.keyboardType = numeric ? .numberPad : .default
        field.autocorrectionType = .no
        field.autocapitalizationType = .none
        field.textColor = SiqiStyle.textPrimary
        field.font = .monospacedDigitSystemFont(ofSize: 14, weight: .medium)
        field.backgroundColor = SiqiStyle.button.withAlphaComponent(0.75)
        field.layer.cornerRadius = 10
        field.layer.cornerCurve = .continuous
        field.layer.borderWidth = 1
        field.layer.borderColor = SiqiStyle.outline.cgColor
        field.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 10, height: 36))
        field.leftViewMode = .always
        field.textAlignment = numeric ? .center : .left
        field.heightAnchor.constraint(equalToConstant: 38).isActive = true
        field.returnKeyType = .done
        field.addTarget(field, action: #selector(UIResponder.resignFirstResponder), for: .editingDidEndOnExit)
        field.addAction(UIAction { [weak self] _ in
            self?.readFieldsIntoProfile()
            self?.updatePreviewText()
        }, for: .editingDidEnd)
    }

    private func makeStepperRow(label: String, field: UITextField, step: Int) -> UIView {
        let title = SiqiStyle.makeSecondaryLabel(label, size: 13)
        title.widthAnchor.constraint(equalToConstant: 78).isActive = true

        styleTextField(field, placeholder: "", numeric: true)

        let minusBtn = SiqiButton(title: "-\(step)", variant: .secondary, fontSize: 12, height: 36)
        minusBtn.widthAnchor.constraint(equalToConstant: 52).isActive = true
        minusBtn.addAction(UIAction { [weak self] _ in
            self?.nudge(field, delta: -step)
        }, for: .touchUpInside)

        let plusBtn = SiqiButton(title: "+\(step)", variant: .secondary, fontSize: 12, height: 36)
        plusBtn.widthAnchor.constraint(equalToConstant: 52).isActive = true
        plusBtn.addAction(UIAction { [weak self] _ in
            self?.nudge(field, delta: step)
        }, for: .touchUpInside)

        let row = UIStackView(arrangedSubviews: [title, minusBtn, field, plusBtn])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = 8
        return row
    }

    private func nudge(_ field: UITextField, delta: Int) {
        view.endEditing(true)
        let current = Int(field.text ?? "") ?? 0
        field.text = String(max(0, current + delta))
        readFieldsIntoProfile()
        syncFieldsFromProfile()
    }

    private func readFieldsIntoProfile() {
        var next = profile
        next.bikeName = bikeNameField.text ?? next.bikeName
        if let v = Int(streamWidthField.text ?? "") { next.streamWidth = v }
        if let v = Int(streamHeightField.text ?? "") { next.streamHeight = v }
        if let v = Int(carPlayWidthField.text ?? "") { next.carPlayWidth = v }
        if let v = Int(carPlayHeightField.text ?? "") { next.carPlayHeight = v }
        if let v = Int(viewportTopField.text ?? "") { next.viewportTop = v }
        if let v = Int(viewportHeightField.text ?? "") { next.viewportHeight = v }
        if let v = Int(physicalMMField.text ?? "") { next.widthPhysicalMM = v }
        next.disableSafeAreaSquareCorners = squareCornersSwitch.isOn
        profile = next.sanitized()
    }

    private func syncFieldsFromProfile() {
        let p = profile.sanitized()
        profile = p
        bikeNameField.text = p.bikeName
        streamWidthField.text = "\(p.streamWidth)"
        streamHeightField.text = "\(p.streamHeight)"
        carPlayWidthField.text = "\(p.carPlayWidth)"
        carPlayHeightField.text = "\(p.carPlayHeight)"
        viewportTopField.text = "\(p.viewportTop)"
        viewportHeightField.text = "\(p.viewportHeight)"
        physicalMMField.text = "\(p.widthPhysicalMM)"
        squareCornersSwitch.isOn = p.disableSafeAreaSquareCorners
        updatePreviewText()
    }

    private func updatePreviewText() {
        let p = profile.sanitized()
        let bottom = max(0, p.streamHeight - p.viewportTop - p.viewportHeight)
        previewLabel.text = "当前预览：CarPlay \(p.carPlayWidth)×\(p.carPlayHeight) → 仪表推流 \(p.streamWidth)×\(p.streamHeight)（视口 y=\(p.viewportTop)..\(p.viewportTop + p.viewportHeight)，底留黑 \(bottom)px）· \(p.widthPhysicalMM)mm · \(p.disableSafeAreaSquareCorners ? "直角" : "安全区")"
    }

    private func saveAndApply() {
        view.endEditing(true)
        readFieldsIntoProfile()
        syncFieldsFromProfile()
        profile.save()
        onSaveAndApply(profile)
        statusLabel.text = isRunning
            ? "已保存「\(profile.bikeName)」并切为自定义预设，正在重连生效…"
            : "已保存「\(profile.bikeName)」并启用自定义分辨率，返回启动 CarPlay 生效。"
        statusLabel.textColor = SiqiStyle.success
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    private func exportProfile(sourceView: UIView) {
        view.endEditing(true)
        readFieldsIntoProfile()
        syncFieldsFromProfile()
        profile.save()
        let report = profile.exportReport(videoMode: videoMode)
        UIPasteboard.general.string = report
        statusLabel.text = "已复制最终分辨率配置到剪贴板，并打开系统分享菜单。"
        statusLabel.textColor = SiqiStyle.success

        let activity = UIActivityViewController(activityItems: [report], applicationActivities: nil)
        if let pop = activity.popoverPresentationController {
            pop.sourceView = sourceView
            pop.sourceRect = sourceView.bounds
        }
        present(activity, animated: true)
    }
}

/// Runtime log viewer in matching SiqiStyle.
final class SiqiLogViewController: UIViewController {
    private let linesProvider: () -> [String]
    private let textView = UITextView()

    init(linesProvider: @escaping () -> [String]) {
        self.linesProvider = linesProvider
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = SiqiStyle.windowBackground

        let backBtn = SiqiButton(title: "‹ 返回", variant: .secondary, fontSize: 14, height: 40)
        backBtn.setContentHuggingPriority(.required, for: .horizontal)
        backBtn.addAction(UIAction { [weak self] _ in
            self?.navigationController?.popViewController(animated: true)
        }, for: .touchUpInside)

        let titleLabel = UILabel()
        titleLabel.text = "运行日志"
        titleLabel.font = .systemFont(ofSize: 21, weight: .bold)
        titleLabel.textColor = SiqiStyle.textPrimary

        let copyBtn = SiqiButton(title: "复制", variant: .secondary, fontSize: 13, height: 40)
        copyBtn.setContentHuggingPriority(.required, for: .horizontal)
        copyBtn.addAction(UIAction { [weak self] _ in
            UIPasteboard.general.string = self?.textView.text ?? ""
            copyBtn.setTitle("已复制", for: .normal)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) { copyBtn.setTitle("复制", for: .normal) }
        }, for: .touchUpInside)

        let bar = UIStackView(arrangedSubviews: [backBtn, titleLabel, UIView(), copyBtn])
        bar.spacing = 12
        bar.alignment = .center
        bar.translatesAutoresizingMaskIntoConstraints = false

        let card = SiqiCardView()
        card.translatesAutoresizingMaskIntoConstraints = false
        textView.isEditable = false
        textView.backgroundColor = .clear
        textView.textColor = SiqiStyle.textPrimary
        textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        textView.text = linesProvider().joined(separator: "\n")
        card.stack.addArrangedSubview(textView)

        view.addSubview(bar)
        view.addSubview(card)
        NSLayoutConstraint.activate([
            bar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 14),
            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 18),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -18),

            card.topAnchor.constraint(equalTo: bar.bottomAnchor, constant: 14),
            card.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 18),
            card.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -18),
            card.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16)
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        textView.text = linesProvider().joined(separator: "\n")
        if !textView.text.isEmpty {
            textView.scrollRangeToVisible(NSRange(location: textView.text.utf16.count - 1, length: 1))
        }
    }
}

/// 多仪表 Wi-Fi 预设管理页（支持添加、编辑、删除、切换、一键去除空格与选择兼容信道）。
final class SiqiWiFiPresetsViewController: UIViewController {
    private let onChanged: () -> Void
    private let scrollView = UIScrollView()
    private let stack = UIStackView()
    private let listStack = UIStackView()

    init(onChanged: @escaping () -> Void) {
        self.onChanged = onChanged
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = SiqiStyle.windowBackground
        buildLayout()
        reloadList()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: false)
        reloadList()
    }

    private func buildLayout() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.alwaysBounceVertical = true
        view.addSubview(scrollView)

        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(stack)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -28),
            stack.leadingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.trailingAnchor, constant: -18)
        ])

        let backBtn = SiqiButton(title: "‹ 返回", variant: .secondary, fontSize: 14, height: 40)
        backBtn.setContentHuggingPriority(.required, for: .horizontal)
        backBtn.addAction(UIAction { [weak self] _ in
            self?.navigationController?.popViewController(animated: true)
        }, for: .touchUpInside)

        let titleLabel = UILabel()
        titleLabel.text = "仪表 Wi-Fi 预设"
        titleLabel.font = .systemFont(ofSize: 21, weight: .bold)
        titleLabel.textColor = SiqiStyle.textPrimary

        let addBtn = SiqiButton(title: "+ 新增", variant: .primary, fontSize: 13, height: 40)
        addBtn.setContentHuggingPriority(.required, for: .horizontal)
        addBtn.addAction(UIAction { [weak self] _ in
            self?.openEditor(preset: nil)
        }, for: .touchUpInside)

        let bar = UIStackView(arrangedSubviews: [backBtn, titleLabel, UIView(), addBtn])
        bar.spacing = 12
        bar.alignment = .center
        stack.addArrangedSubview(bar)

        let tipCard = SiqiCardView()
        tipCard.stack.spacing = 6
        tipCard.stack.addArrangedSubview(SiqiStyle.makeKicker("使用说明"))
        tipCard.stack.addArrangedSubview(SiqiStyle.makeSecondaryLabel(
            "可为不同摩托车或不同骑行场景保存多组仪表 Wi-Fi 热点（名称、密码、开放网络开关及信道）。点选任意预设即可立即切换为首页当前激活热点。",
            size: 12
        ))
        stack.addArrangedSubview(tipCard)

        listStack.axis = .vertical
        listStack.spacing = 12
        stack.addArrangedSubview(listStack)
    }

    private func reloadList() {
        listStack.arrangedSubviews.forEach {
            listStack.removeArrangedSubview($0)
            $0.removeFromSuperview()
        }

        let store = SiqiWiFiPresetStore.shared
        store.reload()
        let selectedID = store.selectedPreset?.id

        if store.presets.isEmpty {
            let emptyCard = SiqiCardView()
            emptyCard.stack.spacing = 10
            emptyCard.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("暂无已保存的仪表 Wi-Fi 预设"))
            emptyCard.stack.addArrangedSubview(SiqiStyle.makeSecondaryLabel(
                "点击下方按钮添加您的仪表 Wi-Fi（密码通常为 12345678，注意名称不要含空格）。",
                size: 13
            ))
            let createBtn = SiqiButton(title: "+ 添加第一条仪表 Wi-Fi 预设", variant: .primary, fontSize: 14, height: 44)
            createBtn.addAction(UIAction { [weak self] _ in
                self?.openEditor(preset: nil)
            }, for: .touchUpInside)
            emptyCard.stack.addArrangedSubview(createBtn)
            listStack.addArrangedSubview(emptyCard)
            return
        }

        for preset in store.presets {
            let isSelected = preset.id == selectedID
            let card = SiqiCardView()
            card.stack.spacing = 8
            if isSelected {
                card.layer.borderColor = SiqiStyle.accent.cgColor
                card.layer.borderWidth = 1.5
            }

            let title = UILabel()
            title.text = (isSelected ? "✓ " : "") + preset.name
            title.font = .systemFont(ofSize: 16, weight: .bold)
            title.textColor = isSelected ? SiqiStyle.accent : SiqiStyle.textPrimary

            let badge = SiqiChipView()
            badge.apply(text: isSelected ? "当前启用" : "点击切换", color: isSelected ? SiqiStyle.success : SiqiStyle.textSecondary)

            let header = UIStackView(arrangedSubviews: [title, UIView(), badge])
            header.alignment = .center
            card.stack.addArrangedSubview(header)

            let ssidText = preset.ssid.isEmpty ? "（未填写 Wi-Fi 名称）" : preset.ssid
            let detail = SiqiStyle.makeSecondaryLabel(
                "Wi-Fi：\(ssidText) · \(preset.passwordSummary) · 信道 \(preset.wifiChannel)",
                size: 13
            )
            card.stack.addArrangedSubview(detail)

            if SiqiWiFiPresetStore.containsSpace(preset.ssid) {
                let warn = SiqiStyle.makeSecondaryLabel("⚠️ 该热点名称包含空格，可能导致 iAP2 无法收到控制连接。", size: 12)
                warn.textColor = SiqiStyle.warning
                card.stack.addArrangedSubview(warn)
            }

            let selectBtn = SiqiButton(
                title: isSelected ? "已启用" : "设为当前热点",
                variant: .segmented(selected: isSelected),
                fontSize: 12,
                height: 36
            )
            selectBtn.addAction(UIAction { [weak self] _ in
                SiqiWiFiPresetStore.shared.select(id: preset.id)
                self?.onChanged()
                self?.reloadList()
            }, for: .touchUpInside)

            let editBtn = SiqiButton(title: "编辑", variant: .secondary, fontSize: 12, height: 36)
            editBtn.addAction(UIAction { [weak self] _ in
                self?.openEditor(preset: preset)
            }, for: .touchUpInside)

            let deleteBtn = SiqiButton(title: "删除", variant: .danger, fontSize: 12, height: 36)
            deleteBtn.addAction(UIAction { [weak self] _ in
                self?.confirmDelete(preset)
            }, for: .touchUpInside)

            let row = UIStackView(arrangedSubviews: [selectBtn, editBtn, deleteBtn])
            row.axis = .horizontal
            row.distribution = .fillEqually
            row.spacing = 8
            card.stack.addArrangedSubview(row)

            listStack.addArrangedSubview(card)
        }
    }

    private func confirmDelete(_ preset: SiqiWiFiPreset) {
        let alert = UIAlertController(
            title: "删除预设「\(preset.name)」？",
            message: "热点名称：\(preset.ssid)",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "删除", style: .destructive) { [weak self] _ in
            SiqiWiFiPresetStore.shared.delete(id: preset.id)
            self?.onChanged()
            self?.reloadList()
        })
        present(alert, animated: true)
    }

    private func openEditor(preset: SiqiWiFiPreset?) {
        let vc = SiqiWiFiPresetEditorViewController(initialPreset: preset) { [weak self] saved in
            SiqiWiFiPresetStore.shared.upsert(saved, selectAfterSave: true)
            self?.onChanged()
            self?.reloadList()
        }
        navigationController?.pushViewController(vc, animated: true)
    }
}

/// 新建或编辑单条仪表 Wi-Fi 预设表单页。
final class SiqiWiFiPresetEditorViewController: UIViewController {
    private let existingID: UUID?
    private let onSave: (SiqiWiFiPreset) -> Void

    private let nameField = UITextField()
    private let ssidField = UITextField()
    private let passwordField = UITextField()
    private let openSwitch = UISwitch()
    private let spaceWarningLabel = SiqiStyle.makeSecondaryLabel("⚠️ 检测到 Wi-Fi 名称含空格，建议清除", size: 12)
    private let cleanSpaceButton = SiqiButton(title: "一键去空格", variant: .secondary, fontSize: 11, height: 28)
    private let spaceWarningRow = UIStackView()
    private var selectedChannel: UInt8
    private var channelButtons: [SiqiButton] = []

    init(initialPreset: SiqiWiFiPreset?, onSave: @escaping (SiqiWiFiPreset) -> Void) {
        self.existingID = initialPreset?.id
        self.selectedChannel = initialPreset?.wifiChannel ?? 36
        self.onSave = onSave
        super.init(nibName: nil, bundle: nil)

        nameField.text = initialPreset?.name ?? ""
        ssidField.text = initialPreset?.ssid ?? ""
        passwordField.text = initialPreset?.isOpenNetwork == true
            ? ""
            : (initialPreset?.password ?? SiqiWiFiPreset.defaultPassword)
        openSwitch.isOn = initialPreset?.isOpenNetwork ?? false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = SiqiStyle.windowBackground
        buildLayout()
        refreshControls()
    }

    private func buildLayout() {
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.alwaysBounceVertical = true
        scroll.keyboardDismissMode = .interactive
        view.addSubview(scroll)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)

        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),

            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -28),
            stack.leadingAnchor.constraint(equalTo: scroll.frameLayoutGuide.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: scroll.frameLayoutGuide.trailingAnchor, constant: -18)
        ])

        let backBtn = SiqiButton(title: "‹ 取消", variant: .secondary, fontSize: 14, height: 40)
        backBtn.setContentHuggingPriority(.required, for: .horizontal)
        backBtn.addAction(UIAction { [weak self] _ in
            self?.navigationController?.popViewController(animated: true)
        }, for: .touchUpInside)

        let titleLabel = UILabel()
        titleLabel.text = existingID == nil ? "新增 Wi-Fi 预设" : "编辑 Wi-Fi 预设"
        titleLabel.font = .systemFont(ofSize: 20, weight: .bold)
        titleLabel.textColor = SiqiStyle.textPrimary

        let bar = UIStackView(arrangedSubviews: [backBtn, titleLabel, UIView()])
        bar.spacing = 12
        bar.alignment = .center
        stack.addArrangedSubview(bar)

        let card = SiqiCardView()
        card.stack.spacing = 10
        card.stack.addArrangedSubview(SiqiStyle.makeKicker("热点凭证"))

        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("预设备注名"))
        styleField(nameField, placeholder: "例如 凯越 450RR（不填自动用热点名）")
        card.stack.addArrangedSubview(nameField)

        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("仪表 Wi-Fi 名称 (SSID)"))
        styleField(ssidField, placeholder: "例如 CQKY_xxxx（注意无空格）")
        ssidField.addAction(UIAction { [weak self] _ in
            self?.refreshSpaceWarning()
        }, for: .editingChanged)
        card.stack.addArrangedSubview(ssidField)

        spaceWarningLabel.textColor = SiqiStyle.warning
        cleanSpaceButton.setContentHuggingPriority(.required, for: .horizontal)
        cleanSpaceButton.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.ssidField.text = SiqiWiFiPresetStore.removingSpaces(self.ssidField.text ?? "")
            self.refreshSpaceWarning()
        }, for: .touchUpInside)
        spaceWarningRow.axis = .horizontal
        spaceWarningRow.alignment = .center
        spaceWarningRow.spacing = 8
        spaceWarningRow.addArrangedSubview(spaceWarningLabel)
        spaceWarningRow.addArrangedSubview(cleanSpaceButton)
        card.stack.addArrangedSubview(spaceWarningRow)

        SiqiStyle.styleSwitch(openSwitch)
        openSwitch.addAction(UIAction { [weak self] _ in
            self?.refreshControls()
        }, for: .valueChanged)
        let openTitle = SiqiStyle.makeSectionTitle("开放热点（无密码）")
        let openRow = UIStackView(arrangedSubviews: [openTitle, UIView(), openSwitch])
        openRow.alignment = .center
        card.stack.addArrangedSubview(openRow)

        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("Wi-Fi 密码（思骑默认 12345678）"))
        styleField(passwordField, placeholder: "默认 12345678（8-63位）")
        card.stack.addArrangedSubview(passwordField)

        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("iAP2 兼容 Wi-Fi 信道（一般保持 36）"))
        channelButtons = SiqiWiFiPreset.supportedChannels.map { channel in
            let btn = SiqiButton(title: "\(channel)", variant: .segmented(selected: channel == selectedChannel), fontSize: 12, height: 36)
            btn.addAction(UIAction { [weak self] _ in
                self?.selectedChannel = channel
                self?.refreshControls()
            }, for: .touchUpInside)
            return btn
        }
        let chRow = UIStackView(arrangedSubviews: channelButtons)
        chRow.axis = .horizontal
        chRow.distribution = .fillEqually
        chRow.spacing = 6
        card.stack.addArrangedSubview(chRow)

        stack.addArrangedSubview(card)

        let saveBtn = SiqiButton(title: "保存并启用该 Wi-Fi 预设", variant: .primary, fontSize: 15, height: 48)
        saveBtn.addAction(UIAction { [weak self] _ in
            self?.savePressed()
        }, for: .touchUpInside)
        stack.addArrangedSubview(saveBtn)
    }

    private func styleField(_ field: UITextField, placeholder: String) {
        field.placeholder = placeholder
        field.autocorrectionType = .no
        field.autocapitalizationType = .none
        field.clearButtonMode = .whileEditing
        field.textColor = SiqiStyle.textPrimary
        field.font = .systemFont(ofSize: 14)
        field.backgroundColor = SiqiStyle.button.withAlphaComponent(0.75)
        field.layer.cornerRadius = 10
        field.layer.cornerCurve = .continuous
        field.layer.borderWidth = 1
        field.layer.borderColor = SiqiStyle.outline.cgColor
        field.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 40))
        field.leftViewMode = .always
        field.heightAnchor.constraint(equalToConstant: 42).isActive = true
        field.returnKeyType = .done
        field.addTarget(field, action: #selector(UIResponder.resignFirstResponder), for: .editingDidEndOnExit)
    }

    private func refreshSpaceWarning() {
        spaceWarningRow.isHidden = !SiqiWiFiPresetStore.containsSpace(ssidField.text ?? "")
    }

    private func refreshControls() {
        refreshSpaceWarning()
        passwordField.isEnabled = !openSwitch.isOn
        passwordField.alpha = openSwitch.isOn ? 0.5 : 1.0
        for (idx, channel) in SiqiWiFiPreset.supportedChannels.enumerated() where idx < channelButtons.count {
            channelButtons[idx].apply(variant: .segmented(selected: channel == selectedChannel))
        }
    }

    private func savePressed() {
        view.endEditing(true)
        let rawSSID = ssidField.text ?? ""
        let trimmedSSID = rawSSID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedSSID.isEmpty else {
            let alert = UIAlertController(title: "请填写 Wi-Fi 名称", message: "思骑仪表热点名不能为空。", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "确定", style: .default))
            present(alert, animated: true)
            return
        }
        if SiqiWiFiPresetStore.containsSpace(rawSSID) {
            let cleaned = SiqiWiFiPresetStore.removingSpaces(rawSSID)
            let alert = UIAlertController(
                title: "检测到热点名称含空格",
                message: "是否将「\(trimmedSSID)」自动清理为「\(cleaned)」后保存？",
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "去空格保存（推荐）", style: .default) { [weak self] _ in
                self?.commitSave(ssid: cleaned)
            })
            alert.addAction(UIAlertAction(title: "保留原样", style: .default) { [weak self] _ in
                self?.commitSave(ssid: trimmedSSID)
            })
            present(alert, animated: true)
            return
        }
        commitSave(ssid: trimmedSSID)
    }

    private func commitSave(ssid: String) {
        let pwd = (passwordField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !openSwitch.isOn && !pwd.isEmpty && !(8...63).contains(pwd.utf8.count) {
            let alert = UIAlertController(title: "密码长度无效", message: "WPA2 Wi-Fi 密码需为 8–63 位，开放网络请打开上方开关。", preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "确定", style: .default))
            present(alert, animated: true)
            return
        }
        let preset = SiqiWiFiPreset(
            id: existingID ?? UUID(),
            name: nameField.text ?? "",
            ssid: ssid,
            password: openSwitch.isOn ? "" : (pwd.isEmpty ? SiqiWiFiPreset.defaultPassword : pwd),
            isOpenNetwork: openSwitch.isOn,
            wifiChannel: selectedChannel
        )
        onSave(preset)
        navigationController?.popViewController(animated: true)
    }
}
