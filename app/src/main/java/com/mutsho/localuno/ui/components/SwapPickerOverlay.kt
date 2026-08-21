package com.mutsho.localuno.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.model.Player
import com.mutsho.localuno.ui.theme.*

/**
 * The 7's-swap seat picker, matched to UnoBoard.dc.html's `swapOpen` branch.
 *
 * Only the player whose 7 is pending sees the list; everyone else gets the waiting state, because
 * the round is genuinely held for them and a blank board with no explanation reads as a freeze.
 *
 * The mockup also offers a "Take the 7 back" escape. That isn't reproduced: the 7 is already on
 * the discard pile and its effect already applied by the time this appears, so undoing it would
 * mean snapshotting and rolling back authoritative engine state and re-broadcasting the reversal -
 * a desync risk for a card the engine's own rules call mandatory ("player MUST swap hand"). The
 * turn clock is the escape hatch instead: let it run out and the largest hand is picked for you.
 */
@Composable
fun SwapPickerOverlay(
    isChooser: Boolean,
    chooserName: String,
    candidates: List<Player>,
    onPick: (String) -> Unit,
    /**
     * The live turn clock, or null when the table plays without one.
     *
     * The engine clocks CHOOSING_SWAP exactly like an ordinary turn - it has to, or a player who
     * plays a 7 and then walks away holds the whole round open, since nobody else may act until the
     * swap resolves. But every skin only draws its timer while the phase is PLAYING, and this
     * overlay covers the board anyway, so the deadline was completely invisible: you'd be deciding
     * at leisure and get auto-resolved into the largest hand with no warning that a clock existed.
     */
    turnStartedAtMillis: Long = 0L,
    turnTimeoutSeconds: Int? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05050F).copy(alpha = 0.85f))
            // Swallow taps: the board underneath is still composed and must not be reachable while
            // the round is held for this choice.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // if/else, NOT an early `return@Column`.
            //
            // Column is an inline composable, so returning out of its content lambda is a non-local
            // return: it jumps past the compiler-generated end-group calls for every group it
            // unwinds. The composition then carries more group ends than starts, and Compose does
            // not fail where that happened - it fails on a LATER frame, popping an empty group
            // stack in endRoot, with not one line of application code on the trace.
            //
            // That was this app's intermittent killer. It only fires on this branch, which needs
            // somebody ELSE to play a 7 with seven-swaps on - so it looked random, survived every
            // reading of the stack, and outlived a Compose runtime bump, a dozen overlay bisects
            // and two claims that it had been ruled out. The seed log recorded in the crash report
            // is what finally placed it: the captured round replays to CHOOSING_SWAP with a bot as
            // the chooser, which is precisely and only when this line ran.
            //
            // Never early-return from a composable lambda. It reads as ordinary Kotlin and it is
            // not.
            if (!isChooser) {
                Text(
                    "SWAPPING HANDS",
                    color = NocturneText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    "$chooserName played a 7 — waiting on their pick",
                    color = Neutral500,
                    fontSize = 11.sp
                )
            } else {
                Text(
                    "SWAP HANDS WITH",
                    color = NocturneText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    "Your 7 is played — pick a seat",
                    color = Neutral500,
                    fontSize = 11.sp,
                    modifier = Modifier.offset(y = (-8).dp)
                )

                if (turnTimeoutSeconds != null && turnStartedAtMillis > 0L) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.offset(y = (-4).dp)
                    ) {
                        TurnTimerText(
                            turnStartedAtMillis = turnStartedAtMillis,
                            totalSeconds = turnTimeoutSeconds
                        )
                        Text(
                            "before the biggest hand is picked for you",
                            color = Neutral500,
                            fontSize = 10.sp
                        )
                    }
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().widthIn(max = 250.dp)
                ) {
                    candidates.forEach { seat ->
                        val avatar = AvatarColors.getOrElse(seat.avatarColor % AvatarColors.size) { AvatarColors[0] }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, Neutral800, RoundedCornerShape(14.dp))
                                .clickable { onPick(seat.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(avatar, avatar.copy(alpha = 0.72f)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    seat.name.take(1).uppercase().ifEmpty { "?" },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(seat.name, color = NocturneText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = when {
                                        seat.cardCount == 1 -> "one card away from winning"
                                        seat.cardCount <= 3 -> "running low — good target"
                                        else -> "holding ${seat.cardCount}"
                                    },
                                    color = Accent300,
                                    fontSize = 9.5.sp
                                )
                            }
                            Text(
                                "${seat.cardCount}",
                                color = NocturneText,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
