#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

javac --release 21 -d "$WORK" "$ROOT/m0-spikes/repayment/src/RepaymentSpike.java"
java -cp "$WORK" RepaymentSpike
