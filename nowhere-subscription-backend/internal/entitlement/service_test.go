package entitlement

import (
	"encoding/json"
	"testing"
	"time"

	"nowhere-subscription-backend/internal/googleplay"
)

func TestEntitlement_Evaluate(t *testing.T) {
	fixedNow := time.Date(2026, 10, 3, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return fixedNow }
	svc := NewServiceWithClock(clock)

	futureExpiry := fixedNow.Add(24 * time.Hour)
	pastExpiry := fixedNow.Add(-24 * time.Hour)

	tests := []struct {
		name            string
		status          *googleplay.SubscriptionStatus
		wantVerified    bool
		wantEntitlement string
		wantActive      bool
		wantProductID   string
		wantExpiry      string
	}{
		{
			name:            "Nil status returns invalid",
			status:          nil,
			wantVerified:    false,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "",
			wantExpiry:      "",
		},
		{
			name: "Active subscription with future expiry grants pro",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StateActive,
				ExpiryTime:        futureExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementPro,
			wantActive:      true,
			wantProductID:   "nowhere_premium",
			wantExpiry:      futureExpiry.Format(time.RFC3339),
		},
		{
			name: "Grace period subscription with future expiry grants pro",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StateInGracePeriod,
				ExpiryTime:        futureExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementPro,
			wantActive:      true,
			wantProductID:   "nowhere_premium",
			wantExpiry:      futureExpiry.Format(time.RFC3339),
		},
		{
			name: "Canceled subscription with future expiry still grants pro until period ends",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium_yearly",
				SubscriptionState: googleplay.StateCanceled,
				ExpiryTime:        futureExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementPro,
			wantActive:      true,
			wantProductID:   "nowhere_premium_yearly",
			wantExpiry:      futureExpiry.Format(time.RFC3339),
		},
		{
			name: "Canceled subscription with past expiry is inactive",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StateCanceled,
				ExpiryTime:        pastExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      pastExpiry.Format(time.RFC3339),
		},
		{
			name: "Active state but past expiry fails closed as inactive",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StateActive,
				ExpiryTime:        pastExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      pastExpiry.Format(time.RFC3339),
		},
		{
			name: "Paused subscription does not grant entitlement",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StatePaused,
				ExpiryTime:        futureExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      futureExpiry.Format(time.RFC3339),
		},
		{
			name: "On hold subscription does not grant entitlement",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StateOnHold,
				ExpiryTime:        futureExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      futureExpiry.Format(time.RFC3339),
		},
		{
			name: "Pending subscription awaiting payment does not grant entitlement",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StatePending,
				ExpiryTime:        futureExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      futureExpiry.Format(time.RFC3339),
		},
		{
			name: "Expired subscription state is inactive",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StateExpired,
				ExpiryTime:        pastExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      pastExpiry.Format(time.RFC3339),
		},
		{
			name: "Pending purchase canceled is inactive",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StatePendingPurchaseCanceled,
				ExpiryTime:        futureExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      futureExpiry.Format(time.RFC3339),
		},
		{
			name: "Unspecified subscription state does not grant entitlement",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StateUnspecified,
				ExpiryTime:        futureExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      futureExpiry.Format(time.RFC3339),
		},
		{
			name: "Unknown future state fails closed to none",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: "SUBSCRIPTION_STATE_SOME_NEW_FUTURE_STATE",
				ExpiryTime:        futureExpiry,
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      futureExpiry.Format(time.RFC3339),
		},
		{
			name: "Zero expiry time fails closed to none",
			status: &googleplay.SubscriptionStatus{
				ProductID:         "nowhere_premium",
				SubscriptionState: googleplay.StateActive,
				ExpiryTime:        time.Time{},
			},
			wantVerified:    true,
			wantEntitlement: EntitlementNone,
			wantActive:      false,
			wantProductID:   "nowhere_premium",
			wantExpiry:      "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			res := svc.Evaluate(tt.status)
			if res.Verified != tt.wantVerified {
				t.Errorf("verified: got %v, want %v", res.Verified, tt.wantVerified)
			}
			if res.Entitlement != tt.wantEntitlement {
				t.Errorf("entitlement: got %s, want %s", res.Entitlement, tt.wantEntitlement)
			}
			if res.Active != tt.wantActive {
				t.Errorf("active: got %v, want %v", res.Active, tt.wantActive)
			}
			if res.ProductID != tt.wantProductID {
				t.Errorf("productId: got %s, want %s", res.ProductID, tt.wantProductID)
			}
			if res.ExpiresAt != tt.wantExpiry {
				t.Errorf("expiresAt: got %s, want %s", res.ExpiresAt, tt.wantExpiry)
			}
		})
	}
}

func TestEntitlement_JSONMarshalling(t *testing.T) {
	// Test Invalid response omits productId and expiresAt
	inv := Invalid()
	bytes, err := json.Marshal(inv)
	if err != nil {
		t.Fatalf("failed to marshal invalid: %v", err)
	}

	expectedJSON := `{"verified":false,"entitlement":"none","active":false}`
	if string(bytes) != expectedJSON {
		t.Errorf("got %s, want %s", string(bytes), expectedJSON)
	}

	// Test Active response includes all fields
	active := Response{
		Verified:    true,
		Entitlement: EntitlementPro,
		Active:      true,
		ProductID:   "nowhere_premium",
		ExpiresAt:   "2026-11-03T08:00:00Z",
	}
	bytesActive, err := json.Marshal(active)
	if err != nil {
		t.Fatalf("failed to marshal active: %v", err)
	}
	expectedActive := `{"verified":true,"entitlement":"pro","active":true,"productId":"nowhere_premium","expiresAt":"2026-11-03T08:00:00Z"}`
	if string(bytesActive) != expectedActive {
		t.Errorf("got %s, want %s", string(bytesActive), expectedActive)
	}
}
