package com.mutsho.localuno.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.engine.GameEngine
import com.mutsho.localuno.ui.theme.NocturneAccent
import com.mutsho.localuno.ui.theme.Surface
import com.mutsho.localuno.ui.theme.UnoRed
import kotlinx.coroutines.delay

/**
 * Circular countdown showing how long the current player has left to act.
 * Purely visual — computed locally from a shared `turnStartedAtMillis` timestamp so it
 * doesn't need a per-second network tick; only the host actually enforces the timeout.
 */
@Composable
private fun rememberTurnRemainingSeconds(turnStartedAtMillis: Long, totalSeconds: Int): Int {
    var remaining by remember(turnStartedAtMillis, totalSeconds) {
        mutableIntStateOf(remainingSeconds(turnStartedAtMillis, totalSeconds))
    }
    LaunchedEffect(turnStartedAtMillis, totalSeconds) {
        while (remaining > 0) {
            delay(500)
            remaining = remainingSeconds(turnStartedAtMillis, totalSeconds)
        }
    }
    return remaining
}

@Composable
fun TurnTimerRing(
    turnStartedAtMillis: Long,
    modifier: Modifier = Modifier,
    totalSeconds: Int = GameEngine.TURN_TIMEOUT_SECONDS
) {
    val remaining = rememberTurnRemainingSeconds(turnStartedAtMillis, totalSeconds)
    val fraction = if (totalSeconds > 0) remaining.toFloat() / totalSeconds else 0f
    val urgent = remaining <= (totalSeconds / 4).coerceAtLeast(3)
    val ringColor = if (urgent) UnoRed else NocturneAccent

    Box(modifier = modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(44.dp)) {
            val stroke = Stroke(width = size.minDimension * 0.14f)
            drawArc(
                color = Color.White.copy(alpha = 0.1f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
                size = Size(size.width - stroke.width, size.height - stroke.width),
                topLeft = androidx.compose.ui.geometry.Offset(stroke.width / 2, stroke.width / 2)
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = stroke,
                size = Size(size.width - stroke.width, size.height - stroke.width),
                topLeft = androidx.compose.ui.geometry.Offset(stroke.width / 2, stroke.width / 2)
            )
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Surface),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$remaining",
                color = ringColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun remainingSeconds(turnStartedAtMillis: Long, totalSeconds: Int): Int {
    if (turnStartedAtMillis <= 0L) return totalSeconds
    val elapsed = ((System.currentTimeMillis() - turnStartedAtMillis) / 1000L).toInt()
    return (totalSeconds - elapsed).coerceIn(0, totalSeconds)
}

/** Linear countdown bar — the Side Rails skin's turn timer, same data as [TurnTimerRing]. */
@Composable
fun TurnTimerBar(
    turnStartedAtMillis: Long,
    modifier: Modifier = Modifier,
    totalSeconds: Int = GameEngine.TURN_TIMEOUT_SECONDS
) {
    val remaining = rememberTurnRemainingSeconds(turnStartedAtMillis, totalSeconds)
    val fraction = if (totalSeconds > 0) remaining.toFloat() / totalSeconds else 0f
    val urgent = remaining <= (totalSeconds / 4).coerceAtLeast(3)
    val barColor = if (urgent) UnoRed else NocturneAccent

    Column(
        modifier = modifier.width(96.dp),
        horizontalAlignment = Alignment.End
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.12f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }
        Text(text = "$remaining", color = barColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Plain number, no bar/ring - the No Mercy skin's turn timer (its chrome is busy enough already). */
@Composable
fun TurnTimerText(
    turnStartedAtMillis: Long,
    modifier: Modifier = Modifier,
    totalSeconds: Int = GameEngine.TURN_TIMEOUT_SECONDS
) {
    val remaining = rememberTurnRemainingSeconds(turnStartedAtMillis, totalSeconds)
    val urgent = remaining <= (totalSeconds / 4).coerceAtLeast(3)
    Text(
        text = "$remaining",
        color = if (urgent) UnoRed else NocturneAccent,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
    )
}
