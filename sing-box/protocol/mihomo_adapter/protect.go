package mihomo_adapter

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	mDNS "github.com/miekg/dns"

	"github.com/sagernet/sing-box/adapter"
	boxDialer "github.com/sagernet/sing-box/common/dialer"
	boxConstant "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/control"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

var (
	hookMu            sync.Mutex
	hookInstalled     bool
	activeProtectFunc atomic.Pointer[protectFuncHolder]
	activeDNSRouter   atomic.Pointer[dnsRouterHolder]
	resolverInstallMu sync.Mutex
	resolverInstalled bool

	registryMu sync.Mutex
	registry   []*hookEntry // in installation order; the last live entry wins
)

type dnsRouterHolder struct {
	router           adapter.DNSRouter
	transportManager adapter.DNSTransportManager
	test             bool
}

type protectFuncHolder struct {
	fn control.Func
}

// hookKey identifies the sing-box instance owning an entry. Services are
// per-Box pointers, so they are comparable and unique per instance.
type hookKey struct {
	router         adapter.DNSRouter
	networkManager adapter.NetworkManager
}

type hookEntry struct {
	key     hookKey
	refs    int
	test    bool
	dns     *dnsRouterHolder
	protect control.Func
}

type testInstanceKey struct{}

// ContextWithTestInstance marks ctx as belonging to a temporary (URL test /
// speed test) sing-box instance. Hooks installed from such a context never
// take precedence over hooks installed by a regular (main) instance.
func ContextWithTestInstance(ctx context.Context) context.Context {
	return context.WithValue(ctx, testInstanceKey{}, true)
}

func isTestInstance(ctx context.Context) bool {
	v, _ := ctx.Value(testInstanceKey{}).(bool)
	return v
}

// refreshActiveLocked publishes the DNS router / protect func of the most
// recently installed live entry, preferring non-test instances.
// registryMu must be held.
func refreshActiveLocked() {
	var (
		dnsHolder *dnsRouterHolder
		protect   control.Func
	)
	for _, wantTest := range []bool{false, true} {
		for i := len(registry) - 1; i >= 0; i-- {
			e := registry[i]
			if e.test != wantTest {
				continue
			}
			if dnsHolder == nil && e.dns != nil {
				dnsHolder = e.dns
			}
			if protect == nil && e.protect != nil {
				protect = e.protect
			}
		}
	}
	activeDNSRouter.Store(dnsHolder)
	if protect != nil {
		activeProtectFunc.Store(&protectFuncHolder{fn: protect})
	} else {
		activeProtectFunc.Store(nil)
	}
}

// InstallProtectHook registers the sing-box Android VPN socket protect
// callback and encrypted DNS router bridge of the instance owning ctx for
// embedded mihomo outbounds. The returned function releases the
// registration and must be called when the outbound is closed; once all
// outbounds of an instance released it, the previous live instance (if any)
// becomes active again.
func InstallProtectHook(ctx context.Context) (release func()) {
	release = func() {}
	resolver.DisableIPv6 = false
	if ctx == nil {
		return
	}

	entry := &hookEntry{test: isTestInstance(ctx)}
	if dnsRouter := service.FromContext[adapter.DNSRouter](ctx); dnsRouter != nil {
		entry.key.router = dnsRouter
		entry.dns = &dnsRouterHolder{
			router:           dnsRouter,
			transportManager: service.FromContext[adapter.DNSTransportManager](ctx),
			test:             entry.test,
		}
		installSingBoxResolverOnce()
	}
	if networkManager := service.FromContext[adapter.NetworkManager](ctx); networkManager != nil {
		entry.key.networkManager = networkManager
		entry.protect = networkManager.ProtectFunc()
	}
	if entry.dns == nil && entry.protect == nil {
		return
	}

	registryMu.Lock()
	var existing *hookEntry
	for _, e := range registry {
		if e.key == entry.key {
			existing = e
			break
		}
	}
	if existing != nil {
		existing.refs++
		entry = existing
	} else {
		entry.refs = 1
		registry = append(registry, entry)
	}
	refreshActiveLocked()
	registryMu.Unlock()

	var once sync.Once
	release = func() {
		once.Do(func() {
			registryMu.Lock()
			defer registryMu.Unlock()
			entry.refs--
			if entry.refs > 0 {
				return
			}
			for i, e := range registry {
				if e == entry {
					registry = append(registry[:i], registry[i+1:]...)
					break
				}
			}
			refreshActiveLocked()
		})
	}

	if entry.protect == nil {
		return
	}

	hookMu.Lock()
	defer hookMu.Unlock()
	if hookInstalled {
		return
	}
	previousHook := dialer.DefaultSocketHook
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		if previousHook != nil {
			if err := previousHook(network, address, conn); err != nil {
				return err
			}
		}
		if h := activeProtectFunc.Load(); h != nil && h.fn != nil {
			return h.fn(network, address, conn)
		}
		return nil
	}
	hookInstalled = true
	return
}

