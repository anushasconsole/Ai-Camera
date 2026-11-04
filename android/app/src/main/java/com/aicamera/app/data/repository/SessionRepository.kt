package com.aicamera.app.data.repository

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.aicamera.app.data.model.*
import com.aicamera.app.network.RetrofitClient
import com.aicamera.app.network.WebSocketClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val TAG = "SessionRepository"

/**
 * Central data layer.  All ViewModel interactions go through here.
 */
class SessionRepository(private val context: Context) {

    private val api = RetrofitClient.apiService
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Observable state ───────────────────────────────────────────────────

    private val _sessionState = MutableStateFlow<Session?>(null)
    val sessionState: StateFlow<Session?> = _sessionState

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos: StateFlow<List<Photo>> = _photos

    private val _aiResponses = MutableStateFlow<Map<String, AiResponse>>(emptyMap())
    val aiResponses: StateFlow<Map<String, AiResponse>> = _aiResponses

    private val _wsConnected = MutableStateFlow(false)
    val wsConnected: StateFlow<Boolean> = _wsConnected

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessages: SharedFlow<String> = _toastMessages

    // ── Internal state ─────────────────────────────────────────────────────

    private var wsClient: WebSocketClient? = null
    private var firestoreListener: ListenerRegistration? = null
    private var currentBatchId: String? = null

