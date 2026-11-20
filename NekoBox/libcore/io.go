package libcore

import (
	"archive/zip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/ulikunitz/xz"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
)

const (
	// Hard ceilings to mitigate xz/zip decompression bombs while comfortably
	// covering geoip.db, geosite.db, and yacd dashboard assets.
	maxDecompressFileSize int64 = 512 << 20 // 512 MiB per file
	maxUnzipTotalSize     int64 = 512 << 20 // 512 MiB per archive
	maxUnzipEntries             = 4096
)

func Unxz(archive string, path string) error {
	i, err := os.Open(archive)
	if err != nil {
		return err
	}
	defer i.Close()
	r, err := xz.NewReader(i)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	o, err := os.Create(path)
	if err != nil {
		return err
	}
	n, err := io.Copy(o, io.LimitReader(r, maxDecompressFileSize+1))
	closeErr := o.Close()
	if err != nil {
		_ = os.Remove(path)
		return err
	}
	if closeErr != nil {
		_ = os.Remove(path)
		return closeErr
	}
	if n > maxDecompressFileSize {
		_ = os.Remove(path)
		return fmt.Errorf("unxz output exceeds limit (%d bytes)", maxDecompressFileSize)
	}
	return nil
}

func Unzip(archive string, path string) error {
	r, err := zip.OpenReader(archive)
	if err != nil {
		return err
	}
	defer r.Close()

	if len(r.File) > maxUnzipEntries {
		return fmt.Errorf("zip contains too many entries: %d (max %d)", len(r.File), maxUnzipEntries)
	}

	err = os.MkdirAll(path, 0755)
	if err != nil {
		return err
	}

	destRoot := filepath.Clean(path)
	destClean := destRoot + string(os.PathSeparator)
	var totalWritten int64
	for _, file := range r.File {
		filePath := filepath.Join(path, file.Name)
		cleanFilePath := filepath.Clean(filePath)
		if !strings.HasPrefix(cleanFilePath, destClean) && cleanFilePath != destRoot {
			return fmt.Errorf("illegal file path in zip: %s", file.Name)
		}

		if file.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("symlinks are not allowed in zip: %s", file.Name)
		}

		if file.FileInfo().IsDir() {
			err = os.MkdirAll(cleanFilePath, 0755)
			if err != nil {
				return err
			}
			continue
		}

		if err = os.MkdirAll(filepath.Dir(cleanFilePath), 0755); err != nil {
			return err
		}

		newFile, err := os.Create(cleanFilePath)
		if err != nil {
			return err
		}

		zipFile, err := file.Open()
		if err != nil {
			newFile.Close()
			_ = os.Remove(cleanFilePath)
			return err
		}

		remainingTotal := maxUnzipTotalSize - totalWritten
		entryLimit := maxDecompressFileSize
		if remainingTotal < entryLimit {
			entryLimit = remainingTotal
		}
		if entryLimit < 0 {
			entryLimit = 0
		}

		n, copyErr := io.Copy(newFile, io.LimitReader(zipFile, entryLimit+1))
		var errs error
		errs = E.Errors(errs, copyErr)
		errs = E.Errors(errs, common.Close(zipFile, newFile))
		if errs != nil {
			_ = os.Remove(cleanFilePath)
			return errs
		}
		if n > entryLimit {
			_ = os.Remove(cleanFilePath)
			return fmt.Errorf("zip entry %s exceeds decompression size limit", file.Name)
		}
		totalWritten += n
	}

	return nil
}
