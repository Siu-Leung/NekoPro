package libcore

import (
	"bytes"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"

	geosites "github.com/sagernet/sing-box/common/geosite"
)

func writeTestGeositeDB(t *testing.T, dir string, domains map[string][]geosites.Item) string {
	t.Helper()
	var buf bytes.Buffer
	if err := geosites.Write(&buf, domains); err != nil {
		t.Fatalf("geosite.Write: %v", err)
	}
	path := filepath.Join(dir, geositeDat)
	if err := os.WriteFile(path, buf.Bytes(), 0644); err != nil {
		t.Fatalf("write geosite.db: %v", err)
	}
	return path
}

func TestGeositeConcurrentReadAndResetClosesFiles(t *testing.T) {
	dir := t.TempDir()
	writeTestGeositeDB(t, dir, map[string][]geosites.Item{
		"cn": {
			{Type: geosites.RuleTypeDomainSuffix, Value: "example.cn"},
		},
		"google": {
			{Type: geosites.RuleTypeDomain, Value: "google.com"},
		},
	})

	savedExternal := externalAssetsPath
	externalAssetsPath = dir
	t.Cleanup(func() {
		ResetGeositeCache()
		externalAssetsPath = savedExternal
	})
	ResetGeositeCache()

	// Warm up one open/reset cycle, then verify repeated resets do not leak fds.
	rules, err := getGeoSiteRules("cn")
	if err != nil || len(rules) != 1 {
		t.Fatalf("initial getGeoSiteRules(cn) = (%v, %v)", rules, err)
	}
	ResetGeositeCache()

	if runtime.GOOS == "linux" {
		beforeEntries, err := os.ReadDir("/proc/self/fd")
		if err != nil {
			t.Fatal(err)
		}
		for i := 0; i < 20; i++ {
			if _, err := getGeoSiteRules("cn"); err != nil {
				t.Fatalf("iteration %d: getGeoSiteRules: %v", i, err)
			}
			ResetGeositeCache()
		}
		afterEntries, err := os.ReadDir("/proc/self/fd")
		if err != nil {
			t.Fatal(err)
		}
		if len(afterEntries) > len(beforeEntries)+1 {
			t.Fatalf("geosite fd leak across resets: before=%d after=%d", len(beforeEntries), len(afterEntries))
		}
	}

	// Stress concurrent reads and resets under -race.
	var (
		wg    sync.WaitGroup
		errMu sync.Mutex
		errs  []error
	)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			code := "cn"
			if id%2 == 1 {
				code = "google"
			}
			for j := 0; j < 50; j++ {
				got, err := getGeoSiteRules(code)
				if err != nil || len(got) != 1 {
					errMu.Lock()
					errs = append(errs, err)
					errMu.Unlock()
					return
				}
			}
		}(i)
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for j := 0; j < 30; j++ {
			ResetGeositeCache()
		}
	}()
	wg.Wait()
	if len(errs) > 0 {
		t.Fatalf("concurrent geosite reads failed (first err: %v, count: %d)", errs[0], len(errs))
	}
}

func writeTestGeoipDB(t *testing.T, dir, code string) string {
	t.Helper()
	var buf bytes.Buffer
	// 1-node 24-bit search tree: left record -> data offset 0 (node_count + 16 = 17), right -> empty (1).
	buf.Write([]byte{0x00, 0x00, 0x11, 0x00, 0x00, 0x01})
	// 16-byte data section separator.
	buf.Write(make([]byte, 16))
	// Data section offset 0: UTF-8 string `code` (len < 29).
	buf.WriteByte(byte(2<<5 | len(code)))
	buf.WriteString(code)
	// Metadata marker + minimal metadata map (5 entries).
	buf.WriteString("\xab\xcd\xefMaxMind.com")
	writeStr := func(s string) {
		buf.WriteByte(byte(2<<5 | len(s)))
		buf.WriteString(s)
	}
	buf.WriteByte(byte(7<<5 | 5)) // map with 5 entries
	writeStr("node_count")
	buf.Write([]byte{byte(6<<5 | 1), 1}) // uint32 = 1
	writeStr("record_size")
	buf.Write([]byte{byte(5<<5 | 1), 24}) // uint16 = 24
	writeStr("ip_version")
	buf.Write([]byte{byte(5<<5 | 1), 4}) // uint16 = 4
	writeStr("binary_format_major_version")
	buf.Write([]byte{byte(5<<5 | 1), 2}) // uint16 = 2
	writeStr("binary_format_minor_version")
	buf.WriteByte(byte(5 << 5)) // uint16 = 0

	path := filepath.Join(dir, geoipDat)
	if err := os.WriteFile(path, buf.Bytes(), 0644); err != nil {
		t.Fatalf("write geoip.db: %v", err)
	}
	return path
}

func TestGeoipConcurrentLookupAndResetNoRace(t *testing.T) {
	dir := t.TempDir()
	writeTestGeoipDB(t, dir, "cn")

	savedExternal := externalAssetsPath
	externalAssetsPath = dir
	t.Cleanup(func() {
		ResetGeoipCache()
		externalAssetsPath = savedExternal
	})
	ResetGeoipCache()

	rules, err := getGeoIPRules("cn")
	if err != nil || len(rules) != 1 || len(rules[0].DefaultOptions.IPCIDR) == 0 {
		t.Fatalf("initial getGeoIPRules(cn) = (%v, %v)", rules, err)
	}

	// Stress concurrent mmap scans and resets under -race.
	var (
		wg    sync.WaitGroup
		errMu sync.Mutex
		errs  []error
	)
	for i := 0; i < 6; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 40; j++ {
				got, err := getGeoIPRules("cn")
				if err != nil || len(got) != 1 || len(got[0].DefaultOptions.IPCIDR) == 0 {
					errMu.Lock()
					errs = append(errs, err)
					errMu.Unlock()
					return
				}
			}
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for j := 0; j < 30; j++ {
			ResetGeoipCache()
		}
	}()
	wg.Wait()
	if len(errs) > 0 {
		t.Fatalf("concurrent geoip lookups failed (first err: %v, count: %d)", errs[0], len(errs))
	}
}
