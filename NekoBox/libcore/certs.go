package libcore

import (
	"crypto/x509"
	"log"
	_ "unsafe" // for go:linkname
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

func updateRootCACerts(pem []byte) {
	_ = UpdateRootCACerts(pem)
}

// UpdateRootCACerts appends PEM-encoded certificates onto the system root pool,
// or resets back to the unmodified system root pool when pem is empty.
func UpdateRootCACerts(pem []byte) bool {
	roots, err := x509.SystemCertPool()
	if err != nil || roots == nil {
		roots = x509.NewCertPool()
	}
	if len(pem) == 0 {
		systemRoots = roots
		log.Println("system root CAs restored")
		return true
	}
	if !roots.AppendCertsFromPEM(pem) {
		log.Println("failed to append certificates from pem")
		return false
	}
	systemRoots = roots
	log.Println("internal ca.pem was loaded")
	return true
}
