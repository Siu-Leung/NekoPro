# NekoPro Vendored Upstream Patch & Asset Inventory

NekoPro vendors upstream `SagerNet/sing-box` (`v1.14.4`) and `MetaCubeX/mihomo` (`v1.19.34`) source trees directly in the repository (alongside offline web dashboard archives) for reproducible offline Android builds. This document tracks NekoPro-specific additions, patches, and bundled asset checksums so future upstream updates can be audited and merged cleanly.

## `sing-box/` (based on `SagerNet/sing-box v1.14.4`)
- `dns/`, `option/dns.go`, `dns/transport_fallback.go`, `dns/transport/multiplexer.go`:
  - Registers NekoPro's `fallback` DNS transport with explicit ordered member tags, shared by native/bridged node dialers and exact TUN DNS rules. Transport errors and transient DNS responses try the next configured member; successful replies and `NXDOMAIN` are terminal. Final-member timeout honors the caller's remaining deadline without artificial 1.2s truncation, and never adds a global/system fallback.
  - Implements `Upstream()` on `multiplexConn` so UoT UDP DNS stream desync errors are unwrapped and fall back cleanly to DNS-over-TCP.
- `common/tls/`:
  - Backports leaf-certificate SHA-256 pinning from `SagerNet/sing-box v1.15.0-alpha.13` (`option.OutboundTLSOptions.certificate_sha256`, padded Base64), distinct from SPKI `certificate_public_key_sha256`; pin verification runs even when `insecure` is set, and conflicts or unsupported TLS engines fail closed.
- `experimental/clashapi/` (`server.go`, `server_resources.go`, `connections.go`, `api_meta.go`):
  - Constant-time `subtle.ConstantTimeCompare` secret verification for `Authorization: Bearer` and WebSocket `?token=`.
  - Loopback same-origin session cookie fallback (`nekopro_clash_auth`) with `Sec-Fetch-Site` and port-matched `Origin`/`Referer` CSRF validation for the built-in Android WebView dashboard.
  - Fixes WebSocket goroutine/subscription leaks across `/logs`, `/traffic`, `/connections`, and `/memory` (`log/observable.go` non-blocking/closed subscriber cleanup), plus Zip-Slip path traversal hardening in `downloadZIP`.
- `protocol/mihomo_adapter/`:
  - Process-global Android VPN socket protection (`InstallProtectHook`), `installSingBoxResolverOnce()` wiring, strict DNS router bridge (`activeDNSRouter`, `strict: true` fail-closed lookup/exchange blocking both `SystemResolver` and non-test `dns-direct`/`dns-local` fallback), per-outbound `NewOutboundResolver` bridge for QUIC/ECH server resolution, per-instance `NewTargetResolver` for UDP/MASQUE target resolution (`IPVersion` honored), init-only `resolver.DisableIPv6 = false`, and UDP resolved packet wrapper.
- `protocol/vless_xhttp/`:
  - VLESS-XHTTP (`splithttp`) outbound adapter backed by mihomo XHTTP transport (`DialerForAPI`, `BasicOption.Resolver`, UDP `TargetResolver`, REALITY, XMUX `extra`, ECH with per-outbound `Resolver`, uTLS).
- `protocol/masque/`:
  - Cloudflare WARP / MASQUE (CONNECT-IP over HTTP/3) outbound adapter backed by mihomo MASQUE outbound (`DialerForAPI`, `BasicOption.Resolver` for QUIC server domain resolution, and `MasqueOption.TargetResolver` for instance-scoped remote/local DNS resolution).
- `protocol/mieru/`:
  - Built-in Mieru outbound adapter backed by mihomo Mieru outbound, with per-outbound `options.DomainResolver` / `ResolveDialer`, UDP `TargetResolver`, `"ip4"`/`"ip6"` filtering wired into `MieruOption.Resolver`, colon-to-hyphen port-range normalization, and uppercase enum normalization for `multiplexing`/`handshake_mode`.
- `nekoutils/`:
  - NekoBox selector callback bridge (`Selector_OnProxySelected`) and GeoIP/GeoSite rule-set integration hooks.

## `mihomo/` (based on `MetaCubeX/mihomo v1.19.34`)
- `transport/xhttp/`:
  - Backported upstream reuse pool stale-connection fix (`MetaCubeX/mihomo@2be2f08`, #3479).
- `transport/masque/` (`client_h2.go`, `masque.go`):
  - Caps HTTP/2 Capsule variable-length integer frame allocation to prevent OOM DoS, replaces `MustNew` panic paths with error propagation, and restricts leaf-certificate SHA-256 pinning strictly to `PeerCertificates[0]` so intermediate CAs cannot satisfy a leaf pin.
- `adapter/outbound/base.go`, `masque.go`, `vless.go`, `ech.go`:
  - Added optional `BasicOption.Resolver` / `BaseOption.Resolver` (propagated via `Base.DialOptions()` -> `dialer.WithResolver(...)`) and `ECHOptions.Resolver` so embedded QUIC (`common.DialQuic`) and ECH HTTPS RR queries honor sing-box's per-outbound `domain_resolver` instead of hardcoding global `resolver.ProxyServerHostResolver`.
  - Added optional `MasqueOption.TargetResolver` / `Masque.targetResolver` so MASQUE target lookups (`remote-dns-resolve == false`) use the owning instance's DNS router instead of global `resolver.DefaultResolver`.
- `adapter/outbound/mieru.go`:
  - Applied configured UDP `MTU` to `mieruclient.ClientConfig.Profile.Mtu` (TCP unaffected).
  - Added optional `MieruOption.Resolver` (`mierucommon.DNSResolver`) for per-outbound server and UDP target domain resolution.

## Bundled Offline Dashboard Assets (`NekoBox/app/src/main/assets/`)

| Asset Path | Upstream Source & Version | Upstream Artifact Digest | Bundled ZIP SHA-256 | License |
| :--- | :--- | :--- | :--- | :--- |
| `NekoBox/app/src/main/assets/metacubexd.zip` (`metacubexd.version.txt` = `127600`) | [`MetaCubeX/metacubexd` `v1.276.0`](https://github.com/MetaCubeX/metacubexd/releases/tag/v1.276.0) (`compressed-dist.tgz`, repackaged under `MetaCubeXD-dist/`) | `8ed5b1e312136a161eb7bbbfc2c4d7dc95217487d0873739668d897ed366af9c` | `0c5f75eb7eac9c50b1ef6c729b1066091fbaeccba5341b7776df58e07c6fdff2` | MIT |
| `NekoBox/app/src/main/assets/yacd.zip` (`yacd.version.txt` = `3`) | [`MetaCubeX/Yacd-meta`](https://github.com/MetaCubeX/Yacd-meta) (`gh-pages` snapshot `2023-06-14`, archive commit comment `f2876c17419e95622a78c73d1aba10fda1a14a47`, inherited from `MatsuriDayo/NekoBoxForAndroid`) | Archive commit `f2876c17419e95622a78c73d1aba10fda1a14a47` | `5ab53b5c2dbee58d2074b00b71f228b3f7c0e748c9ac34501d33b01ef186b7a7` | MIT |
