package dns

import (
	"context"
	"errors"
	"slices"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/service"

	mDNS "github.com/miekg/dns"
)

// A private DoH mirror should answer quickly; a stalled one must not consume the
// whole caller deadline. 1.2s leaves room for the next mirror plus the handshake
// that follows resolution (URL tests run with a ~5s budget).
const fallbackAttemptTimeout = 1200 * time.Millisecond

// Reported instead of a sub-millisecond budget when the caller's deadline is nearly spent.
const fallbackMinimumAttempt = 250 * time.Millisecond

type fallbackTransport struct {
	TransportAdapter
	manager adapter.DNSTransportManager
	members atomic.Pointer[[]adapter.DNSTransport]
}

func newFallbackTransport(ctx context.Context, _ log.ContextLogger, tag string, options option.FallbackDNSServerOptions) (adapter.DNSTransport, error) {
	if len(options.Servers) < 2 {
		return nil, E.New("fallback DNS requires at least two servers")
	}
	seen := map[string]bool{tag: true, "": true}
	for _, server := range options.Servers {
		if seen[server] {
			return nil, E.New("empty, duplicate or self-referencing fallback DNS server")
		}
		seen[server] = true
	}
	manager := service.FromContext[adapter.DNSTransportManager](ctx)
	if manager == nil {
		return nil, E.New("missing DNS transport manager")
	}
	return &fallbackTransport{
		TransportAdapter: NewTransportAdapter(C.DNSTypeFallback, tag, slices.Clone(options.Servers)),
		manager:          manager,
	}, nil
}

func (t *fallbackTransport) Start(stage adapter.StartStage, _ *adapter.Scope) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	members := make([]adapter.DNSTransport, 0, len(t.Dependencies()))
	for _, tag := range t.Dependencies() {
		member, found := t.manager.Transport(tag)
		if !found {
			return E.New("missing fallback DNS server: ", tag)
		}
		if member.Type() == C.DNSTypeFakeIP || member.Type() == C.DNSTypeFallback {
			return E.New("unsupported fallback DNS member: ", tag)
		}
		members = append(members, member)
	}
	t.members.Store(&members)
	return nil
}

// Members own connections and are reset by the transport manager's caller.
func (t *fallbackTransport) Reset() {}

func (t *fallbackTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	members := t.members.Load()
	if members == nil {
		return nil, E.New("fallback DNS is not started")
	}
	var response *mDNS.Msg
	var err error
	for index, member := range *members {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		// Reserve time for every remaining member without extending the caller's deadline.
		budget := fallbackAttemptTimeout
		if deadline, ok := ctx.Deadline(); ok {
			remaining := time.Until(deadline)
			share := remaining / time.Duration(len(*members)-index)
			// Keep a floor only while the caller still has that much time left;
			// otherwise fail fast instead of starting a query that cannot finish.
			if remaining >= fallbackMinimumAttempt {
				share = max(share, fallbackMinimumAttempt)
			}
			budget = min(budget, share)
		}
		attempt, cancel := context.WithTimeout(ctx, budget)
		response, err = exchangeFallbackMember(attempt, member, message.Copy())
		attemptErr := attempt.Err()
		cancel()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if attemptErr != nil {
			response, err = nil, attemptErr
		}
		var rcode RcodeError
		if errors.As(err, &rcode) {
			response, err = FixedResponseStatus(message, int(rcode)), nil
		}
		if err != nil || response == nil {
			// Transport errors can contain credential-bearing endpoint URLs.
			if attemptErr == nil {
				err = E.New("fallback DNS exchange failed at server ", member.Tag())
			}
			response = nil
			continue
		}
		// NOERROR (including NODATA) and NXDOMAIN are terminal DNS answers.
		if response.Rcode == mDNS.RcodeSuccess || response.Rcode == mDNS.RcodeNameError {
			return response, nil
		}
	}
	return response, err
}

func exchangeFallbackMember(ctx context.Context, member adapter.DNSTransport, message *mDNS.Msg) (*mDNS.Msg, error) {
	type result struct {
		response *mDNS.Msg
		err      error
	}
	results := make(chan result, 1)
	// Some transports can block before returning from ExchangeAsync. A timed-out
	// member must not delay the remaining ones or block when its callback arrives.
	go func() {
		if ctx.Err() != nil {
			return
		}
		member.ExchangeAsync(ctx, message, func(response *mDNS.Msg, err error) {
			results <- result{response, err}
		})
	}()
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case value := <-results:
		return value.response, value.err
	}
}

func (t *fallbackTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(*mDNS.Msg, error)) {
	go func() { callback(t.Exchange(ctx, message)) }()
}
