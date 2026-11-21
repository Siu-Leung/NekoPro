package mieru

import (
	"context"
	"net/netip"
	"os"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	boxConstant "github.com/sagernet/sing-box/constant"
)

type fakeMieruTransport struct {
	adapter.DNSTransport
	tag string
}

func (f *fakeMieruTransport) Tag() string { return f.tag }

type fakeMieruTransportMgr struct {
	adapter.DNSTransportManager
	byTag map[string]adapter.DNSTransport
}

func (m *fakeMieruTransportMgr) Transport(tag string) (adapter.DNSTransport, bool) {
	tr, ok := m.byTag[tag]
	return tr, ok
}

type fakeMieruRouter struct {
	adapter.DNSRouter
	lastStrategy boxConstant.DomainStrategy
	lastTag      string
	addrs        []netip.Addr
}

func (r *fakeMieruRouter) Lookup(ctx context.Context, domain string, options adapter.DNSQueryOptions) ([]netip.Addr, error) {
	r.lastStrategy = options.Strategy
	if options.Transport != nil {
		r.lastTag = options.Transport.Tag()
	}
	return r.addrs, nil
}

func TestSingBoxMieruResolverFiltersNetworkAndFailsClosedOnNilTransports(t *testing.T) {
	router := &fakeMieruRouter{
		addrs: []netip.Addr{
			netip.MustParseAddr("198.51.100.22"),
			netip.MustParseAddr("2001:db8::22"),
		},
	}

	// Fail closed if serverTag is set while transports manager is nil.
	broken := &singBoxMieruResolver{
		router:    router,
		serverTag: "dns-group-1",
	}
	if _, err := broken.LookupIP(context.Background(), "ip4", "node.example"); err != os.ErrNotExist {
		t.Fatalf("expected os.ErrNotExist when transports is nil, got %v", err)
	}

	tr := &fakeMieruTransport{tag: "dns-group-1"}
	r := &singBoxMieruResolver{
		router:     router,
		transports: &fakeMieruTransportMgr{byTag: map[string]adapter.DNSTransport{"dns-group-1": tr}},
		serverTag:  "dns-group-1",
	}

	ip4s, err := r.LookupIP(context.Background(), "ip4", "node.example")
	if err != nil || len(ip4s) != 1 || ip4s[0].String() != "198.51.100.22" {
		t.Fatalf("ip4 LookupIP = (%v, %v)", ip4s, err)
	}
	if router.lastStrategy != boxConstant.DomainStrategyIPv4Only || router.lastTag != "dns-group-1" {
		t.Fatalf("unexpected strategy=%v tag=%q", router.lastStrategy, router.lastTag)
	}

	ip6s, err := r.LookupIP(context.Background(), "ip6", "node.example")
	if err != nil || len(ip6s) != 1 || ip6s[0].String() != "2001:db8::22" {
		t.Fatalf("ip6 LookupIP = (%v, %v)", ip6s, err)
	}
	if router.lastStrategy != boxConstant.DomainStrategyIPv6Only {
		t.Fatalf("unexpected strategy=%v", router.lastStrategy)
	}
}
