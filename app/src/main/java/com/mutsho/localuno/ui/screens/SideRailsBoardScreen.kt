package com.mutsho.localuno.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.model.*
import com.mutsho.localuno.ui.components.*
import com.mutsho.localuno.ui.theme.*

/**
 * Side Rails skin, matched to UnoBoard.dc.html's `isRails` branch - the mockup's own default.
 *
 * Opponents sit in 64dp rails down each edge, the centre carries a 220dp halo behind the
 * draw/discard pair, and beneath it the colour pips, the active-colour caption and the pending
 * stack badge. Shared chrome (top bar, move log, turn row, hand, action bar) comes from
 * BoardChrome/BoardHand so all three skins stay identical where the design says they are.
 */
@Composable
fun SideRailsBoardScreen(
    gameState: GameState,
    localPlayerId: String,
    playableCardIds: Set<Int>,
    moveLog: List<MoveLogEntry>,
    logOpen: Boolean,
    onToggleLog: () -> Unit,
    showSymbols: Boolean,
    onPlayCard: (Card) -> Unit,
    onDrawCard: () -> Unit,
    onCallUno: () -> Unit,
    onCallUnoOn: (String) -> Unit,
    onSeatInfo: (String) -> Unit,
    onSendEmoji: (String) -> Unit,
    onRequestLeave: () -> Unit,
    sortHandEnabled: Boolean,
    onToggleSortHand: () -> Unit
) {
    val localPlayer = gameState.getPlayerById(localPlayerId)
    val isMyTurn = gameState.currentPlayer?.id == localPlayerId
    val opponents = gameState.players.filter { it.id != localPlayerId }
    val leftRail = opponents.filterIndexed { i, _ -> i % 2 == 0 }
    val rightRail = opponents.filterIndexed { i, _ -> i % 2 == 1 }
    val hand = localPlayer?.hand?.let { if (sortHandEnabled) it.sortedForDisplay() else it } ?: emptyList()
    val hasPlays = hand.any { it.id in playableCardIds }

    BoardScaffold(
        gameState = gameState,
        localPlayerId = localPlayerId,
        playableCardIds = playableCardIds,
        hand = hand,
        moveLog = moveLog,
        logOpen = logOpen,
        onToggleLog = onToggleLog,
        showSymbols = showSymbols,
        onPlayCard = onPlayCard,
        onCallUno = onCallUno,
        onSendEmoji = onSendEmoji,
        onRequestLeave = onRequestLeave,
        sortHandEnabled = sortHandEnabled,
        onToggleSortHand = onToggleSortHand,
        background = Brush.verticalGradient(listOf(ChromeGradientTop, Background)),
        timer = {
            val timeout = gameState.settings.turnTimeoutSeconds
            if (gameState.phase == GamePhase.PLAYING && timeout != null) {
                TurnTimerBar(turnStartedAtMillis = gameState.turnStartedAtMillis, totalSeconds = timeout)
            }
        }
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp, top = 8.dp)
        ) {
            RailColumn(leftRail, gameState, onCallUnoOn, onSeatInfo,
                !gameState.isSpectating(localPlayerId), leftEdge = true)

            // ── Centre felt ─────────────────────────────────────────────────
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                // Was a flat 0.22-alpha wash that never changed and never reacted. The aura is
                // stronger, crossfades between colours and flares on a switch - which is the whole
                // point, since the moment that needs announcing is a wild changing the colour.
                ActiveColourAura(
                    color = gameState.currentColor,
                    modifier = Modifier.size(260.dp)
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DrawPile(
                            // Only live when there is genuinely nothing to play - GameEngine.drawCardForPlayer
                            // enforces the same rule, so leaving this enabled would just be a button
                            // that silently does nothing. A live draw stack is the exception: taking
                            // the penalty is a real choice even when you could stack instead.
                            // Always live on your own turn. It used to be gated on having nothing
                            // playable, which made the deck unresponsive exactly when a player was
                            // deciding whether to spend a card or take one - a choice the game
                            // should let them make. GameEngine caps it at one voluntary draw a turn.
                            enabled = isMyTurn,
                            onDraw = onDrawCard,
                            width = 58.dp,
                            height = 86.dp,
                            caption = "${gameState.drawPile.size}",
                            // Only when there is genuinely nothing playable - a pending draw
                            // stack is a choice, not a dead end.
                            mustDraw = isMyTurn && !hasPlays && gameState.pendingDrawCount == 0
                        )
                        DiscardSlot(
                            gameState = gameState,
                            isMyTurn = isMyTurn,
                            showSymbols = showSymbols,
                                                width = 74.dp,
                            height = 108.dp
                        )
                    }

                    // Colour pips - the active colour's pip widens. This skin already names the
                    // active colour in text directly below, so the pips are the one colour-only
                    // element here that is genuinely redundant; the symbol row is added anyway so a
                    // player reading by shape can identify all four at a glance rather than having
                    // to parse a sentence to learn which one is lit.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE).forEach { pip ->
                            val active = gameState.currentColor == pip
                            val w by animateDpAsState(if (active) 26.dp else 8.dp, label = "pipW")
                            if (showSymbols) {
                                ColorSymbol(
                                    color = pip,
                                    size = if (active) 14.dp else 9.dp,
                                    modifier = Modifier.alpha(if (active) 1f else 0.45f)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .width(w)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colorFor(pip).copy(alpha = if (active) 1f else 0.4f))
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        // `ACTIVE COLOUR · YELLOW` in 9sp Neutral600 - dark grey, nine pixels
                        // tall, on a dark felt. It named the right thing and nobody could read it.
                        ActiveColourBanner(
                            color = gameState.currentColor,
                            showSymbols = showSymbols
                        )
                        DirectionGlyph(gameState.direction, size = 12.dp, alpha = 0.7f)
                    }

                    if (gameState.pendingDrawCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.verticalGradient(listOf(UnoRed, Color(0xFF8E0F14))))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "+${gameState.pendingDrawCount}",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 20.sp
                            )
                            Text(
                                stackHint(gameState),
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                }
            }

            RailColumn(rightRail, gameState, onCallUnoOn, onSeatInfo,
                !gameState.isSpectating(localPlayerId), leftEdge = false)
        }
    }
}

