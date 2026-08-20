package com.mutsho.localuno.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.ui.theme.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ── The design's own numbers ────────────────────────────────────────────────────────────────────
// HandArc.dc.html is authored against a 390x844 frame with 9px of bezel, so its inner stage is
// 372x392 and one design pixel is one dp on a phone of that size. Everything below is quoted from
// the source and then multiplied by a single [scale] derived from the real viewport, which is what
// keeps the dial the design's shape rather than "an arc, roughly" on every other screen size.
private const val DESIGN_STAGE_W = 372f
private const val DESIGN_RADIUS = 460f      // const R in the source - the dial radius
private const val DESIGN_CARD_W = 78f       // .ha-card width
private const val DESIGN_LIFT = 26f         // translateY(-26px) on the card at the needle
private const val DESIGN_SEL_SCALE = 1.2f
private const val DESIGN_GUIDE_INNER_R = 415f
private const val DESIGN_EDGE_FADE = 52f

/**
 * How far above the stage's bottom edge the card at the needle rests.
 *
 * The source puts this at 125 (radius 460 minus its 335 axle depth), which leaves a tall band of
 * empty dial underneath the hand - the stage has to be 392 to hold it, nearly half the screen, and
 * most of that is nothing at all.
 *
 * Dropping it to 66 crops that band away. The radius is untouched, so the arc keeps exactly the
 * curvature the design specifies - the same circle, seen through a shorter window. This is what
 * lets the stage halve without the cards halving with it: scaled to fit 392 they came out at 39dp,
 * scaled to fit this they stay near 55dp.
 *
 * Not lower than this. A card is placed by its bottom centre on the rim, so the further round the
 * dial it sits, the lower it hangs; at 66 the outermost card is still fully on stage at 30 degrees
 * and only crosses the bottom edge past 34, by which point it has already faded to nothing.
 */
private const val DESIGN_CARD_BASE = 66f
private const val DESIGN_PIVOT_BELOW = DESIGN_RADIUS - DESIGN_CARD_BASE
/** Just clear of the raised card's top: lift + a 1.2-scaled card + a little air. */
private const val DESIGN_NEEDLE_BOTTOM = DESIGN_CARD_BASE + 183f
private const val DESIGN_GLOW_BOTTOM = DESIGN_CARD_BASE + 1f
/** Needle top plus a small margin - the stage holds the dial and nothing else. */
private const val DESIGN_STAGE_H = DESIGN_CARD_BASE + 213f
private const val CARD_ASPECT = 512f / 330f // .uc aspect-ratio 330/512

/**
 * The 0-based occurrence number of each id within [ids] - `[7, 3, 7, 7]` gives `[0, 0, 1, 2]`.
 *
 * Used to make the hand's composition keys collision-proof. A card is keyed by its id, and two
 * items sharing a key in one composition corrupts Compose's slot table: it does not fail where the
 * duplicate is, it takes down a later frame from inside the runtime with no application code on the
 * stack. That is the crash that ended a real game here, and its cause was a host-side engine bug
 * handing the same card to a player twice.
 *
 * The engine bug is fixed, so why guard? Because the hand arrives over a network from ANOTHER
 * PHONE, which is not necessarily running this build. A host still on the old version will send
 * duplicate cards, and the fix being host-side does nothing for the guests. Pairing the id with its
 * occurrence keeps identity stable across sorts and reorders in the normal case (every occurrence
 * is 0) while making a duplicate merely render twice instead of crashing the app.
 */
internal fun occurrenceIndices(ids: List<Int>): List<Int> {
    val seen = HashMap<Int, Int>(ids.size)
    return ids.map { id ->
        val n = seen.getOrDefault(id, 0)
        seen[id] = n + 1
        n
    }
}

/**
 * The dial's arithmetic, with no Compose in it.
 *
 * Pulled out of the composable because this is the part that can be silently wrong: the wrap, the
 * nearest-card search and the spin target are three variations on modular arithmetic, and an error
 * in any of them is invisible in a screenshot - the dial still turns, it just settles on the wrong
 * card, or drifts out of precision after an hour's play. As plain functions they can be tested;
 * inside a `@Composable` they could only be eyeballed.
 */
internal object DialMath {
    const val FRICTION = 0.94f
    const val SNAP = 0.18f
    /** Below this the dial is treated as parked, so it stops nudging itself forever. */
    const val REST_EPSILON = 0.02f
    const val COAST_EPSILON = 0.04f

