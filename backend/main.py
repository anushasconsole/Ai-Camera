"""
main.py – FastAPI entry-point for the AI Camera backend.

Routes
──────
POST /sessions                 Create or retrieve the caller's active session
GET  /sessions/{id}            Get session metadata
POST /sessions/{id}/join       Join an existing session
POST /sessions/{id}/photos     Upload one photo to Storage + register metadata
DELETE /sessions/{id}/photos/{photoId}  Remove a photo
POST /batches                  Submit a batch for AI analysis
GET  /batches/{id}             Get batch details + AI responses
WS   /ws/{session_id}         Real-time updates for a session
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from contextlib import asynccontextmanager
from typing import Annotated, Optional

import firebase_admin
from firebase_admin import credentials
from fastapi import (
    BackgroundTasks,
    Depends,
    FastAPI,
    File,
    Form,
    HTTPException,
    Query,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
    status,
)
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from config import settings
from auth.firebase_auth import verify_token
from database.firestore_service import (
    create_batch,
    delete_photo,
    get_ai_responses_for_batch,
    get_batch,
    get_photos_for_batch,
    get_session,
    save_photo_metadata,
    update_batch_status,
)
from realtime.websocket_manager import ws_manager
from sessions.session_manager import (
    get_or_create_session,
    join_session,
    set_active_batch,
    validate_session_access,
)
from storage.image_storage import delete_image, upload_image
from ai.ai_orchestrator import process_batch

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


# ── Firebase Admin initialisation ────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Support both local file path and inline JSON (for cloud deployments)
    if settings.firebase_service_account_json:
        import json as _json
        service_account_info = _json.loads(settings.firebase_service_account_json)
        cred = credentials.Certificate(service_account_info)
        logger.info("Firebase Admin SDK initialised from environment variable.")
    else:
        cred = credentials.Certificate(settings.firebase_service_account_path)
        logger.info("Firebase Admin SDK initialised from file: %s", settings.firebase_service_account_path)

    firebase_admin.initialize_app(
        cred,
        {"storageBucket": settings.firebase_storage_bucket},
    )
    yield
    # Clean up (optional)


app = FastAPI(title="AI Camera Backend", version="1.0.0", lifespan=lifespan)

# ── CORS ──────────────────────────────────────────────────────────────────────
origins = (
    [o.strip() for o in settings.allowed_origins.split(",")]
    if settings.allowed_origins != "*"
    else ["*"]
)
app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Type alias ────────────────────────────────────────────────────────────────
AuthUser = Annotated[dict, Depends(verify_token)]


# ═══════════════════════════════════════════════════════════════════════════════
# Session routes
# ═══════════════════════════════════════════════════════════════════════════════

class SessionResponse(BaseModel):
    sessionId: str
    created: bool


@app.post("/sessions", response_model=SessionResponse, status_code=201)
async def create_or_get_session(user: AuthUser):
    """Create a new session or return the caller's existing active session."""
    session_id, created = await get_or_create_session(user["uid"])
    return SessionResponse(sessionId=session_id, created=created)


@app.get("/sessions/{session_id}")
async def get_session_info(session_id: str, user: AuthUser):
    ok = await validate_session_access(session_id, user["uid"])
    if not ok:
        raise HTTPException(status_code=403, detail="Access denied to session.")
    session = await get_session(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="Session not found.")
    session["sessionId"] = session_id
    photos = await get_photos_for_batch(session.get("activeBatchId") or "")
    session["photos"] = photos
    return session


@app.post("/sessions/{session_id}/join", status_code=200)
async def join_existing_session(session_id: str, user: AuthUser):
    ok = await join_session(session_id, user["uid"])
    if not ok:
        raise HTTPException(status_code=404, detail="Session not found.")
    return {"joined": True, "sessionId": session_id}


# ═══════════════════════════════════════════════════════════════════════════════
# Photo routes
# ═══════════════════════════════════════════════════════════════════════════════

@app.post("/sessions/{session_id}/photos", status_code=201)
async def upload_photo(
    session_id: str,
    user: AuthUser,
    file: UploadFile = File(...),
    batch_id: str = Form(...),
    device_id: str = Form(...),
):
    """
    Upload a JPEG photo to Firebase Storage and register it in Firestore.
    The Android client calls this endpoint for each captured image.
    """
    if not await validate_session_access(session_id, user["uid"]):
        raise HTTPException(status_code=403, detail="Access denied to session.")

    image_bytes = await file.read()
    if not image_bytes:
        raise HTTPException(status_code=400, detail="Empty file.")

    photo_id = str(uuid.uuid4())
    storage_path, download_url = await upload_image(
        image_bytes=image_bytes,
        session_id=session_id,
        batch_id=batch_id,
        device_id=device_id,
        filename=photo_id,
    )

    await save_photo_metadata(
        photo_id=photo_id,
        session_id=session_id,
        batch_id=batch_id,
        storage_path=storage_path,
        download_url=download_url,
        device_id=device_id,
    )

    # Notify all connected devices
    await ws_manager.broadcast_photo_added(session_id, photo_id, download_url, device_id)

    return {
        "photoId": photo_id,
        "storagePath": storage_path,
        "downloadUrl": download_url,
    }


@app.delete("/sessions/{session_id}/photos/{photo_id}", status_code=204)
async def remove_photo(session_id: str, photo_id: str, user: AuthUser):
    if not await validate_session_access(session_id, user["uid"]):
        raise HTTPException(status_code=403, detail="Access denied to session.")

    from database.firestore_service import COL_PHOTOS
    from database.firestore_service import _db
    db = _db()
    snap = await db.collection(COL_PHOTOS).document(photo_id).get()
    if not snap.exists:
        raise HTTPException(status_code=404, detail="Photo not found.")

    photo_data = snap.to_dict()
    await delete_image(photo_data["storagePath"])
    await delete_photo(photo_id)

    # Notify all devices
    await ws_manager.broadcast(
        session_id,
        {"type": "photo_deleted", "data": {"photoId": photo_id}},
    )


