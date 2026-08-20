package com.mutsho.localuno.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.ui.theme.*

/**
 * The board's floating moments, matched to UnoBoard.dc.html: the UNO burst, the colour splash,
 * the paused scrim, the swap fly-across and the winner celebration.
 */

/**
 * UNO burst - the design's `burstName` state, drawn from assets/uno-burst.svg.
 *
 * Rebuilt in Compose rather than imported: the SVG's centrepiece is a `<text>` "UNO!", which a
 * VectorDrawable cannot express, and the radiating strokes are trivial geometry. Replaces the
 * plain text banner that stood in for this before.
 */
@Composable
fun UnoBurstOverlay(name: String?) {
    AnimatedVisibility(
        visible = name != null,
        enter = scaleIn(tween(450)) + fadeIn(tween(220)),
        exit = scaleOut(tween(260)) + fadeOut(tween(260))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.size(width = 240.dp, height = 140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Six radiating strokes, per the source's stroke group at 55% opacity.
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val sx = size.width / 240f
                        val sy = size.height / 140f
                        val stroke = 4f * sx
                        listOf(
                            Offset(20f, 26f) to Offset(36f, 38f),
                            Offset(220f, 26f) to Offset(204f, 38f),
                            Offset(12f, 84f) to Offset(32f, 84f),
                            Offset(228f, 84f) to Offset(208f, 84f),
                            Offset(34f, 120f) to Offset(48f, 108f),
                            Offset(206f, 120f) to Offset(192f, 108f)
                        ).forEach { (a, b) ->
                            drawLine(
                                color = UnoRed.copy(alpha = 0.55f),
                                start = Offset(a.x * sx, a.y * sy),
                                end = Offset(b.x * sx, b.y * sy),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 160.dp, height = 80.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(UnoRed, Color(0xFFFF5A2B)),
                                    start = Offset.Zero,
                                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "UNO!",
                            color = Color.White,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
                Text(
                    text = name ?: "",
                    color = Neutral300,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Colour splash - the design's `splashOn`: a disc in the played colour expanding out of the
 * discard pile and fading. Keyed on [trigger] so each play restarts it.
 */
@Composable
fun ColorSplashOverlay(trigger: Int, color: CardColor) {
    if (trigger == 0) return
    val progress = remember(trigger) { Animatable(0f) }
    // Ordinary state, written once when the animation lands - NOT `progress.value >= 1f` read in
    // composition. The gate below decides whether anything is emitted at all, and driving that from
    // a value rewritten every frame subscribes this composition to the frame clock and rebuilds the
    // structure at the moment the animation ends. That pattern is what killed HandArc; see
    // `finished` in HandTransferOverlay for the full account.
    var finished by remember(trigger) { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        finished = false
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700, easing = LinearEasing))
        finished = true
    }
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
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .offset(y = (-60).dp)
                    .size(60.dp)
                    .graphicsLayer {
                        val s = 1f + progress.value * 13f
                        scaleX = s; scaleY = s
                        alpha = 0.95f * (1f - progress.value)
                    }
                    .clip(CircleShape)
                    .background(colorFor(color))
            )
        }
    }
}


private data class Confetto(val xFraction: Float, val color: Color, val durationMs: Int, val delayMs: Int)

private val CONFETTI = List(14) { i ->
    Confetto(
        xFraction = ((i * 7 + 4) % 100) / 100f,
        color = listOf(UnoRed, UnoYellow, UnoGreen, UnoBlue)[i % 4],
        durationMs = 2400 + (i % 5) * 400,
        delayMs = i * 180
    )
}

/**
 * Winner celebration - the design's `winner` state: falling confetti, a trophy, the title, and
 * PLAY AGAIN.
 *
 * The mockup's board is self-contained, so this is where its round ends. The app also has a
 * results screen with rankings, per-player scores and round duration - real data the mockup never
 * contemplated - so [onFullResults] is offered alongside rather than throwing that away.
 */
@Composable
fun WinnerOverlay(
    visible: Boolean,
    winnerName: String,
    subtitle: String,
    isHost: Boolean,
    onPlayAgain: () -> Unit,
    onFullResults: () -> Unit
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05050F).copy(alpha = 0.9f))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val fallDistance = maxHeight + 60.dp
            CONFETTI.forEach { spec ->
                key(spec.xFraction, spec.delayMs) {
                    val transition = rememberInfiniteTransition(label = "confetti")
                    val p by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = keyframes {
                                durationMillis = spec.durationMs + spec.delayMs
                                0f at 0
                                0f at spec.delayMs
                                1f at (spec.durationMs + spec.delayMs)
                            },
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "confettiFall"
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = maxWidth * spec.xFraction, y = (-40).dp + fallDistance * p)
                            .size(width = 10.dp, height = 15.dp)
                            .graphicsLayer { rotationZ = 420f * p; alpha = 1f - p }
                            .clip(RoundedCornerShape(2.dp))
                            .background(spec.color)
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_trophy),
                contentDescription = null,
                tint = NocturneAccent,
                modifier = Modifier.size(40.dp)
            )
            Text(
                winnerName,
                color = NocturneAccent,
                fontSize = 38.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(subtitle, color = Neutral300, fontSize = 13.sp)

            if (isHost) {
                Box(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, NocturneAccent, RoundedCornerShape(8.dp))
                        .clickable { onPlayAgain() }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        "PLAY AGAIN",
                        color = NocturneAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = if (isHost) 2.dp else 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onFullResults() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("FULL RESULTS", color = Neutral400, fontSize = 11.sp, letterSpacing = 1.sp)
            }
        }
    }
}
