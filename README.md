# NekoPro

[![CI](https://github.com/Siu-Leung/NekoPro/actions/workflows/ci.yml/badge.svg)](https://github.com/Siu-Leung/NekoPro/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Siu-Leung/NekoPro)](https://github.com/Siu-Leung/NekoPro/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

NekoPro 是一个独立维护、面向 Android 的个人代理客户端开源派生项目，基于
[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
深度修改而来，与各上游项目及其维护者无官方关联或背书关系（详见 [`NOTICE`](NOTICE)）。

本项目采用 **AI Coding** 辅助开发：需求分析、代码实现、协议移植、审计与测试流程均有人机协作参与；发布结论以实际编译、单元测试和真机验证为准，而不是仅依赖生成式输出。

> [!WARNING]
> **请勿使用 `-rc` 预览测试版本！**
> 带 `-rc`（Pre-release）标记的版本仅用于开发分支内部联调测试，**容易出现未知问题**；日常使用请务必下载带 **`Latest`** 标记的正式版本：[点击下载最新正式版](https://github.com/Siu-Leung/NekoPro/releases/latest)。

## 主要特性

- 基于上游 [`SagerNet/sing-box`](https://github.com/SagerNet/sing-box) `v1.14.4` 源码集成构建并维护本地适配补丁的核心网络栈（原生 Snell v4–v6 / 原生 AnyTLS / Typed DNS Schema / 乐观 DNS 缓存 / `quic-go v0.61`；本地补丁清单见 [`docs/VENDORED_PATCHES.md`](docs/VENDORED_PATCHES.md)）
- 基于上游 [`MetaCubeX/mihomo`](https://github.com/MetaCubeX/mihomo) `v1.19.34` 源码集成的协议桥接层（升级 `uTLS v1.9.0-alpha.1` Chrome 155+ 指纹，合入上游 XHTTP 复用连接池过期修复）
- **MASQUE (CONNECT-IP over HTTP/3 / Cloudflare WARP)** 协议集成
- **VLESS-XHTTP + REALITY** 完整支持（兼容 `splithttp`、`mode`、`extra` XMUX、自定义证书与 ECH）
- **Mieru 协议内置原生出站**（无需外部 `mieru-plugin`）
- **Snell 原生集成**（采用上游 `SagerNet/sing-box v1.14.4` 原生 `protocol/snell` 与 `SagerNet/sing-snell`，支持 v4/v5 兼容客户端与 v6）
- **Juicity 协议原生集成**（升级 `sing-juicity v0.3.1`，支持拥塞控制算法配置）
- **真·速度测试与五源 BGP/ASN 拓扑探测**（支持按下载/上传速度排序、一键重测失败节点与出入口 IP/服务商持久化展示）
- **订阅配置文件管理、多格式私有 DoH 提取与候选 UA 智能回退链**
- IPv4 / IPv6 双栈
- AnyTLS、VMess、VLESS、Trojan、Shadowsocks、ShadowTLS
- Hysteria 1/2、TUIC、SSH、SOCKS、WireGuard (Endpoint) 等
- 带三层免密认证的 Clash API 与双内置离线仪表板（默认 **MetaCubeXD `v1.276.0`** + 一键切换 **Yacd-meta**）
- Android 包名：`moe.nb4a.siu`

## 当前版本

`Neko-Pro-1.4.8`（`Latest` 正式版）

- [GitHub Release (v1.4.8)](https://github.com/Siu-Leung/NekoPro/releases/tag/v1.4.8)
- 核心 APK：`NekoBox-Neko-Pro-1.4.8-arm64-v8a.apk`
- 历史版本完整记录：[`CHANGELOG.md`](CHANGELOG.md)

### v1.4.8 正式版改动日志 (2026-11-26)

- **三层免密直连 Clash 仪表板 + 内置 MetaCubeXD `v1.276.0` 默认面板**
  - **内置离线双面板一键切换**：APK 内置打包官方 **MetaCubeXD `v1.276.0`**（默认启用）与 **Yacd-meta**，无外网/首次启动离线秒开，右上角菜单支持一键切换 MetaCubeXD / Yacd 或自定义远程 `https://` 面板（如 Zashboard），未开代理时点击侧栏「仪表板」也会自动启动服务并就绪连接，无需手输后端地址与密钥，并在本地离线沙箱静默拦截内置面板对 `api.github.com` 的版本更新探测以清零 CORS 报错。
  - **三层零密码自动认证（Cookie + Document-Start 注入 + 内核同源校验）**：
    1. **Layer 1（App 私有 WebView `HttpOnly` Cookie）**：进入面板前向 `http://127.0.0.1:9090` 注入 `nekopro_clash_auth=<secret>; Path=/; HttpOnly; SameSite=Strict` 会话 Cookie（对外部浏览器与其他 App 完全隔离）；
    2. **Layer 2（`WebViewCompat.addDocumentStartJavaScript` 预注入与回环鉴权代理）**：在前端框架挂载前按面板规范预置 `localStorage`（修正 MetaCubeXD VueUse 原始字符串 `selectedEndpoint` 规范），并仅针对本机 `127.0.0.1:9090` 自动补齐 `fetch` / `XHR` `Authorization: Bearer` 与 `WebSocket` `?token=`（兼容第三方自定义 `https://` 面板跨域免密连接本地控制器），同时在资源拦截层屏蔽陈旧 ServiceWorker 缓存；
    3. **Layer 3（Clash API 双栈鉴权与 CSRF 防护）**：保留外部客户端 `Authorization: Bearer` 与 WebSocket `?token=` 常量时间比对，新增 `nekopro_clash_auth` Cookie 兜底鉴权，严格校验 `Sec-Fetch-Site` 与本机端口匹配的 `Origin`/`Referer` 以阻断跨站请求伪造，并彻底修复 `/logs`、`/traffic`、`/connections`、`/memory` WebSocket 协程泄漏与 `external_ui_download_url` 解压路径穿越防护。
- **全栈 24 项深度审计修复与真机回归加固（Android UI / `:bg` 服务 / `libcore` / 双核适配层）**
  - **崩溃、沉浸式顶栏与交互收口（P0）**：修复 `BaseService` 在 `Stopping` 阶段收到启动指令未调 `startForeground()` 触发的 Android 12+ `ForegroundServiceDidNotStartInTimeException`；修复全部二级设置页（分组、节点、路由）左上角 `X` 返回失效、快速连点空指针与未保存变更拦截丢失；为 `ToolbarFragment` 子页独立绑定 Android 15+ 状态栏高度留白（修复「设置」等侧栏子页顶栏与状态栏重叠）；移除设置页冗余的「显示节点拓扑」开关，统一交由主页右上角「眼睛」图标管控；消除 `ConfigurationFragment`、`GroupFragment`、`SettingsPreferenceFragment`、`StunActivity`、`SendLog` 脱离宿主或后台线程弹窗崩溃，切页时保留 `ConfigurationFragment` 使后台测速跨页不中断。
  - **测速修复、FakeIP 切点保活与 DNS 防泄漏加固（P0/P1）**：修复 `validateSpeedTestCandidateURL` 将 Ookla `http://<host>:8080/speedtest/upload.php` 测速节点误判为降级重定向的问题（保留防私网/FakeIP SSRF 校验），补齐浏览器 `User-Agent` 并在机房出口被 Ookla 封禁时自动回退 Cloudflare 443 HTTPS 测速；开启 FakeDNS 时自动启用按分组隔离共享的 `cache_file (store_fakeip = true, cache_id = group-<id>)`，同组切节点完整保留 `198.18.0.0/15` 域名映射；修复 `mihomo_adapter` 漏调 `installSingBoxResolverOnce()` 及系统 DNS 回退隐患，补齐 VLESS-XHTTP / Mieru / MASQUE UDP `TargetResolver` 与 `IPVersion`；封堵 Mihomo MASQUE HTTP/2 Capsule OOM、`MustNew` 崩溃与中间 CA 误匹配 pin；修复 `multiplexConn.Upstream()` UoT DNS 回退、末位 DNS 超时截断、`forTest` `dns-remote`、链式代理落地 DNS 抢占及 `IpInfoStore` 非 HTTPS 组 DNS 解析盲区。
  - **流量统计、并发与隐私脱敏闭环（P1/P2）**：修复 `SetV2rayStats` 晚于 `box.Start()` 注册的数据竞争、分组切换与面板选点跨进程状态漂移、`StatsBar` 直连速率开关失效与测速多行文案溢出；将 `protect_path` 生命周期绑定至 `VpnService`；为 Android 14+ 代理模式补齐 `specialUse` 前台服务类型；多进程 Room 迁移至 `Dispatchers.IO` 并为 `extractAssets` 增加 `flock` 跨进程文件锁；保护本地手动编辑的订阅配置不被自动刷新静默覆盖；补全通知栏、磁贴、二维码、路由、日志导出、去重弹窗与内联搜索的「隐藏节点地址」全链路脱敏（含域名/IPv4 尾部 `:port`）。
- **开源合规与工程卫生收口**
  - 新增 [`NOTICE`](NOTICE) 第三方开源许可与非官方关联声明，优化文档中上游组件事实性表述，拆分历史版本日志至 [`CHANGELOG.md`](CHANGELOG.md)，为 Gradle Wrapper 补齐官方 `distributionSha256Sum` 校验，记录内置离线面板资产来源与 SHA-256，并清理遗留的无效嵌套工作流目录 `NekoBox/.github/`。

### 历史版本改动日志

`v1.4.7` 及更早历史版本（`v1.0.1` – `v1.4.7`）的完整更新记录已拆分归档至 [`CHANGELOG.md`](CHANGELOG.md)。

## 主要改动

相较上游 `MatsuriDayo/NekoBoxForAndroid`，本项目主要结构性改动包括（详细本地补丁清单见 [`docs/VENDORED_PATCHES.md`](docs/VENDORED_PATCHES.md)）：

- 将客户端配置生成迁移至 `sing-box 1.14` Typed DNS Schema、Endpoint 模型与新版路由/嗅探结构，支持按订阅组隔离的 `domain_resolver` 与有序 `fallback` DNS 传输。
- 直接采用上游原生 `sing-snell`（Snell v4–v6）与 `sing-anytls` 出站实现，并通过 `mihomo_adapter` 桥接 VLESS-XHTTP、MASQUE (Cloudflare WARP) 与内置 Mieru 出站（统一接入 Android VPN `protect` 与出站级严格 DNS 解析）。
- Clash API 仅监听本机回环地址，使用安装级随机 Secret，提供三层免密认证（同源 `HttpOnly` Cookie + `Document-Start` 脚本预注入 + CSRF 校验）与双内置离线仪表板（MetaCubeXD `v1.276.0` / Yacd-meta）。
- 对导入、分享、拓扑探测、通知栏与日志路径做敏感字段收敛与一键隐私遮蔽，避免记录或展示完整 URI、代理地址、密码、PSK 和 Token。

## 构建与内置资产校验

完整 Android 工程位于 [`NekoBox/`](NekoBox/)（Gradle Wrapper `8.10.2` 已通过 `distributionSha256Sum` 固定二进制校验和）。

```bash
# 需要：JDK 21、Go 1.25.5+、Android SDK、NDK 25.0.8775105
# 确保 ANDROID_HOME 与 ANDROID_NDK_HOME 指向有效目录
cd NekoBox

# 首次构建：安装固定版本的 gomobile/gobind 工具并编译 libcore.aar
cd libcore
bash ./init.sh
bash ./build.sh
cd ..

# libcore.aar 会被安装到 app/libs；随后运行单元测试、Lint 门禁与打包
bash ./gradlew :app:testOssReleaseUnitTest :app:lintVitalOssRelease assembleOssRelease
```

APK 内置离线仪表板压缩包来源与 SHA-256 校验值如下：

| 内置资产路径 | 上游来源与版本 | 打包资产 SHA-256 | 许可证 |
| :--- | :--- | :--- | :--- |
| `NekoBox/app/src/main/assets/metacubexd.zip` | [`MetaCubeX/metacubexd` `v1.276.0`](https://github.com/MetaCubeX/metacubexd/releases/tag/v1.276.0)（`compressed-dist.tgz` 解包封装为 `MetaCubeXD-dist/`） | `0c5f75eb7eac9c50b1ef6c729b1066091fbaeccba5341b7776df58e07c6fdff2` | MIT |
| `NekoBox/app/src/main/assets/yacd.zip` | [`MetaCubeX/Yacd-meta`](https://github.com/MetaCubeX/Yacd-meta)（`gh-pages` 构建快照 `f2876c17419e95622a78c73d1aba10fda1a14a47`，继承自上游 `NekoBoxForAndroid`） | `5ab53b5c2dbee58d2074b00b71f228b3f7c0e748c9ac34501d33b01ef186b7a7` | MIT |

## 项目说明

- 本项目为独立个人维护的开源派生工程，**不是** `MatsuriDayo/NekoBoxForAndroid`、`SagerNet/sing-box` 或 `MetaCubeX/mihomo` 的官方版本，与各上游项目及其作者不存在隶属、合作或官方认可关系；文档中提及上游项目名称仅用于客观说明源码来源与协议兼容架构。
- 本项目不会在日志中故意记录或公开代理密码、PSK、Token、完整导入链接等敏感信息。
- 内置 Clash API 仅监听本机回环地址，并使用安装级随机 Secret 认证。
- 协议实现与前端面板来自多个上游开源项目；请同时遵守对应目录中的许可证与 [`NOTICE`](NOTICE) 第三方声明。
- 使用代理工具时，请遵守所在地法律、服务条款和网络管理要求。

## 上游与致谢

- [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
- [SagerNet/sing-box](https://github.com/SagerNet/sing-box)
- [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo)
- [SagerNet/sing-snell](https://github.com/SagerNet/sing-snell)
- [MetaCubeX/metacubexd](https://github.com/MetaCubeX/metacubexd)
- [MetaCubeX/Yacd-meta](https://github.com/MetaCubeX/Yacd-meta)
- [throneproj/ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid)（感谢参考其测速交互、测试套件与多 UA 回退链设计）
- [exclavenetwork/sing-juicity](https://github.com/exclavenetwork/sing-juicity)（感谢维护 sing-juicity 协议适配库）
- [Mahdi-zarei/speedtest-go](https://github.com/Mahdi-zarei/speedtest-go)（提供 Go speedtest 驱动核心）

## License & Third-Party Notices

本项目根许可证见 [`LICENSE`](LICENSE)（`GPL-3.0-or-later`），Android 应用沿用上游的 `GPL-3.0-or-later` 许可（详见 [`NekoBox/LICENSE`](NekoBox/LICENSE)）。
仓库内 vendored/派生组件及内置前端面板保留其各自原始许可证与附加条款（包括 [`sing-box/LICENSE`](sing-box/LICENSE) 中的名称与非关联附加条款、[`mihomo/LICENSE`](mihomo/LICENSE) 等）；完整的第三方开源组件归属、许可证及校验信息见 [`NOTICE`](NOTICE)。
