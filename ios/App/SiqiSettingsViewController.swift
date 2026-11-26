import AVKit
import UIKit

/// Bridge between Settings UI and ReceiverViewController runtime state.
protocol SiqiSettingsDelegate: AnyObject {
    var isCarPlayRunning: Bool { get }
    var isMFiIdentityLoaded: Bool { get }
    var selectedBikePreset: SiqiBikePreset { get set }
    var selectedIconPreset: SiqiIconSizePreset { get set }
    var selectedVideoMode: SiqiVideoMode { get set }
    var selectedAppearance: ReceiverConfiguration.Appearance { get set }
    var isKeepAliveEnabled: Bool { get set }
    var currentLogLines: [String] { get }
    var lastControllerActionText: String? { get }

    func applyDisplaySettingsAndRestartIfNeeded()
    func forceClusterKeyframe()
    func triggerSimulatedKey(_ action: SiqiKeyAction)
    func showGuideSheet(from presenter: UIViewController)
}

/// Unified settings screen mirroring Android 思骑CarPlay SettingsActivity:
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

        // 车型仪表预设：点击进入二级菜单切换（目前仅思骑出行 / 凯越 450RR）
        card.stack.addArrangedSubview(SiqiStyle.makeSectionTitle("车型仪表预设"))
        bikePresetMenuButton.contentHorizontalAlignment = .fill
        bikePresetMenuButton.addAction(UIAction { [weak self] _ in
            self?.openBikePresetSubmenu()
        }, for: .touchUpInside)
        card.stack.addArrangedSubview(bikePresetMenuButton)
        card.stack.addArrangedSubview(bikeDescLabel)

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
        card.stack.addArrangedSubview(SiqiStyle.makeKicker("连接与后台模式"))

        SiqiStyle.styleSwitch(keepAliveSwitch)
        keepAliveSwitch.addAction(UIAction { [weak self] _ in
            guard let self else { return }
            self.delegate?.isKeepAliveEnabled = self.keepAliveSwitch.isOn
        }, for: .valueChanged)
        card.stack.addArrangedSubview(makeSwitchRow(
            title: "锁屏与后台继续运行",
            subtitle: "手机息屏放兜里或切出应用时保持 CarPlay 推流。请在系统「设置 › 通用 › CarPlay › 思骑CarPlay」开启「锁定时允许 CarPlay」。",
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
            "思骑CarPlay (iOS) 遵循原始开源协议并致敬以下开源项目：\n" +
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
            self?.openExternalURL("https://github.com/NK-WYY56/SiQiCarPlay")
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

        let canvas = d.selectedBikePreset.carPlayCanvasSize(for: d.selectedIconPreset, videoMode: d.selectedVideoMode)
        let viewport = d.selectedBikePreset.viewportRect(for: d.selectedVideoMode)
        let safeArea = d.selectedBikePreset.airPlaySafeArea(for: d.selectedVideoMode)
        if safeArea.disableSafeAreaSquareCorners {
            applyHintLabel.text = "当前参数：CarPlay \(canvas.width)×\(canvas.height) → 仪表 1280×\(viewport.height)（上下各 \(viewport.top)px）· 直角无圆角 · \(d.selectedIconPreset.shortTitle)"
        } else {
            applyHintLabel.text = "当前参数：CarPlay \(canvas.width)×\(canvas.height) 直传 → 仪表 1280×640（安全区 上\(safeArea.top)/下\(safeArea.bottom)）· \(d.selectedIconPreset.shortTitle)"
        }
        applyHintLabel.textColor = SiqiStyle.textSecondary

        keepAliveSwitch.isOn = d.isKeepAliveEnabled

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

    private func showFullCreditsAlert() {
        let msg =
            "思骑CarPlay (iOS) 遵循以下开源项目原始协议并致谢：\n\n" +
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
            "当前版本精简为仅保留「思骑出行 / 凯越 450RR」专属实测预设（16:7 画布 1280×560 → 仪表 1280×484 上下各 78px 居中视口、直角无圆角黑边、四边等距留白与 1:1 图标比例矫正）。后续将在此二级菜单中陆续增加其他思骑出行车型仪表预设。",
            size: 13
        ))
        stack.addArrangedSubview(futureCard)
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
