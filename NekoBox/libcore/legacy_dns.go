package libcore

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badoption"
)

type legacyDNSObject = map[string]json.RawMessage

type legacyDNSServer struct {
	Type            string                `json:"type"`
	Tag             string                `json:"tag"`
	Address         string                `json:"address"`
	Resolver        string                `json:"address_resolver"`
	AddressStrategy option.DomainStrategy `json:"address_strategy"`
	FallbackDelay   badoption.Duration    `json:"address_fallback_delay"`
	Strategy        option.DomainStrategy `json:"strategy"`
	Detour          string                `json:"detour"`
	ClientSubnet    *badoption.Prefixable `json:"client_subnet"`
}

type legacyDNSFakeIP struct {
	Enabled bool              `json:"enabled"`
	Inet4   *badoption.Prefix `json:"inet4_range"`
	Inet6   *badoption.Prefix `json:"inet6_range"`
}

func setLegacyDNSField(object legacyDNSObject, field string, value any) {
	// Values are known DNS option types or JSON-compatible primitives.
	object[field], _ = json.Marshal(value)
}

func decodeLegacyDNSConfig(ctx context.Context, config string) (option.Options, error) {
	var options option.Options
	original := []byte(config)
	input, legacy, err := normalizeLegacyDNS(original)
	if err != nil {
		return options, err
	}
	if err = options.UnmarshalJSONContext(ctx, input); err != nil {
		return options, err
	}
	options.RawMessage = original
	for i, server := range legacy {
		if opts, ok := options.DNS.Servers[i].Options.(interface {
			SetLegacyDNSOptions(C.DomainStrategy, netip.Prefix)
		}); ok && server != nil {
			opts.SetLegacyDNSOptions(C.DomainStrategy(server.Strategy), server.ClientSubnet.Build(netip.Prefix{}))
		}
	}
	return options, nil
}

// Normalize only the in-memory core input. Typed-only configs take the byte-for-byte
// fast path, and unrelated fields in mixed configs are passed to the normal decoder.
// Behavior follows main:sing-box/option/dns.go and dns/transport_dialer.go;
// see upstream-sb-1.14.4 docs/migration.md for the typed formats.
func normalizeLegacyDNS(input []byte) ([]byte, []*legacyDNSServer, error) {
	var root, dns legacyDNSObject
	if json.Unmarshal(input, &root) != nil || json.Unmarshal(root["dns"], &dns) != nil {
		return input, nil, nil // defer malformed/non-object input to the core
	}
	var servers []json.RawMessage
	if raw := dns["servers"]; raw != nil && json.Unmarshal(raw, &servers) != nil {
		return input, nil, nil
	}
	var fake legacyDNSFakeIP
	changed := dns["fakeip"] != nil
	if changed {
		if err := decodeLegacyDNSObject(dns["fakeip"], &fake); err != nil {
			return nil, nil, fmt.Errorf("legacy DNS dns.fakeip: %w", err)
		}
		delete(dns, "fakeip")
	}
	rcodes := make(map[string]int)
	resolverReferences := make(map[int]string)
	var output []json.RawMessage
	var legacy []*legacyDNSServer
	for i, raw := range servers {
		var fields legacyDNSObject
		if json.Unmarshal(raw, &fields) != nil || fields == nil {
			return input, nil, nil
		}
		var typ string
		if v := fields["type"]; v != nil && json.Unmarshal(v, &typ) != nil {
			return input, nil, nil
		}
		if typ != "" && typ != C.DNSTypeLegacy {
			output = append(output, raw)
			legacy = append(legacy, nil)
			continue
		}
		changed = true
		var old legacyDNSServer
		path := fmt.Sprintf("dns.servers[%d]", i)
		if err := decodeLegacyDNSObject(raw, &old); err != nil {
			return nil, nil, fmt.Errorf("legacy DNS %s: %w", path, err)
		}
		newFields, rcode, err := convertLegacyDNSServer(old, fake)
		if err != nil {
			return nil, nil, fmt.Errorf("legacy DNS %s: %w", path, err)
		}
		if newFields == nil {
			rcodes[old.Tag] = rcode
			continue
		}
		if old.Resolver != "" && newFields["domain_resolver"] != nil {
			resolverReferences[i] = old.Resolver
		}
		encoded, _ := json.Marshal(newFields)
		output = append(output, encoded)
		legacy = append(legacy, &old)
	}
	if !changed {
		return input, nil, nil
	}
	if len(rcodes) != 0 {
		// As in the old upgrader, implicit numeric tags are assigned after
		// rcode entries are removed, not from the original list indexes.
		var final string
		_ = json.Unmarshal(dns["final"], &final)
		if _, found := rcodes[final]; final != "" && found {
			return nil, nil, fmt.Errorf("legacy DNS dns.final references an rcode server; use a predefined DNS rule instead")
		}
		for i, resolver := range resolverReferences {
			if _, found := rcodes[resolver]; found {
				return nil, nil, fmt.Errorf("legacy DNS dns.servers[%d].address_resolver references an rcode server", i)
			}
		}
		if raw := dns["rules"]; raw != nil {
			var rules []legacyDNSObject
			if err := json.Unmarshal(raw, &rules); err != nil {
				return nil, nil, fmt.Errorf("legacy DNS dns.rules: %w", err)
			}
			for i, rule := range rules {
				if err := rewriteLegacyRCode(rule, rcodes); err != nil {
					return nil, nil, fmt.Errorf("legacy DNS dns.rules[%d]: %w", i, err)
				}
			}
			setLegacyDNSField(dns, "rules", rules)
		}
	}
	if dns["servers"] != nil {
		setLegacyDNSField(dns, "servers", output)
	}
	setLegacyDNSField(root, "dns", dns)
	encoded, err := json.Marshal(root)
	return encoded, legacy, err
}

