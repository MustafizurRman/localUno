package com.mutsho.localuno.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.model.GameState
import com.mutsho.localuno.model.HandTransfer
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shows a whole-hand transfer happening - a 7's swap between two seats, or a 0's rotation around
 * the whole table - as an actual movement between named players.
 *
 * The thing this exists to answer is "whose cards just went where", which the board otherwise never
 * states: hands simply change size between one frame and the next, and in a rotation every count
 * changes at once. The old treatment was two anonymous cards labelled "7" and "?" sliding past each
 * other in the middle of the screen, which conveyed that *something* happened and nothing about
 * what. Here every participant is drawn with their own avatar colour and name, and a packet of
 * card-backs physically travels from each giver to their receiver.
 *
 * Laid out on its own ring rather than over the real seats. Seat positions differ per skin (a ring
 * on Round Table and No Mercy, two edge columns on Side Rails) and would have to be plumbed up from
 * each skin's internals; a self-contained ring reads the same on all three and cannot be knocked
 * out of alignment by a skin change.
 *
 * Deliberately does NOT consume touches. The round is already held while a swap resolves, and by
 * the time a rotation animates the next player may legitimately want to act - a modal scrim would
 * be stealing a second of their turn clock for a flourish.
 */
@Composable
fun HandTransferOverlay(gameState: GameState, localPlayerId: String) {
    val transfer = gameState.handTransfer ?: return

    // Keyed on the transfer's id, so each distinct transfer plays exactly once. A state update that
    // carries the same (already-played) descriptor re-composes without restarting anything.
    val progress = remember(transfer.id) { Animatable(0f) }
    LaunchedEffect(transfer.id) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = totalDurationMs(transfer), easing = LinearEasing))
    }
    if (progress.value >= 1f) return

    val participants = transfer.order.mapNotNull { id -> gameState.getPlayerById(id) }
    // A transfer naming players this device doesn't know about is not something to half-draw.
    if (participants.size != transfer.order.size || participants.size < 2) return

    val t = progress.value
    // Fade the whole thing in and out so it arrives and leaves softly instead of popping.
    val sceneAlpha = when {
        t < 0.12f -> t / 0.12f
        t > 0.86f -> (1f - t) / 0.14f
        else -> 1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05050F).copy(alpha = 0.72f * sceneAlpha))
            .graphicsLayer { alpha = sceneAlpha },
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val ringRadius: Dp = (minOf(maxWidth, maxHeight) * 0.30f).coerceAtMost(150.dp)
            val centreX = maxWidth / 2
            val centreY = maxHeight / 2

            // Where each participant sits on the ring. Two players face each other across it, which
            // makes a swap read as a straight exchange; three or more spread evenly into a circle so
            // a rotation reads as going round.
            val angles = participants.indices.map { i ->
                if (participants.size == 2) {
                    if (i == 0) PI else 0.0            // left, right
                } else {
                    -PI / 2 + (2 * PI * i / participants.size)   // first at top, then clockwise
                }
            }
            fun seatOffset(i: Int): Pair<Dp, Dp> {
                val a = angles[i]
                return Pair(centreX + ringRadius * cos(a).toFloat(), centreY + ringRadius * sin(a).toFloat())
            }

            // ── The travelling card packets ─────────────────────────────────
            // Each participant hands off to the NEXT one in the cycle (see HandTransfer.order), so
            // one loop covers both shapes: for two players that's simply A->B and B->A.
            val travel = ((t - 0.16f) / 0.62f).coerceIn(0f, 1f)
            val eased = travel * travel * (3f - 2f * travel)   // smoothstep - ease in and out
            participants.indices.forEach { i ->
                val from = seatOffset(i)
                val to = seatOffset((i + 1) % participants.size)
                // Bow the path outward from the ring's centre so opposing packets don't overlap
                // into an unreadable smear, and so the motion reads as an arc around the table.
                val midX = (from.first + to.first) / 2
                val midY = (from.second + to.second) / 2
                val bowX = (midX - centreX) * 0.55f
                val bowY = (midY - centreY) * 0.55f
                val x = quadratic(from.first, midX + bowX, to.first, eased)
                val y = quadratic(from.second, midY + bowY, to.second, eased)

                CardPacket(
                    modifier = Modifier
                        .offset(x = x - 15.dp, y = y - 21.dp)
                        .graphicsLayer {
                            rotationZ = 360f * eased * (if (transfer.isRotation) 1f else 0.5f)
                            // Lifts off, travels large, settles - gives the packet some weight.
                            val s = 0.7f + 0.45f * kotlin.math.sin(eased * PI).toFloat()
                            scaleX = s
                            scaleY = s
                        }
                )
            }

            // ── The seats themselves, drawn over the packets so arrivals land "behind" them ──
            participants.forEachIndexed { i, player ->
                val (x, y) = seatOffset(i)
                // Each seat pulses as its own packet leaves, then as one arrives.
                val give = pulseAt(t, 0.16f)
                val take = pulseAt(t, 0.72f)
                TransferSeat(
                    player = player,
                    isYou = player.id == localPlayerId,
                    emphasis = maxOf(give, take),
                    modifier = Modifier.offset(x = x - 34.dp, y = y - 34.dp)
                )
            }

            // ── Caption ─────────────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 54.dp)
            ) {
                Text(
                    text = if (transfer.isRotation) "EVERY HAND MOVES ON" else "HANDS SWAPPED",
                    color = NocturneText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = captionFor(transfer, participants, localPlayerId),
                    color = Accent300,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** Rotations have more to follow - more packets, more seats - so they get a little longer. */
private fun totalDurationMs(transfer: HandTransfer): Int =
    if (transfer.isRotation) 2000 else 1600

/** A short 0..1..0 bump centred on [at], for a seat reacting as cards leave or land. */
private fun pulseAt(t: Float, at: Float, width: Float = 0.12f): Float {
    val d = kotlin.math.abs(t - at)
    return if (d > width) 0f else 1f - (d / width)
}

private fun quadratic(from: Dp, control: Dp, to: Dp, t: Float): Dp {
    val u = 1f - t
    return from * (u * u) + control * (2f * u * t) + to * (t * t)
}

private fun captionFor(
    transfer: HandTransfer,
    participants: List<Player>,
    localPlayerId: String
): String {
    fun name(p: Player) = if (p.id == localPlayerId) "you" else p.name
    return if (transfer.isRotation) {
        // Naming the whole cycle is unreadable past a few seats, so state the shape of it and let
        // the animation carry the detail.
        "${participants.size} hands pass one seat around the table"
    } else {
        "${name(participants[0])} ⇄ ${name(participants[1])}"
    }
}

/** A small fan of face-down cards - what a "hand" looks like in flight. */
@Composable
private fun CardPacket(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(width = 30.dp, height = 42.dp)) {
        // Three backs offset slightly so the packet reads as several cards, not one.
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .offset(x = (i * 2).dp, y = (-i * 2).dp)
                    .size(width = 26.dp, height = 38.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.linearGradient(listOf(Accent700, Neutral900)))
                    .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(4.dp))
            )
        }
    }
}

/** One participant on the ring: avatar, name, and a ring that flares as cards move. */
@Composable
private fun TransferSeat(
    player: Player,
    isYou: Boolean,
    emphasis: Float,
    modifier: Modifier = Modifier
) {
    val avatar = AvatarColors.getOrElse(player.avatarColor % AvatarColors.size) { AvatarColors[0] }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.size(width = 68.dp, height = 84.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Flare ring - grows and fades with the pulse.
            if (emphasis > 0f) {
                Canvas(modifier = Modifier.size(58.dp)) {
                    drawCircle(
                        color = NocturneAccent.copy(alpha = 0.55f * emphasis),
                        radius = size.minDimension / 2 * (0.75f + 0.35f * emphasis),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(avatar, avatar.copy(alpha = 0.72f))))
                    .border(
                        width = if (isYou) 2.dp else 1.dp,
                        color = if (isYou) NocturneAccent else Color.White.copy(alpha = 0.25f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = player.name.take(1).uppercase().ifEmpty { "?" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            }
        }
        Text(
            text = if (isYou) "YOU" else (if (player.name.length > 8) player.name.take(7) + "…" else player.name),
            color = if (isYou) NocturneAccent else Neutral300,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
