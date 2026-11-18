package snell

import (
	"context"
	"testing"

	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
)

func TestSnellV4V5V6OutboundCreation(t *testing.T) {
	ctx := context.Background()

	// v6 with bracketed IPv6 literal
	outV6, err := NewOutbound(ctx, nil, log.NewNOPFactory().Logger(), "snell-v6", option.SnellOutboundOptions{
		ServerOptions: option.ServerOptions{Server: "[::1]", ServerPort: 21006},
		PSK:           "test-psk",
		Version:       6,
		Mode:          "default",
	})
	if err != nil {
		t.Fatalf("expected v6 outbound success, got error: %v", err)
	}
	_ = outV6.(interface{ Close() error }).Close()

	// v5 mapped cleanly to v4
	outV5, err := NewOutbound(ctx, nil, log.NewNOPFactory().Logger(), "snell-v5", option.SnellOutboundOptions{
		ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: 21004},
		PSK:           "test-psk",
		Version:       5,
		ObfsOpts:      map[string]any{"mode": "tls", "host": "example.com"},
	})
	if err != nil {
		t.Fatalf("expected v5 outbound success, got error: %v", err)
	}
	_ = outV5.(interface{ Close() error }).Close()
}

func TestSnellRejectsInvalidVersionAndMode(t *testing.T) {
	ctx := context.Background()

	if _, err := NewOutbound(ctx, nil, log.NewNOPFactory().Logger(), "snell-v3", option.SnellOutboundOptions{
		ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: 8388},
		PSK:           "test-psk",
		Version:       3,
	}); err == nil {
		t.Fatal("expected unsupported version error for v3")
	}

	if _, err := NewOutbound(ctx, nil, log.NewNOPFactory().Logger(), "snell-bad-mode", option.SnellOutboundOptions{
		ServerOptions: option.ServerOptions{Server: "127.0.0.1", ServerPort: 21006},
		PSK:           "test-psk",
		Version:       6,
		Mode:          "broken",
	}); err == nil {
		t.Fatal("expected invalid mode error for v6")
	}
}
