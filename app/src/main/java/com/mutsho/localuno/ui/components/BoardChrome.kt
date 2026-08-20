package com.mutsho.localuno.ui.components

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.model.MoveLogEntry
import com.mutsho.localuno.model.MoveLogTint
import com.mutsho.localuno.ui.theme.*

/**
 * Board chrome shared by all three skins, matched to UnoBoard.dc.html.
 *
 * The mockup's own structure is: a mode-chip row, then a last-move strip with an expandable log,
 * then the felt (per-skin), then a turn/timer row, then the hand, then the action bar. Everything
 * except the felt is identical across skins in the design, so it lives here rather than being
 * duplicated three times.
 */

fun MoveLogTint.toColor(): Color = when (this) {
    MoveLogTint.NEUTRAL -> Neutral500
    MoveLogTint.WARN -> UnoYellow
    MoveLogTint.DANGER -> UnoRed
    MoveLogTint.RED -> UnoRed
    MoveLogTint.YELLOW -> UnoYellow
    MoveLogTint.GREEN -> UnoGreen
    MoveLogTint.BLUE -> UnoBlue
}

/** Top strip: mode chip, sub-label, move-log toggle, skin picker. Padding 4/14/8, gap 8. */
@Composable
fun BoardTopBar(
    chipLabel: String,
    chipBg: Color,
    chipFg: Color,
    chipEdge: Color,
    subLabel: String,
    logOpen: Boolean,
    onToggleLog: () -> Unit,
    onRequestLeave: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp)
    ) {
        // Leave control. Not in the mockup - which has no way off the board at all - but the app
        // needs one, and a board you cannot leave was a real defect fixed earlier. Styled as a
        // peer of the log/skin buttons so it reads as part of the same control cluster.
        BoardIconButton(icon = R.drawable.ic_leave, description = "Leave game", onClick = onRequestLeave)

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(chipBg)
                .border(1.dp, chipEdge, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(chipLabel, color = chipFg, fontSize = 11.sp, letterSpacing = 1.4.sp)
        }
        Text(
            subLabel,
            color = Neutral500,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        BoardIconButton(
            icon = R.drawable.ic_list,
            description = if (logOpen) "Hide move log" else "Show move log",
            onClick = onToggleLog,
            active = logOpen
        )
    }
}

/** 28dp rounded square, neutral-100 @8% (18% when active). */
@Composable
private fun BoardIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (active) 0.18f else 0.08f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = Neutral300,
            modifier = Modifier.size(15.dp)
        )
    }
}

/** Last-move strip + expandable history. Black @30%, radius-md, 7/10 padding, 5dp dot. */
@Composable
fun MoveLogStrip(
    log: List<MoveLogEntry>,
    logOpen: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            val head = log.firstOrNull()
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(head?.tint?.toColor() ?: NocturneAccent)
            )
            Text(
                text = head?.text ?: "Waiting for the first move",
                color = Neutral300,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (logOpen) {
            Column(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (log.size <= 1) {
                    Text("No earlier moves yet", color = Neutral500, fontSize = 10.sp)
                } else {
                    log.drop(1).take(8).forEach { row ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(row.tint.toColor())
                            )
                            Text(row.text, color = Neutral400, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Turn heading + sort control + a per-skin timer slot. Padding 8/14/0, gap 10.
 * [timer] is supplied by the skin because the design uses a different treatment in each
 * (ring / bar / bare number).
 */
@Composable
fun BoardTurnRow(
    turnTitle: String,
    turnTitleColor: Color,
    turnTitleSize: androidx.compose.ui.unit.TextUnit,
    turnSub: String,
    sortLabel: String,
    onSortHand: () -> Unit,
    timer: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                turnTitle,
                color = turnTitleColor,
                fontSize = turnTitleSize,
                fontWeight = FontWeight.SemiBold,
                lineHeight = turnTitleSize * 1.1f
            )
            Text(turnSub, color = Neutral500, fontSize = 10.5.sp)
        }
        // .btn.btn-ghost — accent text, 30dp tall, 9sp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSortHand() }
                .padding(horizontal = 9.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_sort),
                contentDescription = null,
                tint = NocturneAccent,
                modifier = Modifier.size(13.dp)
            )
            Text(
                sortLabel,
                color = NocturneAccent,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp,
                fontWeight = FontWeight.Medium
            )
        }
        timer()
    }
}

/**
 * Action bar: reactions on the left, the UNO button on the right.
 * Black @32%, padding 8/14/14. The UNO button is 46dp tall with a label and a sub-line, and
 * pulses while it's actually callable.
 */
@Composable
fun BoardActionBar(
    canCallUno: Boolean,
    hasCalledUno: Boolean,
    onCallUno: () -> Unit,
    onSendEmoji: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.32f))
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f)) {
            listOf("lol", "gg").forEach { label ->
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSendEmoji(label) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = NocturneAccent, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        val pulse = rememberInfiniteTransition(label = "unoPulse")
        // State, not `by`: delegating reads the animation in composition, so this button could
        // only animate by recomposing every frame for the whole round. Read inside graphicsLayer
        // below instead. See BoardScaffold's `heatAlpha` for what that costs.
        val scale = pulse.animateFloat(
            initialValue = 1f,
            targetValue = if (canCallUno) 1.06f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(620, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "unoScale"
        )
        val bg = when {
            hasCalledUno -> UnoGreen.copy(alpha = 0.22f)
            canCallUno -> UnoRed
            else -> Color.White.copy(alpha = 0.06f)
        }
        val fg = when {
            hasCalledUno -> UnoGreen
            canCallUno -> Color.White
            else -> Neutral600
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .height(46.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .clip(RoundedCornerShape(if (canCallUno) 23.dp else 12.dp))
                .background(bg)
                .then(
                    if (!canCallUno && !hasCalledUno) {
                        Modifier.border(1.dp, Neutral800, RoundedCornerShape(12.dp))
                    } else Modifier
                )
                .clickable(enabled = !hasCalledUno) { onCallUno() }
                .padding(horizontal = 24.dp)
        ) {
            Text(
                text = if (hasCalledUno) "UNO ✓" else "UNO",
                color = fg,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp
            )
            Text(
                text = when {
                    hasCalledUno -> "called"
                    // "tap now" is literal: the declaration has to land BEFORE the card that
                    // takes this hand to one, so the window closes the moment that card is played.
                    canCallUno -> "before you play"
                    else -> "at two cards"
                },
                color = if (canCallUno) Color.White.copy(alpha = 0.85f) else Neutral600,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
