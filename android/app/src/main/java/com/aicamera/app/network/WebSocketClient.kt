package com.aicamera.app.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WebSocketClient"
private const val RECONNECT_DELAY_MS = 3_000L
private const val MAX_RECONNECT_ATTEMPTS = 10
private const val PING_INTERVAL_MS = 25_000L

/**
 * Persistent WebSocket client with automatic reconnection.
 * Emits raw JSON strings via [messages] flow.
 */
class WebSocketClient(
    private val sessionId: String,
    private val firebaseToken: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
) {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages

    private val _connected = MutableSharedFlow<Boolean>(replay = 1)
    val connected: SharedFlow<Boolean> = _connected

    private var ws: WebSocket? = null
    private val shouldRun = AtomicBoolean(false)
    private var reconnectAttempts = 0

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    fun connect() {
        shouldRun.set(true)
        scope.launch { connectLoop() }
    }

    fun disconnect() {
        shouldRun.set(false)
        ws?.close(1000, "Client disconnecting")
        ws = null
    }

    private suspend fun connectLoop() {
        while (shouldRun.get() && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            val url = "${RetrofitClient.wsBaseUrl}/ws/$sessionId"
            Log.d(TAG, "Connecting to $url (attempt ${reconnectAttempts + 1})")

            val request = Request.Builder().url(url).build()
            var opened = false

            ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    reconnectAttempts = 0
                    Log.d(TAG, "WS opened")
                    // Send auth frame immediately after opening
                    val authFrame = JSONObject().apply {
                        put("type", "auth")
                        put("token", firebaseToken)
                    }
                    webSocket.send(authFrame.toString())
                    scope.launch { _connected.emit(true) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch { _messages.emit(text) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closing: $code $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WS closed: $code $reason")
                    scope.launch { _connected.emit(false) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "WS failure: ${t.message}")
                    scope.launch { _connected.emit(false) }
                }
            })

            // Wait for disconnect before attempting to reconnect
            delay(RECONNECT_DELAY_MS + reconnectAttempts * 1000L)

            if (shouldRun.get() && !opened) {
                reconnectAttempts++
                Log.d(TAG, "Reconnect attempt $reconnectAttempts")
            } else if (!shouldRun.get()) {
                break
            }
        }
    }
}
