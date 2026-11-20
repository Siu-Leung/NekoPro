package libcore

import (
	"crypto/x509"
	"log"
	"sync"

	mihomoCA "github.com/metacubex/mihomo/component/ca"
)

var (
	customCertMu        sync.RWMutex
	baseSystemRootsOnce sync.Once
	baseSystemRoots     *x509.CertPool
	customRootPool      *x509.CertPool
	customCertPEM       string
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

func currentRootCAs() *x509.CertPool {
	customCertMu.RLock()
	pool := customRootPool
	customCertMu.RUnlock()
	if pool != nil {
		return pool
	}
	return nil
}

func currentCustomCAPEM() string {
	customCertMu.RLock()
	pem := customCertPEM
	customCertMu.RUnlock()
	return pem
}

func updateRootCACerts(pem []byte) {
	_ = UpdateRootCACerts(pem)
}

// UpdateRootCACerts appends PEM-encoded certificates onto a fresh clone of the
// unmodified system root pool (without mutating crypto/x509 internals via
// go:linkname), or resets back to the unmodified system root pool when pem is empty.
func UpdateRootCACerts(pem []byte) bool {
	customCertMu.Lock()
	defer customCertMu.Unlock()

	roots := cloneBaseSystemRoots()
	if len(pem) == 0 {
		customRootPool = nil
		customCertPEM = ""
		mihomoCA.ResetCertificate()
		log.Println("system root CAs restored")
		return true
	}
	if !roots.AppendCertsFromPEM(pem) {
		log.Println("failed to append certificates from pem")
		return false
	}
	customRootPool = roots
	customCertPEM = string(pem)
	mihomoCA.ResetCertificate()
	_ = mihomoCA.AddCertificate(customCertPEM)
	log.Println("internal ca.pem was loaded")
	return true
}
