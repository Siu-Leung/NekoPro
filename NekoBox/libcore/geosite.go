package libcore

import (
	"path/filepath"
	"sync"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

var (
	cachedGeositeReader *geosites.Reader
	cachedGeositePath   string
	geositeLock         sync.Mutex
)

func getCachedGeositeReader(path string) (*geosites.Reader, error) {
	geositeLock.Lock()
	defer geositeLock.Unlock()
	if cachedGeositeReader != nil && cachedGeositePath == path {
		return cachedGeositeReader, nil
	}
	r, _, err := geosites.Open(path)
	if err != nil {
		return nil, err
	}
	cachedGeositeReader = r
	cachedGeositePath = path
	return r, nil
}

func getGeoSiteRules(code string) ([]option.HeadlessRule, error) {
	dbPath := filepath.Join(externalAssetsPath, "geosite.db")
	reader, err := getCachedGeositeReader(dbPath)
	if err != nil {
		return nil, err
	}
	sourceSet, err := reader.Read(code)
	if err != nil {
		return nil, nil
	}

	var headlessRule option.DefaultHeadlessRule
	defaultRule := geosites.Compile(sourceSet)
	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

func init() {
	nekoutils.GetGeoSiteHeadlessRules = getGeoSiteRules
}
