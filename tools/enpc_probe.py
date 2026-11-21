#!/usr/bin/env python3
"""Probe TMulator's Epson ENPC discovery responder on UDP 3289."""
import argparse
import socket
import struct
import sys

QUERIES = {
    "broadcast": bytes([0x00, 0x00, 0x00, 0x00]),
    "device-name": bytes([0x03, 0x00, 0x00, 0x00]),
    "network-info": bytes([0x00, 0x00, 0x00, 0x10]),
    "capabilities": bytes([0x03, 0x00, 0x00, 0x10]),
    "who-is-holding": bytes([0x03, 0x00, 0x00, 0x17]),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", help="Printer IP, or 255.255.255.255 for broadcast")
    parser.add_argument("--port", type=int, default=3289)
    parser.add_argument("--query", choices=QUERIES, default="device-name")
    parser.add_argument("--timeout", type=float, default=2.0)
    args = parser.parse_args()

    packet = b"EPSONQ" + QUERIES[args.query] + b"\x00\x00\x00\x00"
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(args.timeout)
    sock.sendto(packet, (args.host, args.port))
    print(f"sent {len(packet)} bytes to {args.host}:{args.port} ({args.query})")
    try:
        data, address = sock.recvfrom(4096)
    except socket.timeout:
        print("no response", file=sys.stderr)
        return 1
    finally:
        sock.close()

    print(f"reply from {address[0]}:{address[1]}: {len(data)} bytes")
    print(data.hex(" "))
    if data.startswith(b"EPSONq") and len(data) >= 14:
        function = data[6:10]
        length = int.from_bytes(data[10:14], "big")
        print(f"function={function.hex()} payload_length={length} actual={len(data) - 14}")
        payload = data[14:]
        if function == bytes([0x03, 0x00, 0x00, 0x00]):
            name = payload[5:].split(b"\x00", 1)[0].decode("ascii", "replace")
            print(f"device name: {name}")
        if function == bytes([0x00, 0x00, 0x00, 0x10]) and len(payload) >= 21:
            print(f"ip={'.'.join(str(b) for b in payload[9:13])} "
                  f"mask={'.'.join(str(b) for b in payload[13:17])} "
                  f"gw={'.'.join(str(b) for b in payload[17:21])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
