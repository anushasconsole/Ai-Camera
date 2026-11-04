package com.aicamera.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicamera.app.viewmodel.AuthViewModel
import com.aicamera.app.viewmodel.CameraViewModel
import com.google.firebase.auth.FirebaseAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    cameraViewModel: CameraViewModel,
    onNavigateBack: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val uiState by cameraViewModel.uiState.collectAsState()
    var prompt by remember { mutableStateOf(cameraViewModel.userPrompt) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
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
            // ── Account ──────────────────────────────────────────────────
            SettingsSectionHeader("Account")
            val user = FirebaseAuth.getInstance().currentUser
            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = { Text(user?.displayName ?: user?.email ?: "Signed In") },
                    supportingContent = { Text(user?.email ?: "", fontSize = 12.sp) },
                    leadingContent = {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(40.dp))
                    },
                    trailingContent = {
                        TextButton(onClick = { showSignOutDialog = true }) {
                            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Sign Out")
                        }
                    },
                )
            }

            // ── Session ───────────────────────────────────────────────────
            SettingsSectionHeader("Session")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsInfoRow("Session ID", uiState.sessionId.take(16) + if (uiState.sessionId.length > 16) "…" else "")
                    SettingsInfoRow("Device ID", uiState.sessionId.take(8))
                    SettingsInfoRow("Status", if (uiState.wsConnected) "🟢 Connected" else "🔴 Disconnected")
                    SettingsInfoRow("Photos in batch", uiState.photoCount.toString())
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear Current Batch")
                    }
                }
            }

            // ── AI Prompt ─────────────────────────────────────────────────
            SettingsSectionHeader("AI Analysis Prompt")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This prompt is sent with every photo batch to both AI providers.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("Prompt") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                    )
                    Button(
                        onClick = {
                            cameraViewModel.userPrompt = prompt
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Save Prompt")
                    }
                }
            }

            // ── Model configuration ───────────────────────────────────────
            SettingsSectionHeader("Model Configuration")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Model IDs are set server-side via the GEMINI_MODEL and GROQ_MODEL environment variables.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "To change models, update your backend .env file and restart the server. No app changes needed.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── About ─────────────────────────────────────────────────────
            SettingsSectionHeader("About")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsInfoRow("Version", "1.0.0")
                    SettingsInfoRow("AI Providers", "Google Gemini + Groq")
                    SettingsInfoRow("Sync", "Firebase Firestore + WebSocket")
                }
            }
        }
    }

    // Sign out dialog
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign Out") },
            text = { Text("Are you sure you want to sign out?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        authViewModel.signOut()
                        onSignedOut()
                    }
                ) { Text("Sign Out") }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Clear batch dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Batch") },
            text = { Text("This will start a new empty batch. Previous results are preserved in session history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        cameraViewModel.startNewBatch()
                    }
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
