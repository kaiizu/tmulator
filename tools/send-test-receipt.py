#!/usr/bin/env python3
"""Send a realistic ESC/POS receipt to TMulator over raw TCP (port 9100)."""
import argparse
import socket
import struct
import sys
import time


def qr_command(payload: bytes) -> bytes:
    if len(payload) > 0xFFFF:
        raise ValueError("QR payload too large")
    return b"\x1d\x28\x6b" + struct.pack("<H", len(payload)) + payload


def build_receipt() -> bytes:
    out = bytearray()
    out += b"\x1b@"                      # initialize
    out += b"\x1b\x61\x01"               # center
    out += b"\x1d\x21\x11"               # double width + height (n=0x11)
    out += b"\x1b\x45\x01"               # bold
    out += b"TMULATOR\n"
    out += b"\x1b\x45\x00"
    out += b"\x1d\x21\x00"
    out += b"\x1b\x45\x01" + b"VIRTUAL RECEIPT PRINTER\n" + b"\x1b\x45\x00"
    out += b"\x1b\x2d\x01" + b"Epson TM-m30 / ESC-POS\n" + b"\x1b\x2d\x00"

    # Raster decoration (GS v 0): validates the bitmap pipeline.
    width_bytes, height = 48, 10
    out += b"\x1d\x76\x30\x00" + struct.pack("<H", width_bytes) + struct.pack("<H", height)
    for y in range(height):
        for x in range(width_bytes):
            if y in (0, height - 1):
                bits = 0xFF
            elif y == 4 and x % 8 == 0:
                bits = 0xF0
            elif y == 5 and x % 8 == 4:
                bits = 0x0F
            else:
                bits = 0x00
            out.append(bits)

    out += b"\n"
    out += b"\x1b\x61\x00"               # left
    out += b"Order      #1042\n"
    out += b"Date       2026-11-13 10:24\n"
    out += b"Server     Alex\n"
    out += b"Terminal   iPad POS / Table 7\n"
    out += b"------------------------------------------\n"
    out += b"2x  Espresso Tonic                 9.50\n"
    out += b"1x  Truffle Fries                  7.25\n"
    out += b"1x  Wagyu Burger                  18.00\n"
    out += b"1x  Sparkling Water                4.00\n"
    out += b"------------------------------------------\n"
    out += b"\x1b\x45\x01" + b"TOTAL                             38.75\n" + b"\x1b\x45\x00"
    out += b"VISA **** 4242                    38.75\n"
    out += b"\x1b\x61\x01\n"

    # QR: model 2, 6-dot modules, ECC M, store + print.
    out += qr_command(bytes([49, 65, 50]))
    out += qr_command(bytes([49, 67, 6]))
    out += qr_command(bytes([49, 69, 49]))
    data = b"https://tmulator.local/r/1042"
    out += qr_command(bytes([49, 80, 48]) + data)
    out += qr_command(bytes([49, 81, 48]))

    out += b"\n"
    out += b"\x1d\x68\x50"               # barcode height 80
    out += b"\x1d\x77\x02"               # module width 2
    out += b"\x1d\x48\x02"               # HRI below
    code = b"TM1042"
    out += b"\x1d\x6b\x49" + bytes([len(code)]) + code
    out += b"\n"
    out += b"\x1b\x61\x00"
    out += b"Thank you for testing TMulator.\n"
    out += b"Network 9100 / ENPC 3289\n\n\n"
    out += b"\x1d\x56\x00"               # full cut
    return bytes(out)


def read_status(sock: socket.socket) -> bytes:
    sock.sendall(b"\x10\x04\x01")        # DLE EOT 1
    try:
        sock.settimeout(1.5)
        return sock.recv(16)
    except socket.timeout:
        return b""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", help="TMulator IP address, e.g. 192.168.0.141")
    parser.add_argument("--port", type=int, default=9100)
    parser.add_argument("--no-status", action="store_true")
    parser.add_argument("--keep-open", action="store_true", help="do not close the socket immediately")
    args = parser.parse_args()

    payload = build_receipt()
    print(f"Connecting to {args.host}:{args.port} ...")
    try:
        with socket.create_connection((args.host, args.port), timeout=5) as sock:
            if not args.no_status:
                status = read_status(sock)
                print("DLE EOT 1 response:", status.hex(" ") if status else "(none)")
            print(f"Sending {len(payload)} bytes of ESC/POS ...")
            sock.sendall(payload)
            if args.keep_open:
                print("Keeping connection open for 5s ...")
                time.sleep(5)
        print("Receipt sent. Check the TMulator screen.")
        return 0
    except OSError as exc:
        print(f"Connection failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
