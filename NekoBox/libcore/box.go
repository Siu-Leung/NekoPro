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

func init() {
	dialer.DoNotSelectInterface = true
}

var (
	mainInstance       *BoxInstance
	mainInstanceAccess sync.RWMutex
)

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

	statsService        *v2rayapi.StatsService
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

	if b.state == 0 {
		b.state = 1
		return b.Box.Start()
	}
	return errors.New("already started")
}

func (b *BoxInstance) Close() (err error) {
	b.access.Lock()
	defer b.access.Unlock()

	defer device.DeferPanicToError("box.Close", func(err_ error) { err = err_ })

	// no double close
	if b.state == 2 {
		return nil
	}
	b.state = 2

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
	if b.statsService != nil {
		log.Println("duplicate call of SetV2rayStats")
		return
	}
	b.statsService = v2rayapi.NewStatsService(option.V2RayStatsServiceOptions{
		Enabled:   true,
		Outbounds: strings.Split(outbounds, "\n"),
	})
	if b.statsService != nil {
		b.Box.Router().AppendTracker(b.statsService)
	}
}

func (b *BoxInstance) QueryStats(tag, direct string) int64 {
	if b.statsService == nil {
		return 0
	}
	value, err := b.statsService.GetStats(context.Background(), &v2rayapi.GetStatsRequest{
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
		if i.statsService != nil {
			connectionTracker = i.statsService
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
	if instance.statsService != nil {
		connectionTracker = instance.statsService
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
	protectMu     sync.Mutex
	protectCloser io.Closer
)

func goServeProtect(start bool) {
	protectMu.Lock()
	defer protectMu.Unlock()
	if protectCloser != nil {
		_ = protectCloser.Close()
		protectCloser = nil
	}
	if start {
		closer, err := protect_server.ServeProtect("protect_path", false, 0, func(fd int) error {
			return intfBox.AutoDetectInterfaceControl(int32(fd))
		})
		if err != nil {
			log.Println("goServeProtect error:", err)
			return
		}
		protectCloser = closer
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
