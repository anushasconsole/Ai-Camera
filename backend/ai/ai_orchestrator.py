"""
ai_orchestrator.py
Runs Gemini and Groq concurrently for a batch of images.
Streams chunks in real-time via WebSocket and persists results to Firestore.
Implements server-side idempotency: a batch can only be submitted once.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from typing import Optional

from config import settings
from database.firestore_service import (
    get_batch,
    update_batch_status,
    save_ai_response,
    append_ai_response_chunk,
    complete_ai_response,
    fail_ai_response,
)
from realtime.websocket_manager import ws_manager
from storage.image_storage import download_image
from .gemini_client import stream_gemini
from .groq_client import stream_groq

logger = logging.getLogger(__name__)

# In-memory lock set to prevent concurrent duplicate submissions.
# Works for a single-process server; use Redis for multi-instance deployments.
_processing_batches: set[str] = set()
_processing_lock = asyncio.Lock()


async def _acquire_batch(batch_id: str) -> bool:
    """Return True if this process acquired the right to process this batch."""
    async with _processing_lock:
        if batch_id in _processing_batches:
            return False
        _processing_batches.add(batch_id)
        return True


async def _release_batch(batch_id: str) -> None:
    async with _processing_lock:
        _processing_batches.discard(batch_id)


async def _run_provider(
    provider_name: str,
    model_name: str,
    stream_fn,
    image_bytes_list: list[bytes],
    prompt: str,
    session_id: str,
    batch_id: str,
) -> None:
    """Run one provider, stream chunks via WS, persist to Firestore."""
    response_id = str(uuid.uuid4())

    await save_ai_response(response_id, batch_id, provider_name, model_name)
    await ws_manager.broadcast_ai_chunk(
        session_id, batch_id, response_id, provider_name, ""
    )

    full_text = ""
    try:
        async for chunk in stream_fn(image_bytes_list, prompt):
            full_text += chunk
            # Broadcast each chunk immediately
            await ws_manager.broadcast_ai_chunk(
                session_id, batch_id, response_id, provider_name, chunk
            )

        await complete_ai_response(response_id, full_text)
        await ws_manager.broadcast_ai_complete(
            session_id, batch_id, response_id, provider_name, full_text
        )
        logger.info("Provider %s completed for batch %s", provider_name, batch_id)

    except Exception as exc:
        error_msg = str(exc)
        logger.error("Provider %s failed for batch %s: %s", provider_name, batch_id, error_msg)
        await fail_ai_response(response_id, error_msg)
        await ws_manager.broadcast_ai_error(
            session_id, batch_id, response_id, provider_name, error_msg
        )


async def process_batch(
    session_id: str,
    batch_id: str,
    prompt: str,
    storage_paths: list[str],
) -> None:
    """
    Entry point called by the HTTP route.
    Downloads images, then runs Gemini + Groq concurrently.
    Idempotent: a second call with the same batch_id is a no-op.
    """
    # ── idempotency guard ─────────────────────────────────────────────────
    batch_doc = await get_batch(batch_id)
    if batch_doc and batch_doc.get("status") not in ("submitted", "error"):
        logger.info("Batch %s already processed (status=%s), skipping.", batch_id, batch_doc.get("status"))
        return

    acquired = await _acquire_batch(batch_id)
    if not acquired:
        logger.info("Batch %s already being processed in this process.", batch_id)
        return

    try:
        await update_batch_status(batch_id, "processing")
        await ws_manager.broadcast_batch_status(session_id, batch_id, "processing")

        # Download all images concurrently
        logger.info("Downloading %d images for batch %s", len(storage_paths), batch_id)
        image_bytes_list: list[bytes] = await asyncio.gather(
            *[download_image(path) for path in storage_paths]
        )

        # Run both providers concurrently; neither can cancel the other
        await asyncio.gather(
            _run_provider(
                settings.gemini_provider_name,
                settings.gemini_model,
                stream_gemini,
                image_bytes_list,
                prompt,
                session_id,
                batch_id,
            ),
            _run_provider(
                settings.groq_provider_name,
                settings.groq_model,
                stream_groq,
                image_bytes_list,
                prompt,
                session_id,
                batch_id,
            ),
            return_exceptions=True,  # one failure must not abort the other
        )

        await update_batch_status(batch_id, "completed")
        await ws_manager.broadcast_batch_status(session_id, batch_id, "completed")
        logger.info("Batch %s completed.", batch_id)

    except Exception as exc:
        logger.error("Batch %s orchestration error: %s", batch_id, exc)
        await update_batch_status(batch_id, "error")
        await ws_manager.broadcast_batch_status(session_id, batch_id, "error")

    finally:
        await _release_batch(batch_id)
