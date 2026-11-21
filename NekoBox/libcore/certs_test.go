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

	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

func TestUpdateRootCACertsAppendsToSystemPoolAndPreservesKeepAlive(t *testing.T) {
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

	// KeepAlive httpClient must reuse the shared transport (nil closer) across
	// repeated requests when custom CA generation has not changed.
	hc := NewHttpClient().(*httpClient)
	hc.KeepAlive()
	req1 := hc.NewRequest().(*httpRequest)
	c1, closer1 := req1.prepareH1H2Client()
	if closer1 != nil || c1 != &hc.h1h2Client {
		t.Fatalf("expected first KeepAlive request to reuse h1h2Client with custom CA, closer=%v", closer1)
	}
	req2 := hc.NewRequest().(*httpRequest)
	c2, closer2 := req2.prepareH1H2Client()
	if closer2 != nil || c2 != &hc.h1h2Client {
		t.Fatalf("expected second KeepAlive request to reuse h1h2Client, closer=%v", closer2)
	}

	// CertificateStoreNone must not be overridden by injectCustomRootCA.
	noneOpts := option.Options{
		Certificate: &option.CertificateOptions{Store: constant.CertificateStoreNone},
	}
	injectCustomRootCA(&noneOpts)
	if len(noneOpts.Certificate.Certificate) != 0 {
		t.Fatalf("expected CertificateStoreNone to remain empty, got %d cert(s)", len(noneOpts.Certificate.Certificate))
	}

	// Default options should receive exactly one custom PEM entry.
	sysOpts := option.Options{}
	injectCustomRootCA(&sysOpts)
	if sysOpts.Certificate == nil || len(sysOpts.Certificate.Certificate) != 1 {
		t.Fatalf("expected 1 injected custom CA entry, got %+v", sysOpts.Certificate)
	}

	// Invalid PEM must not clobber the valid custom root pool.
	beforeInvalid := currentRootCAs()
	if UpdateRootCACerts([]byte("not a valid pem")) {
		t.Fatalf("expected invalid PEM to return false")
	}
	if currentRootCAs() != beforeInvalid {
		t.Fatalf("invalid PEM modified custom root pool")
	}

	// Empty PEM must restore unmodified system roots and rotate CA generation so
	// existing httpClient instances pick up the reset pool.
	if !UpdateRootCACerts(nil) {
		t.Fatalf("expected UpdateRootCACerts(nil) to succeed")
	}
	if currentRootCAs() != nil || currentCustomCAPEM() != "" {
		t.Fatalf("expected custom root pool and PEM to be cleared after reset")
	}
	reqAfterReset := hc.NewRequest().(*httpRequest)
	_, closerAfterReset := reqAfterReset.prepareH1H2Client()
	if closerAfterReset == nil {
		t.Fatalf("expected cloned transport after custom CA generation changed")
	}
	_ = closerAfterReset.Close()
}
