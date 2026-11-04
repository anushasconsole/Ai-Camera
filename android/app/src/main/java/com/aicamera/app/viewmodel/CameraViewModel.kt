package com.aicamera.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aicamera.app.camera.CameraManager
import com.aicamera.app.data.model.*
import com.aicamera.app.data.repository.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CameraViewModel"

// Debounce: ignore button presses within this window
private const val BUTTON_DEBOUNCE_MS = 500L

data class CameraUiState(
    val cameraState: CameraState = CameraState.IDLE,
    val photoCount: Int = 0,
    val isUploading: Boolean = false,
    val isSubmitting: Boolean = false,
    val sessionId: String = "",
    val currentBatchId: String = "",
    val wsConnected: Boolean = false,
    val toastMessage: String? = null,
)

data class CapturedPhotoUi(
    val photoId: String,
    val downloadUrl: String,
    val thumbnail: Bitmap?,
    val localBytes: ByteArray?,
)

class CameraViewModel(
    private val context: Context,
    private val repository: SessionRepository,
) : ViewModel() {

    // ── Public state ───────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState

    /** Local thumbnails for photos captured on this device */
    private val _localPhotos = MutableStateFlow<List<CapturedPhotoUi>>(emptyList())
    val localPhotos: StateFlow<List<CapturedPhotoUi>> = _localPhotos

    /** Photos from all devices (via Firestore sync) */
    val remotePhotos: StateFlow<List<Photo>> = repository.photos

    val aiResponses: StateFlow<Map<String, AiResponse>> = repository.aiResponses
    val wsConnected: StateFlow<Boolean> = repository.wsConnected

    /** Prompt used when submitting the batch */
    var userPrompt: String = "Analyse these images and describe what you see in detail."

    // ── Internal ───────────────────────────────────────────────────────────

    private val cameraManager = CameraManager(context)
    private var lastButtonPressTime = 0L
    private val isCapturing = AtomicBoolean(false)
    private val isSubmitting = AtomicBoolean(false)
    private var currentBatchId: String = UUID.randomUUID().toString()

    init {
        // Forward toast messages from repository to UI state
        viewModelScope.launch {
            repository.toastMessages.collect { msg ->
                showToast(msg)
            }
        }

        // Mirror WS connection status
        viewModelScope.launch {
            repository.wsConnected.collect { connected ->
                _uiState.update { it.copy(wsConnected = connected) }
            }
        }

        // Initialise session
        viewModelScope.launch {
            try {
                val sessionId = repository.initSession()
                _uiState.update {
                    it.copy(sessionId = sessionId, currentBatchId = currentBatchId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Session init failed: ${e.message}")
                showToast("Failed to connect to server: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VOLUME BUTTON HANDLING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Called by the Activity when a physical Volume Up press is detected.
     * Implements the exact workflow from the spec:
     *   1st press → open camera preview
     *   2nd press → capture photo 1
     *   Nth press → capture photo N-1
     */
    fun onVolumeUp(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (!debounce()) return

        viewModelScope.launch {
            when (_uiState.value.cameraState) {
                CameraState.IDLE -> {
                    // First press: open camera, do NOT capture yet
                    openCamera(lifecycleOwner, previewView)
                }

                CameraState.CAMERA_OPEN,
                CameraState.CAPTURING -> {
                    // Second and subsequent presses: capture a photo
                    capturePhoto()
                }
            }
        }
    }

    /**
     * Called by the Activity when a physical Volume Down press is detected.
     * Submits the current photo batch to both AI providers.
     */
    fun onVolumeDown() {
        if (!debounce()) return

        val sessionId = _uiState.value.sessionId
        if (sessionId.isEmpty()) {
            showToast("Not connected to session yet")
            return
        }

        val currentPhotos = repository.photos.value
            .filter { it.batchId == currentBatchId }

        if (currentPhotos.isEmpty()) {
            showToast("Capture a photo first")
            return
        }

        if (isSubmitting.getAndSet(true)) {
            showToast("Analysis already in progress")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isSubmitting = true) }
                val success = repository.submitBatch(sessionId, currentBatchId, userPrompt)
                if (!success) {
                    isSubmitting.set(false)
                    _uiState.update { it.copy(isSubmitting = false) }
                }
                // isSubmitting cleared when AI responses complete (observed via flow)
            } catch (e: Exception) {
                Log.e(TAG, "Submit failed: ${e.message}")
                showToast("Submission failed: ${e.message}")
                isSubmitting.set(false)
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }

        // Watch for completion to unlock submit
        viewModelScope.launch {
            repository.aiResponses.collect { responses ->
                val allDone = responses.values.all {
                    it.status == AiResponseStatus.COMPLETED || it.status == AiResponseStatus.ERROR
                }
                if (allDone && responses.isNotEmpty()) {
                    isSubmitting.set(false)
                    _uiState.update { it.copy(isSubmitting = false) }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Camera operations
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun openCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        _uiState.update { it.copy(cameraState = CameraState.CAMERA_OPEN) }
        try {
            cameraManager.bindCamera(lifecycleOwner, previewView)
            Log.d(TAG, "Camera opened")
        } catch (e: Exception) {
            Log.e(TAG, "Camera open failed: ${e.message}")
            _uiState.update { it.copy(cameraState = CameraState.IDLE) }
            showToast("Camera failed to open: ${e.message}")
        }
    }

    private suspend fun capturePhoto() {
        if (isCapturing.getAndSet(true)) return  // prevent duplicate capture

        try {
            _uiState.update { it.copy(cameraState = CameraState.CAPTURING) }

            val jpegBytes = cameraManager.capturePhoto()
            val thumbnail = cameraManager.generateThumbnail(jpegBytes)

            // Optimistic local UI update
            val tempId = UUID.randomUUID().toString()
            val localPhoto = CapturedPhotoUi(
                photoId = tempId,
                downloadUrl = "",
                thumbnail = thumbnail,
                localBytes = jpegBytes,
            )
            _localPhotos.update { it + localPhoto }
            _uiState.update { it.copy(photoCount = _uiState.value.photoCount + 1, isUploading = true) }

            // Upload to backend
            val sessionId = _uiState.value.sessionId
            if (sessionId.isNotEmpty()) {
                try {
                    val photo = repository.uploadPhoto(sessionId, currentBatchId, jpegBytes)
                    // Replace temp entry with confirmed photo
                    _localPhotos.update { list ->
                        list.map { if (it.photoId == tempId) it.copy(photoId = photo.photoId, downloadUrl = photo.downloadUrl) else it }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Upload failed: ${e.message}")
                    showToast("Photo upload failed: ${e.message}")
                }
            }

            _uiState.update { it.copy(isUploading = false) }
            Log.d(TAG, "Photo captured and uploaded. Total: ${_uiState.value.photoCount}")

        } catch (e: Exception) {
            Log.e(TAG, "Capture failed: ${e.message}")
            showToast("Capture failed: ${e.message}")
        } finally {
            isCapturing.set(false)
        }
    }

    // ── Photo management ───────────────────────────────────────────────────

    fun deletePhoto(photoId: String) {
        viewModelScope.launch {
            val sessionId = _uiState.value.sessionId
            if (sessionId.isEmpty()) return@launch
            try {
                repository.deletePhoto(sessionId, photoId)
                _localPhotos.update { it.filter { p -> p.photoId != photoId } }
                _uiState.update { it.copy(photoCount = maxOf(0, _uiState.value.photoCount - 1)) }
            } catch (e: Exception) {
                showToast("Delete failed: ${e.message}")
            }
        }
    }

    fun startNewBatch() {
        val oldBatchId = currentBatchId
        currentBatchId = UUID.randomUUID().toString()
        _localPhotos.value = emptyList()
        _uiState.update {
            it.copy(
                photoCount = 0,
                isSubmitting = false,
                currentBatchId = currentBatchId,
            )
        }
        isSubmitting.set(false)
        viewModelScope.launch {
            repository.clearCurrentBatchPhotos(oldBatchId)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Returns true only if at least BUTTON_DEBOUNCE_MS have passed since
     * the last accepted press.  Prevents double-trigger on Samsung hardware.
     */
    private fun debounce(): Boolean {
        val now = System.currentTimeMillis()
        return if (now - lastButtonPressTime > BUTTON_DEBOUNCE_MS) {
            lastButtonPressTime = now
            true
        } else {
            false
        }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        cameraManager.release()
        repository.release()
    }

    // ── ViewModelFactory ───────────────────────────────────────────────────

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return CameraViewModel(context, SessionRepository(context)) as T
        }
    }
}
