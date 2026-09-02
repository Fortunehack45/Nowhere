# ==============================================================================
# Build Stage
# ==============================================================================
FROM golang:1.21-alpine AS builder

WORKDIR /app

# Install build dependencies
RUN apk add --no-cache git ca-certificates tzdata

# Cache Go modules
COPY go.mod go.sum ./
RUN go mod download

# Copy source code
COPY . .

# Build static binary with stripped symbols
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -ldflags="-s -w -extldflags '-static'" \
    -o /app/nowhere-vpn-backend main.go

# ==============================================================================
# Production Minimal Image
# ==============================================================================
FROM alpine:3.19

RUN apk add --no-cache ca-certificates tzdata openssh-client wireguard-tools sudo iproute2 iptables

WORKDIR /app

# Create secrets and config directories
RUN mkdir -p /app/config /secrets

# Copy binary from builder
COPY --from=builder /app/nowhere-vpn-backend /app/nowhere-vpn-backend
COPY config/ /app/config/

# Environment Defaults
ENV PORT=8080
ENV CONFIG_DIR=/app/config
ENV SSH_DEFAULT_KEY=/secrets/nowhere_deploy.pem

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["/app/nowhere-vpn-backend"]
