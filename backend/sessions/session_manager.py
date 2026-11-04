"""
session_manager.py
Business logic for session creation, joining and validation.
"""

from __future__ import annotations

import logging
import uuid
from typing import Optional

from database.firestore_service import (
    create_session,
    get_session,
    update_session,
    add_participant,
    get_user_sessions,
)

logger = logging.getLogger(__name__)


async def get_or_create_session(user_id: str) -> tuple[str, bool]:
    """
    Return (session_id, created).
    Looks for the user's most recent active session; creates one if none exists.
    """
    sessions = await get_user_sessions(user_id)
    for s in sessions:
        if s.get("status") == "active":
            # Ensure user is listed as participant (handles re-login edge case)
            await add_participant(s["sessionId"], user_id)
            return s["sessionId"], False

    # No active session – create one
    session_id = str(uuid.uuid4())
    await create_session(session_id, user_id)
    logger.info("Created new session %s for user %s", session_id, user_id)
    return session_id, True


async def join_session(session_id: str, user_id: str) -> bool:
    """
    Add *user_id* as a participant of *session_id*.
    Returns False if the session does not exist.
    """
    session = await get_session(session_id)
    if session is None:
        return False
    await add_participant(session_id, user_id)
    return True


async def validate_session_access(session_id: str, user_id: str) -> bool:
    """Return True if *user_id* is a participant of *session_id*."""
    session = await get_session(session_id)
    if session is None:
        return False
    return user_id in session.get("participantIds", [])


async def set_active_batch(session_id: str, batch_id: str) -> None:
    await update_session(session_id, {"activeBatchId": batch_id})
