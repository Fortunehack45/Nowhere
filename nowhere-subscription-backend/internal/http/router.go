package http

import (
	"log/slog"
	"net/http"
	"strings"

	"nowhere-subscription-backend/internal/config"
	"nowhere-subscription-backend/internal/entitlement"
	"nowhere-subscription-backend/internal/googleplay"
	"nowhere-subscription-backend/internal/middleware"
	"nowhere-subscription-backend/internal/ratelimit"
)

// NewRouter constructs the complete HTTP handler pipeline with all security, logging,
// rate limiting, and route definitions.
func NewRouter(
	cfg *config.Config,
	verifier googleplay.SubscriptionVerifier,
	entitlementSvc *entitlement.Service,
	limiter *ratelimit.IPRateLimiter,
	logger *slog.Logger,
) http.Handler {
	handler := NewHandler(cfg, verifier, entitlementSvc, logger)

	mux := http.NewServeMux()

	// Root Service Info (status check)
	mux.HandleFunc("/", handler.Root)
	mux.HandleFunc("/api/index.go", handler.Root)
	mux.HandleFunc("/api", handler.Root)
	mux.HandleFunc("/api/index", handler.Root)

	// Public Health Check (unthrottled for Cloud Run and Vercel probes)
	mux.HandleFunc("/health", handler.Health)
	mux.HandleFunc("/api/health", handler.Health)

	// Protected Verification API endpoint
	mux.HandleFunc("/api/v1/google-play/verify", handler.Verify)

	// Apply Middleware pipeline from outermost to innermost:
	// 1. Recoverer (catches any panic across entire chain)
	// 2. RequestID (assigns X-Request-ID early)
	// 3. NormalizePath (restores client path from Vercel/proxy rewrites)
	// 4. SecurityHeaders (nosniff, DENY, no-cache, CSP)
	// 5. RateLimiter (throttles abuse per client IP)
	// 6. StructuredLogger (logs completion with timing, normalized path, and request ID)
	var finalHandler http.Handler = mux

	finalHandler = middleware.StructuredLogger(logger)(finalHandler)
	finalHandler = middleware.RateLimiter(limiter, logger)(finalHandler)
	finalHandler = middleware.SecurityHeaders(finalHandler)
	finalHandler = NormalizePath(finalHandler)
	finalHandler = middleware.RequestIDMiddleware(finalHandler)
	finalHandler = middleware.Recoverer(logger)(finalHandler)

	return finalHandler
}

// NormalizePath ensures requests rewritten by reverse proxies, Vercel, or custom gateways
// are restored to their intended route path.
func NormalizePath(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if queryPath := r.URL.Query().Get("__path"); queryPath != "" {
			if !strings.HasPrefix(queryPath, "/") {
				queryPath = "/" + queryPath
			}
			for strings.HasPrefix(queryPath, "//") {
				queryPath = strings.TrimPrefix(queryPath, "/")
				if !strings.HasPrefix(queryPath, "/") {
					queryPath = "/" + queryPath
				}
			}
			r.URL.Path = queryPath
		} else if matchedPath := r.Header.Get("x-matched-path"); matchedPath != "" {
			r.URL.Path = matchedPath
		} else if origURI := r.Header.Get("x-forwarded-uri"); origURI != "" {
			r.URL.Path = origURI
		}

		if r.URL.Path == "/api/index.go" || r.URL.Path == "/api" || r.URL.Path == "/api/index" || r.URL.Path == "" {
			r.URL.Path = "/"
		}

		next.ServeHTTP(w, r)
	})
}
