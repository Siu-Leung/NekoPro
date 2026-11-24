// libbox/dns.go

package libcore

import (
	"context"
	"net/netip"
	"strings"
	"sync"
	"syscall"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/task"

	mDNS "github.com/miekg/dns"
)

var rawQueryFunc func(ctx context.Context, networkHandle int64, request []byte) ([]byte, error)

type LocalDNSTransport interface {
	Raw() bool
	NetworkHandle() int64
	Lookup(ctx *ExchangeContext, network string, domain string) error
	Exchange(ctx *ExchangeContext, message []byte) error
}

var gLocalDNSTransport *platformLocalDNSTransport = nil

type platformLocalDNSTransport struct {
	dns.TransportAdapter
	iif LocalDNSTransport
	raw bool
}

func newPlatformTransport(iif LocalDNSTransport, tag string, options option.LocalDNSServerOptions) *platformLocalDNSTransport {
	return &platformLocalDNSTransport{
		TransportAdapter: dns.NewTransportAdapterWithLocalOptions(constant.DNSTypeLocal, tag, options),
		iif:              iif,
		raw:              iif.Raw(),
	}
}

var _ adapter.DNSTransport = (*platformLocalDNSTransport)(nil)

func (p *platformLocalDNSTransport) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	return nil
}

func (p *platformLocalDNSTransport) Close() error {
	return nil
}

// Reset implements adapter.DNSTransport. The platform DNS transport has no
// persistent connections to close, so reset is a no-op.
func (p *platformLocalDNSTransport) Reset() {
}

func (p *platformLocalDNSTransport) ExchangeAsync(ctx context.Context, message *mDNS.Msg, callback func(response *mDNS.Msg, err error)) {
	go func() {
		response, err := p.Exchange(ctx, message)
		callback(response, err)
	}()
}

func (p *platformLocalDNSTransport) Exchange(ctx context.Context, message *mDNS.Msg) (*mDNS.Msg, error) {
	if p.raw && rawQueryFunc != nil {
		// Raw - Android 10 及以上才有
		networkHandle := p.iif.NetworkHandle()
		if isBgProcess && networkHandle == 0 && hasActiveMainInstance() {
			// WHY: Passing 0 (NETWORK_UNSPECIFIED) to android_res_nsend while VPN/core
			// is active binds the query to Android's default network (tun0), causing
			// recursive DNS loops or leaks when physical network tracking is momentarily 0.
			return nil, syscall.ENETUNREACH
		}
		messageBytes, err := message.Pack()
		if err != nil {
			return nil, err
		}
		msg, err := rawQueryFunc(ctx, networkHandle, messageBytes)
		if err != nil {
			return nil, err
		}
		responseMessage := new(mDNS.Msg)
		err = responseMessage.Unpack(msg)
		if err != nil {
			return nil, err
		}
		return responseMessage, nil
	} else {
		// Lookup - Android 10 以下
		if len(message.Question) == 0 {
			return nil, E.New("empty DNS question")
		}
		question := message.Question[0]
		var network string
		switch question.Qtype {
		case mDNS.TypeA:
			network = "ip4"
		case mDNS.TypeAAAA:
			network = "ip6"
		default:
			return nil, E.New("only IP queries are supported by current version of Android")
		}

		done := make(chan struct{})
		closeDone := sync.OnceFunc(func() {
			close(done)
		})
		defer closeDone()
		response := &ExchangeContext{
			context: ctx,
			doneCh:  done,
			done:    closeDone,
		}

		var responseAddrs []netip.Addr
		var group task.Group
		group.Append0(func(ctx context.Context) error {
			err := p.iif.Lookup(response, network, question.Name)
			if err != nil {
				return err
			}
			select {
			case <-done:
			case <-ctx.Done():
				return context.Canceled
			}
			if response.error != nil {
				return response.error
			}
			responseAddrs = response.addresses
			return nil
		})
		err := group.Run(ctx)
		if err != nil {
			return nil, err
		}
		return dns.FixedResponse(message.Id, question, responseAddrs, constant.DefaultDNSTTL), nil
	}
}

type Func interface {
	Invoke() error
}

type ExchangeContext struct {
	context   context.Context
	doneCh    <-chan struct{}
	message   mDNS.Msg
	addresses []netip.Addr
	error     error
	done      func()
}

func (c *ExchangeContext) OnCancel(callback Func) {
	if callback == nil || c.context == nil {
		return
	}
	go func() {
		select {
		case <-c.context.Done():
			_ = callback.Invoke()
		case <-c.doneCh:
		}
	}()
}

func (c *ExchangeContext) Success(result string) {
	c.addresses = common.Map(common.Filter(strings.Split(result, "\n"), func(it string) bool {
		return !common.IsEmpty(it)
	}), func(it string) netip.Addr {
		return M.ParseSocksaddrHostPort(it, 0).Unwrap().Addr
	})
	c.done()
}

func (c *ExchangeContext) RawSuccess(result []byte) {
	err := c.message.Unpack(result)
	if err != nil {
		c.error = E.Cause(err, "parse response")
	}
	c.done()
}

func (c *ExchangeContext) ErrorCode(code int32) {
	c.error = dns.RcodeError(code)
	c.done()
}

func (c *ExchangeContext) ErrnoCode(code int32) {
	c.error = syscall.Errno(code)
	c.done()
}
