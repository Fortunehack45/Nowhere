package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/cors"
	"nowhere-vpn-backend/internal/api"
	"nowhere-vpn-backend/internal/config"
	"nowhere-vpn-backend/internal/gameboost"
	"nowhere-vpn-backend/internal/proxy"
	"nowhere-vpn-backend/internal/ssh"
	"nowhere-vpn-backend/internal/wireguard"
)

func main() {
	var (
		portFlag      = flag.String("port", getEnv("PORT", "8080"), "HTTP server listen port")
		configDirFlag = flag.String("config", getEnv("CONFIG_DIR", "./config"), "Directory containing nodes.yaml and leased_regions.yaml")
		sshKeyFlag    = flag.String("ssh-key", getEnv("SSH_DEFAULT_KEY", "/secrets/nowhere_deploy.pem"), "Default SSH private key file path")
		apiKeysFlag   = flag.String("api-keys", getEnv("API_KEYS", ""), "Comma-separated valid API keys for client authorization")
	)
	flag.Parse()

	log.Println("================================================================")
	log.Println("⚡ NOWHERE VPN BACKEND — High-Performance WireGuard Control Plane")
	log.Println("🎮 Millisecond Game Booster & Anti-Detection QoS Active")
	log.Println("================================================================")

	// 1. Initialize Configuration Registry
	log.Printf("[Config] Loading nodes and leased regions from: %s", *configDirFlag)
	registry, err := config.NewRegistry(*configDirFlag)
	if err != nil {
		log.Fatalf("[FATAL] Failed loading configuration: %v", err)
	}
	log.Printf("[Config] Successfully registered %d WireGuard nodes, %d leased exit regions",
		len(registry.GetNodes()), len(registry.GetLeasedRegions()))

	// 2. Initialize SSH Connection Pool
	sshPool := ssh.NewClientPool(*sshKeyFlag, 8*time.Second)
	defer sshPool.Close()

	// 3. Initialize WireGuard, Proxy & Game Optimizer
	wgManager := wireguard.NewManager(sshPool)
	leasedHandler := proxy.NewLeasedExitHandler(wgManager, sshPool, registry)
	gameOptimizer := gameboost.NewOptimizer(registry, wgManager, sshPool)

	// 4. Initialize API Server
	apiServer := api.NewServer(registry, wgManager, leasedHandler, gameOptimizer)

	// 5. Setup Router & Middleware
	r := chi.NewRouter()

	// Global Middleware
	r.Use(api.RecoveryMiddleware)
	r.Use(api.LoggingMiddleware)
	r.Use(cors.Handler(cors.Options{
		AllowedOrigins:   []string{"*"},
		AllowedMethods:   []string{"GET", "POST", "DELETE", "OPTIONS"},
		AllowedHeaders:   []string{"Accept", "Authorization", "Content-Type", "X-API-Key"},
		ExposedHeaders:   []string{"Link"},
		AllowCredentials: true,
		MaxAge:           300,
	}))

	// Parse API keys for Auth Middleware
	var apiKeys []string
	if *apiKeysFlag != "" {
		apiKeys = strings.Split(*apiKeysFlag, ",")
		log.Printf("[Auth] API key authentication active (%d key(s) configured)", len(apiKeys))
	} else {
		log.Println("[Auth] WARNING: No API_KEYS set — running in open development mode")
	}

	// Routes
	r.Get("/health", apiServer.HandleHealth)
	r.Get("/api/v1/health", apiServer.HandleHealth)

	r.Group(func(protected chi.Router) {
		protected.Use(api.AuthMiddleware(apiKeys))

		// Core VPN
		protected.Get("/api/v1/nodes", apiServer.HandleListNodes)
		protected.Get("/api/v1/regions", apiServer.HandleListRegions)
		protected.Post("/api/v1/connect", apiServer.HandleConnect)
		protected.Delete("/api/v1/disconnect", apiServer.HandleDisconnect)
		protected.Post("/api/v1/reload", apiServer.HandleReload)

		// Game Boost (Millisecond Latency Optimization)
		protected.Get("/api/v1/game-boost/games", apiServer.HandleListGames)
		protected.Post("/api/v1/game-boost/optimize", apiServer.HandleGameOptimize)
	})

	server := &http.Server{
		Addr:         fmt.Sprintf(":%s", *portFlag),
		Handler:      r,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 20 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// 6. Handle SIGHUP for Dynamic Zero-Downtime Config Reload
	sigHupChan := make(chan os.Signal, 1)
	signal.Notify(sigHupChan, syscall.SIGHUP)
	go func() {
		for range sigHupChan {
			log.Println("[SIGHUP] Received SIGHUP signal: reloading nodes and leased regions...")
			if err := registry.Reload(); err != nil {
				log.Printf("[SIGHUP] Reload failed: %v", err)
			} else {
				log.Printf("[SIGHUP] Reloaded successfully (%d nodes, %d leased regions)",
					len(registry.GetNodes()), len(registry.GetLeasedRegions()))
			}
		}
	}()

	// 7. Start HTTP Server in background
	go func() {
		log.Printf("[HTTP] Nowhere VPN control-plane listening on http://0.0.0.0:%s", *portFlag)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("[FATAL] HTTP server failed: %v", err)
		}
	}()

	// 8. Graceful Shutdown on SIGINT / SIGTERM
	shutdownChan := make(chan os.Signal, 1)
	signal.Notify(shutdownChan, os.Interrupt, syscall.SIGTERM)
	<-shutdownChan

	log.Println("[Shutdown] Initiating graceful server shutdown...")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Printf("[Shutdown] Server shutdown error: %v", err)
	}

	log.Println("[Shutdown] Nowhere VPN control plane stopped cleanly.")
}

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}
