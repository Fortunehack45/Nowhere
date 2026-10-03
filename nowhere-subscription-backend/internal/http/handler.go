package http

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"

	"nowhere-subscription-backend/internal/config"
	"nowhere-subscription-backend/internal/entitlement"
	"nowhere-subscription-backend/internal/googleplay"
	"nowhere-subscription-backend/internal/middleware"
)

// Handler manages HTTP endpoints for subscription verification.
type Handler struct {
	cfg            *config.Config
	verifier       googleplay.SubscriptionVerifier
	entitlementSvc *entitlement.Service
	logger         *slog.Logger
}

// NewHandler constructs a new Handler instance.
func NewHandler(
	cfg *config.Config,
	verifier googleplay.SubscriptionVerifier,
	entitlementSvc *entitlement.Service,
	logger *slog.Logger,
) *Handler {
	return &Handler{
		cfg:            cfg,
		verifier:       verifier,
		entitlementSvc: entitlementSvc,
		logger:         logger,
	}
}

// Root handles GET / with service status and endpoint discovery.
func (h *Handler) Root(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" && r.URL.Path != "/api/index.go" && r.URL.Path != "/api" && r.URL.Path != "/api/index" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"service": "Nowhere Subscription Verification Backend",
		"status":  "online",
		"health":  "/health",
		"verify":  "/api/v1/google-play/verify",
	})
}

// Health handles GET /health.
func (h *Handler) Health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(HealthResponse{Status: "ok"})
}

// Verify handles POST /api/v1/google-play/verify.
func (h *Handler) Verify(w http.ResponseWriter, r *http.Request) {
	reqID := middleware.GetRequestID(r.Context())

	// 1. Validate HTTP Method
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		h.writeFailClosedJSON(w, http.StatusMethodNotAllowed)
		return
	}

	// 2. Validate Content-Type
	contentType := r.Header.Get("Content-Type")
	if !strings.HasPrefix(strings.ToLower(contentType), "application/json") {
		h.logger.Warn("invalid_content_type",
			slog.String("request_id", reqID),
			slog.String("content_type", contentType),
		)
		h.writeFailClosedJSON(w, http.StatusUnsupportedMediaType)
		return
	}

	// 3. Enforce Request Body Size Limit
	r.Body = http.MaxBytesReader(w, r.Body, h.cfg.MaxRequestBodyBytes)

	// 4. Strict JSON Decoding with unknown field rejection
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()

	var req VerifyRequest
	if err := dec.Decode(&req); err != nil {
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			h.logger.Warn("request_body_too_large",
				slog.String("request_id", reqID),
				slog.Int64("limit_bytes", h.cfg.MaxRequestBodyBytes),
			)
			h.writeFailClosedJSON(w, http.StatusRequestEntityTooLarge)
			return
		}

		h.logger.Warn("malformed_json_request",
			slog.String("request_id", reqID),
			slog.String("error", err.Error()),
		)
		h.writeFailClosedJSON(w, http.StatusBadRequest)
		return
	}

	// Ensure no extra data exists after the JSON object
	if dec.More() {
		h.logger.Warn("extraneous_json_payload", slog.String("request_id", reqID))
		h.writeFailClosedJSON(w, http.StatusBadRequest)
		return
	}

	// 5. Validate Required Fields & Package Name
	trimmedPkg := strings.TrimSpace(req.PackageName)
	trimmedProd := strings.TrimSpace(req.ProductID)
	trimmedToken := strings.TrimSpace(req.PurchaseToken)

	if trimmedPkg == "" || trimmedProd == "" || trimmedToken == "" {
		h.logger.Warn("missing_required_fields",
			slog.String("request_id", reqID),
			slog.Bool("has_package", trimmedPkg != ""),
			slog.Bool("has_product", trimmedProd != ""),
			slog.Bool("has_token", trimmedToken != ""),
		)
		h.writeFailClosedJSON(w, http.StatusBadRequest)
		return
	}

	if trimmedPkg != h.cfg.AndroidPackageName {
		h.logger.Warn("package_name_mismatch",
			slog.String("request_id", reqID),
			slog.String("received_pkg", trimmedPkg),
			slog.String("configured_pkg", h.cfg.AndroidPackageName),
		)
		h.writeFailClosedJSON(w, http.StatusBadRequest)
		return
	}

	// 6. Validate Product ID against allowlist
	if !h.cfg.IsProductAllowed(trimmedProd) {
		h.logger.Warn("product_not_allowed",
			slog.String("request_id", reqID),
			slog.String("product_id", trimmedProd),
		)
		h.writeFailClosedJSON(w, http.StatusBadRequest)
		return
	}

	// 7. Verify subscription with Google Play Developer API (Strictly omitting token from logs)
	ctx, cancel := context.WithTimeout(r.Context(), h.cfg.RequestTimeout)
	defer cancel()

	status, err := h.verifier.VerifySubscription(ctx, trimmedPkg, trimmedProd, trimmedToken)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			h.logger.Error("google_play_api_timeout",
				slog.String("request_id", reqID),
				slog.String("product_id", trimmedProd),
			)
			h.writeFailClosedJSON(w, http.StatusGatewayTimeout)
			return
		}

		if errors.Is(err, googleplay.ErrPurchaseNotFound) ||
			errors.Is(err, googleplay.ErrInvalidPurchaseToken) ||
			errors.Is(err, googleplay.ErrProductMismatch) ||
			errors.Is(err, googleplay.ErrPackageMismatch) {
			h.logger.Warn("verification_failed_unverified",
				slog.String("request_id", reqID),
				slog.String("product_id", trimmedProd),
				slog.String("reason", err.Error()),
			)
			// Return 200 with verified: false per response spec
			h.writeFailClosedJSON(w, http.StatusOK)
			return
		}

		// Google upstream internal error
		h.logger.Error("google_play_api_failure",
			slog.String("request_id", reqID),
			slog.String("product_id", trimmedProd),
			slog.String("error", err.Error()),
		)
		h.writeFailClosedJSON(w, http.StatusBadGateway)
		return
	}

	// 8. Calculate Entitlement
	result := h.entitlementSvc.Evaluate(status)

	h.logger.Info("verification_completed",
		slog.String("request_id", reqID),
		slog.String("product_id", result.ProductID),
		slog.Bool("verified", result.Verified),
		slog.Bool("active", result.Active),
		slog.String("entitlement", result.Entitlement),
	)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(result)
}

func (h *Handler) writeFailClosedJSON(w http.ResponseWriter, statusCode int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(entitlement.Invalid())
}

// DrainBody drains and closes reader to enable connection reuse.
func DrainBody(r io.ReadCloser) {
	if r != nil {
		_, _ = io.Copy(io.Discard, r)
		_ = r.Close()
	}
}
