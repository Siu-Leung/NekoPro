package option

import "github.com/sagernet/sing/common/json"

// VlessXHTTPOutboundOptions is the options for VLESS-XHTTP outbound.
// XHTTP 是 VLESS 的传输模式(借道 mihomo 内核实现),
// 对应 Xray/mihomo 的 VLESS + xhttp transport。
type VlessXHTTPOutboundOptions struct {
	DialerOptions
	ServerOptions
	UUID       string `json:"uuid,omitempty"`
	Flow       string `json:"flow,omitempty"`
	PacketEncoding string `json:"packet_encoding,omitempty"`

	// TLS
	TLS                  bool     `json:"tls,omitempty"`
	ServerName           string   `json:"server_name,omitempty"`
	ALPN                 []string `json:"alpn,omitempty"`
	Fingerprint          string   `json:"fingerprint,omitempty"`
	ClientFingerprint    string   `json:"client_fingerprint,omitempty"`
	ClientFingerprintAlt string   `json:"client-fingerprint,omitempty"`
	SkipCertVerify       bool     `json:"skip_cert_verify,omitempty"`

	// XHTTP 参数
	XHTTPPath      string            `json:"xhttp_path,omitempty"`
	XHTTPHost      string            `json:"xhttp_host,omitempty"`
	XHTTPMode      string            `json:"xhttp_mode,omitempty"` // auto / packet-up / stream-up / stream-one
	XHTTPHeaders   map[string]string `json:"xhttp_headers,omitempty"`
	XHTTPReuse     bool   `json:"xhttp_reuse,omitempty"`
	XHTTPReuseMaxConns int `json:"xhttp_reuse_max_conns,omitempty"`

	// UDP
	UDP bool `json:"udp,omitempty"`
}

func (o VlessXHTTPOutboundOptions) MarshalJSON() ([]byte, error) {
	return json.Marshal(o)
}
