#!/usr/bin/env python3
"""Replay the Epson ePOS/status handshake captured from an iPad POS app."""
import argparse
import socket
import time

# Captured 64-byte status probe (the remaining 7 bytes vary by app).
HANDSHAKE = bytes.fromhex(
    "10 14 07 05"
    " 1B 3D 01"
    " 1D 61 FF"
    " 1B 3D 01"
    " 1D 28 45 02 00 06 03"
    " 1D 49 6E"
    " 1B 3D 01"
    " 1D 28 45 02 00 06 0B"
    " 1B 3D 01"
    " 1D 28 48 02 00 40 01"
    " 1B 3D 01"
    " 1D 28 42 07 00 61 31 2C 33 2D 46 37"
    " 1B 3D 01"
    " 1D 49 01"
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=9100)
    args = parser.parse_args()
    with socket.create_connection((args.host, args.port), timeout=4) as sock:
        sock.settimeout(1.5)
        sock.sendall(HANDSHAKE)
        time.sleep(0.4)
        chunks = []
        while True:
            try:
                chunk = sock.recv(256)
            except socket.timeout:
                break
            if not chunk:
                break
            chunks.append(chunk)
    data = b"".join(chunks)
    print(f"sent {len(HANDSHAKE)} bytes, got {len(data)} response bytes")
    print(data.hex(" "))
    print(
        "expected: ASB 14 00 00 0f; paper width 37 27 33 1f 36 00; "
        "model information 3d 6e 00; "
        "column mode 37 27 31 31 1f 30 00; model 01"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
