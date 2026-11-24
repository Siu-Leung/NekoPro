package dns

import (
	"context"
	"errors"
	"net/netip"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
	"github.com/stretchr/testify/require"
)

func TestFallbackTransportExchange(t *testing.T) {
	answer := netip.MustParseAddr("192.0.2.21")
	for _, tc := range []struct {
		name           string
		delay          time.Duration
		primaryError   error
		primaryRcode   int
		primaryAddress netip.Addr
		canceled       bool
		wantBackup     int32
		wantRcode      int
		wantAnswers    []netip.Addr
	}{
		{name: "timeout", delay: 10 * time.Second, wantBackup: 1, wantAnswers: []netip.Addr{answer}},
		{name: "error", primaryError: errors.New("fixture network failure"), wantBackup: 1, wantAnswers: []netip.Addr{answer}},
		{name: "SERVFAIL", primaryRcode: mDNS.RcodeServerFailure, wantBackup: 1, wantAnswers: []netip.Addr{answer}},
		{name: "platform RcodeError", primaryError: RcodeServerFailure, wantBackup: 1, wantAnswers: []netip.Addr{answer}},
		{name: "positive", primaryAddress: answer, wantAnswers: []netip.Addr{answer}},
		{name: "NODATA"},
		{name: "NXDOMAIN", primaryRcode: mDNS.RcodeNameError, wantRcode: mDNS.RcodeNameError},
		{name: "cancelled", canceled: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			primary := &fakeDNSTransport{tag: "first", delay: tc.delay, exchangeErr: tc.primaryError, rcode: tc.primaryRcode, address: tc.primaryAddress}
			backup := &fakeDNSTransport{tag: "second", address: answer}
			foreign := &fakeDNSTransport{tag: "group-aggregate-1", address: answer}
			defaultServer := &fakeDNSTransport{tag: "default", address: answer}
			aggregate := testFallbackTransport(t, []string{primary.tag, backup.tag}, primary, backup, foreign, defaultServer)
			ctx, cancel := context.WithTimeout(t.Context(), 400*time.Millisecond)
			defer cancel()
			if tc.canceled {
				cancel()
			}
			query := new(mDNS.Msg).SetQuestion("entry.example.", mDNS.TypeA)
			if tc.canceled {
				_, err := aggregate.Exchange(ctx, query)
				require.ErrorIs(t, err, context.Canceled)
				require.Zero(t, primary.queryCount.Load())
			} else {
				response, err := aggregate.Exchange(ctx, query)
				require.NoError(t, err)
				require.Equal(t, tc.wantRcode, response.Rcode)
				require.ElementsMatch(t, tc.wantAnswers, MessageToAddresses(response))
				require.EqualValues(t, 1, primary.queryCount.Load())
			}
			require.Equal(t, tc.wantBackup, backup.queryCount.Load())
			require.Zero(t, foreign.queryCount.Load())
			require.Zero(t, defaultServer.queryCount.Load())
		})
	}
}

func TestFallbackTransportOrderAndIsolation(t *testing.T) {
	first := &fakeDNSTransport{tag: "one", rcode: mDNS.RcodeServerFailure}
	second := &fakeDNSTransport{tag: "two", exchangeErr: errors.New("test error")}
	third := &fakeDNSTransport{tag: "three", rcode: mDNS.RcodeRefused}
	foreign := &fakeDNSTransport{tag: "group-aggregate-1", address: netip.MustParseAddr("192.0.2.30")}
	def := &fakeDNSTransport{tag: "default", address: netip.MustParseAddr("192.0.2.31")}
	aggregate := testFallbackTransport(t, []string{first.tag, second.tag, third.tag}, third, def, first, foreign, second)
	response, err := aggregate.Exchange(t.Context(), new(mDNS.Msg).SetQuestion("entry.example.", mDNS.TypeA))
	require.NoError(t, err)
	require.Equal(t, mDNS.RcodeRefused, response.Rcode)
	require.True(t, first.firstQueried.Before(second.firstQueried))
	require.True(t, second.firstQueried.Before(third.firstQueried))
	require.Zero(t, foreign.queryCount.Load())
	require.Zero(t, def.queryCount.Load())
}

