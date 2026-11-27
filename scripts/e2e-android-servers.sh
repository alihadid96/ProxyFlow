#!/usr/bin/env bash
# Starts real upstream servers on the CI host for the on-device (emulator) test:
#   Xray: HTTP (auth) :18080, SOCKS5 (auth) :11080, VLESS TCP :14443, VLESS WS :14444  (access log = proof of proxying)
#   OpenVPN 2.6: UDP :1194 (certificates + tls-crypt), TCP :1195 (certificates + username/password)
# The Android emulator reaches the host as 10.0.2.2.
set -euxo pipefail
UUID=5783a3e7-e373-51cd-8642-c83782b807c5
W=/tmp/e2e; mkdir -p $W; cd $W

# ---------- Xray upstream ----------
curl -sSL -o xray.zip "https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/Xray-linux-64.zip"
unzip -o -q xray.zip xray
cat > upstream.json <<JSON
{
  "log": {"loglevel": "warning", "access": "$W/access.log"},
  "inbounds": [
    {"port": 18080, "listen": "0.0.0.0", "protocol": "http", "settings": {"accounts": [{"user": "huser", "pass": "hpass"}]}},
    {"port": 11080, "listen": "0.0.0.0", "protocol": "socks", "settings": {"auth": "password", "accounts": [{"user": "suser", "pass": "spass"}], "udp": true}},
    {"port": 14443, "listen": "0.0.0.0", "protocol": "vless", "settings": {"clients": [{"id": "$UUID"}], "decryption": "none"}},
    {"port": 14444, "listen": "0.0.0.0", "protocol": "vless", "settings": {"clients": [{"id": "$UUID"}], "decryption": "none"},
     "streamSettings": {"network": "ws", "wsSettings": {"path": "/ws"}}}
  ],
  "outbounds": [{"protocol": "freedom"}]
}
JSON
nohup ./xray run -c upstream.json > xray.log 2>&1 &

# ---------- OpenVPN servers ----------
sudo apt-get install -y -qq openvpn tcpdump > /dev/null
openvpn --version | head -1
ec="-newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes"
openssl req -x509 $ec -keyout ca.key -out ca.crt -days 3 -subj "/CN=ProxyFlow CI CA" 2>/dev/null
for n in server client; do
  openssl req $ec -keyout $n.key -out $n.csr -subj "/CN=$n" 2>/dev/null
  eku=$([ $n = server ] && echo serverAuth || echo clientAuth)
  printf "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyAgreement\nextendedKeyUsage=$eku\n" > $n.ext
  openssl x509 -req -in $n.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out $n.crt -days 3 -extfile $n.ext 2>/dev/null
done
openvpn --genkey secret tc.key
printf '#!/bin/sh\n[ "$(sed -n 1p "$1")" = ovuser ] && [ "$(sed -n 2p "$1")" = ovpass ]\n' > check.sh; chmod 755 check.sh

common="ca $W/ca.crt
cert $W/server.crt
key $W/server.key
dh none
topology subnet
push \"redirect-gateway def1\"
push \"dhcp-option DNS 1.1.1.1\"
keepalive 10 60
verb 3"
cat > server-udp.conf <<C
port 1194
proto udp
dev tun10
server 10.8.0.0 255.255.255.0
tls-crypt $W/tc.key
status $W/status-udp.log 1
status-version 2
$common
C
cat > server-tcp.conf <<C
port 1195
proto tcp-server
dev tun11
server 10.9.0.0 255.255.255.0
script-security 2
auth-user-pass-verify $W/check.sh via-file
status $W/status-tcp.log 1
status-version 2
$common
C
sudo openvpn --config server-udp.conf --daemon --log $W/server-udp.log
sudo openvpn --config server-tcp.conf --daemon --log $W/server-tcp.log

# Forward + NAT the VPN subnets (Docker on the runner sets FORWARD to DROP).
sudo sysctl -q -w net.ipv4.ip_forward=1
for net in 10.8.0.0/24 10.9.0.0/24; do
  sudo iptables -I FORWARD -s $net -j ACCEPT
  sudo iptables -I FORWARD -d $net -j ACCEPT
  sudo iptables -t nat -I POSTROUTING -s $net -j MASQUERADE
done

inline() { echo "<$1>"; cat "$2"; echo "</$1>"; }
{ printf "client\ndev tun\nproto udp\nremote 10.0.2.2 1194\nnobind\nremote-cert-tls server\nverb 3\n"
  inline ca ca.crt; inline cert client.crt; inline key client.key; inline tls-crypt tc.key; } > client-udp.ovpn
{ printf "client\ndev tun\nproto tcp-client\nremote 10.0.2.2 1195\nnobind\nremote-cert-tls server\nauth-user-pass\nverb 3\n"
  inline ca ca.crt; inline cert client.crt; inline key client.key; } > client-tcp.ovpn
sleep 2
tail -3 server-udp.log server-tcp.log xray.log || true
ss -lntup | grep -E "1194|1195|18080|11080|14443|14444" || true
