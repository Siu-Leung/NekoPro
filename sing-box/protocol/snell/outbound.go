package snell

import (
	"context"
	"net"
	"os"
	"sync"
	"syscall"

	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/component/dialer"
	C "github.com/metacubex/mihomo/constant"

	"github.com/sagernet/sing-box/adapter"
	outboundAdapter "github.com/sagernet/sing-box/adapter/outbound"
	boxConstant "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
	"github.com/sagernet/sing/service"
)

func RegisterOutbound(registry *outboundAdapter.Registry) {
	outboundAdapter.Register[option.SnellOutboundOptions](registry, boxConstant.TypeSnell, NewOutbound)
}

var installSocketHookOnce sync.Once

func installAndroidProtectHook(ctx context.Context) {
	installSocketHookOnce.Do(func() {
		networkManager := service.FromContext[adapter.NetworkManager](ctx)
		if networkManager == nil {
			return
		}
		protectFunc := networkManager.ProtectFunc()
		if protectFunc == nil {
			return
		}
		previousHook := dialer.DefaultSocketHook
		dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
			if previousHook != nil {
				if err := previousHook(network, address, conn); err != nil {
					return err
				}
			}
			return protectFunc(network, address, conn)
		}
	})
}

type Outbound struct {
	outboundAdapter.Adapter
	proxy     C.ProxyAdapter
	uotClient *uot.Client
	logger    log.ContextLogger
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.SnellOutboundOptions) (adapter.Outbound, error) {
	ver := options.Version
	if ver <= 0 {
		ver = 4
	}
	reuse := true
	if options.Reuse != nil {
		reuse = *options.Reuse
	}
	snellOption := &outbound.SnellOption{
		Name:              tag,
		Server:            options.Server,
		Port:              int(options.ServerPort),
		Psk:               options.PSK,
		Version:           ver,
		Reuse:             reuse,
		UDP:               options.UDP,
		ObfsOpts:          options.ObfsOpts,
		ClientFingerprint: options.ClientFingerprint,
	}

	installAndroidProtectHook(ctx)

	proxy, err := outbound.NewSnell(*snellOption)
	if err != nil {
		return nil, err
	}

	out := &Outbound{
		Adapter: outboundAdapter.NewAdapterWithDialerOptions(boxConstant.TypeSnell, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		proxy:   proxy,
		logger:  logger,
	}

	out.uotClient = &uot.Client{
		Dialer:  (snellDialer)(out.createProxy),
		Version: uot.Version,
	}

	return out, nil
}

type snellDialer func(ctx context.Context, destination M.Socksaddr) (net.Conn, error)

func (d snellDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return d(ctx, destination)
}

func (d snellDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}

func (h *Outbound) createProxy(ctx context.Context, destination M.Socksaddr) (net.Conn, error) {
	meta := &C.Metadata{
		NetWork: C.TCP,
		Host:    destination.AddrString(),
		DstPort: destination.Port,
	}
	conn, err := h.proxy.DialContext(ctx, meta)
	if err != nil {
		return nil, err
	}
	return conn, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "snell outbound connection to ", destination)
		return h.createProxy(ctx, destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "snell outbound UoT packet connection to ", destination)
		return h.uotClient.DialContext(ctx, network, destination)
	}
	return nil, os.ErrInvalid
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	meta := &C.Metadata{
		NetWork: C.UDP,
		Host:    destination.AddrString(),
		DstPort: destination.Port,
	}
	pc, err := h.proxy.ListenPacketContext(ctx, meta)
	if err == nil && pc != nil {
		return pc, nil
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "snell outbound UoT packet connection to ", destination)
	return h.uotClient.ListenPacket(ctx, destination)
}

func (h *Outbound) Close() error {
	return h.proxy.Close()
}