/** A 64dp rail of opponent seats. The design marks the active seat with a 3dp edge stripe. */
@Composable
private fun RailColumn(
    seats: List<Player>,
    gameState: GameState,
    onCallUnoOn: (String) -> Unit,
    onSeatInfo: (String) -> Unit,
    viewerCanReport: Boolean,
    leftEdge: Boolean
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .width(64.dp)
            .verticalScroll(rememberScrollState())
    ) {
        seats.forEach { seat ->
            val isTurn = gameState.currentPlayer?.id == seat.id
            // See RoundSeat: a knocked-out viewer must not be offered a catch.
            val canCatch = seat.cardCount == 1 && !seat.hasCalledUno && seat.isConnected &&
                viewerCanReport
            val edge = when {
                !seat.isConnected -> Color.Transparent
                canCatch -> UnoRed
                isTurn -> NocturneAccent
                else -> Color.Transparent
            }
            val avatar = AvatarColors.getOrElse(seat.avatarColor % AvatarColors.size) { AvatarColors[0] }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isTurn) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.04f))
                    .drawEdgeStripe(edge, leftEdge)
                    // Tapping a seat names it in full. When this seat is catchable the tap keeps
                    // its existing, time-critical meaning instead - CATCH is loudly signposted (red
                    // ring plus tag) and only live for a couple of seconds, so the ambiguity is both
                    // visible and brief, where losing the catch window to a name popup would not be.
                    .clickable { if (canCatch) onCallUnoOn(seat.id) else onSeatInfo(seat.id) }
                    .padding(horizontal = 5.dp, vertical = 9.dp)
                    .alphaIf(!seat.isConnected, 0.45f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(avatar, avatar.copy(alpha = 0.72f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        seat.name.take(1).uppercase().ifEmpty { "?" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    if (seat.isBot) {
                        BotBadge(modifier = Modifier.align(Alignment.TopEnd), size = 13.dp)
                    }
                }
                Text(
                    if (seat.name.length > 8) seat.name.take(7) + "…" else seat.name,
                    color = if (isTurn) Color.White else Neutral400,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text("×${seat.cardCount}", color = Neutral300, fontSize = 11.sp)
                Box(modifier = Modifier.height(12.dp)) {
                    val tag = when {
                        !seat.isConnected -> "OUT"
                        seat.hasCalledUno -> "UNO!"
                        canCatch -> "CATCH"
                        else -> ""
                    }
                    if (tag.isNotEmpty()) {
                        Text(
                            tag,
                            color = if (tag == "OUT") UnoRed else NocturneAccent,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}
