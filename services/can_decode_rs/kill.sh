#!/usr/bin/env bash
# Stop a locally running can-decode-rs (SIGTERM, so it commits its offset first).
set -euo pipefail
pkill -TERM -f 'target/release/can-decode-rs' || echo "not running"