    /**
     * Degrees between adjacent cards: tight enough that neighbours overlap, wide enough that the
     * wrap seam stays off-screen behind the edge fades. Clamped, so a huge hand packs tighter only
     * up to a point and a tiny one never sprays across the whole rim.
     */
    fun step(n: Int): Float = if (n <= 0) 1f else (100f / n).coerceIn(4.2f, 9.5f)

    /** Total sweep occupied by the hand. Angles wrap modulo this - the wrap IS the roulette. */
    fun span(n: Int): Float = (if (n <= 0) 1 else n) * step(n)

    /**
     * Folds any angle back into (-span/2, span/2].
     *
     * Applied to the dial's own rotation after every change, not just when reading card positions.
     * Rotation is otherwise unbounded - each drag and each flick adds to it forever - and a Float
     * carries about seven significant digits, so a few thousand revolutions of accumulated spin
     * leaves a quantum coarser than [REST_EPSILON] and the dial can no longer settle: it parks a
     * fraction off the needle and jitters. Keeping the angle folded means precision at hour three
     * is precision at minute one.
     */
    fun normalise(theta: Float, n: Int): Float {
        val span = span(n)
        return ((theta + span / 2f) % span + span) % span - span / 2f
    }

    /** Where card [i] currently sits, wrapped into (-span/2, span/2]. 0 is under the needle. */
    fun angleOf(i: Int, n: Int, theta: Float): Float {
        val span = span(n)
        val raw = (i - (n - 1) / 2f) * step(n) + theta
        return ((raw + span / 2f) % span + span) % span - span / 2f
    }

    /** Index of the card under (or nearest to) the needle, or -1 for an empty hand. */
    fun nearest(n: Int, theta: Float): Int {
        var best = -1
        var bestAbs = Float.MAX_VALUE
        for (i in 0 until n) {
            val a = abs(angleOf(i, n, theta))
            if (a < bestAbs) {
                bestAbs = a
                best = i
            }
        }
        return best
    }

    /**
     * How far to turn to bring card [i] to the needle.
     *
     * Wrapped, so the result is at most half a revolution. Note this is not what makes the tap
     * "take the short way round" - the turn is applied instantly, exactly as the source does it, so
     * a wrapped and an unwrapped delta land on the identical frame. The wrap is here to keep the
     * rotation folded; see [normalise].
     */
    fun spinDelta(i: Int, n: Int, theta: Float): Float {
        val span = span(n)
        val raw = -((i - (n - 1) / 2f) * step(n)) - theta
        return ((raw + span / 2f) % span + span) % span - span / 2f
    }
}

/**
 * The player's hand as a roulette dial - HandArc.dc.html.
 *
 * Every card hangs off one axle far below the screen, so the hand is an arc of a single big circle
 * rather than a row: spin it and cards travel round the rim, and whichever card reaches the needle
 * at the top is the selected one. The list wraps, so a hand of any size is reachable by spinning in
 * either direction and the fan never has to spread wider or shrink to fit.
 *
 * This is the answer to the problem the flat fan could not solve. A fan has to show every card at
 * once, so past about twenty cards each one is a sliver a few dp wide and the corner index is all
 * that survives - and in a No Mercy hand that runs to thirty. A dial shows a handful of cards
 * legibly and moves the rest out of the way, so card size stops being a function of hand size:
 * [DESIGN_CARD_W] is 78dp whether you hold four cards or forty.
 *
 * The selection model changes with it. The fan played a card wherever you tapped, so every card had
 * to be both readable and hittable at once. Here the needle position is the only thing that plays,
 * a tap anywhere else just spins that card round to the needle - which means a mis-tap costs you a
 * spin instead of your turn.
 */
