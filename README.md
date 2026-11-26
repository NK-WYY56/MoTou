# 摩投 (MoTou)

面向思骑出行（Thinkerride）摩托车仪表的无线 CarPlay 双端开源工具，支持 **Android 端** 与 **iOS 端**（当前已适配思骑出行仪表的凯越 450RR，后续将陆续增加更多车型预设）。

- **GitHub 仓库**：https://github.com/NK-WYY56/SiQiCarPlay
- **安装包下载（Releases）**：https://github.com/NK-WYY56/SiQiCarPlay/releases
- **作者抖音主页**：https://v.douyin.com/1BVqQqBYtuc/

---

## Android 端使用方法

适用于使用安卓手机作为无线 CarPlay 接收与思骑仪表投屏中继：

1. **安装应用**：在 Releases 页面下载 **`摩投（安卓端）.apk`** 安装到安卓手机，按提示授予蓝牙、定位、附近设备与后台运行权限。
2. **连接思骑仪表**：打开车辆电源，在首页 **「1 · 连接车机」** 连接思骑仪表 Wi-Fi（默认密码一般为 `12345678`），并在仪表端进入投屏界面。
3. **连接 iPhone 无线 CarPlay**：在首页 **「2 · 无线 CarPlay」** 启动服务，使用 iPhone 通过蓝牙与安卓机配对并启用 CarPlay，连接成功后画面将自动离屏推流至摩托车仪表。
4. **音频与 Siri 走头盔耳机**：默认已开启「声音与 Siri 走 iPhone 蓝牙耳机」，音乐、导航语音与 Siri 均直接由 iPhone 输出到你的头盔蓝牙耳机。
5. **蓝牙手把控制器（开发 ing）**：手把按键操控需要搭配**单独开发的专用蓝牙按键控制器硬件**；App 端已预留上 / 下 / 确认 / 返回及长按呼出 Siri 映射逻辑，硬件完成后可直接配对使用。
6. **画面与车型预设**：点击右上角 **「设置」** 可切换图标大小（超大图标 / 大图标推荐 / 标准）或在二级菜单切换车型仪表预设。

---

## iOS 端使用方法

适用于直接在 iPhone 本机运行无线 CarPlay 并投屏到思骑仪表（**无需额外安卓中继设备或 CarPlay 盒子**）：

1. **自签安装 IPA**：在 Releases 页面下载 **`摩投（苹果端）.ipa`**（未签名包，需 iOS 16.0+），推荐使用免费开源自签工具安装：
   - **[iLoader](https://github.com/nab138/iloader)**（推荐）：电脑端连接 iPhone 一键安装 **[LiveContainer](https://github.com/LiveContainer/LiveContainer) + [SideStore](https://github.com/SideStore/SideStore)**，信任证书并开启开发者模式后，直接在 LiveContainer 内导入 `摩投（苹果端）.ipa` 即可运行（不占 3 个签名名额、支持手机端免电脑续签）。
   - 也支持 **[AltStore](https://github.com/altstoreio/AltStore)**、SideStore 或自有开发者证书 / 巨魔直接签名安装。详细步骤见 [ios/README.md](ios/README.md)。
2. **连接仪表 Wi-Fi**：打开车辆电源，在 iPhone 控制中心或系统「设置 › 无线局域网」连接思骑仪表热点（默认密码一般为 `12345678`，点 App 首页卡片 1 按钮可一键复制密码）。
3. **填写一次热点名称**：在首页 **「1 · 连接车机」** 输入框填一次当前连接的仪表 Wi-Fi 名称（如 `CQKY_xxxx`，密码已默认 `12345678`，输入一次后自动记住）。
4. **启动无线 CarPlay**：在仪表端打开投屏界面，点首页 **「启动无线 CarPlay」** 即可自动完成本机握手并将画面实时推送到仪表。
5. **同时保住 5G 上网**：在 iPhone 已连接的仪表 Wi-Fi 右侧点 `i` →「配置 IP」改为「手动」，保留原 IP 与子网掩码，将「路由器」和「DNS」全部留空并存储，即可实现导航/音乐走 5G、仪表投屏走 Wi-Fi。
6. **息屏放兜里继续投屏**：首次连上后前往 iPhone「设置 › 通用 › CarPlay › 摩投」打开 **「锁定时允许 CarPlay」**，并保持本 App 设置里的「锁屏与后台继续运行」开启。
7. **蓝牙手把控制器（开发 ing）**：手把按键操控需要搭配**单独开发的专用蓝牙按键控制器硬件**；App 端已预留蓝牙 HID 按键接收与 CarPlay 光标映射支持，待专用控制器完成后即可配对使用。

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
