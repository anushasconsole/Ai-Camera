package com.aicamera.app.data.model

import kotlinx.serialization.Serializable

// ─── Session ──────────────────────────────────────────────────────────────────

data class Session(
    val sessionId: String = "",
    val ownerId: String = "",
    val participantIds: List<String> = emptyList(),
    val activeBatchId: String? = null,
    val status: String = "active",
    val photos: List<Photo> = emptyList(),
)

// ─── Photo ────────────────────────────────────────────────────────────────────

data class Photo(
    val photoId: String = "",
    val sessionId: String = "",
    val batchId: String = "",
    val storagePath: String = "",
    val downloadUrl: String = "",
    val deviceId: String = "",
    // Local-only – not persisted to Firestore
    val localUri: String? = null,
)

// ─── Batch ────────────────────────────────────────────────────────────────────

data class Batch(
    val batchId: String = "",
    val sessionId: String = "",
    val photoIds: List<String> = emptyList(),
    val prompt: String = "",
    val status: String = "submitted",
)

// ─── AI Response ──────────────────────────────────────────────────────────────

data class AiResponse(
    val responseId: String = "",
    val batchId: String = "",
    val provider: String = "",
    val model: String = "",
    val responseText: String = "",
    val status: AiResponseStatus = AiResponseStatus.WAITING,
    val error: String? = null,
)

enum class AiResponseStatus {
    WAITING,
    ANALYSING,
    COMPLETED,
    ERROR,
}

// ─── WebSocket messages ───────────────────────────────────────────────────────

@Serializable
data class WsMessage(
    val type: String,
    val data: kotlinx.serialization.json.JsonObject? = null,
)

// ─── API request/response models ─────────────────────────────────────────────

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val created: Boolean,
)

@Serializable
data class UploadPhotoResponse(
    val photoId: String,
    val storagePath: String,
    val downloadUrl: String,
)

@Serializable
data class SubmitBatchRequest(
    val sessionId: String,
    val batchId: String,
    val photoIds: List<String>,
    val prompt: String,
)

@Serializable
data class SubmitBatchResponse(
    val accepted: Boolean,
    val batchId: String,
)

// ─── Volume button state machine ─────────────────────────────────────────────

/**
 * Tracks how many Volume Up presses have occurred in the current session.
 *
 * IDLE          – app just opened, camera not yet initialised
 * CAMERA_OPEN   – camera preview is live (1st press done)
 * CAPTURING     – capturing in progress (2nd+ press)
 */
enum class CameraState {
    IDLE,
    CAMERA_OPEN,
    CAPTURING,
}
