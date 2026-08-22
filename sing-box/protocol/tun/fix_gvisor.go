//go:build with_gvisor

package tun

import (
	"context"
	"log"
	"net/netip"
	"reflect"
	"time"
	"unsafe"

	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	tun "github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
)

// gVisor mirrors the private layout of tun.GVisor from sing-tun so that
// fixGvisorClose can reach the unexported stack/endpoint fields.
// The layout is verified at init time against the actual sing-tun type;
// if the dependency ever changes its structure, the hook is disabled and
// the upstream Close is used as-is (safe degradation, no unsafe access).
//
// NOTE: this mirror tracks sing-tun v0.8.x (13 fields, incl. inet4Address/
// inet6Address/icmpTimeout). Detected mismatch disables the hook automatically.
type gVisor struct {
	ctx                  context.Context
	tun                  tun.GVisorTun
	inet4Address         netip.Addr
	inet6Address         netip.Addr
	inet4LoopbackAddress []netip.Addr
	inet6LoopbackAddress []netip.Addr
	udpTimeout           time.Duration
	icmpTimeout          time.Duration
	broadcastAddr        netip.Addr
	handler              tun.Handler
	logger               logger.Logger
	stack                *stack.Stack
	endpoint             stack.LinkEndpoint
}

func gvisorLayoutMatches() bool {
	want := []string{
		"ctx", "tun", "inet4Address", "inet6Address",
		"inet4LoopbackAddress", "inet6LoopbackAddress",
		"udpTimeout", "icmpTimeout", "broadcastAddr", "handler", "logger",
		"stack", "endpoint",
	}
	got := reflect.TypeOf(tun.GVisor{})
	if got.NumField() != len(want) {
		return false
	}
	for i, name := range want {
		if got.Field(i).Name != name {
			return false
		}
	}
	return true
}

func gvisorHookEnabled() bool {
	return gvisorLayoutMatches()
}

func init() {
	if !gvisorLayoutMatches() {
		log.Println("sing-tun GVisor layout mismatch: gvisor close hook disabled")
	}
}

func (t *gVisor) Close() error {
	if t.stack == nil {
		return nil
	}
	t.endpoint.(*tun.LinkEndpointFilter).LinkEndpoint.Attach(nil)
	t.stack.Close()
	for _, endpoint := range t.stack.CleanupEndpoints() {
		endpoint.Abort()
	}
	return nil
}

func (t *Inbound) fixGvisorClose() {
	// 正确的修复方式：修改 sing-tun 里面的 func (w *LinkEndpointFilter) Attach
	if !gvisorHookEnabled() {
		return
	}
	if gvs, ok := t.tunStack.(*tun.GVisor); ok {
		p := (*gVisor)(unsafe.Pointer(gvs))
		p.Close()
		p.stack = nil // prevent next Close
	}
}
