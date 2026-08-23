package vless_xhttp

import (
	"testing"

	"github.com/metacubex/mihomo/component/resolver"
)

func TestEmbeddedMihomoAllowsIPv6(t *testing.T) {
	resolver.DisableIPv6 = true
	setMihomoIPv6Enabled()
	if resolver.DisableIPv6 {
		t.Fatal("mihomo IPv6 remains disabled")
	}
}
