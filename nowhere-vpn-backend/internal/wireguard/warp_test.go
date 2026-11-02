package wireguard

import (
	"context"
	"testing"
	"time"
)

func TestProvisionWarpPeer(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	kp, err := GenerateKeyPair()
	if err != nil {
		t.Fatalf("Failed generating keypair: %v", err)
	}

	config, err := ProvisionWarpPeer(ctx, kp.PublicKeyBase64, kp.PrivateKeyBase64)
	if err != nil {
		t.Fatalf("ProvisionWarpPeer failed: %v", err)
	}

	if config.NodeID != "cloudflare_warp" {
		t.Errorf("Expected node_id cloudflare_warp, got %s", config.NodeID)
	}

	if config.Endpoint != CloudflareWarpEndpoint {
		t.Errorf("Expected endpoint %s, got %s", CloudflareWarpEndpoint, config.Endpoint)
	}

	if config.ServerPubkey != CloudflareWarpPublicKey {
		t.Errorf("Expected server pubkey %s, got %s", CloudflareWarpPublicKey, config.ServerPubkey)
	}

	if config.MTU != 1280 {
		t.Errorf("Expected MTU 1280, got %d", config.MTU)
	}

	if len(config.DNS) == 0 || config.DNS[0] != "1.1.1.1" {
		t.Errorf("Expected DNS 1.1.1.1, got %v", config.DNS)
	}
}

func TestProvisionWarpPeer_InvalidKeyRecovery(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Pass an invalid public key (which Cloudflare rejects with 401)
	invalidKey := "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
	config, err := ProvisionWarpPeer(ctx, invalidKey, "")
	if err != nil {
		t.Fatalf("ProvisionWarpPeer should recover from invalid key, but returned error: %v", err)
	}

	if config == nil || config.ServerPubkey == "" {
		t.Fatalf("Expected valid config after recovery, got nil or empty pubkey")
	}

	if config.ClientPublicKey == "" {
		t.Errorf("Expected regenerated ClientPublicKey, got empty")
	}
}
