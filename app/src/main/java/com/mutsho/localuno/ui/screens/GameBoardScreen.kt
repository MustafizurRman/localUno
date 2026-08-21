package com.mutsho.localuno.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.model.*
import com.mutsho.localuno.ui.components.LocalAmbientMotion
import com.mutsho.localuno.ui.components.rememberSweepAngle
import com.mutsho.localuno.ui.components.*
import com.mutsho.localuno.ui.theme.*

/**
 * Round Table skin, matched to UnoBoard.dc.html's `isRound` branch.
 *
 * A felt slab with a slow conic light sweep, opponents seated around its far arc, and a centre
 * cluster of draw pile / discard (tilted -3°) / active-colour orb.
 */
@Composable
fun GameBoardScreen(
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
                TurnTimerRing(turnStartedAtMillis = gameState.turnStartedAtMillis, totalSeconds = timeout)
            }
        }
    ) {
        val feltGrain = rememberFeltGrain()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp, top = 6.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.radialGradient(listOf(Color(0xFF1E7A49), Color(0xFF0C2B1D)))
                )
                .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(26.dp))
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(feltGrain, alpha = FELT_GRAIN_ALPHA)
            )

            // Slow conic light sweep - 9s linear, per the mockup.
            //
            // State, not `by` - read inside the Canvas draw lambda below. Delegated, this rotated
            // the felt by recomposing the board every frame for the entire game.
            //
            // Gated now as well: this used to turn for the whole round whether or not anybody was
            // watching, which on a four-player table is most of it. See LocalAmbientMotion.
            val angle = rememberSweepAngle(active = LocalAmbientMotion.current, periodMs = 9000)
            Canvas(modifier = Modifier.matchParentSize()) {
                rotate(angle.value) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0.00f to Color.White.copy(alpha = 0f),
                            0.11f to Color.White.copy(alpha = 0.10f),
                            0.22f to Color.White.copy(alpha = 0f),
                            1.00f to Color.White.copy(alpha = 0f)
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = true
                    )
                }
            }

            if (opponents.isEmpty()) {
                Text(
                    "Waiting for other players…",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                )
            } else {
                opponents.forEachIndexed { k, seat ->
                    val (fx, fy) = seatPositionFraction(k, opponents.size)
                    RoundSeat(
                        seat = seat,
                        isCurrentTurn = gameState.currentPlayer?.id == seat.id,
                        viewerCanReport = !gameState.isSpectating(localPlayerId),
                        onCallUnoOn = { onCallUnoOn(seat.id) },
                        onSeatInfo = { onSeatInfo(seat.id) },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = maxWidth * fx - 39.dp, y = maxHeight * fy - 39.dp)
                    )
                }
            }

            ActiveColourAura(
                color = gameState.currentColor,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = maxHeight * 0.52f - 160.dp)
                    .size(290.dp)
            )

            // Centre cluster at 52% height.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = maxHeight * 0.52f - 50.dp)
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
                    width = 66.dp,
                    height = 98.dp,
                    caption = "${gameState.drawPile.size} left",
                    // Only when there is genuinely nothing playable - a pending
                    // draw stack is a choice, not a dead end.
                    mustDraw = isMyTurn && !hasPlays && gameState.pendingDrawCount == 0
                )
                DiscardSlot(
                    gameState = gameState,
                    isMyTurn = isMyTurn,
                    showSymbols = showSymbols,
                                width = 70.dp,
                    height = 100.dp,
                    rotationDeg = -3f
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Was a 40dp orb under an 8sp "COLOUR" caption. A circle states the colour
                    // without naming it, and at 8sp the caption explaining it was unreadable in
                    // play - so the whole thing only worked if you already knew what it meant.
                    ActiveColourBanner(
                        color = gameState.currentColor,
                        showSymbols = showSymbols
                    )
                    DirectionGlyph(gameState.direction, size = 16.dp, alpha = 0.75f)
                }
            }

            if (gameState.pendingDrawCount > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Brush.verticalGradient(listOf(UnoRed, Color(0xFF8E0F14))))
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Text(
                        "+${gameState.pendingDrawCount}",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 24.sp
                    )
                    Column {
                        Text(
                            "STACK LIVE",
                            color = Color.White,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(stackHint(gameState), color = Neutral300, fontSize = 9.sp)
                    }
                }
            }

        }
    }
}

/** Circular seat with a count badge, per the round-table branch. */
@Composable
private fun RoundSeat(
    seat: Player,
    isCurrentTurn: Boolean,
    /** False once the viewer is knocked out - see the canCatch note below. */
    viewerCanReport: Boolean,
    onCallUnoOn: () -> Unit,
    onSeatInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    // A knocked-out player watches the rest of the round but must not be able to change it -
    // the engine rejects their catch anyway, so without this the seat lights up as CATCH and
    // then does nothing when tapped.
    val canCatch = seat.cardCount == 1 && !seat.hasCalledUno && seat.isConnected && viewerCanReport
    val avatar = AvatarColors.getOrElse(seat.avatarColor % AvatarColors.size) { AvatarColors[0] }
    val ring = when {
        !seat.isConnected -> Color.White.copy(alpha = 0.08f)
        canCatch -> UnoRed
        isCurrentTurn -> NocturneAccent
        else -> Color.White.copy(alpha = 0.12f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.width(78.dp).alphaIf(!seat.isConnected, 0.45f)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .border(3.dp, ring, CircleShape)
                .padding(3.dp)
                    // Tapping a seat names it in full. When this seat is catchable the tap keeps
                    // its existing, time-critical meaning instead - CATCH is loudly signposted (red
                    // ring plus tag) and only live for a couple of seconds, so the ambiguity is both
                    // visible and brief, where losing the catch window to a name popup would not be.
                .clickable { if (canCatch) onCallUnoOn() else onSeatInfo() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(avatar, avatar.copy(alpha = 0.72f)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    seat.name.take(1).uppercase().ifEmpty { "?" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Neutral900)
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 1.dp)
            ) {
                Text("${seat.cardCount}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (seat.isBot) {
                BotBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        Text(
            if (seat.name.length > 9) seat.name.take(8) + "…" else seat.name,
            color = if (isCurrentTurn) Color.White else Neutral400,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp)
        )
        Box(modifier = Modifier.height(14.dp)) {
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
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}
