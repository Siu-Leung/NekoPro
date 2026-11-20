package libcore

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"
)

func createTestZip(t *testing.T, entries []struct {
	name string
	mode os.FileMode
	body []byte
}) string {
	t.Helper()
	zipPath := filepath.Join(t.TempDir(), "test.zip")
	f, err := os.Create(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(f)
	for _, e := range entries {
		hdr := &zip.FileHeader{Name: e.name, Method: zip.Deflate}
		if e.mode != 0 {
			hdr.SetMode(e.mode)
		}
		w, err := zw.CreateHeader(hdr)
		if err != nil {
			t.Fatal(err)
		}
		if len(e.body) > 0 {
			if _, err := w.Write(e.body); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	return zipPath
}

func TestUnzipCreatesImplicitParentDirs(t *testing.T) {
	zipPath := createTestZip(t, []struct {
		name string
		mode os.FileMode
		body []byte
	}{
		{name: "nested/dir/hello.txt", body: []byte("world")},
	})
	dest := filepath.Join(t.TempDir(), "out")
	if err := Unzip(zipPath, dest); err != nil {
		t.Fatalf("Unzip failed: %v", err)
	}
	data, err := os.ReadFile(filepath.Join(dest, "nested", "dir", "hello.txt"))
	if err != nil || string(data) != "world" {
		t.Fatalf("extracted content = %q (err=%v), want %q", string(data), err, "world")
	}
}

func TestUnzipRejectsZipSlip(t *testing.T) {
	zipPath := createTestZip(t, []struct {
		name string
		mode os.FileMode
		body []byte
	}{
		{name: "../escape.txt", body: []byte("bad")},
	})
	dest := filepath.Join(t.TempDir(), "out")
	if err := Unzip(zipPath, dest); err == nil {
		t.Fatalf("expected Zip Slip entry to be rejected")
	}
}

func TestUnzipRejectsSymlinks(t *testing.T) {
	zipPath := createTestZip(t, []struct {
		name string
		mode os.FileMode
		body []byte
	}{
		{name: "link", mode: os.ModeSymlink | 0777, body: []byte("/etc/passwd")},
	})
	dest := filepath.Join(t.TempDir(), "out")
	if err := Unzip(zipPath, dest); err == nil {
		t.Fatalf("expected symlink entry to be rejected")
	}
}
