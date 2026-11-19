package libcore

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/oschwald/maxminddb-golang"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

var (
	geoipMu         sync.RWMutex
	geoipReader     *maxminddb.Reader
	geoipPath       string
	geoipInfo       os.FileInfo
	geoipGen        uint64
	geoipRulesCache = make(map[string][]option.HeadlessRule)
)

func sameGeoFile(cached, current os.FileInfo) bool {
	if cached == nil || current == nil {
		return false
	}
	return os.SameFile(cached, current) &&
		cached.Size() == current.Size() &&
		cached.ModTime().Equal(current.ModTime())
}

// ResetGeoipCache drops the cached geoip reader and rules so that a newly
// updated geoip.db takes effect without restarting the process.
func ResetGeoipCache() {
	geoipMu.Lock()
	defer geoipMu.Unlock()
	if geoipReader != nil {
		_ = geoipReader.Close()
		geoipReader = nil
	}
	geoipPath = ""
	geoipInfo = nil
	geoipGen++
	geoipRulesCache = make(map[string][]option.HeadlessRule)
}

func loadGeoIPRules(reader *maxminddb.Reader, key, countryCode string) ([]option.HeadlessRule, error) {
	networks := reader.Networks(maxminddb.SkipAliasedNetworks)
	var (
		ipNets          []*net.IPNet
		ipNet           *net.IPNet
		nextCountryCode string
		err             error
	)
	for networks.Next() {
		ipNet, err = networks.Network(&nextCountryCode)
		if err != nil {
			return nil, fmt.Errorf("failed to get network: %w", err)
		}
		if nextCountryCode == key {
			ipNets = append(ipNets, ipNet)
		}
	}
	if err = networks.Err(); err != nil {
		return nil, fmt.Errorf("failed to iterate networks: %w", err)
	}

	if len(ipNets) == 0 {
		return nil, fmt.Errorf("no networks found for country code: %s", countryCode)
	}

	var headlessRule option.DefaultHeadlessRule
	headlessRule.IPCIDR = make([]string, 0, len(ipNets))
	for _, cidr := range ipNets {
		headlessRule.IPCIDR = append(headlessRule.IPCIDR, cidr.String())
	}

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func getGeoIPRules(countryCode string) ([]option.HeadlessRule, error) {
	dbPath := filepath.Join(externalAssetsPath, geoipDat)
	key := strings.ToLower(countryCode)
	currentInfo, statErr := os.Stat(dbPath)
	if statErr != nil {
		return nil, statErr
	}

	// Fast path: hold RLock across cache check and database iteration so
	// ResetGeoipCache cannot close/munmap geoipReader while it is in use.
	geoipMu.RLock()
	if geoipReader != nil && geoipPath == dbPath && sameGeoFile(geoipInfo, currentInfo) {
		if rules, ok := geoipRulesCache[key]; ok {
			geoipMu.RUnlock()
			return rules, nil
		}
		gen := geoipGen
		rules, err := loadGeoIPRules(geoipReader, key, countryCode)
		geoipMu.RUnlock()
		if err != nil {
			return nil, err
		}
		geoipMu.Lock()
		if geoipGen == gen && geoipPath == dbPath {
			geoipRulesCache[key] = rules
		}
		geoipMu.Unlock()
		return rules, nil
	}
	geoipMu.RUnlock()

	// Cold path: lazily open (or reopen on path/file replacement) under exclusive lock.
	geoipMu.Lock()
	defer geoipMu.Unlock()
	if latestInfo, err := os.Stat(dbPath); err == nil {
		currentInfo = latestInfo
	}
	if geoipReader == nil || geoipPath != dbPath || !sameGeoFile(geoipInfo, currentInfo) {
		r, err := maxminddb.Open(dbPath)
		if err != nil {
			return nil, err
		}
		if geoipReader != nil {
			_ = geoipReader.Close()
		}
		geoipReader = r
		geoipPath = dbPath
		geoipInfo = currentInfo
		geoipGen++
		geoipRulesCache = make(map[string][]option.HeadlessRule)
	}
	if rules, ok := geoipRulesCache[key]; ok {
		return rules, nil
	}
	rules, err := loadGeoIPRules(geoipReader, key, countryCode)
	if err != nil {
		return nil, err
	}
	geoipRulesCache[key] = rules
	return rules, nil
}

func init() {
	nekoutils.GetGeoIPHeadlessRules = getGeoIPRules
}
