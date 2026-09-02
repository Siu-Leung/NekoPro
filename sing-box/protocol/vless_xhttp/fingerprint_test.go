package vless_xhttp

import (
	"context"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

func TestParseFingerprint(t *testing.T) {
	// 1. Browser fingerprint in `fingerprint` field
	for _, fp := range []string{"chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"} {
		certFP, browserFP := parseFingerprint(fp, "")
		if certFP != "" {
			t.Fatalf("expected empty certFP for browser fingerprint %s, got %s", fp, certFP)
		}
		if browserFP != fp {
			t.Fatalf("expected browserFP %s, got %s", fp, browserFP)
		}
	}

	// 2. Explicit client_fingerprint takes priority for browser fingerprint
	certFP, browserFP := parseFingerprint("", "firefox")
	if certFP != "" || browserFP != "firefox" {
		t.Fatalf("expected ('', 'firefox'), got ('%s', '%s')", certFP, browserFP)
	}

	// 3. SHA256 certificate pinning fingerprint (64 hex characters)
	sha256Plain := "2c54efc1b480c4aebe592982d6b38c232c54efc1b480c4aebe592982d6b38c23"
	certFP, browserFP = parseFingerprint(sha256Plain, "")
	if certFP != sha256Plain || browserFP != "" {
		t.Fatalf("expected ('%s', ''), got ('%s', '%s')", sha256Plain, certFP, browserFP)
	}

	sha256Colon := "2c:54:ef:c1:b4:80:c4:ae:be:59:29:82:d6:b3:8c:23:2c:54:ef:c1:b4:80:c4:ae:be:59:29:82:d6:b3:8c:23"
	certFP, browserFP = parseFingerprint(sha256Colon, "")
	if certFP != sha256Colon || browserFP != "" {
		t.Fatalf("expected ('%s', ''), got ('%s', '%s')", sha256Colon, certFP, browserFP)
	}

	// 4. Combined cert pinning and client fingerprint
	certFP, browserFP = parseFingerprint(sha256Plain, "chrome")
	if certFP != sha256Plain || browserFP != "chrome" {
		t.Fatalf("expected ('%s', 'chrome'), got ('%s', '%s')", sha256Plain, certFP, browserFP)
	}
}

func TestNewOutboundBrowserFingerprint(t *testing.T) {
	ctx := context.Background()
	logger := log.NewContextLogger("test")

	// Verify that creating a VLESS-XHTTP outbound with Fingerprint: "chrome" does not fail
	// with "'fingerprint' is used for TLS certificate pinning".
	opts := option.VlessXHTTPOutboundOptions{
		ServerOptions: option.ServerOptions{
			Server:     "127.0.0.1",
			ServerPort: 8443,
		},
		UUID:        "b831381d-6324-4d53-ad4f-8cda48b30811",
		TLS:         true,
		ServerName:  "example.com",
		Fingerprint: "chrome",
		XHTTPPath:   "/xhttp",
		XHTTPMode:   "auto",
	}

	outbound, err := NewOutbound(ctx, nil, logger, "test-vless-xhttp", opts)
	if err != nil {
		if strings.Contains(err.Error(), "certificate pinning") {
			t.Fatalf("NewOutbound failed with certificate pinning error: %v", err)
		}
		t.Fatalf("unexpected NewOutbound error: %v", err)
	}
	if outbound == nil {
		t.Fatal("expected non-nil outbound")
	}
	defer outbound.Close()
}
