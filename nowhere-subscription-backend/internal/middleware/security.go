package middleware

import "net/http"

// SecurityHeaders applies protective HTTP headers to all incoming responses.
// Note: CORS is disabled by default. Cross-origin browser requests will not receive
// permissive Access-Control headers.
func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		h.Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		h.Set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate")
		h.Set("Pragma", "no-cache")
		h.Set("X-XSS-Protection", "1; mode=block")

		next.ServeHTTP(w, r)
	})
}