func installSingBoxResolverOnce() {
	resolverInstallMu.Lock()
	defer resolverInstallMu.Unlock()
	if resolverInstalled {
		return
	}
	// Proxied target domains (resolved by mihomo outbounds before being sent
	// through the tunnel) must not leak to the direct DNS server.
	remote := &singBoxResolver{
		fallback:   resolver.SystemResolver,
		transports: []string{"dns-remote"},
		strict:     true,
	}
	// Proxy server hostnames and direct destinations use direct DNS.
	direct := &singBoxResolver{
		fallback:   resolver.SystemResolver,
		transports: []string{"dns-direct", "dns-local"},
	}
	resolver.DefaultResolver = remote
	resolver.ProxyServerHostResolver = direct
	resolver.DirectHostResolver = direct
	resolverInstalled = true
}

// NewDialer adapts a sing-box N.Dialer (supporting detour chain proxies,
// domain_strategy, encrypted DNS resolution, and Android VPN socket protection)
// into a mihomo C.Dialer for BasicOption.DialerForAPI.
func NewDialer(d N.Dialer) C.Dialer {
	if d == nil {
		return nil
	}
	return &singBoxDialerAdapter{dialer: d}
}

type singBoxDialerAdapter struct {
	dialer N.Dialer
}

func (a *singBoxDialerAdapter) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return a.dialer.DialContext(ctx, network, M.ParseSocksaddr(address))
}

func (a *singBoxDialerAdapter) ListenPacket(ctx context.Context, network, address string, rAddrPort netip.AddrPort) (net.PacketConn, error) {
	var dest M.Socksaddr
	if rAddrPort.IsValid() {
		dest = M.SocksaddrFromNetIP(rAddrPort)
	} else if address != "" {
		dest = M.ParseSocksaddr(address)
	}
	pc, err := a.dialer.ListenPacket(ctx, dest)
	if err != nil {
		return nil, err
	}
	return pc, nil
}

// NewOutboundResolver creates an instance-scoped mihomo resolver.Resolver for
// QUIC dialers (common.DialQuic) and ECH HTTPS RR lookups so embedded outbounds
// (MASQUE, VLESS-XHTTP) honor sing-box's per-outbound domain_resolver /
// domain_strategy instead of bypassing them via global ProxyServerHostResolver.
func NewOutboundResolver(ctx context.Context, dialOptions option.DialerOptions, outboundDialer N.Dialer) (resolver.Resolver, error) {
	strategy := boxConstant.DomainStrategy(dialOptions.DomainStrategy)
	if dialOptions.DomainResolver != nil && dialOptions.DomainResolver.Strategy != option.DomainStrategy(boxConstant.DomainStrategyAsIS) {
		strategy = boxConstant.DomainStrategy(dialOptions.DomainResolver.Strategy)
	}
	if dialOptions.DomainResolver != nil && dialOptions.DomainResolver.Server != "" {
		dnsRouter := service.FromContext[adapter.DNSRouter](ctx)
		transports := service.FromContext[adapter.DNSTransportManager](ctx)
		if dnsRouter == nil || transports == nil {
			return nil, errors.New("missing DNS router or transport manager for domain_resolver: " + dialOptions.DomainResolver.Server)
		}
		if _, loaded := transports.Transport(dialOptions.DomainResolver.Server); !loaded {
			return nil, errors.New("domain resolver not found: " + dialOptions.DomainResolver.Server)
		}
		// 镜像端点回退（2026-11-21）：NekoPro 组节点 DNS 支持多个端点。Kotlin 侧按
		// dns-group-<gid>（主）、dns-group-<gid>-1、dns-group-<gid>-2…（镜像）命名注册
		// transports。这里按前缀收集镜像 tag，主端点解析失败时依序回退，避免单一
		// 私有 DoH 故障导致整组节点不可用。
		var mirrorTags []string
		for _, transport := range transports.Transports() {
			tag := transport.Tag()
			if tag == dialOptions.DomainResolver.Server {
				continue
			}
			if isGroupDNSMirrorTag(dialOptions.DomainResolver.Server, tag) {
				mirrorTags = append(mirrorTags, tag)
			}
		}
		sort.Strings(mirrorTags)
		return &outboundSingBoxResolver{
			router:      dnsRouter,
			transports:  transports,
			serverTag:   dialOptions.DomainResolver.Server,
			mirrorTags:  mirrorTags,
			queryOptions: adapter.DNSQueryOptions{
				Strategy:       strategy,
				LookupStrategy: strategy,
			},
		}, nil
	}
	if resolveDialer, ok := outboundDialer.(boxDialer.ResolveDialer); ok {
		if dnsRouter := service.FromContext[adapter.DNSRouter](ctx); dnsRouter != nil {
			return &outboundSingBoxResolver{
				router:       dnsRouter,
				transports:   service.FromContext[adapter.DNSTransportManager](ctx),
				queryOptions: resolveDialer.QueryOptions(),
			}, nil
		}
	}
	return nil, nil
}

