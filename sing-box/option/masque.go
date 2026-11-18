package option

// MasqueOutboundOptions configures MASQUE (CONNECT-IP / CONNECT-UDP / Cloudflare WARP)
// outbound backed by embedded Mihomo.
type MasqueOutboundOptions struct {
	DialerOptions
	ServerOptions
	PrivateKey           string   `json:"private_key"`
	PublicKey            string   `json:"public_key"`
	LocalAddressIPv4     string   `json:"local_address,omitempty"`
	LocalAddressIPv6     string   `json:"local_address_v6,omitempty"`
	URI                  string   `json:"uri,omitempty"`
	ServerName           string   `json:"server_name,omitempty"`
	MTU                  int      `json:"mtu,omitempty"`
	UDP                  *bool    `json:"udp,omitempty"`
	SkipCertVerify       bool     `json:"skip_cert_verify,omitempty"`
	Network              string   `json:"network,omitempty"` // h3 / h2 / h3-l4proxy
	CongestionController string   `json:"congestion_controller,omitempty"`
	CWND                 int      `json:"cwnd,omitempty"`
	RemoteDNSResolve     bool     `json:"remote_dns_resolve,omitempty"`
	DNS                  []string `json:"dns,omitempty"`
}
