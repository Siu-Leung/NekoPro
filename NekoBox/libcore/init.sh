#!/bin/bash
set -euo pipefail

chmod -R 777 .build 2>/dev/null || true
rm -rf .build 2>/dev/null || true

if [ -z "${GOPATH:-}" ]; then
    GOPATH=$(go env GOPATH)
fi

# Immutable gomobile input; never execute a moving branch.
GOMOBILE_REPOSITORY="https://github.com/MatsuriDayo/gomobile.git"
GOMOBILE_COMMIT="17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f"
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ] || [ ! -f "$GOPATH/bin/gobind-matsuri" ]; then
    rm -rf gomobile
    git clone --no-checkout "$GOMOBILE_REPOSITORY" gomobile
    pushd gomobile >/dev/null
    git fetch --no-tags --depth=1 origin "$GOMOBILE_COMMIT"
    git checkout --detach --force "$GOMOBILE_COMMIT"
    test "$(git rev-parse HEAD)" = "$GOMOBILE_COMMIT"
    pushd cmd/gomobile >/dev/null
    go install -v
    popd >/dev/null
    pushd cmd/gobind >/dev/null
    go install -v
    popd >/dev/null
    popd >/dev/null
    rm -rf gomobile
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

export PATH="$GOPATH/bin:$PATH"
GOBIND="$GOPATH/bin/gobind-matsuri" "$GOPATH/bin/gomobile-matsuri" init
