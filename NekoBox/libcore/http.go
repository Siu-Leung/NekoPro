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
			_, err = socks.ClientHandshake5(socksConn, socks5.CommandConnect, metadata.ParseSocksaddr(addr), "", "")
			if err != nil {
				if c.tryH3Direct {
					return nil, errFailConnectSocks5
				}
				break
			}
			return socksConn, err
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
	defer cancel()
	request := r.request.Clone(ctx)
	response, err := r.h1h2Client.Do(request)
	if err != nil {
		if response != nil && response.Body != nil {
			_ = response.Body.Close()
		}
		// trySocks5 && tryH3Direct
		if r.tryH3Direct && errors.Is(err, errFailConnectSocks5) {
			return r.doH3Direct()
		}
		return nil, err
	}
	httpResp := &httpResponse{Response: response}
	if response.StatusCode != http.StatusOK {
		return nil, errors.New(httpResp.errorString())
	}
	return httpResp, nil
}

type requestFunc func() (response *http.Response, transport io.Closer, err error)

func (r *httpRequest) doH3Direct() (HTTPResponse, error) {
	ctx, cancel := context.WithTimeout(r.request.Context(), r.timeout)
	defer cancel()

	type result struct {
		response  *http.Response
		transport io.Closer
		err       error
	}
	resultCh := make(chan result, 2)
	var finalErr error
	var mu sync.Mutex

	funcs := []requestFunc{
		// Http(s) With Ech
		func() (response *http.Response, transport io.Closer, err error) {
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
		func() (response *http.Response, transport io.Closer, err error) {
			request := r.request.Clone(ctx)
			h3Transport := &http3.Transport{
				TLSClientConfig: r.tls.Clone(),
				QUICConfig:      &quic.Config{MaxIdleTimeout: time.Second},
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

	for i, f := range funcs {
		go func(f requestFunc) {
			defer device.DeferPanicToError("http", func(err error) { log.Println(err) })

			var t string
			switch i {
			case 0:
				t = "http(s)"
			case 1:
				t = "h3"
			}

			// 执行HTTP请求
			rsp, transport, err := f()
			if rsp == nil || err != nil {
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
				resultCh <- result{err: err}
				return
			}

			// 处理 HTTP 状态码
			if rsp.StatusCode != http.StatusOK {
				hr := &httpResponse{Response: rsp}
				err = fmt.Errorf("%s: %s", t, hr.errorString())
				if transport != nil {
					_ = transport.Close()
				}
				mu.Lock()
				finalErr = errors.Join(finalErr, err)
				mu.Unlock()
				resultCh <- result{err: err}
				return
			}
			resultCh <- result{response: rsp, transport: transport}
		}(f)
	}

	var winner *http.Response
	var winnerTransport io.Closer
	for i := 0; i < len(funcs); i++ {
		result := <-resultCh
		if result.response != nil {
			if winner == nil {
				winner = result.response
				winnerTransport = result.transport
				cancel()
			} else {
				// duplicate success: keep transport alive until body is closed,
				// but we cannot reuse it, so close it with the response body.
				if result.transport != nil {
					_ = result.transport.Close()
				}
				if result.response.Body != nil {
					_ = result.response.Body.Close()
				}
			}
		}
	}
	if winner != nil {
		// keep winner transport open; it will be closed when response body is closed
		return &httpResponse{Response: winner, transportCloser: winnerTransport}, nil
	}
	mu.Lock()
	err := finalErr
	mu.Unlock()
	if err == nil {
		err = ctx.Err()
	}
	if err == nil {
		err = errors.New("all HTTP transports failed")
	}
	return nil, err
}

type httpResponse struct {
	*http.Response

	transportCloser io.Closer

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
