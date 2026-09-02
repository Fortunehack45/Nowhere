package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"gopkg.in/yaml.v3"
)

// Node represents a real physical WireGuard server instance.
type Node struct {
	ID            string `yaml:"id" json:"id"`
	RegionGroup   string `yaml:"region_group" json:"region_group"`
	Country       string `yaml:"country" json:"country"`
	CountryName   string `yaml:"country_name" json:"country_name"`
	City          string `yaml:"city" json:"city"`
	Endpoint      string `yaml:"endpoint" json:"endpoint"`
	ServerPubkey  string `yaml:"server_pubkey" json:"server_pubkey"`
	SSHHost       string `yaml:"ssh_host" json:"-"`
	SSHPort       int    `yaml:"ssh_port" json:"-"`
	SSHUser       string `yaml:"ssh_user" json:"-"`
	SSHKeyPath    string `yaml:"ssh_key_path" json:"-"`
	TunnelSubnet  string `yaml:"tunnel_subnet" json:"-"`
	DNS           string `yaml:"dns" json:"dns"`
	Interface     string `yaml:"interface" json:"-"`
	CapacityPeers int    `yaml:"capacity_peers" json:"capacity_peers"`
	Enabled       *bool  `yaml:"enabled,omitempty" json:"enabled,omitempty"`
}

// LeasedRegion represents a country/region with no local VPS, routed via upstream proxy.
type LeasedRegion struct {
	ID            string `yaml:"id" json:"id"`
	Country       string `yaml:"country" json:"country"`
	CountryName   string `yaml:"country_name" json:"country_name"`
	City          string `yaml:"city" json:"city"`
	GatewayNodeID string `yaml:"gateway_node_id" json:"gateway_node_id"`
	Provider      string `yaml:"provider" json:"provider"`
	ProxyType     string `yaml:"proxy_type" json:"proxy_type"`
	ProxyHost     string `yaml:"proxy_host" json:"-"`
	ProxyPort     int    `yaml:"proxy_port" json:"-"`
	ProxyUser     string `yaml:"proxy_user" json:"-"`
	ProxyPassEnv  string `yaml:"proxy_pass_env" json:"-"`
}

type nodesFile struct {
	Nodes []Node `yaml:"nodes"`
}

type leasedRegionsFile struct {
	LeasedRegions []LeasedRegion `yaml:"leased_regions"`
}

// Registry manages in-memory static inventory with thread-safe hot reload.
type Registry struct {
	mu            sync.RWMutex
	configDir     string
	nodes         map[string]Node
	nodesList     []Node
	regionGroups  map[string][]Node
	countryNodes  map[string][]Node
	leased        map[string]LeasedRegion
	leasedList    []LeasedRegion
}

// NewRegistry initializes a new node & region registry.
func NewRegistry(configDir string) (*Registry, error) {
	r := &Registry{
		configDir:    configDir,
		nodes:        make(map[string]Node),
		regionGroups: make(map[string][]Node),
		countryNodes: make(map[string][]Node),
		leased:       make(map[string]LeasedRegion),
	}

	if err := r.Reload(); err != nil {
		return nil, err
	}

	return r, nil
}

// Reload reads nodes.yaml and leased_regions.yaml from the config directory.
func (r *Registry) Reload() error {
	r.mu.Lock()
	defer r.mu.Unlock()

	nodesPath := filepath.Join(r.configDir, "nodes.yaml")
	nodesData, err := os.ReadFile(nodesPath)
	if err != nil {
		return fmt.Errorf("failed to read nodes config at %s: %w", nodesPath, err)
	}

	var nf nodesFile
	if err := yaml.Unmarshal(nodesData, &nf); err != nil {
		return fmt.Errorf("failed to parse nodes.yaml: %w", err)
	}

	newNodes := make(map[string]Node, len(nf.Nodes))
	newNodesList := make([]Node, 0, len(nf.Nodes))
	newRegionGroups := make(map[string][]Node)
	newCountryNodes := make(map[string][]Node)

	for _, node := range nf.Nodes {
		if node.ID == "" || node.Endpoint == "" || node.ServerPubkey == "" {
			continue
		}
		if node.Enabled != nil && !*node.Enabled {
			continue // Exclude disabled/unprovisioned nodes from active routing
		}
		if node.SSHPort == 0 {
			node.SSHPort = 22
		}
		if node.SSHUser == "" {
			node.SSHUser = "deploy"
		}
		if node.Interface == "" {
			node.Interface = "wg0"
		}
		if node.DNS == "" {
			node.DNS = "1.1.1.1"
		}

		newNodes[node.ID] = node
		newNodesList = append(newNodesList, node)

		rg := strings.ToLower(node.RegionGroup)
		if rg == "" {
			rg = strings.ToLower(node.ID)
		}
		newRegionGroups[rg] = append(newRegionGroups[rg], node)

		country := strings.ToUpper(node.Country)
		newCountryNodes[country] = append(newCountryNodes[country], node)
	}

	// Parse leased regions if file exists
	newLeased := make(map[string]LeasedRegion)
	newLeasedList := make([]LeasedRegion, 0)

	leasedPath := filepath.Join(r.configDir, "leased_regions.yaml")
	if leasedData, err := os.ReadFile(leasedPath); err == nil {
		var lrf leasedRegionsFile
		if err := yaml.Unmarshal(leasedData, &lrf); err == nil {
			for _, lr := range lrf.LeasedRegions {
				if lr.ID == "" {
					continue
				}
				newLeased[lr.ID] = lr
				newLeasedList = append(newLeasedList, lr)
			}
		}
	}

	r.nodes = newNodes
	r.nodesList = newNodesList
	r.regionGroups = newRegionGroups
	r.countryNodes = newCountryNodes
	r.leased = newLeased
	r.leasedList = newLeasedList

	return nil
}

// GetNode returns a node by ID.
func (r *Registry) GetNode(id string) (Node, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	node, ok := r.nodes[id]
	return node, ok
}

// GetNodes returns all active real WireGuard nodes.
func (r *Registry) GetNodes() []Node {
	r.mu.RLock()
	defer r.mu.RUnlock()
	res := make([]Node, len(r.nodesList))
	copy(res, r.nodesList)
	return res
}

// GetNodesByRegionGroup returns all nodes belonging to a region group (for load balancing).
func (r *Registry) GetNodesByRegionGroup(group string) []Node {
	r.mu.RLock()
	defer r.mu.RUnlock()
	nodes, ok := r.regionGroups[strings.ToLower(group)]
	if !ok || len(nodes) == 0 {
		return nil
	}
	res := make([]Node, len(nodes))
	copy(res, nodes)
	return res
}

// GetNodesByCountry returns all nodes for an ISO country code.
func (r *Registry) GetNodesByCountry(country string) []Node {
	r.mu.RLock()
	defer r.mu.RUnlock()
	nodes, ok := r.countryNodes[strings.ToUpper(country)]
	if !ok || len(nodes) == 0 {
		return nil
	}
	res := make([]Node, len(nodes))
	copy(res, nodes)
	return res
}

// GetLeasedRegion returns a leased region exit by ID.
func (r *Registry) GetLeasedRegion(id string) (LeasedRegion, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	lr, ok := r.leased[id]
	return lr, ok
}

// GetLeasedRegions returns all leased proxy exit regions.
func (r *Registry) GetLeasedRegions() []LeasedRegion {
	r.mu.RLock()
	defer r.mu.RUnlock()
	res := make([]LeasedRegion, len(r.leasedList))
	copy(res, r.leasedList)
	return res
}
