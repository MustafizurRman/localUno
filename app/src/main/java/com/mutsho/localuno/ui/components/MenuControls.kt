package com.mutsho.localuno.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mutsho.localuno.ui.theme.Neutral100
import com.mutsho.localuno.ui.theme.Neutral700
import com.mutsho.localuno.ui.theme.NocturneAccent

/**
 * The sliding-pill switch the design uses for every toggle - a track with a knob that moves end to
 * end, no Material chrome.
 *
 * Sized per call because the mockups genuinely use three: 42x24 with a 20dp knob on Host a Table,
 * 40x23 with a 19dp knob on Custom Table, and 38x22 with an 18dp knob in the board's preferences
 * sheet. One parameterised control beats three near-identical copies.
 *
 * Deliberately not Material3's Switch: that has its own track/thumb proportions, a thumb that grows
 * on press, a state-layer ripple and an outlined stadium track, none of which match.
 *
 * Presentation only - the caller owns the click, because in every one of these designs the whole
 * row is the tap target, so this must not install its own.
 */
@Composable
fun MenuSwitch(
    on: Boolean,
    modifier: Modifier = Modifier,
    trackWidth: Dp = 42.dp,
    trackHeight: Dp = 24.dp,
    knobSize: Dp = 20.dp,
    trackPadding: Dp = 2.dp
) {
    val track by animateColorAsState(
        targetValue = if (on) NocturneAccent else Neutral700,
        animationSpec = tween(200),
        label = "switchTrack"
    )
    // The knob travels the track's inner width minus its own size.
    val travel = trackWidth - trackPadding * 2 - knobSize
    val knobOffset by animateDpAsState(
        targetValue = if (on) travel else 0.dp,
        animationSpec = tween(200),
        label = "switchKnob"
    )
    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(track)
            .padding(trackPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = knobOffset)
                .size(knobSize)
                .clip(CircleShape)
                .background(Neutral100)
        )
    }
}
