package outbound

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"net/netip"
	"testing"

	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
)

type stubTargetResolver struct {
	resolver.Resolver
	v4 netip.Addr
}

func (s *stubTargetResolver) Invalid() bool {
	return true
}

func (s *stubTargetResolver) LookupIP(ctx context.Context, host string) ([]netip.Addr, error) {
	return []netip.Addr{s.v4}, nil
}

func (s *stubTargetResolver) LookupIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	return []netip.Addr{s.v4}, nil
}

func generateMasqueKeyPairB64(t *testing.T) (privB64, pubB64 string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	privDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	pubDER, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(privDER), base64.StdEncoding.EncodeToString(pubDER)
}

func TestNewMasqueRejectsMalformedURITemplateAndClosesH2IdleConns(t *testing.T) {
	privB64, pubB64 := generateMasqueKeyPairB64(t)

	_, err := NewMasque(MasqueOption{
		Name:       "bad-uri",
		Server:     "162.159.198.1",
		Port:       443,
		PrivateKey: privB64,
		PublicKey:  pubB64,
		Ip:         "172.16.0.2/32",
		URI:        "https://cloudflareaccess.com/{unclosed",
	})
	if err == nil {
		t.Fatal("expected NewMasque to reject malformed RFC 6570 URI template")
	}

	h2Out, err := NewMasque(MasqueOption{
		Name:       "valid-h2",
		Server:     "162.159.198.1",
		Port:       443,
		PrivateKey: privB64,
		PublicKey:  pubB64,
		Ip:         "172.16.0.2/32",
		Network:    "h2",
	})
	if err != nil {
		t.Fatalf("NewMasque h2 failed: %v", err)
	}
	if err := h2Out.Close(); err != nil {
		t.Fatalf("h2Out.Close: %v", err)
	}
}

func TestBaseResolveUDPUsesTargetResolverBeforeDefaultResolver(t *testing.T) {
	want := netip.MustParseAddr("198.51.100.88")
	base := NewBase(BaseOption{
		Name:           "test-base",
		Type:           C.Vless,
		UDP:            true,
		Prefer:         C.IPv4Only,
		TargetResolver: &stubTargetResolver{v4: want},
	})
	meta := &C.Metadata{
		NetWork: C.UDP,
		Host:    "udp-target.example",
		DstPort: 53,
	}
	if err := base.ResolveUDP(context.Background(), meta); err != nil {
		t.Fatalf("ResolveUDP: %v", err)
	}
	if meta.DstIP != want {
		t.Fatalf("ResolveUDP DstIP = %v, want %v", meta.DstIP, want)
	}
}
