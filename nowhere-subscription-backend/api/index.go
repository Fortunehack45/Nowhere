package handler

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"sync"
	"time"

	"nowhere-subscription-backend/internal/config"
	"nowhere-subscription-backend/internal/entitlement"
	"nowhere-subscription-backend/internal/googleplay"
	internalhttp "nowhere-subscription-backend/internal/http"
	"nowhere-subscription-backend/internal/ratelimit"
)

var (
	router   http.Handler
	initOnce sync.Once
	initErr  error
)

func initialize() {
	cfg, err := config.LoadFromEnv()
	if err != nil {
		initErr = err
		return
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	playClient, err := googleplay.NewClient(ctx)
	if err != nil {
		initErr = err
		return
	}

	entitlementSvc := entitlement.NewService()
	limiter := ratelimit.NewIPRateLimiter(
		cfg.RateLimitRPS,
		cfg.RateLimitBurst,
		1*time.Minute,
		5*time.Minute,
	)

	router = internalhttp.NewRouter(cfg, playClient, entitlementSvc, limiter, logger)
}

// Handler is the exported HTTP entry point executed by Vercel Serverless Functions.
func Handler(w http.ResponseWriter, r *http.Request) {
	initOnce.Do(initialize)

	if initErr != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"verified":false,"entitlement":"none","active":false}`))
		return
	}

	// In Vercel rewrites, r.URL.Path is rewritten to the destination file ("/api/index.go").
	// Restore the real client request path from Vercel's x-matched-path or x-forwarded-uri headers.
	if matchedPath := r.Header.Get("x-matched-path"); matchedPath != "" {
		r.URL.Path = matchedPath
	} else if origURI := r.Header.Get("x-forwarded-uri"); origURI != "" {
		r.URL.Path = origURI
	}

	// If path is still /api/index.go, treat it as root /
	if r.URL.Path == "/api/index.go" || r.URL.Path == "" {
		r.URL.Path = "/"
	}

	router.ServeHTTP(w, r)
}
