package ratelimit

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

// clientEntry tracks the rate limiter and the last active timestamp for an IP.
type clientEntry struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

// IPRateLimiter provides in-memory IP-based token-bucket rate limiting.
//
// NOTE: This limiter is strictly in-memory and per-instance. When running on
// Google Cloud Run or horizontally scaled container clusters, rate limits are
// evaluated independently per container instance without shared state (no Redis/DB).
type IPRateLimiter struct {
	mu          sync.Mutex
	clients     map[string]*clientEntry
	rps         rate.Limit
	burst       int
	cleanupTTL  time.Duration
	stopCleanup chan struct{}
}

// NewIPRateLimiter creates an in-memory IP rate limiter and starts a background
// goroutine to periodically clean up inactive IP records.
func NewIPRateLimiter(rps float64, burst int, cleanupInterval, cleanupTTL time.Duration) *IPRateLimiter {
	limiter := &IPRateLimiter{
		clients:     make(map[string]*clientEntry),
		rps:         rate.Limit(rps),
		burst:       burst,
		cleanupTTL:  cleanupTTL,
		stopCleanup: make(chan struct{}),
	}

	go limiter.cleanupLoop(cleanupInterval)
	return limiter
}

// Allow reports whether a request from the specified IP address may proceed.
func (l *IPRateLimiter) Allow(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	entry, exists := l.clients[ip]
	if !exists {
		entry = &clientEntry{
			limiter:  rate.NewLimiter(l.rps, l.burst),
			lastSeen: now,
		}
		l.clients[ip] = entry
	} else {
		entry.lastSeen = now
	}

	return entry.limiter.Allow()
}

// AllowRequest inspects request headers and RemoteAddr to extract the client IP and evaluates rate limit.
func (l *IPRateLimiter) AllowRequest(r *http.Request) (bool, string) {
	ip := ExtractClientIP(r)
	return l.Allow(ip), ip
}

// Stop stops the background eviction goroutine.
func (l *IPRateLimiter) Stop() {
	select {
	case <-l.stopCleanup:
		// already stopped
	default:
		close(l.stopCleanup)
	}
}

// cleanupLoop periodically sweeps inactive IP records.
func (l *IPRateLimiter) cleanupLoop(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-l.stopCleanup:
			return
		case <-ticker.C:
			l.mu.Lock()
			cutoff := time.Now().Add(-l.cleanupTTL)
			for ip, entry := range l.clients {
				if entry.lastSeen.Before(cutoff) {
					delete(l.clients, ip)
				}
			}
			l.mu.Unlock()
		}
	}
}

// ExtractClientIP extracts the real client IP from standard proxy headers (X-Forwarded-For, X-Real-IP)
// or falls back to RemoteAddr. Cloud Run front-end sets X-Forwarded-For.
func ExtractClientIP(r *http.Request) string {
	// 1. Check X-Forwarded-For (client IP is typically the first entry)
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		clientIP := strings.TrimSpace(parts[0])
		if clientIP != "" && net.ParseIP(clientIP) != nil {
			return clientIP
		}
	}

	// 2. Check X-Real-IP
	if xri := strings.TrimSpace(r.Header.Get("X-Real-IP")); xri != "" {
		if net.ParseIP(xri) != nil {
			return xri
		}
	}

	// 3. Fallback to RemoteAddr
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil && host != "" {
		return host
	}

	return strings.TrimSpace(r.RemoteAddr)
}
