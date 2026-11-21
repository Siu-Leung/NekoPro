package libcore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"libcore/ech"
	"log"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var (
	errFailConnectSocks5 = errors.New("fail connect socks5")
	errUnsafeRedirect    = errors.New("http redirect blocked by security policy")
	errBodyReadTimeout   = errors.New("http response body read timed out")
)

const (
	// DefaultHTTPResponseMaxSize is the hard cap used by the legacy gomobile
	// methods. Callers that need a different budget can use the *WithLimit
	// methods without changing the existing API.
	DefaultHTTPResponseMaxSize int64 = 64 << 20

	// maxErrorBodyBytes limits how much of a non-200 response body is read
	// solely to build a diagnostic error snippet.
	maxErrorBodyBytes int64 = 4 << 10

	// errorSnippetReadTimeout caps error response body reads so a slow non-200
	// endpoint cannot stall error reporting.
	errorSnippetReadTimeout = 5 * time.Second

	// maxRedirectHops matches Go's standard limit while enforcing security checks.
	maxRedirectHops = 10

	// defaultBodyReadTimeout bounds in-memory response body reads against
	// slow-drip tarpit servers after response headers arrive.
	defaultBodyReadTimeout = 30 * time.Second

	// defaultStreamReadTimeout bounds streaming file downloads (e.g. GeoIP/GeoSite)
	// so broken connections cannot stall indefinitely.
	defaultStreamReadTimeout = 10 * time.Minute
)

// validateRedirectPolicy enforces HTTP redirect safety for libcore clients.
//
// Parameters:
//   - req: The candidate redirected *http.Request.
//   - via: Previously executed requests in the redirect chain (oldest first).
//
// Returns nil when the redirect is permitted, or an error wrapping
// errUnsafeRedirect (or the hop-limit error) when blocked.
//
// Policy:
//  1. Caps redirects at maxRedirectHops.
//  2. Allows only http/https schemes.
//  3. Blocks HTTPS-to-HTTP protocol downgrades.
//  4. Blocks redirects to loopback, private, link-local, CGNAT, FakeIP, or
//     reserved hosts unless the redirect remains on the exact initial Host
//     explicitly requested by the caller (e.g. self-hosted LAN subscriptions).
func validateRedirectPolicy(req *http.Request, via []*http.Request) error {
	if len(via) >= maxRedirectHops {
		return errors.New("stopped after 10 redirects")
	}
	if req == nil || req.URL == nil {
		return errUnsafeRedirect
	}
	scheme := strings.ToLower(req.URL.Scheme)
	if scheme != "http" && scheme != "https" {
		return fmt.Errorf("%w: unsupported scheme %q", errUnsafeRedirect, scheme)
	}
	if len(via) > 0 && via[len(via)-1].URL != nil {
		// Prevent TLS downgrade from HTTPS to cleartext HTTP (protects credentials
		// and prevents silent MITM downgrade on subscriptions and downloads).
		if strings.EqualFold(via[len(via)-1].URL.Scheme, "https") && scheme == "http" {
			return fmt.Errorf("%w: https to http downgrade", errUnsafeRedirect)
		}
	}
	targetHost := strings.ToLower(strings.TrimSuffix(req.URL.Hostname(), "."))
	if isForbiddenRedirectHost(targetHost) {
		sameInitialAuthority := len(via) > 0 && via[0].URL != nil &&
			strings.EqualFold(via[0].URL.Host, req.URL.Host)
		if !sameInitialAuthority {
			return fmt.Errorf("%w: private or local redirect target", errUnsafeRedirect)
		}
	}
	return nil
}

