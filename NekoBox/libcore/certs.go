package libcore

import (
	"crypto/x509"
	"log"
	"sync"
	_ "unsafe" // for go:linkname
)

//go:linkname systemRoots crypto/x509.systemRoots
var systemRoots *x509.CertPool

var (
	systemRootsUpdateMu sync.Mutex
	baseSystemRootsOnce sync.Once
	baseSystemRoots     *x509.CertPool
)

func cloneBaseSystemRoots() *x509.CertPool {
	baseSystemRootsOnce.Do(func() {
		roots, err := x509.SystemCertPool()
		if err != nil || roots == nil {
			baseSystemRoots = x509.NewCertPool()
			return
		}
		baseSystemRoots = roots.Clone()
	})
	if baseSystemRoots == nil {
		return x509.NewCertPool()
	}
	return baseSystemRoots.Clone()
}

func updateRootCACerts(pem []byte) {
	_ = UpdateRootCACerts(pem)
}

// UpdateRootCACerts appends PEM-encoded certificates onto a fresh clone of the
// unmodified system root pool, or resets back to the unmodified system root
// pool when pem is empty.
func UpdateRootCACerts(pem []byte) bool {
	systemRootsUpdateMu.Lock()
	defer systemRootsUpdateMu.Unlock()

	roots := cloneBaseSystemRoots()
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
