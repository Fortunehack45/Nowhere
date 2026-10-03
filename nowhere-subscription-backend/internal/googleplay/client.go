package googleplay

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"time"

	"google.golang.org/api/androidpublisher/v3"
	"google.golang.org/api/googleapi"
	"google.golang.org/api/option"
)

// Client implements SubscriptionVerifier using the official Google Play Developer API v3.
type Client struct {
	service *androidpublisher.Service
}

// NewClient initializes a new Google Play API client using Application Default Credentials (ADC),
// environment variable credentials (GOOGLE_CREDENTIALS_JSON / GOOGLE_CREDENTIALS_BASE64 for Vercel),
// or provided client options.
func NewClient(ctx context.Context, opts ...option.ClientOption) (*Client, error) {
	var allOpts []option.ClientOption
	if len(opts) == 0 {
		allOpts = []option.ClientOption{
			option.WithScopes(androidpublisher.AndroidpublisherScope),
		}

		// Support Vercel / Serverless environments where JSON credentials are passed via environment variables
		if rawJSON := strings.TrimSpace(os.Getenv("GOOGLE_CREDENTIALS_JSON")); rawJSON != "" {
			allOpts = append(allOpts, option.WithCredentialsJSON([]byte(rawJSON)))
		} else if b64 := strings.TrimSpace(os.Getenv("GOOGLE_CREDENTIALS_BASE64")); b64 != "" {
			decoded, err := base64.StdEncoding.DecodeString(b64)
			if err == nil && len(decoded) > 0 {
				allOpts = append(allOpts, option.WithCredentialsJSON(decoded))
			}
		} else if credPath := strings.TrimSpace(os.Getenv("GOOGLE_APPLICATION_CREDENTIALS")); credPath != "" {
			allOpts = append(allOpts, option.WithCredentialsFile(credPath))
		}
	} else {
		allOpts = opts
	}

	svc, err := androidpublisher.NewService(ctx, allOpts...)
	if err != nil {
		return nil, fmt.Errorf("failed to create google play developer api service: %w", err)
	}

	return &Client{service: svc}, nil
}

// VerifySubscription queries Google Play Subscriptions v2 API, handles transient retry/backoff,
// acknowledges pending entitled purchases where required, and extracts verified subscription state.
//
// The backend always queries Google Play afresh and never trusts cached client state.
func (c *Client) VerifySubscription(
	ctx context.Context,
	packageName string,
	productID string,
	purchaseToken string,
) (*SubscriptionStatus, error) {
	if strings.TrimSpace(packageName) == "" {
		return nil, ErrPackageMismatch
	}
	if strings.TrimSpace(productID) == "" {
		return nil, ErrProductMismatch
	}
	if strings.TrimSpace(purchaseToken) == "" {
		return nil, ErrInvalidPurchaseToken
	}

	// Call Google Play Subscriptions v2 Get API with retry/backoff for transient failures
	purchase, err := c.getSubscriptionWithRetry(ctx, packageName, purchaseToken)
	if err != nil {
		var gErr *googleapi.Error
		if errors.As(err, &gErr) {
			switch gErr.Code {
			case 404:
				return nil, ErrPurchaseNotFound
			case 400:
				return nil, ErrInvalidPurchaseToken
			default:
				return nil, fmt.Errorf("%w: upstream status %d", ErrGoogleAPI, gErr.Code)
			}
		}
		return nil, fmt.Errorf("%w: %v", ErrGoogleAPI, err)
	}

	if purchase == nil {
		return nil, ErrPurchaseNotFound
	}

	// Match product ID within subscription LineItems
	var matchingItem *androidpublisher.SubscriptionPurchaseLineItem
	for _, item := range purchase.LineItems {
		if item != nil && item.ProductId == productID {
			matchingItem = item
			break
		}
	}

	if matchingItem == nil {
		return nil, ErrProductMismatch
	}

	// Parse Expiration Time from LineItem
	var expiryTime time.Time
	if matchingItem.ExpiryTime != "" {
		parsedExpiry, parseErr := time.Parse(time.RFC3339, matchingItem.ExpiryTime)
		if parseErr != nil {
			return nil, fmt.Errorf("%w: %v", ErrInvalidExpiryTime, parseErr)
		}
		expiryTime = parsedExpiry
	} else {
		return nil, ErrInvalidExpiryTime
	}

	// Parse Start Time if present
	var startTime time.Time
	if purchase.StartTime != "" {
		if parsedStart, parseErr := time.Parse(time.RFC3339, purchase.StartTime); parseErr == nil {
			startTime = parsedStart
		}
	}

	// Acknowledge the purchase where required:
	// If acknowledgment is pending and Google indicates an active or entitled subscription,
	// acknowledge the purchase to prevent automatic cancellation/refund by Google after 3 days.
	currentAckState := purchase.AcknowledgementState
	if currentAckState == AckStatePending &&
		(purchase.SubscriptionState == StateActive ||
			purchase.SubscriptionState == StateInGracePeriod ||
			purchase.SubscriptionState == StateCanceled) {
		if ackErr := c.AcknowledgeSubscription(ctx, packageName, matchingItem.ProductId, purchaseToken); ackErr == nil {
			currentAckState = AckStateAcknowledged
		}
	}

	autoRenewing := false
	if matchingItem.AutoRenewingPlan != nil && matchingItem.AutoRenewingPlan.AutoRenewEnabled {
		autoRenewing = true
	}

	isTest := purchase.TestPurchase != nil

	return &SubscriptionStatus{
		PackageName:          packageName,
		ProductID:            matchingItem.ProductId,
		SubscriptionState:    purchase.SubscriptionState,
		AcknowledgementState: currentAckState,
		StartTime:            startTime,
		ExpiryTime:           expiryTime,
		AutoRenewing:         autoRenewing,
		OrderID:              matchingItem.LatestSuccessfulOrderId,
		IsTestPurchase:       isTest,
	}, nil
}

