package snell

import (
	"testing"

	"github.com/metacubex/mihomo/component/resolver"
	"github.com/sagernet/sing-box/option"
)

func TestV6ClientOptions(t *testing.T) {
	client, err := newV6Client(nil, option.SnellOutboundOptions{
		ServerOptions: option.ServerOptions{Server: "::1", ServerPort: 21006},
		PSK:           "test-psk",
		Version:       6,
		Mode:          "default",
	})
	if err != nil {
		t.Fatal(err)
	}
	if client == nil {
		t.Fatal("nil client")
	}
	client.Close()
}

func TestEmbeddedMihomoAllowsIPv6(t *testing.T) {
	setMihomoIPv6Enabled()
	if resolver.DisableIPv6 {
		t.Fatal("mihomo IPv6 remains disabled")
	}
}

func TestV6RejectsUnknownMode(t *testing.T) {
	_, err := newV6Client(nil, option.SnellOutboundOptions{
		ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: 21006},
		PSK:           "test-psk",
		Version:       6,
		Mode:          "broken",
	})
	if err == nil {
		t.Fatal("expected mode error")
	}
}
