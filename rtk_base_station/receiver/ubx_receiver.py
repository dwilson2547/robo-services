#!/usr/bin/env python3
"""
RTK Base Station — Phase 1 UBX Receiver

Listens on TCP port 5555, accepts one connection from the ESP32 logger,
and writes all incoming bytes to a timestamped .ubx file.

Usage:
    python3 ubx_receiver.py

Stop with Ctrl+C. On exit, prints the convbin command and OPUS link.
"""

import socket
import time
import datetime
import signal
import sys
import os

HOST = "0.0.0.0"
PORT = 5555
STATUS_INTERVAL = 60        # seconds between progress prints
WRITE_CHUNK = 4096          # flush to disk every 4KB


def format_bytes(n):
    if n < 1024:
        return f"{n} B"
    elif n < 1048576:
        return f"{n/1024:.2f} KB"
    else:
        return f"{n/1048576:.2f} MB"


def format_elapsed(seconds):
    h = int(seconds) // 3600
    m = (int(seconds) % 3600) // 60
    s = int(seconds) % 60
    return f"{h:02d}:{m:02d}:{s:02d}"


def main():
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"raw_obs_{timestamp}.ubx"

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((HOST, PORT))
    server.listen(1)
    print(f"Listening on {HOST}:{PORT}...")
    print(f"Output file: {filename}")

    conn = None
    outfile = None
    total_bytes = 0
    start_time = None

    def shutdown(signum=None, frame=None):
        print("\nShutting down...")
        if outfile and not outfile.closed:
            outfile.flush()
            outfile.close()
        if conn:
            conn.close()
        server.close()

        if total_bytes > 0:
            elapsed = time.time() - start_time if start_time else 0
            print(f"\nSession complete.")
            print(f"  File:     {filename}")
            print(f"  Size:     {format_bytes(total_bytes)}")
            print(f"  Duration: {format_elapsed(elapsed)}")
            print(f"\nConvert to RINEX:")
            print(f"  convbin {filename} -o observation.obs -r ubx")
            print(f"\nSubmit to OPUS:")
            print(f"  https://www.ngs.noaa.gov/OPUS/")
        sys.exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    try:
        conn, addr = server.accept()
        print(f"Client connected from {addr[0]}:{addr[1]}")
        start_time = time.time()
        last_status = start_time
        outfile = open(filename, "wb")

        buf = bytearray()

        while True:
            try:
                data = conn.recv(WRITE_CHUNK)
            except (ConnectionResetError, OSError):
                print("Client disconnected.")
                break

            if not data:
                print("Client closed connection.")
                break

            buf.extend(data)
            total_bytes += len(data)

            # Flush accumulated data to disk
            if len(buf) >= WRITE_CHUNK:
                outfile.write(buf)
                buf.clear()

            now = time.time()
            if now - last_status >= STATUS_INTERVAL:
                elapsed = now - start_time
                print(f"[{format_elapsed(elapsed)}] {format_bytes(total_bytes)} received")
                last_status = now

        # Flush remaining buffer
        if buf:
            outfile.write(buf)

    except Exception as e:
        print(f"Error: {e}")
    finally:
        shutdown()


if __name__ == "__main__":
    main()
