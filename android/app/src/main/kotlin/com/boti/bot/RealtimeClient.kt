package com.boti.bot

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

object RealtimeClient {
    private const val WS_URL = "wss://zhezpmyrqpzmlmbzxdjx.supabase.co/realtime/v1/websocket"
    private const val API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpoZXpwbXlycXB6bWxtYnp4ZGp4Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODEwMjA5MTEsImV4cCI6MjA5NjU5NjkxMX0.IRmeITfzCUxrxud-ekLFj1Ij7QNEmsL6n4CXx3Kx4uc"

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refSeq = AtomicInteger(0)

    private var ws: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var currentDeviceId: String? = null

    var onNewCommand: (() -> Unit)? = null

    fun connect(deviceId: String) {
        currentDeviceId = deviceId
        val req = Request.Builder()
            .url("$WS_URL?apikey=$API_KEY&vsn=1.0.0")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendJoin(webSocket, deviceId)
                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(25_000)
                        sendHeartbeat(webSocket)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if (msg.optString("event") == "postgres_changes") {
                        val data = msg.optJSONObject("payload")?.optJSONObject("data")
                        if (data?.optString("type") == "INSERT") {
                            onNewCommand?.invoke()
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                heartbeatJob?.cancel()
                scope.launch {
                    delay(5_000)
                    currentDeviceId?.let { connect(it) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                heartbeatJob?.cancel()
                if (code != 1000) {
                    scope.launch {
                        delay(3_000)
                        currentDeviceId?.let { connect(it) }
                    }
                }
            }
        })
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        ws?.close(1000, null)
        ws = null
        currentDeviceId = null
    }

    private fun sendJoin(socket: WebSocket, deviceId: String) {
        val msg = JSONObject().apply {
            put("event", "phx_join")
            put("topic", "realtime:bot-$deviceId")
            put("ref", refSeq.incrementAndGet().toString())
            put("payload", JSONObject().apply {
                put("access_token", API_KEY)
                put("config", JSONObject().apply {
                    put("postgres_changes", JSONArray().put(JSONObject().apply {
                        put("event", "INSERT")
                        put("schema", "public")
                        put("table", "commands")
                        put("filter", "device_id=eq.$deviceId")
                    }))
                })
            })
        }
        socket.send(msg.toString())
    }

    private fun sendHeartbeat(socket: WebSocket) {
        val msg = JSONObject().apply {
            put("event", "heartbeat")
            put("topic", "phoenix")
            put("payload", JSONObject())
            put("ref", refSeq.incrementAndGet().toString())
        }
        socket.send(msg.toString())
    }
}
