package wireguard

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"

	"golang.org/x/crypto/curve25519"
)

// KeyPair represents a Curve25519 WireGuard keypair.
type KeyPair struct {
	PrivateKeyBase64 string `json:"private_key"`
	PublicKeyBase64  string `json:"public_key"`
}

// GenerateKeyPair generates a cryptographically secure WireGuard Curve25519 keypair.
func GenerateKeyPair() (*KeyPair, error) {
	var privateKey [32]byte
	if _, err := rand.Read(privateKey[:]); err != nil {
		return nil, fmt.Errorf("failed to generate random bytes for private key: %w", err)
	}

	// WireGuard private key clamping (RFC 7748 / Curve25519 spec)
	privateKey[0] &= 248
	privateKey[31] = (privateKey[31] & 127) | 64

	var publicKey [32]byte
	curve25519.ScalarBaseMult(&publicKey, &privateKey)

	return &KeyPair{
		PrivateKeyBase64: base64.StdEncoding.EncodeToString(privateKey[:]),
		PublicKeyBase64:  base64.StdEncoding.EncodeToString(publicKey[:]),
	}, nil
}

// PublicKeyFromPrivate computes the base64 public key from a base64 private key.
func PublicKeyFromPrivate(privateKeyBase64 string) (string, error) {
	privBytes, err := base64.StdEncoding.DecodeString(privateKeyBase64)
	if err != nil {
		return "", fmt.Errorf("invalid base64 private key: %w", err)
	}
	if len(privBytes) != 32 {
		return "", errors.New("private key must be exactly 32 bytes")
	}

	var privateKey [32]byte
	copy(privateKey[:], privBytes)

	// Clamp
	privateKey[0] &= 248
	privateKey[31] = (privateKey[31] & 127) | 64

	var publicKey [32]byte
	curve25519.ScalarBaseMult(&publicKey, &privateKey)

	return base64.StdEncoding.EncodeToString(publicKey[:]), nil
}

// ValidatePublicKey checks if a base64 string is a valid 32-byte WireGuard public key.
func ValidatePublicKey(publicKeyBase64 string) bool {
	pubBytes, err := base64.StdEncoding.DecodeString(publicKeyBase64)
	if err != nil {
		return false
	}
	return len(pubBytes) == 32
}
