package com.mutsho.localuno.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * Whether the board's decorative motion should be running.
 *
 * A card game is mostly spent waiting for other people, and the board was rendering at 60fps
 * throughout: a felt sweep, a draw-pile nudge, a dial glow, and on No Mercy a heat pulse - all of
 * them infinite, none of them ever stopping. That is a battery cost paid continuously for something
 * nobody is looking at while three other players take their turns.
 *
 * Defaults to true so previews, the menu and anything outside a live board are unaffected.
 */
val LocalAmbientMotion = staticCompositionLocalOf { true }

/** How long the board keeps breathing after something happens, before it settles. */
private const val QUIET_AFTER_MS = 6_000L

/**
 * The gate itself: motion runs while the table is waiting on YOU, and for a few seconds after
 * anything changes, then rests.
 *
 * Keyed on the sequence number rather than on a timestamp, because that is what actually marks a
 * change - the engine increments it on every real mutation and leaves it alone otherwise. So the
 * effect re-arms exactly when something happened and not once in between.
 *
 * [active] is written from inside an effect, never during composition, and flips at most twice per
 * quiet period - it is an ordinary state change, not a per-frame one, and must never become one.
 */
@Composable
fun rememberAmbientActive(sequenceNumber: Long, isMyTurn: Boolean): Boolean {
    var active by remember { mutableStateOf(true) }
    LaunchedEffect(sequenceNumber, isMyTurn) {
        active = true
        // While it is your turn the board is asking you for something, so it stays awake - that is
        // the one moment the motion is doing a job rather than decorating.
        if (isMyTurn) return@LaunchedEffect
        delay(QUIET_AFTER_MS)
        active = false
    }
    return active
}

/**
 * A value breathing between [from] and [to] while [active], resting where it stopped otherwise.
 *
 * Replaces `rememberInfiniteTransition`, which cannot be switched off: once started it requests a
 * frame forever. This drives an [Animatable] from a [LaunchedEffect] keyed on [active], so turning
 * it off cancels the loop and the frame requests stop with it.
 *
 * Returns [State] and never a delegate, deliberately. Reading one of these with `by` puts the
 * enclosing scope into a recomposition every frame, which is the shape that took this app down from
 * inside the Compose runtime with no application code on the trace. Read `.value` inside a draw or
 * layout lambda. See the comments at each call site for what that cost the first time.
 *
 * The tree is unchanged either way - nothing is added or removed by gating, only the loop stops.
 * That matters here more than it usually would: unmounting a node changes the slot table's shape,
 * which is the exact neighbourhood of the crash this file's callers were rewritten for.
 */
@Composable
fun rememberBreathing(
    active: Boolean,
    from: Float,
    to: Float,
    periodMs: Int,
    easing: Easing = EaseInOutSine
): State<Float> {
    val anim = remember { Animatable(from) }
    LaunchedEffect(active, from, to, periodMs) {
        if (!active) return@LaunchedEffect
        while (true) {
            anim.animateTo(to, tween(periodMs, easing = easing))
            anim.animateTo(from, tween(periodMs, easing = easing))
        }
    }
    return anim.asState()
}

/**
 * A continuously rotating angle while [active], holding its position otherwise.
 *
 * Holds rather than resets on purpose: snapping the felt sweep back to zero every time the table
 * goes quiet would turn a rest into a visible jump, which is worse than the motion it replaces.
 */
@Composable
fun rememberSweepAngle(active: Boolean, periodMs: Int): State<Float> {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(active, periodMs) {
        if (!active) return@LaunchedEffect
        while (true) {
            // Rebased each lap so a pause leaves the angle where it was rather than rewinding.
            val start = anim.value % 360f
            anim.snapTo(start)
            anim.animateTo(start + 360f, tween(periodMs, easing = LinearEasing))
        }
    }
    return anim.asState()
}
