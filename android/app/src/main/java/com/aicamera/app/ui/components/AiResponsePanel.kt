package com.aicamera.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicamera.app.data.model.AiResponse
import com.aicamera.app.data.model.AiResponseStatus

@Composable
fun AiResponsePanel(
    response: AiResponse?,
    providerName: String,
    providerColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = providerColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = providerName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = providerColor,
                    modifier = Modifier.weight(1f),
                )

                // Status badge
                StatusBadge(status = response?.status ?: AiResponseStatus.WAITING)

                // Copy button (only when there's text)
                if (!response?.responseText.isNullOrEmpty()) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("AI Response", response!!.responseText)
                            )
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy response",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Response body
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 300.dp)
                    .verticalScroll(scrollState),
            ) {
                when {
                    response?.status == AiResponseStatus.ERROR -> {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp).padding(top = 2.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = response.error ?: "An error occurred",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    !response?.responseText.isNullOrEmpty() -> {
                        Text(
                            text = response!!.responseText,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Default,
                            lineHeight = 20.sp,
                        )
                    }

                    response?.status == AiResponseStatus.ANALYSING -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = providerColor,
                            )
                            Text("Analysing images...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    else -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Waiting",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: AiResponseStatus) {
    val (label, color) = when (status) {
        AiResponseStatus.WAITING -> "Waiting" to Color(0xFF9E9E9E)
        AiResponseStatus.ANALYSING -> "Analysing" to Color(0xFF2196F3)
        AiResponseStatus.COMPLETED -> "Completed" to Color(0xFF4CAF50)
        AiResponseStatus.ERROR -> "Error" to Color(0xFFF44336)
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontWeight = FontWeight.Medium,
        )
    }
}
