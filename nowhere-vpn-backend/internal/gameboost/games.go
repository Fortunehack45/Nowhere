package gameboost

// GameServerTarget defines a target gaming cluster and its preferred WireGuard node.
type GameServerTarget struct {
	RegionCode       string `json:"region_code"`
	RegionName       string `json:"region_name"`
	TargetHost       string `json:"target_host"`
	PreferredNodeID  string `json:"preferred_node_id"`
	EstimatedPingMs  int    `json:"estimated_ping_ms"`
}

// GameDefinition represents a supported multiplayer game with optimized routing.
type GameDefinition struct {
	ID          string             `json:"id"`
	Name        string             `json:"name"`
	Category    string             `json:"category"` // "fps", "battle_royale", "moba", "sports"
	Icon        string             `json:"icon"`
	PacketQoS   string             `json:"packet_qos"` // "expedited_forwarding" (DSCP 46)
	UDPPorts    []int              `json:"udp_ports"`
	ServerHubs  []GameServerTarget `json:"server_hubs"`
}

// SupportedGames returns the curated catalog of popular mobile & desktop games optimized for low ms.
func SupportedGames() []GameDefinition {
	return []GameDefinition{
		{
			ID:        "cod_mobile",
			Name:      "Call of Duty: Mobile / Warzone",
			Category:  "fps",
			Icon:      "ic_game_cod",
			PacketQoS: "expedited_forwarding",
			UDPPorts:  []int{3074, 27014, 27050, 10000, 10001},
			ServerHubs: []GameServerTarget{
				{RegionCode: "us_central", RegionName: "US Central (Google BBR FastPath)", TargetHost: "104.197.128.154", PreferredNodeID: "us_central_gcp", EstimatedPingMs: 14},
			},
		},
		{
			ID:        "pubg_mobile",
			Name:      "PUBG Mobile / BGMI",
			Category:  "battle_royale",
			Icon:      "ic_game_pubg",
			PacketQoS: "expedited_forwarding",
			UDPPorts:  []int{10012, 17500, 20000, 20001, 20002},
			ServerHubs: []GameServerTarget{
				{RegionCode: "us_central", RegionName: "US Central (Zero Jitter FastPath)", TargetHost: "104.197.128.154", PreferredNodeID: "us_central_gcp", EstimatedPingMs: 15},
			},
		},
		{
			ID:        "free_fire",
			Name:      "Free Fire / Free Fire MAX",
			Category:  "battle_royale",
			Icon:      "ic_game_ff",
			PacketQoS: "expedited_forwarding",
			UDPPorts:  []int{10000, 10001, 10008, 10010},
			ServerHubs: []GameServerTarget{
				{RegionCode: "us_central", RegionName: "US Central (Fast-Path UDP Routing)", TargetHost: "104.197.128.154", PreferredNodeID: "us_central_gcp", EstimatedPingMs: 16},
			},
		},
		{
			ID:        "roblox",
			Name:      "Roblox Multi-Server",
			Category:  "moba",
			Icon:      "ic_game_roblox",
			PacketQoS: "expedited_forwarding",
			UDPPorts:  []int{49152, 65535},
			ServerHubs: []GameServerTarget{
				{RegionCode: "us_central", RegionName: "US Central (Anycast Gateway)", TargetHost: "104.197.128.154", PreferredNodeID: "us_central_gcp", EstimatedPingMs: 12},
			},
		},
		{
			ID:        "mobile_legends",
			Name:      "Mobile Legends: Bang Bang",
			Category:  "moba",
			Icon:      "ic_game_mlbb",
			PacketQoS: "expedited_forwarding",
			UDPPorts:  []int{5000, 5500, 9000, 9001},
			ServerHubs: []GameServerTarget{
				{RegionCode: "us_central", RegionName: "US Central (Optimized Socket Queues)", TargetHost: "104.197.128.154", PreferredNodeID: "us_central_gcp", EstimatedPingMs: 11},
			},
		},
		{
			ID:        "brawl_stars",
			Name:      "Brawl Stars / Clash Royale",
			Category:  "moba",
			Icon:      "ic_game_brawl",
			PacketQoS: "expedited_forwarding",
			UDPPorts:  []int{9339, 9340},
			ServerHubs: []GameServerTarget{
				{RegionCode: "us_central", RegionName: "US Central (Direct Peering)", TargetHost: "104.197.128.154", PreferredNodeID: "us_central_gcp", EstimatedPingMs: 12},
			},
		},
		{
			ID:        "genshin_impact",
			Name:      "Genshin Impact / Honkai: Star Rail",
			Category:  "moba",
			Icon:      "ic_game_genshin",
			PacketQoS: "expedited_forwarding",
			UDPPorts:  []int{22101, 22102},
			ServerHubs: []GameServerTarget{
				{RegionCode: "us_central", RegionName: "US Central (High-Throughput UDP Route)", TargetHost: "104.197.128.154", PreferredNodeID: "us_central_gcp", EstimatedPingMs: 14},
			},
		},
		{
			ID:        "ea_fc_mobile",
			Name:      "EA SPORTS FC Mobile / FIFA",
			Category:  "sports",
			Icon:      "ic_game_fifa",
			PacketQoS: "expedited_forwarding",
			UDPPorts:  []int{3659, 9000, 9999},
			ServerHubs: []GameServerTarget{
				{RegionCode: "us_central", RegionName: "US Central (Matchmaking Engine)", TargetHost: "104.197.128.154", PreferredNodeID: "us_central_gcp", EstimatedPingMs: 13},
			},
		},
	}
}

// FindGameByID searches the supported games catalog by ID.
func FindGameByID(id string) (GameDefinition, bool) {
	for _, g := range SupportedGames() {
		if g.ID == id {
			return g, true
		}
	}
	return GameDefinition{}, false
}
