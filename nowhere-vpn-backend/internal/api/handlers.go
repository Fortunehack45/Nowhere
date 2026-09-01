package api

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"nowhere-vpn-backend/internal/config"
	"nowhere-vpn-backend/internal/proxy"
	"nowhere-vpn-backend/internal/wireguard"
)

// ConnectRequest represents the payload from Nowhere Android client.
type ConnectRequest struct {
	NodeID          string `json:"node_id"`
	RegionGroup     string `json:"region_group"`
	Country         string `json:"country"`
	ClientPublicKey string `json:"client_public_key"`
}

// DisconnectRequest represents the payload to cleanly remove a WireGuard peer.
type DisconnectRequest struct {
	NodeID          string `json:"node_id"`
	ClientPublicKey string `json:"client_public_key"`
}

// Server provides HTTP routing and handlers for Nowhere VPN control-plane.
type Server struct {
	registry     *config.Registry
	wgManager    *wireguard.Manager
	leasedExit   *proxy.LeasedExitHandler
	startTime    time.Time
}

// NewServer creates a new API server instance.
func NewServer(
	registry *config.Registry,
	wgManager *wireguard.Manager,
	leasedExit *proxy.LeasedExitHandler,
) *Server {
	return &Server{
		registry:   registry,
		wgManager:  wgManager,
		leasedExit: leasedExit,
		startTime:  time.Now(),
	}
}

// HandleConnect processes POST /api/v1/connect.
func (s *Server) HandleConnect(w http.ResponseWriter, r *http.Request) {
	var req ConnectRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		respondJSON(w, http.StatusBadRequest, map[string]string{"status": "error", "message": "invalid JSON payload"})
		return
	}

	ctx := r.Context()

	// 1. Check if requested target is a Leased Proxy Region first
	if req.NodeID != "" {
		if leased, ok := s.registry.GetLeasedRegion(req.NodeID); ok {
			tunnelConfig, err := s.leasedExit.ProvisionLeasedPeer(ctx, leased, req.ClientPublicKey, "")
			if err != nil {
				respondJSON(w, http.StatusInternalServerError, map[string]string{"status": "error", "message": err.Error()})
				return
			}
			respondJSON(w, http.StatusOK, tunnelConfig)
			return
		}
	}

	// 2. Resolve target WireGuard node(s)
	var candidateNodes []config.Node

	if req.NodeID != "" {
		if node, ok := s.registry.GetNode(req.NodeID); ok {
			candidateNodes = []config.Node{node}
		}
	} else if req.RegionGroup != "" {
		candidateNodes = s.registry.GetNodesByRegionGroup(req.RegionGroup)
	} else if req.Country != "" {
		candidateNodes = s.registry.GetNodesByCountry(req.Country)
	}

	if len(candidateNodes) == 0 {
		// Default: choose lowest load node globally
		candidateNodes = s.registry.GetNodes()
		if len(candidateNodes) == 0 {
			respondJSON(w, http.StatusServiceUnavailable, map[string]string{
				"status":  "error",
				"message": "no WireGuard server nodes currently available",
			})
			return
		}
	}

	// 3. Pick lowest loaded node among candidates
	selectedNode, err := s.wgManager.SelectBestNodeByLoad(ctx, candidateNodes)
	if err != nil {
		selectedNode = candidateNodes[0]
	}

	// 4. Provision WireGuard peer on live node
	tunnelConfig, err := s.wgManager.ProvisionPeer(ctx, selectedNode, req.ClientPublicKey, "")
	if err != nil {
		respondJSON(w, http.StatusInternalServerError, map[string]string{
			"status":  "error",
			"message": "failed provisioning WireGuard peer: " + err.Error(),
		})
		return
	}

	respondJSON(w, http.StatusOK, tunnelConfig)
}

