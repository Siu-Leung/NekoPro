package clashapi

import (
	"archive/zip"
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/ws"
)

func newProtectedTestHandler(secret string, controllerPort string) http.Handler {
	okHandler := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	return authenticationWithPort(secret, controllerPort)(okHandler)
}

func TestAuthenticationBearerAndWebSocketToken(t *testing.T) {
	const secret = "s3cr3t-clash-token"
	handler := authentication(secret)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	t.Run("valid bearer accepted", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:9090/proxies", nil)
		req.Header.Set("Authorization", "Bearer "+secret)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
	})

	t.Run("invalid bearer rejected", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:9090/proxies", nil)
		req.Header.Set("Authorization", "Bearer wrong-secret")
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want 401", rec.Code)
		}
	})

	t.Run("GET websocket token accepted case-insensitively", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:9090/logs?token="+secret, nil)
		req.Header.Set("Upgrade", "WebSocket")
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
	})

	t.Run("PATCH and POST spoofing Upgrade websocket with query token rejected", func(t *testing.T) {
		for _, method := range []string{http.MethodPatch, http.MethodPost, http.MethodPut, http.MethodDelete} {
			req := httptest.NewRequest(method, "http://127.0.0.1:9090/configs?token="+secret, strings.NewReader(`{}`))
			req.Header.Set("Upgrade", "websocket")
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("%s with spoofed Upgrade status = %d, want 401", method, rec.Code)
			}
		}
	})

	t.Run("plain GET without Upgrade header rejects query token", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:9090/proxies?token="+secret, nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want 401", rec.Code)
		}
	})
}

func TestAuthenticationSameOriginCookieAndCSRFProtection(t *testing.T) {
	const secret = "cookie-secret-9090"
	handler := newProtectedTestHandler(secret, "9090")

	attachCookie := func(req *http.Request, val string) {
		req.AddCookie(&http.Cookie{Name: ClashAuthCookieName, Value: val, HttpOnly: true})
	}

	t.Run("same-origin loopback GET and PUT accepted", func(t *testing.T) {
		getReq := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:9090/proxies", nil)
		attachCookie(getReq, secret)
		getReq.Header.Set("Sec-Fetch-Site", "same-origin")
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, getReq)
		if rec.Code != http.StatusOK {
			t.Fatalf("same-origin GET status = %d, want 200", rec.Code)
		}

		putReq := httptest.NewRequest(http.MethodPut, "http://127.0.0.1:9090/proxies/GLOBAL", strings.NewReader(`{"name":"NodeA"}`))
		attachCookie(putReq, secret)
		putReq.Header.Set("Origin", "http://127.0.0.1:9090")
		putReq.Header.Set("Sec-Fetch-Site", "same-origin")
		putRec := httptest.NewRecorder()
		handler.ServeHTTP(putRec, putReq)
		if putRec.Code != http.StatusOK {
			t.Fatalf("same-origin PUT status = %d, want 200", putRec.Code)
		}

		wsReq := httptest.NewRequest(http.MethodGet, "http://localhost:9090/logs", nil)
		attachCookie(wsReq, secret)
		wsReq.Header.Set("Upgrade", "websocket")
		wsReq.Header.Set("Origin", "http://localhost:9090")
		wsReq.Header.Set("Sec-Fetch-Site", "same-origin")
		wsRec := httptest.NewRecorder()
		handler.ServeHTTP(wsRec, wsReq)
		if wsRec.Code != http.StatusOK {
			t.Fatalf("same-origin WS status = %d, want 200", wsRec.Code)
		}
	})

	t.Run("state-changing request with loopback Referer and omitted Origin accepted", func(t *testing.T) {
		patchReq := httptest.NewRequest(http.MethodPatch, "http://127.0.0.1:9090/configs", strings.NewReader(`{"mode":"Rule"}`))
		attachCookie(patchReq, secret)
		patchReq.Header.Set("Referer", "http://127.0.0.1:9090/ui/")
		patchReq.Header.Set("Sec-Fetch-Site", "same-origin")
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, patchReq)
		if rec.Code != http.StatusOK {
			t.Fatalf("loopback Referer PATCH status = %d, want 200", rec.Code)
		}
	})

	t.Run("cross-origin Origin evil.example rejected", func(t *testing.T) {
		for _, method := range []string{http.MethodGet, http.MethodPost, http.MethodPatch} {
			req := httptest.NewRequest(method, "http://127.0.0.1:9090/configs", nil)
			attachCookie(req, secret)
			req.Header.Set("Origin", "https://evil.example")
			rec := httptest.NewRecorder()
			handler.ServeHTTP(rec, req)
			if rec.Code != http.StatusUnauthorized {
				t.Fatalf("%s cross-origin status = %d, want 401", method, rec.Code)
			}
		}
	})

	t.Run("Sec-Fetch-Site cross-site rejected even if Origin omitted", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:9090/proxies", nil)
		attachCookie(req, secret)
		req.Header.Set("Sec-Fetch-Site", "cross-site")
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("Sec-Fetch-Site cross-site status = %d, want 401", rec.Code)
		}
	})

	t.Run("POST without Origin or Referer and wrong port Origin rejected", func(t *testing.T) {
		noOriginPost := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:9090/configs", strings.NewReader(`{}`))
		attachCookie(noOriginPost, secret)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, noOriginPost)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("POST without Origin/Referer status = %d, want 401", rec.Code)
		}

		wrongPortReq := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:9090/configs", strings.NewReader(`{}`))
		attachCookie(wrongPortReq, secret)
		wrongPortReq.Header.Set("Origin", "http://127.0.0.1:8080")
		wrongPortRec := httptest.NewRecorder()
		handler.ServeHTTP(wrongPortRec, wrongPortReq)
		if wrongPortRec.Code != http.StatusUnauthorized {
			t.Fatalf("wrong port Origin status = %d, want 401", wrongPortRec.Code)
		}
	})
}

