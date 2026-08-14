package com.mutsho.localuno.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.network.Heartbeat
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.ui.theme.NocturneAccent
import com.mutsho.localuno.ui.theme.Neutral900
import com.mutsho.localuno.model.GamePhase
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.ui.theme.UnoRed
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Floating overlays shared by every board skin. The board's structural chrome now lives in
 * BoardChrome/BoardScaffold and the hand in BoardHand; what remains here is only what draws
 * ON TOP of a board.
 */

@Composable
fun UnoAnnouncementOverlay(unoAnnouncement: String?) {
    AnimatedVisibility(
        visible = unoAnnouncement != null,
        enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
        exit = scaleOut(tween(300)) + fadeOut(tween(300))
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 120.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(color = UnoRed, shape = RoundedCornerShape(20.dp), shadowElevation = 24.dp) {
                Text(
                    text = "${unoAnnouncement ?: ""}\nUNO!",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp, vertical = 20.dp)
                )
            }
        }
    }
}

/**
 * Small top banner for routine status messages (turn-timeout auto-draws, a play arriving too
 * late to count, etc) - subtler than the UNO/knockout overlays since these aren't big moments.
 * Callers pass the fully-formatted message; this just displays it.
 */
@Composable
fun TimeoutToastOverlay(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = Modifier.padding(top = 44.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Surface(
                color = Color(0xFF292B31),
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 8.dp
            ) {
                Text(
                    text = message ?: "",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * A player's emoji reaction, shown briefly above the action bar. Rendered once at the skin-dispatch
 * level (UnoBoardScreen) rather than per-skin, so every board gets it identically.
 */
@Composable
fun EmojiReactionOverlay(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(bottom = 132.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = NocturneAccent.copy(alpha = 0.92f),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 10.dp
            ) {
                Text(
                    text = message ?: "",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
fun KnockoutOverlay(knockedOutName: String?, mercyThreshold: Int = 25) {
    AnimatedVisibility(
        visible = knockedOutName != null,
        enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
        exit = scaleOut(tween(300)) + fadeOut(tween(300))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                color = Color(0xFF292B31),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, UnoRed),
                shadowElevation = 12.dp
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 30.dp, vertical = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_skull),
                            contentDescription = null,
                            tint = UnoRed,
                            modifier = Modifier.size(26.dp)
                        )
                        Text(
                            text = "KNOCKED OUT",
                            color = UnoRed,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Text(
                        text = "$knockedOutName hit the $mercyThreshold-card limit",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun DisconnectPauseOverlay(gameState: GameState) {
    if (gameState.phase != GamePhase.PAUSED_DISCONNECT) return
    val disconnectedName = gameState.getPlayerById(gameState.disconnectedPlayerId ?: "")?.name ?: "A player"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            // Swallow taps: the board underneath stays interactive during the pause (the draw
            // pile's clickable is gated on isMyTurn, which is still true), so without this the
            // scrim is purely cosmetic and players can keep poking at a paused game.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$disconnectedName disconnected", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Continuing without them in ${gameState.disconnectCountdown}s...",
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Names the tapped seat in full, centred over the felt, auto-dismissing.
 *
 * Every skin truncates a seat's name to fit (9 characters on Round Table and No Mercy, 8 on Side
 * Rails), which is fine until two people at the table share a prefix - "Christopher" and
 * "Christina" both render as "Christin…", and there was no way at all to tell which seat was which.
 * A tap is the cheapest affordance that doesn't cost the seat any layout it doesn't have to spare.
 */
@Composable
fun SeatNameOverlay(name: String?, onDismiss: () -> Unit) {
    if (name == null) return
    LaunchedEffect(name) {
        kotlinx.coroutines.delay(1800)
        onDismiss()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Tap anywhere to dismiss early, but do NOT swallow the gesture beyond that - this is
            // a passive label, not a modal, and it must not eat a tap meant for a card underneath.
            .pointerInput(name) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

/**
 * Shown to a player knocked out by the Mercy rule while the round carries on without them.
 *
 * Before this, being knocked out looked identical to a bug: the hand went empty, turns stopped
 * arriving, and nothing on screen said why or for how long. The engine keeps them in the round as
 * a scored, ranked participant (their banked hand value decides their placing on the End screen),
 * so leaving is genuinely not the right move - this says so, and gives back the leave button for
 * anyone who'd rather go anyway.
 */
@Composable
fun SpectatingBanner(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .background(UnoRed.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .border(1.dp, UnoRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_skull),
            contentDescription = null,
            tint = UnoRed,
            modifier = Modifier.size(15.dp)
        )
        Column {
            Text("YOU'RE OUT", color = UnoRed, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Watching the rest of the round — you're still scored.",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 10.sp
            )
        }
    }
}

/**
 * A live good/poor read on this device's link to the host, derived from the SAME heartbeat that
 * already exists to detect a dead connection (see Heartbeat.kt and GameClient.lastMessageAt) -
 * no separate ping/pong round-trip needed, just how long it's been since anything at all arrived.
 *
 * Hidden entirely once [ageMs] passes the read timeout: at that point the connection hasn't just
 * degraded, it's about to be (or already has been) declared dead, and ReconnectingOverlay is the
 * right thing on screen, not a red dot quietly sitting in the corner.
 */
@Composable
fun ConnectionQualityDot(ageMs: Long?, modifier: Modifier = Modifier) {
    if (ageMs == null || ageMs >= Heartbeat.READ_TIMEOUT_MS) return
    val color = when {
        ageMs < Heartbeat.PING_INTERVAL_MS * 2 -> Color(0xFF3C7A1C)   // CardGreen - a ping or real
        // traffic landed well inside the last two heartbeat windows.
        ageMs < Heartbeat.PING_INTERVAL_MS * 3 -> Color(0xFFEDBE15)  // CardYellow - one heartbeat
        // late; not yet worrying, worth a glance.
        else -> Color(0xFFCB1607)                                    // CardRed - two heartbeats
        // late; the read timeout is close.
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color)
    )
}

/**
 * Shown to a client on its OWN board while GameViewModel's retry loop tries to get its connection
 * back after a drop - see GameViewModel.handleClientDisconnected. Deliberately NOT gated on
 * GamePhase.PAUSED_DISCONNECT the way DisconnectPauseOverlay (for watching someone ELSE drop) is:
 * this player's own _gameState is frozen at whatever it was the instant their socket died, since
 * they never received the StateUpdate that would have told them the round paused - by definition,
 * they were the one who stopped receiving anything. reconnecting/attempt come from GameViewModel
 * directly instead.
 */
@Composable
fun ReconnectingOverlay(reconnecting: Boolean, attempt: Int, maxAttempts: Int) {
    if (!reconnecting) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            // Board underneath stays visible but nothing on it should be tappable while the
            // connection itself is the problem - a card tap would just be sent nowhere.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = NocturneAccent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(14.dp))
            Text("Reconnecting…", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Attempt $attempt of $maxAttempts",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Small corner chip identifying a bot seat on the board itself - previously bots were only
 * labelled in the lobby, so once a round started there was no way to tell a bot from a human
 * except by its made-up name. Sized to sit as a corner overlay on a seat's avatar circle without
 * a layout slot of its own, since every skin's seat already spends its one free label slot on
 * OUT/UNO!/CATCH - whichever of those is live matters more than "is this a bot," so this rides on
 * the avatar instead of competing for that space.
 */
@Composable
fun BotBadge(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 16.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Neutral900)
            .border(1.dp, NocturneAccent, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // A glyph would need to be legible at 16dp, which none of the deck's own icons are
        // designed for (they're drawn to read at card size). "B" matches the lobby's own BOT
        // badge, which is already plain text at a similarly small size.
        Text(
            "B",
            color = NocturneAccent,
            fontSize = (size.value * 0.5).sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

