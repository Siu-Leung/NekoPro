package tls

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"slices"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	E "github.com/sagernet/sing/common/exceptions"
)

var errCertificateSHA256Reality = E.New("certificate_sha256 is unsupported in REALITY")

func checkCertificateSHA256Support(options option.OutboundTLSOptions) error {
	if len(options.CertificateSHA256) == 0 {
		return nil
	}
	if options.Reality != nil && options.Reality.Enabled {
		return errCertificateSHA256Reality
	}
	if options.Engine != "" && options.Engine != C.TLSEngineGo {
		return E.New("certificate_sha256 is unsupported in TLS engine: ", options.Engine)
	}
	return nil
}

func newCertificateSHA256Verifier(options option.OutboundTLSOptions) (func([][]byte, [][]*x509.Certificate) error, error) {
	if len(options.CertificateSHA256) == 0 {
		return nil, nil
	}
	if err := checkCertificateSHA256Support(options); err != nil {
		return nil, err
	}
	if len(options.Certificate) > 0 || options.CertificatePath != "" {
		return nil, E.New("certificate_sha256 conflicts with certificate or certificate_path")
	}
	// Keep a stale or mistyped DER pin from being masked by a matching SPKI pin.
	if len(options.CertificatePublicKeySHA256) > 0 {
		return nil, E.New("certificate_sha256 conflicts with certificate_public_key_sha256")
	}
	pins := make([][sha256.Size]byte, len(options.CertificateSHA256))
	for i, pin := range options.CertificateSHA256 {
		if len(pin) != sha256.Size {
			return nil, E.New("invalid certificate_sha256 length: ", len(pin), " (want ", sha256.Size, " bytes)")
		}
		copy(pins[i][:], pin)
	}
	return func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		if len(rawCerts) == 0 || len(rawCerts[0]) == 0 {
			return E.New("missing peer certificate")
		}
		// Match the exact, raw DER of the leaf only, never its key or the rest of the chain.
		leafHash := sha256.Sum256(rawCerts[0])
		if slices.Contains(pins, leafHash) {
			return nil
		}
		return E.New("unrecognized peer certificate: certificate_sha256 ", base64.StdEncoding.EncodeToString(leafHash[:]))
	}, nil
}
