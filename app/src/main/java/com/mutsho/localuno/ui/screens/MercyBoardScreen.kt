package com.mutsho.localuno.ui.screens

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import com.mutsho.localuno.ui.components.ColorSymbol
import com.mutsho.localuno.ui.components.DirectionGlyph
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.model.*
import com.mutsho.localuno.ui.components.*
import com.mutsho.localuno.ui.theme.*

/**
 * No Mercy skin, matched to UnoBoard.dc.html's `isMercy` branch.
 *
 * Red felt, seats carrying knockout meters instead of plain counts, a stacking tower behind the
 * discard, and an oversized +N when a draw stack is live.
 */
@Composable
fun MercyBoardScreen(
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
        background = Brush.verticalGradient(listOf(Color(0xFF2A0710), Color(0xFF14010A))),
        timer = {
            val timeout = gameState.settings.turnTimeoutSeconds
            if (gameState.phase == GamePhase.PLAYING && timeout != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_timer),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    TurnTimerText(
                        turnStartedAtMillis = gameState.turnStartedAtMillis,
                        totalSeconds = timeout
                    )
                }
            }
        }
    ) {
        val feltGrain = rememberFeltGrain()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp, top = 6.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.radialGradient(listOf(Color(0xFF5C1018), Color(0xFF280309))))
                .border(1.dp, UnoRed.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(feltGrain, alpha = FELT_GRAIN_ALPHA)
            )

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
                    MercySeatView(
                        seat = seat,
                        isCurrentTurn = gameState.currentPlayer?.id == seat.id,
                        viewerCanReport = !gameState.isSpectating(localPlayerId),
                        mercyThreshold = gameState.settings.mercyThreshold,
                        onCallUnoOn = { onCallUnoOn(seat.id) },
                        onSeatInfo = { onSeatInfo(seat.id) },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = maxWidth * fx - 36.dp, y = maxHeight * fy - 36.dp)
                    )
                }
            }

            // Lit behind the piles, where the eye already is. This skin had no colour wash at
            // all, and its felt is red - so on a red table, a red +4 face up and green in force,
            // nothing on screen disagreed with "red" except a 38dp orb.
            ActiveColourAura(
                color = gameState.currentColor,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = maxHeight * 0.54f - 190.dp)
                    .size(300.dp)
            )

            // Centre at 54%, bottom-aligned so the tower grows upward behind the top card.
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = maxHeight * 0.54f - 75.dp)
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
                    width = 62.dp,
                    height = 92.dp,
                    caption = "deck",
                    // Only when there is genuinely nothing playable - a pending draw
                    // stack is a choice, not a dead end.
                    mustDraw = isMyTurn && !hasPlays && gameState.pendingDrawCount == 0
                )
                Box(
                    modifier = Modifier.size(width = 76.dp, height = 150.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    // Stacking tower - one ghost card per two pending draws, capped at 3.
                    val towerCount = (gameState.pendingDrawCount / 2).coerceIn(0, 3)
                    repeat(towerCount) { i ->
                        Box(
                            modifier = Modifier
                                .offset(x = (i * 4).dp, y = -(i * 6).dp)
                                .size(width = 72.dp, height = 104.dp)
                                .graphicsLayer { rotationZ = (i - 1) * 4f }
                                .clip(RoundedCornerShape(8.dp))
                                .background(UnoRed.copy(alpha = 0.5f + i * 0.15f))
                        )
                    }
                    DiscardSlot(
                        gameState = gameState,
                        isMyTurn = isMyTurn,
                        showSymbols = showSymbols,
                        width = 72.dp,
                        height = 104.dp
                    )
                }

                // The active colour. This skin had NO statement of it anywhere - not an orb, not a
                // label - which is a hole rather than a stylistic choice: No Mercy is the deck with
                // the most wilds in it, so it is precisely the deck where the played card's own
                // colour is most often not the colour in force. Reading the felt was the only way
                // to know what you could play, and the felt is red on every card.
                //
                // Same orb the Round Table skin uses, so the two do not teach different vocabulary.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // The banner rather than an orb, and on this skin it matters most: the felt is
                    // red, so a red orb had to fight the table it was drawn on. Filled type in the
                    // colour, with the colour's NAME on it, has no such problem.
                    ActiveColourBanner(
                        color = gameState.currentColor,
                        showSymbols = showSymbols
                    )
                    DirectionGlyph(gameState.direction, size = 15.dp, alpha = 0.8f)
                }
            }

            if (gameState.pendingDrawCount > 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                ) {
                    Text(
                        "+${gameState.pendingDrawCount}",
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 34.sp
                    )
                    Text(
                        stackHint(gameState),
                        color = UnoYellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp
                    )
                }
            }
        }
    }
}

/** Seat with a knockout meter counting toward the 25-card threshold. */
@Composable
private fun MercySeatView(
    seat: Player,
    isCurrentTurn: Boolean,
    /** False once the viewer is knocked out - see the canCatch note below. */
    viewerCanReport: Boolean,
    mercyThreshold: Int,
    onCallUnoOn: () -> Unit,
    onSeatInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    // See RoundSeat: a knocked-out viewer must not be offered a catch.
    val canCatch = seat.cardCount == 1 && !seat.hasCalledUno && seat.isConnected && viewerCanReport
    val avatar = AvatarColors.getOrElse(seat.avatarColor % AvatarColors.size) { AvatarColors[0] }
    val koFraction = (seat.cardCount.toFloat() / mercyThreshold).coerceIn(0f, 1f)
    val koColor = when {
        koFraction >= 0.88f -> UnoRed
        koFraction >= 0.6f -> UnoYellow
        else -> Color.White.copy(alpha = 0.4f)
    }
    val ring = when {
        !seat.isConnected -> Color.White.copy(alpha = 0.08f)
        canCatch -> UnoRed
        isCurrentTurn -> NocturneAccent
        else -> Color.White.copy(alpha = 0.12f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.width(72.dp).alphaIf(!seat.isConnected, 0.45f)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
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
                    fontSize = 16.sp
                )
            }
            if (seat.isBot) {
                BotBadge(modifier = Modifier.align(Alignment.TopEnd), size = 14.dp)
            }
        }
        Text(
            if (seat.name.length > 9) seat.name.take(8) + "…" else seat.name,
            color = if (isCurrentTurn) Color.White else Neutral400,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(koFraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(koColor)
            )
        }
        Text(
            text = if (seat.isConnected) "${seat.cardCount}/$mercyThreshold" else "OUT",
            color = if (seat.isConnected) koColor else UnoRed,
            fontSize = 9.sp,
            fontWeight = if (seat.isConnected) FontWeight.Normal else FontWeight.ExtraBold
        )
    }
}
