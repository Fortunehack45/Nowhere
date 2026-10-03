package middleware

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"nowhere-subscription-backend/internal/entitlement"
	"nowhere-subscription-backend/internal/ratelimit"
)

// RateLimiter returns an HTTP middleware that enforces the provided IPRateLimiter.
func RateLimiter(limiter *ratelimit.IPRateLimiter, logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			allowed, ip := limiter.AllowRequest(r)
			if !allowed {
				reqID := GetRequestID(r.Context())
				logger.Warn("rate_limit_exceeded",
					slog.String("request_id", reqID),
					slog.String("client_ip", ip),
					slog.String("path", r.URL.Path),
				)

				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusTooManyRequests)
				_ = json.NewEncoder(w).Encode(entitlement.Invalid())
				return
			}

			next.ServeHTTP(w, r)
		})
	}
}
