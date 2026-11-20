#!/bin/bash

set -euo pipefail

DIR=app/src/main/assets/sing-box
mkdir -p "$DIR"
work=$(mktemp -d "$DIR/.download.XXXXXX")
trap 'rm -rf -- "$work"' EXIT

fetch_asset() {
  local name=$1 version=$2 sha256=$3
  printf 'Downloading %s %s\n' "$name" "$version"
  curl --fail --location --silent --show-error --retry 3 --max-time 90 \
    -o "$work/$name.db" "https://github.com/SagerNet/sing-$name/releases/download/$version/$name.db"
  printf '%s  %s\n' "$sha256" "$work/$name.db" | sha256sum -c
  xz -9 "$work/$name.db"
  printf '%s' "$version" > "$work/$name.version.txt"
}

# Pin public upstream assets so clean and repeated builds use the same rules.
fetch_asset geoip 20261112 0da32148f688ba65c0c6b2e2e0b1e6261802534819545d6fd2e3c8e71322825e
fetch_asset geosite 20261118141523 c4b1a17003dbe8e5326ecfcd036528740c07e5c776d138f8b034080be81b660e

for asset in "$work"/*; do
  mv -f -- "$asset" "$DIR/"
done