type outboundSingBoxResolver struct {
	router       adapter.DNSRouter
	transports   adapter.DNSTransportManager
	serverTag    string
	mirrorTags   []string
	queryOptions adapter.DNSQueryOptions
}

var _ resolver.Resolver = (*outboundSingBoxResolver)(nil)

// isGroupDNSMirrorTag 判定 candidate 是否是 primary 的镜像端点 tag。
// 约定：主 tag dns-group-<gid>，镜像 dns-group-<gid>-1、dns-group-<gid>-2…
// 数字后缀按字典序即数值序（个位数；组内镜像超过 9 个不现实）。
func isGroupDNSMirrorTag(primary, candidate string) bool {
	if !strings.HasPrefix(primary, "dns-group-") {
		return false
	}
	if !strings.HasPrefix(candidate, primary+"-") {
		return false
	}
	suffix := candidate[len(primary)+1:]
	if suffix == "" {
		return false
	}
	for _, ch := range suffix {
		if ch < '0' || ch > '9' {
			return false
		}
	}
	return true
}

// lookupTransport 按主端点→镜像端点的顺序查找可用 transport。
func (r *outboundSingBoxResolver) lookupTransport(tag string) (adapter.DNSTransport, bool) {
	tr, loaded := r.transports.Transport(tag)
	if loaded {
		return tr, true
	}
	for _, mirror := range r.mirrorTags {
		tr, loaded = r.transports.Transport(mirror)
		if loaded {
			return tr, true
		}
	}
	return nil, false
}

// resolveTransport 返回当前应使用的 transport；主端点缺失时自动落到镜像。
func (r *outboundSingBoxResolver) resolveTransport() adapter.DNSTransport {
	if r.serverTag != "" && r.transports != nil {
		if tr, loaded := r.lookupTransport(r.serverTag); loaded {
			return tr
		}
	}
	return nil
}

func (r *outboundSingBoxResolver) resolveOptions(strategyOverride boxConstant.DomainStrategy) (adapter.DNSQueryOptions, error) {
	if r.router == nil {
		return adapter.DNSQueryOptions{}, resolver.ErrIPNotFound
	}
	opts := r.queryOptions
	if tr := r.resolveTransport(); tr != nil {
		opts.Transport = tr
	} else if r.serverTag != "" {
		if r.transports == nil {
			return adapter.DNSQueryOptions{}, resolver.ErrIPNotFound
		}
		return adapter.DNSQueryOptions{}, resolver.ErrIPNotFound
	}
	if strategyOverride != boxConstant.DomainStrategyAsIS {
		opts.Strategy = strategyOverride
		opts.LookupStrategy = strategyOverride
	}
	return opts, nil
}

