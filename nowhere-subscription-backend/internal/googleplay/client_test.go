package googleplay

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/api/androidpublisher/v3"
	"google.golang.org/api/option"
)

func TestClient_VerifySubscription_Success(t *testing.T) {
	mockExpiry := time.Now().Add(30 * 24 * time.Hour).UTC().Truncate(time.Second)
	mockStart := time.Now().Add(-24 * time.Hour).UTC().Truncate(time.Second)

	mockResponse := androidpublisher.SubscriptionPurchaseV2{
		SubscriptionState:    StateActive,
		AcknowledgementState: AckStateAcknowledged,
		StartTime:            mockStart.Format(time.RFC3339),
		LineItems: []*androidpublisher.SubscriptionPurchaseLineItem{
			{
				ProductId:  "nowhere_premium",
				ExpiryTime: mockExpiry.Format(time.RFC3339),
				AutoRenewingPlan: &androidpublisher.AutoRenewingPlan{
					AutoRenewEnabled: true,
				},
				LatestSuccessfulOrderId: "GPA.1234-5678-9012-34567",
			},
		},
		TestPurchase: &androidpublisher.TestPurchase{},
	}

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(mockResponse)
	}))
	defer ts.Close()

	ctx := context.Background()
	client, err := NewClient(ctx, option.WithEndpoint(ts.URL), option.WithoutAuthentication())
	if err != nil {
		t.Fatalf("unexpected error creating client: %v", err)
	}

	status, err := client.VerifySubscription(ctx, "com.nowhere.gps.locationchanger", "nowhere_premium", "mock_token")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if status.SubscriptionState != StateActive {
		t.Errorf("expected state %s, got %s", StateActive, status.SubscriptionState)
	}
	if status.ProductID != "nowhere_premium" {
		t.Errorf("expected product ID nowhere_premium, got %s", status.ProductID)
	}
	if !status.ExpiryTime.Equal(mockExpiry) {
		t.Errorf("expected expiry %v, got %v", mockExpiry, status.ExpiryTime)
	}
	if !status.AutoRenewing {
		t.Errorf("expected autoRenewing true")
	}
	if !status.IsTestPurchase {
		t.Errorf("expected isTestPurchase true")
	}
	if status.OrderID != "GPA.1234-5678-9012-34567" {
		t.Errorf("expected order ID GPA.1234-5678-9012-34567, got %s", status.OrderID)
	}
}

func TestClient_VerifySubscription_WithAutoAcknowledgment(t *testing.T) {
	mockExpiry := time.Now().Add(30 * 24 * time.Hour).UTC().Truncate(time.Second)

	mockResponse := androidpublisher.SubscriptionPurchaseV2{
		SubscriptionState:    StateActive,
		AcknowledgementState: AckStatePending, // Pending acknowledgment
		LineItems: []*androidpublisher.SubscriptionPurchaseLineItem{
			{
				ProductId:  "nowhere_premium",
				ExpiryTime: mockExpiry.Format(time.RFC3339),
			},
		},
	}

	var acknowledgedCallCount int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method == http.MethodPost && (r.URL.Path != "" && len(r.URL.Path) > 5) {
			// Acknowledge endpoint called
			atomic.AddInt32(&acknowledgedCallCount, 1)
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("{}"))
			return
		}
		_ = json.NewEncoder(w).Encode(mockResponse)
	}))
	defer ts.Close()

	ctx := context.Background()
	client, err := NewClient(ctx, option.WithEndpoint(ts.URL), option.WithoutAuthentication())
	if err != nil {
		t.Fatalf("unexpected error creating client: %v", err)
	}

	status, err := client.VerifySubscription(ctx, "com.nowhere.gps.locationchanger", "nowhere_premium", "mock_token")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if atomic.LoadInt32(&acknowledgedCallCount) != 1 {
		t.Errorf("expected acknowledge endpoint to be called once, got %d", acknowledgedCallCount)
	}
	if status.AcknowledgementState != AckStateAcknowledged {
		t.Errorf("expected acknowledgement state to be updated to acknowledged, got %s", status.AcknowledgementState)
	}
}

