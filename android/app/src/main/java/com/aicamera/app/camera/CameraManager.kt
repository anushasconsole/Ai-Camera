package com.aicamera.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraManager"

/**
 * Wraps CameraX lifecycle management.
 * - bindCamera()   : opens rear camera preview + ImageCapture use case
 * - capturePhoto() : captures a JPEG and returns its byte array
 * - release()      : unbinds all use cases
 */
class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var isBound = false

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    /**
     * Bind the rear camera to [lifecycleOwner], attaching a preview to [previewView].
     * Safe to call multiple times – if already bound, this is a no-op.
     */
    suspend fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
    ) {
        if (isBound) return

        val provider = getCameraProvider()

        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .build()

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            imageCapture = capture
            isBound = true
            Log.d(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed: ${e.message}")
            throw e
        }
    }

    /**
     * Capture a photograph.
     * Returns the JPEG bytes on success, or throws on failure.
     * Saves to a temp file in the app cache, loads bytes, then deletes the file.
     */
    suspend fun capturePhoto(): ByteArray = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
            ?: run {
                cont.resumeWithException(IllegalStateException("Camera not bound"))
                return@suspendCancellableCoroutine
            }

        val outputFile = File(
            context.cacheDir,
            "capture_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        capture.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val bytes = outputFile.readBytes()
                        outputFile.delete()
                        Log.d(TAG, "Photo captured: ${bytes.size} bytes")
                        cont.resume(bytes)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    outputFile.delete()
                    Log.e(TAG, "Capture error: ${exception.message}")
                    cont.resumeWithException(exception)
                }
            }
        )
    }

    /** Generate a thumbnail Bitmap from raw JPEG bytes. */
    fun generateThumbnail(jpegBytes: ByteArray, maxDimension: Int = 200): Bitmap {
        val original = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        val scale = maxDimension.toFloat() / maxOf(original.width, original.height)
        return if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt(),
                (original.height * scale).toInt(),
                true
            )
        } else {
            original
        }
    }

    fun release() {
        cameraProvider?.unbindAll()
        isBound = false
        imageCapture = null
        Log.d(TAG, "Camera released")
    }

    val isOpen: Boolean get() = isBound

    // ── private helpers ────────────────────────────────────────────────────

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            ProcessCameraProvider.getInstance(context).also { future ->
                future.addListener(
                    {
                        try {
                            val provider = future.get()
                            cameraProvider = provider
                            cont.resume(provider)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        }
                    },
                    mainExecutor
                )
            }
        }
}
