package option

type SnellOutboundOptions struct {
	DialerOptions
	ServerOptions
	PSK               string         `json:"psk"`
	Version           int            `json:"version,omitempty"`
	Mode              string         `json:"mode,omitempty"`
	Reuse             *bool          `json:"reuse,omitempty"`
	UDP               bool           `json:"udp,omitempty"`
	ObfsOpts          map[string]any `json:"obfs-opts,omitempty"`
	ClientFingerprint string         `json:"client-fingerprint,omitempty"`
}
