package googleplay

import "time"

// Subscription states defined by Google Play Developer API Subscriptions v2.
const (
	StateUnspecified             = "SUBSCRIPTION_STATE_UNSPECIFIED"
	StatePending                 = "SUBSCRIPTION_STATE_PENDING"
	StateActive                  = "SUBSCRIPTION_STATE_ACTIVE"
	StatePaused                  = "SUBSCRIPTION_STATE_PAUSED"
	StateInGracePeriod           = "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"
	StateOnHold                  = "SUBSCRIPTION_STATE_ON_HOLD"
	StateCanceled                = "SUBSCRIPTION_STATE_CANCELED"
	StateExpired                 = "SUBSCRIPTION_STATE_EXPIRED"
	StatePendingPurchaseCanceled = "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED"
)

// Acknowledgement states defined by Google Play Developer API.
const (
	AckStateUnspecified  = "ACKNOWLEDGEMENT_STATE_UNSPECIFIED"
	AckStatePending      = "ACKNOWLEDGEMENT_STATE_PENDING"
	AckStateAcknowledged = "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"
)

// SubscriptionStatus encapsulates the normalized subscription details
// retrieved from the Google Play Developer API Subscriptions v2.
type SubscriptionStatus struct {
	PackageName          string
	ProductID            string
	SubscriptionState    string
	AcknowledgementState string
	StartTime            time.Time
	ExpiryTime           time.Time
	AutoRenewing         bool
	OrderID              string
	IsTestPurchase       bool
}
