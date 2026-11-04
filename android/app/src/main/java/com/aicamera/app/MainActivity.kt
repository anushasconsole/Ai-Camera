package com.aicamera.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aicamera.app.ui.screens.*
import com.aicamera.app.ui.theme.AICameraTheme
import com.aicamera.app.viewmodel.AuthViewModel
import com.aicamera.app.viewmodel.CameraViewModel

private const val TAG = "MainActivity"

// ── Navigation routes ─────────────────────────────────────────────────────────
private object Routes {
    const val LOGIN = "login"
    const val WORKSPACE = "workspace"
    const val RESULTS = "results"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    // ViewModels
    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(applicationContext)
    }
    private val cameraViewModel: CameraViewModel by viewModels {
        CameraViewModel.Factory(applicationContext)
    }

    // CameraX PreviewView – created once, passed into Compose
    private val previewView: PreviewView by lazy {
        PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Navigation controller reference (set via Compose callback)
    private var navController: NavHostController? = null

    // ── Camera permission launcher ─────────────────────────────────────────
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Camera permission granted=$granted")
    }

    // ── Activity lifecycle ─────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pre-request camera permission
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            AICameraTheme {
                val navController = rememberNavController().also {
                    this@MainActivity.navController = it
                }

                val authState by authViewModel.uiState.collectAsState()
                val startDestination = if (authState.user != null) Routes.WORKSPACE else Routes.LOGIN

                NavHost(navController = navController, startDestination = startDestination) {

                    composable(Routes.LOGIN) {
                        LoginScreen(
                            viewModel = authViewModel,
                            onSignedIn = {
                                navController.navigate(Routes.WORKSPACE) {
                                    popUpTo(Routes.LOGIN) { inclusive = true }
                                }
                            },
                        )
                    }

                    composable(Routes.WORKSPACE) {
                        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                        WorkspaceScreen(
                            viewModel = cameraViewModel,
                            previewView = previewView,
                            onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                            onNavigateToResults = { navController.navigate(Routes.RESULTS) },
                        )
                    }

                    composable(Routes.RESULTS) {
                        ResultsScreen(
                            viewModel = cameraViewModel,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            authViewModel = authViewModel,
                            cameraViewModel = cameraViewModel,
                            onNavigateBack = { navController.popBackStack() },
                            onSignedOut = {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  VOLUME BUTTON INTERCEPTION
    //
    //  dispatchKeyEvent is called for EVERY key event before the view
    //  hierarchy sees it.  We consume volume events here so the system
    //  volume UI never appears while our app is in the foreground.
    //
    //  We only act on ACTION_DOWN to avoid double-firing (DOWN + UP).
    //  Long-press is suppressed by the debounce in CameraViewModel.
    // ══════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Only intercept when our app is the foreground activity
        if (!isTaskRoot && !isFinishing) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    handleVolumeUp()
                }
                true  // consume the event – system volume UI is suppressed
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    handleVolumeDown()
                }
                true  // consume
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun handleVolumeUp() {
        Log.d(TAG, "Volume UP pressed")
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        // Pass the current lifecycle owner and previewView to the ViewModel
        cameraViewModel.onVolumeUp(this, previewView)
    }

    private fun handleVolumeDown() {
        Log.d(TAG, "Volume DOWN pressed")
        cameraViewModel.onVolumeDown()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
}
