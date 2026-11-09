#!/usr/bin/env bash
# The laptop's HTTPS certificate, signed by the shop's CA (docs/deployment.md §2 item 4), written as server.p12 into the
# installer folder with certificat.txt (name, address, expiry), and the .zip refreshed. It names the laptop, localhost
# (the scripts check https://localhost) and its fixed address; valid two years. openssl asks for the server.p12
# password: give it to the owner apart from the file. The server key is deleted once exported.
#   deploy/bundle/make-certificate.sh PHARMA-PC 192.168.1.20 [installer folder, default ~/IMS-installateur]
set -euo pipefail

name="${1:-}"
address="${2:-}"
out="${3:-$HOME/IMS-installateur}"
ca_dir="${IMS_CA_DIR:-$HOME/.ims-ca}"

if ! [[ "$name" =~ ^[A-Za-z0-9-]{1,15}$ ]]; then
    echo "Usage: $0 <Windows name of the laptop, e.g. PHARMA-PC> <fixed IPv4 address> [installer folder]" >&2
    exit 1
fi
if ! [[ "$address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    echo "The address must be an IPv4 address such as 192.168.1.20." >&2
    exit 1
fi
if [ ! -f "$ca_dir/ims-ca.key" ] || [ ! -d "$out" ]; then
    echo "Needs the CA ($ca_dir, make-ca.sh) and the installer folder ($out, make-bundle.sh)." >&2
    exit 1
fi
if ! cmp -s "$ca_dir/ims-ca.crt" "$out/ims-ca.crt"; then
    echo "The installer folder carries another CA than $ca_dir: rebuild it." >&2
    exit 1
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
openssl req -new -newkey rsa:2048 -nodes -keyout "$work/server.key" -out "$work/server.csr" -subj "/CN=$name" \
    2>/dev/null
openssl x509 -req -in "$work/server.csr" -CA "$ca_dir/ims-ca.crt" -CAkey "$ca_dir/ims-ca.key" \
    -CAserial "$ca_dir/ims-ca.srl" -CAcreateserial -days 730 -sha256 \
    -extfile <(printf 'subjectAltName=DNS:%s,DNS:localhost,IP:%s\n' "$name" "$address") \
    -out "$work/server.crt" 2>/dev/null
echo "Choose the server.p12 password (give it to the owner apart from the file):"
openssl pkcs12 -export -in "$work/server.crt" -inkey "$work/server.key" -certfile "$ca_dir/ims-ca.crt" \
    -out "$out/server.p12"
expiry=$(openssl x509 -in "$work/server.crt" -noout -enddate | cut -d= -f2)
printf 'NOM=%s\nADRESSE=%s\nEXPIRE=%s\n' "$name" "$address" "$expiry" > "$out/certificat.txt"

"$(dirname "$0")/zip-bundle.sh" "$out"
echo "server.p12 for $name ($address), valid until $expiry; write the date in the deployment record (deployment.md §8)."
