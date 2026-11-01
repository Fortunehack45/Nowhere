#!/usr/bin/env bash
# ==============================================================================
# Nowhere VPN — WireGuard Data Node Provisioner (For Vercel Integration)
# ==============================================================================
# Run this on your Linux VPS (Oracle Cloud Free Tier, Hetzner, DigitalOcean, etc.)
# It configures WireGuard, enables IP forwarding, sets up firewall rules,
# and generates the Vercel SSH deploy key & configuration block.
#
# Usage:
#   sudo bash setup_wireguard_node.sh
# ==============================================================================

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
   echo "❌ Error: This script must be run as root (or with sudo)." 
   exit 1
fi

WG_PORT=51820
TUNNEL_SUBNET="10.8.0.0/24"
GATEWAY_IP="10.8.0.1/24"

echo "=================================================================="
echo "⚡ NOWHERE VPN — WireGuard Node Setup (Vercel Data-Plane)"
echo "=================================================================="

# 1. Detect Network Interface and Public IP
ETH_INTERFACE=$(ip -4 route show default | awk '{print $5}' | head -n1)
if [[ -z "$ETH_INTERFACE" ]]; then
    ETH_INTERFACE="eth0"
fi

PUBLIC_IP=$(curl -s4 https://api.ipify.org || curl -s4 https://ifconfig.me || ip route get 1.1.1.1 | awk '{print $7}' | head -n1)

echo "▶ Detected Network Interface: ${ETH_INTERFACE}"
echo "▶ Detected Public IP:         ${PUBLIC_IP}"

# 2. Install Dependencies
echo "▶ Installing WireGuard and networking tools..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard iptables ufw curl iproute2

# 3. Kernel Tuning: IP Forwarding & Google BBR
echo "▶ Enabling IP forwarding and BBR congestion control..."
cat <<EOF > /etc/sysctl.d/99-nowhere-wireguard.conf
net.ipv4.ip_forward=1
net.ipv4.conf.all.forwarding=1
net.ipv6.conf.all.forwarding=1
net.core.default_qdisc=fq
net.ipv4.tcp_congestion_control=bbr
net.core.rmem_max=16777216
net.core.wmem_max=16777216
EOF
sysctl -p /etc/sysctl.d/99-nowhere-wireguard.conf > /dev/null

# 4. Generate Server WireGuard Keypair
mkdir -p /etc/wireguard
chmod 700 /etc/wireguard

if [[ ! -f /etc/wireguard/server_private.key ]]; then
    SERVER_PRIVKEY=$(wg genkey)
    SERVER_PUBKEY=$(echo "$SERVER_PRIVKEY" | wg pubkey)
    echo "$SERVER_PRIVKEY" > /etc/wireguard/server_private.key
    echo "$SERVER_PUBKEY" > /etc/wireguard/server_public.key
    chmod 600 /etc/wireguard/server_private.key
else
    SERVER_PRIVKEY=$(cat /etc/wireguard/server_private.key)
    SERVER_PUBKEY=$(cat /etc/wireguard/server_public.key)
fi

# 5. Generate / Authorize Vercel SSH Deploy Key
echo "▶ Setting up Vercel SSH orchestration key..."
mkdir -p /root/.ssh
chmod 700 /root/.ssh

if [[ ! -f /etc/wireguard/vercel_deploy_key ]]; then
    ssh-keygen -t ed25519 -N "" -f /etc/wireguard/vercel_deploy_key -C "vercel-nowhere-backend"
fi

VERCEL_PUBKEY=$(cat /etc/wireguard/vercel_deploy_key.pub)
touch /root/.ssh/authorized_keys
if ! grep -q "$VERCEL_PUBKEY" /root/.ssh/authorized_keys; then
    echo "$VERCEL_PUBKEY" >> /root/.ssh/authorized_keys
fi
chmod 600 /root/.ssh/authorized_keys

# 6. Configure WireGuard Interface
echo "▶ Configuring WireGuard wg0 interface..."
cat <<EOF > /etc/wireguard/wg0.conf
[Interface]
Address = ${GATEWAY_IP}
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIVKEY}
SaveConfig = false

# NAT Masquerading & Packet Forwarding
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -o ${ETH_INTERFACE} -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -o ${ETH_INTERFACE} -j MASQUERADE
EOF
chmod 600 /etc/wireguard/wg0.conf

# 7. Start & Enable WireGuard Service
systemctl stop wg-quick@wg0 2>/dev/null || true
systemctl enable wg-quick@wg0
systemctl restart wg-quick@wg0

# 8. Firewall Configuration
if command -v ufw >/dev/null 2>&1; then
    ufw allow 22/tcp >/dev/null 2>&1 || true
    ufw allow ${WG_PORT}/udp >/dev/null 2>&1 || true
fi

VERCEL_KEY_B64=$(base64 -w 0 /etc/wireguard/vercel_deploy_key || base64 /etc/wireguard/vercel_deploy_key | tr -d '\n')

echo "=================================================================="
echo "🎉 WIREGUARD DATA NODE PROVISIONED SUCCESSFULLY!"
echo "=================================================================="
echo ""
echo "1. In Vercel Project Settings -> Environment Variables, add:"
echo "   Key:   SSH_PRIVATE_KEY_B64"
echo "   Value: ${VERCEL_KEY_B64}"
echo ""
echo "2. Add this snippet into nowhere-vpn-backend/config/nodes.yaml:"
echo ""
cat <<EOF
  - id: primary_node
    region_group: nowhere_primary
    country: US
    country_name: "Nowhere Primary Secure Shield"
    city: "Live WireGuard Node"
    endpoint: ${PUBLIC_IP}:${WG_PORT}
    server_pubkey: ${SERVER_PUBKEY}
    ssh_host: ${PUBLIC_IP}
    ssh_port: 22
    ssh_user: root
    ssh_key_path: ""
    tunnel_subnet: ${TUNNEL_SUBNET}
    dns: 1.1.1.1,1.0.0.1
    interface: wg0
    capacity_peers: 500
    enabled: true
EOF
echo "=================================================================="
