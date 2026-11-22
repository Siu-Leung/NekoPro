package libcore

import (
	"net/netip"
	"testing"
)

func TestValidateProbeHttpsUrl(t *testing.T) {
	if _, err := validateProbeHttpsUrl("https://api.ip.sb/geoip"); err != nil {
		t.Fatalf("expected valid https URL, got %v", err)
	}
	for _, invalid := range []string{
		"",
		"http://api.ip.sb/geoip",
		"ftp://api.ip.sb/geoip",
		"https://user:pass@api.ip.sb/geoip",
		"https:///geoip",
	} {
		if _, err := validateProbeHttpsUrl(invalid); err == nil {
			t.Errorf("expected error for invalid probe URL %q", invalid)
		}
	}
}

func TestIsUsableResolvedAddrRejectsFakeIpAndBogons(t *testing.T) {
	for _, bad := range []string{
		"0.0.0.0",
		"127.0.0.1",
		"169.254.1.1",
		"198.18.0.1",
		"198.19.255.254",
		"224.0.0.1",
		"240.0.0.1",
		"::1",
		"fe80::1",
		"fc00::1",
		"fd12:3456::1",
		"::ffff:198.18.0.9",
	} {
		addr := netip.MustParseAddr(bad)
		if isUsableResolvedAddr(addr) {
			t.Errorf("expected %s to be rejected", bad)
		}
	}
	for _, good := range []string{"1.1.1.1", "223.5.5.5", "2606:4700:4700::1111"} {
		addr := netip.MustParseAddr(good)
		if !isUsableResolvedAddr(addr) {
			t.Errorf("expected %s to be accepted", good)
		}
	}
}

func TestForbiddenProbeOutboundTypes(t *testing.T) {
	for _, forbidden := range []string{"", "direct", "DIRECT", "block", "dns"} {
		if !isForbiddenProbeOutboundType(forbidden) {
			t.Errorf("expected %q to be forbidden for strict exit probe", forbidden)
		}
	}
	for _, allowed := range []string{"vless", "vless_xhttp", "vmess", "trojan", "shadowsocks", "wireguard", "anytls", "mieru", "snell", "masque", "hysteria2", "tuic", "juicity", "selector"} {
		if isForbiddenProbeOutboundType(allowed) {
			t.Errorf("expected %q to be allowed for strict exit probe", allowed)
		}
	}
}

func TestStripAnsiEscapeCodes(t *testing.T) {
	sample := "\x1b[36mINFO\x1b[0m[2576] [\x1b[38;5;193m2136742438\x1b[0m 0ms] inbound/mixed[mixed-in]: inbound connection"
	want := "INFO[2576] [2136742438 0ms] inbound/mixed[mixed-in]: inbound connection"
	if got := stripAnsiEscapeCodes(sample); got != want {
		t.Fatalf("stripAnsiEscapeCodes() = %q, want %q", got, want)
	}
	if plain := "WARN[0001] plain text [not ansi]"; stripAnsiEscapeCodes(plain) != plain {
		t.Fatalf("expected plain text to remain unchanged")
	}
}

