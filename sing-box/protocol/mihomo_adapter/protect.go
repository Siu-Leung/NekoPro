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
	activeProtectFunc atomic.Value // holds control.Func
	activeDNSRouter   atomic.Pointer[dnsRouterHolder]
	resolverInstallMu sync.Mutex
	resolverInstalled bool
)

type dnsRouterHolder struct {
	router           adapter.DNSRouter
	transportManager adapter.DNSTransportManager
}

// InstallProtectHook registers (and refreshes across Box restarts) the active
// sing-box Android VPN socket protect callback and encrypted DNS router bridge
// for embedded mihomo outbounds.
func InstallProtectHook(ctx context.Context) {
	resolver.DisableIPv6 = false
	if ctx == nil {
		return
	}

	if dnsRouter := service.FromContext[adapter.DNSRouter](ctx); dnsRouter != nil {
		transportManager := service.FromContext[adapter.DNSTransportManager](ctx)
		activeDNSRouter.Store(&dnsRouterHolder{
			router:           dnsRouter,
			transportManager: transportManager,
		})
		installSingBoxResolverOnce()
	}

	networkManager := service.FromContext[adapter.NetworkManager](ctx)
	if networkManager == nil {
		return
	}
	protectFunc := networkManager.ProtectFunc()
	if protectFunc == nil {
		return
	}
	activeProtectFunc.Store(protectFunc)

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
		if fn, ok := activeProtectFunc.Load().(control.Func); ok && fn != nil {
			return fn(network, address, conn)
		}
		return nil
	}
	hookInstalled = true
}

func installSingBoxResolverOnce() {
	resolverInstallMu.Lock()
	defer resolverInstallMu.Unlock()
	if resolverInstalled {
		return
	}
	r := &singBoxResolver{fallback: resolver.SystemResolver}
	resolver.DefaultResolver = r
	resolver.ProxyServerHostResolver = r
	resolver.DirectHostResolver = r
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
}

func (r *singBoxResolver) defaultTransport(h *dnsRouterHolder) adapter.DNSTransport {
	if h == nil || h.transportManager == nil {
		return nil
	}
	if tr, ok := h.transportManager.Transport("dns-direct"); ok {
		return tr
	}
	if tr, ok := h.transportManager.Transport("dns-local"); ok {
		return tr
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
