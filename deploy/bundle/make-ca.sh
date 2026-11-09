#!/usr/bin/env bash
# Creates the shop's private certificate authority, ONCE, on the developer's machine (docs/deployment.md §2, ADR-166).
# ims-ca.key never leaves this machine: keep a copy of the folder on an offline USB key. Losing it means a new CA,
# to be trusted again on every device of the shop. ims-ca.crt (public) goes into the installer bundle.
#   deploy/bundle/make-ca.sh            folder: $IMS_CA_DIR, default ~/.ims-ca
set -euo pipefail

dir="${IMS_CA_DIR:-$HOME/.ims-ca}"
if [ -e "$dir/ims-ca.key" ]; then
    echo "A CA already exists in $dir: it is kept (a new one would have to be trusted again on every device)." >&2
    exit 1
fi
mkdir -p "$dir"
chmod 700 "$dir"
openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes -keyout "$dir/ims-ca.key" -out "$dir/ims-ca.crt" \
    -subj "/CN=Pharmacie IMS CA" 2>/dev/null
chmod 600 "$dir/ims-ca.key"
echo "CA created in $dir, valid until $(openssl x509 -in "$dir/ims-ca.crt" -noout -enddate | cut -d= -f2)."
echo "Copy $dir to an offline USB key now; never copy ims-ca.key to the laptop."
