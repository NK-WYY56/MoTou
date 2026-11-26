# 思骑CarPlay (SiQiCarPlay)

面向思骑出行（Thinkerride）摩托车仪表的无线 CarPlay 双端开源方案，当前已针对 **凯越 450RR** 完成画面比例、直角无圆角黑边与上下状态栏居中视口实测校准。

- **GitHub 仓库**：https://github.com/NK-WYY56/SiQiCarPlay
- **作者抖音主页**：https://v.douyin.com/1BVqQqBYtuc/

## 目录结构

- `android/`：Android 中继/虚拟屏投屏端（支持无线 CarPlay 离屏推流、蓝牙手把按键控制、凯越 450RR 16:7 画布与 `1280×484` 居中视口校准）
- `ios/`：iOS 本机自托管无线 CarPlay 端（无需中继设备，支持 iPhone 息屏后台继续向思骑仪表推流，同步凯越 450RR 实测分辨率方案与蓝牙手把按键支持）

## 凯越 450RR 实测画面方案（双端同步）

- 推流输出：`1280 × 640` @ `30 FPS`（TCP `17818` 控制 / `15457` 心跳 / `15456` H.264 视频）
- CarPlay 基准画布：`2560 × 1120`（16:7），三档图标尺寸：
  - 超大图标：`1024 × 448`（`100 mm`）
  - 大图标（推荐）：`1280 × 560`（`100 mm`）
  - 标准：`1536 × 672`（`200 mm`）
- 视口合成：居中映射至 `1280 × 484`（`y = 78..562`，上下各 `78 px` 黑边隐藏于仪表状态栏后），配合 `cornerMasks = true` 实现直角无圆角黑边、四边等距留白与 1:1 图标比例矫正。

## 安全与构建说明

本仓库**不包含任何私有 MFi 认证密钥或证书**（已在 `.gitignore` 中排除）：
- Android 端构建时请按 `android/app/build.gradle.kts` 说明自行准备本地认证资源目录；
- iOS 端构建时请自行准备 MFi 身份资源后使用 Xcode 编译并自行签名安装。

## 开源致谢与许可

本项目遵循上游开源协议（GNU GPL-3.0 / AGPL-3.0），诚挚致谢以下开源项目：
- [CarWithin](https://github.com/xmz28/CarWithin) (GPL-3.0 / AGPL-3.0)
- [DiPlay](https://github.com/shihabal3amri/DiPlay) (GPL-3.0)
- [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0)
- [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0)
- [PlayPort](https://github.com/youcci/playport) (GPL-3.0)
- [BigInt](https://github.com/attaswift/BigInt) (MIT)

CarPlay 为 Apple Inc. 注册商标。本项目仅限个人学习与摩托车仪表互联研究使用。
