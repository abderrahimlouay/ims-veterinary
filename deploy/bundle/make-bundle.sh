#!/usr/bin/env bash
# Builds the installer folder for the owner's Windows laptop from the current commit (docs/deployment.md §2):
# the release JAR (screens inside), the scripts, WinSW (SHA-256 checked), the CA's public certificate, INSTALLER.cmd
# and LISEZ-MOI.txt, plus a .zip of the folder. server.p12 is added by make-certificate.sh once the laptop's name and
# fixed address are known.
#   deploy/bundle/make-bundle.sh [output folder]     default ~/IMS-installateur; CA from $IMS_CA_DIR (~/.ims-ca)
#   SKIP_TESTS=1 deploy/bundle/make-bundle.sh         only for a commit CI has already passed
set -euo pipefail

# WinSW v2.12.0 publishes no checksum: this is the SHA-256 of the official release asset, downloaded on 2026-11-09
# (18,243,033 bytes, the size GitHub lists for it). A different file is refused.
WINSW_URL='https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe'
WINSW_SHA256='05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da'

repo=$(cd "$(dirname "$0")/../.." && pwd)
out="${1:-$HOME/IMS-installateur}"
ca_dir="${IMS_CA_DIR:-$HOME/.ims-ca}"

if [ ! -f "$ca_dir/ims-ca.crt" ]; then
    echo "No CA in $ca_dir: run deploy/bundle/make-ca.sh first." >&2
    exit 1
fi
if [ -e "$out" ]; then
    echo "$out already exists: move or delete it first (keep its server.p12 if you still need it)." >&2
    exit 1
fi
if [ -n "$(git -C "$repo" status --porcelain)" ]; then
    echo "The working tree has changes: the installer must be built from a commit." >&2
    exit 1
fi
commit=$(git -C "$repo" rev-parse --short HEAD)

(cd "$repo/frontend" && npm ci && npm run build)
skip=()
if [ "${SKIP_TESTS:-}" = 1 ]; then
    skip=(-DskipTests)
fi
(cd "$repo/backend" && ./mvnw -B -q -Prelease "${skip[@]}" package)
jar=$(find "$repo/backend/target" -maxdepth 1 -name 'ims-*.jar' ! -name '*.original' ! -name '*-plain.jar' | head -1)
if [ -z "$jar" ]; then
    echo "No release JAR in backend/target." >&2
    exit 1
fi

mkdir -p "$out/scripts"
base=$(basename "$jar" .jar)
cp "$jar" "$out/$base-$commit.jar"
cp "$repo"/deploy/windows/*.ps1 "$out/scripts/"
cp "$repo/deploy/windows/INSTALLER.cmd" "$repo/deploy/windows/LISEZ-MOI.txt" "$out/"
cp "$ca_dir/ims-ca.crt" "$out/"
curl -fsSL --retry 5 -o "$out/WinSW-x64.exe" "$WINSW_URL"
if [ "$(sha256sum "$out/WinSW-x64.exe" | cut -d' ' -f1)" != "$WINSW_SHA256" ]; then
    rm -rf "$out"
    echo "WinSW-x64.exe does not have the expected SHA-256: refused." >&2
    exit 1
fi
printf 'IMS Vétérinaire, commit %s, construit le %s\n' "$commit" "$(date -u +%Y-%m-%dT%H:%MZ)" > "$out/VERSION.txt"

"$repo/deploy/bundle/zip-bundle.sh" "$out"
echo "Installer folder: $out (and $out.zip). Missing: server.p12 — deploy/bundle/make-certificate.sh <NAME> <ADDRESS>"