@Composable
fun HandArc(
    hand: List<Card>,
    playableCardIds: Set<Int>,
    isMyTurn: Boolean,
    rules: GameRules,
    showSymbols: Boolean,
    /** Shown in place of the dial's own copy when the player has no turn to take. */
    idleHint: String,
    onPlayCard: (Card) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // One scale for the whole dial, taken from whichever axis is tighter, so the arc keeps the
        // design's proportions instead of being stretched to fill an odd viewport.
        val scale = minOf(
            maxWidth.value / DESIGN_STAGE_W,
            maxHeight.value / DESIGN_STAGE_H
        ).coerceAtLeast(0.4f)
        fun d(v: Float): Dp = (v * scale).dp

        // Captured out of the BoxWithConstraints scope: inside an align()'d child the nearest
        // implicit receiver is BoxScope, which has no maxHeight.
        val stageHeightDp = maxHeight
        val density = LocalDensity.current
        val cardW = d(DESIGN_CARD_W)
        val cardH = d(DESIGN_CARD_W * CARD_ASPECT)
        val radiusPx = with(density) { d(DESIGN_RADIUS).toPx() }
        val stageW = with(density) { maxWidth.toPx() }
        val stageH = with(density) { maxHeight.toPx() }
        val centreX = stageW / 2f
        // Measured downward from the stage's top edge, so it can be used directly as a layout offset.
        val pivotY = stageH + with(density) { d(DESIGN_PIVOT_BELOW).toPx() }

        val n = hand.size

        var theta by remember { mutableFloatStateOf(0f) }
        var velocity by remember { mutableFloatStateOf(0f) }
        var dragging by remember { mutableStateOf(false) }

        /**
         * Which card is under the needle - published BY the physics loop, never derived from
         * [theta] here.
         *
         * This is the difference between a dial that spins and an app that dies. `theta` is
         * rewritten on every frame; anything composed that reads it makes the composition itself
         * run at frame rate, and a soak on the emulator killed the app inside two rounds every
         * time that was true. It died in the Compose runtime with an empty group stack at
         * `endRoot` and not one line of application code on the trace, which is why six earlier
         * readings of the stack led nowhere - the report names the frame that tripped, not the one
         * that broke the ground.
         *
         * Two shapes were tried and both crashed: reading `theta` straight into the composition,
         * then hiding it behind `derivedStateOf`. `derivedStateOf` only narrows how OFTEN the
         * composition is invalidated - the composition is still a subscriber to a value being
         * written from a frame callback, and that is the part that does not survive.
         *
         * So the loop pushes the answer out instead, and writes only when the needle card actually
         * changes - a couple of times a second, from ordinary state. Composition never reads
         * `theta` at all now; only `offset {}` and `graphicsLayer {}` do, and those are layout and
         * draw, which are exactly where a 60fps value belongs.
         */
        val selectedState = remember(n) {
            // withoutReadObservation: this initialiser runs DURING composition, so an ordinary
            // read of `theta` here would re-subscribe the composition to the very thing being
            // severed - and would do it invisibly, at the one moment it looks harmless.
            mutableIntStateOf(Snapshot.withoutReadObservation { DialMath.nearest(n, theta) })
        }
        val selected = selectedState.intValue

        // Collision-proof composition keys; see occurrenceIndices.
        val keySuffix = remember(hand) { occurrenceIndices(hand.map { it.id }) }

        // ── Physics, one pass per frame, as the source's rAF loop ──
        LaunchedEffect(n) {
            while (true) {
                withFrameNanos { }
                if (n == 0) continue

                // Publish the needle card. Before the dragging check, so a drag moves the
                // selection as the player's thumb moves rather than snapping when they let go.
                val nearest = DialMath.nearest(n, theta)
                if (nearest >= 0 && nearest != selectedState.intValue) {
                    selectedState.intValue = nearest
                }

                if (dragging) continue
                if (abs(velocity) > 0.04f) {
                    theta = DialMath.normalise(theta + velocity, n)
                    velocity *= DialMath.FRICTION
                } else {
                    // Recomputed here rather than read from the enclosing scope. This coroutine
                    // outlives every recomposition, so a captured `selected` would be frozen at
                    // whatever it was when the hand last changed SIZE - the dial would spin freely
                    // but always ease some long-stale card back onto the needle.
                    val best = DialMath.nearest(n, theta)
                    if (best >= 0) {
                        val off = DialMath.angleOf(best, n, theta)
                        // Proportional rather than a spring: the dial has to come to rest exactly
                        // on a detent, and a spring overshoots one card into the next.
                        if (abs(off) > DialMath.REST_EPSILON) {
                            theta = DialMath.normalise(theta - off * DialMath.SNAP, n)
                            velocity = 0f
                        }
                    }
                }
            }
        }

        val selectedCard = hand.getOrNull(selected)
        val selectedPlayable = selectedCard != null &&
            isMyTurn && selectedCard.id in playableCardIds

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stageHeightDp)
                .pointerInput(n, radiusPx) {
                    var startTheta = 0f
                    var lastNanos = 0L
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            velocity = 0f
                            startTheta = theta
                            lastNanos = System.nanoTime()
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, dx ->
                            change.consume()
                            // Arc length -> angle. The pointer drives the rim of the dial, so a
                            // drag of dx px turns it by dx/R radians whatever the card size.
                            val da = dx / radiusPx * 180f / PI.toFloat()
                            theta = DialMath.normalise(theta + da, n)
                            val now = System.nanoTime()
                            val dtMs = ((now - lastNanos) / 1_000_000f).coerceAtLeast(1f)
                            velocity = da / dtMs * 16f   // degrees per 60fps frame
                            lastNanos = now
                        }
                    )
                }
        ) {
            // ── The two guide rings, both centred on the axle ───────────────
            DialGuides(
                centreX = centreX,
                pivotY = pivotY,
                outerR = radiusPx,
                innerR = with(density) { d(DESIGN_GUIDE_INNER_R).toPx() }
            )

            // ── The glow under the needle ───────────────────────────────────
            val pulse = rememberInfiniteTransition(label = "dialGlow")
            // State, not `by`. This scope composes the entire dial, including the keyed card
            // list, so delegating here recomposed the whole hand every frame for the whole
            // game - the same shape as the theta read that crashed this file before.
            val glowAlpha = pulse.animateFloat(
                initialValue = 0.45f,
                targetValue = 0.85f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1300),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dialGlowAlpha"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -d(DESIGN_GLOW_BOTTOM))
                    .size(width = d(186f), height = d(104f))
                    .graphicsLayer { alpha = glowAlpha.value }
                    .drawBehind {
                        // radial-gradient(64% 100% at 50% 100%, accent 38%, transparent 74%)
                        drawRect(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0f to NocturneAccent.copy(alpha = 0.38f),
                                    0.74f to Color.Transparent,
                                    1f to Color.Transparent
                                ),
                                center = Offset(size.width / 2f, size.height),
                                radius = size.height
                            )
                        )
                    }
            )

            // ── The cards ───────────────────────────────────────────────────
            hand.forEachIndexed { i, card ->
                // EVERY card is composed, every frame, including the ones currently invisible
                // behind the wrap seam.
                //
                // This used to `return@forEachIndexed` when a card faded out, which meant the set
                // of composed children changed as cards rotated through the fade window - a
                // structural edit to the composition on almost every frame, driven by an animation.
                // Composing a constant set and hiding the far ones with alpha costs nothing after
                // the first pass (UnoCardFace is skippable, and alpha 0 skips drawing), and it
                // makes the hand's composition completely stable while the dial spins.
                val onNeedle = i == selected
                val cardWpx = with(density) { cardW.toPx() }
                val cardHpx = with(density) { cardH.toPx() }
                val liftPx = with(density) { d(DESIGN_LIFT).toPx() }

                // Position, rotation and fade are all read inside the layout/draw lambdas below,
                // so `theta` never touches the composition phase.
                fun angleNow() = DialMath.angleOf(i, n, theta)
                fun opacityNow(): Float {
                    val dist = abs(angleNow())
                    return when {
                        dist > 34f -> 0f
                        dist > 24f -> (34f - dist) / 10f
                        else -> 1f
                    }
                }

                key(card.id, keySuffix.getOrElse(i) { 0 }) {
                    Box(
                        modifier = Modifier
                            .offset {
                                // Layout phase: no recomposition when this changes.
                                val rim = radiusPx + if (onNeedle) liftPx else 0f
                                val rad = angleNow() * PI.toFloat() / 180f
                                IntOffset(
                                    (centreX + rim * sin(rad) - cardWpx / 2f).roundToInt(),
                                    (pivotY - rim * cos(rad) - cardHpx).roundToInt()
                                )
                            }
                            // Derived from `selected` rather than from the live angle, so it is
                            // stable between selection changes instead of shifting every frame.
                            .zIndex(
                                if (onNeedle) 60f
                                else (40f - abs(i - selected)).coerceAtLeast(1f)
                            )
                            .size(cardW, cardH)
                            .graphicsLayer {
                                // Draw phase: also free of recomposition.
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                rotationZ = angleNow()
                                val s = if (onNeedle) DESIGN_SEL_SCALE else 1f
                                scaleX = s
                                scaleY = s
                                alpha = opacityNow()
                            }
                            .shadow(
                                elevation = if (onNeedle) 16.dp else 8.dp,
                                shape = RoundedCornerShape(cardW * 0.11f),
                                clip = false
                            )
                            .then(
                                if (onNeedle) Modifier.drawWithContent {
                                    drawContent()
                                    // outline: 2px accent, outline-offset 2px - drawn outside the
                                    // card's own box, so it reads as a selection ring rather than
                                    // as part of the card art.
                                    val inset = with(density) { (-2).dp.toPx() }
                                    val stroke = with(density) { 2.dp.toPx() }
                                    val r = with(density) { (cardW * 0.11f).toPx() }
                                    drawRoundRect(
                                        color = NocturneAccent,
                                        topLeft = Offset(inset, inset),
                                        size = Size(
                                            size.width - inset * 2,
                                            size.height - inset * 2
                                        ),
                                        cornerRadius = CornerRadius(r, r),
                                        style = Stroke(width = stroke)
                                    )
                                } else Modifier
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                // A card faded out behind the wrap seam is still COMPOSED now (see
                                // the loop's own note), so it is still hit-testable - it just is
                                // not visible. Tapping empty felt and having an invisible card
                                // answer would be worse than the recomposition churn that skipping
                                // them used to cause, so visibility is checked here instead.
                                if (opacityNow() <= 0.01f) return@clickable

                                // Compose cancels a click once the pointer passes touch slop, so a
                                // spin that starts on a card never also plays it - the source had
                                // to track this by hand (`wasDrag`) because it read raw events.
                                if (i == selected) {
                                    if (isMyTurn && card.id in playableCardIds) onPlayCard(card)
                                } else {
                                    // Rotate the short way round to bring this card to the needle.
                                    theta = DialMath.normalise(theta + DialMath.spinDelta(i, n, theta), n)
                                    velocity = 0f
                                }
                            }
                    ) {
                        BoardCardFace(
                            card = card,
                            rules = rules,
                            showSymbols = showSymbols,
                            // The needle card is NEVER dimmed, and the unplayable ones behind it
                            // are.
                            //
                            // This used to be exactly inverted - `onNeedle && !selectedPlayable` -
                            // so the only card that could ever look dead was the one under the
                            // needle, lifted, ringed and scaled up as the card you are looking at.
                            // The card you were being invited to play was the gloomy one and its
                            // unplayable neighbours were bright, which reads as the dial telling
                            // you the opposite of what it means.
                            //
                            // Gated on isMyTurn: when the turn is somebody else's nothing is
                            // playable, and dimming the entire hand would say "you hold nothing"
                            // rather than "not yet".
                            dimmed = isMyTurn && !onNeedle && card.id !in playableCardIds,
                            width = cardW,
                            height = cardH,
                            // The dial shows cards at full size with only their edges overlapped,
                            // which is precisely the case the full art was drawn for.
                            compact = false
                        )
                    }
                }
            }

            // ── Edge fades, over the cards, so the wrap seam stays hidden ───
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(d(DESIGN_EDGE_FADE))
                    .height(stageHeightDp)
                    .zIndex(64f)
                    .background(
                        Brush.horizontalGradient(listOf(NocturneBg, Color.Transparent))
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(d(DESIGN_EDGE_FADE))
                    .height(stageHeightDp)
                    .zIndex(64f)
                    .background(
                        Brush.horizontalGradient(listOf(Color.Transparent, NocturneBg))
                    )
            )

            // ── The needle ──────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -d(DESIGN_NEEDLE_BOTTOM))
                    .size(d(22f))
                    .zIndex(70f)
            ) {
                Canvas(modifier = Modifier.size(d(22f))) {
                    // M12 22 L3 5 L12 10.5 L21 5 Z in a 24x24 box - a chevron aimed down at
                    // whichever card is currently under it.
                    val u = size.width / 24f
                    val p = Path().apply {
                        moveTo(12f * u, 22f * u)
                        lineTo(3f * u, 5f * u)
                        lineTo(12f * u, 10.5f * u)
                        lineTo(21f * u, 5f * u)
                        close()
                    }
                    drawPath(p, color = NocturneAccent)
                }
            }

            // ── The read-out: which card is on the needle, and what a tap will do ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(d(9f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -d(15f))
                    .zIndex(70f)
                    .clip(RoundedCornerShape(d(20f)))
                    .background(Neutral900.copy(alpha = 0.88f))
                    .border(1.dp, Accent700, RoundedCornerShape(d(20f)))
                    .padding(horizontal = d(14f), vertical = d(7f))
            ) {
                Text(
                    text = selectedCard?.let { cardDisplayName(it) } ?: "—",
                    color = Accent200,
                    fontSize = (10.5f * scale).coerceAtLeast(9f).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(d(12f))
                        .background(Neutral700)
                )
                Text(
                    text = when {
                        hand.isEmpty() -> "hand empty"
                        !isMyTurn -> idleHint
                        selectedPlayable -> "spin the dial · tap to play"
                        else -> "spin the dial · can't play this one"
                    },
                    color = Neutral400,
                    fontSize = (10.5f * scale).coerceAtLeast(9f).sp
                )
            }
        }
    }
}

