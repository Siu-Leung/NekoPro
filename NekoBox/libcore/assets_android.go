//go:build android

package libcore

import (
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strconv"

	"golang.org/x/mobile/asset"
	"golang.org/x/sys/unix"
)

const assetsLockFileName = ".assets.lock"

func extractAssets() {
	if internalAssetsPath != "" {
		_ = os.MkdirAll(internalAssetsPath, 0o755)
		lockPath := filepath.Join(internalAssetsPath, assetsLockFileName)
		// WHY: Serialize asset extraction across Android main and :bg processes so
		// concurrent startups never clobber geoip.db, geosite.db, yacd, or metacubexd.
		if lockFile, err := os.OpenFile(lockPath, os.O_CREATE|os.O_RDWR, 0o600); err == nil {
			if flockErr := unix.Flock(int(lockFile.Fd()), unix.LOCK_EX); flockErr == nil {
				defer func() {
					_ = unix.Flock(int(lockFile.Fd()), unix.LOCK_UN)
				}()
			} else {
				log.Println("WARN: flock assets lock failed:", flockErr)
			}
			defer lockFile.Close()
		} else {
			log.Println("WARN: open assets lock failed:", err)
		}
	}

	useOfficialAssets := intfNB4A.UseOfficialAssets()

	extract := func(name string) {
		err := extractAssetName(name, useOfficialAssets)
		if err != nil {
			log.Println("Extract", name, "failed:", err)
		}
	}

	extract(geoipDat)
	extract(geositeDat)
	extract(yacdDstFolder)
	extract(metacubexdDstFolder)
}

// 这里解压的是 apk 里面的
func extractAssetName(name string, useOfficialAssets bool) error {
	// 支持非官方源的，就是 replaceable，放 Android 目录
	// 不支持非官方源的，就放 file 目录
	replaceable := true

	var version string
	var apkPrefix string
	switch name {
	case geoipDat:
		version = geoipVersion
		apkPrefix = apkAssetPrefixSingBox
	case geositeDat:
		version = geositeVersion
		apkPrefix = apkAssetPrefixSingBox
	case yacdDstFolder:
		version = yacdVersion
		replaceable = false
	case metacubexdDstFolder:
		version = metacubexdVersion
		replaceable = false
	}

	var dir string
	if !replaceable {
		dir = internalAssetsPath
	} else {
		dir = externalAssetsPath
	}
	dstName := filepath.Join(dir, name)
	versionPath := filepath.Join(dir, version)

	var localVersion string
	var assetVersion string

	// loadAssetVersion from APK
	loadAssetVersion := func() error {
		av, err := asset.Open(apkPrefix + version)
		if err != nil {
			return fmt.Errorf("open version in assets: %v", err)
		}
		b, err := io.ReadAll(av)
		av.Close()
		if err != nil {
			return fmt.Errorf("read internal version: %v", err)
		}
		assetVersion = string(b)
		return nil
	}
	if err := loadAssetVersion(); err != nil {
		return err
	}

	var doExtract bool

	if _, err := os.Stat(dstName); err != nil {
		// assetFileMissing
		doExtract = true
	} else if useOfficialAssets || !replaceable {
		// 官方源升级
		b, err := os.ReadFile(versionPath)
		if err != nil {
			// versionFileMissing
			doExtract = true
			_ = os.RemoveAll(versionPath)
		} else {
			localVersion = string(b)
			if localVersion == "Custom" {
				doExtract = false
			} else {
				av, err := strconv.ParseUint(assetVersion, 10, 64)
				if err != nil {
					doExtract = assetVersion != localVersion
				} else {
					lv, err := strconv.ParseUint(localVersion, 10, 64)
					doExtract = err != nil || av > lv
				}
			}
		}
	} else {
		//非官方源不升级
	}

	if !doExtract {
		return nil
	}

	extractXz := func(f asset.File) error {
		tmpXzName := dstName + ".xz"
		err := extractAsset(f, tmpXzName)
		if err == nil {
			err = Unxz(tmpXzName, dstName)
			_ = os.Remove(tmpXzName)
		}
		if err != nil {
			return fmt.Errorf("extract xz: %v", err)
		}
		return nil
	}

	extractZipFolder := func(zipAssetName string, globPattern string) error {
		f, err := asset.Open(zipAssetName)
		if err != nil {
			return fmt.Errorf("open %s in assets: %w", zipAssetName, err)
		}
		if stale, globErr := filepath.Glob(filepath.Join(internalAssetsPath, globPattern)); globErr == nil {
			for _, stalePath := range stale {
				_ = os.RemoveAll(stalePath)
			}
		}
		_ = os.RemoveAll(dstName)
		tmpZipName := dstName + ".zip"
		err = extractAsset(f, tmpZipName)
		if err == nil {
			err = Unzip(tmpZipName, internalAssetsPath)
			_ = os.Remove(tmpZipName)
		}
		if err != nil {
			return fmt.Errorf("extract zip %s: %w", zipAssetName, err)
		}
		m, err := filepath.Glob(filepath.Join(internalAssetsPath, globPattern))
		if err != nil {
			return fmt.Errorf("glob %s: %w", globPattern, err)
		}
		if len(m) != 1 {
			return fmt.Errorf("glob %s found %d result, expect 1", globPattern, len(m))
		}
		if err := os.Rename(m[0], dstName); err != nil {
			return fmt.Errorf("rename %s: %w", name, err)
		}
		return nil
	}

	switch name {
	case yacdDstFolder:
		if err := extractZipFolder("yacd.zip", "Yacd-*"); err != nil {
			log.Println("extractZipFolder yacd error:", err)
			return err
		}
	case metacubexdDstFolder:
		if err := extractZipFolder("metacubexd.zip", "MetaCubeXD-*"); err != nil {
			log.Println("extractZipFolder metacubexd error:", err)
			return err
		}
	default:
		f, err := asset.Open(apkPrefix + name + ".xz")
		if err != nil {
			return fmt.Errorf("open %s.xz in assets: %w", name, err)
		}
		if err := extractXz(f); err != nil {
			log.Println("extractXz error:", err)
			return err
		}
	}

	o, err := os.Create(versionPath)
	if err != nil {
		return fmt.Errorf("create version: %v", err)
	}
	_, err = io.WriteString(o, assetVersion)
	closeErr := o.Close()
	if err != nil {
		return err
	}
	return closeErr
}

func extractAsset(i asset.File, path string) error {
	defer i.Close()
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	defer o.Close()
	_, err = io.Copy(o, i)
	if err == nil {
		log.Println("Extract >>", path)
	}
	return err
}
