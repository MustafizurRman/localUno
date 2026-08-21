package com.mutsho.localuno.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mutsho.localuno.BuildConfig
import com.mutsho.localuno.CrashReporter
import com.mutsho.localuno.engine.BotBrain
import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.engine.SeedJournal
import com.mutsho.localuno.model.*
import com.mutsho.localuno.network.GameClient
import com.mutsho.localuno.network.GameServer
import com.mutsho.localuno.network.Heartbeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class GameViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** `adb logcat -s LocalUnoSeed` and the seed of every round played is there. */
        private const val SEED_TAG = "LocalUnoSeed"

        /**
         * Where [saveSeedLog] leaves the last finished round, inside the app's private files dir:
         * `adb shell run-as com.mutsho.localuno cat files/seed-log.txt`.
         */
        private const val SEED_LOG_FILE = "seed-log.txt"

        /**
         * How long a bot appears to think before moving. Randomised per turn because a fixed delay
         * makes a table of bots tick like a metronome, which reads as machinery rather than as
         * players.
         *
         * Paced for watching rather than for speed: at this range a full table of bots plays slowly
         * enough to follow each move land - the card, the colour change, the move log, the turn
         * indicator moving on - which is the point when the board is being reviewed rather than
         * played. See BOT_THINK_CLOCK_FRACTION for the ceiling that keeps this safe to raise.
         */
        private val BOT_THINK_DELAY_MS = 2000L..3400L

        /**
         * The window a human gets to catch a bot that just went down to one card. Long enough to
         * see the hand shrink and react; short enough that a bot doesn't look like it forgot.
         */
        private const val BOT_UNO_CALL_DELAY_MS = 2200L

        /**
         * Hard ceiling on a bot's pause, as a fraction of the turn clock actually in force.
         *
         * Without it, raising BOT_THINK_DELAY_MS past the shortest clock (10s) would have bots
         * routinely time out: the clock would auto-draw for them, every seat would be announced as
         * having run out of time, and the bots would look broken rather than slow. Deriving the
         * bound from the live setting means the delays above can be tuned freely without anyone
         * having to remember that relationship.
         */
        private const val BOT_THINK_CLOCK_FRACTION = 0.6
    }

    /**
     * Set when a reconnect actually succeeded, so the next state diff is skipped once.
     *
     * Replaces a heuristic that could not work: the old code inferred "I missed broadcasts" from
     * the sequence number jumping by more than one. But the sequence counts ENGINE STATE
     * TRANSITIONS, not broadcasts, and a single card play routinely produces two or three of them -
     * the play, the card's effect, the mercy check. Every ordinary play therefore looked like a
     * gap, so the move log filled with "Reconnected - catching up" INSTEAD of the moves, in games
     * with no network at all. Only the code that performs a reconnect knows one happened.
     */
    @Volatile private var justReconnected = false

    private var gameEngine: GameEngine? = null
    private var gameServer: GameServer? = null
    private var gameClient: GameClient? = null

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState

    /**
     * The current round's replay recording, or null - which is every release build, and every guest
     * device, since only the host runs an engine. Exposed so the board can show the seed and hand
     * the whole log over. See [SeedJournal].
     */
    private val _seedJournal = MutableStateFlow<SeedJournal?>(null)
    val seedJournal: StateFlow<SeedJournal?> = _seedJournal

    private val _localPlayerId = MutableStateFlow("")
    val localPlayerId: StateFlow<String> = _localPlayerId

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost

    private val _showColorPicker = MutableStateFlow(false)
    val showColorPicker: StateFlow<Boolean> = _showColorPicker

    private val _playableCardIds = MutableStateFlow<Set<Int>>(emptySet())
    val playableCardIds: StateFlow<Set<Int>> = _playableCardIds

    private val _gameOver = MutableStateFlow(false)
    val gameOver: StateFlow<Boolean> = _gameOver

    private val _scores = MutableStateFlow<Map<String, Int>>(emptyMap())
    val scores: StateFlow<Map<String, Int>> = _scores

    private val _winnerName = MutableStateFlow("")
    val winnerName: StateFlow<String> = _winnerName

    private val _durationSeconds = MutableStateFlow(0L)
    val durationSeconds: StateFlow<Long> = _durationSeconds

    private val _unoAnnouncement = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val unoAnnouncement: SharedFlow<String> = _unoAnnouncement

    /**
     * Rolling move log for the board's last-move bar and its expandable history
     * (UnoBoard.dc.html's `lastMove` strip and `log` list). Newest first, capped.
     *
     * Derived on each device from state it already receives rather than broadcast: every entry
     * comes from diffing the previous GameState against the new one inside applyMoveLog, which
     * both host and clients run over the same StateUpdate data. That keeps it off the wire
     * entirely and means it can't desync from the board it describes.
     */
    private val _moveLog = MutableStateFlow<List<MoveLogEntry>>(emptyList())
    val moveLog: StateFlow<List<MoveLogEntry>> = _moveLog

    private val _knockoutEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val knockoutEvent: SharedFlow<String> = _knockoutEvent

    /** Who ran out of time, and whether it cost them a card. */
    private val _timeoutEvent = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 1)
    val timeoutEvent: SharedFlow<Pair<String, Boolean>> = _timeoutEvent

    // Fires when a play is silently rejected because the turn already moved on (e.g. the turn
    // timer auto-drew for this player while they were still choosing a wild card's color) -
    // without this, the player just sees nothing happen with no explanation.
    private val _playRejectedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playRejectedEvent: SharedFlow<String> = _playRejectedEvent

    private val _emojiEvent = MutableSharedFlow<NetworkMessage.EmojiReaction>(extraBufferCapacity = 1)
    val emojiEvent: SharedFlow<NetworkMessage.EmojiReaction> = _emojiEvent

    /**
     * Why this match ended for a client without a winner - the host quit, or the link to them died.
     * Null while the match is live.
     *
     * STATE, not an event, and that distinction is the whole point. As a replay-less SharedFlow this
     * was delivered once to whoever happened to be subscribed at that instant, and both of its
     * consumers are route-scoped: the Game route while you're playing, the End route once you tap
     * FULL RESULTS. During the single navigation frame between them - Game already disposed, End not
     * yet composed - there is no subscriber, so the emission went nowhere, and notifyGameEnded's
     * own duplicate guard then made sure it could never fire again. The player sat on a dead
     * connection with nothing ever telling them.
     *
     * Held as state instead, whichever route composes next simply reads it. Cleared by resetGame()
     * when a new round starts, which is the only thing that legitimately makes it stale.
     */
    private val _gameEndedReason = MutableStateFlow<String?>(null)
    val gameEndedReason: StateFlow<String?> = _gameEndedReason

    private fun notifyGameEnded(reason: String) {
        // Two different signals can both legitimately mean "this match is over, go home": an
        // explicit HostEndedGame message, and the socket to the host simply dying (see
        // clientDisconnectJob). When the host ends the game on purpose BOTH fire in close
        // succession - the message arrives first, then ~150ms later leaveGame()'s delayed teardown
        // actually closes the socket. First reason wins so the second can't overwrite a specific
        // explanation with a vaguer one.
        if (_gameEndedReason.value != null) return
        _gameEndedReason.value = reason
    }

    /**
     * Closes the wild colour picker the instant the turn it belongs to moves on from under it -
     * called on every state update, host-local and client-received alike, so it fires regardless
     * of WHY the turn moved (the clock auto-drew, a stacked draw forced it, anything else).
     *
     * Without this the dialog just sat open: ColorPickerDialog swallows back-presses by design (a
     * wild's colour can't be un-chosen once you've committed to playing it), and _showColorPicker
     * was previously only ever cleared inside chooseColor() itself. A player who deliberated too
     * long was left staring at "PICK A COLOUR" with no indication anything had happened; tapping a
     * colour afterward just produced a "Too slow" toast they had no context for. This gives them
     * that same toast, and closes the dialog, at the moment it actually stops being their turn
     * instead of waiting for them to notice on their own.
     */
    private fun dismissStaleColorPicker(newState: GameState) {
        if (!_showColorPicker.value) return
        if (newState.currentPlayer?.id == _localPlayerId.value) return
        _showColorPicker.value = false
        pendingCard = null
        endColorPick(_localPlayerId.value)
        viewModelScope.launch { _playRejectedEvent.emit("Too slow — your turn ended") }
    }

    private var pendingCard: Card? = null

    /**
     * Whose colour choice the turn clock is currently waiting on, if anyone.
     *
     * A wild is committed the instant it is tapped, but the card is held on that player's device
     * until they pick a colour - so nothing reaches the engine and the clock carries on running
     * against somebody who has already acted, then times them out mid-choice. The clock pauses
     * while this is set.
     *
     * Host-authoritative like everything else here: a guest's picker is invisible from this phone,
     * so the guest announces it with [NetworkMessage.ChoosingColor].
     */
    private var colorPickPendingFor: String? = null
    private var colorPickCapJob: Job? = null
    private var disconnectCountdownJob: Job? = null
    private var turnTimerJob: Job? = null
    private var botJob: Job? = null
    private var reconnectJob: Job? = null

    // Supplied by initializeAsClient (ultimately LobbyViewModel.attemptReconnect) - see
    // handleClientDisconnected. Defaults to always-fail so a host or a test never accidentally
    // tries to "reconnect" a connection that was never a client connection to begin with.
    private var reconnectAttemptFn: suspend () -> GameClient? = { null }

    private val _reconnecting = MutableStateFlow(false)
    /** True while handleClientDisconnected's retry loop is running - drive a "Reconnecting…" UI off this. */
    val reconnecting: StateFlow<Boolean> = _reconnecting

    private val _reconnectAttemptNumber = MutableStateFlow(0)
    val reconnectAttemptNumber: StateFlow<Int> = _reconnectAttemptNumber

    // initializeAsHost/initializeAsClient run again on every "Play Again" rematch, reusing the
    // SAME GameServer/GameClient instance (it isn't torn down between rounds - only leaveLobby()
    // does that). Without cancelling the previous round's collectors first, each rematch would
    // stack another listener onto the same messages flow, so by round 2 every incoming message
    // gets processed twice (a card play could get applied twice, or race and spuriously look
    // "too late" the second time it's seen).
    private var hostMessagesJob: Job? = null
    private var hostConnectionEventsJob: Job? = null
    private var clientMessagesJob: Job? = null
    private var clientDisconnectJob: Job? = null

    /**
     * @param server null for solo play, where no socket is ever opened. Every use of gameServer in
     *   this class is already a null-safe call, because a client's copy has none either - so a solo
     *   host simply broadcasts to nobody, and the engine, the turn clock and the bot driver all run
     *   unchanged.
     */
    fun initializeAsHost(
        settings: GameSettings,
        players: List<PlayerInfo>,
        localPlayerId: String,
        server: GameServer?
    ) {
        // Clear any leftover end-of-round state before the new round. Critical for rematches: the
        // GAME screen navigates to END whenever gameOver is true, so starting a round with a stale
        // true bounces the player straight back out of the game they just joined.
        resetGame()
        _isHost.value = true
        _localPlayerId.value = localPlayerId
        gameServer = server

        // Carry isConnected across. Player.isConnected defaults to true, so dropping it here turned
        // anyone who disconnected in the lobby into a live player in the engine: dealt a hand, never
        // skipped by getNextPlayerFrom, and handed a turn forever - each one burning the full 20s
        // timer before auto-drawing, because nobody is on the other end to act.
        val gamePlayers = players.map { playerInfo ->
            Player(
                id = playerInfo.id,
                name = playerInfo.name,
                avatarColor = playerInfo.avatarColor,
                isHost = playerInfo.isHost,
                isConnected = playerInfo.isConnected,
                isBot = playerInfo.isBot
            )
        }

        // On a debug build the round is dealt from a recorded seed and every move is written down,
        // so a bug seen once here can be replayed exactly on a desktop JVM. See SeedJournal.
        // Release builds get the platform RNG and no journal, and pay nothing for this.
        val journal = if (BuildConfig.DEBUG) SeedJournal(Random.nextLong()) else null
        _seedJournal.value = journal
        // If the process dies mid-round, the report carries the round with it.
        CrashReporter.extraContext = journal?.let { { it.dump() } }
        gameEngine = GameEngine(
            settings,
            journal?.let { Random(it.seed) } ?: Random,
            journal
        )
        val state = gameEngine!!.initializeGame(gamePlayers)
        applyHostState(state, resetTurnTimer = true)
        journal?.let { announceSeed(it) }

        // Listen for player messages. Deliberately NOT Dispatchers.IO: handlePlayerMessage feeds
        // straight into gameEngine, a plain unsynchronized `var state` with no lock of its own.
        // The turn timer, the disconnect countdown, and every direct UI action (playCard/drawCard/
        // etc., called synchronously from Compose click handlers) all touch the same engine on
        // viewModelScope's default (Main) dispatcher - running this collector on IO would let a
        // client's message race a host-side action from a different thread with no synchronization
        // at all. Nothing in handlePlayerMessage does I/O itself, so there's no cost to keeping it
        // on Main alongside everything else that mutates the engine.
        // Both collectors are cancelled unconditionally, even in solo where they're never started -
        // a rematch reuses this ViewModel, so a solo round following a networked one must not leave
        // the previous round's listeners attached to a server it no longer plays on.
        hostMessagesJob?.cancel()
        hostConnectionEventsJob?.cancel()
        if (server == null) return   // solo: nothing to listen to

        hostMessagesJob = viewModelScope.launch {
            server.messages.collect { (playerId, message) ->
                handlePlayerMessage(playerId, message)
            }
        }

        // Listen for mid-game disconnects so a dropped player can't stall the whole game.
        // Same reasoning as hostMessagesJob above - handlePlayerDisconnected mutates gameEngine too.
        hostConnectionEventsJob = viewModelScope.launch {
            server.connectionEvents.collect { event ->
                if (event is GameServer.ConnectionEvent.Disconnected) {
                    handlePlayerDisconnected(event.clientId)
                }
            }
        }
    }

    /**
     * @param reconnect Called (possibly several times) after this device's own connection to the
     *   host dies mid-round - see handleClientDisconnected. Owned by LobbyViewModel, which is the
     *   thing that actually remembers host/port/pin; GameViewModel just calls it. Defaults to a
     *   no-op so solo/host callers (which never call initializeAsClient at all, but keeps this
     *   safe if that ever changes) and tests don't need to supply one.
     */
    fun initializeAsClient(
        localPlayerId: String,
        client: GameClient,
        players: List<PlayerInfo>,
        settings: GameSettings,
        reconnect: suspend () -> GameClient? = { null }
    ) {
        // See initializeAsHost - without this a client's leftover gameOver=true kicks them out of
        // a rematch instantly, which made rematches unplayable for everyone except the host.
        resetGame()
        _isHost.value = false
        _localPlayerId.value = localPlayerId
        reconnectAttemptFn = reconnect

        // Build a FRESH GameState rather than copying the previous one - a .copy() carried the last
        // round's phase (ROUND_OVER) and winnerId over into the new game, until the first
        // StateUpdate happened to overwrite them.
        _gameState.value = GameState(
            settings = settings,
            players = players.map { info ->
                Player(
                    id = info.id,
                    name = info.name,
                    avatarColor = info.avatarColor,
                    isHost = info.isHost,
                    // Label only, on this side. Clients never drive a bot; the host does.
                    isBot = info.isBot
                )
            }
        )

        attachClientMessages(client)
    }

    /**
     * Wires a GameClient's two flows up to this ViewModel - the normal message stream and the
     * disconnect signal. Split out from initializeAsClient so a successful reconnect (a brand new
     * GameClient instance, see handleClientDisconnected) can re-run just this part without
     * re-running the state-wiping setup above, which would throw away everything this player's
     * board currently shows while they wait for the next StateUpdate to repopulate it.
     */
    private fun attachClientMessages(client: GameClient) {
        gameClient = client

        clientMessagesJob?.cancel()
        clientMessagesJob = viewModelScope.launch(Dispatchers.IO) {
            client.messages.collect { message ->
                handleServerMessage(message)
            }
        }

        // The client-side equivalent of hostConnectionEventsJob above. Before the heartbeat/socket
        // timeout in GameClient, a dead connection to the host was invisible here: the screen just
        // froze on stale state forever with no error and no way back except a Leave button that
        // itself tried to write to an already-dead socket.
        clientDisconnectJob?.cancel()
        clientDisconnectJob = viewModelScope.launch {
            client.disconnected.collect {
                handleClientDisconnected()
            }
        }
    }

    /**
     * Tries to get this player back into the match before giving up on it. Runs entirely on the
     * board - no navigation, no lobby - since the round itself hasn't gone anywhere: GameEngine
     * paused it the moment the host noticed the drop (see beginDisconnectCountdown) and is holding
     * the player's own hand exactly as it was, waiting up to 30s for them to reappear.
     *
     * A successful attempt here means "the socket connected and we sent a rejoin request" - it does
     * NOT wait for the host's accept/reject, because that response can arrive on any later message
     * and is handled uniformly by the JoinResponse case in handleServerMessage regardless of
     * whether it's replying to this loop or was somehow delayed - simpler and race-free compared to
     * trying to correlate a specific response to a specific attempt.
     */
    private fun handleClientDisconnected() {
        if (_reconnecting.value) return  // a previous drop's loop is already running
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            _reconnecting.value = true
            var recovered: GameClient? = null
            for (attempt in 1..Heartbeat.RECONNECT_MAX_ATTEMPTS) {
                _reconnectAttemptNumber.value = attempt
                recovered = reconnectAttemptFn()
                if (recovered != null) break
                if (attempt < Heartbeat.RECONNECT_MAX_ATTEMPTS) delay(Heartbeat.RECONNECT_RETRY_DELAY_MS)
            }
            _reconnecting.value = false
            val client = recovered
            if (client != null) {
                justReconnected = true
                attachClientMessages(client)
            } else {
                notifyGameEnded("Connection to the host was lost")
            }
        }
    }

    fun playCard(card: Card) {
        if (_isHost.value) {
            // Host plays directly
            if (card.isWildCard()) {
                pendingCard = card
                _showColorPicker.value = true
                // The host is its own player here, so it pauses its own clock directly.
                beginColorPick(_localPlayerId.value)
            } else {
                executePlayCard(_localPlayerId.value, card, null)
            }
        } else {
            // Client sends to server
            if (card.isWildCard()) {
                pendingCard = card
                _showColorPicker.value = true
                // A picker on this phone is invisible to the host, so say so - otherwise the host's
                // clock times this player out while they are still choosing.
                gameClient?.send(
                    NetworkMessage.ChoosingColor(_localPlayerId.value, choosing = true)
                )
            } else {
                gameClient?.send(
                    NetworkMessage.PlayCard(
                        playerId = _localPlayerId.value,
                        card = card
                    )
                )
            }
        }
    }

    /**
     * Hold the turn clock while [playerId] picks a wild's colour.
     *
     * Capped rather than open-ended: a player who opens the picker and then walks away - or whose
     * phone drops - would otherwise hold the round open for everyone with no way to move it on.
     * The cap gives them one more clock's worth and then lets the timeout run exactly as it would
     * have, so the worst case is the behaviour we had before, not a frozen table.
     */
    private fun beginColorPick(playerId: String) {
        if (_gameState.value.currentPlayer?.id != playerId) return
        colorPickPendingFor = playerId
        turnTimerJob?.cancel()
        colorPickCapJob?.cancel()
        val capSeconds = _gameState.value.settings.turnTimeoutSeconds ?: return
        colorPickCapJob = viewModelScope.launch {
            delay(capSeconds * 1000L)
            if (colorPickPendingFor != playerId) return@launch
            endColorPick(playerId)
            restartTurnTimer(_gameState.value)
        }
    }

    private fun endColorPick(playerId: String?) {
        if (playerId != null && colorPickPendingFor != playerId) return
        colorPickPendingFor = null
        colorPickCapJob?.cancel()
        colorPickCapJob = null
    }

    fun chooseColor(color: CardColor) {
        _showColorPicker.value = false
        endColorPick(_localPlayerId.value)
        if (!_isHost.value) {
            // Release the host's pause even if the play itself is somehow dropped.
            gameClient?.send(
                NetworkMessage.ChoosingColor(_localPlayerId.value, choosing = false)
            )
        }
        val card = pendingCard ?: return
        pendingCard = null

        if (_isHost.value) {
            executePlayCard(_localPlayerId.value, card, color)
        } else {
            gameClient?.send(
                NetworkMessage.PlayCard(
                    playerId = _localPlayerId.value,
                    card = card,
                    chosenColor = color
                )
            )
        }
    }

    fun drawCard() {
        if (_isHost.value) {
            val state = gameEngine?.drawCardForPlayer(_localPlayerId.value) ?: return
            applyHostState(state, resetTurnTimer = true)
        } else {
            gameClient?.send(
                NetworkMessage.DrawCard(playerId = _localPlayerId.value)
            )
        }
    }

    /** Completes this player's 7's swap against [targetPlayerId]. */
    fun chooseSwapTarget(targetPlayerId: String) {
        if (_isHost.value) {
            val state = gameEngine?.chooseSwapTarget(_localPlayerId.value, targetPlayerId) ?: return
            applyHostState(state, resetTurnTimer = true)
        } else {
            gameClient?.send(NetworkMessage.ChooseSwapTarget(targetPlayerId = targetPlayerId))
        }
    }

    fun callUno() {
        if (_isHost.value) {
            // No announcement here - applyHostState detects it by diffing, the same way clients
            // do, so a call by ANY player is announced on the host (not just the host's own).
            val state = gameEngine?.callUno(_localPlayerId.value) ?: return
            applyHostState(state, resetTurnTimer = false)
        } else {
            gameClient?.send(
                NetworkMessage.CallUno(playerId = _localPlayerId.value)
            )
        }
    }

    fun callUnoOn(targetPlayerId: String) {
        if (_isHost.value) {
            val state = gameEngine?.penalizeUnoNotCalled(_localPlayerId.value, targetPlayerId) ?: return
            applyHostState(state, resetTurnTimer = false)
        } else {
            gameClient?.send(
                NetworkMessage.UnoNotCalled(
                    reporterId = _localPlayerId.value,
                    targetPlayerId = targetPlayerId
                )
            )
        }
    }

    fun sendEmoji(emoji: String) {
        val message = NetworkMessage.EmojiReaction(
            playerId = _localPlayerId.value,
            emoji = emoji
        )
        if (_isHost.value) {
            gameServer?.broadcastToAll(message)
        } else {
            gameClient?.send(message)
        }
        // Show locally immediately so the sender sees their own emoji
        viewModelScope.launch { _emojiEvent.emit(message) }
    }

    /**
     * Called when the player chooses to leave an active match. A client triggers a real
     * disconnect that reuses the exact same knockout path a dropped connection already takes for
     * everyone else still playing - no new behavior for them to see. The host ends the match for
     * everyone instead, since nobody else can take over running the authoritative engine.
     */
    fun leaveGame() {
        // Capture the connections we're actually tearing down. The delayed cleanup below must not
        // read these fields again later: initializeAsHost/initializeAsClient can reassign them
        // within the delay window (menu -> create -> start is easily under 150ms), and stopping
        // whatever the field points to *then* would kill the freshly-started game instead.
        val leavingServer = gameServer
        val leavingClient = gameClient

        if (_isHost.value) {
            leavingServer?.broadcastToAll(NetworkMessage.HostEndedGame())
        } else {
            leavingClient?.send(NetworkMessage.PlayerLeft(playerId = _localPlayerId.value))
        }
        disconnectCountdownJob?.cancel()
        turnTimerJob?.cancel()
        botJob?.cancel()
        reconnectJob?.cancel()
        _reconnecting.value = false
        hostMessagesJob?.cancel()
        hostConnectionEventsJob?.cancel()
        clientMessagesJob?.cancel()
        clientDisconnectJob?.cancel()
        gameServer = null
        gameClient = null
        gameEngine = null
        // broadcastToAll/send are fire-and-forget on their own coroutine - tearing the socket down
        // immediately risks cancelling that write before it reaches the wire (same reasoning as
        // LobbyViewModel.leaveLobby()'s delay). Runs on NonCancellable + the application scope
        // rather than viewModelScope so the flush still completes even if the ViewModel is cleared
        // (e.g. the activity finishes) during those 150ms.
        CoroutineScope(Dispatchers.IO).launch {
            delay(150)
            leavingServer?.stop()
            leavingClient?.disconnect()
        }
    }

    private fun executePlayCard(playerId: String, card: Card, chosenColor: CardColor?) {
        val engine = gameEngine ?: return
        if (!engine.canPlayCard(card, playerId)) {
            notifyPlayRejected(playerId, "Too slow — your turn ended")
            return
        }

        val before = engine.getState().sequenceNumber
        val state = engine.playCard(playerId, card, chosenColor)
        // canPlayCard checks turn/phase/playability but NOT hand membership - playCard does that
        // itself and bails by returning the state untouched. An unchanged sequence number then
        // makes applyHostState no-op, so the play used to disappear with no reply at all. Tell them
        // instead: silence looks identical to a dropped connection from the other end.
        if (state.sequenceNumber == before) {
            notifyPlayRejected(playerId, "That card isn't in your hand")
            return
        }
        applyHostState(state, resetTurnTimer = true)
    }

    /** Host-only: tells whichever player's play just got silently dropped why - locally if it
     *  was the host's own play, or over the network if it was a client's. */
    private fun notifyPlayRejected(playerId: String, reason: String) {
        if (playerId == _localPlayerId.value) {
            viewModelScope.launch { _playRejectedEvent.emit(reason) }
        } else {
            gameServer?.sendToClient(playerId, NetworkMessage.PlayRejected(reason))
        }
    }

    /**
     * A JoinRequest arriving mid-round, from GameServer's perspective, is indistinguishable from a
     * brand new join - it's just a message on a freshly re-keyed connection. LobbyViewModel's own
     * collector already knows to stay silent once gameStarted (see its own comment), so this is the
     * ONLY place that responds. Accepted only if [playerId] is the exact seat GameEngine is
     * currently waiting on - see GameEngine.reconnectPlayer for why a seat already knocked out
     * can't come back this way.
     */
    private fun handleRejoinRequest(playerId: String) {
        if (!_isHost.value) return
        val engine = gameEngine ?: return
        val before = engine.getState().sequenceNumber
        val resumed = engine.reconnectPlayer(playerId)
        if (resumed.sequenceNumber == before) {
            // Two very different people land here and they deserve different answers. Someone who
            // was never at this table is a stranger whose Join tap found a match already underway;
            // someone who IS on the roster tried to resume a seat that can no longer be resumed
            // (knocked out, or their grace window already expired). Telling a first-time joiner
            // they "can't rejoin" describes something they never did.
            val wasInThisMatch = engine.getState().getPlayerById(playerId) != null
            gameServer?.sendToClient(
                playerId,
                NetworkMessage.JoinResponse(
                    accepted = false,
                    reason = if (wasInThisMatch) "Can't rejoin — your seat is gone"
                    else "Game already in progress"
                )
            )
            return
        }
        // Accept BEFORE broadcasting the resumed state - applyHostState's broadcastStateToAll is
        // what actually catches this player up (their own hand, current turn, everything), and it
        // can only reach them once GameServer's clients map has them registered under this
        // playerId, which happened when their JoinRequest arrived. Order here doesn't affect that,
        // but sending the acceptance first means their own UI can leave any "reconnecting" state
        // the instant the response lands rather than waiting on the state broadcast right behind it.
        gameServer?.sendToClient(playerId, NetworkMessage.JoinResponse(accepted = true, assignedId = playerId))
        applyHostState(resumed, resetTurnTimer = true)
    }

    private fun handlePlayerDisconnected(playerId: String) {
        val engine = gameEngine ?: return
        val wasAlreadyPaused = engine.getState().phase == GamePhase.PAUSED_DISCONNECT

        val state = engine.beginDisconnectCountdown(playerId)
        applyHostState(state, resetTurnTimer = true)

        if (!wasAlreadyPaused && state.phase == GamePhase.PAUSED_DISCONNECT) {
            startDisconnectCountdownLoop()
        }
    }

    private fun startDisconnectCountdownLoop() {
        disconnectCountdownJob?.cancel()
        disconnectCountdownJob = viewModelScope.launch {
            while (_gameState.value.phase == GamePhase.PAUSED_DISCONNECT) {
                delay(1000)
                val engine = gameEngine ?: break
                val state = engine.tickDisconnectCountdown()
                applyHostState(state, resetTurnTimer = true)

                if (state.phase == GamePhase.ROUND_OVER) break
            }
        }
    }

    /**
     * Single funnel for every host-side state mutation: stores the result, updates playable
     * cards, broadcasts (or ends the game), and keeps the turn timer in sync. Turn-progressing
     * actions (play/draw/challenge/disconnect handling) pass resetTurnTimer=true so a fresh
     * window starts for whoever is current; side actions that don't move the turn forward
     * (calling UNO, penalizing a missed UNO) pass false so they don't extend anyone's clock.
     */
    private fun applyHostState(newState: GameState, resetTurnTimer: Boolean) {
        val prevState = _gameState.value
        // Every GameEngine method increments sequenceNumber on any real mutation and returns the
        // same unchanged state otherwise (e.g. a draw/challenge that arrives after the turn
        // already moved on). An unchanged sequence means nothing happened - skip it entirely so
        // a stale action can't hand the actual current player an undeserved fresh timer window
        // or trigger a pointless broadcast.
        if (newState.sequenceNumber == prevState.sequenceNumber) return
        // CHOOSING_SWAP is stamped as well as PLAYING, and leaving it out was why a 7 never actually
        // offered its picker.
        //
        // restartTurnTimer clocks CHOOSING_SWAP (it must - an unanswered 7 holds the whole round
        // open) and computes its remaining time as `timeout - (now - turnStartedAtMillis)`. With the
        // stamp frozen at the start of the turn the player just spent choosing what to play, that
        // remainder was already near zero or negative by the time the 7 landed, so the delay was
        // skipped and autoResolveSwap fired in the same instant: the picker flashed for a frame and
        // the biggest hand was taken automatically, which looks exactly like a random swap.
        //
        // Choosing a seat is its own decision and gets its own full window.
        val startsFreshClock =
            newState.phase == GamePhase.PLAYING || newState.phase == GamePhase.CHOOSING_SWAP
        // Always restamped, never passed through: see turnClockStamp for why the engine's own value
        // is meaningless and what passing it through used to break - declaring UNO stopped the
        // clock outright, on every device, and so did catching someone or a BOT doing either.
        val stamped = newState.copy(
            turnStartedAtMillis = turnClockStamp(
                previousStamp = prevState.turnStartedAtMillis,
                startsFreshTurn = resetTurnTimer && startsFreshClock,
                nowMillis = System.currentTimeMillis()
            )
        )
        _gameState.value = stamped
        updatePlayableCards()
        dismissStaleColorPicker(stamped)
        emitMercyKnockouts(prevState, stamped)
        emitUnoCalls(prevState, stamped)
        applyMoveLog(prevState, stamped)

        // Always broadcast the new state, INCLUDING the round-ending one. Previously a ROUND_OVER
        // state skipped straight to handleGameOver(), so the winning move was never sent: clients
        // never saw the winning card land on the discard pile, the winner's count stayed frozen at
        // 1, and their winnerId stayed null - which left the End screen unable to rank the winner
        // first and made a win register as a Mercy knockout. Sends are serialized, so this
        // StateUpdate is guaranteed to reach clients before the GameOver that follows it.
        broadcastStateToAll()
        if (stamped.phase == GamePhase.ROUND_OVER) {
            handleGameOver()
        }

        if (resetTurnTimer) restartTurnTimer(stamped)
        scheduleBotAction(stamped)
    }

    /**
     * Caps a bot's pause against the turn clock in force for this round, so slowing bots down can
     * never push them past the clock that is meant to catch a player who has stopped responding.
     * With the clock Off there is nothing to race, and the requested pause stands as-is.
     */
    private fun botPauseFor(state: GameState, requested: Long): Long {
        val clockMillis = state.settings.turnTimeoutSeconds?.times(1000L) ?: return requested
        return requested.coerceAtMost((clockMillis * BOT_THINK_CLOCK_FRACTION).toLong())
    }

    /**
     * Host-side driver for bot seats. Scheduled off every state change rather than looping, so a
     * bot is simply "whatever the table is waiting on next" - there is no separate bot turn order
     * to keep in step with the engine's.
     *
     * Performs at most ONE action per call and then returns: acting mutates state, which runs
     * applyHostState, which schedules the next one. A chain of bots therefore unwinds as a series
     * of independent, individually-cancellable steps instead of one long coroutine holding the
     * round open.
     *
     * The turn timer still runs underneath all of this. That's deliberate belt-and-braces: if a bot
     * ever failed to produce a legal move, the clock auto-draws for it exactly as it would for a
     * human who walked away, and the round continues.
     */
    private fun scheduleBotAction(state: GameState) {
        botJob?.cancel()
        if (!_isHost.value) return
        if (state.phase != GamePhase.PLAYING && state.phase != GamePhase.CHOOSING_SWAP) return

        // An UNO call is owed by a hand, not by a seat, so it's checked before the turn: a bot that
        // played down to one card must call even though the turn has already moved on.
        //
        // Only while the round is actually running, though. GameEngine.callUno bails unless the
        // phase is PLAYING, so paying a debt during a pending 7's swap returns the state untouched -
        // and since this driver is scheduled off state CHANGES, a no-op means nothing reschedules
        // it. With a turn clock that's a stall until the clock rescues the table; with the clock set
        // to Off it's a round that never continues.
        // A bot about to play its second-to-last card declares first - that is now when the call
        // is legal (see GameEngine.callUno). Only the seat whose turn it is: a declaration is made
        // in front of the play it belongs to, not whenever a hand happens to reach two.
        val unoDeclarer = if (state.phase == GamePhase.PLAYING) {
            state.currentPlayer?.takeIf { BotBrain.shouldDeclareUno(it) }
        } else null

        // Someone already down to one card who never declared. Bots hold humans to the rule as
        // well as each other - in a solo game there is nobody else to do it, and a rule the player
        // alone is exempt from is not a rule.
        val unoTarget = if (state.phase == GamePhase.PLAYING && unoDeclarer == null) {
            val catcher = state.players.firstOrNull { it.isBot && it.isConnected }
            val victim = state.players.firstOrNull {
                BotBrain.canBeCaughtForUno(it) && it.id != catcher?.id
            }
            if (catcher != null && victim != null) catcher.id to victim.id else null
        } else null

        val actorId = if (state.phase == GamePhase.CHOOSING_SWAP) {
            state.pendingSwapPlayerId
        } else {
            state.currentPlayer?.id
        }
        val actor = actorId?.let { state.getPlayerById(it) }?.takeIf { it.isBot && it.isConnected }

        if (unoDeclarer == null && unoTarget == null && actor == null) return

        // Anchors this decision to the exact state it was made from. Anything that moves the game
        // on - a human playing, the turn clock firing, a disconnect - bumps the sequence and makes
        // the pending action stale rather than letting it apply to a table that has since changed.
        val stamp = state.sequenceNumber

        botJob = viewModelScope.launch {
            if (unoDeclarer != null) {
                // A visible beat before the play it belongs to, so the declaration reads as its
                // own move rather than as part of the card landing.
                delay(botPauseFor(state, BOT_UNO_CALL_DELAY_MS))
                val engine = gameEngine ?: return@launch
                if (_gameState.value.sequenceNumber != stamp) return@launch
                // Last statement: applyHostState re-enters scheduleBotAction, which cancels this
                // very job. Same hazard the turn timer documents.
                applyHostState(engine.callUno(unoDeclarer.id), resetTurnTimer = false)
                return@launch
            }

            if (unoTarget != null) {
                val (catcherId, victimId) = unoTarget
                // A real pause, not a formality: this is the window in which the human gets to
                // catch someone first. Pouncing instantly would take the mechanic away from the
                // player and hand it entirely to the bots.
                delay(botPauseFor(state, BOT_UNO_CALL_DELAY_MS))
                val engine = gameEngine ?: return@launch
                if (_gameState.value.sequenceNumber != stamp) return@launch
                applyHostState(
                    engine.penalizeUnoNotCalled(catcherId, victimId),
                    resetTurnTimer = false
                )
                return@launch
            }

            val bot = actor ?: return@launch
            delay(botPauseFor(state, BOT_THINK_DELAY_MS.random()))
            val engine = gameEngine ?: return@launch
            if (_gameState.value.sequenceNumber != stamp) return@launch

            val result = if (state.phase == GamePhase.CHOOSING_SWAP) {
                val target = BotBrain.chooseSwapTarget(_gameState.value, bot.id)
                // No legal seat to trade with - hand it to the engine's own auto-resolve rather
                // than leaving CHOOSING_SWAP pinned open waiting on a bot that can't choose.
                if (target == null) engine.autoResolveSwap()
                else engine.chooseSwapTarget(bot.id, target)
            } else {
                val playable = engine.getPlayableCards(bot.id)
                if (playable.isEmpty()) {
                    engine.drawCardForPlayer(bot.id)
                } else {
                    val card = BotBrain.chooseCard(_gameState.value, bot.id, playable)
                    val color = if (card.isWildCard()) {
                        // The wild itself is excluded: a hand of nothing but wilds shouldn't count
                        // as evidence for any colour.
                        BotBrain.chooseColor(
                            bot.hand.filter { it.id != card.id },
                            _gameState.value.currentColor
                        )
                    } else null
                    engine.playCard(bot.id, card, color)
                }
            }
            applyHostState(result, resetTurnTimer = true)
        }
    }

    /**
     * A Mercy-rule knockout (No Mercy mode, 25+ cards) empties a player's hand - unlike winning,
     * which also empties a hand but makes that player GameState.winnerId. That's the signal we
     * use to detect one, without needing a dedicated network message: no card-count bookkeeping
     * exists for it today, so this stays a pure derived comparison on data already transmitted
     * (card counts), computed identically on host and client.
     *
     * Requiring prevState.phase == PLAYING (rather than requiring newState.phase == PLAYING too)
     * lets this still fire when a knockout is also the one that ends the round - e.g. dropping
     * to 1 connected player - while still excluding disconnect-driven knockouts, which transition
     * from PAUSED_DISCONNECT and would never have had prevState.phase == PLAYING.
     */
    private fun emitMercyKnockouts(prevState: GameState, newState: GameState) {
        if (prevState.phase != GamePhase.PLAYING) return
        if (newState.phase != GamePhase.PLAYING && newState.phase != GamePhase.ROUND_OVER) return
        newState.players.forEach { player ->
            if (player.id == newState.winnerId) return@forEach // emptying your hand to win isn't a knockout
            val prevPlayer = prevState.getPlayerById(player.id) ?: return@forEach
            if (prevPlayer.cardCount > 0 && player.cardCount == 0) {
                viewModelScope.launch { _knockoutEvent.emit(player.name) }
            }
        }
    }

    /**
     * Announces any player who just called UNO, detected by diffing hasCalledUno. Shared by the
     * host funnel and the client's StateUpdate handler so both ends announce the same calls -
     * previously the host only announced its OWN call, so a client calling UNO was silent there.
     */
    /**
     * Derives move-log lines by diffing two states. Runs on host and client alike, over data both
     * already have, so the log needs no messages of its own and cannot drift from the board.
     *
     * Only the top card actually identifies a play, so a turn where the discard pile didn't change
     * is reported as a draw rather than guessed at.
     */
    private fun applyMoveLog(prevState: GameState, newState: GameState) {
        if (newState.sequenceNumber == prevState.sequenceNumber) return

        // The diff assumes prevState and newState are CONSECUTIVE - "actor" is read as whoever
        // prevState says is current, which only identifies the real player if exactly one action
        // happened between them. Two situations break that, and they are detected differently.
        //
        // A fresh join: prevState is still the blank default, so there is no history to diff and
        // nothing worth logging - a device that just arrived has not "missed" anything.
        if (prevState.topCard == null) return

        // A reconnect that skipped broadcasts: flagged explicitly by the code that reconnected,
        // NOT inferred from the sequence number. Sequence counts engine state transitions rather
        // than broadcasts, and one play routinely produces two or three, so "jumped by more than
        // one" matched every ordinary play - which is why this line used to appear on a solo table
        // with no network anywhere near it, crowding out the moves it was meant to sit beside.
        if (justReconnected) {
            justReconnected = false
            _moveLog.value = (listOf(MoveLogEntry("Reconnected — catching up", MoveLogTint.NEUTRAL))
                    + _moveLog.value).take(MOVE_LOG_LIMIT)
            return
        }

        val entries = mutableListOf<MoveLogEntry>()

        val prevTop = prevState.topCard
        val newTop = newState.topCard
        val actor = prevState.currentPlayer?.name

        if (newTop != null && newTop.id != prevTop?.id && actor != null) {
            entries += MoveLogEntry(
                text = "$actor played ${newTop.displayName()}",
                tint = MoveLogTint.forCardColor(newState.currentColor)
            )
        } else if (actor != null && newState.currentPlayerIndex != prevState.currentPlayerIndex) {
            val before = prevState.getPlayerById(prevState.currentPlayer?.id ?: "")?.cardCount ?: 0
            val after = newState.getPlayerById(prevState.currentPlayer?.id ?: "")?.cardCount ?: 0
            if (after > before) entries += MoveLogEntry("$actor drew ${after - before}", MoveLogTint.NEUTRAL)
        }

        newState.players.forEach { player ->
            val prevPlayer = prevState.getPlayerById(player.id) ?: return@forEach
            if (player.hasCalledUno && !prevPlayer.hasCalledUno) {
                entries += MoveLogEntry("${player.name} called UNO", MoveLogTint.WARN)
            }
            if (prevPlayer.isConnected && !player.isConnected) {
                entries += MoveLogEntry("${player.name} is out", MoveLogTint.DANGER)
            }
        }

        if (entries.isEmpty()) return
        _moveLog.value = (entries.asReversed() + _moveLog.value).take(MOVE_LOG_LIMIT)
    }

    private fun emitUnoCalls(prevState: GameState, newState: GameState) {
        newState.players.forEach { player ->
            val prevPlayer = prevState.getPlayerById(player.id) ?: return@forEach
            if (player.hasCalledUno && !prevPlayer.hasCalledUno) {
                viewModelScope.launch { _unoAnnouncement.emit(player.name) }
            }
        }
    }

    /**
     * Host-only: auto-draws for the current player if they don't act within the turn timeout.
     * settings.turnTimeoutSeconds is the host's per-lobby "turn clock" pick (HANDOFF_MENUS.md);
     * null means Off, and this simply never schedules an auto-draw in that case.
     */
    private fun restartTurnTimer(state: GameState) {
        turnTimerJob?.cancel()
        // CHOOSING_SWAP is timed too. Without it a player who plays a 7 and then never picks a
        // seat - or backgrounds the app, or drops - would hold the entire round open forever,
        // because nobody else may act while the swap is pending.
        val phase = state.phase
        if (phase != GamePhase.PLAYING && phase != GamePhase.CHOOSING_SWAP) return
        val timeoutSeconds = state.settings.turnTimeoutSeconds ?: return
        val playerId = state.currentPlayer?.id ?: return
        // Paused mid-colour-choice: they have already committed a card, so the clock has nothing
        // left to hurry. beginColorPick owns the cap that stops this being open-ended.
        if (colorPickPendingFor == playerId) return
        val playerName = state.currentPlayer?.name ?: return
        val stamp = state.turnStartedAtMillis

        turnTimerJob = viewModelScope.launch {
            val remaining = timeoutSeconds * 1000L - (System.currentTimeMillis() - stamp)
            if (remaining > 0) delay(remaining)
            val current = _gameState.value
            // Bail if the turn already moved on (a real action beat the clock)
            if (current.phase != phase || current.turnStartedAtMillis != stamp) return@launch
            val engine = gameEngine ?: return@launch

            val resolved = if (phase == GamePhase.CHOOSING_SWAP) {
                engine.autoResolveSwap()
            } else {
                // NOT drawCardForPlayer: it declines to draw for anyone holding a playable card,
                // so the commonest timeout of all - a player with a good hand who stepped away -
                // came back unchanged and the table stopped dead. applyTurnTimeout always advances.
                engine.applyTurnTimeout(playerId)
            }
            // Notify + broadcast BEFORE applyHostState: it calls restartTurnTimer again, which
            // cancels turnTimerJob - since that's this very coroutine before reassignment, anything
            // placed after it risks running on an already-cancelled job.
            // What the clock actually did, read off the result rather than re-derived from the
            // rule. The banner used to claim a draw unconditionally, which is wrong on every table
            // running the default Skip; asking the resolved state cannot drift from the engine the
            // way a second copy of the rule would.
            val before = current.getPlayerById(playerId)?.cardCount ?: 0
            val drew = (resolved.getPlayerById(playerId)?.cardCount ?: before) > before
            _timeoutEvent.emit(playerName to drew)
            gameServer?.broadcastToAll(NetworkMessage.TurnTimedOut(playerName, drew))
            applyHostState(resolved, resetTurnTimer = true)
        }
    }

    private fun handlePlayerMessage(playerId: String, message: NetworkMessage) {
        when (message) {
            is NetworkMessage.JoinRequest -> handleRejoinRequest(playerId)
            is NetworkMessage.PlayCard -> {
                // The play IS the end of any colour choice, whether or not the release arrives.
                endColorPick(playerId)
                executePlayCard(playerId, message.card, message.chosenColor)
            }
            is NetworkMessage.ChoosingColor -> {
                // A guest has committed a wild and is picking its colour. Hold the clock for them,
                // exactly as the host does for its own picker - see beginColorPick.
                if (message.choosing) beginColorPick(playerId) else endColorPick(playerId)
            }
            is NetworkMessage.DrawCard -> {
                val state = gameEngine?.drawCardForPlayer(playerId) ?: return
                applyHostState(state, resetTurnTimer = true)
            }
            is NetworkMessage.CallUno -> {
                val state = gameEngine?.callUno(playerId) ?: return
                applyHostState(state, resetTurnTimer = false)
            }
            is NetworkMessage.UnoNotCalled -> {
                // Reporter comes from the CONNECTION (playerId), not message.reporterId, so a
                // client can't claim to be someone still in the round. Same trust boundary every
                // other handler here follows.
                val state = gameEngine?.penalizeUnoNotCalled(playerId, message.targetPlayerId) ?: return
                applyHostState(state, resetTurnTimer = false)
            }
            is NetworkMessage.EmojiReaction -> {
                // Relay to the other clients, and show it on the host too - the host is a player,
                // not just a relay, so without the local emit it never saw anyone else's reaction.
                gameServer?.broadcastExcept(playerId, message)
                viewModelScope.launch { _emojiEvent.emit(message) }
            }
            is NetworkMessage.ChooseSwapTarget -> {
                // chooserId comes from the connection, not the payload - the message only carries
                // WHO they picked, never who they claim to be.
                val state = gameEngine?.chooseSwapTarget(playerId, message.targetPlayerId) ?: return
                applyHostState(state, resetTurnTimer = true)
            }
            is NetworkMessage.PlayerLeft -> {
                // playerId (the resolved connection identity), not message.playerId (client-
                // claimed) - same trust boundary every other handler here already follows.
                val state = gameEngine?.playerLeft(playerId) ?: return
                applyHostState(state, resetTurnTimer = true)
            }
            else -> {}
        }
    }

    private fun handleServerMessage(message: NetworkMessage) {
        when (message) {
            is NetworkMessage.StateUpdate -> {
                // Update local state from server. The rebuild itself lives in StateSync so
                // the host->client round trip can be tested; everything after it is this
                // ViewModel's own business - announcements, the move log, the colour picker.
                val currentState = _gameState.value
                val newState = applyStateUpdate(
                    previous = currentState,
                    message = message,
                    localPlayerId = _localPlayerId.value,
                    nowMillis = System.currentTimeMillis()
                )
                _gameState.value = newState
                computePlayableCardsForClient(newState)
                dismissStaleColorPicker(newState)
                emitMercyKnockouts(currentState, newState)
                emitUnoCalls(currentState, newState)
                applyMoveLog(currentState, newState)
            }
            is NetworkMessage.GameOver -> {
                _gameOver.value = true
                _winnerName.value = message.winnerName
                _scores.value = message.scores
                _durationSeconds.value = message.durationSeconds
            }
            is NetworkMessage.TurnTimedOut -> {
                viewModelScope.launch { _timeoutEvent.emit(message.playerName to message.drewCard) }
            }
            is NetworkMessage.PlayRejected -> {
                viewModelScope.launch { _playRejectedEvent.emit(message.reason) }
            }
            is NetworkMessage.HostEndedGame -> {
                notifyGameEnded(message.reason)
            }
            is NetworkMessage.JoinResponse -> {
                // Only ever meaningful here as the answer to a reconnect attempt - the ORIGINAL
                // join's response is handled entirely by LobbyViewModel, before this ViewModel
                // exists yet. handleClientDisconnected's retry loop already treats a connected
                // socket as success without waiting on this, so a rejection just means the host
                // won't have this seat back (already knocked out, claimed by another connection,
                // the round ended while this device was reconnecting) - nothing left to retry.
                if (!message.accepted) {
                    notifyGameEnded(message.reason ?: "Could not rejoin the match")
                }
            }
            is NetworkMessage.EmojiReaction -> {
                // Was missing entirely, so clients silently discarded every incoming reaction.
                viewModelScope.launch { _emojiEvent.emit(message) }
            }
            else -> {}
        }
    }

    private fun broadcastStateToAll() {
        val state = _gameState.value
        val server = gameServer ?: return

        val now = System.currentTimeMillis()
        state.players.forEach { player ->
            val stateUpdate = buildStateUpdate(state, player, now) ?: return@forEach

            if (player.id != _localPlayerId.value) {
                server.sendToClient(player.id, stateUpdate)
            }
        }
    }

    private fun handleGameOver() {
        saveSeedLog()
        val state = _gameState.value
        val winner = state.getPlayerById(state.winnerId ?: "")
        val scores = gameEngine?.calculateScores() ?: emptyMap()
        val duration = (System.currentTimeMillis() - state.roundStartTime) / 1000

        _gameOver.value = true
        _winnerName.value = winner?.name ?: "Unknown"
        _scores.value = scores
        _durationSeconds.value = duration

        // Broadcast game over to clients
        gameServer?.broadcastToAll(
            NetworkMessage.GameOver(
                winnerId = state.winnerId ?: "",
                winnerName = winner?.name ?: "Unknown",
                scores = scores,
                durationSeconds = duration
            )
        )
    }

    private fun updatePlayableCards() {
        val engine = gameEngine ?: return
        val playable = engine.getPlayableCards(_localPlayerId.value)
        _playableCardIds.value = playable.map { it.id }.toSet()
    }

    private fun computePlayableCardsForClient(state: GameState) {
        val isMyTurn = state.currentPlayer?.id == _localPlayerId.value
        // Mirror GameEngine.canPlayCard's phase guard. Without it, a client whose turn it is when
        // the round pauses (PAUSED_DISCONNECT) still sees live, tappable cards - the host would
        // reject the play and answer with "Too slow - your turn ended", which is both wrong and
        // confusing since their turn hadn't ended at all.
        if (!isMyTurn || state.phase != GamePhase.PLAYING) {
            _playableCardIds.value = emptySet()
            return
        }
        val topCard = state.topCard
        val localPlayer = state.getPlayerById(_localPlayerId.value)
        // Mirror the "last card must be a number" house rule too. Without it a client holding one
        // power card sees it lit and tappable, taps it, and gets the host's rejection - reported as
        // "Too slow - your turn ended", which is doubly wrong: their turn hadn't ended, and the
        // card was never legal to begin with.
        //
        // Matches GameEngine.emptiesHand: the rule blocks a WINNING play, not a card at one card
        // left. Discard All sweeps every card of its own colour out of the hand in one move, so it
        // can win from a hand of three - a `hand.size == 1` test here would light it and hand the
        // player a rejection the moment they tapped it.
        val hand = localPlayer?.hand.orEmpty()
        fun wouldEmptyHand(card: Card): Boolean {
            val remaining = hand.filterNot { it.id == card.id }
            if (remaining.isEmpty()) return true
            if (card.type == CardType.DISCARD_ALL) return remaining.all { it.color == card.color }
            return false
        }
        val lastCardRuleOn = state.settings.rules.lastCardMustBeNumber
        val ids = localPlayer?.hand?.filter { card ->
            if (lastCardRuleOn && !card.isNumberCard() && wouldEmptyHand(card)) return@filter false
            when {
                // Same rule the engine enforces, off the same data - the top card IS the pending
                // penalty. Stacking off means nothing answers it, so no card lights up and the
                // only move is to take it.
                state.pendingDrawCount > 0 ->
                    state.settings.rules.stacking &&
                            topCard != null &&
                            Card.canStack(card.type, topCard.type)
                card.isWildCard() -> true
                topCard == null -> true
                card.color == state.currentColor -> true
                card.type == topCard.type -> true
                else -> false
            }
        }?.map { it.id }?.toSet() ?: emptySet()
        _playableCardIds.value = ids
    }

    /**
     * Milliseconds since the last line successfully read off the socket to the host - real message
     * or heartbeat Ping, either counts. Null for the host (a star topology's host has N connections,
     * not one single link to summarize) or if there's currently no client at all. A plain function
     * rather than a StateFlow deliberately: gameClient itself gets swapped on a successful
     * reconnect, and a UI polling this every second or so on its own timer stays correct across
     * that swap for free, where a StateFlow proxy would need re-subscribing to follow it.
     */
    fun connectionAgeMs(): Long? {
        if (_isHost.value) return null
        val client = gameClient ?: return null
        return System.currentTimeMillis() - client.lastMessageAt.value
    }

    /**
     * Put the round's seed somewhere it can actually be retrieved, three ways over, because the
     * three situations that need it are reached by different routes.
     *
     * Logcat is for a phone on a cable; the file is for a phone that is not, and is rewritten as the
     * round goes so it survives being killed rather than crashing; the move-log line is for the
     * player, who otherwise has no idea a seed exists and cannot quote one in a bug report. All
     * three are debug-only - [SeedJournal] is never created on a release build.
     */
    private fun announceSeed(journal: SeedJournal) {
        android.util.Log.i(SEED_TAG, "round dealt from seed ${journal.seed}")
        _moveLog.value = (listOf(MoveLogEntry("seed ${journal.seed}", MoveLogTint.NEUTRAL))
                + _moveLog.value).take(MOVE_LOG_LIMIT)
    }

    /**
     * Write the current round's replay log to the app's private storage, overwriting the previous
     * one. Called at the end of a round and when the game is torn down, which between them cover
     * every way a round ends that isn't a crash - and a crash carries the log in its own report.
     *
     * Failure is ignored on purpose: this is a debugging aid, and a full disk must not take a game
     * down with it.
     */
    private fun saveSeedLog() {
        val journal = _seedJournal.value ?: return
        try {
            java.io.File(getApplication<Application>().filesDir, SEED_LOG_FILE)
                .writeText(journal.dump())
        } catch (_: Throwable) {
        }
    }

    fun resetGame() {
        saveSeedLog()
        _moveLog.value = emptyList()
        _gameOver.value = false
        _showColorPicker.value = false
        pendingCard = null
        _gameEndedReason.value = null
        reconnectAttemptFn = { null }
        _reconnecting.value = false
        _reconnectAttemptNumber.value = 0
    }

    override fun onCleared() {
        super.onCleared()
        disconnectCountdownJob?.cancel()
        turnTimerJob?.cancel()
        botJob?.cancel()
        reconnectJob?.cancel()
        hostMessagesJob?.cancel()
        hostConnectionEventsJob?.cancel()
        clientMessagesJob?.cancel()
        clientDisconnectJob?.cancel()
        gameServer?.stop()
        gameClient?.disconnect()
        gameServer = null
        gameClient = null
    }
}

/**
 * Which seat holds the turn, from a host's point of view translated into ours.
 *
 * The only index in the app that crosses a device boundary, which is what makes it worth its own
 * function. [hostIndex] indexes the HOST's roster; using it against a client's roster is only valid
 * while the two lists agree, and the case where [currentPlayerId] cannot be found is precisely the
 * case where they have stopped agreeing - so the raw index is least trustworthy exactly when it
 * used to be trusted. A host list longer than ours points past our end.
 *
 * Order: identity, then the host's index but only if it is genuinely in range for us, then hold the
 * turn where it already was. Never returns an out-of-range value for a non-empty roster.
 */
internal fun resolveTurnIndex(
    players: List<Player>,
    currentPlayerId: String,
    hostIndex: Int,
    previousIndex: Int
): Int {
    if (players.isEmpty()) return 0
    players.indexOfFirst { it.id == currentPlayerId }.let { if (it >= 0) return it }
    if (hostIndex in players.indices) return hostIndex
    return previousIndex.coerceIn(players.indices)
}
