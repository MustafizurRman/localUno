package com.mutsho.localuno.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.ui.components.ColorSymbol
import com.mutsho.localuno.ui.theme.UnoBlue
import com.mutsho.localuno.ui.theme.UnoGreen
import com.mutsho.localuno.ui.theme.UnoRed
import com.mutsho.localuno.ui.theme.UnoYellow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ColorOption(val cardColor: CardColor, val swatch: Color, val riseDelayMs: Int)

private val COLOR_OPTIONS = listOf(
    ColorOption(CardColor.RED, UnoRed, 0),
    ColorOption(CardColor.YELLOW, UnoYellow, 60),
    ColorOption(CardColor.GREEN, UnoGreen, 120),
    ColorOption(CardColor.BLUE, UnoBlue, 180)
)

@Composable
fun ColorPickerDialog(
    onColorChosen: (CardColor) -> Unit,
    /**
     * The board's "Card symbols" preference. This screen is the single worst place for colour to be
     * the only signal: four unlabelled swatches, no text, and a choice that cannot be taken back
     * once tapped. With the preference on, each swatch carries the same shape its cards do.
     */
    showSymbols: Boolean = false
) {
    // A wild card's color must be chosen before play continues - swallow back presses
    // instead of letting the player escape mid-choice.
    BackHandler(enabled = true) {}

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05050F).copy(alpha = 0.82f))
            // Consume every tap so nothing underneath (cards, draw pile) reacts while this is up
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RiseIn(delayMillis = 0) {
                Text(
                    text = "PICK A COLOUR",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                COLOR_OPTIONS.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { option ->
                            RiseIn(delayMillis = option.riseDelayMs) {
                                Box(
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(option.swatch)
                                        .clickable { onColorChosen(option.cardColor) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (showSymbols) {
                                        ColorSymbol(color = option.cardColor, size = 34.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Fades + rises a child into place, staggered by [delayMillis] - matches the design's entrance. */
@Composable
private fun RiseIn(delayMillis: Int, content: @Composable () -> Unit) {
    val offsetY = remember { Animatable(14f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMillis.toLong())
        launch { offsetY.animateTo(0f, tween(220)) }
        launch { alpha.animateTo(1f, tween(220)) }
    }
    Box(
        modifier = Modifier.graphicsLayer {
            translationY = offsetY.value
            this.alpha = alpha.value
        }
    ) {
        content()
    }
}
