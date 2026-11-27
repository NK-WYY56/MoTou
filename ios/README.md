# 摩投（iOS 端）

在 iPhone 本机直接运行无线 CarPlay 并通过 Wi-Fi 投屏至思骑出行摩托车仪表（当前内置凯越 450RR 预设并支持自定义分辨率调参导出），无需额外硬件中继盒子，支持息屏后台持续投屏、多仪表 Wi-Fi 预设保存、名称空格自动检测及 `motou://start` 快捷指令拉起与离开 3 分钟自动停止；同时已预留蓝牙手把按键控制器协议支持（专用控制器硬件**开发 ing**）。

- **项目主页**：https://github.com/NK-WYY56/MoTou
- **安装包下载（Releases）**：https://github.com/NK-WYY56/MoTou/releases
- **作者抖音**：https://v.douyin.com/1BVqQqBYtuc/

---

## iOS 自签安装方法（IPA）

本项目的 iOS 安装包 `摩投（苹果端）.ipa` 为**未签名包**（最低部署目标已放宽至 **iOS 15.0** 实验性兼容，实际体验推荐 iOS 16 及以上），下载后可通过以下免费开源自签工具安装到 iPhone：

### 方式一：iLoader + LiveContainer + SideStore（推荐，免电脑自动续签）
- **iLoader**：https://github.com/nab138/iloader
- **LiveContainer**：https://github.com/LiveContainer/LiveContainer
- **SideStore**：https://github.com/SideStore/SideStore

**安装步骤**：
1. 在电脑（Windows / macOS / Linux）下载并打开 [iLoader](https://github.com/nab138/iloader/releases)（Windows 需先安装官方 iTunes 驱动）。
2. 用数据线连接 iPhone 并点击「信任此电脑」，在 iLoader 中登录 Apple ID（建议使用备用 Apple ID）。
3. 在 iLoader 内选择一键安装 **LiveContainer + SideStore**（自动完成签名、安装与配对文件写入）。
4. 在 iPhone 上前往 **「设置 › 通用 › VPN 与设备管理」** 信任对应开发者证书，并在 **「设置 › 隐私与安全性 › 开发者模式」** 开启开发者模式后重启手机。
5. 打开 LiveContainer，点击右上角 `+` 导入本项目 Releases 中下载的 **`摩投（苹果端）.ipa`** 即可直接运行（不占用免费账号 3 个 App 签名名额；配合 SideStore + 本地 VPN 可在手机端无电脑续签）。

### 方式二：AltStore / SideStore / 巨魔直接安装
- **AltStore**：https://github.com/altstoreio/AltStore
- **SideStore**：https://github.com/SideStore/SideStore
- 若已有自签证书（个人开发者证书 / 企业签 / 巨魔 TrollStore 等），也可直接使用对应工具安装 `摩投（苹果端）.ipa`。

---

## 连接、预设与自动化使用说明

1. **保存并选择 Wi-Fi 预设**：在首页或 **「设置 › 仪表 Wi-Fi 预设管理」** 保存仪表 Wi-Fi 名称（注意不要多打空格，软件带自动空格检测与一键去除）、密码（思骑默认 `12345678`，支持自定义与开放热点）以及兼容信道（默认 `36`）。
2. **快捷指令半无感自动拉起（需手机处于解锁状态）**：
   - 打开 iPhone 自带 **「快捷指令 › 自动化 › + › 无线局域网」**；
   - 选择你的仪表 Wi-Fi（勾选 **加入网络** + **立即运行**）；
   - 添加操作 **「打开 URL」** 并填入 **`motou://start`**。
   - 连上仪表 Wi-Fi 后解锁亮屏即可自动拉起并投屏；骑完离开仪表 Wi-Fi 满 **3 分钟** 将自动停止并释放后台保活。
   - *说明：受 iOS 系统安全沙盒限制，手机未解锁熄屏时无法免解锁静默拉起 App，若需要「手机完全锁屏放兜里无感直连」，仍推荐使用本项目的 Android 端无线中继模式。*
3. **黑屏与分辨率调参导出**：不同型号思骑仪表遇到「视频通道已连但黑屏」时，前往 **「设置 › 手动调节分辨率 / 保存与导出」** 尝试常见分辨率模板并导出分享给开发者录入。
4. **同时保持 5G 上网与息屏投屏**：
   - 连上仪表 Wi-Fi 后，点该 Wi-Fi 右侧 `i` →「配置 IP」改为「手动」，保留与仪表同网段的 IP 与子网掩码，将「路由器」和「DNS」留空并存储，高德地图与音乐即可继续走 5G 流量。
   - 首次连通后前往 iPhone「设置 › 通用 › CarPlay › 摩投」开启 **「锁定时允许 CarPlay」**，并保持本 App 设置中的「锁屏与后台继续运行」为开启状态。
5. **蓝牙手把控制器（开发 ing）**：手把实体按键操控需要搭配单独开发的专用蓝牙控制器硬件，软件端已预留按键映射支持。

---

## 源码构建说明

本仓库不含私有 MFi 身份文件。自行构建时请将 MFi 身份文件放置在 `App/BundledIdentity/` 后运行：

```bash
python3 Tools/generate_project.py
xcodebuild -project TDSCarKit.xcodeproj -scheme TDSCarKit -configuration Release -sdk iphoneos CODE_SIGNING_ALLOWED=NO build
```

## 开源致谢

本 iOS 端基于开源项目 [CarWithin](https://github.com/xmz28/CarWithin) 进行深度定制与思骑仪表协议适配，并遵循 GNU GPL-3.0 / AGPL-3.0 协议。完整第三方许可说明见本目录 `LICENSE` 与 `THIRD_PARTY_NOTICES.txt`。
