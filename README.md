# NekoPro

[![CI](https://github.com/Siu-Leung/NekoPro/actions/workflows/ci.yml/badge.svg)](https://github.com/Siu-Leung/NekoPro/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Siu-Leung/NekoPro)](https://github.com/Siu-Leung/NekoPro/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

NekoPro 是一个面向 Android 的个人代理客户端项目，基于
[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
深度修改而来。

本项目采用 **AI Coding** 辅助开发：需求分析、代码实现、协议移植、审计与测试流程均有人机协作参与；发布结论以实际编译、单元测试和真机验证为准，而不是仅依赖生成式输出。

## 主要特性

- sing-box `1.14.1` 主内核
- Mihomo `1.19.31` 协议桥接
- VLESS-XHTTP（智能指纹分流）
- Snell 原生集成（基于 `sing-snell`，全面支持 v4/v5/v6）
- **Juicity 协议原生集成**（基于 `sing-juicity`）
- **真·速度测试模块**（基于 `speedtest-go` 高性能多流测速）
- **候选 UA 智能轮询回退链**（解决复杂订阅源对未知 UA 的阻断与降级）
- IPv4 / IPv6 双栈
- AnyTLS、VMess、VLESS、Trojan、Shadowsocks、ShadowTLS
- Hysteria 1/2、TUIC、SSH、SOCKS、WireGuard 等
- 带本地认证的 Clash API 与内置仪表板
- Android 包名：`moe.nb4a.siu`

## 当前版本

`Neko-Pro-1.1.4`

- [GitHub Release](https://github.com/Siu-Leung/NekoPro/releases/tag/v1.1.4)
- 核心 APK：`NekoBox-Neko-Pro-1.1.4-arm64-v8a.apk`

### v1.1.4 改动日志 (2026-09-17)

- **直连更新订阅 malformed HTTP response 彻底修复**
  - **根因修复**：在不挂代理（直连）拉取订阅时，底层的 ECH TLS 拨号由于未约束 ALPN，对端 Nginx/OpenResty 服务端优先协商了 `h2`（HTTP/2），并向客户端发送了 HTTP/2 SETTINGS 初始二进制帧（`\x00\x00\x12\x04...`）；而外部的 `http.Transport` 属于纯 HTTP/1.x 传输器，无 HTTP/2 帧解码器，进而抛出 `net/http: HTTP/1.x transport connection broken: malformed HTTP response` 错误。
  - **解决方案**：在 `libcore/http.go` 的直接传输器中明确限制 ALPN 仅协商 `http/1.1`，确保服务端以标准的纯文本 HTTP/1.1 协议响应，彻底解决直连更新订阅失败的问题。

### v1.1.3 改动日志 (2026-09-17)

- **关于界面遮挡修复与沉浸式优化**
  - 进入“关于”页面时自动隐藏底部连接状态栏与悬浮飞机（ServiceButton）按钮，切回配置列表时无缝自动恢复，彻底解决底部悬浮遮挡文字问题。
  - 为关于页面增加底部 48dp 滚动安全留白，优化长文本列表阅读体验。
- **关于界面版本点击与更新链路收敛**
  - 版本卡片点击直接跳转至 `Siu-Leung/NekoPro/releases`，不再回跳原项目。
  - 在线检查更新 API 对齐 NekoPro 官方最新 Release。
  - 彻底移除关于说明文字中的 `-siu` 私人后缀。

### v1.1.2 改动日志 (2026-09-17)

- **Snell 协议全面去胶水化：接入官方原生 `sing-snell` 引擎**
  - 彻底拔除借道 mihomo 桥接 Snell 的过渡胶水代码，采用 sing-box 1.14 官方原生 `protocol/snell` 与 `sing-snell`（v4/v5/v6）。
  - 直接复用 sing-box 核心原生 Dialer，支持 VpnService 保护、原生双栈/纯 IPv6 拨号与低延迟连接复用。
- **集成 Cloudflare 官方 200M 极速测速端点**
  - 默认简单测速端点升级为 Cloudflare 官方 200MB 测试文件（`https://speed.cloudflare.com/__down?bytes=200000000`），充分激发 TCP 拥塞控制（BBR/Cubic）峰值带宽。
  - 自动化注入 Cloudflare WAF 鉴权头（`Referer` 与桌面浏览器 `User-Agent`）。
- **根除测速收尾 EOF 伪错误**
  - 过滤测试连接关闭时的 `EOF` 与 `context canceled` 伪错误，只要成功传输数据并测出速率，卡片状态仅呈现纯净的速率信息（`↓ / ↑ Mbps`），不再产生红字报错干扰。

### v1.1.1 改动日志 (2026-09-17)

- **Snell IPv6 与节点导入全链路修复**
  - **剪贴板导入防错与告警消除**：在 `RawUpdater.kt` 中加入明文协议头预检（`snell://`、`vmess://`、`vless://`、`ss://`、`trojan://`、`hysteria2://` 等），识别到明文 URI 时优先走明文解析，跳过 Base64 尝试，消除 `Cannot decode base64` 警告提示。
  - **Snell URI 健壮化与解码增强**：`SnellFmt.kt` 增加 URI 预处理与 safe fragment 解码，完美支持包含 emoji（如国旗）、空格或未转义中文字符的节点名称，避免 OkHttp 抛出解析异常导致导入失败。
  - **IPv6 双重方括号 Bug 彻底根除**：在 UI 转换层与底层 Core（`protocol/snell`）中对 `server` 统一执行 `removeSurrounding("[", "]")` 规整化，彻底解决底层拨号拼接出的 `[[...]]` 双重方括号问题，打通纯 IPv6 与双栈节点。
  - **Snell v5 服务端向下兼容**：底层出站对 Snell v5 自动平滑对齐 v4 协议栈，并确保 `resolver.DisableIPv6` 放行 IPv6 路由解析。
  - **Clash YAML 导入补全**：在 Clash 配置解析中补全 Snell 的 `obfs-opts`（`mode`、`host`）字段支持。

### v1.1.0 改动日志 (2026-09-11)

- **新增 Juicity 代理协议支持**
  - 在 `libcore` 引入 `github.com/exclavenetwork/sing-juicity v0.1.6` 与 `github.com/gofrs/uuid/v5`，在 outbound registry 中注册 `juicity`。
  - 支持 TLS 证书哈希固定校验（`PinCertSha256`）与 ALPN（默认 `h3`）。
  - Android 端完整支持 `juicity://` 节点链接导入、导出、编辑（`JuicitySettingsActivity`）与 UI 管理。
- **集成真·速度测试（Speed Test）模块**
  - 接入 `github.com/Mahdi-zarei/speedtest-go`，通过 Go 会话向应用层导出测速接口。
  - 分组菜单提供“速度测试本组”功能，支持下载+上传、仅下载、仅上传、简单下载四种模式。
  - 实时显示测速阶段、瞬时速率、服务器信息与最终吞吐量（`↓ / ↑ Mbps`），并直接呈现在节点卡片状态栏。
  - 支持测速弹窗最小化至通知栏后台运行，切回前台自动恢复交互。
- **订阅更新“多 UA 智能候选回退链”与标准 UA 规范**
  - 标准化默认客户端 UA 为 `NekoPro/Android/[VERSION_NAME]`。
  - 在订阅拉取时，按照 `用户自定义 UA` → `默认 UA` → `clash-meta` → `v2rayN/7.8.2` → `sing-box/1.14.0` 智能链式回退轮询，直至成功解析有效节点，大幅提升各类型机场与中转转换的兼容性。
- **架构升级与版本递增**
  - 遵循“加协议版本号 +0.1”规范，版本号升级至 `1.1.0`（Version Code: `49`）。
  - 数据库 Room Schema 升级至版本 8（`AutoMigration(from = 7, to = 8)`），新增测速与协议字段，保持零丢失平滑升级。

### v1.0.1 改动日志 (2026-09-03)

- **修复 VLESS-XHTTP 携带 uTLS 指纹时无法连接的问题**（Fixes [#1](https://github.com/Siu-Leung/NekoPro/issues/1)）
  - **修复**：在 `sing-box/protocol/vless_xhttp` 中引入智能指纹分流逻辑，自动区分 64 位 SHA-256 证书哈希与浏览器伪装指纹（`chrome`、`firefox` 等），分别映射到 `Fingerprint` 与 `ClientFingerprint`。
- **构建与发布流优化**
  - CI 工作流支持 Git Tag 自动触发四 ABI 完整编译打包、签名校验与发布。

## 主要改动

相较上游，本项目主要结构性改动包括：

- 将 sing-box 1.13 已移除的旧 TUN、sniff 与 DNS 字段迁移到新版配置模型。
- 在 Android 专用 outbound registry 中注册自定义协议（XHTTP、Snell、Juicity 等）。
- 通过 mihomo 桥接 VLESS-XHTTP 与 Snell v1–v5。
- 接入 sing-snell 实现 Snell v6，并保留 Android VPN socket protect 链路。
- 修复 mihomo 桥接协议的 IPv6 字面量解析。
- Clash API 仅监听回环地址，使用安装级随机 Secret，并由仪表板自动认证。
- 对导入、分享和日志路径做敏感字段收敛，避免记录完整 URI、密码、PSK 和 Token。

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

## 项目说明

- 本项目不是 MatsuriDayo/NekoBoxForAndroid 的官方版本。
- 本项目不会在日志中故意记录或公开代理密码、PSK、Token、完整导入链接等敏感信息。
- 内置 Clash API 仅监听本机回环地址，并使用安装级随机 Secret 认证。
- 协议实现来自多个上游项目；请同时遵守对应目录中的许可证与第三方声明。
- 使用代理工具时，请遵守所在地法律、服务条款和网络管理要求。

## 上游与致谢

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo)
- [SagerNet/sing-snell](https://github.com/SagerNet/sing-snell)
- [throneproj/ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid)（感谢参考其测速交互、测试套件与多 UA 回退链设计）
- [exclavenetwork/sing-juicity](https://github.com/exclavenetwork/sing-juicity)（感谢维护现代 sing-box Juicity 协议适配）
- [Mahdi-zarei/speedtest-go](https://github.com/Mahdi-zarei/speedtest-go)（提供高性能 Go speedtest 驱动核心）
- [MetaCubeX/Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)

## License

本项目根许可证见 [`LICENSE`](LICENSE)，Android 应用沿用上游的 GPL-3.0-or-later 许可，详见 [`NekoBox/LICENSE`](NekoBox/LICENSE)。
仓库内 vendored/派生组件保留其各自许可证；相关文件包括
[`sing-box/LICENSE`](sing-box/LICENSE) 与 [`mihomo/LICENSE`](mihomo/LICENSE)。
