package clashapi

import (
	"mime"
	"net/http"
	"path"
	"path/filepath"
	"strings"

	"github.com/go-chi/chi/v5"
)

func mountBuiltInPanels(router chi.Router, resolveExternalUI func() string) {
	router.Group(func(r chi.Router) {
		r.Get("/ui", http.RedirectHandler("/ui/", http.StatusFound).ServeHTTP)
		r.Handle("/ui/*", http.StripPrefix("/ui/", fileServerWithDynamicDir(resolveExternalUI)))
		for _, panelName := range []string{"metacubexd", "yacd"} {
			pName := panelName
			prefix := "/panel/" + pName
			r.Get(prefix, http.RedirectHandler(prefix+"/", http.StatusFound).ServeHTTP)
			r.Handle(prefix+"/*", http.StripPrefix(prefix+"/", fileServerWithDynamicDir(func() string {
				return resolveBuiltInPanelDir(resolveExternalUI(), pName)
			})))
		}
	})
}

const selfDestructServiceWorkerJS = `self.addEventListener('install', () => { self.skipWaiting(); });
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    try {
      const keys = await caches.keys();
      await Promise.all(keys.map((k) => caches.delete(k)));
    } catch (_) {}
    try {
      await self.registration.unregister();
    } catch (_) {}
  })());
});
`

const neutralizeRegisterSWJS = `(async () => {
  try {
    if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) {
      const regs = await navigator.serviceWorker.getRegistrations();
      await Promise.all(regs.map((r) => r.unregister()));
    }
    if (typeof caches !== 'undefined' && caches.keys) {
      const keys = await caches.keys();
      await Promise.all(keys.map((k) => caches.delete(k)));
    }
  } catch (_) {}
})();
`

func init() {
	_ = mime.AddExtensionType(".js", "application/javascript; charset=utf-8")
	_ = mime.AddExtensionType(".mjs", "application/javascript; charset=utf-8")
	_ = mime.AddExtensionType(".css", "text/css; charset=utf-8")
	_ = mime.AddExtensionType(".html", "text/html; charset=utf-8")
	_ = mime.AddExtensionType(".htm", "text/html; charset=utf-8")
	_ = mime.AddExtensionType(".svg", "image/svg+xml")
	_ = mime.AddExtensionType(".json", "application/json; charset=utf-8")
	_ = mime.AddExtensionType(".webmanifest", "application/manifest+json; charset=utf-8")
	_ = mime.AddExtensionType(".woff", "font/woff")
	_ = mime.AddExtensionType(".woff2", "font/woff2")
	_ = mime.AddExtensionType(".png", "image/png")
	_ = mime.AddExtensionType(".ico", "image/x-icon")
}

type Dir http.Dir

func (d Dir) Open(name string) (http.File, error) {
	file, err := http.Dir(d).Open(name)
	if err != nil {
		return nil, err
	}
	return &fileWrapper{file}, nil
}

// workaround for #2345 #2596
type fileWrapper struct {
	http.File
}

// classifyServiceWorkerScript detects ServiceWorker worker/registration scripts (`sw.js`,
// `registerSW.js`, `workbox-*.js`, including duplicate-slash paths like `.//sw.js`) so the
// local loopback UI server can replace any legacy cached PWA worker with a self-unregistering
// script instead of returning 404 (which causes Chromium to keep stale SWs alive for 24h).
func classifyServiceWorkerScript(rawPath string) (scriptBody string, matched bool) {
	cleaned := path.Clean("/" + strings.TrimSpace(rawPath))
	base := path.Base(cleaned)
	switch {
	case base == "sw.js":
		return selfDestructServiceWorkerJS, true
	case base == "registerSW.js":
		return neutralizeRegisterSWJS, true
	case strings.HasPrefix(base, "workbox-") && strings.HasSuffix(base, ".js"):
		return neutralizeRegisterSWJS, true
	default:
		return "", false
	}
}

func fileServerWithMime(dir string) http.Handler {
	return fileServerWithDynamicDir(func() string { return dir })
}

func fileServerWithDynamicDir(resolveDir func() string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusOK)
			return
		}
		if swScript, ok := classifyServiceWorkerScript(r.URL.Path); ok {
			w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate")
			w.Header().Set("Service-Worker-Allowed", "/")
			w.WriteHeader(http.StatusOK)
			if r.Method != http.MethodHead {
				_, _ = w.Write([]byte(swScript))
			}
			return
		}
		dir := resolveDir()
		if strings.TrimSpace(dir) == "" {
			http.NotFound(w, r)
			return
		}
		cleanedPath := path.Clean("/" + r.URL.Path)
		baseName := path.Base(cleanedPath)
		ext := strings.ToLower(filepath.Ext(cleanedPath))
		if ext == "" || ext == ".html" || ext == ".htm" || ext == ".webmanifest" || baseName == "config.js" {
			// WHY: Prevent Chromium's 10%-of-age Last-Modified heuristic cache from serving
			// stale 2023 index.html documents across panel switches or APK upgrades.
			w.Header().Set("Cache-Control", "no-store")
		} else {
			w.Header().Set("Cache-Control", "no-cache")
		}
		switch ext {
		case ".js", ".mjs":
			w.Header().Set("Content-Type", "application/javascript; charset=utf-8")
		case ".css":
			w.Header().Set("Content-Type", "text/css; charset=utf-8")
		case ".html", ".htm":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
		case ".json":
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
		case ".webmanifest":
			w.Header().Set("Content-Type", "application/manifest+json; charset=utf-8")
		case ".svg":
			w.Header().Set("Content-Type", "image/svg+xml")
		case ".woff2":
			w.Header().Set("Content-Type", "font/woff2")
		}
		http.FileServer(Dir(dir)).ServeHTTP(w, r)
	})
}
