package libcore

import (
	"bytes"
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"

	"github.com/Mahdi-zarei/speedtest-go/speedtest"
	mDNS "github.com/miekg/dns"
	"github.com/sagernet/sing-box/option"
	"github.com/ulikunitz/xz"
)

func TestResolveStrictProxyDialerUnwrapsSelectorAndRejectsDirectLeaf(t *testing.T) {
	selectorToDirectCfg := `{
		"log":{"disabled":true},
		"outbounds":[
			{"type":"selector","tag":"proxy","outbounds":["direct","node-a"],"default":"direct"},
			{"type":"socks","tag":"node-a","server":"127.0.0.1","server_port":1080},
			{"type":"direct","tag":"direct"}
		]
	}`
	inst, err := NewTestSingBoxInstance(selectorToDirectCfg, nil)
	if err != nil {
		t.Fatalf("NewTestSingBoxInstance: %v", err)
	}
	defer inst.Close()
	if err := inst.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}

	if _, err := resolveStrictProxyDialer(inst.Box); err == nil {
		t.Fatal("expected selector pointing to direct leaf to be rejected")
	}

	if !inst.SelectOutbound("node-a") {
		t.Fatal("SelectOutbound(node-a) returned false")
	}
	leaf, err := resolveStrictProxyDialer(inst.Box)
	if err != nil {
		t.Fatalf("expected selector pointing to socks leaf to succeed, got %v", err)
	}
	if leaf.Tag() != "node-a" {
		t.Fatalf("resolved leaf tag = %q, want node-a", leaf.Tag())
	}
}

func TestSetV2rayStatsIgnoredAfterStartAndResetAllConnectionsGuardsState(t *testing.T) {
	cfg := `{
		"log":{"disabled":true},
		"outbounds":[{"type":"direct","tag":"direct"}]
	}`
	inst, err := NewTestSingBoxInstance(cfg, nil)
	if err != nil {
		t.Fatalf("NewTestSingBoxInstance: %v", err)
	}
	defer inst.Close()

	// Unstarted mainInstance with nil/idle state must not panic on ResetAllConnections.
	mainInstanceAccess.Lock()
	prevMain := mainInstance
	mainInstance = &BoxInstance{state: boxStateIdle}
	mainInstanceAccess.Unlock()
	t.Cleanup(func() {
		mainInstanceAccess.Lock()
		mainInstance = prevMain
		mainInstanceAccess.Unlock()
	})
	ResetAllConnections(true)

	if err := inst.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	// Calling SetV2rayStats after Start() must be ignored safely without mutating Router.trackers.
	inst.SetV2rayStats("direct")
	if inst.statsService.Load() != nil {
		t.Fatal("expected SetV2rayStats after Start() to leave statsService nil")
	}
	if got := inst.QueryStats("direct", "uplink"); got != 0 {
		t.Fatalf("QueryStats = %d, want 0", got)
	}
}

func TestPinnedProtectServerSurvivesBoxClose(t *testing.T) {
	origDir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	tmpDir := t.TempDir()
	if err := os.Chdir(tmpDir); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		StopProtectServer()
		_ = os.Chdir(origDir)
	})

	StartProtectServer()
	if _, err := os.Stat(filepath.Join(tmpDir, protectSocketPath)); err != nil {
		t.Fatalf("expected protect_path socket to exist after StartProtectServer: %v", err)
	}

	b := &BoxInstance{}
	b.SetAsMain()
	_ = b.Close()

	protectMu.Lock()
	stillRunning := protectCloser != nil && protectServerPinned
	protectMu.Unlock()
	if !stillRunning {
		t.Fatal("expected pinned protect server to stay alive across BoxInstance.Close()")
	}

	StopProtectServer()
	protectMu.Lock()
	stopped := protectCloser == nil && !protectServerPinned
	protectMu.Unlock()
	if !stopped {
		t.Fatal("expected StopProtectServer to unpin and close protect server")
	}
}

func TestSendFdToProtectAndAutoDetectInterfaceControlFailClosed(t *testing.T) {
	prevBg := isBgProcess
	isBgProcess = false
	t.Cleanup(func() { isBgProcess = prevBg })

	origDir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	tmpDir := t.TempDir()
	if err := os.Chdir(tmpDir); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chdir(origDir) })

	wrapper := &boxPlatformInterfaceWrapper{}
	sockPair, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer syscall.Close(sockPair[0])
	defer syscall.Close(sockPair[1])

	// Missing protect_path (ENOENT) must be ignored when VPN is off.
	if err := wrapper.AutoDetectInterfaceControl(sockPair[0]); err != nil {
		t.Fatalf("ENOENT should be ignored when VPN is inactive, got %v", err)
	}

	// Live protect_path returning 0 (rejected) must fail closed!
	ln, err := net.Listen("unix", protectSocketPath)
	if err != nil {
		t.Fatalf("Listen unix: %v", err)
	}
	defer ln.Close()
	go func() {
		conn, acceptErr := ln.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		buf := make([]byte, 16)
		_, _ = conn.Read(buf)
		_, _ = conn.Write([]byte{0})
	}()

	if err := wrapper.AutoDetectInterfaceControl(sockPair[0]); err == nil || !strings.Contains(err.Error(), "rejected") {
		t.Fatalf("expected protect rejection to propagate, got %v", err)
	}
}

