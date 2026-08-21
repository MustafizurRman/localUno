package com.mutsho.localuno.network

import android.util.Log
import com.mutsho.localuno.model.NetworkMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class GameServer {

    companion object {
        private const val TAG = "GameServer"

        /**
         * Sockets accepted at once, beyond which new ones are closed immediately.
         *
         * The accept loop used to take everything offered, and each connection costs a coroutine, a
         * reader, a writer and a ping job - none of which requires ever joining successfully. So a
         * peer that never sends a valid message could still flood the table off the air.
         *
         * Set generously against the seat count: reconnects briefly hold two sockets for one
         * player, and a rejected joiner's socket lingers until it is closed.
         */
        private const val CONNECTION_HEADROOM = 6
    }

    /**
     * Seats at this table, used with [CONNECTION_HEADROOM] to cap concurrent sockets. Set by the
     * host when the lobby is created; the default is the largest table the app offers, so a caller
     * that forgets is merely un-tightened rather than broken.
     */
    @Volatile
    var maxPlayers: Int = 8

    private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap<String, ClientConnection>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // sendToClient/broadcastToAll/broadcastExcept each used to do `scope.launch { ... }` on the
    // (multi-threaded) IO dispatcher. Each call's write was individually safe (a per-connection
    // mutex stopped byte-level interleaving), but two calls made back-to-back from the ViewModel -
    // e.g. a TurnTimedOut broadcast immediately followed by the resulting StateUpdate broadcast -
    // had no guarantee of landing on the wire in call order, since separate launches can be
    // scheduled onto different pool threads in either order. The client applies incoming
    // StateUpdates unconditionally (no sequence-number guard), so a reordered pair could roll its
    // game state back to a stale snapshot.
    //
    // sendScope decides ORDER ONLY, and does so fast - it never performs the actual blocking
    // socket write itself any more. Each send*() function below hands the write off to the
    // recipient ClientConnection's own writeDispatcher (see its doc) as its very next step; the
    // launch onto sendScope's single thread just records "this call happened before that one" and
    // returns immediately. Writing the socket directly on sendScope, as this used to do, meant one
    // slow or half-dead client - exactly the kind of connection the read-side timeout above exists
    // to eventually catch, and Sockets have no equivalent WRITE timeout - could block delivery to
    // every other player until that write finally errored out, which for a truly stuck TCP send
    // buffer can take a very long time.
    private val sendScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    private val _messages = MutableSharedFlow<Pair<String, NetworkMessage>>(extraBufferCapacity = 64)
    val messages: SharedFlow<Pair<String, NetworkMessage>> = _messages

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents

    val port: Int get() = serverSocket?.localPort ?: 0

    sealed class ConnectionEvent {
        data class Connected(val clientId: String) : ConnectionEvent()
        data class Disconnected(val clientId: String) : ConnectionEvent()
    }

    data class ClientConnection(
        val socket: Socket,
        val writer: PrintWriter,
        val reader: BufferedReader,
        var playerId: String = "",
        // Each connection gets its own single-threaded view of the shared IO pool for its writes -
        // see broadcastToAll's comment for why. limitedParallelism doesn't allocate a dedicated
        // thread, just a serialized view over the shared pool, so one of these per connected player
        // costs nothing.
        val writeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    )

    fun start(): Int {
        serverSocket = ServerSocket(0) // OS assigns available port
        Log.d(TAG, "Server started on port ${serverSocket!!.localPort}")

        scope.launch {
            while (isActive) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    // Refused at the door, before any coroutine or buffer is spent on it.
                    if (clients.size >= maxPlayers + CONNECTION_HEADROOM) {
                        Log.w(TAG, "Refusing connection - ${clients.size} already open")
                        try { clientSocket.close() } catch (_: Exception) {}
                        continue
                    }
                    handleNewClient(clientSocket)
                } catch (e: java.net.SocketException) {
                    break  // server socket was closed intentionally
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error accepting client", e)
                    }
                    // continue accepting on other transient errors
                }
            }
        }

        return serverSocket!!.localPort
    }

    private fun handleNewClient(socket: Socket) {
        scope.launch {
            val clientId = socket.remoteSocketAddress.toString()
            var finalPlayerId = clientId  // Track resolved UUID; starts as socket address
            // Hoisted so the finally block can tell whether the map still points at OUR connection.
            var connection: ClientConnection? = null
            // Hoisted so the finally block can stop it - it's launched on the shared `scope`, not
            // scoped to this coroutine alone, so nothing else would end it once this one exits.
            var pingJob: Job? = null
            try {
                // Without this, a client that vanishes without a clean TCP close - out of Wi-Fi
                // range, OS kills the app, airplane mode; the ordinary way a disconnect actually
                // happens, not the exception - leaves readLine() below blocked forever waiting for
                // data that's never coming. ConnectionEvent.Disconnected, and everything the engine
                // does with it (the 30s pause, the eventual knockout), depends entirely on this
                // socket actually noticing. See Heartbeat's doc for the full reasoning.
                socket.soTimeout = Heartbeat.READ_TIMEOUT_MS
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                connection = ClientConnection(socket, writer, reader, clientId)
                clients[clientId] = connection

                _connectionEvents.emit(ConnectionEvent.Connected(clientId))

                // Keeps this client's own read timeout from tripping during a long quiet stretch
                // on their end - their turn taking a while, sitting in the lobby. Reads
                // connection.playerId fresh each tick since JoinRequest rekeys it below.
                pingJob = scope.launch {
                    while (isActive && !socket.isClosed) {
                        delay(Heartbeat.PING_INTERVAL_MS)
                        sendToClient(connection.playerId, NetworkMessage.Ping())
                    }
                }

                // Read messages from client
                while (isActive && !socket.isClosed) {
                    val line = reader.readBoundedLine() ?: break
                    val message = MessageSerializer.deserialize(line)
                    if (message != null) {
                        // A JoinRequest no longer rebinds anything here.
                        //
                        // It used to, unconditionally and before a single check had run: the
                        // connection took whatever playerId the payload claimed and replaced that
                        // key in `clients`. Since private hands are addressed through this map -
                        // broadcastStateToAll sends each player their own cards by id - anyone who
                        // could reach the port could claim a seated player's id and start receiving
                        // that player's hand, while the real player went dark and was eventually
                        // declared disconnected. The claimed id was public too: every LobbyUpdate
                        // carries a PlayerInfo, with its id, for every seat.
                        //
                        // The identity is now a claim carried in the payload, and only the
                        // application can turn it into an identity - see bindPlayerId, called after
                        // the PIN has been checked and the join accepted.
                        _messages.emit(Pair(connection.playerId, message))
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // No data - not even a heartbeat - within READ_TIMEOUT_MS. The peer is gone.
                Log.i(TAG, "Connection timed out - no heartbeat from $clientId")
            } catch (e: LineTooLongException) {
                // Framing is already lost, so there is nothing to resynchronise to. Dropping the
                // connection is the whole defence: the alternative is letting one peer decide how
                // much memory the host allocates.
                Log.w(TAG, "Dropping $clientId - ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Client error: $clientId", e)
            } finally {
                pingJob?.cancel()
                // Value-conditional removal, NOT remove(key). If this player already reconnected on
                // a fresh socket, the map now points at that newer connection - a key-only remove
                // here would evict the LIVE connection (making the host unable to reach a player who
                // is actually online) and fire a bogus Disconnected that knocks them out mid-game.
                val conn = connection
                if (conn != null) {
                    // Read from the connection rather than a local copy: bindPlayerId is what
                    // moves an identity now, and it updates the connection. A stale local would
                    // leave the map holding a dead entry under the player's id - which the next
                    // reconnect would then be refused for colliding with.
                    finalPlayerId = conn.playerId
                    clients.remove(clientId, conn)
                    val removedOwn = clients.remove(finalPlayerId, conn)
                    try { socket.close() } catch (_: Exception) {}
                    // Only announce the drop if we were still the registered connection; otherwise
                    // this is just an old socket being cleaned up behind a successful reconnect.
                    if (removedOwn) {
                        _connectionEvents.emit(ConnectionEvent.Disconnected(finalPlayerId))
                    }
                } else {
                    // Never got as far as registering (stream setup failed) - nothing to announce.
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * The one place that actually touches a socket's OutputStream. Always called from a coroutine
     * already dispatched onto that connection's own [ClientConnection.writeDispatcher] - never
     * from sendScope - so a blocked or slow write here only holds up further writes to this SAME
     * connection, never sendScope's ordering thread or any other player's connection.
     */
    private fun writeNow(connection: ClientConnection, serialized: String) {
        try {
            connection.writer.print(serialized)
            connection.writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending to ${connection.playerId}", e)
        }
    }

    /**
     * Give a connection its player identity, once the application has decided it may have one.
     *
     * This is the authorised half of what the read loop used to do for free. Two rules:
     *
     *  - **A live connection already holding that id wins.** A genuine reconnect arrives on a
     *    socket that is dead or gone; a second socket asking for an id that a healthy connection is
     *    currently using is either a bug or somebody helping themselves to another player's hand.
     *    A dead entry is replaced, because that IS the reconnect case.
     *  - **Only the caller decides when.** The PIN, the roster and the engine's view of whether a
     *    seat can be resumed all live above this class, so binding is something it is told to do,
     *    never something it infers from traffic.
     *
     * @return true if the connection now owns [playerId].
     */
    fun bindPlayerId(clientId: String, playerId: String): Boolean {
        val connection = clients[clientId] ?: return false
        if (connection.playerId == playerId) return true

        val existing = clients[playerId]
        if (existing != null && existing !== connection) {
            val stillAlive = !existing.socket.isClosed && existing.socket.isConnected
            if (stillAlive) {
                Log.w(TAG, "Refusing to bind $playerId - a live connection already holds it")
                return false
            }
            clients.remove(playerId, existing)
        }

        clients.remove(clientId, connection)
        connection.playerId = playerId
        clients[playerId] = connection
        return true
    }

    /** Whether a live connection currently holds this id. */
    fun isPlayerIdLive(playerId: String): Boolean {
        val c = clients[playerId] ?: return false
        return !c.socket.isClosed && c.socket.isConnected
    }

    fun sendToClient(playerId: String, message: NetworkMessage) {
        val connection = clients[playerId] ?: return
        val serialized = MessageSerializer.serialize(message)
        sendScope.launch {
            scope.launch(connection.writeDispatcher) { writeNow(connection, serialized) }
        }
    }

    fun broadcastToAll(message: NetworkMessage) {
        val serialized = MessageSerializer.serialize(message)
        sendScope.launch {
            clients.values.forEach { connection ->
                scope.launch(connection.writeDispatcher) { writeNow(connection, serialized) }
            }
        }
    }

    fun broadcastExcept(excludePlayerId: String, message: NetworkMessage) {
        val serialized = MessageSerializer.serialize(message)
        sendScope.launch {
            clients.filter { it.key != excludePlayerId }.values.forEach { connection ->
                scope.launch(connection.writeDispatcher) { writeNow(connection, serialized) }
            }
        }
    }

    fun disconnectClient(playerId: String) {
        val connection = clients.remove(playerId)
        try {
            connection?.socket?.close()
        } catch (_: Exception) {}
    }

    /**
     * Deliver one last message to a client, then close its socket - in that order, guaranteed.
     *
     * sendToClient() followed by disconnectClient() does NOT guarantee it: the write is queued onto
     * the connection's writeDispatcher and returns immediately, so a close issued on the caller's
     * thread can beat the bytes onto the socket and the client is left with nothing but a dropped
     * connection. Queueing the close onto the SAME writeDispatcher puts it behind the write in the
     * one place that serializes them.
     *
     * The map entry is dropped up front so nothing new can be addressed to this player in the
     * meantime, and by value so a client that has already reconnected on a fresh socket keeps it -
     * the same hazard the read loop's finally block guards against.
     */
    fun sendThenDisconnect(playerId: String, message: NetworkMessage) {
        val connection = clients[playerId] ?: return
        clients.remove(playerId, connection)
        val serialized = MessageSerializer.serialize(message)
        sendScope.launch {
            scope.launch(connection.writeDispatcher) {
                writeNow(connection, serialized)
                try { connection.socket.close() } catch (_: Exception) {}
            }
        }
    }

    fun getConnectedPlayerIds(): Set<String> = clients.keys.toSet()

    fun stop() {
        scope.cancel()
        sendScope.cancel()
        clients.values.forEach { connection ->
            try { connection.socket.close() } catch (_: Exception) {}
        }
        clients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }
}
