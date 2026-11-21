package mihomo_adapter

import (
	"context"
	"errors"
	"net/netip"
	"sync/atomic"
	"testing"

	"github.com/metacubex/mihomo/component/resolver"
	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"
)

type spyFallbackResolver struct {
	resolver.Resolver
	calls atomic.Int32
}

func (s *spyFallbackResolver) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	s.calls.Add(1)
	return []netip.Addr{netip.MustParseAddr("192.0.2.1")}, nil
}

func (s *spyFallbackResolver) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	s.calls.Add(1)
	return []netip.Addr{netip.MustParseAddr("192.0.2.1")}, nil
}

func (s *spyFallbackResolver) LookupIPv6(ctx context.Context, host string) ([]netip.Addr, error) {
	s.calls.Add(1)
	return []netip.Addr{netip.MustParseAddr("2001:db8::1")}, nil
}

func (s *spyFallbackResolver) ExchangeContext(ctx context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
	s.calls.Add(1)
	return new(mDNS.Msg).SetReply(m), nil
}

type stubDNSTransport struct {
	adapter.DNSTransport
	tag string
}

func (t *stubDNSTransport) Tag() string { return t.tag }

type stubDNSTransportManager struct {
	adapter.DNSTransportManager
	byTag map[string]adapter.DNSTransport
	def   adapter.DNSTransport
}

func (m *stubDNSTransportManager) Transport(tag string) (adapter.DNSTransport, bool) {
	tr, ok := m.byTag[tag]
	return tr, ok
}

func (m *stubDNSTransportManager) Transports() []adapter.DNSTransport {
	list := make([]adapter.DNSTransport, 0, len(m.byTag))
	for _, transport := range m.byTag {
		list = append(list, transport)
	}
	return list
}

func (m *stubDNSTransportManager) Default() adapter.DNSTransport {
	return m.def
}

type stubDNSRouter struct {
	adapter.DNSRouter
	lastTransportTag string
	addrs            []netip.Addr
}

func (r *stubDNSRouter) Lookup(ctx context.Context, domain string, options adapter.DNSQueryOptions) ([]netip.Addr, error) {
	if options.Transport != nil {
		r.lastTransportTag = options.Transport.Tag()
	}
	return r.addrs, nil
}

func (r *stubDNSRouter) Exchange(ctx context.Context, message *mDNS.Msg, options adapter.DNSQueryOptions) (*mDNS.Msg, error) {
	if options.Transport != nil {
		r.lastTransportTag = options.Transport.Tag()
	}
	return new(mDNS.Msg).SetReply(message), nil
}

func TestStrictSingBoxResolverNeverFallsBackWhenRouterUnavailable(t *testing.T) {
	prev := activeDNSRouter.Swap(nil)
	t.Cleanup(func() {
		activeDNSRouter.Store(prev)
	})

	spy := &spyFallbackResolver{}
	r := &singBoxResolver{
		fallback:   spy,
		transports: []string{"dns-remote"},
		strict:     true,
	}

	if _, err := r.LookupIP(context.Background(), "example.com"); !errors.Is(err, resolver.ErrIPNotFound) {
		t.Fatalf("LookupIP err = %v, want ErrIPNotFound", err)
	}
	if _, err := r.LookupIPv4(context.Background(), "example.com"); !errors.Is(err, resolver.ErrIPNotFound) {
		t.Fatalf("LookupIPv4 err = %v, want ErrIPNotFound", err)
	}
	if _, err := r.LookupIPv6(context.Background(), "example.com"); !errors.Is(err, resolver.ErrIPNotFound) {
		t.Fatalf("LookupIPv6 err = %v, want ErrIPNotFound", err)
	}
	msg := new(mDNS.Msg)
	msg.SetQuestion("example.com.", mDNS.TypeHTTPS)
	if _, err := r.ExchangeContext(context.Background(), msg); !errors.Is(err, resolver.ErrIPNotFound) {
		t.Fatalf("ExchangeContext err = %v, want ErrIPNotFound", err)
	}
	if spy.calls.Load() != 0 {
		t.Fatalf("strict resolver leaked %d call(s) to fallback", spy.calls.Load())
	}
}