func (r *outboundSingBoxResolver) lookup(ctx context.Context, host string, strategy boxConstant.DomainStrategy) ([]netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return []netip.Addr{ip.Unmap()}, nil
	}
	opts, err := r.resolveOptions(strategy)
	if err != nil {
		return nil, err
	}
	// 主端点失败（网络/超时/SERVFAIL）时按序尝试镜像端点；全部失败才返回最后错误。
	addrs, err := r.router.Lookup(ctx, host, opts)
	if err != nil && len(r.mirrorTags) > 0 && r.serverTag != "" && r.transports != nil {
		for _, mirror := range r.mirrorTags {
			tr, loaded := r.transports.Transport(mirror)
			if !loaded {
				continue
			}
			retryOpts := opts
			retryOpts.Transport = tr
			var retryErr error
			addrs, retryErr = r.router.Lookup(ctx, host, retryOpts)
			if retryErr == nil && len(addrs) > 0 {
				return addrs, nil
			}
			if retryErr != nil {
				err = retryErr
			}
		}
	}
	if err != nil {
		return nil, err
	}
	if len(addrs) == 0 {
		return nil, resolver.ErrIPNotFound
	}
	return addrs, nil
}

func (r *outboundSingBoxResolver) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.lookup(ctx, host, boxConstant.DomainStrategyAsIS)
}

func (r *outboundSingBoxResolver) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	addrs, err := r.lookup(ctx, host, boxConstant.DomainStrategyIPv4Only)
	if err != nil {
		return nil, err
	}
	out := make([]netip.Addr, 0, len(addrs))
	for _, ip := range addrs {
		if ip.Unmap().Is4() {
			out = append(out, ip.Unmap())
		}
	}
	if len(out) == 0 {
		return nil, resolver.ErrIPNotFound
	}
	return out, nil
}

func (r *outboundSingBoxResolver) LookupIPv6(ctx context.Context, host string) ([]netip.Addr, error) {
	addrs, err := r.lookup(ctx, host, boxConstant.DomainStrategyIPv6Only)
	if err != nil {
		return nil, err
	}
	out := make([]netip.Addr, 0, len(addrs))
	for _, ip := range addrs {
		if ip.Is6() && !ip.Is4In6() {
			out = append(out, ip)
		}
	}
	if len(out) == 0 {
		return nil, resolver.ErrIPNotFound
	}
	return out, nil
}

func (r *outboundSingBoxResolver) ResolveECH(ctx context.Context, host string) ([]byte, error) {
	msg := &mDNS.Msg{}
	msg.SetQuestion(mDNS.Fqdn(host), mDNS.TypeHTTPS)
	resp, err := r.ExchangeContext(ctx, msg)
	if err != nil {
		return nil, err
	}
	for _, rr := range resp.Answer {
		if httpsRR, ok := rr.(*mDNS.HTTPS); ok {
			for _, val := range httpsRR.Value {
				if echCfg, ok := val.(*mDNS.SVCBECHConfig); ok {
					return echCfg.ECH, nil
				}
			}
		}
	}
	return nil, errors.New("no ECH config found in DNS records")
}

func (r *outboundSingBoxResolver) ExchangeContext(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
	opts, err := r.resolveOptions(boxConstant.DomainStrategyAsIS)
	if err != nil {
		return nil, err
	}
	return r.router.Exchange(ctx, m, opts)
}

func (r *outboundSingBoxResolver) Invalid() bool {
	return true
}

func (r *outboundSingBoxResolver) ClearCache() {
	if r.router != nil {
		r.router.ClearCache()
	}
}

func (r *outboundSingBoxResolver) ResetConnection() {
	if r.router != nil {
		r.router.ResetNetwork()
	}
}

type singBoxResolver struct {
	fallback resolver.Resolver
	// transports lists DNS transport tags in order of preference; the
	// transport manager default is used if none of them exists (unless blocked
	// by strict non-test leak protection).
	transports []string
	// strict disables the system resolver fallback (and any non-test fallback
	// to dns-direct/dns-local) so proxied domains never leak to direct DNS.
	strict bool
}

var _ resolver.Resolver = (*singBoxResolver)(nil)

func (r *singBoxResolver) defaultTransport(h *dnsRouterHolder) (adapter.DNSTransport, bool) {
	if h == nil || h.transportManager == nil {
		return nil, !r.strict
	}
	for _, tag := range r.transports {
		if tr, ok := h.transportManager.Transport(tag); ok {
			return tr, true
		}
	}
	def := h.transportManager.Default()
	if r.strict && !h.test {
		if def == nil {
			return nil, false
		}
		switch def.Tag() {
		case "dns-direct", "dns-local":
			return nil, false
		}
	}
	return def, def != nil || !r.strict
}

