# ProxyFlow – rootless Android proxy agent

ProxyFlow sends **all of your phone's traffic** (or only the apps you pick) through an **HTTP**, **SOCKS5** or **VLESS** proxy, or an **OpenVPN** server. It uses Android's `VpnService`, so it does **not need root**.

| Protocol | Auth | TCP | UDP | DNS |
|---|---|---|---|---|
| HTTP (CONNECT) | none / username + password (Basic) | ✅ | ❌ blocked (HTTP proxies can't carry UDP), so apps fall back to TCP | through the proxy over DoH/TCP |
| SOCKS5 | none / username + password (RFC 1929) | ✅ | ✅ UDP ASSOCIATE, if the server supports it | remote or local |
| VLESS (Xray-core) | UUID | ✅ | ✅ | remote or local |
| OpenVPN 2.6 (.ovpn) | certificates, plus username/password and encrypted keys if the config needs them | ✅ | ✅ | servers pushed by the VPN server (falls back to the DNS server in Settings) |

VLESS transports: TCP (raw), WebSocket, gRPC, XHTTP, HTTPUpgrade. Security: none, TLS, REALITY. Flow: `xtls-rprx-vision`.

## Architecture

```
 Apps ──► TUN (VpnService) ──► hev-socks5-tunnel (lwIP, JNI, in-process)
                                   │  SOCKS5 + UDP, random per-session password
                                   ▼
                     Xray-core (child process, 127.0.0.1 only)
                       ├─ DNS hijack (port 53) → Xray DNS (DoH via proxy / local)
                       ├─ LAN → direct, IPv6 → block/proxy, UDP → block (HTTP only)
                       └─ outbound: HTTP CONNECT │ SOCKS5 │ VLESS(+TLS/REALITY)
```

**OpenVPN** works differently. ProxyFlow starts the OpenVPN 2.6 core (`libovpnexec.so` + `libopenvpn.so`, built from OpenVPN for Android / ics-openvpn) and controls it over OpenVPN's *management interface*, a unix socket. OpenVPN tells ProxyFlow which IP, routes and DNS servers to use. ProxyFlow builds the TUN interface from those and passes its file descriptor back over the socket (`SCM_RIGHTS`). OpenVPN's connection to the server is excluded from the tunnel with `VpnService.protect()`. ProxyFlow answers username/password and private-key password requests from the encrypted profile, so these are never written to a file. Before starting, ProxyFlow resolves server host names on the physical network. When you switch between Wi-Fi and mobile data it sends OpenVPN a `network-change` command.

`.ovpn` files are checked before use. Options that run scripts, write files or take over the management interface (`up`/`down`, `script-security`, `plugin`, `management`, `log`…) are removed. TAP mode and references to external certificate or key files are rejected with an explanation.

* For proxy profiles, ProxyFlow's own app ID is left out of the VPN, so Xray's connections to the upstream server go straight out on Wi-Fi or mobile data and don't loop back into the tunnel.
* The traffic counters on the dashboard are real byte counts read from the TUN engine.
* Latency is measured with a real HTTP request to `http://www.gstatic.com/generate_204` sent through the proxy. The exit IP and its location come from `ipinfo.io`, also queried through the proxy. You can turn the exit-IP lookup off.
* The Xray config, which contains your credentials, is passed to Xray over stdin and is never written to disk.
* Usernames, passwords and UUIDs are stored encrypted with AES-256-GCM. The key lives in the Android Keystore and can't be exported.

Main code:

| Path | What it does |
|---|---|
| `vpn/ProxyVpnService.kt` | TUN setup, foreground service, reconnect, network changes, kill switch, stats |
| `vpn/TProxyService.kt` | JNI bridge to hev-socks5-tunnel |
| `vpn/OpenVpnEngine.kt` | OpenVPN process and management protocol (TUN hand-off, PROTECTFD, credentials, state, byte counts) |
| `core/OvpnConfig.kt` | `.ovpn` parsing, validation and cleanup |
| `core/OpenVpnProbe.kt` | Profile test: sends a real OpenVPN handshake packet without starting a VPN |
| `core/XrayConfigBuilder.kt` | Builds the Xray-core JSON config (routing, DNS, outbounds) |
| `core/XrayProcess.kt` | Runs the bundled `libxray.so` executable |
| `core/ProxyClients.kt` | SOCKS5 and HTTP CONNECT clients written in Kotlin (used for profile tests and measurements) |
| `core/LinkCodec.kt` | Parses and exports `vless://`, `socks5://` and `http://` links, plus base64 subscriptions, with validation |
| `data/` | Room profiles, Keystore crypto, DataStore settings |
| `ui/` | Jetpack Compose (Material 3) screens |

## Installing the APK

1. Open the repo's **Releases** page on your phone (`https://github.com/alihadid96/ProxyFlow/releases`) and pick the newest build.
2. Download **`app-arm64-v8a-release.apk`**. This is the right file for almost every phone made since 2017. If you're not sure, take `app-universal-release.apk` instead.
3. Tap the downloaded file. If Android blocks it, allow *Install unknown apps* for your browser or file manager.
4. Open ProxyFlow, tap **Add proxy** (or import a link), then tap the big **Connect** button and accept the VPN request.
5. Recommended: go to *Settings → Disable battery optimisation*. For a system-level kill switch, also enable *Always-on VPN* and *Block connections without VPN* (Settings → Kill switch).

There are two builds. The **debug** build (`*-debug.apk`, package `com.proxyflow.app.debug`) can be installed alongside the release build.

## Building

GitHub Actions (`.github/workflows/build.yml`) does the full build on every push:

1. `scripts/build-native.sh` compiles Xray-core (pinned to `v26.11.9`), hev-socks5-tunnel (`2.9.3`) and OpenVPN 2.6 with OpenSSL (from ics-openvpn `v0.7.69`) with Android NDK r27c for arm64-v8a, armeabi-v7a and x86_64.
2. The JVM unit tests run. These include real SOCKS5 and HTTP proxy handshakes against local test servers.
3. `scripts/e2e-xray.sh` runs the generated Xray configs with a real Xray-core build, through real local HTTP, SOCKS5 and VLESS servers, out to the Internet.
4. Gradle builds the debug and release APKs (one per ABI plus a universal one), signs them, checks them, and publishes them as a GitHub Release.

To build locally (Linux or macOS with the Android SDK, NDK r27 or newer, Go and JDK 17 installed):

```bash
export ANDROID_NDK_HOME=/path/to/ndk
./scripts/build-native.sh
./gradlew assembleDebug assembleRelease
# APKs: app/build/outputs/apk/{debug,release}/
```

In Android Studio, open the folder, run `scripts/build-native.sh` once, then click Run.

### Signing

The release keystore is in `keystore/proxyflow-release.jks` (password `proxyflow`). It's committed so that every build has the same signature and you can install updates over older versions. **The repo is public, so anyone could sign an APK with this key.** For a private release, create your own key, store it as GitHub secrets, and pass `PF_KEYSTORE`, `PF_STORE_PASS`, `PF_KEY_ALIAS` and `PF_KEY_PASS` to Gradle.

## Pinned versions

AGP 8.7.3 · Gradle 8.11.1 · Kotlin 2.0.21 · KSP 2.0.21-1.0.28 · Compose BOM 2024.12.01 · Room 2.6.1 · DataStore 1.1.1 · Navigation 2.8.5 · Coroutines 1.9.0 · ZXing 3.5.3 / zxing-android-embedded 4.3.0 · Xray-core v26.11.9 · hev-socks5-tunnel 2.9.3 · OpenVPN 2.6 via ics-openvpn v0.7.69 · minSdk 29 (Android 10) · targetSdk 35 (Android 15)

## License

ProxyFlow includes OpenVPN, which is GPL-2.0, so ProxyFlow as a whole is distributed under the **GNU GPL v2** (see `LICENSE`). If you distribute the APK, you must also offer its source code. This public repository satisfies that.

## Known limitations

* **OpenVPN:** only `dev tun` (layer 3) servers work, because Android has no TAP support. Certificates and keys must be embedded in the `.ovpn` file. Traffic counters show bytes on the encrypted link, so they include protocol overhead. LAN bypass needs Android 13 or newer. Profile tests send a real OpenVPN handshake packet, but servers using `tls-auth`/`tls-crypt` ignore unauthenticated packets, so a UDP server with `tls-auth` can only be checked by connecting to it.

* **HTTP proxies can only carry TCP.** UDP is rejected right away so apps switch to TCP. QUIC falls back to TLS. Voice and video calls or games that need UDP won't work over an HTTP proxy.
* **The VLESS HTTP/2 transport was removed** from current Xray-core. Use **XHTTP** instead (it runs over HTTP/2 or HTTP/3). Links with `type=h2` are rejected with an explanation.
* **"Allow insecure" TLS was removed** from Xray-core, so certificates are always verified. For a server with a self-signed certificate, use the *Pinned certificate SHA-256* field.
* **Kill switch:** while ProxyFlow reconnects it keeps the tunnel up, so traffic is blocked rather than leaked. If the app is killed or the phone reboots, only Android's *Always-on VPN* plus *Block connections without VPN* settings can guarantee nothing leaks. An app can't turn these on by itself.
* Only one VPN app can be active on Android at a time.
* The app is in English only.

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` (+ `SPECIAL_USE`, Android 14+), `POST_NOTIFICATIONS` (asked on Android 13+), `RECEIVE_BOOT_COMPLETED` (for the optional auto-connect at boot), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (only used when you tap the button for it), `CAMERA` (declared by the QR scanner and only asked for when you tap *Scan QR*). Per-app routing lists installed apps through a launcher `<queries>` filter rather than `QUERY_ALL_PACKAGES`.

## Privacy

There's no analytics, tracking or cloud sync, and backups of app data are disabled. Passwords and UUIDs are never written to logs: the engine log masks them.
