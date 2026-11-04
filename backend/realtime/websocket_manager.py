"""
websocket_manager.py
Manages active WebSocket connections grouped by session_id.
Provides broadcast helpers used by the AI orchestrator to push
streaming updates to all devices watching the same session.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections import defaultdict
from typing import Any

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class WebSocketManager:
    """Thread-safe registry of active WebSocket connections."""

    def __init__(self) -> None:
        # session_id → set of active WebSocket connections
        self._connections: dict[str, set[WebSocket]] = defaultdict(set)
        self._lock = asyncio.Lock()

    # ── connection lifecycle ──────────────────────────────────────────────

    async def connect(self, websocket: WebSocket, session_id: str) -> None:
        await websocket.accept()
        async with self._lock:
            self._connections[session_id].add(websocket)
        logger.info(
            "WS connected  session=%s total=%d",
            session_id,
            len(self._connections[session_id]),
        )

    async def disconnect(self, websocket: WebSocket, session_id: str) -> None:
        async with self._lock:
            self._connections[session_id].discard(websocket)
            if not self._connections[session_id]:
                del self._connections[session_id]
        logger.info("WS disconnected  session=%s", session_id)

    # ── message helpers ───────────────────────────────────────────────────

    async def broadcast(self, session_id: str, payload: dict) -> None:
        """Send *payload* to every device connected to *session_id*."""
        message = json.dumps(payload)
        async with self._lock:
            sockets = set(self._connections.get(session_id, set()))

        dead: list[WebSocket] = []
        for ws in sockets:
            try:
                await ws.send_text(message)
            except Exception as exc:
                logger.warning("WS send failed (%s), will disconnect: %s", session_id, exc)
                dead.append(ws)

        # Clean up broken sockets
        if dead:
            async with self._lock:
                for ws in dead:
                    self._connections[session_id].discard(ws)

    async def send_to(self, websocket: WebSocket, payload: dict) -> None:
        """Send *payload* to a single WebSocket connection."""
        try:
            await websocket.send_text(json.dumps(payload))
        except Exception as exc:
            logger.warning("WS single send failed: %s", exc)

    # ── typed broadcast helpers ───────────────────────────────────────────

    async def broadcast_session_update(self, session_id: str, data: dict) -> None:
        await self.broadcast(session_id, {"type": "session_update", "data": data})

    async def broadcast_photo_added(
        self, session_id: str, photo_id: str, download_url: str, device_id: str
    ) -> None:
        await self.broadcast(
            session_id,
            {
                "type": "photo_added",
                "data": {
                    "photoId": photo_id,
                    "downloadUrl": download_url,
                    "deviceId": device_id,
                },
            },
        )

    async def broadcast_batch_status(
        self, session_id: str, batch_id: str, status: str
    ) -> None:
        await self.broadcast(
            session_id,
            {"type": "batch_status", "data": {"batchId": batch_id, "status": status}},
        )

    async def broadcast_ai_chunk(
        self,
        session_id: str,
        batch_id: str,
        response_id: str,
        provider: str,
        chunk: str,
    ) -> None:
        await self.broadcast(
            session_id,
            {
                "type": "ai_chunk",
                "data": {
                    "batchId": batch_id,
                    "responseId": response_id,
                    "provider": provider,
                    "chunk": chunk,
                },
            },
        )

    async def broadcast_ai_complete(
        self,
        session_id: str,
        batch_id: str,
        response_id: str,
        provider: str,
        full_text: str,
    ) -> None:
        await self.broadcast(
            session_id,
            {
                "type": "ai_complete",
                "data": {
                    "batchId": batch_id,
                    "responseId": response_id,
                    "provider": provider,
                    "fullText": full_text,
                },
            },
        )

    async def broadcast_ai_error(
        self,
        session_id: str,
        batch_id: str,
        response_id: str,
        provider: str,
        error: str,
    ) -> None:
        await self.broadcast(
            session_id,
            {
                "type": "ai_error",
                "data": {
                    "batchId": batch_id,
                    "responseId": response_id,
                    "provider": provider,
                    "error": error,
                },
            },
        )

    def active_sessions(self) -> list[str]:
        return list(self._connections.keys())

    def connection_count(self, session_id: str) -> int:
        return len(self._connections.get(session_id, set()))


# Singleton used throughout the application
ws_manager = WebSocketManager()
