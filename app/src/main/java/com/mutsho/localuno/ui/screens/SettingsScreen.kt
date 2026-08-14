package com.mutsho.localuno.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.ui.components.BoardSkin
import com.mutsho.localuno.ui.theme.*

/**
 * Everything that used to be reachable only from inside a game.
 *
 * The board skin, the card symbols, the haptics and the turn cue all lived behind a sheet on the
 * board's top bar, which meant the only way to change how the game looks was to be in one - and
 * the only way to see whether you liked it was to keep playing. Worse, a player who never noticed
 * that icon never learned the alternate skins existed at all.
 *
 * Deliberately the same preferences rather than a second set: this reads and writes exactly the
 * MainViewModel state the in-game sheet does, so a change made here is already in force when the
 * next round starts, and the sheet still works mid-game for anyone who prefers it there.
 */
@Composable
fun SettingsScreen(
    playerName: String,
    onNameChange: (String) -> Unit,
    avatarColor: Int,
    onAvatarColorChange: (Int) -> Unit,
    boardSkin: BoardSkin,
    onBoardSkinChange: (BoardSkin) -> Unit,
    showCardSymbols: Boolean,
    onToggleCardSymbols: () -> Unit,
    hapticsEnabled: Boolean,
    onToggleHaptics: () -> Unit,
    turnAlertEnabled: Boolean,
    onToggleTurnAlert: () -> Unit,
    keepHandSorted: Boolean,
    onToggleKeepHandSorted: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ChromeGradientTop, Background)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onBack() }
                        .padding(6.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "SETTINGS",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }

            // ── You ─────────────────────────────────────────────────────────
            SectionLabel("YOU", "the name and colour other players see")
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x0AF3F5FE))
                    .border(1.dp, Neutral800, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                OutlinedTextField(
                    value = playerName,
                    onValueChange = { if (it.length <= 12) onNameChange(it) },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = NocturneAccent,
                        unfocusedIndicatorColor = Neutral700,
                        focusedLabelColor = NocturneAccent,
                        unfocusedLabelColor = Neutral500
                    )
                )

                Text("Avatar colour", color = Neutral400, fontSize = 12.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AvatarColors.forEachIndexed { index, color ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (index == avatarColor) 3.dp else 0.dp,
                                    color = Color.White,
                                    shape = CircleShape
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onAvatarColorChange(index) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Board ───────────────────────────────────────────────────────
            SectionLabel("BOARD", "how the table looks while you play")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BoardSkin.entries.forEach { skin ->
                    SkinRow(
                        skin = skin,
                        selected = skin == boardSkin,
                        onPick = { onBoardSkinChange(skin) }
                    )
                }
            }
            Text(
                // Stated rather than hidden, because picking No Mercy here and then hosting a
                // Classic table would otherwise look like the setting had been ignored.
                "No Mercy's board is only used on a No Mercy table — other decks fall back to Round Table.",
                color = Neutral600,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Play ────────────────────────────────────────────────────────
            SectionLabel("PLAY", "applies to every game on this device")
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x0AF3F5FE))
                    .border(1.dp, Neutral800, RoundedCornerShape(16.dp))
                    .padding(vertical = 6.dp)
            ) {
                SettingToggle(
                    title = "Card symbols",
                    subtitle = "A shape beside each colour, for colourblind play",
                    on = showCardSymbols,
                    onToggle = onToggleCardSymbols
                )
                SettingToggle(
                    title = "Your-turn alert",
                    subtitle = "A double buzz when the turn reaches you",
                    on = turnAlertEnabled,
                    onToggle = onToggleTurnAlert
                )
                SettingToggle(
                    title = "Haptics",
                    subtitle = "A tap when you play or draw a card",
                    on = hapticsEnabled,
                    onToggle = onToggleHaptics
                )
                SettingToggle(
                    title = "Keep hand sorted",
                    subtitle = "Group by colour and number as cards arrive",
                    on = keepHandSorted,
                    onToggle = onToggleKeepHandSorted
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(title, color = Accent300, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
        Text(subtitle, color = Neutral600, fontSize = 10.5.sp)
    }
}

@Composable
private fun SkinRow(skin: BoardSkin, selected: Boolean, onPick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent900 else Color(0x0AF3F5FE))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) NocturneAccent else Neutral800,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onPick() }
            .padding(12.dp)
    ) {
        // A stripe of the skin's own felt, so the choice is made by eye rather than by name.
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(skinPreview(skin)))
                .border(1.dp, Neutral700, RoundedCornerShape(8.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                skin.displayName,
                color = if (selected) Color.White else Neutral300,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(skin.description, color = Neutral600, fontSize = 10.5.sp)
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = NocturneAccent,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun skinPreview(skin: BoardSkin): List<Color> = when (skin) {
    BoardSkin.ROUND_TABLE -> listOf(Color(0xFF1B5E3A), Color(0xFF0E3521))
    BoardSkin.SIDE_RAILS -> listOf(Color(0xFF243056), Color(0xFF121A32))
    BoardSkin.NO_MERCY -> listOf(Color(0xFF6B1414), Color(0xFF2A0808))
}

@Composable
private fun SettingToggle(title: String, subtitle: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle() }
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(subtitle, color = Neutral600, fontSize = 10.5.sp)
        }
        // Drawn rather than a Material Switch, to match the toggles on the host/solo setup screens.
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (on) NocturneAccent else Neutral800)
                .padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}
