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
				{RegionCode: "na_east", RegionName: "North America East (US East)", TargetHost: "198.51.100.10", PreferredNodeID: "us_nyc_1", EstimatedPingMs: 14},
				{RegionCode: "na_west", RegionName: "North America West (US West)", TargetHost: "198.51.100.20", PreferredNodeID: "us_sfo_1", EstimatedPingMs: 18},
				{RegionCode: "eu_central", RegionName: "Europe Central (Frankfurt/London)", TargetHost: "203.0.113.30", PreferredNodeID: "de_fra_1", EstimatedPingMs: 16},
				{RegionCode: "asia_east", RegionName: "Asia East (Tokyo/Singapore)", TargetHost: "203.0.113.80", PreferredNodeID: "jp_tyo_1", EstimatedPingMs: 22},
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
				{RegionCode: "na_east", RegionName: "North America (Virginia/Ohio)", TargetHost: "198.51.100.10", PreferredNodeID: "us_nyc_1", EstimatedPingMs: 15},
				{RegionCode: "eu_west", RegionName: "Europe West (Frankfurt)", TargetHost: "203.0.113.30", PreferredNodeID: "de_fra_1", EstimatedPingMs: 17},
				{RegionCode: "asia_se", RegionName: "Southeast Asia (Singapore)", TargetHost: "203.0.113.90", PreferredNodeID: "sg_sin_1", EstimatedPingMs: 19},
				{RegionCode: "asia_south", RegionName: "India & South Asia (Mumbai)", TargetHost: "203.0.113.110", PreferredNodeID: "in_bom_1", EstimatedPingMs: 21},
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
				{RegionCode: "sa_brazil", RegionName: "South America (Sao Paulo)", TargetHost: "203.0.113.120", PreferredNodeID: "br_sao_1", EstimatedPingMs: 16},
				{RegionCode: "asia_se", RegionName: "Southeast Asia (Singapore)", TargetHost: "203.0.113.90", PreferredNodeID: "sg_sin_1", EstimatedPingMs: 18},
				{RegionCode: "na_east", RegionName: "North America (Miami/NYC)", TargetHost: "198.51.100.40", PreferredNodeID: "us_mia_1", EstimatedPingMs: 20},
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
				{RegionCode: "us_east", RegionName: "US East (New York/Chicago)", TargetHost: "198.51.100.10", PreferredNodeID: "us_nyc_1", EstimatedPingMs: 12},
				{RegionCode: "us_west", RegionName: "US West (San Francisco)", TargetHost: "198.51.100.20", PreferredNodeID: "us_sfo_1", EstimatedPingMs: 15},
				{RegionCode: "eu_west", RegionName: "Europe (London)", TargetHost: "203.0.113.20", PreferredNodeID: "uk_lon_1", EstimatedPingMs: 14},
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
				{RegionCode: "asia_se", RegionName: "Southeast Asia (Singapore/Jakarta)", TargetHost: "203.0.113.90", PreferredNodeID: "sg_sin_1", EstimatedPingMs: 11},
				{RegionCode: "asia_east", RegionName: "East Asia (Tokyo)", TargetHost: "203.0.113.80", PreferredNodeID: "jp_tyo_1", EstimatedPingMs: 18},
				{RegionCode: "eu_central", RegionName: "Europe (Frankfurt)", TargetHost: "203.0.113.30", PreferredNodeID: "de_fra_1", EstimatedPingMs: 24},
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
				{RegionCode: "eu_west", RegionName: "Europe (Frankfurt/Amsterdam)", TargetHost: "203.0.113.30", PreferredNodeID: "de_fra_1", EstimatedPingMs: 12},
				{RegionCode: "us_east", RegionName: "US East (New York)", TargetHost: "198.51.100.10", PreferredNodeID: "us_nyc_1", EstimatedPingMs: 15},
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
				{RegionCode: "asia_east", RegionName: "Asia (Tokyo)", TargetHost: "203.0.113.80", PreferredNodeID: "jp_tyo_1", EstimatedPingMs: 14},
				{RegionCode: "na_east", RegionName: "America (US East)", TargetHost: "198.51.100.10", PreferredNodeID: "us_nyc_1", EstimatedPingMs: 18},
				{RegionCode: "eu_central", RegionName: "Europe (Frankfurt)", TargetHost: "203.0.113.30", PreferredNodeID: "de_fra_1", EstimatedPingMs: 16},
			},
		},
		{
			ID:        "ea_fc_mobile",
			Name:      "EA SPORTS FC Mobile / FIFA",
			Category:  "sports",
			Icon:      "ic_game_fifa",
			PacketQoS: "expedited_forwarding",
			UDPPorts:  []int{3659, 9565, 9570, 9000, 9999},
			ServerHubs: []GameServerTarget{
				{RegionCode: "eu_central", RegionName: "Europe (Frankfurt)", TargetHost: "203.0.113.30", PreferredNodeID: "de_fra_1", EstimatedPingMs: 13},
				{RegionCode: "us_east", RegionName: "US East (New York)", TargetHost: "198.51.100.10", PreferredNodeID: "us_nyc_1", EstimatedPingMs: 16},
				{RegionCode: "sa_brazil", RegionName: "South America (Sao Paulo)", TargetHost: "203.0.113.120", PreferredNodeID: "br_sao_1", EstimatedPingMs: 19},
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
