package com.mutsho.localuno.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.MoveLogEntry
import com.mutsho.localuno.ui.components.*
import com.mutsho.localuno.ui.theme.SurfaceVariant
import com.mutsho.localuno.ui.theme.UnoRed

/**
 * Dispatches to the active board skin and owns everything that floats above it - the skin/prefs
 * sheet, the wild picker, the announcement overlays and the leave confirmation.
 *
 * Per UnoBoard.dc.html all three skins share identical overlays and chrome, so they live here
 * rather than being repeated inside each skin.
 */
@Composable
fun UnoBoardScreen(
    gameState: GameState,
    localPlayerId: String,
    playableCardIds: Set<Int>,
    boardSkin: BoardSkin,
    moveLog: List<MoveLogEntry>,
    showSymbols: Boolean,
    sortHandEnabled: Boolean,
    onToggleSortHand: () -> Unit,
    isHost: Boolean,
    onLeaveGame: () -> Unit,
    onPlayCard: (Card) -> Unit,
    onDrawCard: () -> Unit,
    onCallUno: () -> Unit,
    onCallUnoOn: (String) -> Unit,
    onSendEmoji: (String) -> Unit,
    onChooseSwapTarget: (String) -> Unit,
    showColorPicker: Boolean,
    onColorChosen: (CardColor) -> Unit,
    unoAnnouncement: String? = null,
    knockoutAnnouncement: String? = null,
    timeoutAnnouncement: String? = null,
    emojiAnnouncement: String? = null,
    isRoundOver: Boolean = false,
    winnerName: String = "",
    onPlayAgain: () -> Unit = {},
    onFullResults: () -> Unit = {},
    reconnecting: Boolean = false,
    reconnectAttempt: Int = 0,
    getConnectionAgeMs: () -> Long? = { null },
    turnAlertOn: Boolean = true
) {
    var logOpen by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    // Tapping any opponent's avatar names them in full - see SeatNameOverlay. Held here rather
    // than per-skin so all three share one overlay and one dismissal timer.
    var namedSeatId by remember { mutableStateOf<String?>(null) }

    val isMyTurn = gameState.currentPlayer?.id == localPlayerId

    // Until now, being knocked out showed an empty hand and silently stopped handing out turns,
    // with nothing on screen saying why. See GameState.isSpectating.
    val isSpectating = gameState.isSpectating(localPlayerId)

    // "It's your turn" alert. Fires only on the false -> true edge, so it can't re-buzz on a
    // recomposition or a StateUpdate that leaves the turn where it was. Seeded from the CURRENT
    // turn on first composition rather than from false, so arriving on the board (or returning to
    // it after a reconnect) on your own turn doesn't fire a cue for a turn you already had.
    val turnAlert = rememberTurnAlert()
    var wasMyTurn by remember { mutableStateOf(isMyTurn) }
    // Drives the visual announcement off the SAME edge as the haptic, so the two can never
    // disagree about when the turn arrived. Incremented rather than set true/false: the banner
    // needs to replay on every arrival, and a Boolean that is already true says nothing happened.
    var yourTurnTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(isMyTurn, isRoundOver, isSpectating) {
        val eligible = isMyTurn && !isRoundOver && !isSpectating
        if (eligible && !wasMyTurn) {
            if (turnAlertOn) turnAlert()
            yourTurnTrigger++
        }
        wasMyTurn = eligible
    }

    // The shuffle-and-deal, once per round. Latched in rememberSaveable so a rotation (or any
    // other recreation) mid-round doesn't replay a deal that already happened, while a genuine new
    // round - which arrives on a fresh navigation to this screen - gets its own.
    var roundStartConsumed by rememberSaveable { mutableStateOf(false) }
    var showRoundStart by remember { mutableStateOf(false) }
    LaunchedEffect(gameState.phase) {
        // Keyed on the phase becoming live rather than on first composition: a client composes
        // this screen before the host's first StateUpdate lands, so at that point there is no
        // round to announce yet.
        if (!roundStartConsumed && gameState.phase == GamePhase.PLAYING) {
            roundStartConsumed = true
            showRoundStart = true
        }
    }

    val isNoMercyMode = gameState.settings.gameMode == GameMode.NO_MERCY
    val effectiveSkin = if (boardSkin == BoardSkin.NO_MERCY && !isNoMercyMode) BoardSkin.ROUND_TABLE else boardSkin

    // Splash fires whenever the top card changes - the discard's identity IS the "a card was
    // played" signal, and it's already replicated to every device, so this needs no new event.
    var splashTrigger by remember { mutableIntStateOf(0) }
    var splashColor by remember { mutableStateOf(gameState.currentColor) }
    // Seeded with the card already on the pile, so entering the board (or returning to it) doesn't
    // replay a splash for a card that was played before you got here - only genuine changes fire.
    var lastSplashCardId by remember { mutableStateOf(gameState.topCard?.id) }
    val topCardId = gameState.topCard?.id
    LaunchedEffect(topCardId) {
        if (topCardId != null && topCardId != lastSplashCardId) {
            lastSplashCardId = topCardId
            splashColor = gameState.currentColor
            splashTrigger++
        }
    }

    val otherConnectedCount = gameState.players.count { it.isConnected && it.id != localPlayerId }
    val requestLeave = {
        if (isHost && otherConnectedCount > 0) showLeaveConfirm = true else onLeaveGame()
    }
    // Once the round is over there is no match left to abandon, so back must not run the
    // leave-confirm - it would ask the host whether to "end the match for the other players" on a
    // match that has already ended. Back goes to the results instead.
    BackHandler { if (isRoundOver) onFullResults() else requestLeave() }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("Leave game?", color = Color.White) },
            text = {
                Text(
                    "You're the host — leaving will end the match for the $otherConnectedCount other player(s) still here.",
                    color = Color.White.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                TextButton(onClick = { showLeaveConfirm = false; onLeaveGame() }) {
                    Text("Leave", color = UnoRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Cancel", color = Color.White) }
            },
            containerColor = SurfaceVariant
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (effectiveSkin) {
            BoardSkin.ROUND_TABLE -> GameBoardScreen(
                gameState = gameState,
                localPlayerId = localPlayerId,
                playableCardIds = playableCardIds,
                moveLog = moveLog,
                logOpen = logOpen,
                onToggleLog = { logOpen = !logOpen },
                showSymbols = showSymbols,
                onPlayCard = onPlayCard,
                onDrawCard = onDrawCard,
                onCallUno = onCallUno,
                onCallUnoOn = onCallUnoOn,
                onSeatInfo = { namedSeatId = it },
                onSendEmoji = onSendEmoji,
                onRequestLeave = requestLeave,
                sortHandEnabled = sortHandEnabled,
                onToggleSortHand = onToggleSortHand
            )
            BoardSkin.SIDE_RAILS -> SideRailsBoardScreen(
                gameState = gameState,
                localPlayerId = localPlayerId,
                playableCardIds = playableCardIds,
                moveLog = moveLog,
                logOpen = logOpen,
                onToggleLog = { logOpen = !logOpen },
                showSymbols = showSymbols,
                onPlayCard = onPlayCard,
                onDrawCard = onDrawCard,
                onCallUno = onCallUno,
                onCallUnoOn = onCallUnoOn,
                onSeatInfo = { namedSeatId = it },
                onSendEmoji = onSendEmoji,
                onRequestLeave = requestLeave,
                sortHandEnabled = sortHandEnabled,
                onToggleSortHand = onToggleSortHand
            )
            BoardSkin.NO_MERCY -> MercyBoardScreen(
                gameState = gameState,
                localPlayerId = localPlayerId,
                playableCardIds = playableCardIds,
                moveLog = moveLog,
                logOpen = logOpen,
                onToggleLog = { logOpen = !logOpen },
                showSymbols = showSymbols,
                onPlayCard = onPlayCard,
                onDrawCard = onDrawCard,
                onCallUno = onCallUno,
                onCallUnoOn = onCallUnoOn,
                onSeatInfo = { namedSeatId = it },
                onSendEmoji = onSendEmoji,
                onRequestLeave = requestLeave,
                sortHandEnabled = sortHandEnabled,
                onToggleSortHand = onToggleSortHand
            )
        }

        ColorSplashOverlay(trigger = splashTrigger, color = splashColor)
        // Replaces the old SwapAnimationOverlay, which slid two anonymous cards labelled "7" and
        // "?" past each other - it said something happened and nothing about what. This draws the
        // real participants and covers the 0's rotation too, which had no animation at all.
        HandTransferOverlay(gameState = gameState, localPlayerId = localPlayerId)
        YourTurnBanner(
            trigger = yourTurnTrigger,
            // In the open felt below the discard pile and above the turn row - the one band of the
            // board that carries nothing, so the announcement covers neither the card in play nor
            // the hand it is telling the player to use.
            modifier = Modifier.align(Alignment.Center).offset(y = 44.dp)
        )
        UnoBurstOverlay(unoAnnouncement)
        KnockoutOverlay(knockoutAnnouncement, mercyThreshold = gameState.settings.mercyThreshold)
        TimeoutToastOverlay(timeoutAnnouncement)
        EmojiReactionOverlay(emojiAnnouncement)
        SeatNameOverlay(
            name = namedSeatId?.let { id -> gameState.getPlayerById(id)?.name },
            onDismiss = { namedSeatId = null }
        )

        if (isSpectating) {
            SpectatingBanner(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
            )
        }

        DisconnectPauseOverlay(gameState)
        ReconnectingOverlay(
            reconnecting = reconnecting,
            attempt = reconnectAttempt,
            maxAttempts = com.mutsho.localuno.network.Heartbeat.RECONNECT_MAX_ATTEMPTS
        )

        // Own-link health, clients only - a star topology's host has N connections to summarize,
        // not one, so this stays a client-only affordance. Polls rather than observes a Flow: see
        // GameViewModel.connectionAgeMs's own doc for why that's deliberate across a reconnect.
        if (!isHost) {
            var connectionAgeMs by remember { mutableStateOf<Long?>(null) }
            LaunchedEffect(Unit) {
                while (true) {
                    connectionAgeMs = getConnectionAgeMs()
                    delay(1000)
                }
            }
            ConnectionQualityDot(
                ageMs = connectionAgeMs,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 10.dp, end = 14.dp)
            )
        }

        RoundStartOverlay(
            visible = showRoundStart,
            playerCount = gameState.players.count { it.isConnected },
            onFinished = {
                showRoundStart = false
                // Announce the FIRST turn of the round here rather than from the turn edge above.
                // That edge deliberately seeds `wasMyTurn` from the current turn, so arriving on
                // the board already holding the turn produces no edge at all - which silently
                // excluded the one turn most worth announcing, the opening move. Firing on the
                // deal finishing also puts the banner after the deal instead of underneath it.
                if (isMyTurn && !isRoundOver && !isSpectating) yourTurnTrigger++
            }
        )

        if (showColorPicker) {
            ColorPickerDialog(onColorChosen = onColorChosen, showSymbols = showSymbols)
        }

        // 7's swap. Held above everything except the leave dialog, because the round genuinely
        // cannot advance until it resolves.
        if (gameState.phase == GamePhase.CHOOSING_SWAP) {
            val chooserId = gameState.pendingSwapPlayerId
            SwapPickerOverlay(
                isChooser = chooserId == localPlayerId,
                chooserName = gameState.getPlayerById(chooserId ?: "")?.name ?: "A player",
                candidates = gameState.players.filter { it.id != chooserId && it.isConnected },
                onPick = onChooseSwapTarget,
                turnStartedAtMillis = gameState.turnStartedAtMillis,
                turnTimeoutSeconds = gameState.settings.turnTimeoutSeconds
            )
        }

        WinnerOverlay(
            visible = isRoundOver,
            winnerName = winnerName,
            subtitle = if (winnerName.isNotEmpty()) "took the round" else "",
            isHost = isHost,
            onPlayAgain = onPlayAgain,
            onFullResults = onFullResults
        )

    }
}