func testFallbackTransport(t *testing.T, tags []string, members ...*fakeDNSTransport) adapter.DNSTransport {
	t.Helper()
	registry := NewTransportRegistry()
	manager := NewTransportManager(registry, nil, "default")
	ctx := service.ContextWith[adapter.DNSTransportManager](t.Context(), manager)
	logger := log.NewNOPFactory().Logger()
	RegisterTransport(registry, "fixture", func(_ context.Context, _ log.ContextLogger, tag string, _ struct{}) (adapter.DNSTransport, error) {
		for _, member := range members {
			if member.tag == tag {
				return member, nil
			}
		}
		return nil, errors.New("invalid fixture member")
	})
	require.NoError(t, manager.Create(ctx, logger, "group-aggregate", C.DNSTypeFallback, &option.FallbackDNSServerOptions{Servers: tags}))
	for _, member := range members {
		require.NoError(t, manager.Create(ctx, logger, member.tag, "fixture", nil))
	}
	scope := adapter.NewScope(ctx, logger)
	t.Cleanup(func() { require.NoError(t, scope.Close()) })
	require.NoError(t, manager.Start(adapter.StartStateInitialize, scope))
	require.NoError(t, manager.Start(adapter.StartStateStart, scope))
	transport, ok := manager.Transport("group-aggregate")
	require.True(t, ok)
	return transport
}

type deadlineRecordingTransport struct {
	TransportAdapter
	budgetSeen time.Duration
	failErr    error
	answer     netip.Addr
}

func (*deadlineRecordingTransport) Start(adapter.StartStage, *adapter.Scope) error { return nil }
func (*deadlineRecordingTransport) Reset()                                         {}

func (d *deadlineRecordingTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	if deadline, ok := ctx.Deadline(); ok {
		d.budgetSeen = time.Until(deadline)
	}
	if d.failErr != nil {
		return nil, d.failErr
	}
	return FixedResponse(message.Id, message.Question[0], []netip.Addr{d.answer}, 60), nil
}

func (d *deadlineRecordingTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	callback(d.Exchange(ctx, message))
}

func TestFallbackTransportBudgetCapsOnlyNonFinalMembers(t *testing.T) {
	require.Equal(t, 1800*time.Millisecond, fallbackAttemptTimeout)

	answer := netip.MustParseAddr("198.51.100.77")
	first := &deadlineRecordingTransport{
		TransportAdapter: NewTransportAdapter("fixture", "first", nil),
		failErr:          errors.New("primary failed immediately"),
	}
	final := &deadlineRecordingTransport{
		TransportAdapter: NewTransportAdapter("fixture", "final", nil),
		answer:           answer,
	}

	registry := NewTransportRegistry()
	manager := NewTransportManager(registry, nil, "final")
	ctx := service.ContextWith[adapter.DNSTransportManager](t.Context(), manager)
	logger := log.NewNOPFactory().Logger()
	RegisterTransport(registry, "fixture", func(_ context.Context, _ log.ContextLogger, tag string, _ struct{}) (adapter.DNSTransport, error) {
		switch tag {
		case "first":
			return first, nil
		case "final":
			return final, nil
		default:
			return nil, errors.New("unknown fixture")
		}
	})
	require.NoError(t, manager.Create(ctx, logger, "fallback-pair", C.DNSTypeFallback, &option.FallbackDNSServerOptions{Servers: []string{"first", "final"}}))
	require.NoError(t, manager.Create(ctx, logger, "first", "fixture", nil))
	require.NoError(t, manager.Create(ctx, logger, "final", "fixture", nil))
	scope := adapter.NewScope(ctx, logger)
	t.Cleanup(func() { require.NoError(t, scope.Close()) })
	require.NoError(t, manager.Start(adapter.StartStateInitialize, scope))
	require.NoError(t, manager.Start(adapter.StartStateStart, scope))

	aggregate, ok := manager.Transport("fallback-pair")
	require.True(t, ok)

	queryCtx, cancel := context.WithTimeout(t.Context(), 5*time.Second)
	defer cancel()

	resp, err := aggregate.Exchange(queryCtx, new(mDNS.Msg).SetQuestion("budget.example.", mDNS.TypeA))
	require.NoError(t, err)
	require.Equal(t, []netip.Addr{answer}, MessageToAddresses(resp))

	// First member (remaining=2) must be capped at fallbackAttemptTimeout (1800ms),
	// whereas final member (remaining=1) must receive the full remaining ~5s share.
	require.LessOrEqual(t, first.budgetSeen, fallbackAttemptTimeout+50*time.Millisecond)
	require.Greater(t, first.budgetSeen, 1500*time.Millisecond)
	require.Greater(t, final.budgetSeen, 4*time.Second)
}
