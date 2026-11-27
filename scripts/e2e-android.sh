#!/usr/bin/env bash
# On-device end-to-end test on an Android emulator. Installs the real debug APK, grants the VPN
# consent (appops), connects with each protocol through the TUN interface, sends traffic from the
# device shell and checks that it really arrived at the upstream server.
set -uo pipefail
W=/tmp/e2e
PKG=com.proxyflow.app.debug
CTL="$PKG/com.proxyflow.app.DebugControlActivity"
APK=$(ls dist/app-x86_64-debug.apk)
REPORT=e2e-android-report.txt
UUID=5783a3e7-e373-51cd-8642-c83782b807c5
: > $REPORT; pass=0; failn=0
log() { echo "$*" | tee -a $REPORT; }

adb wait-for-device
log "Device: $(adb shell getprop ro.product.model | tr -d '\r') · Android $(adb shell getprop ro.build.version.release | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
adb install -r -g "$APK" > /dev/null || { log "FAIL install"; exit 1; }
adb shell appops set $PKG ACTIVATE_VPN allow
adb shell cmd deviceidle whitelist +$PKG > /dev/null 2>&1 || true
adb logcat -b crash -c

# Wait until logcat shows a final state. Prints the matching line.
wait_state() { # timeout
  local t=$1
  for _ in $(seq 1 $t); do
    local l
    l=$(adb logcat -d -s ProxyFlowState:I | grep -E "STATE (CONNECTED|ERROR|INVALID|CONTROL_ERROR)" | tail -1)
    [ -n "$l" ] && { echo "$l"; return; }
    sleep 1
  done
  echo "TIMEOUT"
}

disconnect() {
  adb shell am start -n $CTL --es cmd disconnect > /dev/null
  for _ in $(seq 1 15); do
    adb logcat -d -s ProxyFlowState:I | grep -q "STATE DISCONNECTED" && break; sleep 1
  done
  sleep 2
}

probe() { # HTTP request from the device shell (shell UID is routed through the VPN)
  adb shell "printf 'GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nConnection: close\r\n\r\n' | (nc -w 15 www.gstatic.com 80 || toybox nc -w 15 www.gstatic.com 80)" 2>/dev/null | head -1 | tr -d '\r'
}

dump_diag() {
  {
    echo "--- app log ---"; adb logcat -d -s ProxyFlowLog:I ProxyFlowState:I | tail -25
    echo "--- crash ---"; adb logcat -d -b crash | grep -E "Abort message|signal [0-9]+|#0[0-9] pc|JNI|Exception|Caused by|at com.proxyflow" | head -25
    echo "--- routing ---"; adb shell ip route get 1.1.1.1 uid 2000 2>&1 | head -2
    adb shell ip rule 2>&1 | grep -iE "tun|uidrange|vpn" | head -12
    adb shell ip addr 2>&1 | grep -E "tun[0-9]" -A2 | head -6
    adb shell dumpsys connectivity 2>/dev/null | grep -E "NetworkAgentInfo.*VPN" | head -2 | cut -c1-200
  } | tee -a $REPORT
}