    /** Unique identifier for this device install */
    val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    }

    // ── Authentication helpers ─────────────────────────────────────────────

    private suspend fun idToken(): String {
        val user = auth.currentUser ?: throw IllegalStateException("Not signed in")
        return user.getIdToken(false).await().token
            ?: throw IllegalStateException("Could not retrieve ID token")
    }

    private fun bearerToken(token: String) = "Bearer $token"

    // ── Session lifecycle ──────────────────────────────────────────────────

    suspend fun initSession(): String {
        val token = idToken()
        val response = api.createOrGetSession(bearerToken(token))
        val sessionId = response.sessionId
        Log.d(TAG, "Session: $sessionId (created=${response.created})")

        _sessionState.value = Session(sessionId = sessionId)

        // Subscribe to Firestore real-time updates
        subscribeToSession(sessionId)

        // Connect WebSocket
        connectWebSocket(sessionId, token)

        return sessionId
    }

    suspend fun joinSession(sessionId: String) {
        val token = idToken()
        api.joinSession(bearerToken(token), sessionId)
        subscribeToSession(sessionId)
        connectWebSocket(sessionId, token)
    }

    private fun subscribeToSession(sessionId: String) {
        firestoreListener?.remove()
        firestoreListener = db.collection("sessions")
            .document(sessionId)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e(TAG, "Firestore listen error: ${err.message}")
                    return@addSnapshotListener
                }
                if (snap != null && snap.exists()) {
                    val data = snap.data ?: return@addSnapshotListener
                    val updated = Session(
                        sessionId = sessionId,
                        ownerId = data["ownerId"] as? String ?: "",
                        participantIds = (data["participantIds"] as? List<*>)
                            ?.filterIsInstance<String>() ?: emptyList(),
                        activeBatchId = data["activeBatchId"] as? String,
                        status = data["status"] as? String ?: "active",
                    )
                    _sessionState.value = updated
                }
            }
    }

    private fun connectWebSocket(sessionId: String, token: String) {
        wsClient?.disconnect()

        val ws = WebSocketClient(sessionId, token, scope)
        wsClient = ws

        scope.launch {
            ws.connected.collect { connected ->
                _wsConnected.value = connected
                Log.d(TAG, "WS connected=$connected")
            }
        }

        scope.launch {
            ws.messages.collect { raw ->
                handleWsMessage(raw)
            }
        }

        ws.connect()
    }

    private fun handleWsMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.getString("type")
            val data = if (json.has("data")) json.getJSONObject("data") else null

            when (type) {
                "connected" -> {
                    Log.d(TAG, "WS auth confirmed")
                }

                "session_state" -> {
                    // Restore full state on reconnect
                    data?.let { restoreStateFromWs(it) }
                }

                "photo_added" -> {
                    data?.let {
                        val photo = Photo(
                            photoId = it.getString("photoId"),
                            downloadUrl = it.getString("downloadUrl"),
                            deviceId = it.getString("deviceId"),
                            batchId = currentBatchId ?: "",
                            sessionId = _sessionState.value?.sessionId ?: "",
                        )
                        addPhotoToList(photo)
                    }
                }

                "photo_deleted" -> {
                    data?.let {
                        val photoId = it.getString("photoId")
                        _photos.value = _photos.value.filter { p -> p.photoId != photoId }
                    }
                }

                "batch_status" -> {
                    data?.let {
                        val batchId = it.getString("batchId")
                        val status = it.getString("status")
                        Log.d(TAG, "Batch $batchId → $status")
                        if (status == "processing") {
                            // Mark both providers as analysing
                            val updated = _aiResponses.value.toMutableMap()
                            updated.forEach { (key, value) ->
                                if (value.status == AiResponseStatus.WAITING) {
                                    updated[key] = value.copy(status = AiResponseStatus.ANALYSING)
                                }
                            }
                            _aiResponses.value = updated
                        }
                    }
                }

                "ai_chunk" -> {
                    data?.let {
                        val responseId = it.getString("responseId")
                        val provider = it.getString("provider")
                        val chunk = it.getString("chunk")
                        updateAiChunk(responseId, provider, chunk)
                    }
                }

                "ai_complete" -> {
                    data?.let {
                        val responseId = it.getString("responseId")
                        val provider = it.getString("provider")
                        val fullText = it.getString("fullText")
                        completeAiResponse(responseId, provider, fullText)
                    }
                }

                "ai_error" -> {
                    data?.let {
                        val responseId = it.getString("responseId")
                        val provider = it.getString("provider")
                        val error = it.getString("error")
                        errorAiResponse(responseId, provider, error)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WS message parse error: ${e.message}")
        }
    }

    private fun restoreStateFromWs(data: JSONObject) {
        // Restore photos
        val photosArr = data.optJSONArray("photos") ?: return
        val photos = mutableListOf<Photo>()
        for (i in 0 until photosArr.length()) {
            val p = photosArr.getJSONObject(i)
            photos.add(
                Photo(
                    photoId = p.optString("photoId"),
                    sessionId = p.optString("sessionId"),
                    batchId = p.optString("batchId"),
                    storagePath = p.optString("storagePath"),
                    downloadUrl = p.optString("downloadUrl"),
                    deviceId = p.optString("deviceId"),
                )
            )
        }
        _photos.value = photos

        // Restore AI responses
        val responsesArr = data.optJSONArray("aiResponses") ?: return
        val responses = mutableMapOf<String, AiResponse>()
        for (i in 0 until responsesArr.length()) {
            val r = responsesArr.getJSONObject(i)
            val status = when (r.optString("status")) {
                "completed" -> AiResponseStatus.COMPLETED
                "analysing" -> AiResponseStatus.ANALYSING
                "error" -> AiResponseStatus.ERROR
                else -> AiResponseStatus.WAITING
            }
            val ar = AiResponse(
                responseId = r.optString("responseId"),
                batchId = r.optString("batchId"),
                provider = r.optString("provider"),
                model = r.optString("model"),
                responseText = r.optString("responseText"),
                status = status,
                error = r.optString("error").ifEmpty { null },
            )
            responses[ar.provider] = ar
        }
        _aiResponses.value = responses
    }

    private fun addPhotoToList(photo: Photo) {
        val current = _photos.value.toMutableList()
        if (current.none { it.photoId == photo.photoId }) {
            current.add(photo)
            _photos.value = current
        }
    }

    private fun updateAiChunk(responseId: String, provider: String, chunk: String) {
        val current = _aiResponses.value.toMutableMap()
        val existing = current[provider] ?: AiResponse(
            responseId = responseId,
            provider = provider,
            status = AiResponseStatus.ANALYSING,
        )
        current[provider] = existing.copy(
            responseId = responseId,
            responseText = existing.responseText + chunk,
            status = AiResponseStatus.ANALYSING,
        )
        _aiResponses.value = current
    }

    private fun completeAiResponse(responseId: String, provider: String, fullText: String) {
        val current = _aiResponses.value.toMutableMap()
        val existing = current[provider] ?: AiResponse(responseId = responseId, provider = provider)
        current[provider] = existing.copy(
            responseId = responseId,
            responseText = fullText,
            status = AiResponseStatus.COMPLETED,
        )
        _aiResponses.value = current
    }

    private fun errorAiResponse(responseId: String, provider: String, error: String) {
        val current = _aiResponses.value.toMutableMap()
        val existing = current[provider] ?: AiResponse(responseId = responseId, provider = provider)
        current[provider] = existing.copy(
            responseId = responseId,
            status = AiResponseStatus.ERROR,
            error = error,
        )
        _aiResponses.value = current
    }

    // ── Photo operations ───────────────────────────────────────────────────

    /**
     * Upload photo bytes to the backend and add to local state.
     * The backend notifies all other devices via WebSocket.
     */
    suspend fun uploadPhoto(
        sessionId: String,
        batchId: String,
        jpegBytes: ByteArray,
    ): Photo {
        val token = idToken()
        currentBatchId = batchId

        // Write to temp file for multipart upload
        val tmpFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
        tmpFile.writeBytes(jpegBytes)

        val requestFile = tmpFile.asRequestBody("image/jpeg".toMediaType())
        val body = MultipartBody.Part.createFormData("file", tmpFile.name, requestFile)
        val batchIdBody = batchId.toRequestBody("text/plain".toMediaType())
        val deviceIdBody = deviceId.toRequestBody("text/plain".toMediaType())

        val response = api.uploadPhoto(bearerToken(token), sessionId, body, batchIdBody, deviceIdBody)
        tmpFile.delete()

        val photo = Photo(
            photoId = response.photoId,
            sessionId = sessionId,
            batchId = batchId,
            storagePath = response.storagePath,
            downloadUrl = response.downloadUrl,
            deviceId = deviceId,
        )

        // Add to local list immediately (WS broadcast will also arrive for other devices)
        addPhotoToList(photo)
        return photo
    }

    suspend fun deletePhoto(sessionId: String, photoId: String) {
        val token = idToken()
        api.deletePhoto(bearerToken(token), sessionId, photoId)
        _photos.value = _photos.value.filter { it.photoId != photoId }
    }

    // ── Batch submission ───────────────────────────────────────────────────

    /**
     * Submit the current batch for AI analysis.
     * Returns false if there are no photos or if already processing.
     */
    suspend fun submitBatch(sessionId: String, batchId: String, prompt: String): Boolean {
        val photoIds = _photos.value
            .filter { it.batchId == batchId }
            .map { it.photoId }

        if (photoIds.isEmpty()) {
            _toastMessages.emit("Capture a photo first")
            return false
        }

        val token = idToken()

        // Initialise waiting response placeholders
        _aiResponses.value = mapOf(
            "Google Gemini" to AiResponse(provider = "Google Gemini", status = AiResponseStatus.WAITING),
            "Groq Qwen Vision" to AiResponse(provider = "Groq Qwen Vision", status = AiResponseStatus.WAITING),
        )

        val request = SubmitBatchRequest(
            sessionId = sessionId,
            batchId = batchId,
            photoIds = photoIds,
            prompt = prompt,
        )
        api.submitBatch(bearerToken(token), request)
        return true
    }

    // ── New batch ──────────────────────────────────────────────────────────

    fun newBatch(): String {
        val id = UUID.randomUUID().toString()
        currentBatchId = id
        // Don't clear photos so history is preserved; UI shows current batch only
        return id
    }

    fun clearCurrentBatchPhotos(batchId: String) {
        _photos.value = _photos.value.filter { it.batchId != batchId }
    }

    // ── Cleanup ────────────────────────────────────────────────────────────

    fun release() {
        firestoreListener?.remove()
        wsClient?.disconnect()
        scope.cancel()
    }
}
