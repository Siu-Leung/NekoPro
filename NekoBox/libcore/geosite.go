package libcore

import (
	"io"
	"path/filepath"
	"sync"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/nekoutils"
	"github.com/sagernet/sing-box/option"
)

var (
	geositeMu           sync.RWMutex
	cachedGeositeReader *geosites.Reader
	cachedGeositePath   string
)

func closeGeositeReader(r *geosites.Reader) {
	if r == nil {
		return
	}
	if closer, ok := r.Upstream().(io.Closer); ok {
		_ = closer.Close()
	}
}

func readGeoSiteCode(path, code string) (items []geosites.Item, openErr error, readErr error) {
	geositeMu.RLock()
	if cachedGeositeReader != nil && cachedGeositePath == path {
		items, readErr = cachedGeositeReader.Read(code)
		geositeMu.RUnlock()
		return items, nil, readErr
	}
	geositeMu.RUnlock()

	geositeMu.Lock()
	defer geositeMu.Unlock()
	if cachedGeositeReader == nil || cachedGeositePath != path {
		r, _, err := geosites.Open(path)
		if err != nil {
			return nil, err, nil
		}
		closeGeositeReader(cachedGeositeReader)
		cachedGeositeReader = r
		cachedGeositePath = path
	}
	items, readErr = cachedGeositeReader.Read(code)
	return items, nil, readErr
}

// ResetGeositeCache closes and drops the cached geosite reader so that a newly
// updated geosite.db takes effect without restarting the process.
func ResetGeositeCache() {
	geositeMu.Lock()
	defer geositeMu.Unlock()
	closeGeositeReader(cachedGeositeReader)
	cachedGeositeReader = nil
	cachedGeositePath = ""
}

func getGeoSiteRules(code string) ([]option.HeadlessRule, error) {
	dbPath := filepath.Join(externalAssetsPath, geositeDat)
	sourceSet, openErr, readErr := readGeoSiteCode(dbPath, code)
	if openErr != nil {
		return nil, openErr
	}
	if readErr != nil {
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