// isForbiddenRedirectHost checks whether a redirect target host is a local
// hostname or non-public IP literal (loopback, RFC1918, link-local, CGNAT,
// FakeIP 198.18.0.0/15, IPv6 ULA, unspecified, multicast, or reserved).
func isForbiddenRedirectHost(host string) bool {
	if host == "" || host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.HasSuffix(host, ".local") {
		return true
	}
	addr, err := netip.ParseAddr(host)
	if err != nil {
		return false
	}
	addr = addr.Unmap()
	if addr.IsLoopback() || addr.IsPrivate() || addr.IsLinkLocalUnicast() ||
		addr.IsLinkLocalMulticast() || addr.IsMulticast() || addr.IsUnspecified() {
		return true
	}
	if addr.Is4() {
		b := addr.As4()
		// Block 0.0.0.0/8, CGNAT 100.64.0.0/10, FakeIP/benchmark 198.18.0.0/15, and reserved >= 240/4.
		if b[0] == 0 || b[0] >= 240 {
			return true
		}
		if b[0] == 100 && (b[1]&0xC0) == 0x40 {
			return true
		}
		if b[0] == 198 && (b[1] == 18 || b[1] == 19) {
			return true
		}
	}
	return false
}

// h1TransportCloser adapts *http.Transport (which only has
// CloseIdleConnections) to io.Closer for unified transport cleanup.
type h1TransportCloser struct {
	*http.Transport
}

func (c h1TransportCloser) Close() error {
	c.Transport.CloseIdleConnections()
	return nil
}

var ErrHTTPResponseTooLarge = errors.New("http response body exceeds maximum size")

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32)
	ForceSocks5(port int32)
	TryH3Direct()
	KeepAlive()
	NewRequest() HTTPRequest
	Close()
}

type HTTPRequest interface {
	SetURL(link string) error
	SetMethod(method string)
	SetHeader(key string, value string)
	SetContent(content []byte)
	SetContentString(content string)
	SetUserAgent(userAgent string)
	AllowInsecure()
	Execute() (HTTPResponse, error)
}

type HTTPResponse interface {
	GetHeader(string) *StringBox
	GetContent() ([]byte, error)
	GetContentWithLimit(maxBytes int64) ([]byte, error)
	GetContentString() (*StringBox, error)
	WriteTo(path string) error
	WriteToWithLimit(path string, maxBytes int64) error
}

var (
	_ HTTPClient   = (*httpClient)(nil)
	_ HTTPRequest  = (*httpRequest)(nil)
	_ HTTPResponse = (*httpResponse)(nil)
)

type httpClient struct {
	mu                 sync.Mutex
	tls                tls.Config
	h1h2Used           bool
	h1h2CAGen          uint64
	tlsChangedAfterUse bool
	h1h2Transport      http.Transport
	h1h2Client         http.Client
	trySocks5          bool
	forceSocks5        bool
	tryH3Direct        bool
	timeout            time.Duration
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Client.CheckRedirect = validateRedirectPolicy
	client.h1h2Transport.ForceAttemptHTTP2 = true
	client.h1h2Transport.DialContext = (&net.Dialer{Timeout: 10 * time.Second}).DialContext
	client.h1h2Transport.TLSHandshakeTimeout = 10 * time.Second
	client.h1h2Transport.ResponseHeaderTimeout = 10 * time.Second
	// Note: DisableKeepAlives is true by default (short-lived requests, no idle sockets).
	// IdleConnTimeout is only meaningful when KeepAlive() is called.
	client.h1h2Transport.DisableKeepAlives = true
	client.timeout = 10 * time.Second
	return client
}

func (c *httpClient) ModernTLS() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.tls.MinVersion = tls.VersionTLS12
	if c.h1h2Used {
		c.tlsChangedAfterUse = true
	}
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.tls.MinVersion = tls.VersionTLS13
	if c.h1h2Used {
		c.tlsChangedAfterUse = true
	}
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
	if c.h1h2Used {
		c.tlsChangedAfterUse = true
	}
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
	if c.h1h2Used {
		c.tlsChangedAfterUse = true
	}
}

