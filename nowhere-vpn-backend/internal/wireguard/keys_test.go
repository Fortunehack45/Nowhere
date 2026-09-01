package wireguard

import (
	"encoding/base64"
	"testing"
)

func TestGenerateKeyPair(t *testing.T) {
	kp, err := GenerateKeyPair()
	if err != nil {
		t.Fatalf("GenerateKeyPair() failed: %v", err)
	}

	if kp.PrivateKeyBase64 == "" {
		t.Error("expected non-empty private key")
	}
	if kp.PublicKeyBase64 == "" {
		t.Error("expected non-empty public key")
	}

	privBytes, err := base64.StdEncoding.DecodeString(kp.PrivateKeyBase64)
	if err != nil || len(privBytes) != 32 {
		t.Errorf("expected 32-byte base64 private key, got error: %v, len: %d", err, len(privBytes))
	}

	pubBytes, err := base64.StdEncoding.DecodeString(kp.PublicKeyBase64)
	if err != nil || len(pubBytes) != 32 {
		t.Errorf("expected 32-byte base64 public key, got error: %v, len: %d", err, len(pubBytes))
	}

	if !ValidatePublicKey(kp.PublicKeyBase64) {
		t.Errorf("ValidatePublicKey(%s) = false; want true", kp.PublicKeyBase64)
	}
}

func TestPublicKeyFromPrivate(t *testing.T) {
	kp, err := GenerateKeyPair()
	if err != nil {
		t.Fatalf("GenerateKeyPair() failed: %v", err)
	}

	computedPub, err := PublicKeyFromPrivate(kp.PrivateKeyBase64)
	if err != nil {
		t.Fatalf("PublicKeyFromPrivate failed: %v", err)
	}

	if computedPub != kp.PublicKeyBase64 {
		t.Errorf("expected computed public key %s to match %s", computedPub, kp.PublicKeyBase64)
	}
}

func TestValidatePublicKey(t *testing.T) {
	if ValidatePublicKey("invalid-base64") {
		t.Error("expected false for invalid base64")
	}
	if ValidatePublicKey(base64.StdEncoding.EncodeToString([]byte("short"))) {
		t.Error("expected false for < 32 bytes")
	}
}
