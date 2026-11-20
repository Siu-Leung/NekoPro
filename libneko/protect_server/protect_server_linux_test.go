//go:build linux

package protect_server

import (
	"net"
	"os"
	"path/filepath"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

func countOpenFds(t *testing.T) int {
	t.Helper()
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Fatalf("read /proc/self/fd: %v", err)
	}
	return len(entries)
}

func sendFds(t *testing.T, sockPath string, payload []byte, fds ...int) byte {
	t.Helper()
	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: sockPath, Net: "unix"})
	if err != nil {
		t.Fatalf("dial unix: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))

	var oob []byte
	if len(fds) > 0 {
		oob = syscall.UnixRights(fds...)
	}
	if _, _, err := conn.WriteMsgUnix(payload, oob, nil); err != nil {
		t.Fatalf("WriteMsgUnix: %v", err)
	}
	var reply [1]byte
	n, err := conn.Read(reply[:])
	if err != nil {
		t.Fatalf("read reply: %v", err)
	}
	if n != 1 {
		t.Fatalf("reply bytes = %d, want 1", n)
	}
	return reply[0]
}

func TestServeProtectPermissionsAndSingleFd(t *testing.T) {
	sockPath := filepath.Join(t.TempDir(), "protect_path")
	var calls atomic.Int32
	closer, err := ServeProtect(sockPath, false, 0, func(fd int) error {
		calls.Add(1)
		if fd < 0 {
			t.Errorf("received invalid fd %d", fd)
		}
		return nil
	})
	if err != nil {
		t.Fatalf("ServeProtect: %v", err)
	}
	defer closer.Close()

	fi, err := os.Stat(sockPath)
	if err != nil {
		t.Fatalf("stat socket: %v", err)
	}
	if perm := fi.Mode().Perm(); perm != 0600 {
		t.Fatalf("socket mode = %#o, want 0600", perm)
	}

	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	defer w.Close()

	// Test both 1-byte payload and empty payload (as used by sendFdToProtect).
	if reply := sendFds(t, sockPath, []byte{0}, int(r.Fd())); reply != 1 {
		t.Fatalf("reply (1-byte payload) = %d, want 1", reply)
	}
	if reply := sendFds(t, sockPath, nil, int(r.Fd())); reply != 1 {
		t.Fatalf("reply (nil payload) = %d, want 1", reply)
	}
	if got := calls.Load(); got != 2 {
		t.Fatalf("protectCtl calls = %d, want 2", got)
	}
}

func TestServeProtectPropagatesCtlError(t *testing.T) {
	sockPath := filepath.Join(t.TempDir(), "protect_path")
	closer, err := ServeProtect(sockPath, false, 0, func(fd int) error {
		return os.ErrPermission
	})
	if err != nil {
		t.Fatalf("ServeProtect: %v", err)
	}
	defer closer.Close()

	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	defer w.Close()

	if reply := sendFds(t, sockPath, []byte{0}, int(r.Fd())); reply != 0 {
		t.Fatalf("expected reply 0 when protectCtl fails, got %d", reply)
	}
}

func TestServeProtectRejectsMultipleFdsWithoutLeaking(t *testing.T) {
	sockPath := filepath.Join(t.TempDir(), "protect_path")
	var calls atomic.Int32
	closer, err := ServeProtect(sockPath, false, 0, func(fd int) error {
		calls.Add(1)
		return nil
	})
	if err != nil {
		t.Fatalf("ServeProtect: %v", err)
	}
	defer closer.Close()

	r1, w1, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r1.Close()
	defer w1.Close()

	r2, w2, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r2.Close()
	defer w2.Close()

	// Warm up one cycle so server goroutines/listener settle.
	if reply := sendFds(t, sockPath, []byte{0}, int(r1.Fd()), int(r2.Fd())); reply != 0 {
		t.Fatalf("expected multi-fd rejection (0), got %d", reply)
	}
	time.Sleep(20 * time.Millisecond)
	baseFds := countOpenFds(t)

	for i := 0; i < 10; i++ {
		if reply := sendFds(t, sockPath, []byte{0}, int(r1.Fd()), int(r2.Fd())); reply != 0 {
			t.Fatalf("iteration %d: expected multi-fd rejection (0), got %d", i, reply)
		}
	}
	time.Sleep(50 * time.Millisecond)

	if got := calls.Load(); got != 0 {
		t.Fatalf("protectCtl should not be invoked for multi-fd messages, got %d calls", got)
	}
	afterFds := countOpenFds(t)
	if afterFds > baseFds+1 {
		t.Fatalf("possible fd leak after rejected multi-fd messages: before=%d after=%d", baseFds, afterFds)
	}
}

func TestServeProtectRejectsNoRights(t *testing.T) {
	sockPath := filepath.Join(t.TempDir(), "protect_path")
	var calls atomic.Int32
	closer, err := ServeProtect(sockPath, false, 0, func(fd int) error {
		calls.Add(1)
		return nil
	})
	if err != nil {
		t.Fatalf("ServeProtect: %v", err)
	}
	defer closer.Close()

	if reply := sendFds(t, sockPath, []byte{0}); reply != 0 {
		t.Fatalf("expected 0 reply for message without SCM_RIGHTS, got %d", reply)
	}
	if calls.Load() != 0 {
		t.Fatalf("protectCtl should not be invoked when no fd was passed")
	}
}

func TestCheckPeerUID(t *testing.T) {
	uid := os.Getuid()
	if err := checkPeerUID(uint32(uid), uid); err != nil {
		t.Fatalf("expected matching UID %d to pass, got %v", uid, err)
	}
	if err := checkPeerUID(uint32(uid+1), uid); err == nil {
		t.Fatalf("expected mismatched UID to fail")
	}
	if err := checkPeerUID(0, -1); err == nil {
		t.Fatalf("expected negative expectedUID to fail")
	}
}
