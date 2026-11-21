package vless_xhttp

import (
	"context"
	"encoding/hex"
	stdjson "encoding/json"
	"fmt"
	"net"
	"os"
	"strconv"
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
	release   func()
}

func parseFingerprint(fp, clientFP string) (certFingerprint string, browserFingerprint string) {
	browserFingerprint = strings.TrimSpace(clientFP)
	fp = strings.TrimSpace(fp)
	if fp == "" {
		return
	}
	clean := strings.ReplaceAll(fp, ":", "")
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

func applyXHTTPExtra(opts *outbound.XHTTPOptions, rawExtra string) error {
	rawExtra = strings.TrimSpace(rawExtra)
	if rawExtra == "" {
		return nil
	}
	var extra map[string]any
	if err := stdjson.Unmarshal([]byte(rawExtra), &extra); err != nil {
		return fmt.Errorf("vless_xhttp: invalid xhttp_extra JSON: %w", err)
	}
	getStr := func(m map[string]any, keys ...string) string {
		for _, k := range keys {
			if v, ok := m[k]; ok && v != nil {
				switch val := v.(type) {
				case string:
					return val
				case float64:
					if val == float64(int64(val)) {
						return strconv.FormatInt(int64(val), 10)
					}
					return fmt.Sprintf("%v", val)
				case bool:
					return strconv.FormatBool(val)
				}
			}
		}
		return ""
	}
	if v := getStr(extra, "xPaddingBytes", "x-padding-bytes"); v != "" {
		opts.XPaddingBytes = v
	}
	if v, ok := extra["noGrpcHeader"].(bool); ok {
		opts.NoGRPCHeader = v
	} else if v, ok := extra["no-grpc-header"].(bool); ok {
		opts.NoGRPCHeader = v
	}
	if v := getStr(extra, "scMaxEachPostBytes", "sc-max-each-post-bytes"); v != "" {
		opts.ScMaxEachPostBytes = v
	}
	if v := getStr(extra, "scMinPostsIntervalMs", "sc-min-posts-interval-ms"); v != "" {
		opts.ScMinPostsIntervalMs = v
	}
	if hdrs, ok := extra["headers"].(map[string]any); ok && len(hdrs) > 0 {
		if opts.Headers == nil {
			opts.Headers = make(map[string]string, len(hdrs))
		}
		for k, v := range hdrs {
			opts.Headers[k] = fmt.Sprint(v)
		}
	}
	parseReuse := func(m map[string]any) *outbound.XHTTPReuseSettings {
		rs := &outbound.XHTTPReuseSettings{
			MaxConcurrency:   getStr(m, "maxConcurrency", "max-concurrency"),
			MaxConnections:   getStr(m, "maxConnections", "max-connections"),
			CMaxReuseTimes:   getStr(m, "cMaxReuseTimes", "c-max-reuse-times"),
			HMaxRequestTimes: getStr(m, "hMaxRequestTimes", "h-max-request-times"),
			HMaxReusableSecs: getStr(m, "hMaxReusableSecs", "h-max-reusable-secs"),
		}
		if kp, ok := m["hKeepAlivePeriod"].(float64); ok {
			rs.HKeepAlivePeriod = int(kp)
		} else if kp, ok := m["h-keep-alive-period"].(float64); ok {
			rs.HKeepAlivePeriod = int(kp)
		}
		return rs
	}
	if xmux, ok := extra["xmux"].(map[string]any); ok {
		opts.ReuseSettings = parseReuse(xmux)
	} else if rs, ok := extra["reuse-settings"].(map[string]any); ok {
		opts.ReuseSettings = parseReuse(rs)
	}
	return nil
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.VlessXHTTPOutboundOptions) (adapter.Outbound, error) {
	options.Server = strings.Trim(strings.TrimSpace(options.Server), "[]")
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	outboundResolver, err := mihomo_adapter.NewOutboundResolver(ctx, options.DialerOptions, outboundDialer)
	if err != nil {
		return nil, err
	}

	clientFP := options.ClientFingerprint
	if clientFP == "" {
		clientFP = options.ClientFingerprintAlt
	}
	certFP, browserFP := parseFingerprint(options.Fingerprint, clientFP)
	if options.RealityPublicKey != "" && browserFP == "" {
		browserFP = "chrome"
	}

	vlessOption := &outbound.VlessOption{
		BasicOption: outbound.BasicOption{
			DialerForAPI: mihomo_adapter.NewDialer(outboundDialer),
			Resolver:     outboundResolver,
		},
		Name:              tag,
		Server:            options.Server,
		Port:              int(options.ServerPort),
		UUID:              options.UUID,
		Flow:              options.Flow,
		TLS:               options.TLS || options.RealityPublicKey != "",
		ALPN:              options.ALPN,
		UDP:               options.UDP,
		Network:           "xhttp",
		PacketEncoding:    options.PacketEncoding,
		ServerName:        options.ServerName,
		Fingerprint:       certFP,
		Certificate:       strings.TrimSpace(options.Certificate),
		ClientFingerprint: browserFP,
		SkipCertVerify:    options.SkipCertVerify,
		XHTTPOpts: outbound.XHTTPOptions{
			Path:    options.XHTTPPath,
			Host:    options.XHTTPHost,
			Mode:    options.XHTTPMode,
			Headers: options.XHTTPHeaders,
		},
	}
	if options.ECHEnabled {
		vlessOption.ECHOpts = outbound.ECHOptions{
			Enable:   true,
			Config:   strings.TrimSpace(options.ECHConfig),
			Resolver: outboundResolver,
		}
	}
	if options.RealityPublicKey != "" {
		vlessOption.RealityOpts = outbound.RealityOptions{
			PublicKey: strings.TrimSpace(options.RealityPublicKey),
			ShortID:   strings.TrimSpace(options.RealityShortID),
		}
	}
	if options.XHTTPReuse {
		vlessOption.XHTTPOpts.ReuseSettings = &outbound.XHTTPReuseSettings{
			MaxConnections: strconv.Itoa(options.XHTTPReuseMaxConns),
			MaxConcurrency: "0",
			CMaxReuseTimes: "0",
		}
	}
	if err := applyXHTTPExtra(&vlessOption.XHTTPOpts, options.XHTTPExtra); err != nil {
		return nil, err
	}

	release := mihomo_adapter.InstallProtectHook(ctx)

	proxy, err := outbound.NewVless(*vlessOption)
	if err != nil {
		release()
		return nil, err
	}

	out := &Outbound{
		Adapter: outboundAdapter.NewAdapterWithDialerOptions(boxConstant.TypeVlessXHTTP, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		proxy:   proxy,
		logger:  logger,
		release: release,
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
	if destination.IsIP() {
		meta.DstIP = destination.Addr
		meta.Host = ""
	}
	return h.proxy.DialContext(ctx, meta)
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
		// ListenPacket prefers native UDP and already falls back to UoT.
		pc, err := h.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(pc, destination), nil
	}
	return nil, os.ErrInvalid
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	meta := &C.Metadata{
		NetWork: C.UDP,
		Host:    destination.AddrString(),
		DstPort: destination.Port,
	}
	if destination.IsIP() {
		meta.DstIP = destination.Addr
		meta.Host = ""
	}
	pc, err := h.proxy.ListenPacketContext(ctx, meta)
	if err == nil && pc != nil {
		// mihomo resolved a domain destination into meta.DstIP and the
		// returned connection only accepts IP addresses; translate.
		return mihomo_adapter.WrapResolvedPacketConn(pc, meta, destination), nil
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
