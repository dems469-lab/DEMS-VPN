#!/usr/bin/env bash
# Télécharge les deux AAR natifs utilisés par DEMS VPN.
# (Le build Gradle le fait aussi automatiquement via la tâche « fetchDeps ».)
set -euo pipefail
cd "$(dirname "$0")/.."

CORE_VERSION="${CORE_VERSION:-v26.10.21}"
HEV_VERSION="${HEV_VERSION:-2.19.3}"

mkdir -p app/libs

fetch() {
  local url="$1" out="$2"
  if [[ -f "$out" && $(stat -c%s "$out") -gt 1024 ]]; then
    echo "✓ $(basename "$out") déjà présent"
    return
  fi
  echo "→ $(basename "$out")"
  curl -fL --retry 3 -o "$out.part" "$url"
  mv "$out.part" "$out"
}

fetch "https://github.com/2dust/AndroidLibXrayLite/releases/download/${CORE_VERSION}/libv2ray.aar" \
      app/libs/libv2ray.aar
fetch "https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_VERSION}/hev-socks5-tunnel.aar" \
      app/libs/hev-socks5-tunnel.aar

echo "Cœurs prêts dans app/libs/"
