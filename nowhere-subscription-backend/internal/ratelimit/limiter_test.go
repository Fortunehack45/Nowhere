package ratelimit

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestIPRateLimiter_Allow(t *testing.T) {
	// 5 requests per second, burst of 3
	limiter := NewIPRateLimiter(5.0, 3, 100*time.Millisecond, 200*time.Millisecond)
	defer limiter.Stop()

	ip := "192.0.2.1"

	// First 3 requests should succeed (burst capacity)
	for i := 0; i < 3; i++ {
		if !limiter.Allow(ip) {
			t.Fatalf("expected request %d to be allowed", i+1)
		}
	}

	// 4th request immediately following should be rejected
	if limiter.Allow(ip) {
		t.Fatalf("expected 4th immediate request to be rate-limited")
	}

	// Another IP should not be affected
	otherIP := "192.0.2.2"
	if !limiter.Allow(otherIP) {
		t.Fatalf("expected other IP to be allowed")
	}
}

func TestIPRateLimiter_ExtractClientIP(t *testing.T) {
	tests := []struct {
		name     string
		headers  map[string]string
		remote   string
		expected string
	}{
		{
			name:     "X-Forwarded-For multiple IPs returns first IP",
			headers:  map[string]string{"X-Forwarded-For": "203.0.113.195, 70.41.3.18, 150.172.238.178"},
			remote:   "10.0.0.1:12345",
			expected: "203.0.113.195",
		},
		{
			name:     "X-Real-IP single IP",
			headers:  map[string]string{"X-Real-IP": "198.51.100.1"},
			remote:   "10.0.0.1:12345",
			expected: "198.51.100.1",
		},
		{
			name:     "Fallback to RemoteAddr host",
			headers:  nil,
			remote:   "192.168.1.50:54321",
			expected: "192.168.1.50",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/test", nil)
			req.RemoteAddr = tt.remote
			for k, v := range tt.headers {
				req.Header.Set(k, v)
			}

			ip := ExtractClientIP(req)
			if ip != tt.expected {
				t.Errorf("ExtractClientIP() = %q, want %q", ip, tt.expected)
			}
		})
	}
}

func TestIPRateLimiter_Cleanup(t *testing.T) {
	limiter := NewIPRateLimiter(10.0, 10, 20*time.Millisecond, 40*time.Millisecond)
	defer limiter.Stop()

	ip := "192.0.2.99"
	limiter.Allow(ip)

	limiter.mu.Lock()
	if _, ok := limiter.clients[ip]; !ok {
		limiter.mu.Unlock()
		t.Fatalf("expected client to be tracked")
	}
	limiter.mu.Unlock()

	// Wait for cleanup ticker and TTL to expire
	time.Sleep(100 * time.Millisecond)

	limiter.mu.Lock()
	_, exists := limiter.clients[ip]
	limiter.mu.Unlock()

	if exists {
		t.Errorf("expected client entry to be purged after inactivity")
	}
}