/**
 * The two rings the cards ride on: a solid accent one through the card bottoms and a dashed inner
 * one 45dp under it. Both are centred on the axle, which sits well below the screen, so only their
 * top arcs are ever visible - that sliver is what tells the eye the hand is part of a wheel.
 */
@Composable
private fun DialGuides(centreX: Float, pivotY: Float, outerR: Float, innerR: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val axle = Offset(centreX, pivotY)
        drawCircle(
            color = NocturneAccent.copy(alpha = 0.20f),
            radius = outerR,
            center = axle,
            style = Stroke(width = 1.dp.toPx())
        )
        // The inner ring is dashed in the source. Stepping the arc by hand rather than using a
        // PathEffect keeps the dash length constant in screen terms on a circle this large.
        val dashDeg = 1.6f
        val gapDeg = 2.6f
        var deg = -90f - 60f
        while (deg < -90f + 60f) {
            val a0 = deg * PI.toFloat() / 180f
            val a1 = (deg + dashDeg) * PI.toFloat() / 180f
            drawLine(
                color = Neutral100.copy(alpha = 0.07f),
                start = Offset(axle.x + innerR * cos(a0), axle.y + innerR * sin(a0)),
                end = Offset(axle.x + innerR * cos(a1), axle.y + innerR * sin(a1)),
                strokeWidth = 1.dp.toPx()
            )
            deg += dashDeg + gapDeg
        }
    }
}

