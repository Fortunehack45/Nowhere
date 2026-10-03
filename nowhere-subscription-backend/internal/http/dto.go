package http

// VerifyRequest represents the incoming JSON payload for subscription verification.
type VerifyRequest struct {
	PackageName   string `json:"packageName"`
	ProductID     string `json:"productId"`
	PurchaseToken string `json:"purchaseToken"`
}

// HealthResponse represents the health check response payload.
type HealthResponse struct {
	Status string `json:"status"`
}
