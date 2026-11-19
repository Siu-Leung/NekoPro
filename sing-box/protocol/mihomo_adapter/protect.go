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
	boxConstant "github.com/sagernet/sing-box/constant"
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
		transports: []string{"dns-remote", "dns-direct", "dns-local"},
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

type singBoxResolver struct {
	fallback resolver.Resolver
	// transports lists DNS transport tags in order of preference; the
	// transport manager default is used if none of them exists.
	transports []string
	// strict disables the system resolver fallback when the sing-box DNS
	// router is available but fails, so proxied domains never leak to it.
	strict bool
}

func (r *singBoxResolver) defaultTransport(h *dnsRouterHolder) adapter.DNSTransport {
	if h == nil || h.transportManager == nil {
		return nil
	}
	for _, tag := range r.transports {
		if tr, ok := h.transportManager.Transport(tag); ok {
			return tr
		}
	}
	return h.transportManager.Default()
}

func (r *singBoxResolver) lookup(ctx context.Context, host string, strategy boxConstant.DomainStrategy) ([]netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return []netip.Addr{ip.Unmap()}, nil
	}
	h := activeDNSRouter.Load()
	if h != nil && h.router != nil {
		addrs, err := h.router.Lookup(ctx, host, adapter.DNSQueryOptions{
			Transport:      r.defaultTransport(h),
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
		return h.router.Exchange(ctx, m, adapter.DNSQueryOptions{
			Transport: r.defaultTransport(h),
		})
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
