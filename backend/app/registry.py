from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from fastapi import WebSocket

from .binary import FLAG_LAST, KIND_FILE, KIND_SCREEN, KIND_THUMB, parse_frame, unpack_screen_payload
from .config import Settings
from .protocol import ERROR, FILE_BEGIN, FILE_END, RESPONSE, SCREEN_STATUS, envelope

log = logging.getLogger("ownerlink.registry")


class DeviceOffline(Exception):
    pass


class CommandError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass
class Transfer:
    queue: asyncio.Queue
    meta: dict[str, Any] | None = None
    error: CommandError | None = None
    done: bool = False


@dataclass
class DeviceLink:
    websocket: WebSocket
    device_id: str
    paired: bool = False
    last_seen: float = field(default_factory=time.time)
    battery: int | None = None
    charging: bool | None = None
    pending: dict[str, asyncio.Future] = field(default_factory=dict)
    transfers: dict[str, Transfer] = field(default_factory=dict)
    thumbs: dict[str, Transfer] = field(default_factory=dict)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    contact_id: str | None = None
    public_key: str | None = None

    def touch(self) -> None:
        self.last_seen = time.time()

    def is_stale(self, timeout: float) -> bool:
        return (time.time() - self.last_seen) > timeout


class Registry:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.links: dict[str, DeviceLink] = {}
        self.screen_subs: dict[str, set[asyncio.Queue]] = {}
        self._guard = asyncio.Lock()
        self.contacts: dict[str, DeviceLink] = {}

    def get(self, device_id: str) -> DeviceLink | None:
        return self.links.get(device_id)

    def is_online(self, device_id: str) -> bool:
        link = self.links.get(device_id)
        if link is None:
            return False
        return not link.is_stale(self.settings.HEARTBEAT_STALE_SECONDS)

    async def attach(self, device_id: str, websocket: WebSocket, paired: bool) -> DeviceLink:
        async with self._guard:
            old = self.links.get(device_id)
            if old is not None and old.websocket is not websocket:
                try:
                    await old.websocket.close(code=4000, reason="replaced by newer connection")
                except Exception:
                    pass
                self._fail_pending(old, CommandError("REPLACED", "Device reconnected"))
            link = DeviceLink(websocket=websocket, device_id=device_id, paired=paired)
            self.links[device_id] = link
            return link

    async def detach(self, device_id: str, websocket: WebSocket) -> None:
        async with self._guard:
            current = self.links.get(device_id)
            if current is not None and current.websocket is websocket:
                self.links.pop(device_id, None)
                if current.contact_id and self.contacts.get(current.contact_id) is current:
                    self.contacts.pop(current.contact_id, None)
                self._fail_pending(current, CommandError("DISCONNECTED", "Device disconnected"))

    async def register_contact(self, link: DeviceLink, contact_id: str, public_key: str) -> None:
        async with self._guard:
            previous = self.contacts.get(contact_id)
            if previous is not None and previous is not link:
                previous.contact_id = None
            if link.contact_id and self.contacts.get(link.contact_id) is link:
                self.contacts.pop(link.contact_id, None)
            link.contact_id = contact_id
            link.public_key = public_key
            self.contacts[contact_id] = link

    async def route_peer(self, sender: DeviceLink, message: dict) -> bool:
        """Relay an opaque message/signaling envelope to one exact registered contact."""
        target_id = str((message.get("payload") or {}).get("to") or "")
        target = self.contacts.get(target_id)
        if not sender.contact_id or target is None:
            return False
        forwarded = dict(message)
        payload = dict(message.get("payload") or {})
        payload["from"] = sender.contact_id
        payload.pop("to", None)
        forwarded["device_id"] = target.device_id
        forwarded["payload"] = payload
        async with target.lock:
            await target.websocket.send_json(forwarded)
        return True

    def _fail_pending(self, link: DeviceLink, error: CommandError) -> None:
        for fut in list(link.pending.values()):
            if not fut.done():
                fut.set_exception(error)
        link.pending.clear()
        for transfer in list(link.transfers.values()) + list(link.thumbs.values()):
            transfer.error = error
            try:
                transfer.queue.put_nowait(error)
            except Exception:
                pass

    async def send_json(self, device_id: str, message: dict) -> None:
        link = self.links.get(device_id)
        if link is None:
            raise DeviceOffline("Device is offline")
        async with link.lock:
            await link.websocket.send_json(message)

    async def command(self, device_id: str, msg_type: str, payload: dict | None = None, timeout: float | None = None) -> dict:
        link = self.links.get(device_id)
        if link is None or not link.paired:
            raise DeviceOffline("Device is offline")
        request_id = str(uuid.uuid4())
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        link.pending[request_id] = fut
        try:
            await self.send_json(device_id, envelope(msg_type, device_id, payload or {}, request_id))
            return await asyncio.wait_for(fut, timeout or self.settings.COMMAND_TIMEOUT_SECONDS)
        except DeviceOffline:
            raise
        except asyncio.TimeoutError as exc:
            raise CommandError("TIMEOUT", "Request timed out") from exc
        finally:
            link.pending.pop(request_id, None)

    def handle_text(self, link: DeviceLink, message: dict) -> None:
        link.touch()
        msg_type = message.get("type")
        request_id = message.get("request_id")
        payload = message.get("payload") or {}

        if msg_type == RESPONSE and request_id and request_id in link.pending:
            fut = link.pending.get(request_id)
            if fut and not fut.done():
                if payload.get("ok") is False:
                    fut.set_exception(
                        CommandError(payload.get("code") or "ERROR", payload.get("message") or "Device error")
                    )
                else:
                    fut.set_result(payload)
            return

        if msg_type == ERROR and request_id:
            err = CommandError(payload.get("code") or "ERROR", payload.get("message") or "Device error")
            fut = link.pending.get(request_id)
            if fut and not fut.done():
                fut.set_exception(err)
            transfer = link.transfers.get(request_id) or link.thumbs.get(request_id)
            if transfer is not None:
                transfer.error = err
                try:
                    transfer.queue.put_nowait(err)
                except Exception:
                    pass
            return

        if msg_type == FILE_BEGIN:
            transfer_id = payload.get("transfer_id") or request_id
            transfer = link.transfers.get(transfer_id)
            if transfer is not None:
                transfer.meta = payload
                try:
                    transfer.queue.put_nowait({"_meta": payload})
                except Exception:
                    pass
            return

        if msg_type == FILE_END:
            transfer_id = payload.get("transfer_id") or request_id
            transfer = link.transfers.get(transfer_id)
            if transfer is not None:
                transfer.done = True
                try:
                    transfer.queue.put_nowait(None)
                except Exception:
                    pass
            return

        if msg_type == SCREEN_STATUS:
            self._broadcast_screen_json(link.device_id, message)
            return

    def handle_binary(self, link: DeviceLink, data: bytes) -> None:
        link.touch()
        try:
            kind, transfer_uuid, seq, flags, payload = parse_frame(data)
        except ValueError:
            log.warning("ignored malformed binary frame from %s", link.device_id)
            return
        transfer_id = str(transfer_uuid)

        if kind == KIND_FILE:
            transfer = link.transfers.get(transfer_id)
            if transfer is None:
                return
            try:
                transfer.queue.put_nowait(payload)
            except asyncio.QueueFull:
                # bounded queue: drop is not OK for files; block via task is hard here
                # put in a fire-and-forget waiter
                asyncio.create_task(transfer.queue.put(payload))
            if flags & FLAG_LAST:
                transfer.done = True
                asyncio.create_task(transfer.queue.put(None))
            return

        if kind == KIND_THUMB:
            transfer = link.thumbs.get(transfer_id)
            if transfer is None:
                return
            try:
                transfer.queue.put_nowait(payload)
            except asyncio.QueueFull:
                asyncio.create_task(transfer.queue.put(payload))
            if flags & FLAG_LAST:
                asyncio.create_task(transfer.queue.put(None))
            return

        if kind == KIND_SCREEN:
            try:
                width, height, rotation, jpeg = unpack_screen_payload(payload)
            except ValueError:
                return
            self._broadcast_screen_frame(link.device_id, width, height, rotation, jpeg, seq)

    def _broadcast_screen_json(self, device_id: str, message: dict) -> None:
        for queue in list(self.screen_subs.get(device_id, set())):
            try:
                queue.put_nowait(("json", message))
            except asyncio.QueueFull:
                try:
                    queue.get_nowait()
                except Exception:
                    pass
                try:
                    queue.put_nowait(("json", message))
                except Exception:
                    pass

    def _broadcast_screen_frame(
        self, device_id: str, width: int, height: int, rotation: int, jpeg: bytes, seq: int
    ) -> None:
        header = {"type": "FRAME_META", "width": width, "height": height, "rotation": rotation, "seq": seq}
        for queue in list(self.screen_subs.get(device_id, set())):
            try:
                queue.put_nowait(("frame", header, jpeg))
            except asyncio.QueueFull:
                try:
                    queue.get_nowait()
                except Exception:
                    pass
                try:
                    queue.put_nowait(("frame", header, jpeg))
                except Exception:
                    pass

    def subscribe_screen(self, device_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=self.settings.SCREEN_QUEUE_SIZE)
        self.screen_subs.setdefault(device_id, set()).add(queue)
        return queue

    def unsubscribe_screen(self, device_id: str, queue: asyncio.Queue) -> int:
        subs = self.screen_subs.get(device_id)
        if not subs:
            return 0
        subs.discard(queue)
        remaining = len(subs)
        if remaining == 0:
            self.screen_subs.pop(device_id, None)
        return remaining

    def screen_subscriber_count(self, device_id: str) -> int:
        return len(self.screen_subs.get(device_id, set()))

    async def open_transfer(self, device_id: str, transfer_id: str) -> Transfer:
        link = self.links.get(device_id)
        if link is None or not link.paired:
            raise DeviceOffline("Device is offline")
        transfer = Transfer(queue=asyncio.Queue(maxsize=self.settings.TRANSFER_QUEUE_SIZE))
        link.transfers[transfer_id] = transfer
        return transfer

    def close_transfer(self, device_id: str, transfer_id: str) -> None:
        link = self.links.get(device_id)
        if link is not None:
            link.transfers.pop(transfer_id, None)
            link.thumbs.pop(transfer_id, None)

    async def open_thumb(self, device_id: str, request_id: str) -> Transfer:
        link = self.links.get(device_id)
        if link is None or not link.paired:
            raise DeviceOffline("Device is offline")
        transfer = Transfer(queue=asyncio.Queue(maxsize=4))
        link.thumbs[request_id] = transfer
        return transfer
