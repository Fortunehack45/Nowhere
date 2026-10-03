package http

import (
	"log/slog"
	"net/http"

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

	// Public Health Check (unthrottled for Cloud Run probes)
	mux.HandleFunc("/health", handler.Health)

	// Protected Verification API endpoint
	mux.HandleFunc("/api/v1/google-play/verify", handler.Verify)

	// Apply Middleware pipeline from outermost to innermost:
	// 1. Recoverer (catches any panic across entire chain)
	// 2. RequestID (assigns X-Request-ID early)
	// 3. SecurityHeaders (nosniff, DENY, no-cache, CSP)
	// 4. RateLimiter (throttles abuse per client IP)
	// 5. StructuredLogger (logs completion with timing and request ID)
	var finalHandler http.Handler = mux

	finalHandler = middleware.StructuredLogger(logger)(finalHandler)
	finalHandler = middleware.RateLimiter(limiter, logger)(finalHandler)
	finalHandler = middleware.SecurityHeaders(finalHandler)
	finalHandler = middleware.RequestIDMiddleware(finalHandler)
	finalHandler = middleware.Recoverer(logger)(finalHandler)

	return finalHandler
}
