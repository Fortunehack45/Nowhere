#!/usr/bin/env bash
# ==============================================================================
# Nowhere VPN — Node Provisioning Script
# Bootstraps a fresh Ubuntu / Debian / Debian-based VPS as a WireGuard Node
# Usage: sudo ./provision_node.sh <NODE_ID> <REGION_GROUP> <COUNTRY_CODE> <CITY> <SUBNET_CIDR> [PORT]
# Example: sudo ./provision_node.sh us_nyc_1 us_nyc US "New York" 10.8.0.0/24 51820
# ==============================================================================

set -euo pipefail

# 1. Parse Arguments
NODE_ID="${1:-}"
REGION_GROUP="${2:-}"
COUNTRY="${3:-}"
CITY="${4:-}"
SUBNET_CIDR="${5:-}"
WG_PORT="${6:-51820}"

if [[ -z "$NODE_ID" || -z "$COUNTRY" || -z "$SUBNET_CIDR" ]]; then
    echo "❌ Error: Missing required arguments."
    echo "Usage: $0 <NODE_ID> <REGION_GROUP> <COUNTRY_CODE> <CITY> <SUBNET_CIDR> [PORT]"
    echo "Example: $0 us_nyc_1 us_nyc US \"New York\" 10.8.0.0/24 51820"
    exit 1
fi

if [[ $EUID -ne 0 ]]; then
   echo "❌ This script must be run as root (or via sudo)." 
   exit 1
fi

echo "=================================================================="
echo "⚡ NOWHERE VPN — Provisioning Node: ${NODE_ID} (${COUNTRY} - ${CITY})"
echo "=================================================================="

# 2. Detect Primary Network Interface & Public IP
ETH_INTERFACE=$(ip -4 route show default | awk '{print $5}' | head -n1)
if [[ -z "$ETH_INTERFACE" ]]; then
    ETH_INTERFACE="eth0"
fi

PUBLIC_IP=$(curl -s4 https://api.ipify.org || curl -s4 https://ifconfig.me || ip route get 1.1.1.1 | awk '{print $7}' | head -n1)

echo "▶ Detected Network Interface: ${ETH_INTERFACE}"
echo "▶ Detected Public IP: ${PUBLIC_IP}"

# 3. Calculate Gateway IP from Subnet CIDR (e.g. 10.8.0.0/24 -> 10.8.0.1/24)
SUBNET_BASE=$(echo "$SUBNET_CIDR" | cut -d'/' -f1)
SUBNET_MASK=$(echo "$SUBNET_CIDR" | cut -d'/' -f2)
GATEWAY_IP="$(echo "$SUBNET_BASE" | cut -d'.' -f1-3).1/${SUBNET_MASK}"

echo "▶ Calculated Server Gateway IP: ${GATEWAY_IP}"

# 4. Install WireGuard & Tools
echo "▶ Installing WireGuard, iptables, and qrencode..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard iptables ufw curl net-tools iproute2

# 5. Enable Kernel IPv4 and IPv6 Forwarding
echo "▶ Configuring kernel IP packet forwarding..."
cat <<EOF > /etc/sysctl.d/99-nowhere-wireguard.conf
net.ipv4.ip_forward=1
net.ipv4.conf.all.forwarding=1
net.ipv6.conf.all.forwarding=1
EOF
sysctl -p /etc/sysctl.d/99-nowhere-wireguard.conf > /dev/null

# 6. Generate Server WireGuard Keypair
echo "▶ Generating Server WireGuard Keypair..."
mkdir -p /etc/wireguard
chmod 700 /etc/wireguard

SERVER_PRIVKEY=$(wg genkey)
SERVER_PUBKEY=$(echo "$SERVER_PRIVKEY" | wg pubkey)

echo "$SERVER_PRIVKEY" > /etc/wireguard/server_private.key
echo "$SERVER_PUBKEY" > /etc/wireguard/server_public.key
chmod 600 /etc/wireguard/server_private.key
chmod 644 /etc/wireguard/server_public.key

# 7. Write /etc/wireguard/wg0.conf
echo "▶ Writing /etc/wireguard/wg0.conf..."
cat <<EOF > /etc/wireguard/wg0.conf
[Interface]
Address = ${GATEWAY_IP}
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIVKEY}
SaveConfig = false

# NAT Masquerading & Packet Forwarding Rules
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o ${ETH_INTERFACE} -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o ${ETH_INTERFACE} -j MASQUERADE
EOF

chmod 600 /etc/wireguard/wg0.conf

# 8. Open WireGuard Port in Firewall
if ufw status | grep -q "Status: active"; then
    echo "▶ Opening UDP port ${WG_PORT} on UFW..."
    ufw allow "${WG_PORT}/udp" || true
fi

# 9. Start & Enable WireGuard Service
echo "▶ Enabling and starting wg-quick@wg0 service..."
systemctl enable wg-quick@wg0
systemctl restart wg-quick@wg0

# 10. Verify Node Status
if wg show wg0 > /dev/null 2>&1; then
    echo "✅ WireGuard wg0 interface is UP and running!"
else
    echo "❌ Error: Failed to start WireGuard interface wg0"
    exit 1
fi

echo ""
echo "=================================================================="
echo "🎉 PROVISIONING COMPLETE! COPY & PASTE INTO nodes.yaml:"
echo "=================================================================="
cat <<EOF
  - id: ${NODE_ID}
    region_group: ${REGION_GROUP:-$NODE_ID}
    country: ${COUNTRY}
    country_name: "${COUNTRY}"
    city: "${CITY}"
    endpoint: ${PUBLIC_IP}:${WG_PORT}
    server_pubkey: ${SERVER_PUBKEY}
    ssh_host: ${PUBLIC_IP}
    ssh_port: 22
    ssh_user: deploy
    ssh_key_path: /secrets/nowhere_deploy.pem
    tunnel_subnet: ${SUBNET_CIDR}
    dns: 1.1.1.1,1.0.0.1
    interface: wg0
    capacity_peers: 250
EOF
echo "=================================================================="
