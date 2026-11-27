# 摩投 (MoTou)

面向思骑出行（Thinkerride）摩托车仪表的无线投屏双端开源工具，支持 **Android 端**（无线 CarPlay 中继 + 本机 CarLife 纯安卓直连）与 **iOS 端**（iPhone 本机自托管无线 CarPlay，当前已适配思骑出行仪表的凯越 450RR 并支持自定义分辨率）。

- **GitHub 仓库**：https://github.com/NK-WYY56/MoTou
- **安装包下载（Releases）**：https://github.com/NK-WYY56/MoTou/releases
- **作者抖音主页**：https://v.douyin.com/1BVqQqBYtuc/

---

## Android 端使用方法

支持**双模式切换**：既保留 **iPhone 无线 CarPlay 中继**（手机锁屏兜底、无感自连），也支持 **本机百度 CarLife（纯安卓无需 iPhone）**：

1. **安装应用**：在 Releases 页面下载 **`摩投（安卓端）.apk`** 安装到安卓手机，按提示授予蓝牙、定位、附近设备与后台运行权限。
2. **连接思骑仪表**：打开车辆电源，将手机连入思骑仪表 Wi-Fi（默认密码一般为 `12345678`），并在仪表端进入投屏界面。
3. **选择投屏源模式（首页卡片 2 顶部切换）**：
   - **无线 CarPlay (中继)**：启动服务并用 iPhone 配对安卓机启用无线 CarPlay，连接成功后自动离屏推流至仪表；默认声音与 Siri 直接走 iPhone 蓝牙耳机。
   - **本机 CarLife (纯安卓)**：无需 iPhone，启动「本机 CarLife 接收」并打开 **百度 CarLife+**（或兼容车联助手/CarWith）连接本机，支持**首页可触控预览小屏（点按、拖动、主页/返回/语音快捷栏）**与**横屏全屏触控**，并可在息屏后持续推流；手机系统 CarLife/CarWith 蓝牙继续选**头盔耳机蓝牙**即可正常触发并保留耳机出声（仪表蓝牙一般为纯 BLE 数据通道，即使自带经典蓝牙也建议在系统蓝牙详情关闭仪表「媒体音频/通话音频」防抢声音）。
4. **手动调节分辨率 / 保存与导出**：进入右上角 **「设置 › 手动调节分辨率 / 保存与导出」**，可针对黑屏/拉伸的其他思骑仪表切换常用分辨率模板（`800×480` / `1024×600` / `1280×480` / `1280×720` 等）、微调视口与画布比例，并复制或分享 JSON 实测配置。
5. **蓝牙手把控制器（开发 ing）**：手把按键操控需要搭配**单独开发的专用蓝牙按键控制器硬件**；App 端已内置上 / 下 / 确认 / 返回及长按呼出语音（Siri / CarLife 语音）映射逻辑，硬件完成后可直接配对使用。

---

## iOS 端使用方法

适用于直接在 iPhone 本机运行无线 CarPlay 并投屏到思骑仪表（**无需额外安卓中继设备或 CarPlay 盒子**）：

1. **自签安装 IPA**：在 Releases 页面下载 **`摩投（苹果端）.ipa`**（未签名包，实验性兼容 **iOS 15.0+**，推荐 **iOS 16+**），可使用免费开源自签工具安装：
   - **[iLoader](https://github.com/nab138/iloader)**（推荐）：电脑端连接 iPhone 一键安装 **[LiveContainer](https://github.com/LiveContainer/LiveContainer) + [SideStore](https://github.com/SideStore/SideStore)**，信任证书并开启开发者模式后，直接在 LiveContainer 内导入 `摩投（苹果端）.ipa` 即可运行（不占 3 个签名名额、支持手机端免电脑续签）。
   - 也支持 **[AltStore](https://github.com/altstoreio/AltStore)**、SideStore 或自有开发者证书 / 巨魔直接签名安装。详细步骤见 [ios/README.md](ios/README.md)。
2. **仪表 Wi-Fi 预设与空格检测**：
   - 可在首页「Wi-Fi 预设」或 **「设置 › 仪表 Wi-Fi 预设管理」** 保存多台车辆的热点名称、密码（默认 `12345678` / 支持自定义或开放热点）与兼容信道。
   - 输入热点名时若误含空格（常见输入法下划线后自动补空格）会自动弹窗提醒并支持「一键去空格」。
3. **一键启动 & 快捷指令自动化**：
   - 连上仪表 Wi-Fi 后，点 **「启动无线 CarPlay」** 或在设置开启 **「检测到仪表 Wi-Fi 自动启动 CarPlay」**；
   - 支持 URL Scheme **`motou://start`** / **`motou://stop`**，可配合 iPhone 自带「快捷指令 › 自动化」（加入仪表 Wi-Fi + 立即运行 → 打开 URL `motou://start`）在**解锁状态**下自动拉起投屏，并可在 **离开仪表 Wi-Fi 满 3 分钟后自动停止释放后台**（受 iOS 系统限制，锁屏黑屏状态下无法在未解锁时绕过权限拉起 App，完全息屏无感场景仍建议使用 Android 中继）。
4. **手动调节分辨率（解决其他仪表黑屏）**：点击右上角 **「设置 › 手动调节分辨率 / 保存与导出」**，提供 5 组常见模板与像素微调，支持一键复制并导出分享实测配置。
5. **同时保住 5G 上网与息屏投屏**：
   - **保 5G**：在 iPhone 已连接的仪表 Wi-Fi 右侧点 `i` →「配置 IP」改为「手动」，保留原 IP 与子网掩码，将「路由器」和「DNS」全部留空并存储。
   - **息屏后台**：首次连上后前往 iPhone「设置 › 通用 › CarPlay › 摩投」打开 **「锁定时允许 CarPlay」**，并保持本 App 设置里的「锁屏与后台继续运行」开启。
6. **蓝牙手把控制器（开发 ing）**：手把按键操控需要搭配**单独开发的专用蓝牙按键控制器硬件**；App 端已预留蓝牙 HID 按键接收与 CarPlay 光标映射支持，待专用控制器完成后即可配对使用。

---

## 安全与构建说明

本仓库**不包含任何私有 MFi 认证密钥或证书**（已在 `.gitignore` 中排除），自行从源码构建时请按各自子目录说明准备本地认证资源。

## 开源致谢与许可

本项目遵循上游开源协议（GNU GPL-3.0 / AGPL-3.0），诚挚致谢以下开源项目与作者：

- [CarWithin](https://github.com/xmz28/CarWithin) (GPL-3.0 / AGPL-3.0)
- [DiPlay](https://github.com/shihabal3amri/DiPlay) (GPL-3.0)
- [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0)
- [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0)
- [PlayPort](https://github.com/youcci/playport) (GPL-3.0)
- [BigInt](https://github.com/attaswift/BigInt) (MIT)

CarPlay 为 Apple Inc. 注册商标。本项目为个人开源项目，与 Apple、思骑出行及凯越机车无官方关联，仅限个人学习与摩托车仪表互联研究使用。
