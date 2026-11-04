#!/usr/bin/env bash
# Start can-decode-rs locally. Requires env (see README): at minimum
# IGGY_CONNECTION_STRING and DBC_PATH. Example:
#   IGGY_CONNECTION_STRING=iggy+tcp://iggy:iggy@127.0.0.1:8090 \
#   DBC_PATH=../../can_data/dbc/custom/chevy_impala_2008_hscan.dbc ./start.sh
set -euo pipefail
cd "$(dirname "$0")"
cargo build --release
exec ./target/release/can-decode-rs