func (c *httpClient) TrySocks5(port int32) {
	c.mu.Lock()
	defer c.mu.Unlock()
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		c.mu.Lock()
		tryH3 := c.tryH3Direct
		c.mu.Unlock()
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if tryH3 {
					return nil, errFailConnectSocks5
				}
				break
			}
			_ = socksConn.SetDeadline(time.Now().Add(10 * time.Second))
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
			_ = socksConn.SetDeadline(time.Time{})
			if err != nil {
				_ = socksConn.Close()
				if tryH3 {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, nil
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
	c.forceSocks5 = false
}

// ForceSocks5 routes all requests strictly through the local SOCKS5 proxy and
// fails closed without falling back to direct TCP or HTTP/3 connections.
func (c *httpClient) ForceSocks5(port int32) {
	c.mu.Lock()
	defer c.mu.Unlock()
	dialer := &net.Dialer{Timeout: 10 * time.Second}
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
		if err != nil {
			return nil, fmt.Errorf("%w: %v", errFailConnectSocks5, err)
		}
		_ = socksConn.SetDeadline(time.Now().Add(10 * time.Second))
		_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
		_ = socksConn.SetDeadline(time.Time{})
		if err != nil {
			_ = socksConn.Close()
			return nil, fmt.Errorf("%w: %v", errFailConnectSocks5, err)
		}
		return socksConn, nil
	}
	c.trySocks5 = true
	c.forceSocks5 = true
	c.tryH3Direct = false
}

func (c *httpClient) TryH3Direct() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.forceSocks5 {
		c.tryH3Direct = true
	}
}

func (c *httpClient) KeepAlive() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.h1h2Transport.ForceAttemptHTTP2 = true
	c.h1h2Transport.DisableKeepAlives = false
	c.h1h2Transport.IdleConnTimeout = 30 * time.Second
}

func (c *httpClient) NewRequest() HTTPRequest {
	req := &httpRequest{httpClient: c}
	req.request = http.Request{
		Method: "GET",
		Header: http.Header{},
	}
	return req
}

func (c *httpClient) Close() {
	c.h1h2Transport.CloseIdleConnections()
}

type httpRequest struct {
	*httpClient
	request       http.Request
	allowInsecure bool
}

func (r *httpRequest) AllowInsecure() {
	r.allowInsecure = true
}

func (r *httpRequest) requestTLSConfig() *tls.Config {
	r.mu.Lock()
	cfg := r.tls.Clone()
	r.mu.Unlock()
	if cfg.RootCAs == nil {
		cfg.RootCAs = currentRootCAs()
	}
	if r.allowInsecure {
		cfg.InsecureSkipVerify = true
	}
	return cfg
}

func (r *httpRequest) prepareH1H2Client() (*http.Client, io.Closer) {
	r.mu.Lock()
	defer r.mu.Unlock()

	customRoots := currentRootCAs()
	caGen := currentCustomCAGen()
	if r.allowInsecure || r.tlsChangedAfterUse || (r.h1h2Used && r.h1h2CAGen != caGen) {
		tr := r.h1h2Transport.Clone()
		tr.TLSClientConfig = r.tls.Clone()
		if tr.TLSClientConfig.RootCAs == nil {
			tr.TLSClientConfig.RootCAs = customRoots
		}
		if r.allowInsecure {
			tr.TLSClientConfig.InsecureSkipVerify = true
			tr.DisableKeepAlives = true
		}
		return &http.Client{Transport: tr, CheckRedirect: validateRedirectPolicy}, h1TransportCloser{tr}
	}
	if !r.h1h2Used {
		cfg := r.tls.Clone()
		if cfg.RootCAs == nil {
			cfg.RootCAs = customRoots
		}
		r.h1h2Transport.TLSClientConfig = cfg
		r.h1h2CAGen = caGen
		r.h1h2Used = true
	}
	return &r.h1h2Client, nil
}

func (r *httpRequest) cloneRequest(ctx context.Context) (*http.Request, error) {
	req := r.request.Clone(ctx)
	if r.request.GetBody != nil {
		body, err := r.request.GetBody()
		if err != nil {
			return nil, err
		}
		req.Body = body
	}
	return req, nil
}

func (r *httpRequest) SetURL(link string) (err error) {
	r.request.URL, err = url.Parse(link)
	if err != nil {
		return
	}
	if r.request.URL.User != nil {
		user := r.request.URL.User.Username()
		password, _ := r.request.URL.User.Password()
		r.request.SetBasicAuth(user, password)
	}
	return
}

