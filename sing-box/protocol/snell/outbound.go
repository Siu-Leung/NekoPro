package snell

import (
	"context"
	"net"
	"os"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	outboundAdapter "github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	boxConstant "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	snellprotocol "github.com/sagernet/sing-snell"
	"github.com/sagernet/sing-snell/snellv4"
	"github.com/sagernet/sing-snell/snellv6"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

func RegisterOutbound(registry *outboundAdapter.Registry) {
	outboundAdapter.Register[option.SnellOutboundOptions](registry, boxConstant.TypeSnell, NewOutbound)
}

type snellClient interface {
	snellprotocol.Method
	DialContext(ctx context.Context, destination M.Socksaddr) (net.Conn, error)
	Reset()
	Close() error
}

type Outbound struct {
	outboundAdapter.Adapter
	logger     log.ContextLogger
	dialer     N.Dialer
	client     snellClient
	serverAddr M.Socksaddr
}

var _ adapter.InterfaceUpdateListener = (*Outbound)(nil)

func parseObfs(opts map[string]any) (snellprotocol.ObfsMode, string, error) {
	if len(opts) == 0 {
		return snellprotocol.ObfsModeNone, "", nil
	}
	modeStr, _ := opts["mode"].(string)
	hostStr, _ := opts["host"].(string)
	if modeStr == "" || modeStr == "none" {
		return snellprotocol.ObfsModeNone, hostStr, nil
	}
	mode, err := snellprotocol.ParseObfsMode(modeStr)
	if err != nil {
		return snellprotocol.ObfsModeNone, "", err
	}
	return mode, hostStr, nil
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.SnellOutboundOptions) (adapter.Outbound, error) {
	options.Server = strings.Trim(strings.TrimSpace(options.Server), "[]")
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	serverAddr := options.ServerOptions.Build()

	ver := options.Version
	if ver <= 0 || ver == 5 {
		// Snell v5 servers are backward-compatible with v4 client logic
		ver = 4
	}

	reuse := true
	if options.Reuse != nil {
		reuse = *options.Reuse
	}

	var client snellClient
	switch ver {
	case 4:
		obfsMode, obfsHost, err := parseObfs(options.ObfsOpts)
		if err != nil {
			return nil, err
		}
		c, err := snellv4.NewClient(snellv4.ClientOptions{
			PSK:      []byte(options.PSK),
			Reuse:    reuse,
			ObfsMode: obfsMode,
			ObfsHost: obfsHost,
			Dialer:   outboundDialer,
			Server:   serverAddr,
		})
		if err != nil {
			return nil, err
		}
		client = c
	case 6:
		mode, err := snellv6.ParseMode(options.Mode)
		if err != nil {
			return nil, err
		}
		c, err := snellv6.NewClient(snellv6.ClientOptions{
			PSK:    []byte(options.PSK),
			Mode:   mode,
			Reuse:  reuse,
			Dialer: outboundDialer,
			Server: serverAddr,
		})
		if err != nil {
			return nil, err
		}
		client = c
	default:
		return nil, E.New("snell: unsupported version: ", ver)
	}

	return &Outbound{
		Adapter:    outboundAdapter.NewAdapterWithDialerOptions(boxConstant.TypeSnell, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		logger:     logger,
		dialer:     outboundDialer,
		client:     client,
		serverAddr: serverAddr,
	}, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	networkName := N.NetworkName(network)
	switch networkName {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "snell outbound connection to ", destination)
		return h.client.DialContext(ctx, destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "snell outbound packet connection to ", destination)
		conn, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
		if err != nil {
			return nil, err
		}
		packetConn, err := h.client.DialPacketConn(conn)
		if err != nil {
			conn.Close()
			return nil, err
		}
		return bufio.NewBindPacketConn(packetConn, destination), nil
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "snell outbound packet connection to ", destination)
	conn, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
	if err != nil {
		return nil, err
	}
	packetConn, err := h.client.DialPacketConn(conn)
	if err != nil {
		conn.Close()
		return nil, err
	}
	return packetConn, nil
}

func (h *Outbound) InterfaceUpdated(ctx context.Context) {
	h.client.Reset()
}

func (h *Outbound) Close() error {
	if h.client != nil {
		return h.client.Close()
	}
	return nil
}
