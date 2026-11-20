package neko_log

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"testing"
)

func listLogFiles(t *testing.T, dir string) []os.DirEntry {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var out []os.DirEntry
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".lock") {
			continue
		}
		out = append(out, entry)
	}
	return out
}

func TestLogWriterRollsAtRuntimeAndKeepsThreeFiles(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "neko.log")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0644)
	if err != nil {
		t.Fatal(err)
	}
	var mirror bytes.Buffer
	writer := &logWriter{
		path:     path,
		maxSize:  16,
		maxFiles: 3,
		file:     file,
		writers:  []io.Writer{&mirror, file},
	}
	for i := 0; i < 20; i++ {
		if _, err := writer.Write([]byte("0123456789")); err != nil {
			t.Fatalf("write %d failed: %v", i, err)
		}
	}
	if writer.writers[1] != writer.file {
		t.Fatalf("writers slice entry was not updated after rotation")
	}
	if mirror.Len() != 200 {
		t.Fatalf("mirror writer received %d bytes, want 200", mirror.Len())
	}
	_ = writer.Close()
	entries := listLogFiles(t, dir)
	if len(entries) != 3 {
		t.Fatalf("got %d log files, want 3", len(entries))
	}
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			t.Fatal(err)
		}
		if info.Size() == 0 || info.Size() > 16 {
			t.Fatalf("%s size = %d bytes, want in (0, 16]", entry.Name(), info.Size())
		}
	}
}

func TestLogWriterConcurrentWritesAreBounded(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "neko.log")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0644)
	if err != nil {
		t.Fatal(err)
	}
	writer := &logWriter{path: path, maxSize: 1024, maxFiles: 3, file: file, writers: []io.Writer{file}}
	var (
		wg    sync.WaitGroup
		errMu sync.Mutex
		errs  []error
	)
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := writer.Write([]byte(strings.Repeat("x", 100))); err != nil {
				errMu.Lock()
				errs = append(errs, err)
				errMu.Unlock()
			}
		}()
	}
	wg.Wait()
	_ = writer.Close()
	if len(errs) > 0 {
		t.Fatalf("concurrent writes failed (first error: %v, total: %d)", errs[0], len(errs))
	}
	entries := listLogFiles(t, dir)
	if len(entries) > 3 {
		t.Fatalf("got %d log files, want at most 3", len(entries))
	}
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			t.Fatal(err)
		}
		if info.Size() > 1024 {
			t.Fatalf("%s grew to %d bytes", entry.Name(), info.Size())
		}
	}
}

func TestLogWriterMultiInstanceSyncAfterRotation(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "neko.log")
	f1, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0644)
	if err != nil {
		t.Fatal(err)
	}
	f2, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0644)
	if err != nil {
		_ = f1.Close()
		t.Fatal(err)
	}
	w1 := &logWriter{path: path, maxSize: 32, maxFiles: 3, file: f1, writers: []io.Writer{f1}}
	w2 := &logWriter{path: path, maxSize: 32, maxFiles: 3, file: f2, writers: []io.Writer{f2}}
	defer w1.Close()
	defer w2.Close()

	var wg sync.WaitGroup
	for _, w := range []*logWriter{w1, w2} {
		wg.Add(1)
		go func(lw *logWriter) {
			defer wg.Done()
			for i := 0; i < 20; i++ {
				if _, err := lw.Write([]byte("0123456789\n")); err != nil {
					t.Errorf("write failed: %v", err)
					return
				}
			}
		}(w)
	}
	wg.Wait()

	// Final write from w2 must land in active neko.log (reopened after any rotation by w1).
	if _, err := w2.Write([]byte("TAIL\n")); err != nil {
		t.Fatal(err)
	}
	active, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(active), "TAIL\n") {
		t.Fatalf("active log = %q, expected TAIL after multi-writer rotation", string(active))
	}
	entries := listLogFiles(t, dir)
	if len(entries) > 3 {
		t.Fatalf("got %d log files, want at most 3", len(entries))
	}
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			t.Fatal(err)
		}
		if info.Size() > 32 {
			t.Fatalf("%s grew to %d bytes, want <= 32", entry.Name(), info.Size())
		}
	}
}

func TestLogWriterTruncateAfterRotation(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "neko.log")
	otherPath := filepath.Join(dir, "other.log")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0644)
	if err != nil {
		t.Fatal(err)
	}
	otherFile, err := os.OpenFile(otherPath, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0644)
	if err != nil {
		_ = file.Close()
		t.Fatal(err)
	}
	defer otherFile.Close()

	writer := &logWriter{
		path:     path,
		maxSize:  16,
		maxFiles: 3,
		file:     file,
		writers:  []io.Writer{otherFile, file},
	}
	defer writer.Close()

	// Force rotation.
	for i := 0; i < 4; i++ {
		if _, err := writer.Write([]byte("12345678")); err != nil {
			t.Fatal(err)
		}
	}
	writer.Truncate()

	// Truncate must only truncate the active log file, never other *os.File entries.
	if data, err := os.ReadFile(path); err != nil || len(data) != 0 {
		t.Fatalf("active log file after Truncate = %q (err=%v), want empty", string(data), err)
	}
	if data, err := os.ReadFile(otherPath); err != nil || len(data) != 32 {
		t.Fatalf("other file length = %d (err=%v), want 32 (untouched)", len(data), err)
	}

	if _, err := writer.Write([]byte("after")); err != nil {
		t.Fatalf("write after Truncate failed: %v", err)
	}
	if data, err := os.ReadFile(path); err != nil || string(data) != "after" {
		t.Fatalf("active log file after post-truncate write = %q (err=%v), want %q", string(data), err, "after")
	}
}

func TestLogWriterRotationRedupsStderr(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("linux-only stderr dup3 test")
	}
	savedStderr, err := syscall.Dup(int(os.Stderr.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = syscall.Dup3(savedStderr, int(os.Stderr.Fd()), 0)
		_ = syscall.Close(savedStderr)
	}()

	dir := t.TempDir()
	path := filepath.Join(dir, "neko.log")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0644)
	if err != nil {
		t.Fatal(err)
	}
	if err := syscall.Dup3(int(file.Fd()), int(os.Stderr.Fd()), 0); err != nil {
		_ = file.Close()
		t.Fatal(err)
	}
	writer := &logWriter{
		path:           path,
		maxSize:        16,
		maxFiles:       3,
		file:           file,
		redirectStderr: true,
		writers:        []io.Writer{file},
	}
	defer writer.Close()

	// Fill first file and trigger rotation onto a new inode.
	if _, err := writer.Write([]byte("0123456789abcdefX")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stderr.Write([]byte("STDERR")); err != nil {
		t.Fatal(err)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), "STDERR") {
		t.Fatalf("rotated neko.log = %q, expected stderr output after rotation", string(data))
	}
}
