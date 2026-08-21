package com.mutsho.localuno.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.model.RulesDigest
import com.mutsho.localuno.ui.theme.NocturneAccent
import com.mutsho.localuno.ui.theme.NocturneSurface
import com.mutsho.localuno.ui.theme.Neutral400
import com.mutsho.localuno.ui.theme.Neutral500

/**
 * "Here is what is true at this table."
 *
 * One component for both places it is needed - above START in the lobby, and behind the mode chip
 * on the board - because the two must never disagree, and the surest way to keep them agreeing is
 * for there to be only one of them.
 *
 * Every guest already holds this data; it simply had nowhere to appear. See [RulesDigest].
 */
@Composable
fun RulesInForce(
    settings: GameSettings,
    modifier: Modifier = Modifier,
    showHeadline: Boolean = true
) {
    val lines = RulesDigest.activeRules(settings)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (showHeadline) {
            Text(
                text = RulesDigest.headline(settings).uppercase(),
                color = NocturneAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
        }

        lines.forEach { line ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Aligned to the label's cap height rather than centred on the row: the detail
                // wraps to two lines on a narrow phone, and a centred dot would drift down with it.
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(NocturneAccent)
                )
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = line.label,
                        color = Neutral400,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = line.detail,
                        color = Neutral500,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

/**
 * The same list, as a dialog, for the board - where there is no spare room to keep it on screen.
 *
 * An AlertDialog rather than a ModalBottomSheet: every other interruption in this app is one
 * (leaving, removing a player, picking a colour), and a lone sheet among them would read as a
 * different KIND of thing rather than as the same app answering a question.
 */
@Composable
fun RulesInForceDialog(
    settings: GameSettings,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = settings.gameMode.displayName,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            // Headline suppressed here - the dialog is already titled with the mode, and
            // "2 HOUSE RULES" directly beneath that title reads as a subtitle for the mode itself.
            RulesInForce(settings = settings, showHeadline = false)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it", color = Color.White) }
        },
        containerColor = NocturneSurface
    )
}