func TestStrictSingBoxResolverBlocksDirectDefaultOnMainInstanceButAllowsTestInstance(t *testing.T) {
	directTr := &stubDNSTransport{tag: "dns-direct"}
	tm := &stubDNSTransportManager{
		byTag: map[string]adapter.DNSTransport{"dns-direct": directTr},
		def:   directTr,
	}
	router := &stubDNSRouter{addrs: []netip.Addr{netip.MustParseAddr("198.51.100.10")}}
	spy := &spyFallbackResolver{}
	r := &singBoxResolver{
		fallback:   spy,
		transports: []string{"dns-remote"},
		strict:     true,
	}

	prev := activeDNSRouter.Swap(&dnsRouterHolder{
		router:           router,
		transportManager: tm,
		test:             false,
	})
	t.Cleanup(func() {
		activeDNSRouter.Store(prev)
	})

	if _, err := r.LookupIP(context.Background(), "target.example"); !errors.Is(err, resolver.ErrIPNotFound) {
		t.Fatalf("non-test strict LookupIP err = %v, want ErrIPNotFound", err)
	}
	if spy.calls.Load() != 0 {
		t.Fatalf("strict resolver leaked %d call(s) to fallback", spy.calls.Load())
	}

	// Temporary URLTest/SpeedTest instances legitimately use dns-direct without dns-remote.
	activeDNSRouter.Store(&dnsRouterHolder{
		router:           router,
		transportManager: tm,
		test:             true,
	})
	addrs, err := r.LookupIP(context.Background(), "target.example")
	if err != nil || len(addrs) != 1 {
		t.Fatalf("test instance LookupIP = (%v, %v), want 1 addr", addrs, err)
	}
	if router.lastTransportTag != "dns-direct" {
		t.Fatalf("test instance transport = %q, want dns-direct", router.lastTransportTag)
	}
}

func TestNewOutboundResolverBindsGroupDomainResolverAndFailsClosedOnMissingTag(t *testing.T) {
	groupTr := &stubDNSTransport{tag: "dns-group-7"}
	tm := &stubDNSTransportManager{
		byTag: map[string]adapter.DNSTransport{"dns-group-7": groupTr},
	}
	router := &stubDNSRouter{addrs: []netip.Addr{netip.MustParseAddr("203.0.113.7")}}

	ctx := service.ContextWithDefaultRegistry(context.Background())
	service.MustRegister[adapter.DNSRouter](ctx, router)
	service.MustRegister[adapter.DNSTransportManager](ctx, tm)

	var validOpts option.DialerOptions
	validOpts.DomainResolver = &option.DomainResolveOptions{Server: "dns-group-7"}
	outResolver, err := NewOutboundResolver(ctx, validOpts, nil)
	if err != nil || outResolver == nil {
		t.Fatalf("NewOutboundResolver err = %v, resolver = %v", err, outResolver)
	}
	addrs, err := outResolver.LookupIPv4(ctx, "entry.private.example")
	if err != nil || len(addrs) != 1 || addrs[0] != netip.MustParseAddr("203.0.113.7") {
		t.Fatalf("LookupIPv4 = (%v, %v)", addrs, err)
	}
	if router.lastTransportTag != "dns-group-7" {
		t.Fatalf("transport tag = %q, want dns-group-7", router.lastTransportTag)
	}

	var missingOpts option.DialerOptions
	missingOpts.DomainResolver = &option.DomainResolveOptions{Server: "dns-group-missing"}
	if _, err := NewOutboundResolver(ctx, missingOpts, nil); err == nil {
		t.Fatal("expected error for missing domain_resolver tag")
	}
}
