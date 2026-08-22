//go:build !with_gvisor

package tun

func (t *Inbound) fixGvisorClose() {
}
