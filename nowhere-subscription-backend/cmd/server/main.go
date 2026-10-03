package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"nowhere-subscription-backend/internal/config"
	"nowhere-subscription-backend/internal/entitlement"
	"nowhere-subscription-backend/internal/googleplay"
	internalhttp "nowhere-subscription-backend/internal/http"
	"nowhere-subscription-backend/internal/ratelimit"
)

func main() {
	// 1. Initialize Structured JSON Logger
	logLevel := slog.LevelInfo
	if os.Getenv("APP_LOG_LEVEL") == "DEBUG" {
		logLevel = slog.LevelDebug
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: logLevel,
	}))
	slog.SetDefault(logger)

	// 2. Load Configuration from Environment
	cfg, err := config.LoadFromEnv()
	if err != nil {
		logger.Error("configuration_load_failed", slog.String("error", err.Error()))
		os.Exit(1)
	}

	logger.Info("configuration_loaded",
		slog.String("package_name", cfg.AndroidPackageName),
		slog.Any("allowed_products", cfg.AllowedProductIDsList),
		slog.String("port", cfg.Port),
		slog.String("environment", cfg.Environment),
		slog.Float64("rate_limit_rps", cfg.RateLimitRPS),
		slog.Int("rate_limit_burst", cfg.RateLimitBurst),
	)

	// 3. Initialize Google Play Developer API Client
	ctx, cancelInit := context.WithTimeout(context.Background(), 15*time.Second)
	playClient, err := googleplay.NewClient(ctx)
	cancelInit()
	if err != nil {
		logger.Error("google_play_client_initialization_failed",
			slog.String("error", err.Error()),
			slog.String("hint", "Ensure Application Default Credentials (ADC) or GOOGLE_APPLICATION_CREDENTIALS are configured"),
		)
		os.Exit(1)
	}

	// 4. Initialize Domain Services
	entitlementSvc := entitlement.NewService()
	limiter := ratelimit.NewIPRateLimiter(
		cfg.RateLimitRPS,
		cfg.RateLimitBurst,
		1*time.Minute,
		5*time.Minute,
	)
	defer limiter.Stop()

	// 5. Construct Router and Middleware Chain
	router := internalhttp.NewRouter(cfg, playClient, entitlementSvc, limiter, logger)

	// 6. Configure Production HTTP Server
	server := &http.Server{
		Addr:              cfg.Address(),
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 20, // 1 MB
	}

	// 7. Start Server in Background Goroutine
	serverErrChan := make(chan error, 1)
	go func() {
		logger.Info("server_starting", slog.String("address", cfg.Address()))
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErrChan <- err
		}
	}()

	// 8. Graceful Shutdown on SIGINT/SIGTERM
	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	select {
	case err := <-serverErrChan:
		logger.Error("server_fatal_error", slog.String("error", err.Error()))
		os.Exit(1)
	case sig := <-stopChan:
		logger.Info("shutdown_signal_received", slog.String("signal", sig.String()))

		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer shutdownCancel()

		if err := server.Shutdown(shutdownCtx); err != nil {
			logger.Error("server_shutdown_error", slog.String("error", err.Error()))
			os.Exit(1)
		}

		logger.Info("server_shutdown_completed")
	}
}