func (r *httpRequest) SetMethod(method string) {
	r.request.Method = method
}

func (r *httpRequest) SetHeader(key string, value string) {
	r.request.Header.Set(key, value)
}

func (r *httpRequest) SetUserAgent(userAgent string) {
	r.request.Header.Set("User-Agent", userAgent)
}

func (r *httpRequest) SetContent(content []byte) {
	copied := append([]byte(nil), content...)
	r.request.Body = io.NopCloser(bytes.NewReader(copied))
	r.request.GetBody = func() (io.ReadCloser, error) {
		return io.NopCloser(bytes.NewReader(copied)), nil
	}
	r.request.ContentLength = int64(len(copied))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

// doWithHeaderTimeout bounds dial, handshake, and response headers to timeout
// without cancelling the returned response body read after headers arrive.
func doWithHeaderTimeout(
	parent context.Context,
	timeout time.Duration,
	do func(ctx context.Context) (*http.Response, io.Closer, error),
) (*http.Response, io.Closer, context.CancelFunc, error) {
	ctx, cancelCause := context.WithCancelCause(parent)
	var (
		mu          sync.Mutex
		headersDone bool
		timedOut    bool
	)
	var timer *time.Timer
	if timeout > 0 {
		timer = time.AfterFunc(timeout, func() {
			mu.Lock()
			if !headersDone {
				timedOut = true
				cancelCause(context.DeadlineExceeded)
			}
			mu.Unlock()
		})
	}
	defer func() {
		mu.Lock()
		headersDone = true
		mu.Unlock()
		if timer != nil {
			timer.Stop()
		}
	}()

	response, transport, err := do(ctx)
	mu.Lock()
	headersDone = true
	wasTimedOut := timedOut
	mu.Unlock()
	if timer != nil {
		timer.Stop()
	}

	cancel := func() { cancelCause(nil) }
	if err != nil {
		cancel()
		if response != nil && response.Body != nil {
			_ = response.Body.Close()
		}
		if transport != nil {
			_ = transport.Close()
		}
		if wasTimedOut && errors.Is(context.Cause(ctx), context.DeadlineExceeded) {
			return nil, nil, nil, context.DeadlineExceeded
		}
		return nil, nil, nil, err
	}
	if wasTimedOut {
		cancel()
		if response != nil && response.Body != nil {
			_ = response.Body.Close()
		}
		if transport != nil {
			_ = transport.Close()
		}
		return nil, nil, nil, context.DeadlineExceeded
	}
	return response, transport, cancel, nil
}

func (r *httpRequest) Execute() (resp HTTPResponse, err error) {
	defer device.DeferPanicToError("http execute", func(panicErr error) {
		log.Println(panicErr)
		resp = nil
		err = panicErr
	})
	if r.request.URL == nil {
		return nil, errors.New("request URL is not set")
	}
	r.mu.Lock()
	tryH3Direct := r.tryH3Direct
	trySocks5 := r.trySocks5
	forceSocks5 := r.forceSocks5
	r.mu.Unlock()

	// full direct
	if !forceSocks5 && tryH3Direct && !trySocks5 {
		return r.doH3Direct()
	}

	client, transportCloser := r.prepareH1H2Client()
	response, closer, cancel, err := doWithHeaderTimeout(
		r.request.Context(),
		r.timeout,
		func(ctx context.Context) (*http.Response, io.Closer, error) {
			request, cloneErr := r.cloneRequest(ctx)
			if cloneErr != nil {
				return nil, transportCloser, cloneErr
			}
			res, doErr := client.Do(request)
			return res, transportCloser, doErr
		},
	)
	if err != nil {
		// trySocks5 && tryH3Direct (never bypass when forceSocks5 is set)
		if !forceSocks5 && tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{
		Response:        response,
		transportCloser: closer,
		cancelFunc:      cancel,
	}
	if response.StatusCode != http.StatusOK {
		errMsg := httpResp.errorString()
		httpResp.closeResources()
		return nil, errors.New(errMsg)
	}
	return httpResp, nil
}

type requestFunc func(ctx context.Context) (response *http.Response, transport io.Closer, err error)

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	if r.request.URL == nil {
		return nil, errors.New("request URL is not set")
	}
	tlsCfg := r.requestTLSConfig()
	handshakeTimeout := r.timeout
	if handshakeTimeout <= 0 {
		handshakeTimeout = 10 * time.Second
	}

	funcs := []requestFunc{
		// Http(s) With Ech
		func(ctx context.Context) (response *http.Response, transport io.Closer, err error) {
			request, err := r.cloneRequest(ctx)
			if err != nil {
				return nil, nil, err
			}
			echTransport := &http.Transport{
				DisableKeepAlives:     true,
				DialContext:           (&net.Dialer{Timeout: handshakeTimeout}).DialContext,
				TLSHandshakeTimeout:   handshakeTimeout,
				ResponseHeaderTimeout: handshakeTimeout,
			}
			transport = h1TransportCloser{echTransport}
			echClient := &http.Client{
				Transport:     echTransport,
				CheckRedirect: validateRedirectPolicy,
			}
			echTransport.DialTLSContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
				d := net.Dialer{Timeout: handshakeTimeout}
				c, err := d.DialContext(ctx, network, addr)
				if err != nil {
					return c, err
				}
				domain := addr
				if host, _, _ := net.SplitHostPort(addr); host != "" {
					domain = host
				}
				tlsConfig := tlsCfg.Clone()
				// Restrict ALPN to HTTP/1.1 because echTransport is an H1 transport
				// without H2 frame decoding. Allowing H2 ALPN causes servers to send
				// HTTP/2 SETTINGS frames, which triggers 'malformed HTTP response'.
				tlsConfig.NextProtos = []string{"http/1.1"}
				echTls := ech.NewECHClientConfig(domain, tlsConfig, gLocalDNSTransport)
				tlsConn, err := echTls.Client(ctx, c)
				if err != nil {
					_ = c.Close()
					return nil, err
				}
				return tlsConn, nil
			}
			response, err = echClient.Do(request)
			return
		},
		// H3 HTTPS
		func(ctx context.Context) (response *http.Response, transport io.Closer, err error) {
			request, err := r.cloneRequest(ctx)
			if err != nil {
				return nil, nil, err
			}
			idleTimeout := 30 * time.Second
			if r.timeout > idleTimeout {
				idleTimeout = r.timeout
			}
			h3Transport := &http3.Transport{
				TLSClientConfig: tlsCfg.Clone(),
				QUICConfig: &quic.Config{
					HandshakeIdleTimeout: handshakeTimeout,
					MaxIdleTimeout:       idleTimeout,
				},
			}
			transport = h3Transport
			h3Client := &http.Client{
				Transport:     h3Transport,
				CheckRedirect: validateRedirectPolicy,
			}
			response, err = h3Client.Do(request)
			return
		},
	}

	if r.request.URL.Scheme == "http" {
		funcs = funcs[:1]
	}

	return r.raceDirectRequests(funcs)
}

