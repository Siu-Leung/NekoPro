package libcore

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"libcore/device"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/constant"
	M "github.com/sagernet/sing/common/metadata"
)

const (
	defaultProbeTimeout   = 10 * time.Second
	maxProbeTimeout       = 30 * time.Second
	defaultProbeMaxBytes  = 16 << 10
	hardProbeMaxBytesCap  = 1 << 20
	defaultDNSLookupTimer = 6 * time.Second
)

// resolveStrictProxyDialer locates the explicit "proxy" outbound or endpoint
// inside instance and rejects direct/block/dns pseudo-outbounds so node exit
// and attribution probes can never leak onto the physical network.
//
// Parameters:
//   - instance: Running sing-box instance to inspect.
//
// Returns the verified proxy adapter.Outbound or an error if unavailable.
func resolveStrictProxyDialer(instance *box.Box) (adapter.Outbound, error) {
	if instance == nil {
		return nil, errors.New("nil sing-box instance")
	}
	if proxyOutbound, ok := instance.Outbound().Outbound("proxy"); ok {
		if isForbiddenProbeOutboundType(proxyOutbound.Type()) {
			return nil, fmt.Errorf("refusing non-proxy outbound type %q", proxyOutbound.Type())
		}
		return proxyOutbound, nil
	}
	if proxyEndpoint, ok := instance.Endpoint().Get("proxy"); ok {
		if isForbiddenProbeOutboundType(proxyEndpoint.Type()) {
			return nil, fmt.Errorf("refusing non-proxy endpoint type %q", proxyEndpoint.Type())
		}
		return proxyEndpoint, nil
	}
	return nil, errors.New("proxy outbound or endpoint not found")
}

func isForbiddenProbeOutboundType(outboundType string) bool {
	switch strings.ToLower(strings.TrimSpace(outboundType)) {
	case "", constant.TypeDirect, constant.TypeBlock, constant.TypeDNS:
		return true
	default:
		return false
	}
}

// FetchHttpsJson performs a bounded, redirect-free HTTPS GET exclusively over
// this BoxInstance's "proxy" outbound or WireGuard endpoint.
//
// Parameters:
//   - targetUrl: HTTPS endpoint URL to query (e.g., GeoIP/ASN JSON API).
//   - userAgent: Explicit User-Agent header value to avoid WAF blocks on default Go UA.
//   - maxBytes: Maximum response body size in bytes (clamped to [1, 1 MiB]).
//   - timeoutMs: Overall request timeout in milliseconds (clamped to [500ms, 30s]).
//
// Returns the raw response body bytes on HTTP 2xx, or an error on validation,
// transport, status, or size failure.
func (b *BoxInstance) FetchHttpsJson(targetUrl string, userAgent string, maxBytes int64, timeoutMs int32) (payload []byte, err error) {
	defer device.DeferPanicToError("box.FetchHttpsJson", func(panicErr error) { err = panicErr })

	if b == nil || b.Box == nil {
		return nil, errors.New("box instance is not initialized")
	}
	parsedURL, err := validateProbeHttpsUrl(targetUrl)
	if err != nil {
		return nil, err
	}
	proxyDialer, err := resolveStrictProxyDialer(b.Box)
	if err != nil {
		return nil, err
	}

	timeout := normalizeProbeTimeout(timeoutMs, defaultProbeTimeout)
	limit := normalizeProbeMaxBytes(maxBytes)

	transport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return proxyDialer.DialContext(ctx, network, M.ParseSocksaddr(addr))
		},
		TLSHandshakeTimeout:   timeout,
		ResponseHeaderTimeout: timeout,
		DisableKeepAlives:     true,
	}
	if roots := currentRootCAs(); roots != nil {
		transport.TLSClientConfig = &tls.Config{RootCAs: roots}
	}
	httpClient := &http.Client{
		Transport: transport,
		Timeout:   timeout,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			// WHY: Attribution and exit probes must never follow redirects to untrusted or cleartext origins.
			return http.ErrUseLastResponse
		},
	}
	defer httpClient.CloseIdleConnections()

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, parsedURL.String(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json, text/plain;q=0.8")
	if trimmedUA := strings.TrimSpace(userAgent); trimmedUA != "" {
		req.Header.Set("User-Agent", trimmedUA)
	}

	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("unexpected HTTP status %d", resp.StatusCode)
	}
	if resp.ContentLength > limit {
		return nil, fmt.Errorf("response exceeds %d bytes", limit)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > limit {
		return nil, fmt.Errorf("response body exceeded %d bytes", limit)
	}
	return data, nil
}

