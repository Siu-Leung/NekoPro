package mihomo_adapter

import (
	"context"
	"errors"
	"net"
	"net/netip"
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
	// Non-test entries always win; a test entry is only a last resort while no
	// main instance is running, and never contributes a DNS router.
	for _, wantTest := range []bool{false, true} {
		for i := len(registry) - 1; i >= 0; i-- {
			e := registry[i]
			if e.test != wantTest {
				continue
			}
			if dnsHolder == nil && e.dns != nil && !e.test {
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

func init() {
	// Enable IPv6 lookup once at package load rather than writing this global
	// flag concurrently from outbound constructors.
	resolver.DisableIPv6 = false
}

// InstallProtectHook registers the sing-box Android VPN socket protect
// callback and encrypted DNS router bridge of the instance owning ctx for
// embedded mihomo outbounds. The returned function releases the
// registration and must be called when the outbound is closed; once all
// outbounds of an instance released it, the previous live instance (if any)
// becomes active again.
func InstallProtectHook(ctx context.Context) (release func()) {
	release = func() {}
	if ctx == nil {
		return
	}
	// WHY: Replace mihomo's process-global SystemResolver (which queries cleartext
	// 114.114.114.114:53 / 8.8.8.8:53) immediately even when the first box in the
	// process is a temporary URLTest/SpeedTest instance (entry.test == true).
	installSingBoxResolverOnce()

	entry := &hookEntry{test: isTestInstance(ctx)}
	// A temporary box (URL test, speed test, exit probe) must not publish its DNS
	// router: the mihomo resolver is process-global, so a short-lived config would
	// otherwise replace the main instance's dns-remote after it closes. Test boxes
	// already use their own in-process DNS for their own dials.
	if !entry.test {
		if dnsRouter := service.FromContext[adapter.DNSRouter](ctx); dnsRouter != nil {
			entry.key.router = dnsRouter
			entry.dns = &dnsRouterHolder{
				router:           dnsRouter,
				transportManager: service.FromContext[adapter.DNSTransportManager](ctx),
				test:             false,
			}
		}
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
	// through the tunnel) must not leak to direct or cleartext system DNS.
	remote := &singBoxResolver{
		fallback:   nil,
		transports: []string{"dns-remote"},
		strict:     true,
	}
	// Proxy server hostnames and direct destinations use direct DNS via sing-box
	// and never fall back to mihomo's cleartext SystemResolver.
	direct := &singBoxResolver{
		fallback:   nil,
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

func effectiveDomainStrategy(dialOptions option.DialerOptions) boxConstant.DomainStrategy {
	//nolint:staticcheck
	strategy := boxConstant.DomainStrategy(dialOptions.DomainStrategy)
	if dialOptions.DomainResolver != nil && dialOptions.DomainResolver.Strategy != option.DomainStrategy(boxConstant.DomainStrategyAsIS) {
		strategy = boxConstant.DomainStrategy(dialOptions.DomainResolver.Strategy)
	}
	return strategy
}

// MapDomainStrategy maps sing-box DialerOptions domain strategy
// (prefer_ipv6, ipv6_only, ipv4_only, prefer_ipv4) onto mihomo's C.DNSPrefer IPVersion.
func MapDomainStrategy(dialOptions option.DialerOptions) C.DNSPrefer {
	switch effectiveDomainStrategy(dialOptions) {
	case boxConstant.DomainStrategyPreferIPv4:
		return C.IPv4Prefer
	case boxConstant.DomainStrategyPreferIPv6:
		return C.IPv6Prefer
	case boxConstant.DomainStrategyIPv4Only:
		return C.IPv4Only
	case boxConstant.DomainStrategyIPv6Only:
		return C.IPv6Only
	default:
		return C.DualStack
	}
}

// NewOutboundResolver creates an instance-scoped mihomo resolver.Resolver for
// QUIC dialers (common.DialQuic) and ECH HTTPS RR lookups so embedded outbounds
// (MASQUE, VLESS-XHTTP) honor sing-box's per-outbound domain_resolver /
// domain_strategy instead of bypassing them via global ProxyServerHostResolver.
func NewOutboundResolver(ctx context.Context, dialOptions option.DialerOptions, outboundDialer N.Dialer) (resolver.Resolver, error) {
	strategy := effectiveDomainStrategy(dialOptions)
	if dialOptions.DomainResolver != nil && dialOptions.DomainResolver.Server != "" {
		dnsRouter := service.FromContext[adapter.DNSRouter](ctx)
		transports := service.FromContext[adapter.DNSTransportManager](ctx)
		if dnsRouter == nil || transports == nil {
			return nil, errors.New("missing DNS router or transport manager for domain_resolver: " + dialOptions.DomainResolver.Server)
		}
		queryOptions, err := boxDialer.NewDNSQueryOptions(ctx, dialOptions.DomainResolver, false)
		if err != nil {
			return nil, err
		}
		queryOptions.Strategy = strategy
		queryOptions.LookupStrategy = strategy
		return &outboundSingBoxResolver{
			router:       dnsRouter,
			transports:   transports,
			serverTag:    dialOptions.DomainResolver.Server,
			queryOptions: queryOptions,
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
	// WHY: When outboundDialer does not implement ResolveDialer (e.g. IP-literal
	// server with ECH domain SNI, or Detour chain proxy), still build a valid
	// instance-scoped resolver via NewDNSQueryOptions (defaulting to "dns-direct"
	// when DomainResolver is empty) so lookups never fall back to global state.
	dnsRouter := service.FromContext[adapter.DNSRouter](ctx)
	transports := service.FromContext[adapter.DNSTransportManager](ctx)
	if dnsRouter == nil || transports == nil {
		return nil, nil
	}
	var (
		queryOptions adapter.DNSQueryOptions
		err          error
	)
	networkManager := service.FromContext[adapter.NetworkManager](ctx)
	if networkManager != nil && networkManager.DefaultOptions().DomainResolver != "" {
		queryOptions, err = boxDialer.NewDNSQueryOptions(ctx, dialOptions.DomainResolver, false)
		if err != nil {
			return nil, err
		}
	} else {
		var fallbackResolver option.DomainResolveOptions
		if dialOptions.DomainResolver != nil {
			fallbackResolver = *dialOptions.DomainResolver
		}
		if _, ok := transports.Transport("dns-direct"); ok {
			fallbackResolver.Server = "dns-direct"
			queryOptions, err = boxDialer.NewDNSQueryOptions(ctx, &fallbackResolver, false)
			if err != nil {
				return nil, err
			}
		} else if _, ok := transports.Transport("dns-local"); ok {
			fallbackResolver.Server = "dns-local"
			queryOptions, err = boxDialer.NewDNSQueryOptions(ctx, &fallbackResolver, false)
			if err != nil {
				return nil, err
			}
		} else if def := transports.Default(); def != nil {
			queryOptions = adapter.DNSQueryOptions{Transport: def}
		} else {
			return nil, nil
		}
	}
	if strategy != boxConstant.DomainStrategyAsIS {
		queryOptions.Strategy = strategy
		queryOptions.LookupStrategy = strategy
	}
	return &outboundSingBoxResolver{
		router:       dnsRouter,
		transports:   transports,
		queryOptions: queryOptions,
	}, nil
}

// NewTargetResolver creates an instance-scoped mihomo resolver.Resolver for
// proxied target domains (e.g., MASQUE when RemoteDnsResolve is disabled).
// Unlike the process-global resolver.DefaultResolver, this resolver stays
// bound to the owning sing-box instance so temporary test boxes resolve
// domains via their own DNS router even after the main VPN disconnects, while
// main VPN instances remain fail-closed against dns-direct/dns-local leaks.
func NewTargetResolver(ctx context.Context) resolver.Resolver {
	if ctx == nil {
		return nil
	}
	dnsRouter := service.FromContext[adapter.DNSRouter](ctx)
	transports := service.FromContext[adapter.DNSTransportManager](ctx)
	if dnsRouter == nil || transports == nil {
		return nil
	}
	return &instanceTargetResolver{
		holder: &dnsRouterHolder{
			router:           dnsRouter,
			transportManager: transports,
			test:             isTestInstance(ctx),
		},
		inner: &singBoxResolver{
			transports: []string{"dns-remote"},
			strict:     true,
		},
	}
}

type instanceTargetResolver struct {
	holder *dnsRouterHolder
	inner  *singBoxResolver
}

var _ resolver.Resolver = (*instanceTargetResolver)(nil)

func (r *instanceTargetResolver) lookup(ctx context.Context, host string, strategy boxConstant.DomainStrategy) ([]netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return []netip.Addr{ip.Unmap()}, nil
	}
	if r.holder == nil || r.holder.router == nil {
		return nil, resolver.ErrIPNotFound
	}
	tr, ok := r.inner.defaultTransport(r.holder)
	if !ok {
		return nil, resolver.ErrIPNotFound
	}
	addrs, err := r.holder.router.Lookup(ctx, host, adapter.DNSQueryOptions{
		Transport:      tr,
		Strategy:       strategy,
		LookupStrategy: strategy,
	})
	if err != nil {
		return nil, err
	}
	if len(addrs) == 0 {
		return nil, resolver.ErrIPNotFound
	}
	return addrs, nil
}

func (r *instanceTargetResolver) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	return r.lookup(ctx, host, boxConstant.DomainStrategyAsIS)
}

func (r *instanceTargetResolver) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	addrs, err := r.lookup(ctx, host, boxConstant.DomainStrategyIPv4Only)
	if err != nil {
		return nil, err
	}
	return filterIPv4Addrs(addrs)
}

func (r *instanceTargetResolver) LookupIPv6(ctx context.Context, host string) ([]netip.Addr, error) {
	addrs, err := r.lookup(ctx, host, boxConstant.DomainStrategyIPv6Only)
	if err != nil {
		return nil, err
	}
	return filterIPv6Addrs(addrs)
}

func (r *instanceTargetResolver) ResolveECH(ctx context.Context, host string) ([]byte, error) {
	msg := &mDNS.Msg{}
	msg.SetQuestion(mDNS.Fqdn(host), mDNS.TypeHTTPS)
	resp, err := r.ExchangeContext(ctx, msg)
	if err != nil {
		return nil, err
	}
	return extractECHFromDNS(resp)
}

func (r *instanceTargetResolver) ExchangeContext(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
	if r.holder == nil || r.holder.router == nil {
		return nil, resolver.ErrIPNotFound
	}
	tr, ok := r.inner.defaultTransport(r.holder)
	if !ok {
		return nil, resolver.ErrIPNotFound
	}
	return r.holder.router.Exchange(ctx, m, adapter.DNSQueryOptions{
		Transport: tr,
	})
}

func (r *instanceTargetResolver) Invalid() bool { return true }

func (r *instanceTargetResolver) ClearCache() {
	if r.holder != nil && r.holder.router != nil {
		r.holder.router.ClearCache()
	}
}

func (r *instanceTargetResolver) ResetConnection() {
	if r.holder != nil && r.holder.router != nil {
		r.holder.router.ResetNetwork()
	}
}

func filterIPv4Addrs(addrs []netip.Addr) ([]netip.Addr, error) {
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

func filterIPv6Addrs(addrs []netip.Addr) ([]netip.Addr, error) {
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

func extractECHFromDNS(resp *mDNS.Msg) ([]byte, error) {
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

type outboundSingBoxResolver struct {
	router       adapter.DNSRouter
	transports   adapter.DNSTransportManager
	serverTag    string
	queryOptions adapter.DNSQueryOptions
}

var _ resolver.Resolver = (*outboundSingBoxResolver)(nil)

func (r *outboundSingBoxResolver) resolveOptions(strategyOverride boxConstant.DomainStrategy) (adapter.DNSQueryOptions, error) {
	if r.router == nil {
		return adapter.DNSQueryOptions{}, resolver.ErrIPNotFound
	}
	opts := r.queryOptions
	if r.serverTag != "" {
		transport, loaded := r.transports.Transport(r.serverTag)
		if !loaded {
			return adapter.DNSQueryOptions{}, resolver.ErrIPNotFound
		}
		opts.Transport = transport
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
	addrs, err := r.router.Lookup(ctx, host, opts)
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
	if r.strict {
		if def == nil {
			return nil, false
		}
		switch def.Tag() {
		case "dns-local":
			return nil, false
		case "dns-direct":
			// WHY: Full main and forTest configs register "dns-remote" and return above.
			// Only allow Default() == "dns-direct" on minimal unit-test configs where
			// "dns-remote" is not registered at all; main instances always fail closed.
			if !h.test {
				return nil, false
			}
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
