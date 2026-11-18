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
	"net/url"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/sagernet/quic-go"
	"github.com/sagernet/quic-go/http3"
	"github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/protocol/socks"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

var errFailConnectSocks5 = errors.New("fail connect socks5")

// h1TransportCloser adapts *http.Transport (which only has
// CloseIdleConnections) to io.Closer for unified transport cleanup.
type h1TransportCloser struct {
	*http.Transport
}

func (c h1TransportCloser) Close() error {
	c.Transport.CloseIdleConnections()
	return nil
}

// DefaultHTTPResponseMaxSize is the hard cap used by the legacy gomobile
// methods. Callers that need a different budget can use the *WithLimit
// methods without changing the existing API.
const DefaultHTTPResponseMaxSize int64 = 64 << 20

var ErrHTTPResponseTooLarge = errors.New("http response body exceeds maximum size")

type HTTPClient interface {
	RestrictedTLS()
	ModernTLS()
	PinnedTLS12()
	PinnedSHA256(sumHex string)
	TrySocks5(port int32)
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
	tls           tls.Config
	h1h2Transport http.Transport
	h1h2Client    http.Client
	trySocks5     bool
	tryH3Direct   bool
	timeout       time.Duration
}

func NewHttpClient() HTTPClient {
	client := new(httpClient)
	client.h1h2Client.Transport = &client.h1h2Transport
	client.h1h2Transport.TLSClientConfig = &client.tls
	client.h1h2Transport.ForceAttemptHTTP2 = true
	client.h1h2Transport.TLSHandshakeTimeout = 10 * time.Second
	client.h1h2Transport.ResponseHeaderTimeout = 10 * time.Second
	// Note: DisableKeepAlives is true by default (short-lived requests, no idle sockets).
	// IdleConnTimeout is only meaningful when KeepAlive() is called.
	client.h1h2Transport.DisableKeepAlives = true
	client.timeout = 10 * time.Second
	return client
}

func (c *httpClient) ModernTLS() {
	c.tls.MinVersion = tls.VersionTLS12
	// c.tls.CipherSuites = nekoutils.Map(tls.CipherSuites(), func(it *tls.CipherSuite) uint16 { return it.ID })
}

func (c *httpClient) RestrictedTLS() {
	c.tls.MinVersion = tls.VersionTLS13
	// c.tls.CipherSuites = nekoutils.Map(nekoutils.Filter(tls.CipherSuites(), func(it *tls.CipherSuite) bool {
	// 	return nekoutils.Contains(it.SupportedVersions, uint16(tls.VersionTLS13))
	// }), func(it *tls.CipherSuite) uint16 {
	// 	return it.ID
	// })
}

func (c *httpClient) PinnedTLS12() {
	c.tls.MinVersion = tls.VersionTLS12
	c.tls.MaxVersion = tls.VersionTLS12
}

func (c *httpClient) PinnedSHA256(sumHex string) {
	c.tls.VerifyPeerCertificate = func(rawCerts [][]byte, verifiedChains [][]*x509.Certificate) error {
		for _, rawCert := range rawCerts {
			certSum := sha256.Sum256(rawCert)
			if sumHex == hex.EncodeToString(certSum[:]) {
				return nil
			}
		}
		return errors.New("pinned sha256 sum mismatch")
	}
}

func (c *httpClient) TrySocks5(port int32) {
	dialer := new(net.Dialer)
	c.h1h2Transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		for {
			socksConn, err := dialer.DialContext(ctx, "tcp", "127.0.0.1:"+strconv.Itoa(int(port)))
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			_ = socksConn.SetDeadline(time.Now().Add(10 * time.Second))
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
			_ = socksConn.SetDeadline(time.Time{})
			if err != nil {
				_ = socksConn.Close()
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, nil
		}
		return dialer.DialContext(ctx, network, addr)
	}
	c.trySocks5 = true
}

func (c *httpClient) TryH3Direct() {
	c.tryH3Direct = true
}

func (c *httpClient) KeepAlive() {
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
	request http.Request
}

func (r *httpRequest) AllowInsecure() {
	r.tls.InsecureSkipVerify = true
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
	buffer := bytes.Buffer{}
	buffer.Write(content)
	r.request.Body = io.NopCloser(bytes.NewReader(buffer.Bytes()))
	r.request.ContentLength = int64(len(content))
}

func (r *httpRequest) SetContentString(content string) {
	r.SetContent([]byte(content))
}

