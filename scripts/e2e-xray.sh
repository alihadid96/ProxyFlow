#!/usr/bin/env bash
# End-to-end check of the configurations ProxyFlow generates, using a real Xray-core binary (Linux build
# of the same version that ships in the APK) against real local upstream HTTP / SOCKS5 / VLESS servers.
# Traffic path tested: curl -> ProxyFlow client config (authenticated SOCKS5 inbound, as used by the TUN
# engine) -> upstream proxy -> Internet. Writes a report to e2e-report.txt.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CFG="$ROOT/app/build/xray-configs"
XRAY="$ROOT/native-build/xray-linux"
REPORT="$ROOT/e2e-report.txt"
UUID=5783a3e7-e373-51cd-8642-c83782b807c5
: > "$REPORT"
pass=0; failn=0
log() { echo "$*" | tee -a "$REPORT"; }

if [ ! -x "$XRAY" ]; then
  (cd "$ROOT/native-build/xray" && CGO_ENABLED=0 go build -o "$XRAY" -trimpath -ldflags="-s -w" ./main) || { log "FAIL build linux xray"; exit 1; }
fi
"$XRAY" version | head -1 | tee -a "$REPORT"

cat > /tmp/upstream.json <<JSON
{
  "log": {"loglevel": "warning"},
  "inbounds": [
    {"port": 18080, "listen": "127.0.0.1", "protocol": "http", "settings": {"accounts": [{"user": "huser", "pass": "hpass"}]}},
    {"port": 18081, "listen": "127.0.0.1", "protocol": "http", "settings": {}},
    {"port": 11080, "listen": "127.0.0.1", "protocol": "socks", "settings": {"auth": "password", "accounts": [{"user": "suser", "pass": "spass"}], "udp": true}},
    {"port": 11081, "listen": "127.0.0.1", "protocol": "socks", "settings": {"auth": "noauth", "udp": true}},
    {"port": 14443, "listen": "127.0.0.1", "protocol": "vless", "settings": {"clients": [{"id": "$UUID"}], "decryption": "none"}},
    {"port": 14444, "listen": "127.0.0.1", "protocol": "vless", "settings": {"clients": [{"id": "$UUID"}], "decryption": "none"},
     "streamSettings": {"network": "ws", "wsSettings": {"path": "/ws"}}}
  ],
  "outbounds": [{"protocol": "freedom"}]
}
JSON
"$XRAY" run -c /tmp/upstream.json > /tmp/upstream.log 2>&1 &
UP=$!
sleep 2

log "== Config validation (xray run -test) =="
for f in "$CFG"/*.json; do
  if "$XRAY" run -test -c "$f" > /tmp/test.log 2>&1; then log "PASS valid  $(basename "$f")"; pass=$((pass+1))
  else log "FAIL valid  $(basename "$f"): $(tail -2 /tmp/test.log)"; failn=$((failn+1)); fi
done

DIRECT_IP=$(curl -s -m 10 https://api.ipify.org || echo "?")
log "Public IP without proxy: $DIRECT_IP"

run_case() { # name config expect(ok|fail)
  local name=$1 cfg=$2 expect=$3
  "$XRAY" run -c "$CFG/$cfg.json" > "/tmp/$cfg.log" 2>&1 &
  local pid=$!
  for _ in $(seq 1 30); do (echo > /dev/tcp/127.0.0.1/10808) 2>/dev/null && break; sleep 0.2; done
  local code ip
  code=$(curl -s -o /dev/null -w '%{http_code}' -m 15 --proxy "socks5h://local:localpass@127.0.0.1:10808" http://www.gstatic.com/generate_204)
  ip=$(curl -s -m 15 --proxy "socks5h://local:localpass@127.0.0.1:10808" https://api.ipify.org)
  kill $pid 2>/dev/null; wait $pid 2>/dev/null
  if [ "$expect" = ok ] && [ "$code" = 204 ] && [ -n "$ip" ]; then
    log "PASS e2e   $name  (generate_204=$code, exit IP via proxy=$ip)"; pass=$((pass+1))
  elif [ "$expect" = fail ] && [ "$code" != 204 ]; then
    log "PASS e2e   $name  (correctly refused, http_code=$code)"; pass=$((pass+1))
  else
    log "FAIL e2e   $name  (http_code=$code ip=$ip expected=$expect)"; tail -5 "/tmp/$cfg.log" | tee -a "$REPORT"; failn=$((failn+1))
  fi
}

log "== End-to-end through local upstream servers =="
run_case "HTTP with auth"        client-http-auth      ok
run_case "HTTP wrong password"   client-http-wrongpass fail
run_case "HTTP no auth"          client-http-noauth    ok
run_case "SOCKS5 with auth"      client-socks-auth     ok
run_case "SOCKS5 no auth"        client-socks-noauth   ok
run_case "SOCKS5 local DNS"      client-socks-localdns ok
run_case "VLESS TCP"             client-vless-tcp      ok
run_case "VLESS WebSocket"       client-vless-ws       ok

# Local authentication of the inbound used by the TUN engine must be enforced.
"$XRAY" run -c "$CFG/client-socks-noauth.json" > /dev/null 2>&1 &
pid=$!; sleep 1
code=$(curl -s -o /dev/null -w '%{http_code}' -m 8 --proxy "socks5h://127.0.0.1:10808" http://www.gstatic.com/generate_204)
kill $pid 2>/dev/null; wait $pid 2>/dev/null
if [ "$code" != 204 ]; then log "PASS local inbound rejects unauthenticated apps"; pass=$((pass+1)); else log "FAIL local inbound open"; failn=$((failn+1)); fi

kill $UP 2>/dev/null
log "== Result: $pass passed, $failn failed =="
[ "$failn" -eq 0 ]
