package com.gatherin.data

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

/**
 * Socket.IO connection for the live organizer dashboard.
 * Mirrors the web client exactly:
 *  - authenticates with the access token via the handshake `auth` payload,
 *  - joins the `event:<id>` room through the `join:event` event,
 *  - listens to `checkin:new` and `event:stats_update` broadcasts.
 */
class EventSocketManager(
    private val eventId: String,
    private val onCheckin: (registrationId: String, checkedInAt: String, checkedInCount: Int?, spotsRemaining: Int?) -> Unit,
    private val onStats: (registeredCount: Int?, spotsRemaining: Int?) -> Unit,
    private val onConnectionChange: (connected: Boolean) -> Unit
) {

    private var socket: Socket? = null

    fun connect() {
        if (socket != null) return

        val options = IO.Options().apply {
            // Backend middleware: io.use((socket) => jwt.verify(socket.handshake.auth.token))
            auth = mapOf("token" to (SessionManager.accessToken ?: ""))
            reconnection = true
        }

        socket = IO.socket(RetrofitClient.SOCKET_BASE_URL, options).also { s ->

            s.on(Socket.EVENT_CONNECT) {
                onConnectionChange(true)
                // Organizer-only room join; server verifies ownership.
                s.emit("join:event", JSONObject().put("event_id", eventId))
            }
            s.on(Socket.EVENT_DISCONNECT) { onConnectionChange(false) }
            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.w("EventSocket", "connect_error: ${args.firstOrNull()}")
                onConnectionChange(false)
            }

            s.on("checkin:new") { args ->
                val payload = args.firstOrNull() as? JSONObject ?: return@on
                onCheckin(
                    payload.optString("registration_id"),
                    payload.optString("checked_in_at"),
                    if (payload.has("checked_in_count")) payload.optInt("checked_in_count") else null,
                    if (payload.has("spots_remaining")) payload.optInt("spots_remaining") else null
                )
            }

            s.on("event:stats_update") { args ->
                val payload = args.firstOrNull() as? JSONObject ?: return@on
                onStats(
                    if (payload.has("registered_count")) payload.optInt("registered_count") else null,
                    if (payload.has("spots_remaining")) payload.optInt("spots_remaining") else null
                )
            }

            s.connect()
        }
    }

    fun disconnect() {
        socket?.let { s ->
            s.emit("leave:event", JSONObject().put("event_id", eventId))
            s.off()
            s.disconnect()
        }
        socket = null
    }
}