func TestClient_VerifySubscription_RetryOn5xx(t *testing.T) {
	mockExpiry := time.Now().Add(30 * 24 * time.Hour).UTC().Truncate(time.Second)
	mockResponse := androidpublisher.SubscriptionPurchaseV2{
		SubscriptionState: StateActive,
		LineItems: []*androidpublisher.SubscriptionPurchaseLineItem{
			{
				ProductId:  "nowhere_premium",
				ExpiryTime: mockExpiry.Format(time.RFC3339),
			},
		},
	}

	var attempts int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		att := atomic.AddInt32(&attempts, 1)
		if att == 1 {
			// First attempt fails with 503 Service Unavailable
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"error": {"code": 503, "message": "Backend transient error"}}`))
			return
		}
		// Second attempt succeeds
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(mockResponse)
	}))
	defer ts.Close()

	ctx := context.Background()
	client, err := NewClient(ctx, option.WithEndpoint(ts.URL), option.WithoutAuthentication())
	if err != nil {
		t.Fatalf("unexpected error creating client: %v", err)
	}

	status, err := client.VerifySubscription(ctx, "com.nowhere.gps.locationchanger", "nowhere_premium", "mock_token")
	if err != nil {
		t.Fatalf("expected retry to succeed, got error: %v", err)
	}

	if attempts != 2 {
		t.Errorf("expected 2 attempts (1 retry), got %d", attempts)
	}
	if status.SubscriptionState != StateActive {
		t.Errorf("expected StateActive, got %s", status.SubscriptionState)
	}
}

func TestClient_VerifySubscription_NoRetryOn404(t *testing.T) {
	var attempts int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&attempts, 1)
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error": {"code": 404, "message": "Purchase token not found"}}`))
	}))
	defer ts.Close()

	ctx := context.Background()
	client, err := NewClient(ctx, option.WithEndpoint(ts.URL), option.WithoutAuthentication())
	if err != nil {
		t.Fatalf("unexpected error creating client: %v", err)
	}

	_, err = client.VerifySubscription(ctx, "com.nowhere.gps.locationchanger", "nowhere_premium", "invalid_token")
	if !errors.Is(err, ErrPurchaseNotFound) {
		t.Errorf("expected ErrPurchaseNotFound, got %v", err)
	}

	// Must NOT retry 404
	if attempts != 1 {
		t.Errorf("expected exactly 1 attempt without retry for 404, got %d", attempts)
	}
}

func TestClient_VerifySubscription_NoRetryOn400(t *testing.T) {
	var attempts int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&attempts, 1)
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte(`{"error": {"code": 400, "message": "Invalid purchase token"}}`))
	}))
	defer ts.Close()

	ctx := context.Background()
	client, err := NewClient(ctx, option.WithEndpoint(ts.URL), option.WithoutAuthentication())
	if err != nil {
		t.Fatalf("unexpected error creating client: %v", err)
	}

	_, err = client.VerifySubscription(ctx, "com.nowhere.gps.locationchanger", "nowhere_premium", "malformed_token")
	if !errors.Is(err, ErrInvalidPurchaseToken) {
		t.Errorf("expected ErrInvalidPurchaseToken, got %v", err)
	}

	if attempts != 1 {
		t.Errorf("expected exactly 1 attempt without retry for 400, got %d", attempts)
	}
}

func TestClient_VerifySubscription_ProductMismatch(t *testing.T) {
	mockResponse := androidpublisher.SubscriptionPurchaseV2{
		SubscriptionState: StateActive,
		LineItems: []*androidpublisher.SubscriptionPurchaseLineItem{
			{
				ProductId:  "another_unrelated_product",
				ExpiryTime: time.Now().Add(24 * time.Hour).Format(time.RFC3339),
			},
		},
	}

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(mockResponse)
	}))
	defer ts.Close()

	ctx := context.Background()
	client, err := NewClient(ctx, option.WithEndpoint(ts.URL), option.WithoutAuthentication())
	if err != nil {
		t.Fatalf("unexpected error creating client: %v", err)
	}

	_, err = client.VerifySubscription(ctx, "com.nowhere.gps.locationchanger", "nowhere_premium", "mock_token")
	if !errors.Is(err, ErrProductMismatch) {
		t.Errorf("expected ErrProductMismatch, got %v", err)
	}
}

func TestClient_VerifySubscription_EmptyFields(t *testing.T) {
	ctx := context.Background()
	client := &Client{}

	if _, err := client.VerifySubscription(ctx, "", "prod", "tok"); !errors.Is(err, ErrPackageMismatch) {
		t.Errorf("expected ErrPackageMismatch for empty package, got %v", err)
	}
	if _, err := client.VerifySubscription(ctx, "pkg", "", "tok"); !errors.Is(err, ErrProductMismatch) {
		t.Errorf("expected ErrProductMismatch for empty product, got %v", err)
	}
	if _, err := client.VerifySubscription(ctx, "pkg", "prod", ""); !errors.Is(err, ErrInvalidPurchaseToken) {
		t.Errorf("expected ErrInvalidPurchaseToken for empty token, got %v", err)
	}
}

func TestClient_VerifySubscription_InvalidExpiry(t *testing.T) {
	mockResponse := androidpublisher.SubscriptionPurchaseV2{
		SubscriptionState: StateActive,
		LineItems: []*androidpublisher.SubscriptionPurchaseLineItem{
			{
				ProductId:  "nowhere_premium",
				ExpiryTime: "not-a-timestamp",
			},
		},
	}

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(mockResponse)
	}))
	defer ts.Close()

	ctx := context.Background()
	client, err := NewClient(ctx, option.WithEndpoint(ts.URL), option.WithoutAuthentication())
	if err != nil {
		t.Fatalf("unexpected error creating client: %v", err)
	}

	_, err = client.VerifySubscription(ctx, "com.nowhere.gps.locationchanger", "nowhere_premium", "mock_token")
	if !errors.Is(err, ErrInvalidExpiryTime) {
		t.Errorf("expected ErrInvalidExpiryTime, got %v", err)
	}
}
