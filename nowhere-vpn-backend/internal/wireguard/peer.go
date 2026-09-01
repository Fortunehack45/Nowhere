package wireguard

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"nowhere-vpn-backend/internal/config"
	"nowhere-vpn-backend/internal/ssh"
)

// TunnelConfig is the ready-to-use WireGuard configuration returned to the client.
type TunnelConfig struct {
	NodeID           string   `json:"node_id"`
	Country          string   `json:"country"`
	CountryName      string   `json:"country_name"`
	City             string   `json:"city"`
	ServerPubkey     string   `json:"server_pubkey"`
	Endpoint         string   `json:"endpoint"`
	AssignedIP       string   `json:"assigned_ip"`
	ClientPrivateKey string   `json:"client_private_key,omitempty"`
	ClientPublicKey  string   `json:"client_public_key"`
	AllowedIPs       []string `json:"allowed_ips"`
	DNS              []string `json:"dns"`
	MTU              int      `json:"mtu"`
}

// NodeTransferLoad captures live network throughput/peer count on a WireGuard node.
type NodeTransferLoad struct {
	Node       config.Node
	PeerCount  int
	TotalRxTx  uint64
	Available  bool
	Error      error
}

// Manager orchestrates remote WireGuard peer provisioning without local database storage.
type Manager struct {
	sshPool *ssh.ClientPool
}

// NewManager creates a new WireGuard peer manager.
func NewManager(sshPool *ssh.ClientPool) *Manager {
	return &Manager{
		sshPool: sshPool,
	}
}

// ProvisionPeer creates a new peer on the selected node and returns the complete tunnel config.
func (m *Manager) ProvisionPeer(
	ctx context.Context,
	node config.Node,
	clientPubkey string,
	clientPrivKey string,
) (*TunnelConfig, error) {
	if clientPubkey == "" {
		kp, err := GenerateKeyPair()
		if err != nil {
			return nil, fmt.Errorf("failed generating client keypair: %w", err)
		}
		clientPubkey = kp.PublicKeyBase64
		clientPrivKey = kp.PrivateKeyBase64
	}

	if !ValidatePublicKey(clientPubkey) {
		return nil, errors.New("invalid client WireGuard public key (must be valid 32-byte base64)")
	}

	iface := node.Interface
	if iface == "" {
		iface = "wg0"
	}

	// 1. Fetch live assigned IPs from server
	usedIPs, err := m.GetAssignedIPs(ctx, node)
	if err != nil {
		return nil, fmt.Errorf("failed inspecting live WireGuard peers on %s: %w", node.ID, err)
	}

	// 2. Allocate lowest free IP in tunnel subnet
	assignedIP, err := AllocateFreeIP(node.TunnelSubnet, usedIPs)
	if err != nil {
		return nil, fmt.Errorf("ip exhaustion on node %s: %w", node.ID, err)
	}

	// 3. Register peer on WireGuard server
	// Run: wg set <iface> peer <pubkey> allowed-ips <ip>/32
	addCmd := fmt.Sprintf("sudo wg set %s peer %s allowed-ips %s/32", iface, clientPubkey, assignedIP.String())
	if _, err := m.sshPool.Run(ctx, node, addCmd); err != nil {
		return nil, fmt.Errorf("failed adding peer to WireGuard on %s: %w", node.ID, err)
	}

	// 4. Persist configuration asynchronously so it survives server reboots
	go func() {
		saveCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		saveCmd := fmt.Sprintf("sudo wg-quick save %s || true", iface)
		_, _ = m.sshPool.Run(saveCtx, node, saveCmd)
	}()

	dnsList := []string{"1.1.1.1", "1.0.0.1"}
	if node.DNS != "" {
		dnsList = strings.Split(node.DNS, ",")
		for i := range dnsList {
			dnsList[i] = strings.TrimSpace(dnsList[i])
		}
	}

	return &TunnelConfig{
		NodeID:           node.ID,
		Country:          node.Country,
		CountryName:      node.CountryName,
		City:             node.City,
		ServerPubkey:     node.ServerPubkey,
		Endpoint:         node.Endpoint,
		AssignedIP:       fmt.Sprintf("%s/32", assignedIP.String()),
		ClientPrivateKey: clientPrivKey,
		ClientPublicKey:  clientPubkey,
		AllowedIPs:       []string{"0.0.0.0/0", "::/0"},
		DNS:              dnsList,
		MTU:              1420,
	}, nil
}

// RemovePeer removes a peer from the specified WireGuard node by public key.
func (m *Manager) RemovePeer(ctx context.Context, node config.Node, clientPubkey string) error {
	if !ValidatePublicKey(clientPubkey) {
		return errors.New("invalid client WireGuard public key")
	}

	iface := node.Interface
	if iface == "" {
		iface = "wg0"
	}

	removeCmd := fmt.Sprintf("sudo wg set %s peer %s remove", iface, clientPubkey)
	if _, err := m.sshPool.Run(ctx, node, removeCmd); err != nil {
		return fmt.Errorf("failed removing peer %s from node %s: %w", clientPubkey, node.ID, err)
	}

	// Persist
	go func() {
		saveCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		saveCmd := fmt.Sprintf("sudo wg-quick save %s || true", iface)
		_, _ = m.sshPool.Run(saveCtx, node, saveCmd)
	}()

	return nil
}

