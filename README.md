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
- **MASQUE (CONNECT-IP over HTTP/3 / Cloudflare WARP)** 协议集成
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

`Neko-Pro-1.4.6`（测试分支预发布：`Neko-Pro-1.4.7-rc4`）

- [GitHub Release](https://github.com/Siu-Leung/NekoPro/releases/tag/v1.4.6)
- 核心 APK：`NekoBox-Neko-Pro-1.4.6-arm64-v8a.apk`

### v1.4.7-rc4 测试版改动日志 (2026-11-21)

- **功能重塑为「测拓扑」并根治探测完成后界面无显示的三大真机问题**
  - **修复设置页 `showNodeIpOwner` 缺失 `defaultValue="true"` 静默隐藏全部拓扑 UI 的缺陷**：此前 `global_preferences.xml` 未声明 `app:defaultValue="true"`，导致用户只要进入过一次设置页，AndroidX `TwoStatePreference` 即向数据库写入 `false`，使卡片按钮、拓扑面板与顶部状态栏全面隐藏（即便后台跑完整组探测也不渲染任何内容）。现已补齐 `defaultValue="true"`、增加升级自动恢复迁移，并将节点卡片「测拓扑」按钮设为常驻可见、手动发起拓扑检测自动开启展示。
  - **修复 VPN 连接态流量刷新竞态与五源 BGP/ASN 拓扑识别**：新增 `ConfigurationAdapter.updateTopologyResult` 原地同步列表实体并修复 `onUpdated(TrafficData)` 旧 `holder.entity` 覆盖竞态；将归属数据源扩展为 `api.ip.sb` → `api.ipapi.is` → `ipinfo.io` → `freeipapi.com` → `ipwho.is`（外加 `1.1.1.1` / `cloudflare.com` trace 兜底），彻底解决共享机场 IEPL 出口（如阿里 GTM 动态入口 → 香港专线出口）触发单一接口限流的问题，并在拓扑面板直观标注 `[中转]`（琥珀色）与 `[直连]`（翠绿色）。
- **彻底清除日志页 ANSI 256 色转义乱码与首行截断**
  - 修复 `sing-box/log/observable.go` 中 `platformFormatter` 未开启 `DisableColors` 导致向 `neko.log` 持续写入 `[38;5;193m` / `[37m` / `[0m` 终端控制码的问题；在 `platform_box.go` 与 `LogcatFragment` 双端剥离 ANSI CSI 序列，修复 `SendLog.getNekoLog` 尾部截取断行残片（如 `E[0m`），并按 `INFO`（翠绿）/ `WARN`（琥珀）/ `ERROR`（珊瑚红）/ `DEBUG`（次级灰）异步高亮渲染日志。

### v1.4.7-rc3 测试版改动日志 (2026-11-21)

- **全量二级菜单、嵌套子菜单与全部子页面 Surge 化重塑**
  - **全系设置与 19 套协议编辑器升级为 Surge Inset Grouped 圆角卡片列表**：新增 `SurgePreferenceStyler`，将全局设置（`SettingsPreferenceFragment`）、分组设置（`GroupSettingsActivity`）、路由规则编辑（`RouteSettingsActivity`）以及全部 19 个协议配置页（VLESS / VMess / Trojan / AnyTLS / Snell / Hysteria / TUIC / Juicity / Mieru / ShadowTLS / WireGuard / Naive / SSH / SOCKS / HTTP / 自定义配置等）统一渲染为 iOS/Surge 内嵌分组圆角卡片（`14dp` 圆角 + `1dp` 轻描边 + `16dp` 缩进发丝分隔线）。
  - **全部二级弹出菜单、三级嵌套菜单与下拉框统一为 Surge 浮层**：重塑顶栏 `+`（含「手动输入配置」二级协议子菜单）、顶栏 `⋮`（含「排序与布局」二级子菜单）、节点卡片「分享」（二维码 / 剪贴板 / 导出配置二级子菜单）以及设置项 `SimpleMenuPreference` 下拉弹窗，统一采用 `14dp` 圆角纯净表面 + `1dp` 发丝描边与高对比排版；侧边抽屉菜单项升级为 Surge 圆角胶囊高亮条，全面适配 Android 15+ 状态栏安全边距。
  - **补齐全部二级工具与子页面 Surge 视觉一致性**：完成分应用代理（`AppManagerActivity` / `AppListActivity`）、代理链编辑（`ChainSettingsActivity`）、配置 JSON 编辑器与扩展键盘栏（`ConfigEditActivity`）、实时日志控制台（`LogcatFragment`）、STUN NAT 检测（`StunActivity`）、关于页（`AboutFragment`）、分组弹窗与测速/探测进度弹窗的 Surge 扁平化改造。
- **彻底打通「入口 IP + 服务商」与「出口 IP + 服务商」全链路探测与常驻展示**
  - **修复 Cloudflare WAF `403` 拦截与直连被墙问题**：修复 `IpInfoStore` 默认发送 `okhttp/*` UA 被 `api.ip.sb` 前端 Cloudflare WAF 拦截导致入口/出口归属服务商全部返回空值的问题；新增 `api.ip.sb` → `ipwho.is` → Cloudflare `trace` 多源容灾链路与 6 小时内存归属缓存，并优先复用节点自身隔离代理隧道查询入口与出口归属，即使未开启主 VPN 也不受本地运营商阻断影响。
  - **内核级 `BoxInstance` 原生严格代理探测与全协议 DNS 支持**：`libcore` 新增 `FetchHttpsJson`（严格限定经由 `"proxy"` 出站或 WireGuard `"proxy"` Endpoint 拨号，绝不回退直连）与 `LookupDomainWithTag`（原生支持 `https`/`h3`/`tls`/`quic`/`udp`/`fallback` 全部组 DNS 协议），彻底解决 WireGuard、代理链、自定义配置、DoT/DoQ/DoH3 组 DNS 及 `autoAdoptNodeDns` 纯节点订阅下无法探测出入口的问题。
  - **持久化双行徽标展示 + 顶部状态栏遥测 + 批量整组查询**：修复旧版将 `exitIp` / `exitProvider` 强制写空导致切页即丢的问题（升级自动清理历史遗留非严格字段，仅在服务器地址或端口变更时失效）；节点卡片与顶部 Surge 状态卡同步以「`入口`（蓝）+ IP + 国旗/ASN/服务商」与「`出口`（绿）+ IP + 国旗/ASN/服务商」双行呈现；首次授权后一键直查，连接节点或单节点测速后自动补齐，并在顶栏 `⋮` 菜单新增「查询本组出入口 IP / 服务商」并发批量探测。

### v1.4.6 改动日志 (2026-11-21)

- **全新 Surge 风格界面精修（日间浅灰分组底板 + 夜间深邃黑曜石表面）**
  - **Surge 扁平发丝线层次**：全局切换为 iOS/Surge 分组画布底色（日间 `#F2F2F7` / 夜间 `#0B0D11`）与纯净卡片表面（日间 `#FFFFFF` / 夜间 `#16181F`），移除厚重阴影改用 `1dp` 轻描边，顶栏与分组标签改为无阴影平铺导航并自动适配系统状态栏/导航栏明暗图标，全面兼容 21 套主题强调色与深浅色模式。
  - **Surge 顶部状态与快捷分流条**：主界面节点列表顶部新增轻量状态卡片，实时展示连接状态绿/黄/灰圆点与当前选中节点名，右侧内置一键「规则模式 / 全局模式」切换胶囊，点击卡片可快速定位到当前节点。
  - **Surge 风格节点卡片与底部测速坞**：节点延迟采用等宽数字与三色语义分级（≤150ms 翠绿 / ≤350ms 琥珀 / >350ms 珊瑚红），选中卡片高亮描边与胶囊指示条；底部状态坞改为 Surge 纯净表面 + 右下角拇指区 FAB 停靠与水平单行上下行速率展示；侧边栏补齐极简 NekoPro 双核标题头，订阅、路由、规则库与备份页同步统一为 Surge 视觉语言。
- **修复「出入口 IP / 服务商」按钮消失与探测稳定性**
  - **彻底解决按钮被挤出卡片的问题**：将节点卡片的「测速」与「IP/归属」操作按钮独立为专属操作行，无论单列还是双列布局、长协议名或测速结果多行展示，均不再被挤压裁剪，文字采用高对比主色在全部主题下清晰可读。
  - **Surge 诊断面板样式呈现入口/出口 IP 与服务商**：探测结果以独立的柔和内嵌诊断面板展示，点击面板即可再次刷新；探测过程新增「查询中…」实时状态与忙碌提示。
  - **增强探测成功率**：`IpInfoStore` 为组内 DoH 查询补齐底层网络失败时的安全回退（仍严格过滤 FakeIP `198.18.0.0/15` 与保留段），放宽 `api.ip.sb` 服务商查询（8s）与隔离测试盒出口探测（12s）超时，并优化出口探测放行条件。
- **全量 31 项深度代码审计修复（P0–P3）**
  - **P0 安全与兼容性**：彻底加固本地 Clash 仪表板 WebView（同源子资源拦截、阻断恶意 `hostname`/`port` 参数注入、严格按本地 `baseURL` 同步 `secret`、防隐藏 iframe 静默外跳）；重构 `speedTest`/`pingTest`/`urlTest` 统一并发令牌状态机与 `NonCancellable` 清理；将 `UniversalFmt` 与 `AnyTLSCertificatePin` 从 API 26 专有 `java.util.Base64` 迁回 API 21 兼容的 Guava `BaseEncoding`，并在 `lint.xml` 将 `NewApi` 设为 `fatal` 与补齐 PR Android 编译单测门禁。
  - **内核、DNS 与网络防泄漏**：为 MASQUE 增加实例级 `TargetResolver` 修复测试盒解析故障；`probeNodeExit` 补传 `LocalResolverImpl`；`IpInfoStore` 严格屏蔽 FakeIP `198.18.0.0/15`/私网/CGNAT/ULA 并使组 DoH 绑定底层物理网络与自定义 `ca.pem`；`libcore/http.go` 增加重定向防降级/SSRF 策略与 8 MiB/超时硬上限；`GroupNodeDnsPlan` 导出 JSON 自动将私有 `fallback` 降级为上游标准端点并在含私有 DNS 时弹窗确认；`GroupSettingsActivity` 输入框启用 `FLAG_SECURE`。
  - **订阅、协议与存储可靠度**：收紧 SnakeYAML 防别名炸弹与深层嵌套；拒绝 HTML 维护页误作订阅清空整组；Clash YAML 跳过已知不支持协议并补齐 `wireguard` 支持；修齐 Mieru 上游分享链接（`?port=`/`?profile=`/`a:b` 冒号范围与枚举大小写）、WireGuard 多行 `AllowedIPs`/CIDR/Keepalive、AnyTLS `alpn`/含冒号密码/PEM 冲突检测、Snell 拒绝不支持的 v1–v3；备份导入改用严格 Kryo 校验（不再吞掉 `KryoException`）；`WorkManager 2.10` 多进程组件绑定 `:bg` 且保留 `InitializationProvider`；修复 `TestInstance`/`GuardedProcessPool`/`SpeedTestRunner` 并发取消资源泄漏与 `AssetsActivity`/`ConfigurationFragment` 主线程列表更新竞态。

### v1.4.5 改动日志 (2026-11-21)

- **订阅 DNS 识别改为按客户端回退**：默认先用 FlClash 的 UA 请求完整配置，拿不到 DNS 就依次换 Clash Verge、Clash Meta、NekoPro；只要某个客户端返回了带 `dns` 的完整配置就采用。仍然只读解析，不访问候选端点。
- **可手动指定客户端**：识别失败或只返回纯节点时，弹窗提供「换个客户端重试」，可指定用哪个客户端 UA 再拉一次，不再只能靠默认顺序碰运气。
- **测速可强制打断**：卡住的测试会一直占着令牌，导致之后点测速无反应。现在令牌被占用时会提示「强制开始」，打断上一个测试（URL Test / ping / 测速）并立即开始新的。三个入口都支持。
- 组 DNS 输入框继续支持多行；提示文案区分「已有测试仍在运行」与「强制打断」。

### v1.4.4 改动日志 (2026-11-21)

- **修复：组 DNS 多端点只剩一个**。分组设置里的「节点域名 DNS」编辑器此前沿用 AndroidX 默认单行输入，多行内容被压成一行显示；现在改为多行输入框，并在摘要里显示已配置的端点数量。
- **修复：点过「IP/归属」后测速和 URL Test 失效**。入口/出口探测会临时创建一个测速盒，它此前按主实例身份注册全局 mihomo DNS 解析器，临时盒关闭后全局解析器指向已销毁的实例。现在测试实例只登记 socket protect，不再改写进程级解析器。
- **测速改为独立按钮**：卡片底部新增「测速」按钮，长按节点不再触发测速，避免与入口查询和列表滑动冲突。
- 单次 DNS 回退尝试改为 1.2 秒、URL 测速超时放宽到 8 秒，给多端点解析留出握手时间；剩余预算不足时直接失败，不再启动无法完成的子查询。

### v1.4.3 改动日志 (2026-11-21)

- **组 DNS 容错**：完整 Clash 配置可从分组设置 →「订阅 DNS 候选」按类型查看并多选，按顺序主备。只有明确指向本配置 DNS 监听的回环项，才允许自动采纳 `nameserver` 的同路径私有 DoH 镜像；纯节点订阅缺 DNS 时会说明，不猜测地址。
- **真实入口导出**：导出节点链接、二维码、配置或整组节点时，使用组内私有 DoH 的实际解析结果替换可转换节点的连接地址，保留 TLS SNI、证书固定与显式 Host。不改原节点、不从公网 DNS猜测；解析失败或不支持时保留原域名并提示。固定 IP 是导出时的快照。
- **节点地址和服务商**：节点卡片可按需检测入口/出口 IP 与归属。开启「隐藏地址」后仍显示服务商，只遮住地址和名称里的地址；服务商查询使用 `api.ip.sb`，仅在确认后请求。无法证明出口经过指定节点时不显示出口结果。
- 组 DNS 缺失不再阻止公网可解析的节点测速；运行中的任务占用测速时会明确提示。Room 9/10 升级仅新增字段，保留已有节点和备份。

### v1.4.2 改动日志 (2026-11-21)

- 参照 Husi 将「节点域名 DNS」提升为**所有分组**通用的「出站 DNS」，保留订阅组自动采纳和旧手填值；普通订阅即使没有 `dns.proxy-server-nameserver` 也能手填解析器刷新。
- 精确隔离节点域名：显式 `domain_resolver` 跟随每个节点所属分组（含跨组链与测速）；同名节点域名被两个组指定不同解析器时，TUN 查询直接拒绝，不再任意选一个私有 DNS。
- 自动采纳仅接受一个可移植的公网 DNS 端点：排除网站 DNS、其他客户端的回环监听和 Mihomo 专有后缀；自动模式缺 DNS 时保留原节点和解析器，并显示明确状态。
- AnyTLS 导入将证书 DER 的 SHA-256 固定 `fingerprint` 与 uTLS `client-fingerprint` 分开存储，参考 Husi 上游 sing-box 1.15 补齐叶证书固定，修复 `x509: certificate signed by unknown authority` 时“明明有 pin 却被当成浏览器指纹”的问题；**不使用全局跳过证书验证**。

### v1.4.1 改动日志 (2026-11-21)

- **紧急热修（真机崩溃定位）**
  - **修复连接/测速 `StackOverflowError` 崩溃**：升级 `Gson 2.11.0` 后，`SingBoxOptionSerializer` 向 `getDelegateAdapter` 传入未注册匿名 `TypeAdapterFactory` 会返回自身序列化器并触发无限递归；现改为标准注册的 `SingBoxOptionTypeAdapterFactory` 并补充 `SingBoxOption.asMap()` 纯 JVM 回归单测。
  - **修复「关于」页快速切换 `IllegalStateException` 崩溃**：`AboutContent.getMaterialAboutList` 在后台 `AsyncTask` 执行时改用传入的 `activityContext.getString(...)`，避免页面切换脱离宿主后调用 `Fragment.getString(...)` 崩溃。
  - **补齐主题属性 `itemShapeFillColor`**：消除 `MainActivity` 启动加载侧栏颜色列表时的 `ResourcesCompat` `UnsupportedOperationException` 警告。
- **全量逻辑审计修复（v1.3.0 → v1.4.1 二次复核）**
  - **修复 Kryo 分块流边界错位与备份恢复污染**：移除 `MieruBean`、`WireGuardBean`、`SnellBean` 与 `SubscriptionBean` 按版本字段上的 `input.limit() - input.position() > 0` 误判（`ByteBufferInput(InputStream)` 按 4096 字节分块读取，跨块边界会误跳过字段并污染后续 `AbstractBean`/`ProxyGroup` 流），`KryoConverters.deserialize` 改为直接构造 `ByteBufferInput(bytes)`，并补充 32 字节极小分块流回归单测。
  - **补齐 MASQUE / VLESS-XHTTP (QUIC/ECH) 与 Mieru 出站域名解析隔离**：在 `mihomo` `BasicOption`、`Base.DialOptions()` 与 `ECHOptions` 接入出站级 `Resolver` 并由 `mihomo_adapter.NewOutboundResolver` 绑定 sing-box `domain_resolver`（阻断 QUIC `common.DialQuic` 与 ECH `HTTPS` 记录绕过订阅组节点 DNS 回落全局直连）；修复 `singBoxMieruResolver` `ip4`/`ip6` 过滤、实例级 `ResolveDialer` 绑定与空传输管理器严格报错。
  - **堵死非测速实例 mihomo 远端解析回落直连**：`mihomo_adapter` 严格模式在非测速主实例缺失 `dns-remote` 时禁止回退至默认 `dns-direct` / `dns-local` 传输，彻底阻断自定义配置下的远端目标域名泄漏。
  - **加固订阅组「节点域名 DNS」严格失败与防泄漏闭环**：`ConfigBuilder` 对非法组节点 DNS 直接报错中止（不再静默降级 `dns-direct`）并在 `GroupSettingsActivity` 输入时即时校验；跳过外部插件本地 SOCKS 占位出站绑定；将配置了专属节点 DNS 的组排除出 `bypassDNSBeans`（防止 TUN 劫持 DNS 将私有节点域名发往 `dns-direct`），同时将组节点 DNS 服务器自身域名加入 `domainListDNSDirectForce` 引导解析；`RawUpdater` 在配置节点 DNS 时自动跳过本地 `forceResolve`。
  - **修复自定义根证书注入一致性与 HTTP/2 连接复用**：去除 `NewSingBoxInstance` 重复调用 `injectCustomRootCA`、补齐 `NewTestSingBoxInstance` 与 `UrlTest` 自定义根证书注入并尊重 `certificate.store = "none"`；`certs.go` / `http.go` 引入证书代际计数 `customCertGen`，修复加载 `ca.pem` 后每次请求重建 Transport 导致 `KeepAlive()` 连接池失效的问题。
  - **修复订阅更新锁误删、重复通知与截断 JSON 误解析**：`GroupUpdater.executeUpdate` 在并发重入时于 `try` 之前直接返回（避免 `finally` 误清正在运行的更新锁与抛出 `CancellationException` 中断批量更新），移除 `RawUpdater.doUpdate` 尾部重复的 `finishUpdate`；截断 JSON 订阅在 `forSubscription` 下严格报错，不再静默回落文本节点抓取；Mieru 仅填 `port-range` 时自动提取起始端口作为有效默认 `serverPort`；「关于」页检查更新与规则库在线更新在已连接代理时切换为严格 `forceSocks5`。

### v1.4.0 改动日志 (2026-11-20)

- **订阅组专属「节点域名 DNS」（手动绑定 + Clash 自动采纳）**
  - **分组隔离绑定**：在订阅组设置新增可选「节点域名 DNS」与「自动采用订阅节点 DNS」开关（Kryo v2 向后兼容）；`ConfigBuilder` 为配置了节点 DNS 的订阅组注册独立 `dns-group-<id>` 解析器并逐出站绑定 `domain_resolver`（支持链式代理各跳按所属组独立解析、测速与正常连接一致），不接管普通网站、规则集与 FakeIP。
  - **Clash 自动采纳与 UA 协商**：开启自动模式后按 `clash-meta` → `FlClash/v0.9.4 clash-verge Platform/android` 顺序协商完整 Clash YAML，严格校验 `dns.proxy-server-nameserver`（拒绝 `#PROXY` 后缀、多服务器截断与 `?query` 失真）并要求与节点来自同一份响应；刷新失败保留旧节点与旧 DNS 快照。
- **协议参数保真与清理（WireGuard / Mieru / Snell）**
  - **WireGuard**：`WireGuardBean` 与 `.conf` 导入保留 `AllowedIPs` 与 `PersistentKeepalive`（已有节点默认行为不变），并接入编辑页与 sing-box Endpoint 生成。
  - **Mieru**：补全 `port_range`、`multiplexing`、`handshake_mode` 字段、`mieru://` 分享链接导入导出与 Clash YAML `type: mieru` 解析，移除已不可达的外部 `mieru-plugin` 启动与导出残支。
  - **Snell**：补全 `userkey` 编辑页、`snell://` 链接与 Clash YAML 导入导出并接入原生 `sing-snell` 出站。
- **工程维护与 CI 修复**
  - 新增 [`docs/VENDORED_PATCHES.md`](docs/VENDORED_PATCHES.md) 记录 `sing-box` 与 `mihomo` 的全部私有补丁边界。
  - 修复 `android-emulator-runner` 多行脚本拆分子 shell 导致升级测试变量丢失的问题。
  - 彻底移除 `libcore/certs.go` 对 `//go:linkname crypto/x509.systemRoots` 的依赖，改为线程安全自定义证书池并分别注入 `httpClient`、sing-box `options.Certificate` 与 `mihomo/component/ca`。
  - 关闭 `android.enableJetifier`，并将 Coroutines（`1.9.0`）、AndroidX Core/AppCompat/Fragment/Navigation/Work 与 Material（`1.12.0`）、Gson（`2.11.0`）升级至现代版本。

### v1.3.1 改动日志 (2026-11-20)

- **安全与代理隔离加固**
  - **阻断订阅与规则库更新静默回退直连**：`libcore/http.go` 新增严格代理模式 `ForceSocks5(port)` 并补齐 `c.mu` 并发锁；订阅更新在已连接代理或后台「仅连接时更新」模式下强制走本地 SOCKS5（失败直接报错，不再回退直连 TCP 或 HTTP/3），并在请求结束后释放 HTTP 客户端；`assets_update.go` 下载 `geoip.db` / `geosite.db` 后先校验 `maxminddb` / `geosite` 结构完整性再原子替换。
  - **堵死 mihomo 桥接 DNS 泄漏**：`sing-box/protocol/mihomo_adapter/protect.go` 将远程解析传输收窄为 `["dns-remote"]`，并在 `activeDNSRouter == nil` 时于 `Lookup*` 与 `ExchangeContext`（含 ECH）统一禁止回退系统 DNS。
  - **自定义根证书重置修复**：`libcore/certs.go` 首次修改前快照原始系统证书池并加锁串行更新，修复清空 `ca.pem`（`UpdateRootCACerts(nil)`）后旧自定义 CA 仍残留的问题。
  - **本地 Clash 仪表板同源隔离**：`WebviewFragment` 加载本地 `http://127.0.0.1:9090/ui` 面板时限制 WebView 仅允许同源导航，外部链接改由系统浏览器打开。
- **订阅稳定性、协议桥接与 CI 修复**
  - **订阅取消与防误删**：`GroupUpdater` 不再吞掉 `CancellationException`，并在 `NonCancellable` `finally` 中保证清理更新状态；`RawUpdater` 为 SIP008 JSON 订阅补齐半坏快照拒接保护。
  - **Mieru 出站支持独立 `domain_resolver`**：在 `mihomo/adapter/outbound/mieru.go` 增加 `Resolver` 注入并在 `sing-box/protocol/mieru/outbound.go` 接入 `options.DomainResolver`，不再绕过出站 DNS 硬走全局解析器。
  - **CI 升级回归测试去硬编码**：`.github/workflows/ci.yml` 与 `.github/scripts/check-android-upgrade.sh` 改为从 `NekoBox/nb4a.properties` 动态读取版本号并覆盖所有 `v*` 发版标签。

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