func (r *httpRequest) raceDirectRequests(funcs []requestFunc) (HTTPResponse, error) {
	if len(funcs) == 0 {
		return nil, errors.New("all HTTP transports failed")
	}
	type result struct {
		idx       int
		response  *http.Response
		transport io.Closer
		cancel    context.CancelFunc
		err       error
	}
	resultCh := make(chan result, len(funcs))
	var finalErr error
	var mu sync.Mutex

	candCancels := make([]context.CancelFunc, len(funcs))
	for i := range funcs {
		candCtx, candCancel := context.WithCancel(r.request.Context())
		candCancels[i] = candCancel
		idx := i
		fn := funcs[i]
		go func() {
			var t string
			switch idx {
			case 0:
				t = "http(s)"
			case 1:
				t = "h3"
			default:
				t = fmt.Sprintf("transport-%d", idx)
			}

			sent := false
			sendResult := func(res result) {
				if !sent {
					sent = true
					resultCh <- res
				}
			}

			defer device.DeferPanicToError("http", func(panicErr error) {
				log.Println(panicErr)
				candCancel()
				mu.Lock()
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", t, panicErr))
				mu.Unlock()
				sendResult(result{idx: idx, err: panicErr})
			})

			rsp, transport, headerCancel, err := doWithHeaderTimeout(candCtx, r.timeout, fn)
			if rsp == nil || err != nil {
				if headerCancel != nil {
					headerCancel()
				}
				candCancel()
				if rsp != nil && rsp.Body != nil {
					_ = rsp.Body.Close()
				}
				if transport != nil {
					_ = transport.Close()
				}
				if err == nil {
					err = errors.New("request returned no response")
				}
				mu.Lock()
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", t, err))
				mu.Unlock()
				sendResult(result{idx: idx, err: err})
				return
			}

			combinedCancel := func() {
				if headerCancel != nil {
					headerCancel()
				}
				candCancel()
			}

			if rsp.StatusCode != http.StatusOK {
				hr := &httpResponse{Response: rsp, transportCloser: transport, cancelFunc: combinedCancel}
				err = fmt.Errorf("%s: %s", t, hr.errorString())
				hr.closeResources()
				mu.Lock()
				finalErr = errors.Join(finalErr, err)
				mu.Unlock()
				sendResult(result{idx: idx, err: err})
				return
			}
			sendResult(result{idx: idx, response: rsp, transport: transport, cancel: combinedCancel})
		}()
	}

	for received := 0; received < len(funcs); received++ {
		res := <-resultCh
		if res.response != nil {
			// Cancel losing in-flight transports without cancelling the winner's body read context.
			for j, c := range candCancels {
				if j != res.idx && c != nil {
					c()
				}
			}
			// Drain remaining candidates asynchronously so a slow/hung loser cannot block the winner.
			if remaining := len(funcs) - received - 1; remaining > 0 {
				go func(left int) {
					for k := 0; k < left; k++ {
						extra := <-resultCh
						if extra.cancel != nil {
							extra.cancel()
						}
						if extra.response != nil && extra.response.Body != nil {
							_ = extra.response.Body.Close()
						}
						if extra.transport != nil {
							_ = extra.transport.Close()
						}
					}
				}(remaining)
			}
			return &httpResponse{Response: res.response, transportCloser: res.transport, cancelFunc: res.cancel}, nil
		}
	}

	for _, c := range candCancels {
		if c != nil {
			c()
		}
	}
	mu.Lock()
	err := finalErr
	mu.Unlock()
	if err == nil {
		err = errors.New("all HTTP transports failed")
	}
	return nil, err
}

