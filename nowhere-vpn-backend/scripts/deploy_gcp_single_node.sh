#!/usr/bin/env bash
# ==============================================================================
# Nowhere VPN — Automated Single Node Provisioning for Google Cloud Platform
# ==============================================================================
# Bootstraps an Ubuntu 22.04/24.04 LTS VM as the primary Nowhere WireGuard node.
# Configures:
#  1. WireGuard kernel module & tools
#  2. Google BBR congestion control & high-throughput UDP buffer tuning
#  3. Anti-Detection TCP MSS Clamping & iptables NAT Masquerade
#  4. Go runtime & Nowhere VPN Go backend control-plane service (systemd)
#
# Usage:
#   sudo bash deploy_gcp_single_node.sh
# ==============================================================================

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
   echo "❌ Error: This script must be run as root (or with sudo)." 
   exit 1
fi

WG_PORT=51820
HTTP_PORT=8080
TUNNEL_SUBNET="10.8.0.0/24"
GATEWAY_IP="10.8.0.1/24"
API_KEY="nowhere_live_prod_key_77a9c84e1b"
INSTALL_DIR="/opt/nowhere-vpn-backend"

echo "=================================================================="
echo "⚡ NOWHERE VPN — Google Cloud Platform Single Node Provisioner"
echo "=================================================================="

# 1. Detect Network Interface and Public IP
ETH_INTERFACE=$(ip -4 route show default | awk '{print $5}' | head -n1)
if [[ -z "$ETH_INTERFACE" ]]; then
    ETH_INTERFACE="eth0"
fi

PUBLIC_IP=$(curl -s4 https://api.ipify.org || curl -s4 https://ifconfig.me || ip route get 1.1.1.1 | awk '{print $7}' | head -n1)

echo "▶ Detected Network Interface: ${ETH_INTERFACE}"
echo "▶ Detected Public IP:         ${PUBLIC_IP}"

# 2. Install Required System Packages & WireGuard
echo "▶ Updating package index and installing dependencies..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard iptables ufw curl git net-tools iproute2 build-essential

# 3. Kernel Tuning: Packet Forwarding, Google BBR & UDP Buffers
echo "▶ Configuring kernel IP packet forwarding, Google BBR, and gaming buffer tuning..."
cat <<EOF > /etc/sysctl.d/99-nowhere-wireguard.conf
# Kernel IPv4/IPv6 packet forwarding
net.ipv4.ip_forward=1
net.ipv4.conf.all.forwarding=1
net.ipv6.conf.all.forwarding=1

# Google BBR Low-Latency & Zero-Jitter Congestion Control
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr

# High-Throughput UDP Buffers for Real-time WireGuard packets
net.core.rmem_max=16777216
net.core.wmem_max=16777216
net.ipv4.udp_rmem_min=8192
net.ipv4.udp_wmem_min=8192
EOF

sysctl -p /etc/sysctl.d/99-nowhere-wireguard.conf > /dev/null

# 4. Generate Server WireGuard Keypair
echo "▶ Setting up WireGuard cryptographic keys..."
mkdir -p /etc/wireguard
chmod 700 /etc/wireguard

if [[ ! -f /etc/wireguard/server_private.key ]]; then
    SERVER_PRIVKEY=$(wg genkey)
    SERVER_PUBKEY=$(echo "$SERVER_PRIVKEY" | wg pubkey)
    echo "$SERVER_PRIVKEY" > /etc/wireguard/server_private.key
    echo "$SERVER_PUBKEY" > /etc/wireguard/server_public.key
    chmod 600 /etc/wireguard/server_private.key
    chmod 644 /etc/wireguard/server_public.key
else
    SERVER_PRIVKEY=$(cat /etc/wireguard/server_private.key)
    SERVER_PUBKEY=$(cat /etc/wireguard/server_public.key)
fi

echo "▶ Server WireGuard Public Key: ${SERVER_PUBKEY}"

# 5. Configure /etc/wireguard/wg0.conf with MSS Clamping (Anti-Detection) & NAT
echo "▶ Generating /etc/wireguard/wg0.conf with Anti-Detection rules..."
cat <<EOF > /etc/wireguard/wg0.conf
[Interface]
Address = ${GATEWAY_IP}
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIVKEY}
SaveConfig = false

# NAT Masquerading, Packet Forwarding, and TCP MSS Clamping (Anti-Detection)
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o ${ETH_INTERFACE} -j MASQUERADE; iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o ${ETH_INTERFACE} -j MASQUERADE; iptables -t mangle -D FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu
EOF

chmod 600 /etc/wireguard/wg0.conf

# 6. Enable & Start WireGuard Interface
echo "▶ Starting wg-quick@wg0..."
systemctl enable wg-quick@wg0
systemctl restart wg-quick@wg0

