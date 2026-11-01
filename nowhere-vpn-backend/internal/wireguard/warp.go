package wireguard

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

const (
	CloudflareWarpEndpoint  = "162.159.192.1:2408"
	CloudflareWarpPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
	CloudflareWarpRegURL    = "https://api.cloudflareclient.com/v0a2158/reg"
)

type warpRegResponse struct {
	ID     string `json:"id"`
	Config struct {
		ClientID string `json:"client_id"`
		Peers    []struct {
			PublicKey string `json:"public_key"`
			Endpoint  struct {
				V4   string `json:"v4"`
				V6   string `json:"v6"`
				Host string `json:"host"`
			} `json:"endpoint"`
		} `json:"peers"`
		Interface struct {
			Addresses struct {
				V4 string `json:"v4"`
				V6 string `json:"v6"`
			} `json:"addresses"`
		} `json:"interface"`
	} `json:"config"`
}

// ProvisionWarpPeer registers a free client account on Cloudflare WARP and returns the WireGuard config.
func ProvisionWarpPeer(ctx context.Context, clientPubkey string, clientPrivKey string) (*TunnelConfig, error) {
	if clientPubkey == "" {
		kp, err := GenerateKeyPair()
		if err != nil {
			return nil, fmt.Errorf("failed generating client keypair: %w", err)
		}
		clientPubkey = kp.PublicKeyBase64
		clientPrivKey = kp.PrivateKeyBase64
	}

	reqPayload := map[string]interface{}{
		"key":        clientPubkey,
		"install_id": "",
		"tos":        time.Now().UTC().Format("2006-01-02T15:04:05.000Z07:00"),
		"type":       "Android",
		"model":      "Nowhere MockLocation",
		"locale":     "en_US",
	}

	payloadBytes, err := json.Marshal(reqPayload)
	if err != nil {
		return nil, fmt.Errorf("failed marshaling warp request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, "POST", CloudflareWarpRegURL, bytes.NewBuffer(payloadBytes))
	if err != nil {
		return nil, fmt.Errorf("failed creating warp request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "okhttp/3.12.1")

	client := &http.Client{Timeout: 8 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fallbackWarpConfig(clientPubkey, clientPrivKey), nil
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("cloudflare registration returned status %d: %s", resp.StatusCode, string(body))
	}

	var warpResp warpRegResponse
	if err := json.NewDecoder(resp.Body).Decode(&warpResp); err != nil {
		return fallbackWarpConfig(clientPubkey, clientPrivKey), nil
	}

	assignedIP := warpResp.Config.Interface.Addresses.V4
	if assignedIP == "" {
		assignedIP = "172.16.0.2"
	}
	if !bytes.Contains([]byte(assignedIP), []byte("/")) {
		assignedIP = assignedIP + "/32"
	}

	serverPubkey := CloudflareWarpPublicKey
	if len(warpResp.Config.Peers) > 0 && warpResp.Config.Peers[0].PublicKey != "" {
		serverPubkey = warpResp.Config.Peers[0].PublicKey
	}

	return &TunnelConfig{
		NodeID:           "cloudflare_warp",
		Country:          "US",
		CountryName:      "Cloudflare Global Anycast",
		City:             "Global Fast Shield (300+ Cities)",
		ServerPubkey:     serverPubkey,
		Endpoint:         CloudflareWarpEndpoint,
		AssignedIP:       assignedIP,
		ClientPrivateKey: clientPrivKey,
		ClientPublicKey:  clientPubkey,
		AllowedIPs:       []string{"0.0.0.0/0", "::/0"},
		DNS:              []string{"1.1.1.1", "1.0.0.1"},
		MTU:              1280,
	}, nil
}

func fallbackWarpConfig(clientPubkey, clientPrivKey string) *TunnelConfig {
	return &TunnelConfig{
		NodeID:           "cloudflare_warp",
		Country:          "US",
		CountryName:      "Cloudflare Global Anycast",
		City:             "Global Fast Shield (300+ Cities)",
		ServerPubkey:     CloudflareWarpPublicKey,
		Endpoint:         CloudflareWarpEndpoint,
		AssignedIP:       "172.16.0.2/32",
		ClientPrivateKey: clientPrivKey,
		ClientPublicKey:  clientPubkey,
		AllowedIPs:       []string{"0.0.0.0/0", "::/0"},
		DNS:              []string{"1.1.1.1", "1.0.0.1"},
		MTU:              1280,
	}
}
