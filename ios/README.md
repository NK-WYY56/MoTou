# 摩投（iOS 端）

在 iPhone 本机直接运行无线 CarPlay 并通过 Wi-Fi 投屏至思骑出行摩托车仪表（当前内置凯越 450RR 预设），无需额外硬件中继盒子，支持息屏后台持续投屏，并已预留蓝牙手把按键控制器协议支持（专用控制器硬件**开发 ing**）。

- **项目主页**：https://github.com/NK-WYY56/SiQiCarPlay
- **安装包下载（Releases）**：https://github.com/NK-WYY56/SiQiCarPlay/releases
- **作者抖音**：https://v.douyin.com/1BVqQqBYtuc/

---

## iOS 自签安装方法（IPA）

本项目的 iOS 安装包 `摩投（苹果端）.ipa` 为**未签名包**（需 iOS 16.0 及以上），下载后可通过以下免费开源自签工具安装到 iPhone：

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

### 方式二：AltStore / SideStore 直接签名安装
- **AltStore**：https://github.com/altstoreio/AltStore
- **SideStore**：https://github.com/SideStore/SideStore
- 若已有自签证书（个人开发者证书 / 企业签 / 巨魔 TrollStore 等），也可直接使用对应签名工具签名安装 `摩投（苹果端）.ipa`。

---

## 连接与使用方法

1. 开启摩托车电源，在 iPhone 控制中心或系统「设置 › 无线局域网」连接思骑仪表 Wi-Fi（默认密码通常为 `12345678`）。
2. 打开 **摩投**，在首页「1 · 连接车机」输入框填写一次当前仪表热点名称（如 `CQKY_xxxx`，后续自动记住）。
3. 在仪表端打开投屏界面，点击 App 首页 **「启动无线 CarPlay」** 即可完成连接并在仪表显示画面。
4. **同时保持 5G 上网**：连上仪表 Wi-Fi 后，点该 Wi-Fi 右侧 `i` →「配置 IP」改为「手动」，保留与仪表同网段的 IP 与子网掩码，将「路由器」和「DNS」留空并存储，高德地图与音乐即可继续走 5G 流量。
5. **手机息屏放兜里投屏**：首次连通后前往 iPhone「设置 › 通用 › CarPlay › 摩投」开启 **「锁定时允许 CarPlay」**，并保持本 App 设置中的「锁屏与后台继续运行」为开启状态。
6. **蓝牙手把控制器（开发 ing）**：手把实体按键操控需要搭配单独开发的专用蓝牙控制器硬件，软件端已预留按键映射支持。

---

## 源码构建说明

本仓库不含私有 MFi 身份文件。自行构建时请将 MFi 身份文件放置在 `App/BundledIdentity/` 后运行：

```bash
python3 Tools/generate_project.py
xcodebuild -project TDSCarKit.xcodeproj -scheme TDSCarKit -configuration Release -sdk iphoneos CODE_SIGNING_ALLOWED=NO build
```

## 开源致谢

本 iOS 端基于开源项目 [CarWithin](https://github.com/xmz28/CarWithin) 进行深度定制与思骑仪表协议适配，并遵循 GNU GPL-3.0 / AGPL-3.0 协议。完整第三方许可说明见本目录 `LICENSE` 与 `THIRD_PARTY_NOTICES.txt`。
