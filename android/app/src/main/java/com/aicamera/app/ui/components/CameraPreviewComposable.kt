package com.aicamera.app.ui.components

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Composable wrapper for CameraX [PreviewView].
 * Shows a placeholder when the camera is not yet open.
 */
@Composable
fun CameraPreviewComposable(
    previewView: PreviewView,
    isCameraOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (isCameraOpen) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A2E)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Press Volume Up to open camera",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
