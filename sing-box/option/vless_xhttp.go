package option

// VlessXHTTPOutboundOptions configures VLESS + XHTTP (SplitHTTP) outbound
// backed by embedded Mihomo with sing-box Dialer & encrypted DNS integration.
type VlessXHTTPOutboundOptions struct {
	DialerOptions
	ServerOptions
	UUID           string `json:"uuid,omitempty"`
	Flow           string `json:"flow,omitempty"`
	PacketEncoding string `json:"packet_encoding,omitempty"`

	// TLS & REALITY
	TLS                  bool     `json:"tls,omitempty"`
	ServerName           string   `json:"server_name,omitempty"`
	ALPN                 []string `json:"alpn,omitempty"`
	Fingerprint          string   `json:"fingerprint,omitempty"`
	ClientFingerprint    string   `json:"client_fingerprint,omitempty"`
	ClientFingerprintAlt string   `json:"client-fingerprint,omitempty"`
	SkipCertVerify       bool     `json:"skip_cert_verify,omitempty"`
	RealityPublicKey     string   `json:"reality_public_key,omitempty"`
	RealityShortID       string   `json:"reality_short_id,omitempty"`

	// XHTTP transport & XMUX / Extra JSON
	XHTTPPath          string            `json:"xhttp_path,omitempty"`
	XHTTPHost          string            `json:"xhttp_host,omitempty"`
	XHTTPMode          string            `json:"xhttp_mode,omitempty"` // auto / packet-up / stream-up / stream-one
	XHTTPHeaders       map[string]string `json:"xhttp_headers,omitempty"`
	XHTTPExtra         string            `json:"xhttp_extra,omitempty"`
	XHTTPReuse         bool              `json:"xhttp_reuse,omitempty"`
	XHTTPReuseMaxConns int               `json:"xhttp_reuse_max_conns,omitempty"`

	// Tolerate multiplex block if enabled on profile in UI
	OutboundMultiplexOptions *OutboundMultiplexOptions `json:"multiplex,omitempty"`

	// UDP
	UDP bool `json:"udp,omitempty"`
}
