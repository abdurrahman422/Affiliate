"""Compact binary frames for file chunks, screen JPEGs, and thumbnails."""

from __future__ import annotations

import struct
import uuid

MAGIC = b"OLB1"
KIND_FILE = 1
KIND_SCREEN = 2
KIND_THUMB = 3
FLAG_LAST = 0x01
HEADER_LEN = 4 + 1 + 16 + 4 + 1  # magic, kind, uuid, seq, flags


def make_frame(kind: int, transfer_id: uuid.UUID, seq: int, payload: bytes, last: bool = False) -> bytes:
    flags = FLAG_LAST if last else 0
    return MAGIC + bytes([kind]) + transfer_id.bytes + struct.pack(">I", seq) + bytes([flags]) + payload


def parse_frame(data: bytes) -> tuple[int, uuid.UUID, int, int, bytes]:
    if len(data) < HEADER_LEN:
        raise ValueError("short binary frame")
    if data[:4] != MAGIC:
        raise ValueError("bad binary magic")
    kind = data[4]
    transfer_id = uuid.UUID(bytes=data[5:21])
    seq = struct.unpack_from(">I", data, 21)[0]
    flags = data[25]
    return kind, transfer_id, seq, flags, data[26:]


def pack_screen_payload(width: int, height: int, rotation: int, jpeg: bytes) -> bytes:
    return struct.pack(">HHBB", width, height, rotation & 0xFF, 0) + jpeg


def unpack_screen_payload(payload: bytes) -> tuple[int, int, int, bytes]:
    if len(payload) < 6:
        raise ValueError("short screen payload")
    width, height, rotation, _reserved = struct.unpack_from(">HHBB", payload, 0)
    return width, height, rotation, payload[6:]
