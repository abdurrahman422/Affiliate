import uuid

from app.binary import KIND_FILE, KIND_SCREEN, make_frame, parse_frame, pack_screen_payload, unpack_screen_payload


def test_file_frame_roundtrip():
    transfer = uuid.uuid4()
    payload = b"hello-bytes" * 100
    raw = make_frame(KIND_FILE, transfer, 7, payload, last=True)
    kind, tid, seq, flags, body = parse_frame(raw)
    assert kind == KIND_FILE
    assert tid == transfer
    assert seq == 7
    assert flags & 1
    assert body == payload


def test_screen_payload_roundtrip():
    jpeg = b"\xff\xd8fakejpeg"
    packed = pack_screen_payload(720, 1280, 1, jpeg)
    width, height, rotation, out = unpack_screen_payload(packed)
    assert (width, height, rotation, out) == (720, 1280, 1, jpeg)
    frame = make_frame(KIND_SCREEN, uuid.UUID(int=0), 1, packed)
    kind, _, _, _, body = parse_frame(frame)
    assert kind == KIND_SCREEN
    assert unpack_screen_payload(body)[3] == jpeg
