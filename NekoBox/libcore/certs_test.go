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
	// Ensure systemRoots is initialized before snapshotting for cleanup.
	origPool, err := x509.SystemCertPool()
	if err != nil {
		t.Fatalf("x509.SystemCertPool: %v", err)
	}
	savedSystemRoots := systemRoots
	t.Cleanup(func() {
		systemRoots = savedSystemRoots
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

	updateRootCACerts(caPEM)

	updatedPool, err := x509.SystemCertPool()
	if err != nil {
		t.Fatalf("x509.SystemCertPool after update: %v", err)
	}
	// Custom CA must now validate leafCert via system roots.
	if _, err := leafCert.Verify(x509.VerifyOptions{DNSName: "leaf.internal.test"}); err != nil {
		t.Fatalf("expected leaf signed by internal CA to verify against systemRoots, got: %v", err)
	}
	// System roots must be preserved (appended, not replaced).
	if origPool != nil && updatedPool.Equal(origPool) {
		t.Fatalf("expected updated system cert pool to include appended custom CA")
	}
	if origPool != nil && len(origPool.Subjects()) > 0 { //nolint:staticcheck // test checks preserved system root count
		if len(updatedPool.Subjects()) <= len(origPool.Subjects()) { //nolint:staticcheck
			t.Fatalf("system roots were replaced instead of appended: orig=%d updated=%d",
				len(origPool.Subjects()), len(updatedPool.Subjects())) //nolint:staticcheck
		}
	}

	// Invalid PEM must not clobber the valid systemRoots.
	beforeInvalid := systemRoots
	updateRootCACerts([]byte("not a valid pem"))
	if systemRoots != beforeInvalid {
		t.Fatalf("invalid PEM modified systemRoots")
	}
}
