package http

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nowhere-subscription-backend/internal/config"
	"nowhere-subscription-backend/internal/entitlement"
	"nowhere-subscription-backend/internal/googleplay"
	"nowhere-subscription-backend/internal/ratelimit"
)

type mockVerifier struct {
	verifyFunc func(ctx context.Context, packageName, productID, purchaseToken string) (*googleplay.SubscriptionStatus, error)
}

func (m *mockVerifier) VerifySubscription(ctx context.Context, packageName, productID, purchaseToken string) (*googleplay.SubscriptionStatus, error) {
	if m.verifyFunc != nil {
		return m.verifyFunc(ctx, packageName, productID, purchaseToken)
	}
	return nil, errors.New("mock not configured")
}

func setupTestRouter(verifier googleplay.SubscriptionVerifier, limitRPS float64, burst int) (http.Handler, *config.Config) {
	cfg := &config.Config{
		AndroidPackageName: "com.nowhere.gps.locationchanger",
		AllowedProductIDs: map[string]struct{}{
			"nowhere_premium":        {},
			"nowhere_premium_yearly": {},
		},
		AllowedProductIDsList: []string{"nowhere_premium", "nowhere_premium_yearly"},
		Port:                  "8080",
		MaxRequestBodyBytes:   1024, // 1KB for testing oversized requests easily
		RateLimitRPS:          limitRPS,
		RateLimitBurst:        burst,
		RequestTimeout:        2 * time.Second,
		Environment:           "test",
	}

	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	entSvc := entitlement.NewService()
	limiter := ratelimit.NewIPRateLimiter(limitRPS, burst, 10*time.Minute, 10*time.Minute)

	router := NewRouter(cfg, verifier, entSvc, limiter, logger)
	return router, cfg
}

func TestHealthCheck(t *testing.T) {
	router, _ := setupTestRouter(&mockVerifier{}, 10, 10)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", rr.Code)
	}

	var resp HealthResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to parse health response: %v", err)
	}
	if resp.Status != "ok" {
		t.Errorf("expected status 'ok', got %q", resp.Status)
	}
}

func TestVerify_ValidActiveSubscription(t *testing.T) {
	futureExpiry := time.Now().Add(30 * 24 * time.Hour).UTC().Truncate(time.Second)

	mock := &mockVerifier{
		verifyFunc: func(ctx context.Context, pkg, prod, token string) (*googleplay.SubscriptionStatus, error) {
			if pkg != "com.nowhere.gps.locationchanger" || prod != "nowhere_premium" || token != "valid-token-123" {
				return nil, errors.New("unexpected params")
			}
			return &googleplay.SubscriptionStatus{
				PackageName:          pkg,
				ProductID:            prod,
				SubscriptionState:    googleplay.StateActive,
				AcknowledgementState: googleplay.AckStateAcknowledged,
				ExpiryTime:           futureExpiry,
				AutoRenewing:         true,
			}, nil
		},
	}

	router, _ := setupTestRouter(mock, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"valid-token-123"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()

	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}

	var resp entitlement.Response
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if !resp.Verified || !resp.Active || resp.Entitlement != "pro" || resp.ProductID != "nowhere_premium" {
		t.Errorf("expected active pro response, got %+v", resp)
	}
	if resp.ExpiresAt != futureExpiry.Format(time.RFC3339) {
		t.Errorf("expected expiresAt %s, got %s", futureExpiry.Format(time.RFC3339), resp.ExpiresAt)
	}
}

func TestVerify_MalformedJSON(t *testing.T) {
	router, _ := setupTestRouter(&mockVerifier{}, 10, 10)

	invalidJSONs := []string{
		`{broken json`,
		`{"packageName": "com.nowhere.gps.locationchanger",}`,
		`plain text string`,
		`{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"token","unknownField":"hacker"}`,
	}

	for _, badBody := range invalidJSONs {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(badBody))
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		router.ServeHTTP(rr, req)

		if rr.Code != http.StatusBadRequest {
			t.Errorf("expected 400 for bad body %q, got %d", badBody, rr.Code)
		}

		var resp entitlement.Response
		_ = json.Unmarshal(rr.Body.Bytes(), &resp)
		if resp.Verified || resp.Active || resp.Entitlement != "none" {
			t.Errorf("expected invalid fail-closed response, got %+v", resp)
		}
	}
}

