package snell

import (
	"context"
	"net"
	"os"
	"strings"

	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"

	"github.com/sagernet/sing-box/adapter"
	outboundAdapter "github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	boxConstant "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/mihomo_adapter"
	"github.com/sagernet/sing-snell/snellv6"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
)

func RegisterOutbound(registry *outboundAdapter.Registry) {
	outboundAdapter.Register[option.SnellOutboundOptions](registry, boxConstant.TypeSnell, NewOutbound)
}

type Outbound struct {
	outboundAdapter.Adapter
	proxy     C.ProxyAdapter
	uotClient *uot.Client
	logger    log.ContextLogger
	v6Client  *snellv6.Client
}

func setMihomoIPv6Enabled() {
	resolver.DisableIPv6 = false
}

func newV6Client(ctx context.Context, options option.SnellOutboundOptions) (*snellv6.Client, error) {
	options.Server = strings.Trim(strings.TrimSpace(options.Server), "[]")
	mode, err := snellv6.ParseMode(options.Mode)
	if err != nil {
		return nil, err
	}
	var outboundDialer N.Dialer
	if ctx != nil {
		outboundDialer, err = dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
		if err != nil {
			return nil, err
		}
	}
	server := options.ServerOptions.Build()
	return snellv6.NewClient(snellv6.ClientOptions{
		PSK:    []byte(options.PSK),
		Mode:   mode,
		Reuse:  options.Reuse == nil || *options.Reuse,
		Dialer: outboundDialer,
		Server: server,
	})
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.SnellOutboundOptions) (adapter.Outbound, error) {
	ver := options.Version
	if ver <= 0 {
		ver = 4
	}
	// Snell v5 servers are backward-compatible with v4 client logic
	if ver == 5 {
		ver = 4
	}
	reuse := true
	if options.Reuse != nil {
		reuse = *options.Reuse
	}
	cleanServer := strings.Trim(strings.TrimSpace(options.Server), "[]")
	options.Server = cleanServer
	if ver == 6 {
		client, err := newV6Client(ctx, options)
		if err != nil {
			return nil, err
		}
		return &Outbound{
			Adapter:  outboundAdapter.NewAdapterWithDialerOptions(boxConstant.TypeSnell, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
			logger:   logger,
			v6Client: client,
		}, nil
	}
	snellOption := &outbound.SnellOption{
		Name:              tag,
		Server:            cleanServer,
		Port:              int(options.ServerPort),
		Psk:               options.PSK,
		Version:           ver,
		Reuse:             reuse,
		UDP:               options.UDP,
		ObfsOpts:          options.ObfsOpts,
		ClientFingerprint: options.ClientFingerprint,
	}

	mihomo_adapter.InstallProtectHook(ctx)
	// Embedded mihomo defaults to IPv4-only unless its full Clash executor
	// initializes global DNS state. The Android bridge embeds only outbounds,
	// so allow literal/domain IPv6 servers here.
	setMihomoIPv6Enabled()

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
	if h.v6Client != nil {
		return h.v6Client.DialContext(ctx, destination)
	}
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
	if h.v6Client != nil {
		return nil, os.ErrInvalid
	}
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
	if h.v6Client != nil {
		return h.v6Client.Close()
	}
	return h.proxy.Close()
}