// LookupDomainWithTag resolves a node hostname using a named DNS transport
// registered inside this BoxInstance (supporting https, h3, tls, quic, udp,
// tcp, and fallback group resolvers) and returns the first usable IP literal.
//
// Parameters:
//   - domain: Node server hostname to resolve.
//   - transportTag: Optional DNS server tag (e.g. "dns-group-1" or "dns-direct"); uses Default() if blank.
//   - timeoutMs: Lookup timeout in milliseconds.
//
// Returns the resolved IP string, or an error when resolution fails or returns only unusable addresses.
func (b *BoxInstance) LookupDomainWithTag(domain string, transportTag string, timeoutMs int32) (resolved string, err error) {
	defer device.DeferPanicToError("box.LookupDomainWithTag", func(panicErr error) { err = panicErr })

	if b == nil || b.dnsRouter == nil || b.dnsTransportManager == nil {
		return "", errors.New("box DNS router is not initialized")
	}
	cleanDomain := strings.TrimSuffix(strings.TrimSpace(domain), ".")
	if cleanDomain == "" {
		return "", errors.New("empty domain")
	}
	if literal, parseErr := netip.ParseAddr(strings.Trim(cleanDomain, "[]")); parseErr == nil {
		if isUsableResolvedAddr(literal) {
			return literal.Unmap().String(), nil
		}
		return "", errors.New("unusable literal address")
	}

	var transport adapter.DNSTransport
	if trimmedTag := strings.TrimSpace(transportTag); trimmedTag != "" {
		loaded, ok := b.dnsTransportManager.Transport(trimmedTag)
		if !ok {
			return "", fmt.Errorf("dns transport %q not found", trimmedTag)
		}
		transport = loaded
	} else {
		transport = b.dnsTransportManager.Default()
	}

	timeout := normalizeProbeTimeout(timeoutMs, defaultDNSLookupTimer)
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	addrs, err := b.dnsRouter.Lookup(ctx, cleanDomain, adapter.DNSQueryOptions{
		Transport:    transport,
		DisableCache: true,
	})
	if err != nil {
		return "", err
	}
	for _, addr := range addrs {
		unmapped := addr.Unmap()
		if isUsableResolvedAddr(unmapped) {
			return unmapped.String(), nil
		}
	}
	return "", errors.New("no usable IP address returned")
}

func validateProbeHttpsUrl(rawURL string) (*url.URL, error) {
	parsed, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil {
		return nil, fmt.Errorf("invalid probe URL: %w", err)
	}
	if !strings.EqualFold(parsed.Scheme, "https") || parsed.Host == "" {
		return nil, errors.New("probe URL must be https with non-empty host")
	}
	if parsed.User != nil {
		return nil, errors.New("probe URL must not embed credentials")
	}
	return parsed, nil
}

func normalizeProbeTimeout(timeoutMs int32, fallback time.Duration) time.Duration {
	if timeoutMs <= 0 {
		return fallback
	}
	dur := time.Duration(timeoutMs) * time.Millisecond
	if dur < 500*time.Millisecond {
		return 500 * time.Millisecond
	}
	if dur > maxProbeTimeout {
		return maxProbeTimeout
	}
	return dur
}

func normalizeProbeMaxBytes(maxBytes int64) int64 {
	if maxBytes <= 0 {
		return defaultProbeMaxBytes
	}
	if maxBytes > hardProbeMaxBytesCap {
		return hardProbeMaxBytesCap
	}
	return maxBytes
}

func isUsableResolvedAddr(addr netip.Addr) bool {
	if !addr.IsValid() || addr.IsUnspecified() || addr.IsLoopback() || addr.IsLinkLocalUnicast() || addr.IsLinkLocalMulticast() || addr.IsMulticast() {
		return false
	}
	unmapped := addr.Unmap()
	if unmapped.Is4() {
		octets := unmapped.As4()
		// Reject 0.0.0.0/8, FakeIP 198.18.0.0/15, and reserved >= 224.0.0.0/3.
		if octets[0] == 0 || octets[0] >= 224 {
			return false
		}
		if octets[0] == 198 && (octets[1] == 18 || octets[1] == 19) {
			return false
		}
		return true
	}
	// Reject IPv6 ULA fc00::/7.
	octets := unmapped.As16()
	return (octets[0] & 0xFE) != 0xFC
}
