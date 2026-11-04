package com.aicamera.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aicamera.app.data.model.AiResponse
import com.aicamera.app.data.model.AiResponseStatus
import com.aicamera.app.data.model.Photo
import com.aicamera.app.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    viewModel: CameraViewModel,
    onNavigateBack: () -> Unit,
) {
    val aiResponses by viewModel.aiResponses.collectAsState()
    val remotePhotos by viewModel.remotePhotos.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analysis Results", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Photos row
            if (remotePhotos.isNotEmpty()) {
                Text("Analysed Photos (${remotePhotos.size})", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    remotePhotos.take(6).forEach { photo ->
                        AsyncImage(
                            model = photo.downloadUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .weight(1f, fill = false),
                        )
                    }
                    if (remotePhotos.size > 6) {
                        Box(
                            modifier = Modifier.size(72.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("+${remotePhotos.size - 6} more", fontSize = 11.sp)
                        }
                    }
                }
                HorizontalDivider()
            }

            // Gemini panel
            FullResponsePanel(
                title = "Google Gemini",
                titleColor = Color(0xFF4285F4),
                response = aiResponses["Google Gemini"],
            )

            // Groq panel
            FullResponsePanel(
                title = "Groq / Qwen Vision",
                titleColor = Color(0xFFF66B0E),
                response = aiResponses["Groq Qwen Vision"],
            )
        }
    }
}

@Composable
private fun FullResponsePanel(
    title: String,
    titleColor: Color,
    response: AiResponse?,
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = titleColor,
                    modifier = Modifier.weight(1f),
                )

                // Status chip
                val (statusLabel, statusColor) = when (response?.status) {
                    AiResponseStatus.WAITING -> "Waiting" to Color(0xFF9E9E9E)
                    AiResponseStatus.ANALYSING -> "Analysing" to Color(0xFF2196F3)
                    AiResponseStatus.COMPLETED -> "Completed" to Color(0xFF4CAF50)
                    AiResponseStatus.ERROR -> "Error" to Color(0xFFF44336)
                    null -> "Waiting" to Color(0xFF9E9E9E)
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Copy button
                if (!response?.responseText.isNullOrEmpty()) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("$title Response", response!!.responseText)
                            )
                        },
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy $title response",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (response?.model?.isNotEmpty() == true) {
                Text(
                    text = "Model: ${response.model}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            when {
                response?.status == AiResponseStatus.ERROR -> {
                    Text(
                        text = "Error: ${response.error ?: "Unknown error"}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                    )
                }

                response?.status == AiResponseStatus.ANALYSING && response.responseText.isEmpty() -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Analysing images…", fontSize = 13.sp)
                    }
                }

                !response?.responseText.isNullOrEmpty() -> {
                    Text(
                        text = response!!.responseText,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                    )
                }

                else -> {
                    Text(
                        text = "Waiting for submission…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
