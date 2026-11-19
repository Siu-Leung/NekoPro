package neko_log

import (
	"fmt"
	"io"
	"log"
	"os"
	"runtime"
	"sync"

	"github.com/matsuridayo/libneko/neko_common"
	"github.com/matsuridayo/libneko/syscallw"
)

var LogWriter *logWriter
var LogWriterDisable = false
var TruncateOnStart = true
var NB4AGuiLogWriter io.Writer
var MaxLogFiles = 3

func SetupLog(maxSize int, path string) (err error) {
	if LogWriter != nil {
		return
	}
	if maxSize <= 0 {
		maxSize = 16 << 20
	}

	redirectStderr := false
	var f *os.File
	f, err = os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0644)
	if err == nil {
		fd := int(f.Fd())
		if TruncateOnStart {
			_ = syscallw.Flock(fd, syscallw.LOCK_EX)
			if size, seekErr := f.Seek(0, io.SeekEnd); seekErr == nil && size > int64(maxSize) {
				_, _ = f.Seek(-int64(maxSize), io.SeekEnd)
				oldBytes, readErr := io.ReadAll(f)
				if readErr == nil {
					if runtime.GOOS == "windows" {
						_ = f.Close()
						_ = os.Remove(path)
						f, err = os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0644)
						if err == nil {
							fd = int(f.Fd())
						}
					} else {
						err = f.Truncate(0)
						if err == nil {
							_, _ = f.Seek(0, io.SeekStart)
						}
					}
					if err == nil {
						_, err = f.Write(oldBytes)
					}
				}
			}
			if f != nil {
				_ = syscallw.Flock(fd, syscallw.LOCK_UN)
			}
		}
		if err == nil && neko_common.RunMode == neko_common.RunMode_NekoBoxForAndroid {
			// redirect stderr
			if int(f.Fd()) != int(os.Stderr.Fd()) {
				_ = syscallw.Dup3(int(f.Fd()), int(os.Stderr.Fd()), 0)
			}
			redirectStderr = true
		}
	}

	if err != nil {
		if f != nil {
			_ = f.Close()
		}
		err = fmt.Errorf("error open log: %v", err)
		log.Println(err)
		return err
	}

	LogWriter = &logWriter{
		path:           path,
		maxSize:        int64(maxSize),
		maxFiles:       MaxLogFiles,
		file:           f,
		redirectStderr: redirectStderr,
	}
	if neko_common.RunMode == neko_common.RunMode_NekoBoxForAndroid {
		LogWriter.writers = []io.Writer{NB4AGuiLogWriter, f}
	} else {
		LogWriter.writers = []io.Writer{os.Stdout, f}
	}
	// setup std log
	log.SetFlags(log.LstdFlags | log.LUTC)
	log.SetOutput(LogWriter)

	return
}

type logWriter struct {
	writers        []io.Writer
	path           string
	maxSize        int64
	maxFiles       int
	file           *os.File
	redirectStderr bool
	mu             sync.Mutex
	closed         bool
}

func (w *logWriter) Write(p []byte) (int, error) {
	if w == nil {
		return len(p), nil
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return 0, os.ErrClosed
	}
	if LogWriterDisable {
		return len(p), nil
	}

	wroteFile := false
	for _, dst := range w.writers {
		if dst == nil {
			continue
		}
		if w.file != nil {
			if f, ok := dst.(*os.File); ok && f == w.file {
				if err := w.writeFileLocked(p); err != nil {
					return 0, err
				}
				wroteFile = true
				continue
			}
		}
		n, err := dst.Write(p)
		if err != nil {
			return n, err
		}
		if n != len(p) {
			return n, io.ErrShortWrite
		}
	}

	if !wroteFile && (w.file != nil || w.path != "") {
		if err := w.writeFileLocked(p); err != nil {
			return 0, err
		}
	}

	return len(p), nil
}

func (w *logWriter) replaceWriterLocked(oldFile, newFile *os.File) []int {
	var indices []int
	if oldFile == nil {
		return indices
	}
	for i, dst := range w.writers {
		if dst == oldFile {
			if newFile != nil {
				w.writers[i] = newFile
			} else {
				w.writers[i] = nil
			}
			indices = append(indices, i)
		}
	}
	return indices
}

func (w *logWriter) dupStderrLocked(file *os.File) {
	if !w.redirectStderr || file == nil {
		return
	}
	if int(file.Fd()) != int(os.Stderr.Fd()) {
		_ = syscallw.Dup3(int(file.Fd()), int(os.Stderr.Fd()), 0)
	}
}

func (w *logWriter) writeFileLocked(p []byte) error {
	if w.file == nil {
		if w.path == "" {
			return os.ErrInvalid
		}
		file, err := os.OpenFile(w.path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0644)
		if err != nil {
			return err
		}
		w.file = file
		w.dupStderrLocked(file)
	}
	if w.maxSize <= 0 {
		_, err := w.file.Write(p)
		return err
	}
	for len(p) > 0 {
		size, err := w.file.Seek(0, io.SeekEnd)
		if err != nil {
			return err
		}
		if size >= w.maxSize {
			if err := w.rotateLocked(); err != nil {
				return err
			}
			size = 0
		}
		part := p
		if remaining := w.maxSize - size; int64(len(part)) > remaining {
			part = part[:remaining]
		}
		n, err := w.file.Write(part)
		if err != nil {
			return err
		}
		if n != len(part) {
			return io.ErrShortWrite
		}
		p = p[n:]
	}
	return nil
}

func (w *logWriter) rotateLocked() error {
	oldFile := w.file
	replacedIdx := w.replaceWriterLocked(oldFile, nil)
	w.file = nil
	if oldFile != nil {
		if err := oldFile.Close(); err != nil {
			return err
		}
	}
	if w.maxFiles < 1 {
		w.maxFiles = 1
	}
	if w.maxFiles == 1 {
		if err := os.Remove(w.path); err != nil && !os.IsNotExist(err) {
			return err
		}
	} else {
		for i := w.maxFiles - 2; i >= 1; i-- {
			oldPath := fmt.Sprintf("%s.%d", w.path, i)
			newPath := fmt.Sprintf("%s.%d", w.path, i+1)
			if err := os.Rename(oldPath, newPath); err != nil && !os.IsNotExist(err) {
				return err
			}
		}
		if err := os.Rename(w.path, w.path+".1"); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	file, err := os.OpenFile(w.path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0644)
	if err != nil {
		return err
	}
	w.file = file
	for _, i := range replacedIdx {
		w.writers[i] = file
	}
	w.dupStderrLocked(file)
	return nil
}

func (w *logWriter) Truncate() {
	if w == nil {
		return
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed || w.file == nil {
		return
	}
	_ = w.file.Truncate(0)
	_, _ = w.file.Seek(0, io.SeekStart)
}

func (w *logWriter) Close() error {
	if w == nil {
		return nil
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return nil
	}
	w.closed = true
	if w.file != nil {
		oldFile := w.file
		w.file = nil
		w.replaceWriterLocked(oldFile, nil)
		return oldFile.Close()
	}
	return nil
}
