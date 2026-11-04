package com.aicamera.app.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "AuthViewModel"

data class AuthUiState(
    val user: FirebaseUser? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(private val context: Context) : ViewModel() {

    private val auth: FirebaseAuth = Firebase.auth

    private val _uiState = MutableStateFlow(
        AuthUiState(user = auth.currentUser)
    )
    val uiState: StateFlow<AuthUiState> = _uiState

    val isSignedIn: Boolean get() = auth.currentUser != null

    // Google Sign-In client
    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken(context.getString(com.aicamera.app.R.string.default_web_client_id))
        .requestEmail()
        .build()

    val googleSignInClient: GoogleSignInClient by lazy {
        GoogleSignIn.getClient(context, gso)
    }

    fun getGoogleSignInIntent(): Intent = googleSignInClient.signInIntent

    fun handleGoogleSignInResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.result
                val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                val result = auth.signInWithCredential(credential).await()
                _uiState.update { it.copy(user = result.user, isLoading = false) }
                Log.d(TAG, "Google sign-in success: ${result.user?.email}")
            } catch (e: Exception) {
                Log.e(TAG, "Google sign-in failed: ${e.message}")
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val result = auth.signInWithEmailAndPassword(email, password).await()
                _uiState.update { it.copy(user = result.user, isLoading = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Email sign-in failed: ${e.message}")
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun createAccountWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val result = auth.createUserWithEmailAndPassword(email, password).await()
                _uiState.update { it.copy(user = result.user, isLoading = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Account creation failed: ${e.message}")
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun signOut() {
        auth.signOut()
        googleSignInClient.signOut()
        _uiState.update { it.copy(user = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AuthViewModel(context) as T
        }
    }
}
