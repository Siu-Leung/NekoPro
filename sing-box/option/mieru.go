package option

// MieruOutboundOptions configures native built-in Mieru v3 outbound
// backed by embedded Mihomo (no external plugin APK required).
type MieruOutboundOptions struct {
	DialerOptions
	ServerOptions
	PortRange      string `json:"port_range,omitempty"`
	Transport      string `json:"transport"` // TCP or UDP
	MTU            int32  `json:"mtu,omitempty"`
	UDP            *bool  `json:"udp,omitempty"`
	UserName       string `json:"username"`
	Password       string `json:"password"`
	Multiplexing   string `json:"multiplexing,omitempty"`
	HandshakeMode  string `json:"handshake_mode,omitempty"`
	TrafficPattern string `json:"traffic_pattern,omitempty"`
}