run_case() { # name expect(ok|error:<text>) proof(xray|nat:<net>) kind(link|ovpn) value [user pass]
  local name=$1 expect=$2 proof=$3 kind=$4 value=$5 user=${6:-} pass=${7:-}
  adb logcat -c
  : | sudo tee $W/access.log > /dev/null
  sudo iptables -Z FORWARD
  # Single-quote every value for the device shell ('&', '#', '?' in links are shell metacharacters there).
  local cmd="am start -n $CTL --es cmd connect --es $kind '$value'"
  [ -n "$user" ] && cmd="$cmd --es user '$user' --es pass '$pass'"
  adb shell "$cmd" > /dev/null
  local st; st=$(wait_state 50)
  if [[ $expect == error:* ]]; then
    local want=${expect#error:}
    if echo "$st" | grep -q "STATE ERROR" && echo "$st" | grep -qi "$want"; then
      log "PASS $name — rejected as expected: ${st#*STATE ERROR }"; pass=$((pass+1))
    else log "FAIL $name — expected error '$want', got: $st"; dump_diag; failn=$((failn+1)); fi
    disconnect; return
  fi
  if ! echo "$st" | grep -q "STATE CONNECTED"; then
    log "FAIL $name — did not connect: $st"; dump_diag; failn=$((failn+1)); disconnect; return
  fi
  # Give ConnectivityService a moment to apply the VPN's routes/UID ranges.
  for _ in $(seq 1 10); do adb shell dumpsys connectivity 2>/dev/null | grep -q "VPN CONNECTED\|VPN.*CONNECTED" && break; sleep 1; done
  sleep 3
  local resp; resp=$(probe)
  local ok_proof=no detail=""
  case $proof in
    xray) grep -q "www.gstatic.com:80" $W/access.log && ok_proof=yes; detail="upstream access log: $(grep -c accepted $W/access.log) connection(s)";;
    nat:*) local net=${proof#nat:}; local pk
           pk=$(sudo iptables -L FORWARD -v -n -x | awk -v n="$net/24" '$8 == n {print $1; exit}')
           [ "${pk:-0}" -gt 0 ] && ok_proof=yes; detail="VPN server forwarded ${pk:-0} packet(s) from the tunnel subnet $net/24 to the Internet";;
  esac
  local traffic; traffic=$(adb logcat -d -s ProxyFlowState:I | grep TRAFFIC | tail -1 | sed 's/.*TRAFFIC //')
  if [[ "$resp" == *" 204"* && $ok_proof == yes ]]; then
    log "PASS $name — device got '$resp' through the tunnel; $detail; app counters: ${traffic:-none}"; pass=$((pass+1))
  else
    log "FAIL $name — response='$resp' proof=$ok_proof ($detail)"; dump_diag; failn=$((failn+1))
  fi
  disconnect
}

DIRECT=$(probe); log "Before connecting (no VPN): '$DIRECT'"
OVPN_UDP=$(base64 -w0 $W/client-udp.ovpn)
OVPN_TCP=$(base64 -w0 $W/client-tcp.ovpn)

run_case "HTTP proxy + auth"          ok xray link "http://huser:hpass@10.0.2.2:18080#ci-http"
run_case "SOCKS5 proxy + auth"        ok xray link "socks5://suser:spass@10.0.2.2:11080#ci-socks"
run_case "VLESS TCP"                  ok xray link "vless://$UUID@10.0.2.2:14443?type=tcp&security=none#ci-vless"
run_case "VLESS WebSocket"            ok xray link "vless://$UUID@10.0.2.2:14444?type=ws&path=%2Fws&security=none#ci-ws"
run_case "OpenVPN UDP + tls-crypt"    ok nat:10.8.0.0 ovpn "$OVPN_UDP"
run_case "OpenVPN TCP + user/pass"    ok nat:10.9.0.0 ovpn "$OVPN_TCP" ovuser ovpass
run_case "HTTP wrong password"        error:authentication - link "http://huser:WRONG@10.0.2.2:18080#ci-bad"
run_case "SOCKS5 wrong password"      error:authentication - link "socks5://suser:WRONG@10.0.2.2:11080#ci-bad2"
run_case "Unreachable proxy"          error:reach - link "http://10.0.2.2:9#ci-dead"
run_case "VLESS wrong UUID"           "error:did not pass traffic" - link "vless://00000000-0000-4000-8000-000000000000@10.0.2.2:14443?type=tcp&security=none#ci-baduuid"
run_case "OpenVPN wrong password"     error:authentication - ovpn "$OVPN_TCP" ovuser WRONG

log "== Result: $pass passed, $failn failed =="
[ "$failn" -eq 0 ]
