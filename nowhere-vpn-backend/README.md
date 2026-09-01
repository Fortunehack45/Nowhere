# Nowhere VPN Backend — Stateless WireGuard Control Plane

A lightweight, stateless WireGuard control-plane service written in Go. It issues real-time WireGuard tunnel configurations on demand to the Nowhere Android and iOS applications by directly querying and managing physical WireGuard servers over SSH.

---

## Key Highlights

- **Stateless & Zero Database**: No SQL/NoSQL databases, no ORM, and no local session persistence. Each WireGuard server's own `wg show` kernel state is the single source of truth.
- **Dynamic Peer Allocation**: Generates Curve25519 keypairs, queries live allocated IPs on the target server via SSH, assigns the lowest free IP in the region's subnet, and runs `wg set` live.
- **Load-Aware Routing**: Group multiple servers under a `region_group` (e.g. `us_nyc`). The backend queries live `wg show <iface> transfer` in parallel and automatically assigns new clients to the server with the lowest load.
- **Leased Proxy Exit Regions**: For countries without physical commercial datacenters (small islands, specific regions), client tunnels to a regional gateway node and egresses through geolocated residential/mobile proxy streams via transparent server-side policy routing.
- **Single Static Go Binary**: Highly concurrent, lightweight, and deployable via Docker, Docker Compose, or systemd.
- **Zero-Downtime Hot Reloading**: Update `config/nodes.yaml` and reload via `SIGHUP` or `POST /api/v1/reload` without restarting the daemon.

---

## Repository Structure

```
nowhere-vpn-backend/
├── main.go                     # HTTP server entrypoint & graceful shutdown
├── config/
│   ├── nodes.yaml              # Static inventory of real WireGuard VPS nodes
│   └── leased_regions.yaml     # Proxy-leased exit region definitions
├── internal/
│   ├── api/
│   │   ├── handlers.go         # POST /connect, DELETE /disconnect, GET /nodes, GET /regions
│   │   └── middleware.go       # API key auth, structured logging, panic recovery, CORS
│   ├── config/
│   │   └── config.go           # Thread-safe node inventory & hot-reloading
│   ├── proxy/
│   │   └── leased_exit.go      # Proxy-leased exit handler & policy routing
│   ├── ssh/
│   │   └── client.go           # Authenticated SSH client connection pooling per node
│   └── wireguard/
│       ├── keys.go             # Curve25519 keypair generation & clamping (RFC 7748)
│       └── peer.go             # IP allocation, live wg set/show commands, load balancing
├── deploy/
│   ├── Dockerfile              # Multi-stage minimal Alpine container
│   ├── docker-compose.yml      # Ready-to-run container orchestration
│   └── systemd/
│       └── nowhere-vpn-backend.service # Systemd service unit for bare-metal VPS
├── scripts/
│   └── provision_node.sh       # One-command VPS bootstrap script for new regions
├── go.mod
└── README.md
```

---

## API Reference

### 1. Connect (Provision WireGuard Peer)
**Endpoint**: `POST /api/v1/connect`  
**Headers**: `X-API-Key: <api_key>` (or `Authorization: Bearer <api_key>`)

**Request Body (By Node ID)**:
```json
{
  "node_id": "us_nyc_1"
}
```

**Request Body (By Region Group or Country)**:
```json
{
  "region_group": "us_nyc"
}
```
*Or:*
```json
{
  "country": "US"
}
```

**Response (`200 OK`)**:
```json
{
  "node_id": "us_nyc_1",
  "country": "US",
  "country_name": "United States",
  "city": "New York",
  "server_pubkey": "8XpQZ+3VjW0Q/2bXyU1nF0k9kEwPvG6+3N7M9dK1L2M=",
  "endpoint": "198.51.100.10:51820",
  "assigned_ip": "10.8.0.14/32",
  "client_private_key": "4O9N1eL3M4O5P...",
  "client_public_key": "U4kM0P5Zd+2nR...",
  "allowed_ips": ["0.0.0.0/0", "::/0"],
  "dns": ["1.1.1.1", "1.0.0.1"],
  "mtu": 1420
}
```

---

### 2. Disconnect (Remove WireGuard Peer)
**Endpoint**: `DELETE /api/v1/disconnect`  
**Headers**: `X-API-Key: <api_key>`

**Request Body**:
```json
{
  "node_id": "us_nyc_1",
  "client_public_key": "U4kM0P5Zd+2nR1WxF7oF9l0xQwH8+4O9N1eL3M4O5P="
}
```

