# Stage 1: Build the Go binary statically
FROM golang:1.24-alpine AS builder

WORKDIR /app

# Install git and ca-certificates
RUN apk add --no-cache git ca-certificates tzdata

# Leverage Docker cache for module dependencies
COPY go.mod go.sum ./
RUN go mod download && go mod verify

# Copy application source
COPY . .

# Compile optimized static binary
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -trimpath \
    -ldflags="-s -w -extldflags '-static'" \
    -o /app/server ./cmd/server

# Stage 2: Minimal, secure runtime image
FROM gcr.io/distroless/static-debian12:nonroot

WORKDIR /

# Copy trusted CA certificates for Google API TLS
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=builder /usr/share/zoneinfo /usr/share/zoneinfo

# Copy compiled static binary
COPY --from=builder /app/server /server

# Cloud Run defaults to PORT 8080
ENV PORT=8080
EXPOSE 8080

# Non-root user (distroless nonroot: nonroot)
USER nonroot:nonroot

ENTRYPOINT ["/server"]