func TestConnectionsZeroIntervalReturnsBadRequestWithoutPanic(t *testing.T) {
	tm := trafficcontrol.NewManager()
	handler := getConnections(context.Background(), tm)

	for _, rawQuery := range []string{"interval=0", "interval=-100", "interval=invalid"} {
		for _, wsHeader := range []string{"websocket", ""} {
			req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:9090/connections?"+rawQuery, nil)
			if wsHeader != "" {
				req.Header.Set("Upgrade", wsHeader)
			}
			rec := httptest.NewRecorder()
			handler(rec, req)
			if rec.Code != http.StatusBadRequest {
				t.Fatalf("query %q upgrade=%q status = %d, want 400", rawQuery, wsHeader, rec.Code)
			}
		}
	}
}

func TestWebSocketLogsExitsPromptlyOnClientDisconnect(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	factory := log.NewDefaultFactory(ctx, log.Formatter{}, io.Discard, "", nil, true)
	if err := factory.Start(); err != nil {
		t.Fatalf("factory.Start: %v", err)
	}
	defer factory.Close()

	handlerDone := make(chan struct{})
	logsHandler := getLogs(ctx, factory)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(handlerDone)
		logsHandler(w, r)
	}))
	defer srv.Close()

	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http") + "/logs?level=info"
	conn, _, _, err := ws.Dial(ctx, wsURL)
	if err != nil {
		t.Fatalf("ws.Dial: %v", err)
	}
	_ = conn.Close()

	select {
	case <-handlerDone:
	case <-time.After(2 * time.Second):
		t.Fatal("getLogs WebSocket handler leaked after client disconnect")
	}
}

func TestDownloadZIPRejectsZipSlipAndSymlinks(t *testing.T) {
	buildZip := func(t *testing.T, modify func(zw *zip.Writer)) []byte {
		t.Helper()
		var buf bytes.Buffer
		zw := zip.NewWriter(&buf)
		modify(zw)
		if err := zw.Close(); err != nil {
			t.Fatalf("close zip: %v", err)
		}
		return buf.Bytes()
	}

	t.Run("zip-slip ../ca.pem rejected", func(t *testing.T) {
		parentDir := t.TempDir()
		uiDir := filepath.Join(parentDir, "yacd")
		srv := &Server{ctx: context.Background(), externalUI: uiDir}

		malicious := buildZip(t, func(zw *zip.Writer) {
			w, err := zw.Create("Yacd-meta-gh-pages/../../ca.pem")
			if err != nil {
				t.Fatal(err)
			}
			_, _ = w.Write([]byte("evil-cert"))
		})

		if err := srv.downloadZIP(bytes.NewReader(malicious), uiDir); err == nil {
			t.Fatal("expected error for Zip-Slip ../ca.pem")
		}
		if _, err := os.Stat(filepath.Join(parentDir, "ca.pem")); !os.IsNotExist(err) {
			t.Fatal("Zip-Slip wrote outside externalUI directory")
		}
	})

	t.Run("symlink entry rejected", func(t *testing.T) {
		uiDir := filepath.Join(t.TempDir(), "yacd")
		srv := &Server{ctx: context.Background(), externalUI: uiDir}

		malicious := buildZip(t, func(zw *zip.Writer) {
			hdr := &zip.FileHeader{Name: "Yacd-meta-gh-pages/escape"}
			hdr.SetMode(os.ModeSymlink | 0o777)
			w, err := zw.CreateHeader(hdr)
			if err != nil {
				t.Fatal(err)
			}
			_, _ = w.Write([]byte("/etc/passwd"))
		})

		if err := srv.downloadZIP(bytes.NewReader(malicious), uiDir); err == nil {
			t.Fatal("expected error for symlink zip entry")
		}
	})
}
