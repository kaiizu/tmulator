#!/usr/bin/env python3
"""
Minimal Epson-style printer for latency diagnosis.

Listens on TCP 9100 (ESC/POS) and UDP 3289 (ENPC), answers the same setup
queries as TMulator, and logs every packet with a timestamp. Point iPos at
this PC's IP to see whether the client sends receipt data immediately.

Usage:
    python tools/fake_printer.py --ip 192.168.0.144 --name TM-m30III
"""
from __future__ import annotations

import argparse
import socket
import struct
import threading
import time
import uuid

LOCK = threading.Lock()
HOLDER: str | None = None


def now() -> str:
    return time.strftime("%H:%M:%S") + f".{int(time.time() * 1000) % 1000:03d}"


def log(tag: str, message: str) -> None:
    print(f"{now()} {tag:7s} {message}", flush=True)


def hexstr(data: bytes, limit: int = 96) -> str:
    text = data[:limit].hex(" ")
    if len(data) > limit:
        text += " …"
    return text


# --------------------------------------------------------------------- ENPC

def enpc_frame(function: bytes, payload: bytes) -> bytes:
    return b"EPSONq" + function + struct.pack(">I", len(payload)) + payload


def enpc_reply(query: bytes, source_ip: str, printer_ip: str, name: str) -> bytes | None:
    if not query.startswith(b"EPSONQ") or len(query) < 10:
        return None
    function = query[6:10]

    def ip_bytes(value: str) -> bytes:
        return bytes(int(part) for part in value.split("."))

    mac = uuid.getnode().to_bytes(6, "big")
    ip = ip_bytes(printer_ip)
    mask = ip_bytes("255.255.255.0")
    gateway = ip_bytes("192.168.0.1")

    if function == bytes([0, 0, 0, 0]):
        payload = bytearray(54)
        payload[0:17] = b"UB-EEAE083ENSN\x00\x00\x00"
        payload[33:40] = bytes([0x01, 0xFF, 0xFF, 0x15, 0x00, 0x02, 0x00])
        payload[40:46] = mac
        payload[46:54] = bytes([0, 0, 0, 1, 0, 0, 0, 1])
        return enpc_frame(function, bytes(payload))
    if function == bytes([3, 0, 0, 0]):
        payload = bytearray(133)
        prefix = bytes([0x00, 0x05, 0x01, 0x02, 0x01])
        payload[0:5] = prefix
        encoded = name.encode("ascii")[:127]
        payload[5:5 + len(encoded)] = encoded
        return enpc_frame(function, bytes(payload))
    if function == bytes([0, 0, 0, 0x10]):
        payload = bytearray(23)
        payload[0] = 0x01
        payload[1:7] = mac
        payload[7:9] = bytes([0x00, 0x04])
        payload[9:13] = ip
        payload[13:17] = mask
        payload[17:21] = gateway
        payload[21:23] = bytes([0x80, 0x7C])
        return enpc_frame(function, bytes(payload))
    if function == bytes([3, 0, 0, 0x10]):
        return enpc_frame(function, bytes.fromhex("0e1400000fffffffff39414000"))
    if function == bytes([3, 0, 0, 0x17]):
        with LOCK:
            holder = HOLDER
        return enpc_frame(function, ip_bytes(holder) if holder else b"\x00\x00\x00\x00")
    return None


def udp_server(port: int, printer_ip: str, name: str) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.bind(("0.0.0.0", port))
    log("ENPC", f"listening on UDP {port}")
    while True:
        try:
            query, address = sock.recvfrom(4096)
        except OSError:
            continue
        log("ENPC RX", f"{address[0]}:{address[1]} {hexstr(query, 64)}")
        if query.startswith(b"EPSONC"):
            # Commands use EPSONC/EPSONc and carry a 2-byte result code.
            # 03000015 = hold/acquire; 03000016 = release/clear timeout.
            if len(query) >= 10:
                reply = b"EPSONc" + query[6:10] + b"\x00\x00" + b"\x00\x00"
                sock.sendto(reply, address)
                log("ENPC CMD", f"answered {query[8:10].hex()} -> {hexstr(reply, 64)}")
            else:
                log("ENPC CMD", "short command ignored")
            continue
        reply = enpc_reply(query, address[0], printer_ip, name)
        if reply is not None:
            sock.sendto(reply, address)
            log("ENPC TX", hexstr(reply, 64))


# ---------------------------------------------------------------------- TCP