# ═══════════════════════════════════════════════════════════════════════════════
# Batch / AI routes
# ═══════════════════════════════════════════════════════════════════════════════

class SubmitBatchRequest(BaseModel):
    sessionId: str
    batchId: str
    photoIds: list[str]
    prompt: str = "Analyse these images and describe what you see in detail."


@app.post("/batches", status_code=202)
async def submit_batch(
    body: SubmitBatchRequest,
    user: AuthUser,
    background_tasks: BackgroundTasks,
):
    """
    Submit a batch for AI analysis.
    Returns immediately; analysis runs in the background and streams via WS.
    """
    if not await validate_session_access(body.sessionId, user["uid"]):
        raise HTTPException(status_code=403, detail="Access denied to session.")

    if not body.photoIds:
        raise HTTPException(status_code=400, detail="No photos in batch.")

    # Resolve storage paths from photo IDs
    photos = await get_photos_for_batch(body.batchId)
    storage_paths = [p["storagePath"] for p in photos if p["photoId"] in body.photoIds]

    if not storage_paths:
        raise HTTPException(status_code=400, detail="No valid photos found for batch.")

    # Persist batch record
    await create_batch(
        batch_id=body.batchId,
        session_id=body.sessionId,
        photo_ids=body.photoIds,
        prompt=body.prompt,
    )
    await set_active_batch(body.sessionId, body.batchId)

    # Run AI processing in the background
    background_tasks.add_task(
        process_batch,
        session_id=body.sessionId,
        batch_id=body.batchId,
        prompt=body.prompt,
        storage_paths=storage_paths,
    )

    return {"accepted": True, "batchId": body.batchId}


@app.get("/batches/{batch_id}")
async def get_batch_status(batch_id: str, user: AuthUser):
    """Poll batch results – used on device reconnect to restore existing state."""
    batch = await get_batch(batch_id)
    if batch is None:
        raise HTTPException(status_code=404, detail="Batch not found.")

    session_id = batch.get("sessionId", "")
    if not await validate_session_access(session_id, user["uid"]):
        raise HTTPException(status_code=403, detail="Access denied.")

    ai_responses = await get_ai_responses_for_batch(batch_id)
    photos = await get_photos_for_batch(batch_id)
    return {
        "batch": batch,
        "aiResponses": ai_responses,
        "photos": photos,
    }


# ═══════════════════════════════════════════════════════════════════════════════
# WebSocket
# ═══════════════════════════════════════════════════════════════════════════════

@app.websocket("/ws/{session_id}")
async def websocket_endpoint(websocket: WebSocket, session_id: str):
    """
    WebSocket endpoint. The client must send a JSON auth frame first:
      {"type": "auth", "token": "<Firebase ID token>"}
    After that, all messages from the server are broadcast events.
    """
    await websocket.accept()

    # ── authentication handshake ──────────────────────────────────────────
    try:
        auth_frame = await asyncio.wait_for(websocket.receive_json(), timeout=10.0)
    except asyncio.TimeoutError:
        await websocket.close(code=4001, reason="Auth timeout")
        return

    if auth_frame.get("type") != "auth":
        await websocket.close(code=4002, reason="First message must be auth frame")
        return

    token = auth_frame.get("token", "")
    try:
        from firebase_admin import auth as fb_auth
        decoded = fb_auth.verify_id_token(token)
        user_id = decoded["uid"]
    except Exception:
        await websocket.close(code=4003, reason="Invalid token")
        return

    # Verify session access
    if not await validate_session_access(session_id, user_id):
        await websocket.close(code=4004, reason="Access denied")
        return

    # ── register connection ───────────────────────────────────────────────
    # Don't call accept() again; connection is already accepted above
    async with _connected(websocket, session_id):
        await ws_manager.send_to(
            websocket,
            {"type": "connected", "data": {"sessionId": session_id, "userId": user_id}},
        )

        # Send current batch state so late-joining devices sync immediately
        session_doc = await get_session(session_id)
        if session_doc and session_doc.get("activeBatchId"):
            batch_id = session_doc["activeBatchId"]
            batch_data = await get_batch(batch_id)
            if batch_data:
                ai_responses = await get_ai_responses_for_batch(batch_id)
                photos = await get_photos_for_batch(batch_id)
                await ws_manager.send_to(
                    websocket,
                    {
                        "type": "session_state",
                        "data": {
                            "batch": batch_data,
                            "aiResponses": ai_responses,
                            "photos": photos,
                        },
                    },
                )

        # Keep connection alive until client disconnects
        try:
            while True:
                data = await websocket.receive_text()
                # Ping-pong keepalive
                if data == "ping":
                    await websocket.send_text("pong")
        except WebSocketDisconnect:
            pass


from contextlib import asynccontextmanager as _acm


@_acm
async def _connected(ws: WebSocket, session_id: str):
    """Register / deregister a WebSocket without calling accept() again."""
    async with ws_manager._lock:
        ws_manager._connections[session_id].add(ws)
    logger.info("WS registered session=%s", session_id)
    try:
        yield
    finally:
        async with ws_manager._lock:
            ws_manager._connections[session_id].discard(ws)
            if not ws_manager._connections[session_id]:
                del ws_manager._connections[session_id]
        logger.info("WS removed session=%s", session_id)


# ═══════════════════════════════════════════════════════════════════════════════
# Health check
# ═══════════════════════════════════════════════════════════════════════════════

@app.get("/health")
async def health():
    return {
        "status": "ok",
        "activeSessions": ws_manager.active_sessions(),
    }
