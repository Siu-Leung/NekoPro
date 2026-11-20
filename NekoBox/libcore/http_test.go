package libcore

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestHTTPResponseGetContentRejectsBodyOverLimit(t *testing.T) {
	body := io.NopCloser(strings.NewReader("0123456789"))
	response := &httpResponse{Response: &http.Response{Body: body, Status: "200 OK"}}
	_, err := response.GetContentWithLimit(5)
	if !errors.Is(err, ErrHTTPResponseTooLarge) {
		t.Fatalf("expected ErrHTTPResponseTooLarge, got %v", err)
	}
}

func TestHTTPResponseWriteToRejectsBodyOverLimit(t *testing.T) {
	response := &httpResponse{Response: &http.Response{Body: io.NopCloser(strings.NewReader("0123456789")), Status: "200 OK"}}
	path := t.TempDir() + "/out"
	if err := response.WriteToWithLimit(path, 5); !errors.Is(err, ErrHTTPResponseTooLarge) {
		t.Fatalf("expected ErrHTTPResponseTooLarge, got %v", err)
	}
}

func TestHTTPExecuteTimeoutIsBoundedAndNeverReturnsNilNil(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer server.Close()
	client := NewHttpClient().(*httpClient)
	client.timeout = 30 * time.Millisecond
	request := client.NewRequest().(*httpRequest)
	if err := request.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	started := time.Now()
	response, err := request.Execute()
	if response != nil || err == nil {
		t.Fatalf("expected timeout error and nil response, got response=%v err=%v", response, err)
	}
	if time.Since(started) > time.Second {
		t.Fatalf("request was not bounded: %v", time.Since(started))
	}
}

func TestHTTPExecuteSlowBodyDoesNotTimeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		time.Sleep(120 * time.Millisecond)
		_, _ = w.Write([]byte("chunk-1-"))
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		time.Sleep(60 * time.Millisecond)
		_, _ = w.Write([]byte("chunk-2"))
	}))
	defer server.Close()

	for _, direct := range []bool{false, true} {
		client := NewHttpClient().(*httpClient)
		client.timeout = 60 * time.Millisecond
		if direct {
			client.TryH3Direct()
		}
		defer client.Close()

		request := client.NewRequest().(*httpRequest)
		if err := request.SetURL(server.URL); err != nil {
			t.Fatal(err)
		}
		response, err := request.Execute()
		if err != nil {
			t.Fatalf("direct=%v: Execute failed: %v", direct, err)
		}
		body, err := response.GetContent()
		if err != nil {
			t.Fatalf("direct=%v: GetContent failed on slow body: %v", direct, err)
		}
		if string(body) != "chunk-1-chunk-2" {
			t.Fatalf("direct=%v: body = %q, want %q", direct, string(body), "chunk-1-chunk-2")
		}
	}
}

func TestHTTPRequestAllowInsecureIsIsolatedPerRequest(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("ok"))
	}))
	defer server.Close()

	client := NewHttpClient().(*httpClient)
	defer client.Close()

	req1 := client.NewRequest()
	if err := req1.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	req1.AllowInsecure()
	resp1, err := req1.Execute()
	if err != nil {
		t.Fatalf("expected AllowInsecure request to succeed, got %v", err)
	}
	if _, err := resp1.GetContent(); err != nil {
		t.Fatal(err)
	}

	// Second request on the same HTTPClient without AllowInsecure must still verify TLS.
	req2 := client.NewRequest()
	if err := req2.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	resp2, err := req2.Execute()
	if err == nil || resp2 != nil {
		t.Fatalf("expected second request without AllowInsecure to fail TLS verification, got resp=%v err=%v", resp2, err)
	}
}

func TestHTTPExecuteWithoutURLReturnsError(t *testing.T) {
	client := NewHttpClient()
	defer client.Close()
	resp, err := client.NewRequest().Execute()
	if resp != nil || err == nil {
		t.Fatalf("expected error when URL is unset, got resp=%v err=%v", resp, err)
	}
}

