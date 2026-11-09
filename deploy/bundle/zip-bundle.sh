#!/usr/bin/env bash
# Writes SHA256SUMS.txt in the installer folder and (re)creates <folder>.zip next to it.
set -euo pipefail
out=$(cd "$1" && pwd)
(cd "$out" && find . -type f ! -name SHA256SUMS.txt -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS.txt)
rm -f "$out.zip"
(cd "$(dirname "$out")" && zip -q -r "$(basename "$out").zip" "$(basename "$out")")
