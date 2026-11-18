package mieru

import (
	"context"
	"net"
	"os"
	"strings"

	"github.com/metacubex/mihomo/adapter/outbound"
	C "github.com/metacubex/mihomo/constant"

	"github.com/sagernet/sing-box/adapter"
	outboundAdapter "github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	boxConstant "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/mihomo_adapter"
	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func RegisterOutbound(registry *outboundAdapter.Registry) {
	outboundAdapter.Register[option.MieruOutboundOptions](registry, boxConstant.TypeMieru, NewOutbound)
}

type Outbound struct {
	outboundAdapter.Adapter
	proxy  C.ProxyAdapter
	logger log.ContextLogger
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.MieruOutboundOptions) (adapter.Outbound, error) {
	options.Server = strings.Trim(strings.TrimSpace(options.Server), "[]")
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}

	udpEnabled := true
	if options.UDP != nil {
		udpEnabled = *options.UDP
	}
	transport := strings.ToUpper(strings.TrimSpace(options.Transport))
	if transport == "" {
		transport = "TCP"
	}

	port := int(options.ServerPort)
	portRange := strings.TrimSpace(options.PortRange)
	if portRange != "" {
		port = 0
	}

	mieruOpt := outbound.MieruOption{
		BasicOption: outbound.BasicOption{
			DialerForAPI: mihomo_adapter.NewDialer(outboundDialer),
		},
		Name:           tag,
		Server:         options.Server,
		Port:           port,
		PortRange:      portRange,
		Transport:      transport,
		UDP:            udpEnabled,
		UserName:       options.UserName,
		Password:       options.Password,
		Multiplexing:   strings.TrimSpace(options.Multiplexing),
		HandshakeMode:  strings.TrimSpace(options.HandshakeMode),
		TrafficPattern: strings.TrimSpace(options.TrafficPattern),
	}

	mihomo_adapter.InstallProtectHook(ctx)
	proxy, err := outbound.NewMieru(mieruOpt)
	if err != nil {
		return nil, err
	}

	networks := []string{N.NetworkTCP}
	if udpEnabled {
		networks = append(networks, N.NetworkUDP)
	}

	return &Outbound{
		Adapter: outboundAdapter.NewAdapterWithDialerOptions(boxConstant.TypeMieru, tag, networks, options.DialerOptions),
		proxy:   proxy,
		logger:  logger,
	}, nil
}

func buildMetadata(network C.NetWork, destination M.Socksaddr) *C.Metadata {
	meta := &C.Metadata{
		NetWork: network,
		DstPort: destination.Port,
	}
	if destination.IsIP() {
		meta.DstIP = destination.Addr
	} else {
		meta.Host = destination.Fqdn
	}
	return meta
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "mieru outbound connection to ", destination)
		return h.proxy.DialContext(ctx, buildMetadata(C.TCP, destination))
	case N.NetworkUDP:
		pc, err := h.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(pc, destination), nil
	default:
		return nil, os.ErrInvalid
	}
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "mieru outbound packet connection to ", destination)
	pc, err := h.proxy.ListenPacketContext(ctx, buildMetadata(C.UDP, destination))
	if err != nil {
		return nil, err
	}
	return pc, nil
}

func (h *Outbound) Close() error {
	return h.proxy.Close()
}
