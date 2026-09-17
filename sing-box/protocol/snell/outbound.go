package snell

import (
	"context"
	"net"
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

type Outbound struct {
	outboundAdapter.Adapter
	logger     log.ContextLogger
	dialer     N.Dialer
	v4Client   *snellv4.Client
	v6Client   *snellv6.Client
	serverAddr M.Socksaddr
}

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

	out := &Outbound{
		Adapter:    outboundAdapter.NewAdapterWithDialerOptions(boxConstant.TypeSnell, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		logger:     logger,
		dialer:     outboundDialer,
		serverAddr: serverAddr,
	}

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
		out.v4Client = c
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
		out.v6Client = c
	default:
		return nil, E.New("snell: unsupported version: ", ver)
	}

	return out, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	networkName := N.NetworkName(network)
	switch networkName {
	case N.NetworkTCP:
		h.logger.InfoContext(ctx, "snell outbound connection to ", destination)
		conn, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
		if err != nil {
			return nil, err
		}
		if h.v4Client != nil {
			return h.v4Client.DialConn(conn, destination)
		}
		if h.v6Client != nil {
			return h.v6Client.DialConn(conn, destination)
		}
		conn.Close()
		return nil, E.New("snell: client not initialized")
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "snell outbound packet connection to ", destination)
		conn, err := h.dialer.DialContext(ctx, N.NetworkTCP, h.serverAddr)
		if err != nil {
			return nil, err
		}
		var packetConn N.NetPacketConn
		if h.v4Client != nil {
			packetConn, err = h.v4Client.DialPacketConn(conn)
		} else if h.v6Client != nil {
			packetConn, err = h.v6Client.DialPacketConn(conn)
		} else {
			conn.Close()
			return nil, E.New("snell: client not initialized")
		}
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
	var packetConn N.NetPacketConn
	if h.v4Client != nil {
		packetConn, err = h.v4Client.DialPacketConn(conn)
	} else if h.v6Client != nil {
		packetConn, err = h.v6Client.DialPacketConn(conn)
	} else {
		conn.Close()
		return nil, E.New("snell: client not initialized")
	}
	if err != nil {
		conn.Close()
		return nil, err
	}
	return packetConn, nil
}
