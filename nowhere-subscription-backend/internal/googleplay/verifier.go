package googleplay

import (
	"context"
	"errors"
)

// Standard domain errors returned by SubscriptionVerifier implementations.
var (
	ErrInvalidPurchaseToken = errors.New("empty or invalid purchase token")
	ErrPurchaseNotFound     = errors.New("subscription purchase not found on google play")
	ErrProductMismatch      = errors.New("product ID mismatch with subscription record")
	ErrPackageMismatch      = errors.New("package name mismatch with subscription record")
	ErrGoogleAPI            = errors.New("google play developer api call failed")
	ErrInvalidExpiryTime    = errors.New("invalid or missing expiration time in subscription")
)

// SubscriptionVerifier defines the contract for verifying subscriptions against Google Play.
type SubscriptionVerifier interface {
	VerifySubscription(
		ctx context.Context,
		packageName string,
		productID string,
		purchaseToken string,
	) (*SubscriptionStatus, error)
}
