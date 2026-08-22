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

	var f *os.File
	f, err = os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0644)
	if err == nil {
		fd := int(f.Fd())
		if TruncateOnStart {
			syscallw.Flock(fd, syscallw.LOCK_EX)
			if size, _ := f.Seek(0, io.SeekEnd); size > int64(maxSize) {
				_, _ = f.Seek(-int64(maxSize), io.SeekEnd)
				oldBytes, err := io.ReadAll(f)
				if err == nil {
					if runtime.GOOS == "windows" {
						f.Close()
						os.Remove(path)
						f, err = os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0644)
					} else {
						err = f.Truncate(0)
					}
					if err == nil {
						_, err = f.Write(oldBytes)
					}
				}
			}
			syscallw.Flock(fd, syscallw.LOCK_UN)
		}
		if neko_common.RunMode == neko_common.RunMode_NekoBoxForAndroid {
			// redirect stderr
			syscallw.Dup3(fd, int(os.Stderr.Fd()), 0)
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

	LogWriter = &logWriter{path: path, maxSize: int64(maxSize), maxFiles: MaxLogFiles, file: f}
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
	writers  []io.Writer
	path     string
	maxSize  int64
	maxFiles int
	file     *os.File
	mu       sync.Mutex
	closed   bool
}

func (w *logWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return 0, os.ErrClosed
	}
	if LogWriterDisable {
		return len(p), nil
	}

	for _, dst := range w.writers {
		if dst == nil {
			continue
		}
		if f, ok := dst.(*os.File); ok && (w.file == nil || f == w.file) {
			if w.file == nil {
				w.file = f
			}
			if err := w.writeFileLocked(p); err != nil {
				return 0, err
			}
		} else {
			n, err := dst.Write(p)
			if err != nil {
				return n, err
			}
			if n != len(p) {
				return n, io.ErrShortWrite
			}
		}
	}

	return len(p), nil
}

func (w *logWriter) writeFileLocked(p []byte) error {
	if w.file == nil {
		return os.ErrInvalid
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
	if err := w.file.Close(); err != nil {
		return err
	}
	if w.maxFiles < 1 {
		w.maxFiles = 1
	}
	for i := w.maxFiles - 1; i >= 1; i-- {
		oldPath := fmt.Sprintf("%s.%d", w.path, i)
		newPath := fmt.Sprintf("%s.%d", w.path, i+1)
		if err := os.Rename(oldPath, newPath); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	if err := os.Rename(w.path, w.path+".1"); err != nil && !os.IsNotExist(err) {
		return err
	}
	file, err := os.OpenFile(w.path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0644)
	if err != nil {
		return err
	}
	w.file = file
	return nil
}

func (w *logWriter) Truncate() {
	w.mu.Lock()
	defer w.mu.Unlock()
	for _, dst := range w.writers {
		if dst == nil {
			continue
		}
		if f, ok := dst.(*os.File); ok {
			_ = f.Truncate(0)
		}
	}
}

func (w *logWriter) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.closed {
		return nil
	}
	w.closed = true
	if w.file != nil {
		return w.file.Close()
	}
	return nil
}
