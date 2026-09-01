package proxy

import (
	"context"
	"fmt"
	"os"

	"nowhere-vpn-backend/internal/config"
	"nowhere-vpn-backend/internal/ssh"
	"nowhere-vpn-backend/internal/wireguard"
)

// LeasedExitHandler handles country exit routing via upstream proxy networks.
type LeasedExitHandler struct {
	wgManager *wireguard.Manager
	sshPool   *ssh.ClientPool
	registry  *config.Registry
}

// NewLeasedExitHandler creates a new leased exit handler.
func NewLeasedExitHandler(wgManager *wireguard.Manager, sshPool *ssh.ClientPool, registry *config.Registry) *LeasedExitHandler {
	return &LeasedExitHandler{
		wgManager: wgManager,
		sshPool:   sshPool,
		registry:  registry,
	}
}

// ProvisionLeasedPeer provisions a WireGuard peer on the gateway node and attaches proxy policy routing.
func (h *LeasedExitHandler) ProvisionLeasedPeer(
	ctx context.Context,
	leasedRegion config.LeasedRegion,
	clientPubkey string,
	clientPrivKey string,
) (*wireguard.TunnelConfig, error) {
	// 1. Resolve Gateway Node
	gatewayNode, ok := h.registry.GetNode(leasedRegion.GatewayNodeID)
	if !ok {
		// Fallback: pick any active node in US/EU
		nodes := h.registry.GetNodes()
		if len(nodes) == 0 {
			return nil, fmt.Errorf("no gateway nodes available for leased region %s", leasedRegion.ID)
		}
		gatewayNode = nodes[0]
	}

	// 2. Provision WireGuard peer on the Gateway Node
	tunnelConfig, err := h.wgManager.ProvisionPeer(ctx, gatewayNode, clientPubkey, clientPrivKey)
	if err != nil {
		return nil, fmt.Errorf("failed provisioning wireguard peer on gateway %s: %w", gatewayNode.ID, err)
	}

	// 3. Configure server-side egress policy routing on the gateway node
	// Route client's assigned IP through the upstream proxy daemon (e.g. redsocks/sing-box transparent proxy)
	proxyPass := os.Getenv(leasedRegion.ProxyPassEnv)
	if proxyPass == "" {
		proxyPass = "default_pass"
	}

	assignedIPClean := tunnelConfig.AssignedIP
	if len(assignedIPClean) > 3 && assignedIPClean[len(assignedIPClean)-3:] == "/32" {
		assignedIPClean = assignedIPClean[:len(assignedIPClean)-3]
	}

	// Mark packets from this specific client tunnel IP to route via proxy redirect port
	// e.g. iptables -t nat -A PREROUTING -s <assigned_ip> -p tcp -j REDIRECT --to-ports 12345
	policyCmd := fmt.Sprintf(
		"sudo iptables -t nat -C PREROUTING -s %s -p tcp -j REDIRECT --to-ports 12345 2>/dev/null || "+
			"sudo iptables -t nat -A PREROUTING -s %s -p tcp -j REDIRECT --to-ports 12345",
		assignedIPClean, assignedIPClean,
	)

	// Execute policy routing rule on the gateway node
	_, _ = h.sshPool.Run(ctx, gatewayNode, policyCmd)

	// 4. Override tunnel config metadata to represent the target leased country
	tunnelConfig.NodeID = leasedRegion.ID
	tunnelConfig.Country = leasedRegion.Country
	tunnelConfig.CountryName = leasedRegion.CountryName
	tunnelConfig.City = leasedRegion.City

	return tunnelConfig, nil
}
