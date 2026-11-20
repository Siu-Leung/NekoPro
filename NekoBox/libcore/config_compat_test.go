package libcore

import (
	"context"
	"testing"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
)

func TestSingBox114ConfigSchemaDecode(t *testing.T) {
	ctx := box.Context(
		context.Background(),
		nekoboxAndroidInboundRegistry(),
		nekoboxAndroidOutboundRegistry(),
		nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(nil),
		nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)

	const sampleConfig = `{
		"log": {"level": "warn"},
		"dns": {
			"servers": [
				{"type": "local", "tag": "dns-local"},
				{"type": "https", "tag": "dns-direct", "server": "223.5.5.5", "path": "/dns-query"},
				{"type": "https", "tag": "dns-remote", "server": "dns.google", "path": "/dns-query", "detour": "proxy"},
				{"type": "fakeip", "tag": "dns-fake", "inet4_range": "198.18.0.0/15", "inet6_range": "fc00::/18"}
			],
			"rules": [
				{"domain": ["example.com"], "server": "dns-direct"},
				{"domain_suffix": ["ads.example"], "action": "predefined", "rcode": "NOERROR"},
				{"inbound": ["tun-in"], "query_type": ["A", "AAAA"], "server": "dns-fake", "disable_cache": true}
			],
			"final": "dns-remote",
			"strategy": "prefer_ipv4",
			"independent_cache": true
		},
		"inbounds": [
			{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 2080}
		],
		"outbounds": [
			{"type": "direct", "tag": "direct"},
			{"type": "direct", "tag": "bypass"},
			{
				"type": "snell",
				"tag": "snell-v4",
				"server": "127.0.0.1",
				"server_port": 8388,
				"psk": "secret",
				"version": 4,
				"reuse": true,
				"obfs_mode": "http",
				"obfs_host": "example.com"
			},
			{
				"type": "snell",
				"tag": "snell-v6",
				"server": "127.0.0.1",
				"server_port": 8389,
				"psk": "secret",
				"version": 6,
				"mode": "default",
				"reuse": true
			},
			{
				"type": "anytls",
				"tag": "anytls-out",
				"server": "127.0.0.1",
				"server_port": 443,
				"password": "secret",
				"tls": {"enabled": true, "server_name": "example.com"}
			},
			{
				"type": "selector",
				"tag": "proxy",
				"outbounds": ["snell-v4", "snell-v6", "anytls-out"],
				"default": "snell-v4"
			}
		],
		"route": {
			"auto_detect_interface": true,
			"default_domain_resolver": "dns-direct",
			"final": "proxy",
			"rules": [
				{"inbound": ["tun-in", "mixed-in"], "action": "sniff", "sniff_override_destination": true},
				{"port": [53], "action": "hijack-dns"},
				{"protocol": ["dns"], "action": "hijack-dns"}
			]
		}
	}`

	var opts option.Options
	if err := opts.UnmarshalJSONContext(ctx, []byte(sampleConfig)); err != nil {
		t.Fatalf("failed to decode sing-box 1.14 config: %v", err)
	}
	if len(opts.DNS.Servers) != 4 {
		t.Fatalf("expected 4 DNS servers, got %d", len(opts.DNS.Servers))
	}
	if opts.Route.DefaultDomainResolver == nil || opts.Route.DefaultDomainResolver.Server != "dns-direct" {
		t.Fatalf("expected default_domain_resolver=dns-direct, got %+v", opts.Route.DefaultDomainResolver)
	}
}