def parse_command(data: bytes) -> tuple[int, list[bytes]] | None:
    """Return (consumed, replies), or None when this is receipt data."""
    if data.startswith(b"\x10\x04\x01"):
        return 3, [b"\x16"]
    if data.startswith(b"\x10\x04\x12\x01"):
        return 4, [b"\x12"]
    if data.startswith(b"\x10\x14\x07\x05"):
        return 4, []
    if data.startswith(b"\x1b\x3d\x01"):
        return 3, []
    if data.startswith(b"\x1d\x61\xff"):
        return 3, [b"\x14\x00\x00\x0f"]
    if data.startswith(b"\x1d\x28\x45\x02\x00\x06\x03"):
        return 7, [b"\x37\x27\x33\x1f\x36\x00"]
    if data.startswith(b"\x1d\x49\x6e"):
        return 3, [b"\x3d\x6e\x00"]
    if data.startswith(b"\x1d\x28\x45\x02\x00\x06\x0b"):
        return 7, [b"\x37\x27\x31\x31\x1f\x30\x00"]
    if data.startswith(b"\x1d\x28\x48\x02\x00\x40\x01"):
        return 7, []
    if data.startswith(b"\x1d\x28\x42"):
        if len(data) < 5:
            return 0, []
        total = 5 + data[3] + (data[4] << 8)
        return (total, []) if len(data) >= total else (0, [])
    if data.startswith(b"\x1d\x49\x01"):
        return 3, [b"\x01"]
    if data.startswith(b"\x10\x14\x08"):
        return (10, [b"\x37\x25\x00"]) if len(data) >= 10 else (0, [])
    if data.startswith(b"\x1d\x28\x48\x06\x00\x30\x30"):
        return (11, [b"\x37\x22" + data[7:11] + b"\x00"]) if len(data) >= 11 else (0, [])
    if len(data) < 16 and any(
        len(data) < len(cmd) and cmd.startswith(data)
        for cmd in (
            b"\x10\x04\x01", b"\x10\x04\x12\x01", b"\x10\x14\x07\x05",
            b"\x1b\x3d\x01", b"\x1d\x61\xff", b"\x1d\x28\x45\x02\x00\x06\x03",
            b"\x1d\x49\x6e", b"\x1d\x28\x45\x02\x00\x06\x0b", b"\x1d\x28\x48\x02\x00\x40\x01",
            b"\x1d\x49\x01", b"\x10\x14\x08", b"\x1d\x28\x48\x06\x00\x30\x30",
        )
    ):
        return 0, []
    return None


def tcp_client(conn: socket.socket, address: tuple[str, int]) -> None:
    global HOLDER
    with LOCK:
        HOLDER = address[0]
    log("TCP", f"connect {address[0]}:{address[1]} (holder set)")
    conn.settimeout(None)
    buffer = b""
    last_query = time.monotonic()
    data_logged = False
    try:
        while True:
            chunk = conn.recv(65536)
            if not chunk:
                break
            log("TCP RX", f"{len(chunk)} bytes {hexstr(chunk)}")
            buffer += chunk
            offset = 0
            while offset < len(buffer):
                parsed = parse_command(buffer[offset:])
                if parsed is None:
                    if not data_logged:
                        data_logged = True
                        delta = time.monotonic() - last_query
                        log("DATA", f"receipt data began (+{delta:.3f}s after last query)")
                    offset = len(buffer)
                    break
                consumed, replies = parsed
                if consumed == 0:
                    if parsed != (0, []):
                        pass
                    break
                for reply in replies:
                    conn.sendall(reply)
                    log("TCP TX", hexstr(reply))
                last_query = time.monotonic()
                offset += consumed
            buffer = buffer[offset:] if offset else buffer
            if len(buffer) > 65536:
                buffer = buffer[-1024:]
    except OSError as error:
        log("TCP", f"error {error}")
    finally:
        conn.close()
        with LOCK:
            HOLDER = None
        log("TCP", f"disconnect {address[0]}:{address[1]} (holder cleared)")


def tcp_server(port: int) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", port))
    sock.listen(8)
    log("TCP", f"listening on TCP {port}")
    while True:
        conn, address = sock.accept()
        threading.Thread(target=tcp_client, args=(conn, address), daemon=True).start()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ip", default="192.168.0.144", help="This PC's LAN IPv4 address")
    parser.add_argument("--name", default="TM-m30III", help="ENPC device name")
    parser.add_argument("--tcp-port", type=int, default=9100)
    parser.add_argument("--udp-port", type=int, default=3289)
    args = parser.parse_args()

    threading.Thread(target=udp_server, args=(args.udp_port, args.ip, args.name), daemon=True).start()
    log("SYS", f"fake printer {args.name} at {args.ip}, TCP {args.tcp_port}, UDP {args.udp_port}")
    try:
        tcp_server(args.tcp_port)
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
