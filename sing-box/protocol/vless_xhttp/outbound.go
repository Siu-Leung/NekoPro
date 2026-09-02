package vless_xhttp

import (
	"context"
	"encoding/hex"
	"net"
	"os"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"

	"github.com/sagernet/sing-box/adapter"
	outboundAdapter "github.com/sagernet/sing-box/adapter/outbound"
	boxConstant "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/mihomo_adapter"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/uot"
)

func RegisterOutbound(registry *outboundAdapter.Registry) {
	outboundAdapter.Register[option.VlessXHTTPOutboundOptions](registry, boxConstant.TypeVlessXHTTP, NewOutbound)
}

type Outbound struct {
	outboundAdapter.Adapter
	proxy     C.ProxyAdapter
	uotClient *uot.Client
	logger    log.ContextLogger
}

func setMihomoIPv6Enabled() {
	resolver.DisableIPv6 = false
}

func parseFingerprint(fp, clientFP string) (certFingerprint string, browserFingerprint string) {
	browserFingerprint = clientFP
	if fp == "" {
		return
	}
	clean := strings.ReplaceAll(strings.TrimSpace(fp), ":", "")
	if len(clean) == 64 {
		if _, err := hex.DecodeString(clean); err == nil {
			certFingerprint = fp
			return
		}
	}
	if browserFingerprint == "" {
		browserFingerprint = fp
	}
	return
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.VlessXHTTPOutboundOptions) (adapter.Outbound, error) {
	clientFP := options.ClientFingerprint
	if clientFP == "" {
		clientFP = options.ClientFingerprintAlt
	}
	certFP, browserFP := parseFingerprint(options.Fingerprint, clientFP)

	// 构造 mihomo VLESS-XHTTP option
	vlessOption := &outbound.VlessOption{
		Name:              tag,
		Server:            options.Server,
		Port:              int(options.ServerPort),
		UUID:              options.UUID,
		Flow:              options.Flow,
		TLS:               options.TLS,
		ALPN:              options.ALPN,
		UDP:               options.UDP,
		Network:           "xhttp",
		PacketEncoding:    options.PacketEncoding,
		ServerName:        options.ServerName,
		Fingerprint:       certFP,
		ClientFingerprint: browserFP,
		SkipCertVerify:    options.SkipCertVerify,
		XHTTPOpts: outbound.XHTTPOptions{
			Path:    options.XHTTPPath,
			Host:    options.XHTTPHost,
			Mode:    options.XHTTPMode,
			Headers: options.XHTTPHeaders,
		},
	}
	if options.XHTTPReuse {
		vlessOption.XHTTPOpts.ReuseSettings = &outbound.XHTTPReuseSettings{
			MaxConnections: strconv.Itoa(options.XHTTPReuseMaxConns),
			MaxConcurrency: "0",
			CMaxReuseTimes: "0",
		}
	}

	mihomo_adapter.InstallProtectHook(ctx)
	// This library embedding bypasses mihomo's Clash executor, whose normal
	// responsibility includes enabling IPv6 in the resolver.
	setMihomoIPv6Enabled()

	proxy, err := outbound.NewVless(*vlessOption)
	if err != nil {
		return nil, err
	}

	out := &Outbound{
		Adapter: outboundAdapter.NewAdapterWithDialerOptions(boxConstant.TypeVlessXHTTP, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		proxy:   proxy,
		logger:  logger,
	}

	out.uotClient = &uot.Client{
		Dialer:  (vlessXHTTPDialer)(out.createProxy),
		Version: uot.Version,
	}

	return out, nil
}

type vlessXHTTPDialer func(ctx context.Context, destination M.Socksaddr) (net.Conn, error)

func (d vlessXHTTPDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	return d(ctx, destination)
}

func (d vlessXHTTPDialer) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
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
		h.logger.InfoContext(ctx, "vless-xhttp outbound connection to ", destination)
		return h.createProxy(ctx, destination)
	case N.NetworkUDP:
		h.logger.InfoContext(ctx, "vless-xhttp outbound UoT packet connection to ", destination)
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
	if err != nil {
		h.logger.DebugContext(ctx, "vless-xhttp native UDP unavailable, falling back to UoT: ", err)
	} else {
		h.logger.DebugContext(ctx, "vless-xhttp native UDP returned nil, falling back to UoT")
	}
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "vless-xhttp outbound UoT packet connection to ", destination)
	return h.uotClient.ListenPacket(ctx, destination)
}

func (h *Outbound) Close() error {
	return h.proxy.Close()
}
