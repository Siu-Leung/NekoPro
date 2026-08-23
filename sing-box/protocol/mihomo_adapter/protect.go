package mihomo_adapter

import (
	"context"
	"sync"
	"syscall"

	"github.com/metacubex/mihomo/component/dialer"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
)

var (
	hookMu        sync.Mutex
	hookInstalled bool
)

// InstallProtectHook installs the Android socket protect hook into mihomo's
// global dialer. All mihomo-bridged protocols (snell, vless_xhttp, ...) must
// call this once so the hook is installed exactly once, regardless of how many
// bridged outbounds exist and in what order they are created.
//
// If the context does not carry an adapter.NetworkManager (e.g. non-Android
// environments), the hook is not installed and the next caller may retry.
func InstallProtectHook(ctx context.Context) {
	hookMu.Lock()
	defer hookMu.Unlock()
	if hookInstalled {
		return
	}
	networkManager := service.FromContext[adapter.NetworkManager](ctx)
	if networkManager == nil {
		return
	}
	protectFunc := networkManager.ProtectFunc()
	if protectFunc == nil {
		return
	}
	previousHook := dialer.DefaultSocketHook
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		if previousHook != nil {
			if err := previousHook(network, address, conn); err != nil {
				return err
			}
		}
		return protectFunc(network, address, conn)
	}
	hookInstalled = true
}
