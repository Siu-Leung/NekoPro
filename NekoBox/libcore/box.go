package libcore

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"log"
	"net"
	"net/http"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/matsuridayo/libneko/protect_server"
	"github.com/matsuridayo/libneko/speedtest"
	mihomoConstant "github.com/metacubex/mihomo/constant"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/experimental/v2rayapi"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-box/protocol/mihomo_adapter"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/service"
	"github.com/sagernet/sing/service/pause"
)

const (
	boxStateIdle    = 0
	boxStateStarted = 1
	boxStateClosed  = 2

	protectSocketPath = "protect_path"
)

func init() {
	dialer.DoNotSelectInterface = true
}

var (
	mainInstance       *BoxInstance
	mainInstanceAccess sync.RWMutex
)

func hasActiveMainInstance() bool {
	mainInstanceAccess.RLock()
	active := mainInstance != nil
	mainInstanceAccess.RUnlock()
	return active
}

func VersionBox() string {
	version := []string{
		"sing-box: " + constant.Version,
		runtime.Version() + "@" + runtime.GOOS + "/" + runtime.GOARCH,
	}

	var tags string
	debugInfo, loaded := debug.ReadBuildInfo()
	if loaded {
		for _, setting := range debugInfo.Settings {
			switch setting.Key {
			case "-tags":
				tags = setting.Value
			}
		}
	}

	if tags != "" {
		version = append(version, tags)
	}

	return strings.Join(version, "\n")
}

func MihomoVersion() string {
	return mihomoConstant.Version
}

func ResetAllConnections(system bool) {
	if system {
		mainInstanceAccess.RLock()
		instance := mainInstance
		mainInstanceAccess.RUnlock()
		if instance != nil {
			instance.access.Lock()
			defer instance.access.Unlock()
			if instance.state != boxStateStarted || instance.Box == nil {
				log.Println("Reset all connections skipped (instance not running)")
				return
			}
			instance.Box.Network().ResetNetwork(context.Background())
			log.Println("Reset all connections done")
		} else {
			log.Println("Reset all connections skipped (no instance)")
		}
	} else {
		log.Println("TODO: Reset user connections")
	}
}

type BoxInstance struct {
	access sync.Mutex

	*box.Box
	cancel context.CancelFunc
	state  int

	statsService        atomic.Pointer[v2rayapi.StatsService]
	selector            *group.Selector
	pauseManager        pause.Manager
	dnsRouter           adapter.DNSRouter
	dnsTransportManager adapter.DNSTransportManager
}

func NewSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewSingBoxInstance", func(err_ error) { err = err_ })

	// create box context
	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)

	// parse options
	options, err := decodeLegacyDNSConfig(ctx, config)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}
	injectCustomRootCA(&options)

	// create box
	instance, err := box.New(box.Options{
		Options:           options,
		Context:           ctx,
		PlatformLogWriter: boxPlatformLogWriter,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:                 instance,
		cancel:              cancel,
		pauseManager:        service.FromContext[pause.Manager](ctx),
		dnsRouter:           service.FromContext[adapter.DNSRouter](ctx),
		dnsTransportManager: service.FromContext[adapter.DNSTransportManager](ctx),
	}

	// selector
	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

// NewTestSingBoxInstance 供 URL 测速与 SpeedTest 实例使用：不注册全局 PlatformLogWriter
func NewTestSingBoxInstance(config string, localTransport LocalDNSTransport) (b *BoxInstance, err error) {
	defer device.DeferPanicToError("NewTestSingBoxInstance", func(err_ error) { err = err_ })

	ctx, cancel := context.WithCancel(context.Background())
	ctx = box.Context(ctx,
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(localTransport), nekoboxAndroidServiceRegistry(),
		nekoboxAndroidCertificateProviderRegistry(),
	)
	ctx = service.ContextWithDefaultRegistry(ctx)
	service.MustRegister[adapter.PlatformInterface](ctx, boxPlatformInterfaceInstance)
	// never let a temporary test instance take over the process-global
	// mihomo protect/DNS hooks from the main instance
	ctx = mihomo_adapter.ContextWithTestInstance(ctx)

	options, err := decodeLegacyDNSConfig(ctx, config)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("decode config: %v", err)
	}
	injectCustomRootCA(&options)

	instance, err := box.New(box.Options{
		Options: options,
		Context: ctx,
	})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("create service: %v", err)
	}

	b = &BoxInstance{
		Box:                 instance,
		cancel:              cancel,
		pauseManager:        service.FromContext[pause.Manager](ctx),
		dnsRouter:           service.FromContext[adapter.DNSRouter](ctx),
		dnsTransportManager: service.FromContext[adapter.DNSTransportManager](ctx),
	}

	if proxy, ok := b.Outbound().Outbound("proxy"); ok {
		if selector, ok := proxy.(*group.Selector); ok {
			b.selector = selector
		}
	}

	return b, nil
}

