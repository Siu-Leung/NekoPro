package mihomo_adapter

import (
	"context"
	"errors"
	"net/netip"
	"sync/atomic"
	"testing"

	"github.com/metacubex/mihomo/component/resolver"
	mDNS "github.com/miekg/dns"
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