func TestVerify_MissingFields(t *testing.T) {
	router, _ := setupTestRouter(&mockVerifier{}, 10, 10)

	cases := []string{
		`{"productId":"nowhere_premium","purchaseToken":"tok"}`,                           // missing packageName
		`{"packageName":"com.nowhere.gps.locationchanger","purchaseToken":"tok"}`,         // missing productId
		`{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium"}`, // missing purchaseToken
		`{"packageName":"","productId":"nowhere_premium","purchaseToken":"tok"}`,
		`{"packageName":"com.nowhere.gps.locationchanger","productId":"","purchaseToken":"tok"}`,
		`{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"  "}`,
	}

	for _, payload := range cases {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(payload))
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		router.ServeHTTP(rr, req)

		if rr.Code != http.StatusBadRequest {
			t.Errorf("expected 400 for payload %q, got %d", payload, rr.Code)
		}
	}
}

func TestVerify_OversizedRequest(t *testing.T) {
	router, _ := setupTestRouter(&mockVerifier{}, 10, 10)

	// Create payload larger than MaxRequestBodyBytes (1024 bytes)
	hugeToken := strings.Repeat("A", 2048)
	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"` + hugeToken + `"}`

	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("expected 413 Request Entity Too Large, got %d", rr.Code)
	}
}

func TestVerify_InvalidPackageName(t *testing.T) {
	router, _ := setupTestRouter(&mockVerifier{}, 10, 10)

	body := `{"packageName":"com.fake.impostor.app","productId":"nowhere_premium","purchaseToken":"tok"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for invalid package name, got %d", rr.Code)
	}
}

func TestVerify_UnknownProductID(t *testing.T) {
	router, _ := setupTestRouter(&mockVerifier{}, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"unauthorized_cheat_product","purchaseToken":"tok"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for unknown product ID, got %d", rr.Code)
	}
}

func TestVerify_InvalidPurchaseTokens(t *testing.T) {
	mock := &mockVerifier{
		verifyFunc: func(ctx context.Context, pkg, prod, token string) (*googleplay.SubscriptionStatus, error) {
			return nil, googleplay.ErrInvalidPurchaseToken
		},
	}
	router, _ := setupTestRouter(mock, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"invalid-token"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}

	var resp entitlement.Response
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if resp.Verified || resp.Active {
		t.Errorf("expected verified false, active false, got %+v", resp)
	}
}

func TestVerify_ExpiredSubscription(t *testing.T) {
	pastExpiry := time.Now().Add(-24 * time.Hour).UTC().Truncate(time.Second)

	mock := &mockVerifier{
		verifyFunc: func(ctx context.Context, pkg, prod, token string) (*googleplay.SubscriptionStatus, error) {
			return &googleplay.SubscriptionStatus{
				PackageName:       pkg,
				ProductID:         prod,
				SubscriptionState: googleplay.StateExpired,
				ExpiryTime:        pastExpiry,
			}, nil
		},
	}
	router, _ := setupTestRouter(mock, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"expired-token"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}

	var resp entitlement.Response
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if !resp.Verified || resp.Active || resp.Entitlement != "none" {
		t.Errorf("expected verified true, active false, got %+v", resp)
	}
}

func TestVerify_CanceledSubscription(t *testing.T) {
	futureExpiry := time.Now().Add(10 * 24 * time.Hour).UTC().Truncate(time.Second)

	mock := &mockVerifier{
		verifyFunc: func(ctx context.Context, pkg, prod, token string) (*googleplay.SubscriptionStatus, error) {
			return &googleplay.SubscriptionStatus{
				PackageName:       pkg,
				ProductID:         prod,
				SubscriptionState: googleplay.StateCanceled,
				ExpiryTime:        futureExpiry,
				AutoRenewing:      false,
			}, nil
		},
	}
	router, _ := setupTestRouter(mock, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"canceled-token"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}

	var resp entitlement.Response
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if !resp.Verified || !resp.Active || resp.Entitlement != "pro" {
		t.Errorf("expected active pro until future expiry, got %+v", resp)
	}
}

func TestVerify_PendingSubscription(t *testing.T) {
	futureExpiry := time.Now().Add(24 * time.Hour).UTC().Truncate(time.Second)

	mock := &mockVerifier{
		verifyFunc: func(ctx context.Context, pkg, prod, token string) (*googleplay.SubscriptionStatus, error) {
			return &googleplay.SubscriptionStatus{
				PackageName:       pkg,
				ProductID:         prod,
				SubscriptionState: googleplay.StatePending,
				ExpiryTime:        futureExpiry,
			}, nil
		},
	}
	router, _ := setupTestRouter(mock, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"pending-token"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}

	var resp entitlement.Response
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if !resp.Verified || resp.Active || resp.Entitlement != "none" {
		t.Errorf("expected inactive entitlement for pending state, got %+v", resp)
	}
}

