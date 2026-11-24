package log

import (
	"context"
	"errors"
	"io"
	"os"
	"sync"
	"testing"
)

type recordingPlatformWriter struct {
	mu      sync.Mutex
	entries []Level
}

func (w *recordingPlatformWriter) WriteMessage(level Level, _ string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.entries = append(w.entries, level)
}

func (w *recordingPlatformWriter) snapshot() []Level {
	w.mu.Lock()
	defer w.mu.Unlock()
	out := make([]Level, len(w.entries))
	copy(out, w.entries)
	return out
}

func TestPlatformWriterRespectsFactoryLevel(t *testing.T) {
	for _, needObservable := range []bool{false, true} {
		pw := &recordingPlatformWriter{}
		factory := NewDefaultFactory(context.Background(), Formatter{}, io.Discard, "", pw, needObservable)
		factory.SetLevel(LevelWarn)

		logger := factory.NewLogger("test")
		// Pre-Start pending entries must also be filtered when Start() flushes them.
		logger.Debug("suppressed pre-start debug")
		logger.Warn("visible pre-start warn")

		if err := factory.Start(); err != nil {
			t.Fatalf("Start(needObservable=%v): %v", needObservable, err)
		}
		defer factory.Close()

		logger.Trace("suppressed trace")
		logger.Debug("suppressed debug")
		logger.Info("suppressed info")
		logger.Warn("visible warn")
		logger.Error("visible error")

		got := pw.snapshot()
		want := []Level{LevelWarn, LevelWarn, LevelError}
		if len(got) != len(want) {
			t.Fatalf("needObservable=%v: PlatformWriter got %v, want %v", needObservable, got, want)
		}
		for i := range want {
			if got[i] != want[i] {
				t.Fatalf("needObservable=%v: entry[%d] = %v, want %v", needObservable, i, got[i], want[i])
			}
		}
	}
}

func TestSubscribeReturnsErrorWhenObserverNil(t *testing.T) {
	factory := NewDefaultFactory(context.Background(), Formatter{}, io.Discard, "", nil, false)
	if err := factory.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer factory.Close()

	sub, done, err := factory.Subscribe()
	if !errors.Is(err, os.ErrInvalid) {
		t.Fatalf("Subscribe err = %v, want os.ErrInvalid", err)
	}
	if sub != nil || done != nil {
		t.Fatalf("expected nil subscription and done channel when observer disabled")
	}
	// UnSubscribe must also be a safe no-op when observer is nil.
	factory.UnSubscribe(nil)
}
