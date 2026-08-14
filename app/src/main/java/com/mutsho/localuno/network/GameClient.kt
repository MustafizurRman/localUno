package com.mutsho.localuno.network

import android.util.Log
import com.mutsho.localuno.model.NetworkMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

@OptIn(ExperimentalCoroutinesApi::class)
class GameClient {

    companion object {
        private const val TAG = "GameClient"
    }

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // send() launches a new coroutine per call on a multi-threaded dispatcher - without this,
    // two calls close together (a rapid double-tap, an emoji sent right after a card play) could
    // interleave their writes to the same PrintWriter at the byte level, corrupting both JSON
    // lines. GameServer already guards its writes the same way per-connection; this was the one
    // write path that didn't.
    private val writeMutex = Mutex()

    // Beyond byte-level interleaving, two send() calls launched close together on the (multi-
    // threaded) IO dispatcher have no guarantee of reaching the socket in call order - whichever
    // coroutine gets scheduled first wins the writeMutex first. A single-threaded send dispatcher
    // serializes writes in call order without touching the read loop's dispatcher above.
    private val sendScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    private val _messages = MutableSharedFlow<NetworkMessage>(replay = 1, extraBufferCapacity = 64)
    val messages: SharedFlow<NetworkMessage> = _messages

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _disconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnected: SharedFlow<Unit> = _disconnected

    // Timestamp of the last successfully received line - a real message OR a heartbeat Ping,
    // whichever arrives more recently. This is the connection-quality signal: a UI polling
    // `System.currentTimeMillis() - lastMessageAt.value` and bucketing the result against
    // Heartbeat.PING_INTERVAL_MS/READ_TIMEOUT_MS gets a live "good/poor" read with no new wire
    // protocol - the heartbeat that already exists to catch a dead connection is exactly the
    // signal that tells you how healthy a LIVE one currently looks.
    private val _lastMessageAt = MutableStateFlow(System.currentTimeMillis())
    val lastMessageAt: StateFlow<Long> = _lastMessageAt

    // Set true only inside disconnect() itself, before it touches the socket. Closing our own
    // socket to tear down cleanly (leaving the lobby, a rejected join) unblocks the very same
    // readLine() that a genuinely dead connection would - both throw the same way - so without
    // this the read loop's finally block below can't tell "we hung up" from "the peer vanished"
    // and would emit _disconnected for both, double-firing whatever the caller wired that flow to.
    // @Volatile: disconnect() runs on the caller's thread, the read loop on its own IO thread: the
    // flag has to be visible across that boundary the instant it's set, not whenever the JIT/CPU
    // cache happens to flush it.
    @Volatile
    private var intentionalDisconnect = false

    /**
     * @param connectTimeoutMs 0 (default) waits as long as the platform's own connect timeout
     *   allows - fine for the ordinary join flow, where the user is actively watching and a slow
     *   connect just means a slow spinner. A reconnect attempt after a drop instead needs to fail
     *   FAST so a bounded retry loop (see LobbyViewModel.attemptReconnect) can try again rather
     *   than sit on one hung attempt for however long the platform default takes.
     */
    fun connect(host: String, port: Int, connectTimeoutMs: Int = 0): Boolean {
        return try {
            intentionalDisconnect = false
            socket = Socket()
            socket!!.connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
            // Without this, readLine() below blocks forever waiting for data from a peer that will
            // never send any again - the ordinary way a phone disconnects (out of Wi-Fi range, OS
            // kills the background process, airplane mode) doesn't send a clean TCP close, so this
            // socket would otherwise wait on the OS's own dead-peer detection, which can take many
            // minutes or never happen inside a round's lifetime. See Heartbeat's doc for the pairing
            // with the ping below, which is what keeps this timeout from false-firing on a
            // perfectly healthy but quiet connection.
            socket!!.soTimeout = Heartbeat.READ_TIMEOUT_MS
            writer = PrintWriter(socket!!.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            _connected.value = true

            // Keeps the host's read timeout from tripping during a long quiet stretch - a slow
            // human's turn, sitting in the lobby. Cancelled automatically with the rest of `scope`
            // wherever this connection ends, cleanly or not.
            scope.launch {
                while (isActive) {
                    delay(Heartbeat.PING_INTERVAL_MS)
                    send(NetworkMessage.Ping())
                }
            }

            // Start reading messages
            scope.launch {
                try {
                    while (isActive && socket?.isClosed == false) {
                        val line = reader?.readLine() ?: break
                        _lastMessageAt.value = System.currentTimeMillis()
                        val message = MessageSerializer.deserialize(line)
                        if (message != null) {
                            _messages.emit(message)
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // No data - not even a heartbeat - within READ_TIMEOUT_MS. The peer is gone.
                    Log.i(TAG, "Connection timed out - no heartbeat from host")
                } catch (e: java.net.SocketException) {
                    // Expected when host closes the game ? not an error
                    Log.i(TAG, "Socket closed: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Read error", e)
                } finally {
                    _connected.value = false
                    if (!intentionalDisconnect) {
                        _disconnected.emit(Unit)
                    }
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            false
        }
    }

    fun send(message: NetworkMessage) {
        sendScope.launch {
            try {
                val serialized = MessageSerializer.serialize(message)
                writeMutex.withLock {
                    writer?.print(serialized)
                    writer?.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
            }
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        scope.cancel()
        sendScope.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        reader = null
        _connected.value = false
    }
}