func TestVerify_RevokedSubscription(t *testing.T) {
	pastExpiry := time.Now().Add(-10 * time.Minute).UTC().Truncate(time.Second)

	mock := &mockVerifier{
		verifyFunc: func(ctx context.Context, pkg, prod, token string) (*googleplay.SubscriptionStatus, error) {
			return &googleplay.SubscriptionStatus{
				PackageName:       pkg,
				ProductID:         prod,
				SubscriptionState: googleplay.StateExpired,
				ExpiryTime:        pastExpiry,
			}, nil
		},
	}
	router, _ := setupTestRouter(mock, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"revoked-token"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}

	var resp entitlement.Response
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if !resp.Verified || resp.Active || resp.Entitlement != "none" {
		t.Errorf("expected inactive for revoked subscription, got %+v", resp)
	}
}

func TestVerify_UnknownSubscriptionState(t *testing.T) {
	futureExpiry := time.Now().Add(24 * time.Hour).UTC().Truncate(time.Second)

	mock := &mockVerifier{
		verifyFunc: func(ctx context.Context, pkg, prod, token string) (*googleplay.SubscriptionStatus, error) {
			return &googleplay.SubscriptionStatus{
				PackageName:       pkg,
				ProductID:         prod,
				SubscriptionState: "SOME_FUTURE_UNKNOWN_STATE",
				ExpiryTime:        futureExpiry,
			}, nil
		},
	}
	router, _ := setupTestRouter(mock, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"mystery-token"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rr.Code)
	}

	var resp entitlement.Response
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if !resp.Verified || resp.Active || resp.Entitlement != "none" {
		t.Errorf("expected fail-closed inactive for unknown state, got %+v", resp)
	}
}

func TestVerify_GoogleAPIFailure(t *testing.T) {
	mock := &mockVerifier{
		verifyFunc: func(ctx context.Context, pkg, prod, token string) (*googleplay.SubscriptionStatus, error) {
			return nil, errors.New("upstream server connection refused")
		},
	}
	router, _ := setupTestRouter(mock, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"tok"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusBadGateway {
		t.Errorf("expected 502 Bad Gateway for upstream failure, got %d", rr.Code)
	}

	var resp entitlement.Response
	_ = json.Unmarshal(rr.Body.Bytes(), &resp)
	if resp.Verified || resp.Active {
		t.Errorf("expected fail-closed invalid response, got %+v", resp)
	}
}

func TestVerify_GoogleAPITimeout(t *testing.T) {
	mock := &mockVerifier{
		verifyFunc: func(ctx context.Context, pkg, prod, token string) (*googleplay.SubscriptionStatus, error) {
			return nil, context.DeadlineExceeded
		},
	}
	router, _ := setupTestRouter(mock, 10, 10)

	body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"tok"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusGatewayTimeout {
		t.Errorf("expected 504 Gateway Timeout, got %d", rr.Code)
	}
}

func TestVerify_RateLimiting(t *testing.T) {
	// Configure limiter with burst capacity 1
	router, _ := setupTestRouter(&mockVerifier{}, 1.0, 1)

	makeReq := func() *httptest.ResponseRecorder {
		body := `{"packageName":"com.nowhere.gps.locationchanger","productId":"nowhere_premium","purchaseToken":"tok"}`
		req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = "203.0.113.50:50000"
		rr := httptest.NewRecorder()
		router.ServeHTTP(rr, req)
		return rr
	}

	// 1st request consumes token
	rr1 := makeReq()
	if rr1.Code == http.StatusTooManyRequests {
		t.Fatalf("first request should not be rate limited")
	}

	// 2nd request immediately following must receive 429
	rr2 := makeReq()
	if rr2.Code != http.StatusTooManyRequests {
		t.Errorf("expected 429 Too Many Requests, got %d", rr2.Code)
	}

	var resp entitlement.Response
	_ = json.Unmarshal(rr2.Body.Bytes(), &resp)
	if resp.Verified || resp.Active {
		t.Errorf("expected fail-closed invalid response on 429, got %+v", resp)
	}
}

func TestVerify_ContentTypeValidation(t *testing.T) {
	router, _ := setupTestRouter(&mockVerifier{}, 10, 10)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/google-play/verify", bytes.NewBufferString("{}"))
	req.Header.Set("Content-Type", "text/plain")
	rr := httptest.NewRecorder()
	router.ServeHTTP(rr, req)

	if rr.Code != http.StatusUnsupportedMediaType {
		t.Errorf("expected 415 Unsupported Media Type, got %d", rr.Code)
	}
}
