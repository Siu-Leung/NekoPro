# NekoPro Vendored Upstream Patch Inventory

NekoPro vendors `sing-box` (`v1.14.4`) and `mihomo` (`v1.19.34`) directly in the repository for reproducible offline Android builds. This document tracks NekoPro-specific additions and patches on top of upstream so future upstream security updates can be merged cleanly.

## `sing-box/` (based on `SagerNet/sing-box v1.14.4`)
- `protocol/mihomo_adapter/`: Process-global Android VPN socket protection (`InstallProtectHook`) and strict DNS router bridge (`activeDNSRouter`, `strict: true` fail-closed lookup/exchange with no `SystemResolver` leak) plus UDP resolved packet wrapper.
- `protocol/vless_xhttp/`: VLESS-XHTTP (`splithttp`) outbound adapter backed by mihomo XHTTP transport (`DialerForAPI`, REALITY, XMUX `extra`, ECH, uTLS).
- `protocol/masque/`: Cloudflare WARP / MASQUE (`h3`, `h2`, `h3-l4proxy`) outbound adapter backed by mihomo MASQUE outbound.
- `protocol/mieru/`: Built-in Mieru outbound adapter backed by mihomo Mieru outbound, with per-outbound `options.DomainResolver` wired into `MieruOption.Resolver`.
- `nekoutils/`: NekoBox selector callback bridge (`Selector_OnProxySelected`) and GeoIP/GeoSite rule-set integration hooks.

## `mihomo/` (based on `MetaCubeX/mihomo v1.19.34`)
- `transport/xhttp/`: Backported upstream reuse pool stale-connection fix (`MetaCubeX/mihomo@2be2f08`, #3479).
- `adapter/outbound/mieru.go`:
  - Applied configured UDP `MTU` to `mieruclient.ClientConfig.Profile.Mtu` (TCP unaffected).
  - Added optional `MieruOption.Resolver` (`mierucommon.DNSResolver`) so embedded callers (such as sing-box `domain_resolver`) can supply a per-outbound resolver instead of hardcoding global `resolver.ProxyServerHostResolver`.
