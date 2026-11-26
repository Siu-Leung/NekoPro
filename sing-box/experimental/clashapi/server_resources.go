package clashapi

import (
	"archive/zip"
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/sing/common/ntp"
	"github.com/sagernet/sing/service/filemanager"
)

func (s *Server) checkAndDownloadExternalUI() {
	if s.externalUI == "" {
		return
	}
	// Fallback check for Android relative paths: if s.externalUI doesn't exist, check common fallback paths
	if _, err := os.Stat(s.externalUI); err != nil {
		fallbacks := []string{
			"metacubexd",
			"../files/metacubexd",
			"yacd",
			"../files/yacd",
		}
		for _, fb := range fallbacks {
			if fi, err := os.Stat(fb); err == nil && fi.IsDir() {
				s.externalUI = fb
				break
			}
		}
	}
	entries, err := filemanager.ReadDir(s.ctx, s.externalUI)
	if err != nil {
		filemanager.MkdirAll(s.ctx, s.externalUI, 0o755)
	}
	if len(entries) == 0 {
		s.logger.Warn("external ui folder empty at: ", s.externalUI)
	}
}

// resolveBuiltInPanelDir locates an extracted built-in dashboard directory (`metacubexd` or `yacd`)
// adjacent to `externalUI` (e.g. `<filesDir>/metacubexd` and `<filesDir>/yacd`) so both panels can
// be served simultaneously on isolated routes without restarting the core or colliding on `/ui/` SW scope.
func resolveBuiltInPanelDir(externalUI string, panelName string) string {
	if panelName == "" || strings.ContainsAny(panelName, `/\`) || panelName == "." || panelName == ".." {
		return ""
	}
	var candidates []string
	if trimmed := strings.TrimSpace(externalUI); trimmed != "" {
		cleaned := filepath.Clean(trimmed)
		if filepath.Base(cleaned) == panelName {
			candidates = append(candidates, cleaned)
		}
		if parent := filepath.Dir(cleaned); parent != "" {
			candidates = append(candidates, filepath.Join(parent, panelName))
		}
	}
	candidates = append(candidates, panelName, filepath.Join("../files", panelName))
	for _, candidate := range candidates {
		if fi, err := os.Stat(candidate); err == nil && fi.IsDir() {
			return candidate
		}
	}
	return ""
}

func (s *Server) downloadExternalUI() error {
	var downloadURL string
	if s.externalUIDownloadURL != "" {
		downloadURL = s.externalUIDownloadURL
	} else {
		downloadURL = "https://github.com/MetaCubeX/Yacd-meta/archive/gh-pages.zip"
	}
	var detour adapter.Outbound
	if s.externalUIDownloadDetour != "" {
		outbound, loaded := s.outbound.Outbound(s.externalUIDownloadDetour)
		if !loaded {
			return E.New("detour outbound not found: ", s.externalUIDownloadDetour)
		}
		detour = outbound
	} else {
		outbound := s.outbound.Default()
		detour = outbound
	}
	s.logger.Info("downloading external ui using outbound/", detour.Type(), "[", detour.Tag(), "]")
	httpClient := &http.Client{
		Transport: &http.Transport{
			ForceAttemptHTTP2:   true,
			TLSHandshakeTimeout: C.TCPTimeout,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return detour.DialContext(ctx, network, M.ParseSocksaddr(addr))
			},
			TLSClientConfig: &tls.Config{
				Time:    ntp.TimeFuncFromContext(s.ctx),
				RootCAs: adapter.RootPoolFromContext(s.ctx),
			},
		},
	}
	defer httpClient.CloseIdleConnections()
	response, err := httpClient.Get(downloadURL)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return E.New("download external ui failed: ", response.Status)
	}
	err = s.downloadZIP(response.Body, s.externalUI)
	if err != nil {
		removeAllInDirectory(s.ctx, s.externalUI)
	}
	return err
}

const (
	maxExternalUICompressedBytes int64 = 128 << 20
	maxExternalUIFileBytes       int64 = 64 << 20
	maxExternalUITotalBytes      int64 = 128 << 20
	maxExternalUIZipEntries            = 4096
)

func (s *Server) downloadZIP(body io.Reader, output string) error {
	targetRoot := output
	if strings.TrimSpace(targetRoot) == "" {
		targetRoot = s.externalUI
	}
	baseDir := filepath.Clean(targetRoot)
	if baseDir == "" || baseDir == "." || baseDir == string(os.PathSeparator) {
		return E.New("invalid external UI destination directory: ", targetRoot)
	}
	baseDirPrefix := baseDir + string(os.PathSeparator)

	tempFile, err := filemanager.CreateTemp(s.ctx, "external-ui.zip")
	if err != nil {
		return err
	}
	defer filemanager.Remove(s.ctx, tempFile.Name())
	copiedCompressed, err := io.Copy(tempFile, io.LimitReader(body, maxExternalUICompressedBytes+1))
	closeErr := tempFile.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if copiedCompressed > maxExternalUICompressedBytes {
		return E.New("external UI archive exceeds compressed size limit")
	}

	reader, err := zip.OpenReader(tempFile.Name())
	if err != nil {
		return err
	}
	defer reader.Close()
	if len(reader.File) > maxExternalUIZipEntries {
		return E.New("external UI archive contains too many entries: ", len(reader.File))
	}

	trimDir := zipIsInSingleDirectory(reader.File)
	var totalExtracted int64
	for _, file := range reader.File {
		// WHY: Reject symlinks before directory checks so a crafted archive cannot
		// plant a directory symlink inside externalUI and write subsequent files outside baseDir.
		if file.Mode()&os.ModeSymlink != 0 {
			return E.New("symlink entry is not allowed in external UI archive: ", file.Name)
		}
		normalizedName := strings.ReplaceAll(file.Name, "\\", "/")
		rawSegments := strings.Split(normalizedName, "/")
		for _, seg := range rawSegments {
			if seg == ".." {
				return E.New("path traversal entry in external UI archive: ", file.Name)
			}
		}
		if file.FileInfo().IsDir() {
			continue
		}
		pathElements := rawSegments
		if trimDir && len(pathElements) > 0 {
			pathElements = pathElements[1:]
		}
		cleanElements := make([]string, 0, len(pathElements))
		for _, elem := range pathElements {
			if elem != "" && elem != "." {
				cleanElements = append(cleanElements, elem)
			}
		}
		if len(cleanElements) == 0 {
			continue
		}

		targetPath := filepath.Clean(filepath.Join(append([]string{baseDir}, cleanElements...)...))
		if !strings.HasPrefix(targetPath, baseDirPrefix) {
			return E.New("illegal file path in external UI archive: ", file.Name)
		}
		if file.UncompressedSize64 > uint64(maxExternalUIFileBytes) {
			return E.New("external UI entry exceeds file size limit: ", file.Name)
		}

		remainingTotal := maxExternalUITotalBytes - totalExtracted
		if remainingTotal <= 0 {
			return E.New("external UI archive exceeds cumulative extraction limit")
		}
		entryLimit := min(maxExternalUIFileBytes, remainingTotal)

		saveDirectory := filepath.Dir(targetPath)
		if !strings.HasPrefix(saveDirectory+string(os.PathSeparator), baseDirPrefix) {
			return E.New("illegal directory path in external UI archive: ", file.Name)
		}
		err = filemanager.MkdirAll(s.ctx, saveDirectory, 0o755)
		if err != nil {
			return err
		}
		written, err := downloadZIPEntryBounded(s.ctx, file, targetPath, entryLimit)
		if err != nil {
			return err
		}
		totalExtracted += written
	}
	return nil
}

func downloadZIPEntryBounded(ctx context.Context, zipFile *zip.File, savePath string, maxBytes int64) (int64, error) {
	saveFile, err := filemanager.Create(ctx, savePath)
	if err != nil {
		return 0, err
	}
	reader, err := zipFile.Open()
	if err != nil {
		_ = saveFile.Close()
		_ = filemanager.Remove(ctx, savePath)
		return 0, err
	}
	written, copyErr := io.Copy(saveFile, io.LimitReader(reader, maxBytes+1))
	closeErr := common.Close(reader, saveFile)
	if copyErr != nil {
		_ = filemanager.Remove(ctx, savePath)
		return 0, copyErr
	}
	if closeErr != nil {
		_ = filemanager.Remove(ctx, savePath)
		return 0, closeErr
	}
	if written > maxBytes {
		_ = filemanager.Remove(ctx, savePath)
		return 0, E.New("external UI entry exceeds extraction byte limit: ", zipFile.Name)
	}
	return written, nil
}

func removeAllInDirectory(ctx context.Context, directory string) {
	dirEntries, err := filemanager.ReadDir(ctx, directory)
	if err != nil {
		return
	}
	for _, dirEntry := range dirEntries {
		filemanager.RemoveAll(ctx, filepath.Join(directory, dirEntry.Name()))
	}
}

func zipIsInSingleDirectory(files []*zip.File) bool {
	var singleDirectory string
	for _, file := range files {
		if file.FileInfo().IsDir() {
			continue
		}
		pathElements := strings.Split(file.Name, "/")
		if len(pathElements) == 0 {
			return false
		}
		if singleDirectory == "" {
			singleDirectory = pathElements[0]
		} else if singleDirectory != pathElements[0] {
			return false
		}
	}
	return true
}