func (b *BoxInstance) Start() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Start", func(err_ error) { err = err_ })

	if b.state == boxStateIdle {
		b.state = boxStateStarted
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == boxStateClosed {
		return nil
	}
	b.state = boxStateClosed

	// clear main instance
	mainInstanceAccess.Lock()
	if mainInstance == b {
		mainInstance = nil
		goServeProtect(false)
	}
	mainInstanceAccess.Unlock()

	// close box
	if b.cancel != nil {
		b.cancel()
	}
	if b.Box != nil {
		b.Box.Close()
	}

	return nil
}

func (b *BoxInstance) Sleep() {
	if b.pauseManager != nil {
		b.pauseManager.DevicePause()
	}
	// _ = b.Box.Router().ResetNetwork()
}

func (b *BoxInstance) Wake() {
	if b.pauseManager != nil {
		b.pauseManager.DeviceWake()
	}
}

func (b *BoxInstance) SetAsMain() {
	mainInstanceAccess.Lock()
	mainInstance = b
	mainInstanceAccess.Unlock()
	goServeProtect(true)
}

func (b *BoxInstance) SetV2rayStats(outbounds string) {
	b.access.Lock()
	defer b.access.Unlock()
	if b.state != boxStateIdle {
		// WHY: Router.AppendTracker mutates Router.trackers without synchronization;
		// calling SetV2rayStats after Start() races with active routed connections.
		log.Println("WARN: SetV2rayStats ignored: SetV2rayStats must be called before Start")
		return
	}
	if b.statsService.Load() != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	svc := v2rayapi.NewStatsService(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	if svc != nil {
		b.statsService.Store(svc)
		b.Box.Router().AppendTracker(svc)
	}
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	svc := b.statsService.Load()
	if svc == nil {
		return 0
	}
	value, err := svc.GetStats(context.Background(), &v2rayapi.GetStatsRequest{
		Name:   fmt.Sprintf("outbound>>>%s>>>traffic>>>%s", tag, direct),
		Reset_: true,
	})
	if err != nil {
		return 0
	}
	return value.Stat.Value
}

func (b *BoxInstance) SelectOutbound(tag string) bool {
	if b.selector != nil {
		return b.selector.SelectOutbound(tag)
	}
	return false
}

func UrlTest(i *BoxInstance, link string, timeout int32) (latency int32, err error) {
	defer device.DeferPanicToError("box.UrlTest", func(err_ error) { err = err_ })
	var connectionTracker adapter.ConnectionTracker
	timeoutDur := time.Duration(timeout) * time.Millisecond
	if timeoutDur <= 0 {
		timeoutDur = 5 * time.Second
	}
	// test i
	if i != nil {
		if svc := i.statsService.Load(); svc != nil {
			connectionTracker = svc
		}
		client := createProxyHttpClient(i.Box, connectionTracker, timeoutDur)
		defer client.CloseIdleConnections()
		return speedtest.UrlTest(client, link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test direct
	mainInstanceAccess.RLock()
	instance := mainInstance
	mainInstanceAccess.RUnlock()
	if instance == nil {
		client := createProxyHttpClient(nil, nil, timeoutDur)
		defer client.CloseIdleConnections()
		return speedtest.UrlTest(client, link, timeout, speedtest.UrlTestStandard_RTT)
	}
	// test mainInstance
	if svc := instance.statsService.Load(); svc != nil {
		connectionTracker = svc
	}
	client := createProxyHttpClient(instance.Box, connectionTracker, timeoutDur)
	defer client.CloseIdleConnections()
	return speedtest.UrlTest(client, link, timeout, speedtest.UrlTestStandard_RTT)
}

func createProxyHttpClient(instance *box.Box, tracker adapter.ConnectionTracker, timeout time.Duration) *http.Client {
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			if instance == nil {
				var d net.Dialer
				return d.DialContext(ctx, network, addr)
			}
			destination := M.ParseSocksaddr(addr)
			if proxyOut, ok := instance.Outbound().Outbound("proxy"); ok {
				return proxyOut.DialContext(ctx, network, destination)
			}
			if proxyEndpoint, ok := instance.Endpoint().Get("proxy"); ok {
				return proxyEndpoint.DialContext(ctx, network, destination)
			}
			detour := dialer.NewDetour(instance.Outbound(), instance.Outbound().Default().Tag(), true)
			return detour.DialContext(ctx, network, destination)
		},
		TLSHandshakeTimeout:   timeout,
		ResponseHeaderTimeout: timeout,
		DisableKeepAlives:     false,
		MaxIdleConns:          2,
		IdleConnTimeout:       timeout,
	}
	if roots := currentRootCAs(); roots != nil {
		transport.TLSClientConfig = &tls.Config{RootCAs: roots}
	}
	client := &http.Client{
		Transport: transport,
	}
	_ = tracker
	return client
}

var (
	protectMu           sync.Mutex
	protectCloser       io.Closer
	protectServerPinned bool
)

// StartProtectServer starts the Unix-domain socket protect listener (if not
// already active) and pins it across BoxInstance.Close() reloads so UI test
// sockets never see a missing protect_path mid-switch while VPN stays up.
func StartProtectServer() {
	protectMu.Lock()
	defer protectMu.Unlock()
	protectServerPinned = true
	ensureProtectServerLocked()
}

// StopProtectServer unpins and closes the Unix-domain socket protect listener
// when the Android VpnService tears down.
func StopProtectServer() {
	protectMu.Lock()
	defer protectMu.Unlock()
	protectServerPinned = false
	stopProtectServerLocked()
}

func ensureProtectServerLocked() {
	if protectCloser != nil {
		return
	}
	closer, err := protect_server.ServeProtect(protectSocketPath, false, 0, func(fd int) error {
		return intfBox.AutoDetectInterfaceControl(int32(fd))
	})
	if err != nil {
		log.Println("goServeProtect error:", err)
		return
	}
	protectCloser = closer
}

func stopProtectServerLocked() {
	if protectCloser != nil {
		_ = protectCloser.Close()
		protectCloser = nil
	}
}

func goServeProtect(start bool) {
	protectMu.Lock()
	defer protectMu.Unlock()
	if start {
		ensureProtectServerLocked()
		return
	}
	if !protectServerPinned {
		stopProtectServerLocked()
	}
}

func injectCustomRootCA(options *option.Options) {
	pemStr := currentCustomCAPEM()
	if pemStr == "" || options == nil {
		return
	}
	if options.Certificate != nil && options.Certificate.Store == constant.CertificateStoreNone {
		return
	}
	if options.Certificate == nil {
		options.Certificate = &option.CertificateOptions{
			Store: constant.CertificateStoreSystem,
		}
	}
	options.Certificate.Certificate = append(options.Certificate.Certificate, pemStr)
}
