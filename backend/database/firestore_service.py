"""
firestore_service.py
Low-level Firestore helpers.  All collection / document access lives here so
the rest of the backend never imports firebase_admin directly.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any, Optional

from google.cloud.firestore_v1 import AsyncClient, AsyncDocumentReference
import firebase_admin
from firebase_admin import firestore

logger = logging.getLogger(__name__)

# ─── collection names ────────────────────────────────────────────────────────
COL_USERS = "users"
COL_SESSIONS = "sessions"
COL_PHOTOS = "photos"
COL_BATCHES = "batches"
COL_AI_RESPONSES = "ai_responses"


def _db() -> AsyncClient:
    """Return the async Firestore client (initialised by firebase_admin)."""
    return firestore.AsyncClient(project=firebase_admin.get_app().project_id)


# ─── sessions ────────────────────────────────────────────────────────────────

async def create_session(session_id: str, owner_id: str) -> None:
    db = _db()
    await db.collection(COL_SESSIONS).document(session_id).set(
        {
            "ownerId": owner_id,
            "participantIds": [owner_id],
            "createdAt": datetime.now(timezone.utc),
            "activeBatchId": None,
            "status": "active",
        }
    )


async def get_session(session_id: str) -> Optional[dict]:
    db = _db()
    snap = await db.collection(COL_SESSIONS).document(session_id).get()
    return snap.to_dict() if snap.exists else None


async def update_session(session_id: str, data: dict) -> None:
    db = _db()
    await db.collection(COL_SESSIONS).document(session_id).update(data)


async def add_participant(session_id: str, user_id: str) -> None:
    db = _db()
    from google.cloud.firestore_v1 import ArrayUnion
    await db.collection(COL_SESSIONS).document(session_id).update(
        {"participantIds": ArrayUnion([user_id])}
    )


async def get_user_sessions(user_id: str) -> list[dict]:
    db = _db()
    query = (
        db.collection(COL_SESSIONS)
        .where("participantIds", "array_contains", user_id)
        .order_by("createdAt", direction="DESCENDING")
        .limit(20)
    )
    docs = query.stream()
    results = []
    async for doc in docs:
        d = doc.to_dict()
        d["sessionId"] = doc.id
        results.append(d)
    return results


# ─── photos ──────────────────────────────────────────────────────────────────

async def save_photo_metadata(
    photo_id: str,
    session_id: str,
    batch_id: str,
    storage_path: str,
    download_url: str,
    device_id: str,
) -> None:
    db = _db()
    await db.collection(COL_PHOTOS).document(photo_id).set(
        {
            "sessionId": session_id,
            "batchId": batch_id,
            "storagePath": storage_path,
            "downloadUrl": download_url,
            "deviceId": device_id,
            "createdAt": datetime.now(timezone.utc),
        }
    )


async def get_photos_for_batch(batch_id: str) -> list[dict]:
    db = _db()
    query = db.collection(COL_PHOTOS).where("batchId", "==", batch_id)
    results = []
    async for doc in query.stream():
        d = doc.to_dict()
        d["photoId"] = doc.id
        results.append(d)
    return results


async def delete_photo(photo_id: str) -> None:
    db = _db()
    await db.collection(COL_PHOTOS).document(photo_id).delete()


# ─── batches ─────────────────────────────────────────────────────────────────

async def create_batch(
    batch_id: str,
    session_id: str,
    photo_ids: list[str],
    prompt: str,
) -> None:
    db = _db()
    await db.collection(COL_BATCHES).document(batch_id).set(
        {
            "sessionId": session_id,
            "photoIds": photo_ids,
            "prompt": prompt,
            "status": "submitted",
            "createdAt": datetime.now(timezone.utc),
        }
    )


async def update_batch_status(batch_id: str, status: str) -> None:
    db = _db()
    await db.collection(COL_BATCHES).document(batch_id).update({"status": status})


async def get_batch(batch_id: str) -> Optional[dict]:
    db = _db()
    snap = await db.collection(COL_BATCHES).document(batch_id).get()
    return snap.to_dict() if snap.exists else None


# ─── AI responses ─────────────────────────────────────────────────────────────

async def save_ai_response(
    response_id: str,
    batch_id: str,
    provider: str,
    model: str,
) -> None:
    """Create the placeholder document; status is updated incrementally."""
    db = _db()
    await db.collection(COL_AI_RESPONSES).document(response_id).set(
        {
            "batchId": batch_id,
            "provider": provider,
            "model": model,
            "responseText": "",
            "status": "analysing",
            "error": None,
            "completedAt": None,
            "createdAt": datetime.now(timezone.utc),
        }
    )


async def append_ai_response_chunk(response_id: str, chunk: str) -> None:
    from google.cloud.firestore_v1 import Increment  # noqa: F401
    # Firestore doesn't natively append strings; we overwrite the field.
    # For large responses this is acceptable – streaming consumers get
    # updates via WebSocket; Firestore is the persistent store.
    db = _db()
    ref = db.collection(COL_AI_RESPONSES).document(response_id)
    snap = await ref.get()
    existing = snap.to_dict().get("responseText", "") if snap.exists else ""
    await ref.update({"responseText": existing + chunk})


async def complete_ai_response(response_id: str, full_text: str) -> None:
    db = _db()
    await db.collection(COL_AI_RESPONSES).document(response_id).update(
        {
            "responseText": full_text,
            "status": "completed",
            "completedAt": datetime.now(timezone.utc),
        }
    )


async def fail_ai_response(response_id: str, error: str) -> None:
    db = _db()
    await db.collection(COL_AI_RESPONSES).document(response_id).update(
        {
            "status": "error",
            "error": error,
            "completedAt": datetime.now(timezone.utc),
        }
    )


async def get_ai_responses_for_batch(batch_id: str) -> list[dict]:
    db = _db()
    query = db.collection(COL_AI_RESPONSES).where("batchId", "==", batch_id)
    results = []
    async for doc in query.stream():
        d = doc.to_dict()
        d["responseId"] = doc.id
        results.append(d)
    return results