// HandleDisconnect processes DELETE /api/v1/disconnect.
func (s *Server) HandleDisconnect(w http.ResponseWriter, r *http.Request) {
	var req DisconnectRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		respondJSON(w, http.StatusBadRequest, map[string]string{"status": "error", "message": "invalid JSON payload"})
		return
	}

	if req.ClientPublicKey == "" {
		respondJSON(w, http.StatusBadRequest, map[string]string{"status": "error", "message": "missing client_public_key"})
		return
	}

	ctx := r.Context()

	// Check if node is leased region
	targetNodeID := req.NodeID
	if leased, ok := s.registry.GetLeasedRegion(req.NodeID); ok {
		targetNodeID = leased.GatewayNodeID
	}

	node, ok := s.registry.GetNode(targetNodeID)
	if !ok {
		// If node ID not provided, attempt remove across nodes
		respondJSON(w, http.StatusBadRequest, map[string]string{"status": "error", "message": "node_id not found"})
		return
	}

	if err := s.wgManager.RemovePeer(ctx, node, req.ClientPublicKey); err != nil {
		respondJSON(w, http.StatusInternalServerError, map[string]string{
			"status":  "error",
			"message": "failed removing peer: " + err.Error(),
		})
		return
	}

	respondJSON(w, http.StatusOK, map[string]string{
		"status":  "success",
		"message": "peer removed from WireGuard node",
	})
}

// HandleListNodes processes GET /api/v1/nodes.
func (s *Server) HandleListNodes(w http.ResponseWriter, r *http.Request) {
	nodes := s.registry.GetNodes()
	respondJSON(w, http.StatusOK, map[string]interface{}{
		"status": "success",
		"count":  len(nodes),
		"nodes":  nodes,
	})
}

// HandleListRegions processes GET /api/v1/regions (direct nodes + leased proxy exits).
func (s *Server) HandleListRegions(w http.ResponseWriter, r *http.Request) {
	nodes := s.registry.GetNodes()
	leased := s.registry.GetLeasedRegions()

	type RegionItem struct {
		ID          string `json:"id"`
		Country     string `json:"country"`
		CountryName string `json:"country_name"`
		City        string `json:"city"`
		Type        string `json:"type"` // "direct" or "leased_proxy"
	}

	regions := make([]RegionItem, 0, len(nodes)+len(leased))
	seen := make(map[string]bool)

	for _, n := range nodes {
		key := strings.ToUpper(n.Country) + "_" + n.City
		if !seen[key] {
			seen[key] = true
			regions = append(regions, RegionItem{
				ID:          n.ID,
				Country:     n.Country,
				CountryName: n.CountryName,
				City:        n.City,
				Type:        "direct",
			})
		}
	}

	for _, l := range leased {
		regions = append(regions, RegionItem{
			ID:          l.ID,
			Country:     l.Country,
			CountryName: l.CountryName,
			City:        l.City,
			Type:        "leased_proxy",
		})
	}

	respondJSON(w, http.StatusOK, map[string]interface{}{
		"status":  "success",
		"count":   len(regions),
		"regions": regions,
	})
}

// HandleReload processes POST /api/v1/reload.
func (s *Server) HandleReload(w http.ResponseWriter, r *http.Request) {
	if err := s.registry.Reload(); err != nil {
		respondJSON(w, http.StatusInternalServerError, map[string]string{
			"status":  "error",
			"message": "failed reloading configuration: " + err.Error(),
		})
		return
	}

	respondJSON(w, http.StatusOK, map[string]interface{}{
		"status":  "success",
		"message": "configuration reloaded successfully",
		"nodes":   len(s.registry.GetNodes()),
		"leased":  len(s.registry.GetLeasedRegions()),
	})
}

// HandleHealth processes GET /health & GET /api/v1/health.
func (s *Server) HandleHealth(w http.ResponseWriter, r *http.Request) {
	uptime := time.Since(s.startTime).String()
	respondJSON(w, http.StatusOK, map[string]interface{}{
		"status":  "healthy",
		"uptime":  uptime,
		"service": "nowhere-vpn-backend",
		"nodes":   len(s.registry.GetNodes()),
		"leased":  len(s.registry.GetLeasedRegions()),
	})
}

func respondJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}
