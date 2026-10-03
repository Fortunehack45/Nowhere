package middleware

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"nowhere-subscription-backend/internal/entitlement"
	"nowhere-subscription-backend/internal/ratelimit"
)

func TestRequestIDMiddleware(t *testing.T) {
	handler := RequestIDMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reqID := GetRequestID(r.Context())
		if reqID == "" || reqID == "unknown" {
			t.Errorf("expected valid request ID in context, got %s", reqID)
		}
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	respID := rr.Header().Get("X-Request-ID")
	if respID == "" {
		t.Errorf("expected X-Request-ID header in response")
	}

	// Test passing existing X-Request-ID
	reqCustom := httptest.NewRequest(http.MethodGet, "/test", nil)
	reqCustom.Header.Set("X-Request-ID", "custom-id-12345")
	rrCustom := httptest.NewRecorder()
	handler.ServeHTTP(rrCustom, reqCustom)

	if rrCustom.Header().Get("X-Request-ID") != "custom-id-12345" {
		t.Errorf("expected custom-id-12345, got %s", rrCustom.Header().Get("X-Request-ID"))
	}
}

func TestSecurityHeaders(t *testing.T) {
	handler := SecurityHeaders(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	headers := map[string]string{
		"X-Content-Type-Options":  "nosniff",
		"X-Frame-Options":         "DENY",
		"Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
		"Cache-Control":           "no-store, no-cache, must-revalidate, proxy-revalidate",
	}

	for k, want := range headers {
		got := rr.Header().Get(k)
		if got != want {
			t.Errorf("header %s: got %q, want %q", k, got, want)
		}
	}
}

func TestRecoverer(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	handler := Recoverer(logger)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		panic("simulated critical crash")
	}))

	req := httptest.NewRequest(http.MethodGet, "/panic", nil)
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusInternalServerError {
		t.Errorf("expected status %d, got %d", http.StatusInternalServerError, rr.Code)
	}

	var resp entitlement.Response
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode response JSON: %v", err)
	}
	if resp.Verified || resp.Active || resp.Entitlement != "none" {
		t.Errorf("expected fail-closed invalid response, got %+v", resp)
	}
}

func TestRateLimiterMiddleware(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	lim := ratelimit.NewIPRateLimiter(1.0, 1, time.Second, time.Second)
	defer lim.Stop()

	handler := RateLimiter(lim, logger)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req1 := httptest.NewRequest(http.MethodGet, "/test", nil)
	req1.RemoteAddr = "192.0.2.1:12345"
	rr1 := httptest.NewRecorder()
	handler.ServeHTTP(rr1, req1)

	if rr1.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rr1.Code)
	}

	// Second request exhausts burst of 1
	req2 := httptest.NewRequest(http.MethodGet, "/test", nil)
	req2.RemoteAddr = "192.0.2.1:12345"
	rr2 := httptest.NewRecorder()
	handler.ServeHTTP(rr2, req2)

	if rr2.Code != http.StatusTooManyRequests {
		t.Errorf("expected 429 Too Many Requests, got %d", rr2.Code)
	}
}