// AcknowledgeSubscription acknowledges a subscription purchase via the Google Play Developer API.
func (c *Client) AcknowledgeSubscription(
	ctx context.Context,
	packageName string,
	productID string,
	purchaseToken string,
) error {
	req := &androidpublisher.SubscriptionPurchasesAcknowledgeRequest{}
	return c.service.Purchases.Subscriptions.Acknowledge(packageName, productID, purchaseToken, req).Context(ctx).Do()
}

// getSubscriptionWithRetry calls Purchases.Subscriptionsv2.Get with exponential backoff
// for transient network and 5xx errors, without retrying permanent 4xx client errors indefinitely.
func (c *Client) getSubscriptionWithRetry(
	ctx context.Context,
	packageName string,
	purchaseToken string,
) (*androidpublisher.SubscriptionPurchaseV2, error) {
	const maxAttempts = 3
	backoffs := []time.Duration{100 * time.Millisecond, 250 * time.Millisecond}

	var lastErr error
	for attempt := 0; attempt < maxAttempts; attempt++ {
		purchase, err := c.service.Purchases.Subscriptionsv2.Get(packageName, purchaseToken).Context(ctx).Do()
		if err == nil {
			return purchase, nil
		}
		lastErr = err

		// If error is not transient (e.g. 400, 404, 401, 403) or this was our last attempt, stop immediately
		if !isRetryable(err) || attempt == maxAttempts-1 || ctx.Err() != nil {
			return nil, lastErr
		}

		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(backoffs[attempt]):
		}
	}

	return nil, lastErr
}

// isRetryable determines whether an error from Google Play Developer API is transient.
func isRetryable(err error) bool {
	if err == nil {
		return false
	}

	// 1. Google API status code evaluation
	var gErr *googleapi.Error
	if errors.As(err, &gErr) {
		// Do not retry 4xx errors except 429 (Too Many Requests)
		if gErr.Code >= 400 && gErr.Code < 500 {
			return gErr.Code == 429
		}
		// Retry 5xx errors (500 Internal, 502 Bad Gateway, 503 Service Unavailable, 504 Gateway Timeout)
		if gErr.Code >= 500 && gErr.Code < 600 {
			return true
		}
		return false
	}

	// 2. Network timeouts and temporary socket errors
	var netErr net.Error
	if errors.As(err, &netErr) {
		return true
	}

	// 3. Premature EOF / socket resets
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return true
	}

	return false
}
