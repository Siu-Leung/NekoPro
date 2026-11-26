package masque

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"io"
	"math/big"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/quic-go/quicvarint"
	"github.com/metacubex/tls"
)

func TestH2DatagramStreamRejectsOversizedCapsulePayload(t *testing.T) {
	var frame bytes.Buffer
	buf := quicvarint.Append(nil, h2DatagramCapsuleType)
	// Encode an attacker-supplied payload length above maxH2CapsulePayloadBytes (e.g. 1 GiB).
	buf = quicvarint.Append(buf, 1<<30)
	frame.Write(buf)

	stream := &h2DatagramStream{
		responseBody: io.NopCloser(&frame),
		cancel:       func() {},
	}
	_, err := stream.ReceiveDatagram(context.Background())
	if err == nil || !strings.Contains(err.Error(), "exceeds maximum") {
		t.Fatalf("ReceiveDatagram oversized payload err = %v, want exceeds maximum error", err)
	}
}

func TestConnectTunnelInvalidURITemplateReturnsErrorWithoutPanic(t *testing.T) {
	const malformedTemplate = "https://cloudflareaccess.com/{unclosed"

	if _, _, err := ConnectTunnelH2(context.Background(), nil, malformedTemplate); err == nil {
		t.Fatal("ConnectTunnelH2 expected error for malformed URI template")
	}
	if _, _, err := ConnectTunnel(context.Background(), nil, malformedTemplate); err == nil {
		t.Fatal("ConnectTunnel expected error for malformed URI template")
	}
}

func generateTestCert(t *testing.T, key *ecdsa.PrivateKey) *x509.Certificate {
	t.Helper()
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("CreateCertificate: %v", err)
	}
	parsed, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatalf("ParseCertificate: %v", err)
	}
	return parsed
}

func TestPrepareTlsConfigPinsLeafCertAndRejectsEmptyChain(t *testing.T) {
	clientKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	leafCert := generateTestCert(t, leafKey)
	caCert := generateTestCert(t, caKey)

	cfg, err := PrepareTlsConfig(clientKey, &leafKey.PublicKey, ConnectSNI, false)
	if err != nil {
		t.Fatalf("PrepareTlsConfig: %v", err)
	}

	if err := cfg.VerifyConnection(tls.ConnectionState{}); err == nil {
		t.Fatal("expected empty PeerCertificates to be rejected")
	}
	if err := cfg.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{leafCert, caCert}}); err != nil {
		t.Fatalf("expected matching leaf cert with distinct CA cert in chain to succeed, got %v", err)
	}
	if err := cfg.VerifyConnection(tls.ConnectionState{PeerCertificates: []*x509.Certificate{caCert, leafCert}}); err == nil {
		t.Fatal("expected mismatched leaf cert to fail even if trusted key appears later in chain")
	}
}