/**
 * The card's name for the dial's read-out - "GREEN SKIP", "WILD DRAW TEN".
 *
 * Distinct from [cardLabel], which is the two-to-four character mark that fits in a corner. This is
 * the spoken name, and it exists because a dial with one card under the needle can afford to say
 * exactly what that card is, which is the whole reason the read-out is there.
 */
fun cardDisplayName(card: Card): String {
    val colour = when (card.color) {
        CardColor.RED -> "RED"
        CardColor.YELLOW -> "YELLOW"
        CardColor.GREEN -> "GREEN"
        CardColor.BLUE -> "BLUE"
        CardColor.WILD -> ""
    }
    val name = when (card.type) {
        CardType.ZERO -> "0"; CardType.ONE -> "1"; CardType.TWO -> "2"; CardType.THREE -> "3"
        CardType.FOUR -> "4"; CardType.FIVE -> "5"; CardType.SIX -> "6"; CardType.SEVEN -> "7"
        CardType.EIGHT -> "8"; CardType.NINE -> "9"
        CardType.SKIP -> "SKIP"
        CardType.REVERSE -> "REVERSE"
        CardType.DRAW_TWO -> "DRAW TWO"
        CardType.DRAW_FOUR -> "DRAW FOUR"
        CardType.DISCARD_ALL -> "DISCARD ALL"
        CardType.SKIP_EVERYONE -> "SKIP EVERYONE"
        CardType.WILD -> "WILD"
        CardType.WILD_DRAW_SIX -> "WILD DRAW SIX"
        CardType.WILD_DRAW_TEN -> "WILD DRAW TEN"
        CardType.WILD_REVERSE_DRAW_FOUR -> "WILD REVERSE DRAW FOUR"
        CardType.WILD_COLOR_ROULETTE -> "WILD COLOUR ROULETTE"
        CardType.FLIP -> "FLIP"
        else -> card.type.name.replace('_', ' ')
    }
    return if (colour.isEmpty()) name else "$colour $name"
}
