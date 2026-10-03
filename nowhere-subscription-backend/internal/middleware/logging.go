package middleware

import (
	"log/slog"
	"net/http"
	"time"

	"nowhere-subscription-backend/internal/ratelimit"
)

// responseWriterInterceptor wraps http.ResponseWriter to capture HTTP status and written bytes.
type responseWriterInterceptor struct {
	http.ResponseWriter
	statusCode   int
	bytesWritten int
}

func (w *responseWriterInterceptor) WriteHeader(statusCode int) {
	w.statusCode = statusCode
	w.ResponseWriter.WriteHeader(statusCode)
}

func (w *responseWriterInterceptor) Write(b []byte) (int, error) {
	if w.statusCode == 0 {
		w.statusCode = http.StatusOK
	}
	n, err := w.ResponseWriter.Write(b)
	w.bytesWritten += n
	return n, err
}

// StructuredLogger logs HTTP request metadata without exposing request bodies, credentials, or tokens.
func StructuredLogger(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			reqID := GetRequestID(r.Context())
			clientIP := ratelimit.ExtractClientIP(r)

			interceptor := &responseWriterInterceptor{
				ResponseWriter: w,
				statusCode:     http.StatusOK,
			}

			next.ServeHTTP(interceptor, r)

			duration := time.Since(start)

			// Structured log entry - strictly excludes bodies and sensitive headers
			logger.Info("http_request",
				slog.String("request_id", reqID),
				slog.String("method", r.Method),
				slog.String("path", r.URL.Path),
				slog.String("client_ip", clientIP),
				slog.Int("status", interceptor.statusCode),
				slog.Int("bytes", interceptor.bytesWritten),
				slog.Int64("duration_ms", duration.Milliseconds()),
			)
		})
	}
}