// GetAssignedIPs queries `wg show <iface> allowed-ips` and returns all assigned IP addresses.
func (m *Manager) GetAssignedIPs(ctx context.Context, node config.Node) (map[string]bool, error) {
	iface := node.Interface
	if iface == "" {
		iface = "wg0"
	}

	cmd := fmt.Sprintf("sudo wg show %s allowed-ips", iface)
	out, err := m.sshPool.Run(ctx, node, cmd)
	if err != nil {
		return nil, err
	}

	used := make(map[string]bool)
	lines := strings.Split(out, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		// Format: <peer_pubkey>\t<ip1>/32 <ip2>/32
		parts := strings.Fields(line)
		if len(parts) >= 2 {
			for _, cidr := range parts[1:] {
				ip, _, err := net.ParseCIDR(cidr)
				if err == nil {
					used[ip.String()] = true
				}
			}
		}
	}

	return used, nil
}

// AllocateFreeIP finds the lowest unused host IP in the specified CIDR subnet.
func AllocateFreeIP(subnetCIDR string, usedIPs map[string]bool) (net.IP, error) {
	ip, ipNet, err := net.ParseCIDR(subnetCIDR)
	if err != nil {
		return nil, fmt.Errorf("invalid subnet CIDR '%s': %w", subnetCIDR, err)
	}

	ip4 := ip.To4()
	if ip4 == nil {
		return nil, errors.New("only IPv4 subnets are currently supported for peer assignment")
	}

	// Clone base IP
	curr := make(net.IP, len(ip4))
	copy(curr, ip4)

	// Scan through host space starting from .2 (reserving .0 network and .1 gateway)
	for {
		incrementIP(curr)
		if !ipNet.Contains(curr) {
			break
		}

		// Skip gateway (.1) and broadcast
		if isGatewayOrBroadcast(curr, ipNet) {
			continue
		}

		if !usedIPs[curr.String()] {
			allocated := make(net.IP, len(curr))
			copy(allocated, curr)
			return allocated, nil
		}
	}

	return nil, fmt.Errorf("no free IP addresses remaining in subnet %s", subnetCIDR)
}

func incrementIP(ip net.IP) {
	for j := len(ip) - 1; j >= 0; j-- {
		ip[j]++
		if ip[j] > 0 {
			break
		}
	}
}

func isGatewayOrBroadcast(ip net.IP, ipNet *net.IPNet) bool {
	ip4 := ip.To4()
	if ip4 == nil {
		return false
	}
	// Gateway is typically .1
	if ip4[3] == 0 || ip4[3] == 1 || ip4[3] == 255 {
		return true
	}
	return false
}

// SelectBestNodeByLoad queries candidate nodes concurrently and picks the lowest loaded node.
func (m *Manager) SelectBestNodeByLoad(ctx context.Context, candidateNodes []config.Node) (config.Node, error) {
	if len(candidateNodes) == 0 {
		return config.Node{}, errors.New("no candidate nodes provided")
	}
	if len(candidateNodes) == 1 {
		return candidateNodes[0], nil
	}

	type result struct {
		node      config.Node
		peerCount int
		rxTxBytes uint64
		err       error
	}

	resultsChan := make(chan result, len(candidateNodes))
	var wg sync.WaitGroup

	for _, n := range candidateNodes {
		wg.Add(1)
		go func(node config.Node) {
			defer wg.Done()
			subCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
			defer cancel()

			iface := node.Interface
			if iface == "" {
				iface = "wg0"
			}

			// Check transfer stats: wg show <iface> transfer
			cmd := fmt.Sprintf("sudo wg show %s transfer", iface)
			out, err := m.sshPool.Run(subCtx, node, cmd)
			if err != nil {
				resultsChan <- result{node: node, err: err}
				return
			}

			var peerCount int
			var totalBytes uint64
			for _, line := range strings.Split(out, "\n") {
				line = strings.TrimSpace(line)
				if line == "" {
					continue
				}
				fields := strings.Fields(line)
				// Format: <peer_pubkey> <rx_bytes> <tx_bytes>
				if len(fields) >= 3 {
					peerCount++
					rx, _ := strconv.ParseUint(fields[1], 10, 64)
					tx, _ := strconv.ParseUint(fields[2], 10, 64)
					totalBytes += (rx + tx)
				}
			}

			resultsChan <- result{
				node:      node,
				peerCount: peerCount,
				rxTxBytes: totalBytes,
				err:       nil,
			}
		}(n)
	}

	wg.Wait()
	close(resultsChan)

	var bestNode *config.Node
	minPeers := int(^uint(0) >> 1)
	var minBytes uint64 = ^uint64(0)

	for res := range resultsChan {
		if res.err != nil {
			continue
		}
		if res.peerCount < minPeers || (res.peerCount == minPeers && res.rxTxBytes < minBytes) {
			minPeers = res.peerCount
			minBytes = res.rxTxBytes
			chosen := res.node
			bestNode = &chosen
		}
	}

	if bestNode != nil {
		return *bestNode, nil
	}

	// Fallback to first candidate if live queries failed
	return candidateNodes[0], nil
}
