package juicity

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"net"
	"os"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"github.com/exclavenetwork/sing-juicity"
	"github.com/gofrs/uuid/v5"
)

const TypeJuicity = "juicity"

type JuicityOutboundOptions struct {
	option.DialerOptions
	option.ServerOptions
	UUID              string `json:"uuid,omitempty"`
	Password          string `json:"password,omitempty"`
	CongestionControl string `json:"congestion_control,omitempty"`
	option.OutboundTLSOptionsContainer
	PinCertSha256 string `json:"pin_cert_sha256,omitempty"`
}

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[JuicityOutboundOptions](registry, TypeJuicity, NewOutbound)
}

var _ adapter.Outbound = (*Outbound)(nil)

type Outbound struct {
	outbound.Adapter
	logger logger.ContextLogger
	client *juicity.Client
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options JuicityOutboundOptions) (adapter.Outbound, error) {
	options.Server = strings.Trim(strings.TrimSpace(options.Server), "[]")
	if options.TLS == nil || !options.TLS.Enabled {
		return nil, C.ErrTLSRequired
	}
	outboundDialer, err := dialer.NewWithOptions(dialer.Options{
		Context:        ctx,
		Options:        options.DialerOptions,
		RemoteIsDomain: options.ServerIsDomain(),
	})
	if err != nil {
		return nil, err
	}
	if len(options.TLS.ALPN) == 0 {
		options.TLS.ALPN = []string{"h3"}
	}
	tlsConfig, err := tls.NewSTDClient(ctx, logger, options.Server, *options.TLS)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(options.PinCertSha256) != "" {
		pinCertSha256, err := decodeCertSha256(options.PinCertSha256)
		if err != nil {
			return nil, E.Cause(err, "decode pin cert sha256")
		}
		stdTLSConfig, _ := tlsConfig.STDConfig()
		stdTLSConfig.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
			peerHash := certChainHash(rawCerts)
			if !bytes.Equal(pinCertSha256, peerHash) {
				return E.New("peer cert hash mismatch")
			}
			return nil
		}
		stdTLSConfig.InsecureSkipVerify = true
	}
	uuidInstance, err := uuid.FromString(strings.TrimSpace(options.UUID))
	if err != nil {
		return nil, err
	}
	client, err := juicity.NewClient(juicity.ClientOptions{
		Context:           ctx,
		Dialer:            outboundDialer,
		ServerAddress:     options.Build(),
		TLSConfig:         tlsConfig,
		UUID:              [uuid.Size]byte(uuidInstance.Bytes()),
		Password:          options.Password,
		CongestionControl: strings.TrimSpace(options.CongestionControl),
	})
	if err != nil {
		return nil, err
	}
	return &Outbound{
		Adapter: outbound.NewAdapterWithDialerOptions(TypeJuicity, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		logger:  logger,
		client:  client,
	}, nil
}

var _ adapter.Lifecycle = (*Outbound)(nil)

func (o *Outbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateInitialize {
		return nil
	}
	scope.Add(o.Close)
	return nil
}

func (o *Outbound) Close() error {
	return o.client.CloseWithError(os.ErrClosed)
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	switch N.NetworkName(network) {
	case N.NetworkTCP:
		ctx, metadata := adapter.ExtendContext(ctx)
		metadata.Outbound = o.Tag()
		metadata.Destination = destination
		o.logger.InfoContext(ctx, "outbound connection to ", destination)
		return o.client.DialConn(ctx, destination)
	case N.NetworkUDP:
		conn, err := o.ListenPacket(ctx, destination)
		if err != nil {
			return nil, err
		}
		return bufio.NewBindPacketConn(conn, destination), nil
	default:
		return nil, E.New("unsupported network: ", network)
	}
}

func (o *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = o.Tag()
	metadata.Destination = destination
	o.logger.InfoContext(ctx, "outbound packet connection to ", destination)
	return o.client.ListenPacket(ctx, destination)
}

// decodeCertSha256 strictly validates 32-byte SHA-256 digests in hex (with or
// without colons) BEFORE trying Base64, preventing 64-char hex strings from
// falsely decoding as 48-byte Base64 payloads.
func decodeCertSha256(raw string) ([]byte, error) {
	trimmed := strings.TrimSpace(raw)
	cleanHex := strings.ReplaceAll(trimmed, ":", "")
	if len(cleanHex) == sha256.Size*2 {
		if decoded, err := hex.DecodeString(cleanHex); err == nil && len(decoded) == sha256.Size {
			return decoded, nil
		}
	}
	decoders := []*base64.Encoding{
		base64.URLEncoding,
		base64.RawURLEncoding,
		base64.StdEncoding,
		base64.RawStdEncoding,
	}
	var errs []error
	for _, dec := range decoders {
		decoded, err := dec.DecodeString(trimmed)
		if err == nil && len(decoded) == sha256.Size {
			return decoded, nil
		}
		if err != nil {
			errs = append(errs, err)
		}
	}
	return nil, E.Cause(E.Errors(errs...), "invalid 32-byte sha256 fingerprint")
}

func certChainHash(rawCerts [][]byte) []byte {
	var chainHash []byte
	for _, cert := range rawCerts {
		certHash := sha256.Sum256(cert)
		if chainHash == nil {
			chainHash = certHash[:]
		} else {
			newHash := sha256.Sum256(append(chainHash, certHash[:]...))
			chainHash = newHash[:]
		}
	}
	return chainHash
}
