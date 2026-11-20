# NekoPro

[![CI](https://github.com/Siu-Leung/NekoPro/actions/workflows/ci.yml/badge.svg)](https://github.com/Siu-Leung/NekoPro/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Siu-Leung/NekoPro)](https://github.com/Siu-Leung/NekoPro/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

NekoPro 是一个面向 Android 的个人代理客户端项目，基于
[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)
深度修改而来。

本项目采用 **AI Coding** 辅助开发：需求分析、代码实现、协议移植、审计与测试流程均有人机协作参与；发布结论以实际编译、单元测试和真机验证为准，而不是仅依赖生成式输出。

## 主要特性

- sing-box `1.14.4` 加固版主内核（原生 Snell v4–v6 / 原生 AnyTLS / 新版 Typed DNS Schema / 乐观 DNS 缓存 / `quic-go v0.61`）
- Mihomo `1.19.34` 协议桥接（升级 `uTLS v1.9.0-alpha.1` Chrome 155+ 指纹，合入上游 XHTTP 复用连接池过期修复）
- **MASQUE (CONNECT-IP / CONNECT-UDP / Cloudflare WARP)** 协议集成
- **VLESS-XHTTP + REALITY** 完整支持（兼容 `splithttp`、`mode`、`extra` XMUX、自定义证书与 ECH）
- **Mieru 协议内置原生出站**（无需外部 `mieru-plugin`）
- **Snell 原生集成**（采用 `sing-box 1.14.4` 上游原生 `protocol/snell` 与 `sing-snell`，支持 v4/v5 兼容客户端与 v6）
- **Juicity 协议原生集成**（升级 `sing-juicity v0.3.1`，支持拥塞控制算法配置）
- **真·速度测试模块**（支持按下载/上传速度排序与一键重测失败节点）
- **候选 UA 智能轮询回退链**（解决复杂订阅源对未知 UA 的阻断与降级）
- IPv4 / IPv6 双栈
- AnyTLS、VMess、VLESS、Trojan、Shadowsocks、ShadowTLS
- Hysteria 1/2、TUIC、SSH、SOCKS、WireGuard (Endpoint) 等
- 带本地认证的 Clash API 与内置仪表板
- Android 包名：`moe.nb4a.siu`

## 当前版本

`Neko-Pro-1.3.0`

- [GitHub Release](https://github.com/Siu-Leung/NekoPro/releases/tag/v1.3.0)
- 核心 APK：`NekoBox-Neko-Pro-1.3.0-arm64-v8a.apk`

### v1.3.0 改动日志 (2026-11-20)

- **内核跨代升级（原生优先）**
  - **主内核升级至 `sing-box 1.14.4`**：采用「原生优先」策略，Snell（v4/v5/v6）与 AnyTLS（含 `client_metadata` 安全保护）全面切回上游原生实现并移除历史魔改；仅保留上游尚未提供的 `vless_xhttp`、`masque`、`mieru` 与 `juicity`（升级至 `sing-juicity v0.3.1` 并接入拥塞控制）适配器；同步启用 `no_tailscale` 裁除未使用的 mihomo Tailscale 代码。
  - **应用生成的 DNS 配置迁移至 `sing-box 1.14` Typed DNS Schema**：使用 `local`、`fakeip`、`predefined NOERROR`、`route.default_domain_resolver` 与 `query_type`。DNS 服务器地址为域名时，即使同时设置了 `detour`，也保留指定的 `domain_resolver`；IP 字面量不需要域名解析器。
  - **旧完整配置**：常用旧 DNS 服务器写法（如 UDP、系统 DNS、DoH）、FakeIP 和引用固定响应的 DNS 规则在启动时转换；不改已保存的 JSON，导出仍为原文。带有不支持的旧选项时会指出相应字段，而不是猜测出口或解析行为。
  - **Mihomo XHTTP 连接复用修复**：合入 `MetaCubeX/mihomo@2be2f08`（#3479），避免 XHTTP 复用池挑到已过期或关闭的底层连接。
  - **Mieru UDP MTU**：应用生成的 Mieru 出站在 UDP 传输时使用节点配置的 MTU（默认 1400），并传递给 Mihomo 客户端；TCP 传输不应用该设置。
  - **版本命名**：应用显示名为 `Neko Pro`，应用版本标识为 `Neko-Pro-1.3.0`；内核统一显示 `sing-box 1.14.4` 和 `mihomo 1.19.34`。
- **高优先级安全与数据保护修复**
  - **数据库迁移与防数据丢失（H16）**：补齐 Room `MIGRATION_1_2` / `MIGRATION_2_3` 手写迁移，移除无条件 `fallbackToDestructiveMigration()`，改为仅降级回退。
  - **跨进程日志锁与 Protect 错误传播（H1 / H7）**：`libneko/neko_log` 增加跨进程 `flock` 锁与轮转后 `os.SameFile` 自动重开；`protect_server` 真实回传 `AutoDetectInterfaceControl` 错误码并在客户端校验。
  - **隐私隔离与解压炸弹防护（H12 / H17 / H6）**：收窄 FileProvider `cache_paths.xml` 仅暴露 `share_backup/` 与 `log/` 并启动自动清理残留明文备份；`libcore/io.go` `Unxz`/`Unzip` 增加 512 MiB 上限、4096 条目上限、符号链接拦截与隐式父目录创建；`assets_update.go` 改用 `os.CreateTemp` 并支持代理。
  - **Root / 插件 / 自定义根证书治理（C1 / C10）**：Hysteria FakeTCP Root 执行增加默认关闭的显式开关；插件校验支持同签名免打扰 + 白名单与 TOFU；新增应用私有目录自定义 Root CA PEM 导入/清除管理入口。
- **订阅、协议与稳定性修复**
  - **Clash 订阅刷新**：如果 YAML 或任一节点解析失败，不用仅解析成功的部分覆盖现有订阅；没有候选返回完整可用列表时，保留已有节点。手动导入可跳过坏节点，与订阅刷新不同。另修复订阅节点重排和选中节点清理，以及 WireGuard IPv6、SIP008 JSON、VLESS-XHTTP 等格式的部分解析问题。
  - **协议与 `libcore`**：AnyTLS 遵循全局 `globalAllowInsecure`；Mieru 不再依赖外部插件；修复 DNS 解析协程的取消、测速超时与并发、资源解压和 Android Q 以下本地解析回落等问题。
  - **序列化、WebView 与服务**：修复 `SubscriptionBean` 按版本反序列化、`AbstractBean` 并发序列化、部分 WebView URL 跳转和后台服务取消/重启问题；生成的 WireGuard 配置使用 `endpoints[]`。
- **正式发布检查**：CI 通过 `libneko`（含竞态检测）、Mihomo 的 XHTTP 与 outbound 包（含 Mieru MTU 回归测试）、`sing-box`（指定构建标签，不含 `common/netns`）及 `libcore` 的 Go 测试后，才进行 Android 发布构建。发布前还须通过 `ossRelease` JVM 单元测试和 Lint，并验证四个 APK 均使用 v1.2.2 的签名证书。v1.3.0 另在隔离模拟器上检查从 v1.2.2 覆盖安装后，原先保存的测试节点仍在。这些检查不代表所有协议已在真机验证。

### v1.2.2 改动日志 (2026-11-20)

- **更新与自动发布链路闭环**
  - **「关于」页接入正式版检查更新**：新增 `AppUpdatePolicy` 语义化版本比对（修复子串匹配导致 `1.2.1` 漏检 `1.2.10` 或反向提示降级的问题），修复 `Libcore.newHttpClient()` 未关闭资源泄漏、GitHub API 限流/异常 JSON 报错与防连点，校验官方 Release 链接后安全跳转下载页。
  - **规则库（GeoIP / GeoSite）热更新生效**：`AssetsActivity` 导入与在线更新改为唯一临时文件原子替换、最小体积校验并显式调用 `ResetGeoipCache`/`ResetGeositeCache` 及提示重载；Go 层 `geoip.go`/`geosite.go` 增加基于 `os.FileInfo`（inode/大小/mtime）的自动变更检测，跨 `:bg` 进程更新规则库后无需重启进程即可生效。
  - **CI 自动推送 GitHub Releases**：合入 `main` 且检测到 `nb4a.properties` 为未发布的新版本时自动创建 `v*` tag 并发布签名 APK + 校验和至 Releases 下载页，自动从 `README.md` 提取对应版本更新说明；避免 `main`/发版 tag 触发并发误取消，收紧 job 权限并纳入 `libneko` Go 单测。
- **稳定性、测速一致性与编辑页修复**
  - **测速结果防覆盖与状态一致（H10）**：`TrafficLooper` 持久化流量改为仅更新 `tx`/`rx` 的局部 SQL，避免断开或切换节点时用旧快照覆盖期间的测速结果与编辑；测速成功自动清理残留错误状态，测速失败清空过期速度值并同步 UI；修复速度排序分组测速后自动重排、`speedTest` `finally` 块在 `NonCancellable + Main` 线程安全关闭弹窗以及 `pingTest` `underlyingNetwork!!` 空指针。
  - **后台服务并发与生命周期修复（H11）**：`BaseService.Binder.callbackIdMap` 改用 `ConcurrentHashMap<IBinder, Int>` 并在 `onCallbackDied` 清理已死客户端；`SelectorOnly` 重载、`urlTest` 与待机休眠回调均先校验 `proxy.isInitialized()` 避免未初始化 `lateinit box` 崩溃；`ProxyInstance.close()` 改为先停止 `TrafficLooper`（含等待轮询退出）再关闭 `box`。
  - **屏幕旋转编辑数据保护（H13）**：`StandardV2RaySettingsActivity` 旋转重建后自动恢复 `tmpBean` 与 Trojan/VLESS 字段绑定（不再清空 Trojan 密码或把 VLESS `xhttp` 重置为 `tcp`）；`ProfileSettingsActivity` 旋转保留 `dirty` 与 `isSubscription` 状态；`RouteSettingsActivity` 与 `GroupSettingsActivity` 旋转后重新注册变更监听，并修复 `EXTRA_PACKAGE_NAME` 预填路由保存时报“Empty route”的问题。

### v1.2.1 改动日志 (2026-11-19)

- **安全与权限加固**
  - **插件加载校验与 Root 隔离**：优先加载 APK 内置原生插件，对第三方插件实施已知包名白名单与首次使用（TOFU）签名证书固定，限制可执行路径不得逃逸 `nativeLibraryDir`；Hysteria FakeTCP 仅允许使用内置插件并将 `su -c` 命令整体转义为单参数。
  - **资产导入与快捷方式保护**：自定义规则库导入接入 `AssetImportPolicy` 防止路径穿越；为 `QuickToggleShortcut` 增加签名权限保护，并将 `ACTION_CREATE_SHORTCUT` 隔离至独立 `<activity-alias>`；`BootReceiver` 显式校验系统广播 Action。
  - **密钥防泄漏与存储隔离**：导出配置时自动剔除 `clash_api`（避免泄漏 `ClashApiSecret` 与本地路径）；WebView 控制台日志脱敏、限制仅允许 `http/https` 面板地址并将 `localStorage` 密钥比对改为安全 JSON 引用；自动备份配置全面排除 `root/file/sharedpref/database`；崩溃日志扩大敏感键脱敏并统一脱敏代理链接解析报错；自定义 `ca.pem` 改为仅从应用私有内部目录同步加载并追加至系统证书池（不再替换系统根证书）。
  - **Unix Socket 保护服务加固**：`protect_server` 强制校验 `SO_PEERCRED` 同 UID、设置 `0600` 权限、单连接 5 秒超时并在任何异常路径关闭所有收到的文件描述符。
- **Go 内核与协议栈修复**
  - **多实例并发隔离与 DNS/UDP 修复**：测速临时实例不再覆盖主代理实例的保护回调；`mihomo_adapter` 区分直连主机解析与远端目标解析，MASQUE 开启远端 DNS 解析时走隧道内 DNS；VLESS-XHTTP/MASQUE/Mieru 补齐 UDP NAT 地址映射与实例引用释放，AnyTLS 未配置 `client_metadata` 时恢复默认客户端标识。
  - **GeoIP/GeoSite 并发竞态与句柄泄漏修复**：以 `sync.RWMutex` 与代际校验保护缓存读写与重置，释放旧句柄防止 FD 泄漏。
  - **日志轮转与 HTTP 客户端修复**：修复 `neko_log` 首次轮转后因残留已关闭文件句柄导致的持续写入失败并同步重定向 `stderr`；`libcore/http.go` 改为按响应头超时（避免大文件下载中途超时）、隔离单请求 `AllowInsecure` TLS 配置、修复 `Execute()` panic 返回 `(nil, nil)` 及 H3 竞速 panic 死锁与慢分支阻塞。
- **DNS / 路由、解析与稳定性修复**
  - **路由与配置生成修复**：`ConfigBuilder` 增加链式代理成环保护与前置/落地代理递归解析；修复 `globalOutbounds` 复用导致悬空 `detour`；非纯域名/UID 规则不再误生成 DNS 规则；`hijack-dns` 置后于 `sniff`；多播阻断拆分为目标/源两条独立规则；移除尾跳直连 DNS 注入；`Rule_DefaultOptions.checkEmpty()` 补全 `ip_is_private`、`source_ip_is_private`、`source_port`、`network`、`protocol` 检查。
  - **崩溃、死循环与兼容性修复**：修复 Android 8+ `VpnService` 未进入前台即停止导致的崩溃；以 Okio 替代 `java.util.Base64` 恢复 API < 26 兼容；修复 `rulesProvider` 偏好数组越界、`TrafficLooper` 忙等死循环、`NativeInterface` 空指针、WireGuard 缺省 MTU 空指针与 v1 反序列化、`GroupUpdater` IPv6 排序反转、Shadowsocks `sUoT` 开关失效、`RawUpdater` 多处字段映射错误及 `SubscriptionUpdater` 延迟计算错误。
  - **备份恢复与导入防炸弹**：备份恢复改为先全量解析再在 Room 事务内原子写入（避免异常清空现有数据），修复 `ProxyGroup` 序列化字段缺失与 `KeyValuePair` UTF-8 字节长度计算；为 ZIP 导入与 `zlibDecompress` 增加体积/条目上限防护；对齐测速默认参数与 `DataStore` 一致。

### v1.2.0 改动日志 (2026-11-19)

- **内核与新协议升级**
  - **Mihomo 升级至 `1.19.34`**：同步上游 `uTLS v1.9.0-alpha.1`（Chrome 155+ 指纹）、DoH 拨号泄漏修复与 TCP MSS 优化。
  - **新增 MASQUE (Cloudflare WARP) 协议**：完整支持 `masque://` 链接导入导出、Clash YAML 订阅解析、UI 编辑（支持 IPv4/IPv6 本地地址、UDP 转发开关、远端 DNS 解析、BBR 拥塞控制）与 Room 数据库 v9 自动迁移。
  - **VLESS-XHTTP + REALITY 全链路打通**：补齐 XHTTP 出站的 `reality_public_key` / `reality_short_id`、`xhttp_mode`、`xhttp_extra`（XMUX JSON）及 `type=splithttp` 别名兼容；构建通用 `mihomo_adapter` 拨号与 DNS 桥接器，支持链式代理 `detour` 前置跳点并消除嵌入式明文 UDP DNS 泄漏。
  - **WireGuard 迁移至 `endpoints` 架构**：适配 sing-box 1.13+ Endpoint 规范，并显式指定 `route.final = proxy` 防止单 WireGuard 节点直连泄漏。
  - **Snell 连接池复用修复**：修正 `snell/outbound.go` 旁路 `sing-snell` `Reuse` 连接池的问题，补全 v6 `mode` 配置与标准分享链接导出，修复 `v6_test.go` 单测编译。
- **测速闭环与全量审计修复（Closes [#3](https://github.com/Siu-Leung/NekoPro/issues/3)）**
  - **按下载/上传速度排序 & 一键重测失败节点**：分组排序菜单新增“以下载速度 / 以上传速度”降序排列，右上角菜单新增“一键重测失败节点”。
  - **Cloudflare 200M 限时测速结算修复**：10 秒测试窗口到期时只要已有传输流量即按实测时长正常结算吞吐速率，不再将 `<160 Mbps` 节点误判为超时失败。
  - **HTTP 客户端提前 `cancel()` 修复**：修复 `libcore/http.go` 在读取响应体前提前取消上下文导致大订阅或规则集下载断流的问题，并保证错误响应体关闭幂等。
  - **UI 崩溃与路由规则修复**：修复“关于/设置”页旋转屏幕 `FragmentManager` 崩溃、测速最小化切页崩溃、自定义路由规则同填域名与 IP 时清空 `geosite:` 及正则表达式被强制转小写等问题。

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
