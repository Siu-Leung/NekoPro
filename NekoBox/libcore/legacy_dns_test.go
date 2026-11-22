package libcore

import (
	"context"
	"encoding/pem"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"

	mDNS "github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing/service"
)

func TestLegacyDNSFullConfigStart(t *testing.T) {
	for name, servers := range map[string]string{
		"bare":  `{"address":"1.1.1.1","tag":"remote"}`,
		"udp":   `{"type":"legacy","address":"udp://[2001:4860:4860::8888]:5353","tag":"remote","detour":"direct"}`,
		"local": `{"address":"local","tag":"remote","detour":"direct"}`,
		"doh":   `{"address":"https://dns.example:8443/dns-query","tag":"remote","address_resolver":"bootstrap","address_strategy":"ipv4_only","strategy":"prefer_ipv4"},{"address":"1.1.1.1","tag":"bootstrap","detour":"direct"}`,
	} {
		t.Run(name, func(t *testing.T) {
			cfg := `{"log":{"disabled":true},"dns":{"final":"remote","servers":[{"address":"rcode://success","tag":"blocked"},` + servers + `,{"address":"fakeip","tag":"fake","strategy":"ipv4_only"}],"fakeip":{"enabled":true,"inet4_range":"198.18.0.0/15","inet6_range":"fc00::/18"},"rules":[{"domain":"blocked.example","server":"blocked"},{"domain":"fake.example","query_type":["A","AAAA"],"server":"fake"}]},"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}`
			instance, err := NewTestSingBoxInstance(cfg, nil)
			if err != nil {
				t.Fatal(err)
			}
			defer instance.Close()
			if err = instance.Start(); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestLegacyDNSNoOpAndErrors(t *testing.T) {
	original := " {\n \"dns\":{\"servers\":[{\"type\":\"udp\",\"server\":\"1.1.1.1\"}]} } "
	got, flags, err := normalizeLegacyDNS([]byte(original))
	if err != nil || flags != nil || string(got) != original {
		t.Fatalf("typed config changed: %s, %v", got, err)
	}
	for _, conf := range []string{`{"dns":{"servers":[{"address":"https://dns.example"}]}}`, `{"dns":{"servers":[{"address":"fakeip"}]}}`, `{"dns":{"servers":[{"address":"rcode://success","tag":"a"}],"final":"a"}}`} {
		if _, _, err := normalizeLegacyDNS([]byte(conf)); err == nil || !strings.Contains(err.Error(), "legacy DNS dns.") {
			t.Fatalf("expected a labeled error for %s: %v", conf, err)
		}
	}
}

// The configured hostname only exists in the injected Android-style local
// resolver. The default outbound is required (the first outbound blocks).
func TestLegacyDNSDoHQuery(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/custom-dns" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		payload, err := io.ReadAll(r.Body)
		request := new(mDNS.Msg)
		if err != nil || request.Unpack(payload) != nil || len(request.Question) != 1 {
			http.Error(w, "bad query", 400)
			return
		}
		response := new(mDNS.Msg).SetReply(request)
		response.Answer = []mDNS.RR{&mDNS.A{Hdr: mDNS.RR_Header{Name: request.Question[0].Name, Rrtype: mDNS.TypeA, Class: mDNS.ClassINET, Ttl: 60}, A: netip.MustParseAddr("192.0.2.7").AsSlice()}}
		wire, _ := response.Pack()
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(wire)
	}))
	defer server.Close()
	cert := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: server.Certificate().Raw})
	port := server.Listener.Addr().(*net.TCPAddr).Port
	cfg := fmt.Sprintf(`{"log":{"disabled":true},"certificate":{"certificate":%q},"dns":{"final":"remote","servers":[{"address":"rcode://success","tag":"block-dns"},{"address":"https://example.com:%d/custom-dns","tag":"remote","address_resolver":"bootstrap","address_strategy":"ipv4_only","strategy":"ipv4_only"},{"address":"local","tag":"bootstrap","detour":"direct"},{"address":"fakeip","tag":"fake"}],"fakeip":{"enabled":true,"inet4_range":"198.18.0.0/15"},"rules":[{"domain":"blocked.example","server":"block-dns"},{"domain":"fake.example","query_type":"A","server":"fake"}]},"outbounds":[{"type":"block","tag":"block"},{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}`, cert, port)
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(testLegacyLocal{}), nekoboxAndroidServiceRegistry(), nekoboxAndroidCertificateProviderRegistry())
	options, err := decodeLegacyDNSConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err = instance.Start(); err != nil {
		t.Fatal(err)
	}
	router := service.FromContext[adapter.DNSRouter](ctx)
	if router == nil {
		t.Fatal("DNS router not registered")
	}
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	query := func(name string, typ uint16) *mDNS.Msg {
		t.Helper()
		result, err := router.Exchange(ctx, new(mDNS.Msg).SetQuestion(name, typ), adapter.DNSQueryOptions{})
		if err != nil || result.Rcode != mDNS.RcodeSuccess {
			t.Fatalf("lookup %s: %v, %v", name, result, err)
		}
		return result
	}
	if got := query("blocked.example.", mDNS.TypeA); len(got.Answer) != 0 {
		t.Fatalf("rcode answer: %v", got.Answer)
	}
	if got := query("fake.example.", mDNS.TypeA); len(got.Answer) != 1 || !netip.MustParsePrefix("198.18.0.0/15").Contains(netip.MustParseAddr(got.Answer[0].(*mDNS.A).A.String())) {
		t.Fatalf("fakeip answer: %v", got.Answer)
	}
	if got := query("ordinary.example.", mDNS.TypeA); len(got.Answer) != 1 || got.Answer[0].(*mDNS.A).A.String() != "192.0.2.7" {
		t.Fatalf("DoH answer: %v", got.Answer)
	}
	if got := query("ordinary.example.", mDNS.TypeAAAA); len(got.Answer) != 0 {
		t.Fatalf("strategy answer: %v", got.Answer)
	}
}

