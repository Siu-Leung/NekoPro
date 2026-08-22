package libcore

import (
	"fmt"
	"net"
	"path/filepath"
	"strings"
	"sync"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

var (
	geoipOpenMu  sync.Mutex
	geoipReader  *maxminddb.Reader
	geoipOpenErr error

	geoipRulesMu    sync.Mutex
	geoipRulesCache = make(map[string][]option.HeadlessRule)
)

func getGeoipReader() (*maxminddb.Reader, error) {
	geoipOpenMu.Lock()
	defer geoipOpenMu.Unlock()
	return getGeoipReaderLocked()
}

func getGeoipReaderLocked() (*maxminddb.Reader, error) {
	if geoipReader == nil {
		geoipReader, geoipOpenErr = maxminddb.Open(filepath.Join(externalAssetsPath, "geoip.db"))
	}
	if geoipOpenErr != nil {
		return nil, geoipOpenErr
	}
	return geoipReader, nil
}

// ResetGeoipCache drops the cached geoip reader and rules so that a newly
// updated geoip.db takes effect without restarting the process.
func ResetGeoipCache() {
	geoipOpenMu.Lock()
	if geoipReader != nil {
		_ = geoipReader.Close()
		geoipReader = nil
	}
	geoipOpenErr = nil
	geoipOpenMu.Unlock()
	geoipRulesMu.Lock()
	geoipRulesCache = make(map[string][]option.HeadlessRule)
	geoipRulesMu.Unlock()
}

func getGeoIPRules(countryCode string) ([]option.HeadlessRule, error) {
	reader, err := getGeoipReader()
	if err != nil {
		return nil, err
	}
	key := strings.ToLower(countryCode)

	geoipRulesMu.Lock()
	defer geoipRulesMu.Unlock()
	if rules, ok := geoipRulesCache[key]; ok {
		return rules, nil
	}

	networks := reader.Networks(maxminddb.SkipAliasedNetworks)
	countryMap := make(map[string][]*net.IPNet)
	var (
		ipNet           *net.IPNet
		nextCountryCode string
	)
	for networks.Next() {
		ipNet, err = networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		countryMap[nextCountryCode] = append(countryMap[nextCountryCode], ipNet)
	}

	ipNets := countryMap[key]

	if len(ipNets) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	var headlessRule option.DefaultHeadlessRule
	headlessRule.IPCIDR = make([]string, 0, len(ipNets))
	for _, cidr := range ipNets {
		headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidr.String())
	}

	rules := []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}
	geoipRulesCache[key] = rules
	return rules, nil
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = getGeoIPRules
}
