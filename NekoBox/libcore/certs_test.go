package libcore

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"testing"
	"time"
)

func TestUpdateRootCACertsAppendsToSystemPool(t *testing.T) {
	origPool, err := x509.SystemCertPool()
	if err != nil {
		t.Fatalf("x509.SystemCertPool: %v", err)
	}
	t.Cleanup(func() {
		UpdateRootCACerts(nil)
	})

	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	caTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "NekoPro Test Internal CA"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTemplate, caTemplate, &caKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	caCert, err := x509.ParseCertificate(caDER)
	if err != nil {
		t.Fatal(err)
	}
	caPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER})

	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	leafTemplate := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: "leaf.internal.test"},
		DNSNames:     []string{"leaf.internal.test"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leafTemplate, caCert, &leafKey.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	leafCert, err := x509.ParseCertificate(leafDER)
	if err != nil {
		t.Fatal(err)
	}

	if !UpdateRootCACerts(caPEM) {
		t.Fatalf("expected UpdateRootCACerts(caPEM) to succeed")
	}

	updatedPool := currentRootCAs()
	if updatedPool == nil {
		t.Fatalf("expected non-nil custom root pool after update")
	}
	if _, err := leafCert.Verify(x509.VerifyOptions{DNSName: "leaf.internal.test", Roots: updatedPool}); err != nil {
		t.Fatalf("expected leaf signed by internal CA to verify against custom root pool, got: %v", err)
	}
	if origPool != nil && updatedPool.Equal(origPool) {
		t.Fatalf("expected updated cert pool to include appended custom CA")
	}

	// Invalid PEM must not clobber the valid custom root pool.
	beforeInvalid := currentRootCAs()
	if UpdateRootCACerts([]byte("not a valid pem")) {
		t.Fatalf("expected invalid PEM to return false")
	}
	if currentRootCAs() != beforeInvalid {
		t.Fatalf("invalid PEM modified custom root pool")
	}

	// Empty PEM must restore unmodified system roots.
	if !UpdateRootCACerts(nil) {
		t.Fatalf("expected UpdateRootCACerts(nil) to succeed")
	}
	if currentRootCAs() != nil || currentCustomCAPEM() != "" {
		t.Fatalf("expected custom root pool and PEM to be cleared after reset")
	}
}
