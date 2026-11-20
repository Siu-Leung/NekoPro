package mieru

import (
	"context"
	"net"
	"net/netip"
	"os"
	"strings"

	mierucommon "github.com/enfein/mieru/v3/apis/common"
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
	"github.com/sagernet/sing/service"
)

func RegisterOutbound(registry *outboundAdapter.Registry) {
	outboundAdapter.Register[option.MieruOutboundOptions](registry, boxConstant.TypeMieru, NewOutbound)
}

type Outbound struct {
	outboundAdapter.Adapter
	proxy   C.ProxyAdapter
	logger  log.ContextLogger
	release func()
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
		MTU:            options.MTU,
		UserName:       options.UserName,
		Password:       options.Password,
		Multiplexing:   strings.TrimSpace(options.Multiplexing),
		HandshakeMode:  strings.TrimSpace(options.HandshakeMode),
		TrafficPattern: strings.TrimSpace(options.TrafficPattern),
	}

	if options.DomainResolver != nil && options.DomainResolver.Server != "" {
		if dnsRouter := service.FromContext[adapter.DNSRouter](ctx); dnsRouter != nil {
			mieruOpt.Resolver = &singBoxMieruResolver{
				router:     dnsRouter,
				transports: service.FromContext[adapter.DNSTransportManager](ctx),
				serverTag:  options.DomainResolver.Server,
				queryOptions: adapter.DNSQueryOptions{
					Strategy: boxConstant.DomainStrategy(options.DomainResolver.Strategy),
				},
			}
		}
	}

	release := mihomo_adapter.InstallProtectHook(ctx)
	proxy, err := outbound.NewMieru(mieruOpt)
	if err != nil {
		release()
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
		release: release,
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
	meta := buildMetadata(C.UDP, destination)
	pc, err := h.proxy.ListenPacketContext(ctx, meta)
	if err != nil {
		return nil, err
	}
	// mihomo resolved a domain destination into meta.DstIP and the returned
	// connection only accepts IP addresses; translate between them.
	return mihomo_adapter.WrapResolvedPacketConn(pc, meta, destination), nil
}

var _ adapter.Lifecycle = (*Outbound)(nil)

func (h *Outbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	scope.Add(h.Close)
	return nil
}

func (h *Outbound) Close() error {
	h.release()
	return h.proxy.Close()
}

type singBoxMieruResolver struct {
	router       adapter.DNSRouter
	transports   adapter.DNSTransportManager
	serverTag    string
	queryOptions adapter.DNSQueryOptions
}

var _ mierucommon.DNSResolver = (*singBoxMieruResolver)(nil)

func (r *singBoxMieruResolver) LookupIP(ctx context.Context, network, host string) ([]net.IP, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return []net.IP{ip.Unmap().AsSlice()}, nil
	}
	queryOptions := r.queryOptions
	if r.serverTag != "" && r.transports != nil {
		tr, loaded := r.transports.Transport(r.serverTag)
		if !loaded {
			return nil, os.ErrNotExist
		}
		queryOptions.Transport = tr
	}
	addrs, err := r.router.Lookup(ctx, host, queryOptions)
	if err != nil {
		return nil, err
	}
	ips := make([]net.IP, 0, len(addrs))
	for _, addr := range addrs {
		ips = append(ips, addr.Unmap().AsSlice())
	}
	return ips, nil
}
