package middleware

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"runtime/debug"

	"nowhere-subscription-backend/internal/entitlement"
)

// Recoverer catches unhandled panics, logs the error, and returns a safe fail-closed JSON response.
func Recoverer(logger *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			defer func() {
				if rvr := recover(); rvr != nil {
					reqID := GetRequestID(r.Context())
					stack := string(debug.Stack())

					logger.Error("panic_recovered",
						slog.String("request_id", reqID),
						slog.String("error", fmt.Sprintf("%v", rvr)),
						slog.String("stack", stack),
					)

					w.Header().Set("Content-Type", "application/json")
					w.WriteHeader(http.StatusInternalServerError)
					_ = json.NewEncoder(w).Encode(entitlement.Invalid())
				}
			}()

			next.ServeHTTP(w, r)
		})
	}
}
