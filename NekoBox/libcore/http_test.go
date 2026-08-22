package libcore

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
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
