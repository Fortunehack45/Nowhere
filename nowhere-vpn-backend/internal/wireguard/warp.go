package wireguard

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

const (
	CloudflareWarpEndpoint  = "162.159.192.1:2408"
	CloudflareWarpPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
	CloudflareWarpRegURL    = "https://api.cloudflareclient.com/v0a2158/reg"
	WarpClientVersion       = "a-6.10-2158"
	WarpUserAgent           = "okhttp/3.12.1"
)

type warpRegResponse struct {
	ID     string `json:"id"`
	Token  string `json:"token"`
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

// ProvisionWarpPeer registers a genuine client account on Cloudflare WARP and activates egress routing.
func ProvisionWarpPeer(ctx context.Context, clientPubkey string, clientPrivKey string) (*TunnelConfig, error) {
	// If public key is not provided, generate a cryptographically valid Curve25519 keypair
	if clientPubkey == "" {
		kp, err := GenerateKeyPair()
		if err != nil {
			return nil, fmt.Errorf("failed generating WireGuard keypair: %w", err)
		}
		clientPubkey = kp.PublicKeyBase64
		clientPrivKey = kp.PrivateKeyBase64
	}

	reqPayload := map[string]interface{}{
		"key":        clientPubkey,
		"install_id": "",
		"fcm_token":  "",
		"tos":        time.Now().UTC().Format("2006-01-02T15:04:05.000Z07:00"),
		"model":      "PC",
		"locale":     "en_US",
	}

	payloadBytes, err := json.Marshal(reqPayload)
	if err != nil {
		return nil, fmt.Errorf("failed marshaling registration payload: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, "POST", CloudflareWarpRegURL, bytes.NewBuffer(payloadBytes))
	if err != nil {
		return nil, fmt.Errorf("failed building registration request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", WarpUserAgent)
	req.Header.Set("CF-Client-Version", WarpClientVersion)

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("network error during Cloudflare registration: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		// If Cloudflare rejected an existing client key (e.g. 401 Invalid public key), retry with fresh key
		kp, keyErr := GenerateKeyPair()
		if keyErr == nil && kp.PublicKeyBase64 != clientPubkey {
			return ProvisionWarpPeer(ctx, kp.PublicKeyBase64, kp.PrivateKeyBase64)
		}
		return nil, fmt.Errorf("cloudflare registration rejected (HTTP %d)", resp.StatusCode)
	}

	var warpResp warpRegResponse
	if err := json.NewDecoder(resp.Body).Decode(&warpResp); err != nil {
		return nil, fmt.Errorf("failed decoding registration response: %w", err)
	}

	// Activate WARP routing via PATCH request
	if warpResp.ID != "" && warpResp.Token != "" {
		patchPayload := map[string]interface{}{
			"warp_enabled": true,
		}
		patchBytes, _ := json.Marshal(patchPayload)
		patchURL := fmt.Sprintf("%s/%s", CloudflareWarpRegURL, warpResp.ID)
		patchReq, patchErr := http.NewRequestWithContext(ctx, "PATCH", patchURL, bytes.NewBuffer(patchBytes))
		if patchErr == nil {
			patchReq.Header.Set("Content-Type", "application/json")
			patchReq.Header.Set("Authorization", "Bearer "+warpResp.Token)
			patchReq.Header.Set("User-Agent", WarpUserAgent)
			patchReq.Header.Set("CF-Client-Version", WarpClientVersion)
			patchResp, patchDoErr := client.Do(patchReq)
			if patchDoErr == nil {
				patchResp.Body.Close()
			}
		}
	}

	assignedIPv4 := warpResp.Config.Interface.Addresses.V4
	if assignedIPv4 == "" {
		assignedIPv4 = "172.16.0.2"
	}
	if !strings.Contains(assignedIPv4, "/") {
		assignedIPv4 = assignedIPv4 + "/32"
	}

	assignedIPv6 := warpResp.Config.Interface.Addresses.V6
	if assignedIPv6 != "" && !strings.Contains(assignedIPv6, "/") {
		assignedIPv6 = assignedIPv6 + "/128"
	}

	serverPubkey := CloudflareWarpPublicKey
	endpoint := CloudflareWarpEndpoint
	if len(warpResp.Config.Peers) > 0 {
		peer := warpResp.Config.Peers[0]
		if peer.PublicKey != "" {
			serverPubkey = peer.PublicKey
		}
		if peer.Endpoint.Host != "" {
			endpoint = peer.Endpoint.Host
		} else if peer.Endpoint.V4 != "" {
			endpoint = peer.Endpoint.V4
		}
	}

	return &TunnelConfig{
		NodeID:           "cloudflare_warp",
		Country:          "US",
		CountryName:      "Cloudflare Global Anycast",
		City:             "Global Fast Shield (300+ Cities)",
		ServerPubkey:     serverPubkey,
		Endpoint:         endpoint,
		AssignedIP:       assignedIPv4,
		AssignedIPv6:     assignedIPv6,
		ClientPrivateKey: clientPrivKey,
		ClientPublicKey:  clientPubkey,
		AllowedIPs:       []string{"0.0.0.0/0", "::/0"},
		DNS:              []string{"1.1.1.1", "1.0.0.1"},
		MTU:              1280,
	}, nil
}
