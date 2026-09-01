package api

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"
)

type errorResponse struct {
	Status  string `json:"status"`
	Message string `json:"message"`
}

// AuthMiddleware enforces API key authentication between Nowhere app and control-plane.
func AuthMiddleware(apiKeys []string) func(http.Handler) http.Handler {
	validKeys := make(map[string]bool)
	for _, k := range apiKeys {
		k = strings.TrimSpace(k)
		if k != "" {
			validKeys[k] = true
		}
	}

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			// Skip auth for healthcheck endpoint
			if r.URL.Path == "/health" || r.URL.Path == "/api/v1/health" {
				next.ServeHTTP(w, r)
				return
			}

			// If no API keys configured on backend, allow requests in dev mode
			if len(validKeys) == 0 {
				next.ServeHTTP(w, r)
				return
			}

			// Check X-API-Key header
			apiKey := r.Header.Get("X-API-Key")
			if apiKey == "" {
				// Check Authorization: Bearer <key>
				authHeader := r.Header.Get("Authorization")
				if strings.HasPrefix(authHeader, "Bearer ") {
					apiKey = strings.TrimPrefix(authHeader, "Bearer ")
				}
			}
			if apiKey == "" {
				// Check URL query parameters (?api_key=... or ?key=...)
				apiKey = r.URL.Query().Get("api_key")
				if apiKey == "" {
					apiKey = r.URL.Query().Get("key")
				}
			}

			if apiKey == "" || !validKeys[apiKey] {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusUnauthorized)
				_ = json.NewEncoder(w).Encode(errorResponse{
					Status:  "error",
					Message: "unauthorized: invalid or missing API key",
				})
				return
			}

			next.ServeHTTP(w, r)
		})
	}
}

// LoggingMiddleware logs incoming HTTP requests with latency and status code.
func LoggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ww := &statusWriter{ResponseWriter: w, statusCode: http.StatusOK}
		next.ServeHTTP(ww, r)
		duration := time.Since(start)

		log.Printf("[%s] %s %s -> %d (%s) [client: %s]",
			r.Method,
			r.URL.Path,
			r.Proto,
			ww.statusCode,
			duration,
			r.RemoteAddr,
		)
	})
}

// RecoveryMiddleware gracefully catches panics and returns 500 JSON error.
func RecoveryMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("PANIC recovered in HTTP handler: %v", rec)
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusInternalServerError)
				_ = json.NewEncoder(w).Encode(errorResponse{
					Status:  "error",
					Message: "internal server error",
				})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

type statusWriter struct {
	http.ResponseWriter
	statusCode int
}

func (w *statusWriter) WriteHeader(code int) {
	w.statusCode = code
	w.ResponseWriter.WriteHeader(code)
}
