package vless_xhttp

import (
	"testing"

	"github.com/metacubex/mihomo/component/resolver"
	_ "github.com/sagernet/sing-box/protocol/mihomo_adapter"
)

func TestEmbeddedMihomoAllowsIPv6(t *testing.T) {
	// mihomo_adapter.init() disables mihomo's IPv6 block once at process startup
	// instead of mutating resolver.DisableIPv6 concurrently per outbound (L-04).
	if resolver.DisableIPv6 {
		t.Fatal("mihomo IPv6 remains disabled after mihomo_adapter init")
	}
}
