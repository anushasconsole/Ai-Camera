package com.aicamera.app.ui.screens

import android.Manifest
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicamera.app.data.model.AiResponseStatus
import com.aicamera.app.data.model.CameraState
import com.aicamera.app.ui.components.AiResponsePanel
import com.aicamera.app.ui.components.CameraPreviewComposable
import com.aicamera.app.ui.components.PhotoThumbnailRow
import com.aicamera.app.viewmodel.CameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    viewModel: CameraViewModel,
    previewView: PreviewView,
    onNavigateToSettings: () -> Unit,
    onNavigateToResults: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val localPhotos by viewModel.localPhotos.collectAsState()
    val remotePhotos by viewModel.remotePhotos.collectAsState()
    val aiResponses by viewModel.aiResponses.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera permission
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    // Toast
    uiState.toastMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Camera", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            text = if (uiState.sessionId.isNotEmpty())
                                "Session: ${uiState.sessionId.take(8)}…"
                            else "Connecting…",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    // Connection indicator
                    Icon(
                        imageVector = if (uiState.wsConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = if (uiState.wsConnected) "Connected" else "Disconnected",
                        tint = if (uiState.wsConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = {
            uiState.toastMessage?.let { msg ->
                Snackbar(modifier = Modifier.padding(8.dp)) { Text(msg) }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Camera preview ───────────────────────────────────────────
            CameraPreviewComposable(
                previewView = previewView,
                isCameraOpen = uiState.cameraState != CameraState.IDLE,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            )

            // ── Permission notice ────────────────────────────────────────
            if (!cameraPermission.status.isGranted) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Camera permission required", fontWeight = FontWeight.SemiBold)
                            Text("Grant permission to use Volume Up to capture photos", fontSize = 12.sp)
                        }
                        Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                            Text("Grant")
                        }
                    }
                }
            }

            // ── Photo count + status ─────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Photos: ${uiState.photoCount}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.isUploading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text("Uploading…", fontSize = 12.sp)
                        }
                    }
                    if (uiState.isSubmitting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text("Submitting…", fontSize = 12.sp)
                        }
                    }
                }
            }

            // ── Thumbnail strip ──────────────────────────────────────────
            PhotoThumbnailRow(
                localPhotos = localPhotos,
                remotePhotos = remotePhotos,
                onDeletePhoto = { photoId -> viewModel.deletePhoto(photoId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )

            // ── Instructions ─────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    InstructionRow(
                        key = "Vol ▲ (1st)",
                        value = when (uiState.cameraState) {
                            CameraState.IDLE -> "Open rear camera"
                            else -> "✓ Camera open"
                        },
                    )
                    InstructionRow(key = "Vol ▲ (2nd+)", value = "Capture photo")
                    InstructionRow(key = "Vol ▼", value = "Send to Gemini + Groq")
                }
            }

            // ── New batch button ─────────────────────────────────────────
            if (aiResponses.values.any { it.status == AiResponseStatus.COMPLETED || it.status == AiResponseStatus.ERROR }) {
                OutlinedButton(
                    onClick = { viewModel.startNewBatch() },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(8.dp),
                ) {
                    Text("Start New Batch")
                }
            }

            // ── AI response panels ────────────────────────────────────────
            if (aiResponses.isNotEmpty()) {
                Text(
                    "Analysis Results",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AiResponsePanel(
                        response = aiResponses["Google Gemini"],
                        providerName = "Gemini",
                        providerColor = Color(0xFF4285F4),
                        modifier = Modifier.weight(1f),
                    )
                    AiResponsePanel(
                        response = aiResponses["Groq Qwen Vision"],
                        providerName = "Groq / Qwen",
                        providerColor = Color(0xFFF66B0E),
                        modifier = Modifier.weight(1f),
                    )
                }

                if (aiResponses.values.any { it.status == AiResponseStatus.COMPLETED }) {
                    TextButton(
                        onClick = onNavigateToResults,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("View Full Results →")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InstructionRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        Text(
            value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