type testLegacyLocal struct{}

func (testLegacyLocal) Raw() bool            { return false }
func (testLegacyLocal) NetworkHandle() int64 { return 0 }
func (testLegacyLocal) Lookup(ctx *ExchangeContext, network, domain string) error {
	if strings.TrimSuffix(domain, ".") != "example.com" || network != "ip4" {
		return fmt.Errorf("unexpected bootstrap lookup %q %q", network, domain)
	}
	ctx.Success("127.0.0.1")
	return nil
}
func (testLegacyLocal) Exchange(*ExchangeContext, []byte) error {
	return fmt.Errorf("unexpected raw exchange")
}

// TestDirectDnsFallbackRecoversFromPrimaryServfail verifies that when the primary
// direct DoH resolver returns SERVFAIL for a node hostname (e.g. an airport .sbs
// domain), the ConfigBuilder-style `dns-direct` fallback transport transparently
// fails over to `dns-local` instead of failing SpeedTest / URLTest / exit probes.
func TestDirectDnsFallbackRecoversFromPrimaryServfail(t *testing.T) {
	servfailDoH := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		payload, err := io.ReadAll(r.Body)
		request := new(mDNS.Msg)
		if err != nil || request.Unpack(payload) != nil || len(request.Question) != 1 {
			http.Error(w, "bad query", http.StatusBadRequest)
			return
		}
		response := new(mDNS.Msg).SetReply(request)
		response.Rcode = mDNS.RcodeServerFailure
		wire, _ := response.Pack()
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(wire)
	}))
	defer servfailDoH.Close()

	cert := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: servfailDoH.Certificate().Raw})
	port := servfailDoH.Listener.Addr().(*net.TCPAddr).Port
	cfg := fmt.Sprintf(`{
		"log":{"disabled":true},
		"certificate":{"certificate":%q},
		"dns":{
			"final":"dns-direct",
			"strategy":"prefer_ipv4",
			"servers":[
				{"type":"local","tag":"dns-local"},
				{"type":"https","tag":"dns-direct-1","server":"127.0.0.1","server_port":%d,"path":"/dns-query"},
				{"type":"fallback","tag":"dns-direct","servers":["dns-direct-1","dns-local"]}
			],
			"rules":[]
		},
		"outbounds":[{"type":"direct","tag":"direct"}],
		"route":{"final":"direct","default_domain_resolver":"dns-direct"}
	}`, cert, port)

	instance, err := NewTestSingBoxInstance(cfg, testNodeLocal{answerIPv4: "77.67.126.132"})
	if err != nil {
		t.Fatal(err)
	}
	defer instance.Close()
	if err := instance.Start(); err != nil {
		t.Fatal(err)
	}

	resolved, err := instance.LookupDomainWithTag("node.airport.sbs", "dns-direct", 3000)
	if err != nil {
		t.Fatalf("expected fallback to dns-local after primary SERVFAIL, got error: %v", err)
	}
	if resolved != "77.67.126.132" {
		t.Fatalf("expected 77.67.126.132 from dns-local fallback, got %q", resolved)
	}
}

type testNodeLocal struct {
	answerIPv4 string
}

func (testNodeLocal) Raw() bool            { return false }
func (testNodeLocal) NetworkHandle() int64 { return 0 }
func (l testNodeLocal) Lookup(ctx *ExchangeContext, network, _ string) error {
	if network == "ip4" {
		ctx.Success(l.answerIPv4)
		return nil
	}
	ctx.Success("")
	return nil
}
func (testNodeLocal) Exchange(*ExchangeContext, []byte) error {
	return fmt.Errorf("unexpected raw exchange")
}