type httpResponse struct {
	*http.Response

	transportCloser   io.Closer
	cancelFunc        context.CancelFunc
	bodyReadTimeout   time.Duration
	streamReadTimeout time.Duration

	getContentOnce sync.Once
	closeBodyOnce  sync.Once
	content        []byte
	contentError   error
}

// armReadDeadline starts a watchdog timer that closes the response body and
// underlying transport if a read exceeds timeout, preventing slow-drip servers
// from hanging callers indefinitely.
func (h *httpResponse) armReadDeadline(timeout time.Duration) (stop func(), didTimeout func() bool) {
	var timedOut atomic.Bool
	if timeout <= 0 {
		return func() {}, timedOut.Load
	}
	timer := time.AfterFunc(timeout, func() {
		timedOut.Store(true)
		h.closeResources()
	})
	return func() { timer.Stop() }, timedOut.Load
}

// errorString formats a bounded diagnostic message for non-200 HTTP responses.
// It reads at most maxErrorBodyBytes (4 KiB) within errorSnippetReadTimeout so
// oversized or stalled error bodies cannot exhaust memory or block execution.
func (h *httpResponse) errorString() string {
	defer h.closeResources()
	if h.Body == nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
	stopDeadline, didTimeout := h.armReadDeadline(errorSnippetReadTimeout)
	defer stopDeadline()

	rawSnippet, readErr := io.ReadAll(io.LimitReader(h.Body, maxErrorBodyBytes))
	if didTimeout() || (readErr != nil && len(rawSnippet) == 0) {
		return fmt.Sprint("HTTP ", h.Status)
	}
	content := string(rawSnippet)
	if len(content) > 100 {
		content = content[:100] + " ..."
	}
	return fmt.Sprint("HTTP ", h.Status, ": ", content)
}

