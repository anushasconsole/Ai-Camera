package com.aicamera.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aicamera.app.data.model.Photo
import com.aicamera.app.viewmodel.CapturedPhotoUi

@Composable
fun PhotoThumbnailRow(
    localPhotos: List<CapturedPhotoUi>,
    remotePhotos: List<Photo>,
    onDeletePhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Merge: local photos take priority; remote photos from other devices are shown by download URL
    val allPhotoIds = localPhotos.map { it.photoId }.toSet()
    val otherDevicePhotos = remotePhotos.filter { it.photoId !in allPhotoIds && it.downloadUrl.isNotEmpty() }

    if (localPhotos.isEmpty() && otherDevicePhotos.isEmpty()) return

    LazyRow(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        // Photos from this device (may have local bitmap)
        items(localPhotos, key = { it.photoId }) { photo ->
            PhotoThumbnailItem(
                photoId = photo.photoId,
                bitmap = photo.thumbnail,
                downloadUrl = photo.downloadUrl.ifEmpty { null },
                isLocalDevice = true,
                onDelete = onDeletePhoto,
            )
        }

        // Photos from other devices (remote URL only)
        items(otherDevicePhotos, key = { it.photoId }) { photo ->
            PhotoThumbnailItem(
                photoId = photo.photoId,
                bitmap = null,
                downloadUrl = photo.downloadUrl,
                isLocalDevice = false,
                onDelete = onDeletePhoto,
            )
        }
    }
}

@Composable
private fun PhotoThumbnailItem(
    photoId: String,
    bitmap: android.graphics.Bitmap?,
    downloadUrl: String?,
    isLocalDevice: Boolean,
    onDelete: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isLocalDevice) 2.dp else 1.dp,
                color = if (isLocalDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp),
            ),
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Captured photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            downloadUrl != null -> {
                AsyncImage(
                    model = downloadUrl,
                    contentDescription = "Synced photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }

        // Delete button
        IconButton(
            onClick = { onDelete(photoId) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete photo",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }

        // Device indicator
        if (!isLocalDevice) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                Text("📱", fontSize = 8.sp)
            }
        }
    }
}
