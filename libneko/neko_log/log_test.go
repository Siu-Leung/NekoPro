package neko_log

import (
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

func TestLogWriterRollsAtRuntimeAndKeepsThreeFiles(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "neko.log")
	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0644)
	if err != nil {
		t.Fatal(err)
	}
	writer := &logWriter{path: path, maxSize: 16, maxFiles: 3, file: file}
	for i := 0; i < 20; i++ {
		if _, err := writer.Write([]byte("0123456789")); err != nil {
			t.Fatal(err)
		}
	}
	_ = writer.Close()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) > 3 {
		t.Fatalf("got %d log files, want at most 3", len(entries))
	}
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			t.Fatal(err)
		}
		if info.Size() > 16 {
			t.Fatalf("%s grew to %d bytes", entry.Name(), info.Size())
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
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); _, _ = writer.Write([]byte(strings.Repeat("x", 100))) }()
	}
	wg.Wait()
	_ = writer.Close()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) > 3 {
		t.Fatalf("got %d log files", len(entries))
	}
}

type ioWriter = interface{ Write([]byte) (int, error) }
