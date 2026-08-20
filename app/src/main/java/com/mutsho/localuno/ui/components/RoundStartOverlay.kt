package com.mutsho.localuno.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The deck being shuffled and dealt, played once at the start of a round.
 *
 * This exists because the board previously just... appeared, fully dealt, with no moment marking
 * the transition. A player coming from the lobby could not tell whether the game had started, was
 * still waiting for someone, or had frozen - the screen looked identical in all three cases. A deal
 * is the universal signal that a card game has begun, and every physical game opens with one.
 *
 * It also replaces something that was lost: the old flat-fan hand dealt its cards in with a
 * staggered flight, and swapping the fan for the dial dropped that with nothing in its place. This
 * puts the beat back where it belongs - on the whole table rather than on one player's hand.
 *
 * Deliberately does NOT consume touches. The turn clock is live the instant the round starts, and a
 * modal scrim would be spending someone's first turn on a flourish.
 */
@Composable
fun RoundStartOverlay(
    visible: Boolean,
    playerCount: Int,
    onFinished: () -> Unit
) {
    if (!visible) return

    val progress = remember { Animatable(0f) }
    // Ordinary state written once at the end, rather than gating the emission below on
    // `progress.value >= 1f`. See `finished` in HandTransferOverlay: deciding STRUCTURE from a
    // value the animation rewrites every frame is what tears the subtree out mid-flight.
    var finished by remember { mutableStateOf(false) }
    // The caption's two words are the one thing here that legitimately belongs to composition -
    // it changes once, not every frame - so it is driven by its own flag rather than by reading
    // the clock. `dealingAt` below is for the layout and draw lambdas only.
    var dealingNow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        finished = false
        dealingNow = false
        progress.snapTo(0f)
        launch {
            delay((TOTAL_MS * 0.44f).toLong())
            dealingNow = true
        }
        progress.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
        finished = true
        onFinished()
    }

    // NOTHING below reads `progress` in composition. This overlay plays at the start of every
    // round, and the crash clusters in the first seconds of a round - 8s, 15s, 31s - which is
    // exactly this animation's window. Every read now happens in a layout or draw lambda.
    fun sceneAlphaAt(t: Float) = when {
        t < 0.08f -> t / 0.08f
        t > 0.88f -> (1f - t) / 0.12f
        else -> 1f
    }
    fun shuffleAt(t: Float) = ((t - 0.08f) / 0.34f).coerceIn(0f, 1f)
    fun dealingAt(t: Float) = ((t - 0.44f) / 0.44f).coerceIn(0f, 1f)
    // Emission is wrapped in `if`, never skipped with an early `return`.
    //
    // Returning early AFTER remember/LaunchedEffect makes this composable's group structure differ
    // between frames: on some frames it opens groups and emits, on others it bails partway. Driven
    // by an ANIMATING value that flips many times a second, that is a structure changing under the
    // composer on almost every frame, and the failure lands nowhere near here - it surfaces as
    // IndexOutOfBoundsException at Stack.pop / ComposerImpl.endRoot, with no application code on
    // the stack at all. Keeping every remember unconditional and only the CONTENT conditional is
    // the shape Compose is built for.
    if (!finished) {

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF05050F).copy(alpha = 0.82f))
                .graphicsLayer { alpha = sceneAlphaAt(progress.value) }
        ) {
            val centreX = maxWidth / 2
            val centreY = maxHeight / 2
            val cardW = 46.dp
            val cardH = 68.dp

            // ── Phase 1: the riffle ─────────────────────────────────────────────
            // Two halves of the deck split apart, spring back, and interleave. Drawn as a stack of
            // backs whose horizontal offset alternates by parity, so odd and even cards move in
            // opposite directions - that alternation is what reads as a shuffle rather than as a stack
            // sliding around.
            // The riffle stack is ALWAYS composed. It used to be wrapped in `if (dealing <= 0f)`,
            // so nine children vanished from the tree at the frame the deal began - a structural
            // edit driven by an animating value read in composition. Held at zero alpha instead.
            run {
                repeat(DECK_CARDS) { i ->
                    // Three riffles across the shuffle phase.
                    // Each card is a little further through the riffle than the one below it, so the
                    // deck cascades. Without the per-card phase offset every odd card moves in exact
                    // lockstep with every other odd card and nine cards read as two.
                    val side = if (i % 2 == 0) 1f else -1f
                    val lift = (i - DECK_CARDS / 2f) * 2.6f
                    fun swingAt(t: Float) = sin(shuffleAt(t) * PI.toFloat() * 6f + i * 0.38f)
                    // Amplitude decays so the deck visibly settles into a neat stack before dealing.
                    fun ampAt(t: Float) = (1f - shuffleAt(t)) * 30f

                    CardBackTile(
                        width = cardW,
                        height = cardH,
                        modifier = Modifier
                            .offset {
                                val t = progress.value
                                IntOffset(
                                    (centreX - cardW / 2 + (swingAt(t) * side * ampAt(t)).dp).roundToPx(),
                                    (centreY - cardH / 2 + lift.dp).roundToPx()
                                )
                            }
                            .graphicsLayer {
                                val t = progress.value
                                rotationZ = swingAt(t) * side * ampAt(t) * 0.35f
                                // Gone once the deal starts, rather than removed from the tree.
                                alpha = if (dealingAt(t) > 0f) 0f else 1f
                            }
                    )
                }
            }

            // ── Phase 2: the deal ───────────────────────────────────────────────
            // One card per seat, flying out from the stack. Seat 0 is straight down - that is where
            // this player's own hand lives on every skin - and the rest spread evenly around, so the
            // fan of flights matches the table it is dealing to.
            run {
                val seats = playerCount.coerceIn(2, 8)
                repeat(seats) { i ->
                    // Staggered, so cards leave one at a time the way a dealer actually deals.
                    val delay = i / (seats * 1.6f)
                    fun localAt(t: Float) =
                        ((dealingAt(t) - delay) / (1f - delay).coerceAtLeast(0.2f)).coerceIn(0f, 1f)
                    // No `return@repeat` here. Skipping a seat until its card is due changes how
                    // many children this loop composes on almost every frame, driven by the
                    // animation itself - the exact shape that took HandArc down. Every seat is
                    // composed for the whole deal and the ones not yet airborne are simply drawn
                    // with no alpha.
                    fun easedAt(t: Float): Float {
                        val l = localAt(t)
                        return l * l * (3f - 2f * l)
                    }

                    val angle = PI.toFloat() / 2f + (2f * PI.toFloat() * i / seats)
                    val reach = minOf(maxWidth, maxHeight) * 0.34f

                    CardBackTile(
                        width = cardW,
                        height = cardH,
                        modifier = Modifier
                            .offset {
                                val e = easedAt(progress.value)
                                IntOffset(
                                    (centreX - cardW / 2 + (reach.value * cos(angle) * e).dp).roundToPx(),
                                    (centreY - cardH / 2 + (reach.value * sin(angle) * e).dp).roundToPx()
                                )
                            }
                            .graphicsLayer {
                                val t = progress.value
                                val eased = easedAt(t)
                                rotationZ = 360f * eased * (if (i % 2 == 0) 1f else -1f) * 0.6f
                                val s = 1f - 0.25f * eased
                                scaleX = s
                                scaleY = s
                                // `local <= 0f` is where the loop used to bail out entirely. Held
                                // at zero alpha instead, so a seat whose card is not yet due is
                                // invisible rather than absent - identical on screen, but the
                                // composition keeps the same shape from first frame to last.
                                alpha = if (localAt(t) <= 0f) 0f else 1f - (eased * eased) * 0.95f
                            }
                    )
                }
            }

            // ── Caption ─────────────────────────────────────────────────────────
            // Anchored to the top rather than under the deck. The deal throws a card straight down
            // towards this player's own seat, and at the caption's old position that card landed on
            // top of the word DEALING.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 118.dp)
            ) {
                Text(
                    text = if (dealingNow) "DEALING" else "SHUFFLING",
                    color = NocturneText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
                Text(
                    text = "$playerCount players",
                    color = Accent300,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

// Short on purpose. The engine's turn clock starts the instant the round does, so every frame of
// this is spent out of the first player's turn - at 2400ms that was a sixth of a 15s clock burned
// on a flourish. Long enough to read as a deal, short enough not to cost a move.
private const val TOTAL_MS = 1700
private const val DECK_CARDS = 9

/** A face-down card, matching the frame the real faces use so the deck reads as the same deck. */
@Composable
private fun CardBackTile(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(width, height)
            .clip(RoundedCornerShape(width * 0.11f))
            .background(CardInk)
            .padding(width * 0.055f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(width * 0.075f))
                .background(Brush.linearGradient(listOf(Accent700, Neutral900)))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(width * 0.075f)
                )
        )
    }
}