func decodeLegacyDNSObject(raw json.RawMessage, target any) error {
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.DisallowUnknownFields()
	return decoder.Decode(target)
}

func convertLegacyDNSServer(old legacyDNSServer, fake legacyDNSFakeIP) (fields legacyDNSObject, rcode int, err error) {
	fields = make(legacyDNSObject)
	typ, host, path := C.DNSTypeUDP, old.Address, ""
	if old.Address == C.DNSTypeLocal || old.Address == C.DNSTypeFakeIP {
		typ = old.Address
	} else if strings.Contains(old.Address, "://") {
		u, parseErr := url.Parse(old.Address)
		if parseErr != nil || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.RawPath != "" {
			return nil, 0, fmt.Errorf("invalid or unsupported address URL (userinfo, query, fragment and escaped paths are not migrated)")
		}
		typ, host, path = u.Scheme, u.Host, u.Path
	}
	if typ == "rcode" {
		codes := map[string]int{"success": 0, "format_error": 1, "server_failure": 2, "name_error": 3, "not_implemented": 4, "refused": 5}
		if code, ok := codes[host]; ok && (path == "" || path == "/") {
			return nil, code, nil
		}
		return nil, 0, fmt.Errorf("unsupported rcode address")
	}
	if old.Tag != "" {
		setLegacyDNSField(fields, "tag", old.Tag)
	}
	setLegacyDNSField(fields, "type", typ)
	switch typ {
	case C.DNSTypeFakeIP:
		if !fake.Enabled || (fake.Inet4 == nil && fake.Inet6 == nil) {
			return nil, 0, fmt.Errorf("fakeip requires enabled dns.fakeip and at least one address range")
		}
		if fake.Inet4 != nil {
			setLegacyDNSField(fields, "inet4_range", fake.Inet4)
		}
		if fake.Inet6 != nil {
			setLegacyDNSField(fields, "inet6_range", fake.Inet6)
		}
		return fields, 0, nil
	case C.DNSTypeLocal:
		// Android's platform local transport ignores the dialer, as in v1.2.2.
	case C.DNSTypeUDP, C.DNSTypeTCP, C.DNSTypeTLS, C.DNSTypeQUIC, C.DNSTypeHTTPS, C.DNSTypeHTTP3:
		server, port, e := splitLegacyDNSAddress(host)
		if e != nil {
			return nil, 0, e
		}
		setLegacyDNSField(fields, "server", server)
		if port != 0 {
			setLegacyDNSField(fields, "server_port", port)
		}
		if typ == C.DNSTypeHTTPS || typ == C.DNSTypeHTTP3 {
			if path != "" {
				setLegacyDNSField(fields, "path", path)
			}
		} else if path != "" && path != "/" {
			return nil, 0, fmt.Errorf("non-HTTP DNS addresses cannot include a path")
		}
		if old.Resolver == "" && old.Detour == "" {
			if _, ipErr := netip.ParseAddr(server); ipErr != nil {
				return nil, 0, fmt.Errorf("domain address requires address_resolver when detour is omitted")
			}
		}
	default:
		return nil, 0, fmt.Errorf("unsupported address scheme %q", typ)
	}
	if old.Detour != "" {
		setLegacyDNSField(fields, "detour", old.Detour)
	}
	if old.Resolver != "" {
		setLegacyDNSField(fields, "domain_resolver", option.DomainResolveOptions{Server: old.Resolver, Strategy: old.AddressStrategy})
	}
	if old.FallbackDelay != 0 {
		setLegacyDNSField(fields, "fallback_delay", old.FallbackDelay)
	}
	return fields, 0, nil
}

func splitLegacyDNSAddress(address string) (string, uint16, error) {
	if ip, err := netip.ParseAddr(strings.TrimSuffix(strings.TrimPrefix(address, "["), "]")); err == nil {
		return ip.String(), 0, nil
	}
	host, portString, err := net.SplitHostPort(address)
	var port uint64
	if err == nil {
		port, err = strconv.ParseUint(portString, 10, 16)
		if err != nil {
			return "", 0, fmt.Errorf("invalid DNS server port")
		}
	} else {
		host = address
	}
	if ip, ipErr := netip.ParseAddr(host); ipErr == nil {
		return ip.String(), uint16(port), nil
	}
	if host == "" || strings.ContainsAny(host, ":/[]@?#% \t\r\n") {
		return "", 0, fmt.Errorf("invalid DNS server address")
	}
	return host, uint16(port), nil
}

func rewriteLegacyRCode(rule legacyDNSObject, rcodes map[string]int) error {
	var action, server string
	_ = json.Unmarshal(rule["action"], &action)
	if raw := rule["server"]; raw == nil || json.Unmarshal(raw, &server) != nil {
		return nil
	}
	rcode, found := rcodes[server]
	if !found {
		return nil
	}
	if action != "" && action != C.RuleActionTypeRoute {
		return fmt.Errorf("rcode server is only supported as the target of a route action")
	}
	// The old upgrader discarded these route-only fields for fixed responses.
	for _, key := range []string{"server", "strategy", "disable_cache", "rewrite_ttl", "client_subnet"} {
		delete(rule, key)
	}
	setLegacyDNSField(rule, "action", C.RuleActionTypePredefined)
	setLegacyDNSField(rule, "rcode", rcode)
	return nil
}
