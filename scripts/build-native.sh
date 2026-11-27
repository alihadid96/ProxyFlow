#!/usr/bin/env bash
# Builds the two native components ProxyFlow ships inside the APK:
#   * libxray.so                 – Xray-core executable (VLESS / HTTP / SOCKS5 outbounds, DNS)
#   * libhev-socks5-tunnel.so    – hev-socks5-tunnel (TUN -> SOCKS5 packet forwarding, JNI)
# Requirements: Go (version from Xray's go.mod), Android NDK (ANDROID_NDK_HOME), git.
set -euo pipefail

XRAY_VERSION="${XRAY_VERSION:-v26.11.9}"
HEV_VERSION="${HEV_VERSION:-2.9.3}"
ICS_VERSION="${ICS_VERSION:-v0.7.69}"   # OpenVPN for Android: provides the OpenVPN 2.6 core + OpenSSL build
API=29
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/native-build"
OUT="$ROOT/app/src/main/jniLibs"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
[ -n "$NDK" ] || { echo "ANDROID_NDK_HOME is not set"; exit 1; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

mkdir -p "$WORK" "$OUT"

# ---------- Xray-core ----------
if [ ! -d "$WORK/xray" ]; then
  git clone --depth 1 --branch "$XRAY_VERSION" https://github.com/XTLS/Xray-core "$WORK/xray"
fi
pushd "$WORK/xray" >/dev/null
go mod download
build_xray() { # abi goarch cc [goarm]
  local abi=$1 goarch=$2 cc=$3 goarm=${4:-}
  mkdir -p "$OUT/$abi"
  echo ">> Xray $XRAY_VERSION for $abi"
  env GOOS=android GOARCH="$goarch" GOARM="$goarm" CGO_ENABLED=1 CC="$TOOLCHAIN/$cc" \
    go build -o "$OUT/$abi/libxray.so" -trimpath -buildvcs=false -gcflags="all=-l=4" \
    -ldflags="-X github.com/xtls/xray-core/core.build=$XRAY_VERSION -s -w -buildid= -checklinkname=0 -extldflags=-Wl,--pack-dyn-relocs=android" \
    ./main
}
build_xray arm64-v8a   arm64 "aarch64-linux-android${API}-clang"
build_xray armeabi-v7a arm   "armv7a-linux-androideabi${API}-clang" 7
build_xray x86_64      amd64 "x86_64-linux-android${API}-clang"
popd >/dev/null

# ---------- hev-socks5-tunnel ----------
if [ ! -d "$WORK/hev" ]; then
  git clone --depth 1 --branch "$HEV_VERSION" --recursive --shallow-submodules \
    https://github.com/heiher/hev-socks5-tunnel "$WORK/hev"
fi
pushd "$WORK/hev" >/dev/null
"$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk \
  "APP_ABI=arm64-v8a armeabi-v7a x86_64" APP_PLATFORM=android-$API \
  NDK_LIBS_OUT="$WORK/hev-libs" NDK_OUT="$WORK/hev-obj" \
  "APP_CFLAGS=-O3 -DPKGNAME=com/proxyflow/app/vpn -DCLSNAME=TProxyService" \
  "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu" -j"$(nproc)"
popd >/dev/null
for abi in arm64-v8a armeabi-v7a x86_64; do
  cp "$WORK/hev-libs/$abi/libhev-socks5-tunnel.so" "$OUT/$abi/"
done

# ---------- OpenVPN 2.x (from ics-openvpn, GPLv2) ----------
# Produces libopenvpn.so (OpenVPN core, OpenSSL statically linked) and libovpnexec.so (PIE launcher).
if [ ! -d "$WORK/ics" ]; then
  git clone --depth 1 --branch "$ICS_VERSION" https://github.com/schwabe/ics-openvpn "$WORK/ics"
  (cd "$WORK/ics" && git submodule update --init --depth 1 \
      main/src/main/cpp/openvpn main/src/main/cpp/openssl main/src/main/cpp/lz4 main/src/main/cpp/fmt)
fi
for abi in arm64-v8a armeabi-v7a x86_64; do
  echo ">> OpenVPN for $abi"
  B="$WORK/ovpn-build/$abi"
  # A "skeleton" output path makes ics-openvpn's CMake skip the OpenVPN3/SWIG parts we don't need.
  LIBOUT="$WORK/ovpn-out/build/intermediates/cmake/skeleton/$abi"
  cmake -S "$WORK/ics/main/src/main/cpp" -B "$B" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$LIBOUT" -DCMAKE_RUNTIME_OUTPUT_DIRECTORY="$LIBOUT" > "$WORK/ovpn-cmake-$abi.log"
  cmake --build "$B" --target libovpnexec.so -j"$(nproc)"
  cp "$LIBOUT/libovpnexec.so" "$LIBOUT/libopenvpn.so" "$OUT/$abi/"
  "$TOOLCHAIN/llvm-strip" "$OUT/$abi/libovpnexec.so" "$OUT/$abi/libopenvpn.so"
done

ls -la "$OUT"/*
