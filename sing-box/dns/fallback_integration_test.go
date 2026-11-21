package dns_test

import (
	"context"
	"fmt"
	"net/netip"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	boxService "github.com/sagernet/sing-box/adapter/service"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/anytls"
	"github.com/sagernet/sing-box/protocol/mihomo_adapter"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
	"github.com/stretchr/testify/require"
)

type fallbackFixture struct {
	dns.TransportAdapter
	query func(*mDNS.Msg) (*mDNS.Msg, error)
}

func (*fallbackFixture) Start(adapter.StartStage, *adapter.Scope) error { return nil }
func (*fallbackFixture) Reset()                                         {}
func (f *fallbackFixture) Exchange(_ context.Context, m *mDNS.Msg) (*mDNS.Msg, error) {
	return f.query(m)
}
func (f *fallbackFixture) ExchangeAsync(ctx context.Context, m *mDNS.Msg, cb func(*mDNS.Msg, error)) {
	cb(f.Exchange(ctx, m))
}

func TestFallbackDNSNativeBridgeAndTun(t *testing.T) {
	registry := dns.NewTransportRegistry()
	outbounds := outbound.NewRegistry()
	anytls.RegisterOutbound(outbounds)
	ctx := box.Context(service.ContextWithDefaultRegistry(t.Context()), inbound.NewRegistry(), outbounds, endpoint.NewRegistry(), registry, boxService.NewRegistry(), certificate.NewRegistry())
	addr := netip.MustParseAddr("192.0.2.42")
	dns.RegisterTransport(registry, "fixture", func(_ context.Context, _ log.ContextLogger, tag string, _ struct{}) (adapter.DNSTransport, error) {
		return &fallbackFixture{TransportAdapter: dns.NewTransportAdapter("fixture", tag, nil), query: func(m *mDNS.Msg) (*mDNS.Msg, error) {
			switch tag {
			case "dns-group-7-1":
				return dns.FixedResponseStatus(m, mDNS.RcodeServerFailure), nil
			case "dns-group-7-2":
				if m.Question[0].Name == "native.entry.example." {
					return nil, dns.RcodeNameError
				}
				return dns.FixedResponse(m.Id, m.Question[0], []netip.Addr{addr}, 60), nil
			default:
				t.Errorf("queried unrelated transport %q", tag)
				return nil, fmt.Errorf("unrelated fixture")
			}
		}}, nil
	})
	var options option.Options
	require.NoError(t, options.UnmarshalJSONContext(ctx, []byte(`{
		"log":{"disabled":true},
		"dns":{"disable_cache":true,"final":"default","servers":[
			{"type":"fallback","tag":"dns-group-7","servers":["dns-group-7-1","dns-group-7-2"]},
			{"type":"fixture","tag":"dns-group-7-2"},{"type":"fixture","tag":"default"},
			{"type":"fixture","tag":"dns-group-7-1"},{"type":"fixture","tag":"dns-group-8-1"}],
			"rules":[{"domain":["tun.entry.example"],"action":"route","server":"dns-group-7"}]},
		"outbounds":[{"type":"anytls","tag":"native","server":"native.entry.example","server_port":443,
			"password":"fixture","tls":{"enabled":true},"domain_resolver":{"server":"dns-group-7","strategy":"ipv4_only"}}]
	}`)))
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	require.NoError(t, err)
	require.NoError(t, instance.Start())
	t.Cleanup(func() { require.NoError(t, instance.Close()) })
	out, ok := instance.Outbound().Outbound("native")
	require.True(t, ok)
	_, err = out.DialContext(ctx, N.NetworkTCP, M.ParseSocksaddr("target.example:443"))
	// NXDOMAIN is returned by the backup; no entry socket is opened by this test.
	require.ErrorContains(t, err, "NXDOMAIN")

	bridge, err := mihomo_adapter.NewOutboundResolver(ctx, option.DialerOptions{AbstractDialerOptions: option.AbstractDialerOptions{DomainResolver: &option.DomainResolveOptions{Server: "dns-group-7"}}}, nil)
	require.NoError(t, err)
	addresses, err := bridge.LookupIPv4(ctx, "bridge.entry.example")
	require.NoError(t, err)
	require.Equal(t, []netip.Addr{addr}, addresses)

	resolver := service.FromContext[adapter.DNSRouter](ctx)
	var actual *mDNS.Msg
	var exchangeErr error
	done := make(chan struct{})
	resolver.ExchangeAsync(ctx, new(mDNS.Msg).SetQuestion("tun.entry.example.", mDNS.TypeA), adapter.DNSQueryOptions{}, func(response *mDNS.Msg, err error) {
		defer close(done)
		actual, exchangeErr = response, err
	})
	<-done
	require.NoError(t, exchangeErr)
	require.Equal(t, []netip.Addr{addr}, dns.MessageToAddresses(actual))
}