func (r *singBoxResolver) lookup(ctx context.Context, host string, strategy boxConstant.DomainStrategy) ([]netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return []netip.Addr{ip.Unmap()}, nil
	}
	h := activeDNSRouter.Load()
	if h != nil && h.router != nil {
		tr, ok := r.defaultTransport(h)
		if !ok {
			return nil, resolver.ErrIPNotFound
		}
		addrs, err := h.router.Lookup(ctx, host, adapter.DNSQueryOptions{
			Transport:      tr,
			Strategy:       strategy,
			LookupStrategy: strategy,
		})
		if err == nil && len(addrs) > 0 {
			return addrs, nil
		}
		if r.strict {
			if err == nil {
				err = resolver.ErrIPNotFound
			}
			return nil, err
		}
	} else if r.strict {
		return nil, resolver.ErrIPNotFound
	}
	if r.fallback != nil {
		switch strategy {
		case boxConstant.DomainStrategyIPv4Only:
			return r.fallback.LookupIPv4(ctx, host)
		case boxConstant.DomainStrategyIPv6Only:
			return r.fallback.LookupIPv6(ctx, host)
		default:
			return r.fallback.LookupIP(ctx, host)
		}
	}
	return nil, resolver.ErrIPNotFound
}

func (r *singBoxResolver) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.lookup(ctx, host, boxConstant.DomainStrategyAsIS)
}

func (r *singBoxResolver) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	addrs, err := r.lookup(ctx, host, boxConstant.DomainStrategyIPv4Only)
	if err != nil {
		return nil, err
	}
	out := make([]netip.Addr, 0, len(addrs))
	for _, ip := range addrs {
		if ip.Unmap().Is4() {
			out = append(out, ip.Unmap())
		}
	}
	if len(out) == 0 {
		return nil, resolver.ErrIPNotFound
	}
	return out, nil
}

func (r *singBoxResolver) LookupIPv6(ctx context.Context, host string) ([]netip.Addr, error) {
	addrs, err := r.lookup(ctx, host, boxConstant.DomainStrategyIPv6Only)
	if err != nil {
		return nil, err
	}
	out := make([]netip.Addr, 0, len(addrs))
	for _, ip := range addrs {
		if ip.Is6() && !ip.Is4In6() {
			out = append(out, ip)
		}
	}
	if len(out) == 0 {
		return nil, resolver.ErrIPNotFound
	}
	return out, nil
}

func (r *singBoxResolver) ResolveECH(ctx context.Context, host string) ([]byte, error) {
	msg := &mDNS.Msg{}
	msg.SetQuestion(mDNS.Fqdn(host), mDNS.TypeHTTPS)
	resp, err := r.ExchangeContext(ctx, msg)
	if err != nil {
		return nil, err
	}
	for _, rr := range resp.Answer {
		if httpsRR, ok := rr.(*mDNS.HTTPS); ok {
			for _, val := range httpsRR.Value {
				if echCfg, ok := val.(*mDNS.SVCBECHConfig); ok {
					return echCfg.ECH, nil
				}
			}
		}
	}
	return nil, errors.New("no ECH config found in DNS records")
}

func (r *singBoxResolver) ExchangeContext(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
	h := activeDNSRouter.Load()
	if h != nil && h.router != nil {
		tr, ok := r.defaultTransport(h)
		if !ok {
			return nil, resolver.ErrIPNotFound
		}
		return h.router.Exchange(ctx, m, adapter.DNSQueryOptions{
			Transport: tr,
		})
	}
	if r.strict {
		return nil, resolver.ErrIPNotFound
	}
	if r.fallback != nil {
		return r.fallback.ExchangeContext(ctx, m)
	}
	return nil, resolver.ErrIPNotFound
}

func (r *singBoxResolver) Invalid() bool {
	return true
}

func (r *singBoxResolver) ClearCache() {
	if h := activeDNSRouter.Load(); h != nil && h.router != nil {
		h.router.ClearCache()
	}
}

func (r *singBoxResolver) ResetConnection() {
	if h := activeDNSRouter.Load(); h != nil && h.router != nil {
		h.router.ResetNetwork()
	}
}
