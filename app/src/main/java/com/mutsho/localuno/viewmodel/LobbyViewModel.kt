package com.mutsho.localuno.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mutsho.localuno.model.*
import com.mutsho.localuno.network.GameClient
import com.mutsho.localuno.network.GameServer
import com.mutsho.localuno.network.Heartbeat
import com.mutsho.localuno.network.NsdHelper
import com.mutsho.localuno.network.getLocalIpAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class LobbyViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * Names are obviously non-human on purpose. A bot called "Sam" sitting between two real
         * players is indistinguishable from one at the table, and the roster badge is only visible
         * in the lobby - the board just shows names.
         */
        private val BOT_NAMES = listOf(
            "Robo", "Circuit", "Pixel", "Widget", "Cogsworth", "Blip", "Chip"
        )

        /** Mirrors ui.theme.AvatarColors; every consumer takes it modulo that list's size. */
        private const val AVATAR_COLOR_COUNT = 8

        /** How long the "looking for tables" spinner shows before the empty state offers the
         *  manual/QR fallbacks. Discovery itself keeps running well past this. */
        private const val SPINNER_MS = 12_000L

        /** Re-issue the mDNS listener this often; a long-lived one can die with no callback. */
        private const val SCAN_REARM_MS = 20_000L
    }

    private var nsdHelper: NsdHelper? = null
    private var gameServer: GameServer? = null
    private var gameClient: GameClient? = null
    private var localPlayerId: String? = null
    private var scanTimeoutJob: Job? = null
    private var spinnerJob: Job? = null

    // Remembered purely so a mid-game drop can be retried without the player re-typing anything -
    // see attemptReconnect(). Not persisted beyond this ViewModel's lifetime; a reconnect only
    // ever needs to reach the same host this device already joined once this session.
    private var lastHost: String? = null
    private var lastPort: Int = 0
    private var lastPin: String? = null
    private var lastPlayerName: String = ""
    private var lastAvatarColor: Int = 0

    // connectAndJoin's own launch (connect + send JoinRequest + collect messages forever) and its
    // client.disconnected observer. Both need to be tracked and cancelled on a retry: the comment
    // on connectAndJoin already claimed a prior attempt's collector "would leak" without cleanup,
    // but gameClient?.disconnect() alone only tears down the OLD GameClient's own internal scope -
    // it has no way to reach into THIS ViewModel's viewModelScope and stop the coroutine launched
    // here, which just sits forever re-suspended on a SharedFlow that will never emit again. Every
    // retry (a rejected PIN, a failed connection) left one more of those permanently parked.
    private var clientConnectionJob: Job? = null
    private var clientDisconnectJob: Job? = null

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost

    // Solo is a host with no server. Kept as its own flag rather than inferred from
    // "isHost && gameServer == null", because callers outside this class can't see gameServer and
    // the two states want different behaviour on rematch (see prepareRematch).
    private val _isSolo = MutableStateFlow(false)
    val isSolo: StateFlow<Boolean> = _isSolo

    private val _settings = MutableStateFlow(GameSettings())
    val settings: StateFlow<GameSettings> = _settings

    private val _players = MutableStateFlow<List<PlayerInfo>>(emptyList())
    val players: StateFlow<List<PlayerInfo>> = _players

    private val _lobbies = MutableStateFlow<List<NsdHelper.LobbyInfo>>(emptyList())
    val lobbies: StateFlow<List<NsdHelper.LobbyInfo>> = _lobbies

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _gameStarted = MutableStateFlow(false)
    val gameStarted: StateFlow<Boolean> = _gameStarted

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Host-only: "ip:port" so it can be shared manually if a joiner's mDNS discovery can't see it.
    private val _hostAddress = MutableStateFlow<String?>(null)
    val hostAddress: StateFlow<String?> = _hostAddress

    // Fires on non-host clients when the host starts a rematch, so the End screen can navigate
    // them back to the lobby (unlike gameStarted, which they can't watch from the End screen).
    private val _rematchRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val rematchRequested: SharedFlow<Unit> = _rematchRequested

    fun createLobby(settings: GameSettings, playerId: String, playerName: String, avatarColor: Int) {
        _isHost.value = true
        localPlayerId = playerId
        _settings.value = settings

        // Add host as first player
        _players.value = listOf(
            PlayerInfo(
                id = playerId,
                name = playerName,
                avatarColor = avatarColor,
                isHost = true,
                isConnected = true
            )
        )

        // Start the TCP server, THEN advertise it.
        //
        // This was `gameServer = GameServer().apply { ...; advertiseLobby() }`, and the apply block
        // runs before the assignment lands - so `gameServer` was still null inside it, and
        // advertiseLobby's `val server = gameServer ?: return` bailed out on the first line. The
        // host never registered an mDNS service at all, so nobody could ever discover the table and
        // every guest had to be given the IP by hand.
        //
        // It looked like flaky mDNS rather than a hard bug because a REMATCH re-advertises from
        // prepareRematch, by which time gameServer is set - so the second game of an evening could
        // announce itself while the first never did.
        //
        // advertiseLobby now takes the server as a parameter rather than reading the field, so this
        // particular ordering mistake cannot be made again.
        val server = GameServer()
        val port = server.start()
        gameServer = server
        _hostAddress.value = getLocalIpAddress()?.let { "$it:$port" }
        nsdHelper = NsdHelper(getApplication())
        advertiseLobby(server)

        // Listen for incoming connections. Deliberately NOT Dispatchers.IO: handleServerMessage and
        // the disconnect handler just below both do plain read-modify-write on _players.value
        // (read the current list, compute a new one, assign it back) with no lock of its own. Both
        // addBot()/removeBot() and leaveLobby() mutate the exact same field from Compose click
        // handlers, which run on Main - IO here would let a JoinRequest race one of those from a
        // different thread with no synchronization, and StateFlow assignment isn't a
        // compare-and-swap: whichever write lands second silently overwrites the other's, dropping
        // a joining player or a just-added bot with no error anywhere. GameViewModel's own
        // equivalent collectors already avoid IO for this exact reason; this one was the odd one
        // out, harmless until addBot()/removeBot() gave it something on Main to actually race.
        viewModelScope.launch {
            gameServer?.messages?.collect { (clientId, message) ->
                handleServerMessage(clientId, message)
            }
        }

        viewModelScope.launch {
            gameServer?.connectionEvents?.collect { event ->
                when (event) {
                    is GameServer.ConnectionEvent.Disconnected -> {
                        _players.value = _players.value.map { player ->
                            if (player.id == event.clientId) player.copy(isConnected = false)
                            else player
                        }
                        broadcastLobbyUpdate()
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Solo play: you against [GameSettings.maxPlayers] - 1 bots, with no networking at all.
     *
     * Deliberately starts NO GameServer and NO NSD advertising. Solo could have been implemented as
     * "host a lobby nobody joins", but that opens a listening socket and puts the device on the
     * local network's mDNS registry to run a game that is purely local - visible to everyone on the
     * Wi-Fi, and dependent on having a usable network at all. Nothing downstream needs one: the host
     * already runs the engine itself and every broadcast is a null-safe call on gameServer.
     *
     * The roster is built here rather than in the UI so the seat count means one thing everywhere -
     * the table's size, humans and bots together.
     */
    fun startSolo(settings: GameSettings, playerId: String, playerName: String, avatarColor: Int) {
        // Tear down anything left over from a previous lobby before taking over the roster.
        leaveLobby()

        _isHost.value = true
        _isSolo.value = true
        localPlayerId = playerId
        _settings.value = settings

        val you = PlayerInfo(
            id = playerId,
            name = playerName,
            avatarColor = avatarColor,
            isHost = true,
            isConnected = true
        )
        _players.value = listOf(you)
        repeat((settings.maxPlayers - 1).coerceAtLeast(1)) { addBot() }

        // Deliberately does NOT set _gameStarted. Nothing in the solo path reads it - the caller
        // starts the engine and navigates itself - but the Lobby and Join screens both auto-launch
        // a game the moment they see it true. Leaving it set here would mean a solo round exited
        // without a full teardown could drop a later visit to either screen straight into a match.
    }

    /**
     * Host-only: seat a bot. Bots are ordinary roster entries the host will drive itself once the
     * round starts (see GameViewModel's bot job), so they occupy a real seat and count toward
     * maxPlayers - a lobby packed with bots reports itself full to joiners, which is accurate.
     * Removing one frees the seat again.
     */
    fun addBot() {
        if (!_isHost.value) return
        if (_gameStarted.value) return
        val current = _players.value
        if (current.size >= _settings.value.maxPlayers) return

        val takenNames = current.map { it.name }.toSet()
        val name = BOT_NAMES.firstOrNull { it !in takenNames }
            ?: "Bot ${current.count { it.isBot } + 1}"
        // Prefer a colour nobody is using so the seats stay tellable apart at a glance.
        val takenColors = current.map { it.avatarColor }.toSet()
        val color = (0 until AVATAR_COLOR_COUNT).firstOrNull { it !in takenColors }
            ?: (0 until AVATAR_COLOR_COUNT).random()

        _players.value = current + PlayerInfo(
            // Prefixed rather than a bare UUID so a bot id is recognisable in a log or on the wire.
            id = "bot-${UUID.randomUUID()}",
            name = name,
            avatarColor = color,
            isHost = false,
            isConnected = true,
            isBot = true
        )
        broadcastLobbyUpdate()
    }

    /** Host-only. Guarded on isBot so this can never be turned into a kick for a real player. */
    fun removeBot(botId: String) {
        if (!_isHost.value) return
        if (_gameStarted.value) return
        val target = _players.value.find { it.id == botId } ?: return
        if (!target.isBot) return
        _players.value = _players.value.filter { it.id != botId }
        broadcastLobbyUpdate()
    }

    /**
     * Publish this lobby on the local network so the Join screen can find it.
     *
     * Split out of createLobby because advertising has to be switched OFF while a match runs and
     * back ON for a rematch - see startGame/prepareRematch. Reads the port from the live server
     * rather than a remembered value, so it can never advertise a stale one.
     */
    private fun advertiseLobby(server: GameServer) {
        val settings = _settings.value
        nsdHelper?.registerService(
            lobbyName = settings.lobbyName,
            port = server.port,
            gameMode = settings.gameMode.name,
            maxPlayers = settings.maxPlayers,
            hasPin = settings.pin != null,
            onRegistered = { /* Service registered */ },
            onError = { error -> _error.value = error }
        )
    }

    fun startScanning() {
        _isScanning.value = true
        _lobbies.value = emptyList()

        // stopDiscovery(), NOT cleanup(). cleanup() also unregisters, and this ViewModel is shared
        // across every route - so a host who wandered back to the main menu (which scans, to show
        // the nearby-table count) tore down their OWN advertisement and vanished from everyone
        // else's list while still sitting in their lobby.
        nsdHelper?.stopDiscovery()
        nsdHelper = null

        // The spinner is on its own clock now, separate from whether discovery is alive. They were
        // the same flag, which is what made "stop the spinner" mean "stop looking" - see below.
        spinnerJob?.cancel()
        spinnerJob = viewModelScope.launch {
            delay(SPINNER_MS)
            _isScanning.value = false
        }

        // Discovery runs for as long as the screen is open. It used to hard-stop after 12 seconds,
        // which quietly guaranteed the most ordinary sequence at a table would fail: everyone opens
        // Join, THEN one person hosts. Twelve seconds later every guest's discovery was dead, and
        // the host's table - advertised at second 20 - was announcing itself to nobody still
        // listening. Nothing on screen said discovery had stopped, so it read as "the app can't
        // find the game", and the only way out was a manual refresh at exactly the right moment.
        //
        // Instead the listener is re-armed periodically. A long-lived NsdManager discovery can be
        // torn down by the system (Wi-Fi flap, doze, the daemon restarting) with no callback at
        // all, so a scan left alone for minutes can be silently deaf; re-issuing it is cheap and
        // makes the screen self-healing. Found lobbies are NOT cleared on re-arm - every live host
        // re-announces immediately and is de-duplicated on arrival, so the list never blinks.
        scanTimeoutJob?.cancel()
        scanTimeoutJob = viewModelScope.launch {
            while (true) {
                delay(SCAN_REARM_MS)
                val helper = nsdHelper ?: break
                helper.restartDiscovery()
            }
        }

        nsdHelper = NsdHelper(getApplication()).apply {
            discoverServices(
                onLobbyFound = { lobby ->
                    val current = _lobbies.value.toMutableList()
                    // Avoid duplicates
                    current.removeAll { it.host == lobby.host && it.port == lobby.port }
                    current.add(lobby)
                    _lobbies.value = current
                    // Found something - drop the spinner early, but keep discovering: more tables
                    // may still be coming up, and one of them may be the one this player wants.
                    spinnerJob?.cancel()
                    _isScanning.value = false
                },
                onLobbyLost = { serviceName ->
                    // Match by serviceName ("LocalUno-<lobbyName>") not display name
                    _lobbies.value = _lobbies.value.filter { it.serviceName != serviceName }
                },
                onError = { error ->
                    _error.value = error
                    _isScanning.value = false
                }
            )
        }
    }

    fun stopScanning() {
        scanTimeoutJob?.cancel()
        spinnerJob?.cancel()
        nsdHelper?.stopDiscovery()
        _isScanning.value = false
    }

    fun joinLobby(lobby: NsdHelper.LobbyInfo, pin: String?, playerId: String, playerName: String, avatarColor: Int) {
        connectAndJoin(lobby.host, lobby.port, pin, playerId, playerName, avatarColor)
    }

    /**
     * Fallback for when mDNS/NSD discovery doesn't find anything (common on guest/corporate
     * Wi-Fi with client isolation) - connects directly to a manually-entered host:port instead
     * of a discovered NsdHelper.LobbyInfo. Mode/player-count/PIN-required aren't known upfront;
     * they arrive moments later via the normal JoinResponse/LobbyUpdate exchange.
     */
    fun joinManual(host: String, port: Int, pin: String?, playerId: String, playerName: String, avatarColor: Int) {
        connectAndJoin(host, port, pin, playerId, playerName, avatarColor)
    }

    private fun connectAndJoin(host: String, port: Int, pin: String?, playerId: String, playerName: String, avatarColor: Int) {
        _isHost.value = false
        localPlayerId = playerId
        lastHost = host
        lastPort = port
        lastPin = pin
        lastPlayerName = playerName
        lastAvatarColor = avatarColor
        // Clean up a previous attempt (e.g. a retry after a rejected PIN) - otherwise its socket
        // and message collector would leak, staying alive alongside the new attempt. Cancelling
        // the JOBS is what actually stops the old collectors (see the fields' own comment);
        // disconnect() alone only tears down the old GameClient's internals.
        gameClient?.disconnect()
        clientConnectionJob?.cancel()
        clientDisconnectJob?.cancel()

        clientConnectionJob = viewModelScope.launch(Dispatchers.IO) {
            gameClient = GameClient()
            val connected = gameClient!!.connect(host, port)
            if (!connected) {
                _error.value = "Failed to connect to lobby"
                return@launch
            }

            // Send join request
            gameClient!!.send(
                NetworkMessage.JoinRequest(
                    playerName = playerName,
                    playerId = playerId,
                    pin = pin,
                    avatarColor = avatarColor
                )
            )

            // Surfaces a connection that dies while sitting in the lobby - a dropped Wi-Fi link,
            // the host quitting without a clean disconnect, etc. - which previously left the
            // screen frozen on "Waiting for host to start..." forever. Only acts pre-GameStart;
            // once the round is underway, GameViewModel's own equivalent collector takes over the
            // in-game "connection lost" flow, so this would otherwise fire a second, redundant one
            // right as that path is already handling it.
            clientDisconnectJob = viewModelScope.launch {
                gameClient!!.disconnected.collect {
                    if (!_gameStarted.value) {
                        _error.value = "Lost connection to the host"
                    }
                }
            }

            // Listen for messages. Off IO from here on: handleClientMessage writes _players/
            // _settings the same read-modify-write way the host-side handlers above do, and
            // leaveLobby() (Main, from a UI action) can write _players too - same race, just a
            // narrower window since a client only has itself as a writer most of the time. The
            // blocking connect()/send() calls above stay on IO; only the message-handling tail
            // moves.
            withContext(Dispatchers.Main) {
                gameClient!!.messages.collect { message ->
                    handleClientMessage(message)
                }
            }
        }
    }

    /**
     * One reconnect attempt after a mid-game drop: a fresh socket to the same host:port this
     * device already joined, re-sending the identical JoinRequest (same playerId) so the host's
     * GameEngine.reconnectPlayer recognises it as the seat it's been waiting on, not a stranger.
     *
     * Bounded to [Heartbeat.READ_TIMEOUT_MS] worth of time to fail fast - GameViewModel calls this
     * in a retry loop, and one attempt hanging on the platform's own (much longer) default connect
     * timeout would eat into the 30-second window the engine actually gives a dropped player to
     * come back. Returns the new, already-connected GameClient on success; null on any failure,
     * leaving the decision of whether/how to retry entirely to the caller.
     *
     * Deliberately doesn't touch _players/_settings/_gameStarted or navigate anywhere - restoring
     * the BOARD mid-game is GameViewModel's concern, not the lobby's. What it must do is re-point
     * this ViewModel's own message collector at the new socket, which is the subtle half: the old
     * collector is subscribed to the dead GameClient's flow and will never emit again, so without
     * re-attaching, a reconnected player silently stops receiving every message only the LOBBY
     * handles. Concretely, they'd miss ReturnToLobby and be stranded on the End screen while the
     * rest of the table followed the host into a rematch, and miss LobbyUpdate so their roster and
     * settings would quietly go stale.
     */
    suspend fun attemptReconnect(): GameClient? {
        val host = lastHost ?: return null
        val id = localPlayerId ?: return null
        val client = GameClient()
        val connected = withContext(Dispatchers.IO) {
            client.connect(host, lastPort, connectTimeoutMs = Heartbeat.RECONNECT_CONNECT_TIMEOUT_MS)
        }
        if (!connected) return null
        client.send(
            NetworkMessage.JoinRequest(
                playerName = lastPlayerName,
                playerId = id,
                pin = lastPin,
                avatarColor = lastAvatarColor
            )
        )
        gameClient = client

        // Re-point the lobby's own collector. The disconnect observer is NOT restarted here: while
        // a round is running, GameViewModel owns the "connection died" flow (including retrying
        // through this very method), and a second observer would race it with a duplicate error.
        clientConnectionJob?.cancel()
        clientDisconnectJob?.cancel()
        clientConnectionJob = viewModelScope.launch {
            client.messages.collect { message ->
                handleClientMessage(message)
            }
        }
        return client
    }

    fun startGame() {
        if (!_isHost.value) return
        // Count/keep only players who are actually still here. A dropped lobby member stays on the
        // roster (marked disconnected) so the host can see who left, but starting a round with them
        // still in it deals them a hand and gives them turns nobody will ever take. This also stops
        // "host + one ghost" from satisfying the 2-player minimum.
        val connected = _players.value.filter { it.isConnected }
        if (connected.size < 2) return

        // Publish the pruned roster BEFORE GameStart so host and clients build their player lists
        // from an identical set, in an identical order.
        _players.value = connected
        broadcastLobbyUpdate()

        // Stop advertising for the duration of the match. The service stayed published for the
        // whole game, so an in-progress table kept showing up in everyone's Join list looking
        // joinable - tap it and the host rejects you, because a JoinRequest arriving mid-round is
        // only ever honoured as a reconnect for a seat already in the match. Reconnects are
        // unaffected: a dropped player dials the host:port they already know (attemptReconnect),
        // never discovery.
        nsdHelper?.unregisterService()

        _gameStarted.value = true
        gameServer?.broadcastToAll(NetworkMessage.GameStart())
    }

    /**
     * Host-only: called when "Play Again" is tapped. Resets gameStarted so the Lobby screen
     * shows normally instead of auto-skipping back into a game (its LaunchedEffect only fires
     * on gameStarted actually becoming true), refreshes the roster from who was still connected
     * at the end of the match, and tells clients to head back to the lobby too - without this,
     * the host would restart alone while everyone else stayed stuck on the End screen.
     */
    fun prepareRematch(finishedPlayers: List<Player>) {
        if (!_isHost.value) return
        // Who comes back for the next round is a question about SOCKETS, not about Player
        // .isConnected - the engine overloads that flag to mean "still in this round", and clears
        // it for anyone knocked out at the Mercy threshold. Filtering on it dropped every
        // eliminated player from the rematch even though they were sitting right there with a live
        // connection: they'd follow the host back to the lobby and find themselves missing from the
        // roster, then watch a round start without them.
        //
        // The server's own client map is the honest answer for remote players. Bots and the host
        // have no socket by definition and are always still here.
        val reachable = gameServer?.getConnectedPlayerIds().orEmpty()
        val survivors = if (_isSolo.value) {
            // Solo has no sockets at all, so nobody can be genuinely gone.
            finishedPlayers
        } else {
            finishedPlayers.filter { it.isBot || it.id == localPlayerId || it.id in reachable }
        }
        _players.value = survivors.map { player ->
            PlayerInfo(
                id = player.id,
                name = player.name,
                avatarColor = player.avatarColor,
                isHost = player.isHost,
                isConnected = true,
                // Dropping this would re-seat every surviving bot as a player nobody drives: dealt
                // a hand, handed turns, and left to burn the full turn clock on each one.
                isBot = player.isBot
            )
        }
        _gameStarted.value = false
        // Back in the lobby, so the table is joinable again - republish it. Re-registering the same
        // name is safe here because we unregistered it ourselves at startGame; the collision-rename
        // hazard NsdHelper warns about comes from re-registering while the old name is still live.
        gameServer?.let { advertiseLobby(it) }
        gameServer?.broadcastToAll(NetworkMessage.ReturnToLobby())
        broadcastLobbyUpdate()
    }

    fun leaveLobby() {
        // Tell the host we're gone before the game starts, so they actually remove us from the
        // roster (freeing our slot) instead of just marking us disconnected via the ordinary
        // socket-close detection path, which leaves a ghost entry occupying a seat. send() is
        // fire-and-forget on its own coroutine and disconnect() cancels that scope immediately,
        // so without a brief delay here the message could be cancelled before it's ever written.
        val id = localPlayerId
        val client = gameClient
        if (!_isHost.value && id != null && client != null) {
            client.send(NetworkMessage.PlayerLeft(playerId = id))
            // NOT viewModelScope: onCleared() calls this, and the framework cancels viewModelScope
            // *before* onCleared() runs - the delayed disconnect would silently never fire, leaking
            // the socket. A standalone scope survives the ViewModel long enough to finish the flush.
            CoroutineScope(Dispatchers.IO).launch {
                delay(150)
                client.disconnect()
            }
        } else {
            gameClient?.disconnect()
        }

        clientConnectionJob?.cancel()
        clientDisconnectJob?.cancel()
        nsdHelper?.cleanup()
        nsdHelper = null
        gameServer?.stop()
        gameServer = null
        gameClient = null
        localPlayerId = null
        _players.value = emptyList()
        _isHost.value = false
        _isSolo.value = false
        _gameStarted.value = false
        _error.value = null
        _hostAddress.value = null
    }

    private fun handleServerMessage(clientId: String, message: NetworkMessage) {
        when (message) {
            is NetworkMessage.JoinRequest -> {
                // Validate PIN
                val settings = _settings.value
                if (settings.pin != null && message.pin != settings.pin) {
                    gameServer?.sendToClient(
                        clientId,
                        NetworkMessage.JoinResponse(accepted = false, reason = "Invalid PIN")
                    )
                    return
                }

                // The match is already underway. A JoinRequest arriving now is always a mid-round
                // RECONNECT attempt (a fresh join can't reach this point - the ordinary flow never
                // gets past a full lobby, and the lobby itself is gone once the round starts), and
                // GameViewModel's GameEngine is the only thing that knows whether this playerId is
                // actually a seat worth resuming. This collector must do NOTHING here, not reject:
                // both it and GameViewModel's own collector are attached to the same
                // GameServer.messages flow, so responding here as well as there would send the
                // rejoining client two different JoinResponses for one request. This used to
                // reject unconditionally with "Game already in progress" - correct for a stranger,
                // but it meant a legitimately dropped player could never get back in at all.
                if (_gameStarted.value) {
                    return
                }

                // A player with this id may already be on the roster - a reconnect after a dropped
                // socket, since playerId is a stable per-device UUID. Treat it as a rejoin and
                // replace that entry; appending would leave two rows sharing one id, which breaks
                // getPlayerById, collides in the id-keyed card-count maps, and burns a slot.
                val existing = _players.value.indexOfFirst { it.id == message.playerId }
                if (existing < 0 && _players.value.size >= settings.maxPlayers) {
                    gameServer?.sendToClient(
                        clientId,
                        NetworkMessage.JoinResponse(accepted = false, reason = "Lobby is full")
                    )
                    return
                }

                val newPlayer = PlayerInfo(
                    id = message.playerId,
                    name = message.playerName,
                    avatarColor = message.avatarColor,
                    isHost = _players.value.getOrNull(existing)?.isHost ?: false,
                    isConnected = true
                )
                _players.value = if (existing >= 0) {
                    _players.value.toMutableList().also { it[existing] = newPlayer }
                } else {
                    _players.value + newPlayer
                }

                // Use clientId (socket key) to send response ? message.playerId not yet registered
                gameServer?.sendToClient(
                    clientId,
                    NetworkMessage.JoinResponse(accepted = true, assignedId = message.playerId)
                )

                broadcastLobbyUpdate()
            }
            is NetworkMessage.PlayerLeft -> {
                // clientId (the identity GameServer resolved for this socket), not message.playerId
                // (whatever the sender put in the payload) - otherwise one client can remove a
                // different player from the roster. GameViewModel's handler already works this way;
                // this one was the odd one out.
                _players.value = _players.value.filter { it.id != clientId }
                broadcastLobbyUpdate()
            }
            else -> {}
        }
    }

    private fun handleClientMessage(message: NetworkMessage) {
        when (message) {
            is NetworkMessage.JoinResponse -> {
                if (!message.accepted) {
                    _error.value = message.reason ?: "Join rejected"
                    gameClient?.disconnect()
                }
            }
            is NetworkMessage.LobbyUpdate -> {
                _players.value = message.players
                _settings.value = message.settings
            }
            is NetworkMessage.GameStart -> {
                _gameStarted.value = true
            }
            is NetworkMessage.ReturnToLobby -> {
                _gameStarted.value = false
                viewModelScope.launch { _rematchRequested.emit(Unit) }
            }
            else -> {}
        }
    }

    private fun broadcastLobbyUpdate() {
        gameServer?.broadcastToAll(
            NetworkMessage.LobbyUpdate(
                players = _players.value,
                settings = _settings.value
            )
        )
    }

    fun getServer(): GameServer? = gameServer
    fun getClient(): GameClient? = gameClient
    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        leaveLobby()
    }
}
