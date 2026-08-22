package libcore

import (
	"fmt"
	"os"
	"path/filepath"
	"time"
)

const (
	// 官方数据源（sing-box 官方发布）
	GeoIPDownloadURL   = "https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db"
	GeoSiteDownloadURL = "https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db"

	// 最小合法大小：防止下载到错误页/空文件（geoip.db 约 10MB+，保守下限 1MB）
	geoAssetMinSize = 1 << 20
)

// UpdateGeoIP downloads the latest geoip.db from the official source and
// atomically replaces the local asset, then resets the geoip cache so the new
// database takes effect without restarting the process.
//
// Returns a summary string on success, or an error. If the VPN is not connected
// and the source is unreachable, the caller should retry after connecting.
func UpdateGeoIP() (string, error) {
	return downloadGeoAsset("geoip.db", GeoIPDownloadURL, func() {
		ResetGeoipCache()
	})
}

// UpdateGeoSite downloads the latest geosite.db from the official source and
// atomically replaces the local asset, then resets the geosite cache.
func UpdateGeoSite() (string, error) {
	return downloadGeoAsset("geosite.db", GeoSiteDownloadURL, func() {
		ResetGeositeCache()
	})
}

func downloadGeoAsset(fileName, url string, onSuccess func()) (string, error) {
	dst := filepath.Join(externalAssetsPath, fileName)
	tmp := dst + ".tmp"
	if err := os.MkdirAll(externalAssetsPath, 0755); err != nil {
		return "", err
	}

	client := NewHttpClient()
	client.KeepAlive()
	defer client.Close()

	request := client.NewRequest()
	if err := request.SetURL(url); err != nil {
		return "", err
	}
	response, err := request.Execute()
	if err != nil {
		return "", fmt.Errorf("download %s: %w", fileName, err)
	}
	if err := response.WriteToWithLimit(tmp, 1<<30); err != nil {
		_ = os.Remove(tmp)
		return "", fmt.Errorf("save %s: %w", fileName, err)
	}
	stat, err := os.Stat(tmp)
	if err != nil {
		_ = os.Remove(tmp)
		return "", fmt.Errorf("stat %s: %w", fileName, err)
	}
	if stat.Size() < geoAssetMinSize {
		_ = os.Remove(tmp)
		return "", fmt.Errorf("downloaded %s too small: %d bytes", fileName, stat.Size())
	}
	if err := os.Rename(tmp, dst); err != nil {
		_ = os.Remove(tmp)
		return "", fmt.Errorf("replace %s: %w", fileName, err)
	}
	if onSuccess != nil {
		onSuccess()
	}
	return fmt.Sprintf("%s:%s:%d", fileName, time.Now().Format("200601021504"), stat.Size()), nil
}
