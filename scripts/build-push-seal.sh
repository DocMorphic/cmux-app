#!/bin/sh
set -eu
# Build the one-shot Mac encryption adapter; stdin is supplied only by the host.
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$root/build/push"
/usr/bin/swiftc -O -parse-as-library "$root/push/PushSeal.swift" -o "$root/build/push/cmux-push-seal"
