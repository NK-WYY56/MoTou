# 思骑CarPlay（iOS 端）

在 iPhone 本机直接运行无线 CarPlay 并通过 Wi-Fi 投屏至思骑出行摩托车仪表（当前内置凯越 450RR 预设），无需额外硬件中继盒子，支持息屏后台持续投屏与蓝牙手把按键控制器。

- **项目主页**：https://github.com/NK-WYY56/SiQiCarPlay
- **作者抖音**：https://v.douyin.com/1BVqQqBYtuc/

## 使用方法

1. 将编译好的未签名 IPA 通过你常用的签名工具（如 LiveContainer / SideStore / AltStore / 个人开发者证书）签名并安装到 iPhone（需 iOS 16.0 及以上）。
2. 开启摩托车电源，在 iPhone 系统「设置 › 无线局域网」或控制中心连接思骑仪表 Wi-Fi（默认密码通常为 `12345678`）。
3. 打开 **思骑CarPlay**，在首页「1 · 连接车机」输入框填写一次当前仪表热点名称（如 `CQKY_xxxx`，后续自动记住）。
4. 在仪表端打开投屏界面，点击 App 首页 **「启动无线 CarPlay」** 即可完成连接并在仪表显示画面。
5. **同时保持 5G 上网**：连上仪表 Wi-Fi 后，点该 Wi-Fi 右侧 `i` →「配置 IP」改为「手动」，保留与仪表同网段的 IP 与子网掩码，将「路由器」和「DNS」留空并存储，高德地图与音乐即可继续走 5G 流量。
6. **手机息屏放兜里投屏**：首次连通后前往 iPhone「设置 › 通用 › CarPlay › 思骑CarPlay」开启 **「锁定时允许 CarPlay」**，并保持本 App 设置中的「锁屏与后台继续运行」为开启状态。

## 源码构建说明

本仓库不含私有 MFi 身份文件。自行构建时请将 MFi 身份文件放置在 `App/BundledIdentity/` 后运行：

```bash
python3 Tools/generate_project.py
xcodebuild -project TDSCarKit.xcodeproj -scheme TDSCarKit -configuration Release -sdk iphoneos CODE_SIGNING_ALLOWED=NO build
```

## 开源致谢

本 iOS 端基于开源项目 [CarWithin](https://github.com/xmz28/CarWithin) 进行深度定制与思骑仪表协议适配，并遵循 GNU GPL-3.0 / AGPL-3.0 协议。完整第三方许可说明见本目录 `LICENSE` 与 `THIRD_PARTY_NOTICES.txt`。