func TestRaceDirectRequestsPanicDoesNotDeadlock(t *testing.T) {
	client := NewHttpClient().(*httpClient)
	client.timeout = 200 * time.Millisecond
	req := client.NewRequest().(*httpRequest)

	done := make(chan error, 1)
	go func() {
		resp, err := req.raceDirectRequests([]requestFunc{
			func(ctx context.Context) (*http.Response, io.Closer, error) {
				panic("simulated transport panic")
			},
			func(ctx context.Context) (*http.Response, io.Closer, error) {
				return nil, nil, errors.New("secondary failed")
			},
		})
		if resp != nil {
			done <- errors.New("expected nil response")
			return
		}
		done <- err
	}()

	select {
	case err := <-done:
		if err == nil || !strings.Contains(err.Error(), "simulated transport panic") {
			t.Fatalf("expected panic error, got %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("raceDirectRequests deadlocked after candidate panic")
	}
}

func TestRaceDirectRequestsWinnerDoesNotBlockOnSlowLoser(t *testing.T) {
	client := NewHttpClient().(*httpClient)
	client.timeout = 500 * time.Millisecond
	req := client.NewRequest().(*httpRequest)

	var loserCanceled atomic.Bool
	started := time.Now()
	resp, err := req.raceDirectRequests([]requestFunc{
		func(ctx context.Context) (*http.Response, io.Closer, error) {
			return &http.Response{
				StatusCode: http.StatusOK,
				Status:     "200 OK",
				Body:       io.NopCloser(strings.NewReader("fast-winner")),
			}, nil, nil
		},
		func(ctx context.Context) (*http.Response, io.Closer, error) {
			<-ctx.Done()
			loserCanceled.Store(true)
			time.Sleep(300 * time.Millisecond)
			return nil, nil, ctx.Err()
		},
	})
	elapsed := time.Since(started)
	if err != nil {
		t.Fatalf("expected winner response, got error: %v", err)
	}
	if elapsed > 150*time.Millisecond {
		t.Fatalf("winner blocked waiting for slow loser: %v", elapsed)
	}
	body, err := resp.GetContent()
	if err != nil {
		t.Fatalf("GetContent failed: %v", err)
	}
	if string(body) != "fast-winner" {
		t.Fatalf("body = %q, want %q", string(body), "fast-winner")
	}
	deadline := time.Now().Add(time.Second)
	for !loserCanceled.Load() && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if !loserCanceled.Load() {
		t.Fatal("losing candidate context was not canceled")
	}
}

func TestHTTPExecuteClosesErrorBody(t *testing.T) {
	closed := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
		_, _ = w.Write([]byte("busy"))
	}))
	defer server.Close()
	client := NewHttpClient().(*httpClient)
	client.h1h2Transport.RegisterProtocol("test", roundTripperFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{StatusCode: http.StatusServiceUnavailable, Status: "503 Service Unavailable", Body: closeNotifyReader{Reader: strings.NewReader("busy"), closed: closed}}, nil
	}))
	request := client.NewRequest().(*httpRequest)
	if err := request.SetURL("test://example"); err != nil {
		t.Fatal(err)
	}
	_, err := request.Execute()
	if err == nil {
		t.Fatal("expected 503 error")
	}
	select {
	case <-closed:
	case <-time.After(time.Second):
		t.Fatal("error response body was not closed")
	}
}

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

type closeNotifyReader struct {
	io.Reader
	closed chan struct{}
}

func (r closeNotifyReader) Close() error { close(r.closed); return nil }

func TestHTTPForceSocks5NeverFallsBackToDirect(t *testing.T) {
	var directHits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		directHits.Add(1)
		_, _ = w.Write([]byte("leaked"))
	}))
	defer server.Close()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	closedPort := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()

	client := NewHttpClient().(*httpClient)
	client.timeout = 2 * time.Second
	client.ForceSocks5(int32(closedPort))
	client.TryH3Direct()
	defer client.Close()

	req := client.NewRequest()
	if err := req.SetURL(server.URL); err != nil {
		t.Fatal(err)
	}
	resp, err := req.Execute()
	if err == nil || resp != nil {
		t.Fatalf("expected ForceSocks5 error on unreachable proxy, got resp=%v err=%v", resp, err)
	}
	if directHits.Load() != 0 {
		t.Fatalf("ForceSocks5 leaked %d direct request(s)", directHits.Load())
	}
}
