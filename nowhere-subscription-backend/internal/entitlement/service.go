package entitlement

import (
	"time"

	"nowhere-subscription-backend/internal/googleplay"
)

const (
	// EntitlementPro grants full pro access to the user.
	EntitlementPro = "pro"
	// EntitlementNone indicates no active pro access.
	EntitlementNone = "none"
)

// Response represents the verified entitlement JSON payload returned to clients.
type Response struct {
	Verified    bool   `json:"verified"`
	Entitlement string `json:"entitlement"`
	Active      bool   `json:"active"`
	ProductID   string `json:"productId,omitempty"`
	ExpiresAt   string `json:"expiresAt,omitempty"`
}

// Service evaluates verified Google Play subscription records into client entitlements.
type Service struct {
	clock func() time.Time
}

// NewService constructs an entitlement evaluation service.
func NewService() *Service {
	return &Service{
		clock: time.Now,
	}
}

// NewServiceWithClock constructs an entitlement evaluation service with a custom clock (for testing).
func NewServiceWithClock(clock func() time.Time) *Service {
	return &Service{
		clock: clock,
	}
}

// Evaluate evaluates a Google Play SubscriptionStatus and returns the entitlement Response.
// It fails closed for missing records, expired timestamps, non-entitled states, or unknown states.
func (s *Service) Evaluate(status *googleplay.SubscriptionStatus) Response {
	if status == nil {
		return Response{
			Verified:    false,
			Entitlement: EntitlementNone,
			Active:      false,
		}
	}

	now := s.clock().UTC()
	formattedExpiry := ""
	if !status.ExpiryTime.IsZero() {
		formattedExpiry = status.ExpiryTime.UTC().Format(time.RFC3339)
	}

	// 1. Strict Expiry check: if expiry time is zero or has passed, subscription is inactive.
	if status.ExpiryTime.IsZero() || !status.ExpiryTime.After(now) {
		return Response{
			Verified:    true,
			Entitlement: EntitlementNone,
			Active:      false,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}
	}

	// 2. Evaluate subscription state according to Google Play Subscriptions v2 lifecycle semantics
	switch status.SubscriptionState {
	case googleplay.StateActive:
		// ACTIVE: Active auto-renewing or prepaid plan with unexpired access.
		return Response{
			Verified:    true,
			Entitlement: EntitlementPro,
			Active:      true,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}

	case googleplay.StateInGracePeriod:
		// IN_GRACE_PERIOD: Payment failed on renewal, but Google Play explicitly retains entitlement
		// during the grace period window configured by the developer. Authoritative expiry time is not yet reached.
		return Response{
			Verified:    true,
			Entitlement: EntitlementPro,
			Active:      true,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}

	case googleplay.StateCanceled:
		// CANCELED: User turned off auto-renew / canceled subscription, but has already paid through
		// the current billing period. Access is retained until the verified ExpiryTime.
		return Response{
			Verified:    true,
			Entitlement: EntitlementPro,
			Active:      true,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}

	case googleplay.StatePending:
		// PENDING: Subscription was created but payment is awaiting processing during signup.
		// MUST NOT grant Pro.
		return Response{
			Verified:    true,
			Entitlement: EntitlementNone,
			Active:      false,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}

	case googleplay.StatePaused:
		// PAUSED: User temporarily paused the subscription.
		// MUST NOT grant Pro.
		return Response{
			Verified:    true,
			Entitlement: EntitlementNone,
			Active:      false,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}

	case googleplay.StateOnHold:
		// ON_HOLD: Payment failed and grace period expired without recovery; access is suspended.
		// MUST NOT grant Pro.
		return Response{
			Verified:    true,
			Entitlement: EntitlementNone,
			Active:      false,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}

	case googleplay.StateExpired:
		// EXPIRED: Subscription has ended.
		// MUST NOT grant Pro.
		return Response{
			Verified:    true,
			Entitlement: EntitlementNone,
			Active:      false,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}

	case googleplay.StatePendingPurchaseCanceled:
		// PENDING_PURCHASE_CANCELED: The pending purchase transaction was canceled.
		// MUST NOT grant Pro.
		return Response{
			Verified:    true,
			Entitlement: EntitlementNone,
			Active:      false,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}

	case googleplay.StateUnspecified:
		// UNSPECIFIED: Unspecified subscription state.
		// MUST NOT grant Pro.
		return Response{
			Verified:    true,
			Entitlement: EntitlementNone,
			Active:      false,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}

	default:
		// Unknown/future states: Must fail closed.
		return Response{
			Verified:    true,
			Entitlement: EntitlementNone,
			Active:      false,
			ProductID:   status.ProductID,
			ExpiresAt:   formattedExpiry,
		}
	}
}

// Invalid returns a standard fail-closed invalid response for errors or mismatches.
func Invalid() Response {
	return Response{
		Verified:    false,
		Entitlement: EntitlementNone,
		Active:      false,
	}
}
