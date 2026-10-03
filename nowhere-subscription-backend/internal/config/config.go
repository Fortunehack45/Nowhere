package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config holds the application configuration.
type Config struct {
	// AndroidPackageName is the exact package name expected from client requests.
	AndroidPackageName string

	// AllowedProductIDs is the list of permitted Google Play subscription product IDs.
	AllowedProductIDs map[string]struct{}

	// AllowedProductIDsList is the raw list of allowed product IDs for reporting/logging.
	AllowedProductIDsList []string

	// Port is the HTTP server listening port.
	Port string

	// MaxRequestBodyBytes limits the maximum size of incoming request bodies.
	MaxRequestBodyBytes int64

	// RateLimitRPS is the allowed requests per second per IP.
	RateLimitRPS float64

	// RateLimitBurst is the burst capacity per IP.
	RateLimitBurst int

	// RequestTimeout is the timeout applied to downstream verification requests.
	RequestTimeout time.Duration

	// Environment indicates "production", "development", or "test".
	Environment string
}

// LoadFromEnv loads configuration from environment variables and validates required settings.
func LoadFromEnv() (*Config, error) {
	pkgName := strings.TrimSpace(os.Getenv("ANDROID_PACKAGE_NAME"))
	if pkgName == "" {
		return nil, errors.New("ANDROID_PACKAGE_NAME environment variable is required")
	}

	rawProductIDs := os.Getenv("GOOGLE_PLAY_PRODUCT_IDS")
	if strings.TrimSpace(rawProductIDs) == "" {
		return nil, errors.New("GOOGLE_PLAY_PRODUCT_IDS environment variable is required")
	}

	productIDsMap := make(map[string]struct{})
	var productIDsList []string
	for _, id := range strings.Split(rawProductIDs, ",") {
		trimmed := strings.TrimSpace(id)
		if trimmed != "" {
			productIDsMap[trimmed] = struct{}{}
			productIDsList = append(productIDsList, trimmed)
		}
	}
	if len(productIDsMap) == 0 {
		return nil, errors.New("GOOGLE_PLAY_PRODUCT_IDS must contain at least one valid product ID")
	}

	port := strings.TrimSpace(os.Getenv("PORT"))
	if port == "" {
		port = "8080"
	}

	maxBodyBytes := int64(16384) // 16 KB default
	if envBytes := os.Getenv("MAX_REQUEST_BODY_BYTES"); envBytes != "" {
		if val, err := strconv.ParseInt(envBytes, 10, 64); err == nil && val > 0 {
			maxBodyBytes = val
		}
	}

	rateRPS := 10.0
	if envRPS := os.Getenv("RATE_LIMIT_RPS"); envRPS != "" {
		if val, err := strconv.ParseFloat(envRPS, 64); err == nil && val > 0 {
			rateRPS = val
		}
	}

	rateBurst := 20
	if envBurst := os.Getenv("RATE_LIMIT_BURST"); envBurst != "" {
		if val, err := strconv.Atoi(envBurst); err == nil && val > 0 {
			rateBurst = val
		}
	}

	reqTimeout := 10 * time.Second
	if envTimeout := os.Getenv("REQUEST_TIMEOUT_SECONDS"); envTimeout != "" {
		if val, err := strconv.Atoi(envTimeout); err == nil && val > 0 {
			reqTimeout = time.Duration(val) * time.Second
		}
	}

	env := strings.ToLower(strings.TrimSpace(os.Getenv("APP_ENV")))
	if env == "" {
		env = "production"
	}

	return &Config{
		AndroidPackageName:    pkgName,
		AllowedProductIDs:     productIDsMap,
		AllowedProductIDsList: productIDsList,
		Port:                  port,
		MaxRequestBodyBytes:   maxBodyBytes,
		RateLimitRPS:          rateRPS,
		RateLimitBurst:        rateBurst,
		RequestTimeout:        reqTimeout,
		Environment:           env,
	}, nil
}

// IsProductAllowed checks if a product ID exists in the configured allowlist.
func (c *Config) IsProductAllowed(productID string) bool {
	if c == nil || c.AllowedProductIDs == nil {
		return false
	}
	_, allowed := c.AllowedProductIDs[productID]
	return allowed
}

// Address returns the formatted host:port string for http.Server.
func (c *Config) Address() string {
	return fmt.Sprintf("0.0.0.0:%s", c.Port)
}
