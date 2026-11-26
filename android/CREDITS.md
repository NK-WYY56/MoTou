# 开源项目来源与致谢声明 (Open-Source Credits & Attribution)

**CarPlaySiQiRelay (CarPlay ⇄ 思骑/凯越 450RR 中继网关)** 直接内置并致敬以下开源项目，保留原作者署名、代码注释与许可证条款：

## 核心上游项目

1. **DiPlay** (`shihabal3amri/DiPlay`)
   - 开源地址: https://github.com/shihabal3amri/DiPlay
   - 许可协议: **GNU General Public License v3.0 (GPL-3.0)**（含沿用自 DiAuto 的 **AGPL-3.0-only** 界面组件，见 [LICENSE](LICENSE) 与 [docs/licenses/DiAuto-AGPL-3.0.txt](docs/licenses/DiAuto-AGPL-3.0.txt)）
   - 集成模块: [`shared/`](shared) 与 [`common/`](common) — 提供 Android 端无线 (Bluetooth + 5GHz Wi-Fi Direct/Hotspot + AirPlay) 与有线 CarPlay 接收、H.264 硬解、音频与 HID 输入完整核心。

2. **xcertplay** (`shilapi/xcertplay`)
   - 开源地址: https://github.com/shilapi/xcertplay
   - 许可协议: **GNU General Public License v3.0 (GPL-3.0)**
   - 贡献: DiPlay 所基于的上游 Apple iAP2 / AirPlay 会话协议栈与多通道音频路由。

3. **DiAuto** (`shihabal3amri/DiAuto`)
   - 开源地址: https://github.com/shihabal3amri/DiAuto
   - 许可协议: **GNU Affero General Public License v3.0 (AGPL-3.0)**
   - 贡献: 内置 `DiPlayActivity.kt` 的界面配色、布局与设置交互设计。

## 协议研究与第三方组件致谢

- [LIVI](https://github.com/f-io/LIVI) & [Showcase](https://github.com/amineross/showcase) — 协议研究致谢
- [SpeexDSP 1.2.1](https://github.com/xiph/speexdsp) — BSD 3-Clause（通话回声消除 `libspeex_echo.so`）
- [Concentus](https://github.com/lostromb/concentus) — Opus BSD License（纯 Java Opus 编码器）
- **Bouncy Castle 1.79** (MIT-style)、**JmDNS 3.6.3** (Apache-2.0)、**SLF4J** (MIT)、**ZXing Core** (Apache-2.0)、**AndroidX / Jetpack Compose** (Apache-2.0)

完整上游第三方说明请见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。

## 品牌与本地测试说明

- CarPlay 界面默认 OEM 车标与标签已适配替换为 **凯越机车 (KOVE)** (`ic_car_kove.png` / `ic_car_kove_dark.png`)，并保留原作者内置车标可选项。
- KOVE / CarPlay 等标识分属其各自权利人所有，仅作个人车载/摩托车机互联界面展示使用。