# 7. Install Go if not present
if ! command -v go &> /dev/null; then
    echo "▶ Installing Go runtime (1.22.5)..."
    GO_TAR="go1.22.5.linux-amd64.tar.gz"
    curl -sSL "https://go.dev/dl/${GO_TAR}" -o "/tmp/${GO_TAR}"
    rm -rf /usr/local/go
    tar -C /usr/local -xzf "/tmp/${GO_TAR}"
    rm -f "/tmp/${GO_TAR}"
    export PATH=$PATH:/usr/local/go/bin
    echo 'export PATH=$PATH:/usr/local/go/bin' >> /etc/profile
fi

export PATH=$PATH:/usr/local/go/bin

# 8. Setup Nowhere VPN Go Backend Application Directory
echo "▶ Deploying Nowhere VPN Go control-plane service..."
mkdir -p "${INSTALL_DIR}/config"
mkdir -p "${INSTALL_DIR}/secrets"

# Clone or copy repo backend code
if [[ -d "/tmp/Nowhere/nowhere-vpn-backend" ]]; then
    cp -r /tmp/Nowhere/nowhere-vpn-backend/* "${INSTALL_DIR}/"
elif [[ -d "./nowhere-vpn-backend" ]]; then
    cp -r ./nowhere-vpn-backend/* "${INSTALL_DIR}/"
else
    echo "▶ Cloning repository from GitHub..."
    TMP_CLONE="/tmp/nowhere_git_clone"
    rm -rf "$TMP_CLONE"
    git clone https://github.com/Fortunehack45/Nowhere.git "$TMP_CLONE"
    cp -r "$TMP_CLONE/nowhere-vpn-backend/"* "${INSTALL_DIR}/"
    rm -rf "$TMP_CLONE"
fi

# 9. Configure nodes.yaml for Local Management
cat <<EOF > "${INSTALL_DIR}/config/nodes.yaml"
# Nowhere VPN — Single Production WireGuard Node Configuration
nodes:
  - id: us_central_gcp
    region_group: us_central
    country: US
    country_name: "Nowhere Primary Secure Shield"
    city: "Google Cloud Node"
    endpoint: ${PUBLIC_IP}:${WG_PORT}
    server_pubkey: ${SERVER_PUBKEY}
    ssh_host: 127.0.0.1
    ssh_port: 22
    ssh_user: root
    ssh_key_path: ""
    tunnel_subnet: ${TUNNEL_SUBNET}
    dns: 1.1.1.1,1.0.0.1
    interface: wg0
    capacity_peers: 500
    enabled: true
EOF

# 10. Compile the Go Backend Binary
echo "▶ Compiling nowhere-vpn-backend binary..."
cd "${INSTALL_DIR}"
go build -o nowhere-vpn-backend main.go
chmod +x nowhere-vpn-backend

# 11. Create and Start Systemd Service
echo "▶ Configuring systemd service: nowhere-vpn-backend.service..."
cat <<EOF > /etc/systemd/system/nowhere-vpn-backend.service
[Unit]
Description=Nowhere VPN Control Plane Backend Service
After=network.target network-online.target wg-quick@wg0.service
Wants=network-online.target

[Service]
Type=simple
User=root
WorkingDirectory=${INSTALL_DIR}
ExecStart=${INSTALL_DIR}/nowhere-vpn-backend -port=${HTTP_PORT} -config=${INSTALL_DIR}/config
ExecReload=/bin/kill -HUP \$MAINPID
Restart=always
RestartSec=3s

# Security & Limits
LimitNOFILE=65535

# Environment
Environment=PORT=${HTTP_PORT}
Environment=CONFIG_DIR=${INSTALL_DIR}/config
Environment=API_KEYS=${API_KEY}

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable nowhere-vpn-backend.service
systemctl restart nowhere-vpn-backend.service

# 12. Health Check
echo "▶ Performing health check..."
sleep 2
if curl -fs "http://localhost:${HTTP_PORT}/health" > /dev/null; then
    echo "✅ Nowhere VPN Control Plane is LIVE and healthy on port ${HTTP_PORT}!"
else
    echo "⚠️ Warning: Health check endpoint did not respond immediately. Check logs with: journalctl -u nowhere-vpn-backend -n 50"
fi

echo ""
echo "=================================================================="
echo "🎉 DEPLOYMENT COMPLETE! YOUR SINGLE VPN SERVER IS READY!"
echo "=================================================================="
echo "Public IP:          ${PUBLIC_IP}"
echo "WireGuard Endpoint: ${PUBLIC_IP}:${WG_PORT}"
echo "Control API URL:    http://${PUBLIC_IP}:${HTTP_PORT}"
echo "Server Public Key:  ${SERVER_PUBKEY}"
echo "API Key:            ${API_KEY}"
echo "=================================================================="
echo "Ensure GCP Firewall allows UDP port ${WG_PORT} and TCP port ${HTTP_PORT}!"
echo "=================================================================="