func (h *httpResponse) GetHeader(key string) *StringBox {
	return wrapString(h.Header.Get(key))
}

func (h *httpResponse) GetContent() ([]byte, error) {
	return h.GetContentWithLimit(DefaultHTTPResponseMaxSize)
}

// GetContentWithLimit reads the entire response body into memory up to maxBytes
// within the configured body read timeout.
//
// Parameters:
//   - maxBytes: Maximum permitted payload size in bytes (negative values clamp to 0).
//
// Returns the payload bytes, ErrHTTPResponseTooLarge when the body exceeds
// maxBytes, or errBodyReadTimeout when reading exceeds the body deadline.
func (h *httpResponse) GetContentWithLimit(maxBytes int64) ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.closeResources()
		if maxBytes < 0 {
			maxBytes = 0
		}
		timeout := h.bodyReadTimeout
		if timeout <= 0 {
			timeout = defaultBodyReadTimeout
		}
		stopDeadline, didTimeout := h.armReadDeadline(timeout)
		defer stopDeadline()

		h.content, h.contentError = io.ReadAll(io.LimitReader(h.Body, maxBytes+1))
		if didTimeout() {
			h.content = nil
			h.contentError = errBodyReadTimeout
			return
		}
		if h.contentError == nil && int64(len(h.content)) > maxBytes {
			h.content = nil
			h.contentError = ErrHTTPResponseTooLarge
		}
	})
	return h.content, h.contentError
}

// closeResources closes the response body and the underlying transport (if any).
// It is safe to call multiple times.
func (h *httpResponse) closeResources() {
	h.closeBodyOnce.Do(func() {
		if h.Body != nil {
			_ = h.Body.Close()
		}
		if h.transportCloser != nil {
			_ = h.transportCloser.Close()
			h.transportCloser = nil
		}
		if h.cancelFunc != nil {
			h.cancelFunc()
			h.cancelFunc = nil
		}
	})
}

func (h *httpResponse) GetContentString() (*StringBox, error) {
	content, err := h.getContentString()
	if err != nil {
		return nil, err
	}
	return wrapString(content), nil
}

func (h *httpResponse) getContentString() (string, error) {
	content, err := h.GetContent()
	if err != nil {
		return "", err
	}
	return string(content), nil
}

func (h *httpResponse) WriteTo(path string) error {
	return h.WriteToWithLimit(path, DefaultHTTPResponseMaxSize)
}

// WriteToWithLimit streams the response body to path, enforcing both maxBytes
// and the configured streaming read deadline.
//
// Parameters:
//   - path: Destination filesystem path to create/overwrite.
//   - maxBytes: Maximum permitted payload size in bytes (negative values clamp to 0).
//
// Returns nil on success, ErrHTTPResponseTooLarge when truncated at maxBytes,
// errBodyReadTimeout on deadline expiry, or any underlying I/O error.
func (h *httpResponse) WriteToWithLimit(path string, maxBytes int64) error {
	defer h.closeResources()
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	if maxBytes < 0 {
		maxBytes = 0
	}
	timeout := h.streamReadTimeout
	if timeout <= 0 {
		timeout = defaultStreamReadTimeout
	}
	stopDeadline, didTimeout := h.armReadDeadline(timeout)
	defer stopDeadline()

	var written int64
	written, err = io.Copy(file, io.LimitReader(h.Body, maxBytes+1))
	if didTimeout() {
		return errBodyReadTimeout
	}
	if err == nil && written > maxBytes {
		err = ErrHTTPResponseTooLarge
		_ = file.Truncate(maxBytes)
	}
	return err
}