func (r *httpRequest) Execute() (HTTPResponse, error) {
	defer device.DeferPanicToError("http execute", func(err error) { log.Println(err) })
	// full direct
	if r.tryH3Direct && !r.trySocks5 {
		return r.doH3Direct()
	}
	ctx, cancel := context.WithTimeout(r.request.Context(), r.timeout)
	request := r.request.Clone(ctx)
	response, err := r.h1h2Client.Do(request)
	if err != nil {
		cancel()
		if response != nil && response.Body != nil {
			_ = response.Body.Close()
		}
		// trySocks5 && tryH3Direct
		if r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{Response: response, cancelFunc: cancel}
	if response.StatusCode != http.StatusOK {
		errMsg := httpResp.errorString()
		httpResp.closeResources()
		return nil, errors.New(errMsg)
	}
	return httpResp, nil
}

type requestFunc func(ctx context.Context) (response *http.Response, transport io.Closer, err error)

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	type result struct {
		idx       int
		response  *http.Response
		transport io.Closer
		cancel    context.CancelFunc
		err       error
	}
	resultCh := make(chan result, 2)
	var finalErr error
	var mu sync.Mutex

	funcs := []requestFunc{
		// Http(s) With Ech
		func(ctx context.Context) (response *http.Response, transport io.Closer, err error) {
			request := r.request.Clone(ctx)
			echTransport := &http.Transport{
				DisableKeepAlives:     true,
				TLSHandshakeTimeout:   r.timeout,
				ResponseHeaderTimeout: r.timeout,
			}
			transport = h1TransportCloser{echTransport}
			echClient := &http.Client{
				Transport: echTransport,
				Timeout:   r.timeout,
			}
			echTransport.DialTLSContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
				var d net.Dialer
				c, err := d.DialContext(ctx, network, addr)
				if err != nil {
					return c, err
				}
				domain := addr
				if host, _, _ := net.SplitHostPort(addr); host != "" {
					domain = host
				}
				tlsConfig := r.tls.Clone()
				// Restrict ALPN to HTTP/1.1 because echTransport is an H1 transport
				// without H2 frame decoding. Allowing H2 ALPN causes servers to send
				// HTTP/2 SETTINGS frames, which triggers 'malformed HTTP response'.
				tlsConfig.NextProtos = []string{"http/1.1"}
				echTls := ech.NewECHClientConfig(domain, tlsConfig, gLocalDNSTransport)
				return echTls.Client(ctx, c)
			}
			response, err = echClient.Do(request)
			return
		},
		// H3 HTTPS
		func(ctx context.Context) (response *http.Response, transport io.Closer, err error) {
			request := r.request.Clone(ctx)
			idleTimeout := r.timeout
			if idleTimeout < 5*time.Second {
				idleTimeout = 5 * time.Second
			}
			h3Transport := &http3.Transport{
				TLSClientConfig: r.tls.Clone(),
				QUICConfig:      &quic.Config{MaxIdleTimeout: idleTimeout},
			}
			transport = h3Transport
			h3Client := &http.Client{
				Transport: h3Transport,
				Timeout:   r.timeout,
			}
			response, err = h3Client.Do(request)
			return
		},
	}

	if r.request.URL.Scheme == "http" {
		funcs = funcs[:1]
	}

	candCancels := make([]context.CancelFunc, len(funcs))
	for i := range funcs {
		candCtx, candCancel := context.WithTimeout(r.request.Context(), r.timeout)
		candCancels[i] = candCancel
		idx := i
		fn := funcs[i]
		go func() {
			defer device.DeferPanicToError("http", func(err error) { log.Println(err) })

			var t string
			switch idx {
			case 0:
				t = "http(s)"
			case 1:
				t = "h3"
			}

			rsp, transport, err := fn(candCtx)
			if rsp == nil || err != nil {
				candCancel()
				if rsp != nil && rsp.Body != nil {
					_ = rsp.Body.Close()
				}
				if transport != nil {
					_ = transport.Close()
				}
				mu.Lock()
				if err == nil {
					err = errors.New("request returned no response")
				}
				finalErr = errors.Join(finalErr, fmt.Errorf("%s: %w", t, err))
				mu.Unlock()
				resultCh <- result{idx: idx, err: err}
				return
			}

			if rsp.StatusCode != http.StatusOK {
				hr := &httpResponse{Response: rsp, transportCloser: transport, cancelFunc: candCancel}
				err = fmt.Errorf("%s: %s", t, hr.errorString())
				hr.closeResources()
				mu.Lock()
				finalErr = errors.Join(finalErr, err)
				mu.Unlock()
				resultCh <- result{idx: idx, err: err}
				return
			}
			resultCh <- result{idx: idx, response: rsp, transport: transport, cancel: candCancel}
		}()
	}

	var winner *http.Response
	var winnerTransport io.Closer
	var winnerCancel context.CancelFunc
	for i := 0; i < len(funcs); i++ {
		res := <-resultCh
		if res.response != nil {
			if winner == nil {
				winner = res.response
				winnerTransport = res.transport
				winnerCancel = res.cancel
				// Cancel losing in-flight transports without cancelling the winner's body read context
				for j, c := range candCancels {
					if j != res.idx && c != nil {
						c()
					}
				}
			} else {
				if res.cancel != nil {
					res.cancel()
				}
				if res.transport != nil {
					_ = res.transport.Close()
				}
				if res.response.Body != nil {
					_ = res.response.Body.Close()
				}
			}
		}
	}
	if winner != nil {
		return &httpResponse{Response: winner, transportCloser: winnerTransport, cancelFunc: winnerCancel}, nil
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

	transportCloser io.Closer
	cancelFunc      context.CancelFunc

	getContentOnce sync.Once
	content        []byte
	contentError   error
}

func (h *httpResponse) errorString() string {
	content, err := h.getContentString()
	if err != nil {
		return fmt.Sprint("HTTP ", h.Status)
	}
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

func (h *httpResponse) GetContentWithLimit(maxBytes int64) ([]byte, error) {
	h.getContentOnce.Do(func() {
		defer h.closeResources()
		if maxBytes < 0 {
			maxBytes = 0
		}
		h.content, h.contentError = io.ReadAll(io.LimitReader(h.Body, maxBytes+1))
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
	var n int64
	n, err = io.Copy(file, io.LimitReader(h.Body, maxBytes+1))
	if err == nil && n > maxBytes {
		err = ErrHTTPResponseTooLarge
		_ = file.Truncate(maxBytes)
	}
	return err
}
