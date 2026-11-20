package dns

import (
	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	"net/netip"
)

type TransportAdapter struct {
	transportType      string
	transportTag       string
	dependencies       []string
	references         []string
	legacyStrategy     C.DomainStrategy
	legacyClientSubnet netip.Prefix
}

func NewTransportAdapter(transportType string, transportTag string, dependencies []string) TransportAdapter {
	return TransportAdapter{
		transportType: transportType,
		transportTag:  transportTag,
		dependencies:  dependencies,
	}
}

func NewTransportAdapterWithLocalOptions(transportType string, transportTag string, localOptions option.LocalDNSServerOptions) TransportAdapter {
	var dependencies []string
	if localOptions.DomainResolver != nil && localOptions.DomainResolver.Server != "" {
		dependencies = append(dependencies, localOptions.DomainResolver.Server)
	}
	var references []string
	if localOptions.Detour != "" {
		references = []string{localOptions.Detour}
	}
	return TransportAdapter{
		transportType:      transportType,
		transportTag:       transportTag,
		dependencies:       dependencies,
		references:         references,
		legacyStrategy:     localOptions.LegacyDNSStrategy,
		legacyClientSubnet: localOptions.LegacyDNSClientSubnet,
	}
}

func NewTransportAdapterWithRemoteOptions(transportType string, transportTag string, remoteOptions option.RemoteDNSServerOptions) TransportAdapter {
	var dependencies []string
	if remoteOptions.DomainResolver != nil && remoteOptions.DomainResolver.Server != "" {
		dependencies = append(dependencies, remoteOptions.DomainResolver.Server)
	}
	var references []string
	if remoteOptions.Detour != "" {
		references = []string{remoteOptions.Detour}
	}
	return TransportAdapter{
		transportType:      transportType,
		transportTag:       transportTag,
		dependencies:       dependencies,
		references:         references,
		legacyStrategy:     remoteOptions.LegacyDNSStrategy,
		legacyClientSubnet: remoteOptions.LegacyDNSClientSubnet,
	}
}

func (a *TransportAdapter) Type() string {
	return a.transportType
}

func (a *TransportAdapter) Tag() string {
	return a.transportTag
}

func (a *TransportAdapter) Dependencies() []string {
	return a.dependencies
}

func (a *TransportAdapter) References() []string {
	return a.references
}

// ApplyLegacyDNSDefaults applies the legacy server's query options only when
// the caller or a DNS rule did not supply an override.
func (a *TransportAdapter) ApplyLegacyDNSDefaults(options *adapter.DNSQueryOptions) {
	if options.Strategy == C.DomainStrategyAsIS {
		options.Strategy = a.legacyStrategy
	}
	if !options.ClientSubnet.IsValid() && !options.RemoveClientSubnet {
		options.ClientSubnet = a.legacyClientSubnet
	}
}
