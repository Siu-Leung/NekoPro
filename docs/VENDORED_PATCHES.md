# NekoPro Vendored Upstream Patch Inventory

NekoPro vendors `sing-box` (`v1.14.4`) and `mihomo` (`v1.19.34`) directly in the repository for reproducible offline Android builds. This document tracks NekoPro-specific additions and patches on top of upstream so future upstream security updates can be merged cleanly.

## `sing-box/` (based on `SagerNet/sing-box v1.14.4`)
- `dns/`, `option/dns.go`: NekoPro registers its `fallback` DNS transport with explicit ordered member tags. It is shared by native and bridged node dialers and exact TUN DNS rules. Transport errors and transient DNS responses try the next configured member; successful replies and NXDOMAIN are terminal. Each attempt is bounded by the caller's remaining deadline and never adds a global/system fallback.
- `common/tls/`: Backport the leaf-certificate SHA-256 pin from `SagerNet/sing-box v1.15.0-alpha.13` (`option.OutboundTLSOptions.certificate_sha256`, padded Base64). It is distinct from SPKI `certificate_public_key_sha256`; the pin verifier runs even when `insecure` is set, and conflicts or unsupported TLS engines fail closed. Adapted from Husi's pinned upstream design; TLS client verification remains local, not a remote service.
- `protocol/mihomo_adapter/`: Process-global Android VPN socket protection (`InstallProtectHook`), strict DNS router bridge (`activeDNSRouter`, `strict: true` fail-closed lookup/exchange blocking both `SystemResolver` and non-test `dns-direct`/`dns-local` fallback), per-outbound `NewOutboundResolver` bridge for QUIC/ECH server resolution, per-instance `NewTargetResolver` for MASQUE `remote-dns-resolve` in test instances (`activeDNSRouter == nil`), init-only `resolver.DisableIPv6 = false`, and UDP resolved packet wrapper.
- `protocol/vless_xhttp/`: VLESS-XHTTP (`splithttp`) outbound adapter backed by mihomo XHTTP transport (`DialerForAPI`, `BasicOption.Resolver`, REALITY, XMUX `extra`, ECH with per-outbound `Resolver`, uTLS).
- `protocol/masque/`: Cloudflare WARP / MASQUE (CONNECT-IP over HTTP/3) outbound adapter backed by mihomo MASQUE outbound (`DialerForAPI`, `BasicOption.Resolver` for QUIC server domain resolution, and `MasqueOption.TargetResolver` for instance-scoped remote DNS resolution).
- `protocol/mieru/`: Built-in Mieru outbound adapter backed by mihomo Mieru outbound, with per-outbound `options.DomainResolver` / `ResolveDialer`, `"ip4"`/`"ip6"` filtering wired into `MieruOption.Resolver`, colon-to-hyphen port-range normalization, and uppercase enum normalization for `multiplexing`/`handshake_mode`.
- `nekoutils/`: NekoBox selector callback bridge (`Selector_OnProxySelected`) and GeoIP/GeoSite rule-set integration hooks.

## `mihomo/` (based on `MetaCubeX/mihomo v1.19.34`)
- `transport/xhttp/`: Backported upstream reuse pool stale-connection fix (`MetaCubeX/mihomo@2be2f08`, #3479).
- `adapter/outbound/base.go`, `masque.go`, `vless.go`, `ech.go`:
  - Added optional `BasicOption.Resolver` / `BaseOption.Resolver` (propagated via `Base.DialOptions()` -> `dialer.WithResolver(...)`) and `ECHOptions.Resolver` so embedded QUIC (`common.DialQuic`) and ECH HTTPS RR queries honor sing-box's per-outbound `domain_resolver` instead of hardcoding global `resolver.ProxyServerHostResolver`.
  - Added optional `MasqueOption.TargetResolver` / `Masque.targetResolver` so MASQUE target lookups (`remote-dns-resolve == false`) use the owning sing-box instance's DNS router instead of global `resolver.DefaultResolver` during isolated tests/probes.
- `adapter/outbound/mieru.go`:
  - Applied configured UDP `MTU` to `mieruclient.ClientConfig.Profile.Mtu` (TCP unaffected).
  - Added optional `MieruOption.Resolver` (`mierucommon.DNSResolver`) so embedded callers (such as sing-box `domain_resolver`) can supply a per-outbound resolver instead of hardcoding global `resolver.ProxyServerHostResolver`.