func TestSpeedTestCandidateValidationAllowsOoklaHttpAndRejectsSSRF(t *testing.T) {
	// Ookla's https://www.speedtest.net/api/js/servers returns http://<host>:8080/speedtest/upload.php entries.
	if err := validateSpeedTestCandidateURL("http://speedtest.isp.example:8080/speedtest/upload.php", DefaultSpeedTestServerListURL); err != nil {
		t.Fatalf("expected standard Ookla http:8080 candidate to succeed, got %v", err)
	}
	if err := validateSpeedTestCandidateURL("https://speedtest.isp.example:8080/speedtest/upload.php", DefaultSpeedTestServerListURL); err != nil {
		t.Fatalf("expected valid public https candidate to succeed, got %v", err)
	}
	if err := validateSpeedTestCandidateURL("http://127.0.0.1:9090/speedtest/upload.php", DefaultSpeedTestServerListURL); !errors.Is(err, errUnsafeRedirect) {
		t.Fatalf("expected loopback SSRF candidate rejection, got %v", err)
	}
	if err := validateSpeedTestCandidateURL("https://192.168.1.1/speedtest/upload.php", DefaultSpeedTestServerListURL); !errors.Is(err, errUnsafeRedirect) {
		t.Fatalf("expected RFC1918 SSRF candidate rejection, got %v", err)
	}
	if err := validateSpeedTestCandidateURL("http://198.18.0.1:8080/speedtest/upload.php", DefaultSpeedTestServerListURL); !errors.Is(err, errUnsafeRedirect) {
		t.Fatalf("expected FakeIP SSRF candidate rejection, got %v", err)
	}
	if err := validateSpeedTestCandidateURL("ftp://speedtest.isp.example/upload.php", DefaultSpeedTestServerListURL); !errors.Is(err, errUnsafeRedirect) {
		t.Fatalf("expected non-HTTP scheme rejection, got %v", err)
	}

	_, _, err := selectLowestLatencyServer(
		context.Background(),
		nil,
		speedtest.Servers{{ID: "1", URL: "https://127.0.0.1:9090/speedtest/upload.php"}},
		DefaultSpeedTestServerListURL,
	)
	if !errors.Is(err, errUnsafeRedirect) {
		t.Fatalf("selectLowestLatencyServer err = %v, want errUnsafeRedirect", err)
	}
}

func TestUnxzAtomicPreservesExistingTargetOnCorruptArchive(t *testing.T) {
	dir := t.TempDir()
	dst := filepath.Join(dir, "geoip.db")
	if err := os.WriteFile(dst, []byte("existing-valid-db"), 0o644); err != nil {
		t.Fatal(err)
	}

	// Corrupt archive must leave existing dst intact and clean up temp file.
	corruptArchive := filepath.Join(dir, "corrupt.xz")
	var validHeader bytes.Buffer
	zw, err := xz.NewWriter(&validHeader)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = zw.Write([]byte("partial-payload"))
	_ = zw.Close()
	// Truncate archive mid-stream so xz.NewReader succeeds on header but io.Copy fails!
	truncated := validHeader.Bytes()[:len(validHeader.Bytes())-6]
	if err := os.WriteFile(corruptArchive, truncated, 0o644); err != nil {
		t.Fatal(err)
	}

	if err := Unxz(corruptArchive, dst); err == nil {
		t.Fatal("expected error for truncated xz archive")
	}
	got, err := os.ReadFile(dst)
	if err != nil || string(got) != "existing-valid-db" {
		t.Fatalf("existing file clobbered on failed Unxz: got %q, err %v", string(got), err)
	}
}

type zeroHandleRawLocal struct{}

func (zeroHandleRawLocal) Raw() bool                                     { return true }
func (zeroHandleRawLocal) NetworkHandle() int64                          { return 0 }
func (zeroHandleRawLocal) Lookup(*ExchangeContext, string, string) error { return nil }
func (zeroHandleRawLocal) Exchange(*ExchangeContext, []byte) error       { return nil }

func TestPlatformLocalDNSTransportBlocksZeroNetworkHandleWhenMainActive(t *testing.T) {
	prevBg := isBgProcess
	prevRawQuery := rawQueryFunc
	mainInstanceAccess.Lock()
	prevMain := mainInstance
	mainInstance = &BoxInstance{state: boxStateStarted}
	mainInstanceAccess.Unlock()

	called := false
	isBgProcess = true
	rawQueryFunc = func(ctx context.Context, networkHandle int64, request []byte) ([]byte, error) {
		called = true
		return nil, nil
	}
	t.Cleanup(func() {
		isBgProcess = prevBg
		rawQueryFunc = prevRawQuery
		mainInstanceAccess.Lock()
		mainInstance = prevMain
		mainInstanceAccess.Unlock()
	})

	tr := newPlatformTransport(zeroHandleRawLocal{}, "dns-local", option.LocalDNSServerOptions{})
	query := new(mDNS.Msg).SetQuestion("example.com.", mDNS.TypeA)
	_, err := tr.Exchange(context.Background(), query)
	if !errors.Is(err, syscall.ENETUNREACH) {
		t.Fatalf("Exchange err = %v, want ENETUNREACH", err)
	}
	if called {
		t.Fatal("rawQueryFunc must not be called with networkHandle=0 when VPN mainInstance is active")
	}
}
