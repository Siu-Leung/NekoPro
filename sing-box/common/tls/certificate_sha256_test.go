package tls

import (
	"context"
	"crypto/sha256"
	stdtls "crypto/tls"
	"crypto/x509"
	"encoding/pem"
	"net"
	"testing"
	"time"

	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json"
	"github.com/sagernet/sing/common/logger"

	"github.com/stretchr/testify/require"
)

func TestClientCertificateSHA256Go(t *testing.T) {
	testClientCertificateSHA256(t, nil)
}

func testClientCertificateSHA256(t *testing.T, utls *option.OutboundUTLSOptions) {
	t.Helper()
	certificate, err := GenerateKeyPair(nil, nil, nil, "localhost")
	require.NoError(t, err)
	leaf, err := x509.ParseCertificate(certificate.Certificate[0])
	require.NoError(t, err)
	leafHash := sha256.Sum256(leaf.Raw)
	publicKey, err := x509.MarshalPKIXPublicKey(leaf.PublicKey)
	require.NoError(t, err)
	publicKeyHash := sha256.Sum256(publicKey)

	// Send another certificate as well so that the test rejects non-leaf pins.
	other, err := GenerateKeyPair(nil, nil, nil, "other")
	require.NoError(t, err)
	certificate.Certificate = append(certificate.Certificate, other.Certificate[0])
	otherHash := sha256.Sum256(other.Certificate[0])
	serverConfig := &stdtls.Config{Certificates: []stdtls.Certificate{*certificate}}
	trustedCert := string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: leaf.Raw}))

	for _, test := range []struct {
		name      string
		apply     func(*option.OutboundTLSOptions)
		wantError string
	}{
		{name: "leaf pin replaces PKI", apply: func(o *option.OutboundTLSOptions) {
			o.CertificateSHA256 = [][]byte{leafHash[:]}
		}},
		{name: "second leaf pin matches without SNI", apply: func(o *option.OutboundTLSOptions) {
			o.CertificateSHA256 = [][]byte{otherHash[:], leafHash[:]}
			o.DisableSNI = true
		}},
		{name: "only leaf can match", apply: func(o *option.OutboundTLSOptions) {
			o.CertificateSHA256 = [][]byte{otherHash[:]}
		}, wantError: "unrecognized peer certificate"},
		{name: "insecure does not bypass DER pin", apply: func(o *option.OutboundTLSOptions) {
			o.CertificateSHA256 = [][]byte{publicKeyHash[:]}
			o.Insecure = true
		}, wantError: "unrecognized peer certificate"},
		{name: "existing SPKI pin", apply: func(o *option.OutboundTLSOptions) {
			o.CertificatePublicKeySHA256 = [][]byte{publicKeyHash[:]}
		}},
		{name: "existing SPKI pin rejects DER hash", apply: func(o *option.OutboundTLSOptions) {
			o.CertificatePublicKeySHA256 = [][]byte{leafHash[:]}
		}, wantError: "unrecognized remote public key"},
		{name: "no pin rejects untrusted leaf", wantError: "certificate signed by unknown authority"},
		{name: "no pin uses configured CA", apply: func(o *option.OutboundTLSOptions) {
			o.Certificate = []string{trustedCert}
		}},
	} {
		t.Run(test.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			options := option.OutboundTLSOptions{Enabled: true, ServerName: "localhost", UTLS: utls}
			if test.apply != nil {
				test.apply(&options)
			}
			config, err := NewClient(ctx, logger.NOP(), "", options)
			require.NoError(t, err)

			client, server := net.Pipe()
			defer client.Close()
			defer server.Close()
			serverDone := make(chan error, 1)
			go func() {
				defer server.Close()
				serverDone <- stdtls.Server(server, serverConfig).HandshakeContext(ctx)
			}()
			// Cloning and updating the name must not discard the pin callback.
			config = config.Clone()
			config.SetServerName("localhost")
			_, err = ClientHandshake(ctx, client, config)
			if test.wantError != "" {
				require.ErrorContains(t, err, test.wantError)
			} else {
				require.NoError(t, err)
				require.NoError(t, <-serverDone)
			}
		})
	}
}

func TestCertificateSHA256Configuration(t *testing.T) {
	ctx := context.Background()
	pin := sha256.Sum256([]byte("test pin"))
	options := option.OutboundTLSOptions{Enabled: true, ServerName: "localhost", CertificateSHA256: [][]byte{pin[:]}}
	for _, test := range []struct {
		name      string
		apply     func(*option.OutboundTLSOptions)
		wantError string
	}{
		{"short pin", func(o *option.OutboundTLSOptions) { o.CertificateSHA256 = [][]byte{pin[:31]} }, "invalid certificate_sha256 length"},
		{"empty pin", func(o *option.OutboundTLSOptions) { o.CertificateSHA256 = [][]byte{pin[:], nil} }, "invalid certificate_sha256 length"},
		{"inline CA", func(o *option.OutboundTLSOptions) { o.Certificate = []string{"test"} }, "conflicts with certificate or certificate_path"},
		{"CA path", func(o *option.OutboundTLSOptions) { o.CertificatePath = "not-read.pem" }, "conflicts with certificate or certificate_path"},
		{"SPKI", func(o *option.OutboundTLSOptions) { o.CertificatePublicKeySHA256 = [][]byte{pin[:]} }, "conflicts with certificate_public_key_sha256"},
		{"REALITY", func(o *option.OutboundTLSOptions) { o.Reality = &option.OutboundRealityOptions{Enabled: true} }, "unsupported in REALITY"},
		{"Apple", func(o *option.OutboundTLSOptions) { o.Engine = "apple" }, "unsupported in TLS engine"},
		{"Windows", func(o *option.OutboundTLSOptions) { o.Engine = "windows" }, "unsupported in TLS engine"},
	} {
		t.Run(test.name, func(t *testing.T) {
			modified := options
			test.apply(&modified)
			_, err := NewClient(ctx, logger.NOP(), "", modified)
			require.ErrorContains(t, err, test.wantError)
		})
	}
	_, err := ValidateSystemTLSOptions(ctx, options, "Apple HTTP engine")
	require.ErrorContains(t, err, "certificate_sha256 is unsupported in Apple HTTP engine")
	_, err = NewSTDClient(ctx, logger.NOP(), "", options)
	require.ErrorContains(t, err, "certificate_sha256 requires the standard TLS client dispatcher")

	verify, err := newCertificateSHA256Verifier(options)
	require.NoError(t, err)
	require.ErrorContains(t, verify(nil, nil), "missing peer certificate")
	require.ErrorContains(t, verify([][]byte{nil}, nil), "missing peer certificate")
}

func TestCertificateSHA256JSON(t *testing.T) {
	const digest = `"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="`
	for _, value := range []string{digest, "[" + digest + "]"} {
		options, err := json.UnmarshalExtended[option.OutboundTLSOptions]([]byte(`{"enabled":true,"certificate_sha256":` + value + `}`))
		require.NoError(t, err)
		require.Len(t, options.CertificateSHA256, 1)
		require.Len(t, options.CertificateSHA256[0], sha256.Size)
		require.Equal(t, byte(31), options.CertificateSHA256[0][31])
	}
}