**Response (`200 OK`)**:
```json
{
  "status": "success",
  "message": "peer removed from WireGuard node"
}
```

---

### 3. List Available Nodes
**Endpoint**: `GET /api/v1/nodes`

**Response (`200 OK`)**:
```json
{
  "status": "success",
  "count": 16,
  "nodes": [
    {
      "id": "us_nyc_1",
      "region_group": "us_nyc",
      "country": "US",
      "country_name": "United States",
      "city": "New York",
      "endpoint": "198.51.100.10:51820",
      "server_pubkey": "8XpQZ+3VjW0Q...",
      "dns": "1.1.1.1,1.0.0.1",
      "capacity_peers": 250
    }
  ]
}
```

---

### 4. List All Regions (Direct Nodes + Leased Proxy Exits)
**Endpoint**: `GET /api/v1/regions`

**Response (`200 OK`)**:
```json
{
  "status": "success",
  "count": 21,
  "regions": [
    { "id": "us_nyc_1", "country": "US", "country_name": "United States", "city": "New York", "type": "direct" },
    { "id": "bz_bze", "country": "BZ", "country_name": "Belize", "city": "Belize City", "type": "leased_proxy" }
  ]
}
```

---

### 5. Health Check
**Endpoint**: `GET /health` or `GET /api/v1/health` (No authentication required)

**Response (`200 OK`)**:
```json
{
  "status": "healthy",
  "uptime": "14h32m10s",
  "service": "nowhere-vpn-backend",
  "nodes": 16,
  "leased": 5
}
```

---

## Provisioning a New WireGuard Server

To add a new physical or VPS node (DigitalOcean, Vultr, Hetzner, Linode, OVH):

1. SSH into the newly created Ubuntu/Debian VPS.
2. Run the bootstrap script:
   ```bash
   curl -sSL https://raw.githubusercontent.com/Fortunehack45/nowhere-vpn-backend/main/scripts/provision_node.sh -o provision_node.sh
   chmod +x provision_node.sh
   sudo ./provision_node.sh de_fra_1 de_fra DE "Frankfurt" 10.9.2.0/24 51820
   ```
3. Copy the output snippet printed by the script and append it to `config/nodes.yaml`.
4. Trigger zero-downtime config reload:
   ```bash
   curl -X POST http://127.0.0.1:8080/api/v1/reload -H "X-API-Key: <your_api_key>"
   ```

---

## Deployment Guide

### Option A: Docker Compose (Recommended)

1. Clone repository and place deployment SSH private key in `./deploy/secrets/nowhere_deploy.pem`:
   ```bash
   chmod 600 ./deploy/secrets/nowhere_deploy.pem
   ```
2. Launch with Docker Compose:
   ```bash
   cd deploy
   docker-compose up -d
   ```

### Option B: Bare-Metal Systemd on Ubuntu / Debian VPS

1. Compile static binary:
   ```bash
   CGO_ENABLED=0 go build -ldflags="-s -w" -o nowhere-vpn-backend main.go
   ```
2. Copy files to `/opt/nowhere-vpn-backend`:
   ```bash
   sudo mkdir -p /opt/nowhere-vpn-backend/config /opt/nowhere-vpn-backend/secrets
   sudo cp nowhere-vpn-backend /opt/nowhere-vpn-backend/
   sudo cp -r config/* /opt/nowhere-vpn-backend/config/
   sudo cp secrets/nowhere_deploy.pem /opt/nowhere-vpn-backend/secrets/
   sudo chmod 600 /opt/nowhere-vpn-backend/secrets/nowhere_deploy.pem
   ```
3. Install systemd unit:
   ```bash
   sudo cp deploy/systemd/nowhere-vpn-backend.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now nowhere-vpn-backend
   ```
4. Put behind Caddy/Nginx for automated HTTPS TLS termination.

---

## Android App Integration (`com.wireguard.android`)

When the Nowhere Android app receives the JSON response from `POST /api/v1/connect`:

```kotlin
val config = Config.Builder()
    .setInterface(
        Interface.Builder()
            .parsePrivateKey(response.clientPrivateKey)
            .addAddress(InetNetwork.parse(response.assignedIp))
            .addDnsServer(InetAddress.getByName(response.dns.first()))
            .build()
    )
    .addPeer(
        Peer.Builder()
            .parsePublicKey(response.serverPubkey)
            .parseEndpoint(response.endpoint)
            .parseAllowedIPs("0.0.0.0/0")
            .build()
    )
    .build()

// Activate WireGuard tunnel
goBackend.setState(tunnel, Tunnel.State.UP, config)
```
