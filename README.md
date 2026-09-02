# NekoPro

[![CI](https://github.com/Siu-Leung/NekoPro/actions/workflows/ci.yml/badge.svg)](https://github.com/Siu-Leung/NekoPro/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Siu-Leung/NekoPro)](https://github.com/Siu-Leung/NekoPro/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

NekoPro 是一个面向 Android 的个人代理客户端项目，基于
[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
深度修改而来。

本项目采用 **AI Coding** 辅助开发：需求分析、代码实现、协议移植、审计与测试流程均有人机协作参与；发布结论以实际编译、单元测试和真机验证为准，而不是仅依赖生成式输出。

## 主要特性

- sing-box `1.13.19` 主内核
- Mihomo 协议桥接
- VLESS-XHTTP
- Snell v1–v5（Mihomo 实现）与 Snell v6（sing-snell 实现；其上游亦支持 v4/v5）
- IPv4 / IPv6 双栈
- AnyTLS、VMess、VLESS、Trojan、Shadowsocks、ShadowTLS
- Hysteria 1/2、TUIC、SSH、SOCKS、WireGuard 等
- 带本地认证的 Clash API 与内置仪表板
- Android 包名：`moe.nb4a.siu`

## 当前版本

`Neko-Pro-1.0.1`

- [GitHub Release](https://github.com/Siu-Leung/NekoPro/releases/tag/v1.0.1)
- 发布资产：`Neko-Pro-1.0.1-arm64-v8a.apk`

## 主要改动

相较上游，本项目不是仅修改名称或界面，主要结构性改动包括：

- 将 sing-box 1.13 已移除的旧 TUN、sniff 与 DNS 字段迁移到新版配置模型。
- 在 Android 专用 outbound registry 中注册自定义协议。
- 通过 mihomo 桥接 VLESS-XHTTP 与 Snell v1–v5。
- 接入 sing-snell 实现 Snell v6，并保留 Android VPN socket protect 链路。
- 修复 mihomo 桥接协议的 IPv6 字面量解析。
- Clash API 仅监听回环地址，使用安装级随机 Secret，并由仪表板自动认证。
- 对导入、分享和日志路径做敏感字段收敛，避免记录完整 URI、密码、PSK 和 Token。

## 已执行验证

首个 Release 在本地执行并通过了以下验证：

- sing-box：`go test ./...`
- 重点协议：`go test -race` 与 `go vet`
- Android libcore：gomobile 四 ABI bind
- Android：Release 单元测试、Kotlin Release 编译、`assembleOssRelease`
- APK：包名、版本、ABI、原生库内容与 v1/v2 签名检查
- 真机：Snell v4/v5/v6、VLESS-XHTTP、AnyTLS，以及 IPv4/IPv6 双栈入口

CI 提供可重复的 Go 测试；手动 Android 工作流使用 GitHub Actions 加密 Secrets 恢复发布 keystore，生成四 ABI 签名 APK，并同时输出 `MD5SUMS.txt` 与 `SHA256SUMS.txt`。私钥文件与密码不会进入仓库或构建 Artifact。协议可用性仍以真实网络与真机测试为准。

正式 APK 的签名证书 SHA-256 指纹：

```text
CC:5B:75:9E:7E:8F:E9:2A:23:D2:18:D8:66:29:D4:30:85:9D:87:F1:4A:98:A9:BB:C1:5E:4D:39:BA:F5:F3:7C
```

## 构建

完整 Android 工程位于 [`NekoBox/`](NekoBox/)。

```bash
# 需要：JDK 21、Go 1.24.7+、Android SDK、NDK 25.0.8775105
# 确保 ANDROID_HOME 与 ANDROID_NDK_HOME 指向有效目录
cd NekoBox

# 首次构建：安装固定版本的 gomobile/gobind 工具
cd libcore
bash ./init.sh
bash ./build.sh
cd ..

# libcore.aar 会被安装到 app/libs；随后运行 Android 检查和打包
bash ./gradlew :app:testOssReleaseUnitTest
bash ./gradlew assembleOssRelease
```

`libcore/init.sh` 会从固定提交构建 `gomobile-matsuri` 与 `gobind-matsuri`；`libcore/build.sh` 会生成四 ABI 的 `libcore.aar` 并安装到 `app/libs/`。Gradle 的 `ensureGeoAssets` 任务会在需要时下载官方 GeoIP/GeoSite 资源。Release APK 输出于 `NekoBox/app/build/outputs/apk/oss/release/`。

Release 签名信息不在仓库中。请自行配置本地 keystore，切勿提交私钥、密码、Token 或其他凭据。

## 项目说明

- 本项目不是 MatsuriDayo/NekoBoxForAndroid 的官方版本。
- 本项目不会在日志中故意记录或公开代理密码、PSK、Token、完整导入链接等敏感信息。
- 内置 Clash API 仅监听本机回环地址，并使用安装级随机 Secret 认证。
- 协议实现来自多个上游项目；请同时遵守对应目录中的许可证与第三方声明。
- 使用代理工具时，请遵守所在地法律、服务条款和网络管理要求。

## 使用与维护声明

- 本项目仅用于学习、研究及个人网络工具开发，不提供商业服务、节点、订阅或付费支持。
- 请勿将本项目用于违反当地法律法规、侵犯他人权益或绕过未经授权的访问控制。
- 本项目按“现状”提供，不承诺可用性、兼容性、更新频率或 Issue 响应时间。
- 这是个人维护并由 AI 辅助开发的项目；提交 Issue 时请附版本、复现步骤和已脱敏日志。

## 上游与致谢

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo)
- [SagerNet/sing-snell](https://github.com/SagerNet/sing-snell)
- [MetaCubeX/Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)

## License

本项目根许可证见 [`LICENSE`](LICENSE)，Android 应用沿用上游的 GPL-3.0-or-later 许可，详见 [`NekoBox/LICENSE`](NekoBox/LICENSE)。
仓库内 vendored/派生组件保留其各自许可证；相关文件包括
[`sing-box/LICENSE`](sing-box/LICENSE) 与 [`mihomo/LICENSE`](mihomo/LICENSE)。