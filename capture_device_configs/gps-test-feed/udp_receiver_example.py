from __future__ import annotations

import argparse
import json
import socket


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Print UDP packets from the ESP32 GPS test feed")
    parser.add_argument("--bind", default="0.0.0.0", help="Address to bind")
    parser.add_argument("--port", type=int, default=5514, help="UDP port to bind")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.bind((args.bind, args.port))
        print(f"Listening on udp://{args.bind}:{args.port}")
        while True:
            payload, sender = sock.recvfrom(4096)
            try:
                message = json.loads(payload.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                print(f"{sender[0]}:{sender[1]} raw={payload!r}")
                continue
            print(f"{sender[0]}:{sender[1]} {json.dumps(message, sort_keys=True)}")


if __name__ == "__main__":
    raise SystemExit(main())
