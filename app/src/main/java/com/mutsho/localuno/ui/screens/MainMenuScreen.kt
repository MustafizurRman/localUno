package com.mutsho.localuno.ui.screens

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.ui.components.MenuCardFace
import com.mutsho.localuno.ui.theme.*

/**
 * Main menu, matched to MenuScreens.dc.html (screen 1) rather than to prose.
 *
 * Every offset, size, opacity, rotation and duration below is read straight off that file's inline
 * styles. Two things in the mockup are deliberately NOT reproduced, both because copying them into
 * a real build would be a defect rather than fidelity:
 *
 *  - The fake status bar ("21:04" + status-bar.svg). That row exists so the HTML preview reads as a
 *    phone screenshot. A real app already has the OS status bar in that exact space; drawing a
 *    second painted one underneath it would just be a wrong clock sitting under the right one.
 *  - The resume banner ("TABLE STILL OPEN"). It needs a persisted live-table record that does not
 *    exist - every leave path deliberately tears the connection fully down. The layout slot is
 *    wired and honours the same 12dp bottom margin, so turning it on later is a one-line change,
 *    but it stays hidden rather than showing an invented table.
 *
 * `nearbyLabel` IS real: the menu now runs a discovery scan and reports the true count.
 *
 * One thing here is NOT in the mockup: the ghost link to Custom Table. That screen exists in the
 * design project (CustomTable.dc.html) but nothing in the menus ever links to it - the mockup is a
 * preview harness, so it never had to answer how a player reaches it. See the link's own comment
 * for why it is ghost weight rather than a third slab.
 */
@Composable
fun MainMenuScreen(
    playerName: String,
    onNameChange: (String) -> Unit,
    avatarColor: Int,
    onAvatarColorChange: (Int) -> Unit,
    gamesPlayed: Int,
    gamesWon: Int,
    nearbyCount: Int,
    onCreateGame: () -> Unit,
    onCustomTable: () -> Unit,
    onPlaySolo: () -> Unit,
    onJoinGame: () -> Unit,
    onInspectCards: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf(playerName) }
    LaunchedEffect(playerName) { editedName = playerName }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // radial-gradient(120% 80% at 82% 6%, #12356b, transparent 58%)
            //   over linear-gradient(#0d1730, var(--color-bg) 70%)
            .background(Brush.verticalGradient(0f to MenuGradientTop, 0.70f to Background, 1f to Background))
    ) {
        val screenW = maxWidth
        val screenH = maxHeight
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(0f to Color(0xFF12356B), 0.58f to Color.Transparent),
                        center = Offset(
                            x = with(androidx.compose.ui.platform.LocalDensity.current) { (screenW * 0.82f).toPx() },
                            y = with(androidx.compose.ui.platform.LocalDensity.current) { (screenH * 0.06f).toPx() }
                        ),
                        radius = with(androidx.compose.ui.platform.LocalDensity.current) { (screenW * 1.2f).toPx() }
                    )
                )
        )

        // ── Broadcast rings: right:-56 top:104, 250x250, three staggered msRing loops ──
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 56.dp, y = 104.dp)
                .size(250.dp)
        ) {
            repeat(3) { i ->
                val ring = rememberInfiniteTransition(label = "ring$i")
                // msRing: 0% scale .6 / opacity .55  ->  100% scale 1.9 / opacity 0, 3.4s ease-out,
                // delays 0 / 1.1s / 2.2s. Modelled as one 3.4s sweep with the delay folded into a
                // keyframe hold, since infiniteRepeatable's initialStartOffset would only delay the
                // very first pass, not every repeat.
                val progress by ring.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 3400 + (i * 1100)
                            0f at 0
                            0f at (i * 1100)
                            1f at (3400 + i * 1100)
                        },
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "ringProgress$i"
                )
                val scale = 0.6f + (1.9f - 0.6f) * progress
                val alpha = 0.55f * (1f - progress)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                        .border(width = 1.dp, color = NocturneAccent, shape = CircleShape)
                )
            }
        }

        // ── Card fan: container right:-42 top:60; red is the in-flow element, blue/yellow are
        // positioned from the container's right edge (see per-card offsets below). ──
        DriftingCard(
            color = UnoBlue, glyph = R.drawable.ic_card_draw,
            rotationDeg = -24f, opacity = 0.4f, width = 104.dp, height = 149.dp,
            offsetXFromRight = (-54).dp, offsetY = 86.dp, periodMs = 7000, delayMs = 0, shadow = false
        )
        DriftingCard(
            color = UnoYellow, glyph = R.drawable.ic_card_reverse,
            rotationDeg = -11f, opacity = 0.62f, width = 112.dp, height = 160.dp,
            offsetXFromRight = (-4).dp, offsetY = 68.dp, periodMs = 6000, delayMs = 500, shadow = false
        )
        DriftingCard(
            color = UnoRed, glyph = R.drawable.ic_card_skip,
            rotationDeg = 6f, opacity = 1f, width = 126.dp, height = 180.dp,
            offsetXFromRight = 42.dp, offsetY = 60.dp, periodMs = 5400, delayMs = 1000, shadow = true
        )

        // ── Content column: padding 0 22px 20px ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The status bar sits over this screen's own painted gradient (enableEdgeToEdge
                // draws every screen edge-to-edge with no default inset) - without this the
                // wordmark and the profile row both slid up under the status bar / camera cutout.
                // The background Box above stays full-bleed; only this content column pads.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 22.dp, end = 22.dp, bottom = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(150.dp))

            // Wordmark. wordmark.svg can't be imported (it's SVG <text>), so the lockup is rebuilt
            // in Compose using HANDOFF_MENUS.md's explicit Compose values, with wordmark-rule.svg's
            // four bars (184x8) drawn as real geometry beneath.
            Text(
                text = "LOCAL",
                color = Neutral400,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 7.sp
            )
            Text(
                text = "UNO",
                color = NocturneText,
                fontSize = 62.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.width(184.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(UnoRed, UnoYellow, UnoGreen, UnoBlue).forEach { swatch ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(swatch)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "One phone hosts. Everyone else joins over the same Wi‑Fi — no accounts, no internet.",
                color = Neutral400,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                modifier = Modifier.widthIn(max = 196.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Resume banner slot - see file header for why it stays hidden.
            val hasResume = false
            @Suppress("KotlinConstantConditions")
            if (hasResume) {
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── HOST A TABLE ────────────────────────────────────────────────
            HostCta(onClick = onCreateGame)

            // ── Solo / Custom table ─────────────────────────────────────────
            // Both sit directly under the host slab because both are the same action taken a
            // different way, not new destinations. Kept at .btn-ghost weight and sharing one row:
            // the mockup's menu is a two-choice screen (host / join), and extra full-height cards
            // would flatten that hierarchy and push the profile row and footer off a short screen,
            // since this column doesn't scroll.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GhostLink("Play solo vs bots", onClick = onPlaySolo)
                Text("·", color = Neutral700, fontSize = 10.5.sp)
                GhostLink("Custom table", onClick = onCustomTable)
                Text("·", color = Neutral700, fontSize = 10.5.sp)
                // A test surface rather than a feature, so it sits at ghost weight alongside the
                // other two rather than taking a slab of its own.
                GhostLink("Cards", onClick = onInspectCards)
                Text("·", color = Neutral700, fontSize = 10.5.sp)
                GhostLink("Settings", onClick = onOpenSettings)
            }

            Spacer(modifier = Modifier.height(5.dp))

            // ── JOIN NEARBY ─────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x0AF3F5FE))
                    .border(1.5.dp, Neutral700, RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onJoinGame() }
                    .padding(horizontal = 16.dp, vertical = 17.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_wifi_small),
                    contentDescription = null,
                    tint = Neutral300,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "JOIN NEARBY",
                        color = NocturneText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    )
                    Text(
                        text = if (nearbyCount == 1) "1 table found on this network"
                        else "$nearbyCount tables found on this network",
                        color = Accent300,
                        fontSize = 10.5.sp
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = null,
                    tint = Neutral300,
                    modifier = Modifier.size(17.dp).graphicsLayer { rotationZ = 180f }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── Profile row ─────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x0DF3F5FE))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                val swatch = AvatarColors.getOrElse(avatarColor % AvatarColors.size) { AvatarColors[0] }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(swatch, swatch.copy(alpha = 0.72f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = playerName.take(1).uppercase().ifEmpty { "?" },
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(playerName, color = NocturneText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$gamesPlayed games", color = Neutral400, fontSize = 10.sp)
                        Text("·", color = Neutral700, fontSize = 10.sp)
                        Text("$gamesWon wins", color = Neutral400, fontSize = 10.sp)
                    }
                }
                // .btn.btn-ghost: accent text, 28dp tall, 10sp
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { editedName = playerName; isEditingName = true }
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("EDIT", color = NocturneAccent, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }

            if (isEditingName) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { if (it.length <= 16) editedName = it },
                    label = { Text("Your name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NocturneAccent,
                        unfocusedBorderColor = Neutral700,
                        focusedLabelColor = NocturneAccent,
                        unfocusedLabelColor = Neutral500
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AvatarColors.forEachIndexed { index, color ->
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f))))
                                .border(
                                    width = if (index == avatarColor) 2.dp else 0.dp,
                                    color = Color.White,
                                    shape = CircleShape
                                )
                                .clickable { onAvatarColorChange(index) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (editedName.isNotBlank()) onNameChange(editedName.trim())
                            isEditingName = false
                        }
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("DONE", color = NocturneAccent, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }

            // ── Footer ──────────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .graphicsLayer { alpha = 0.8f }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.matchParentSize()
                    )
                }
                Text(
                    "LOCAL UNO · v1.0",
                    color = Neutral600,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.4.sp
                )
            }
        }
    }
}

/** `.btn.btn-ghost` from the design system: accent text, no fill, no border, no ripple. */
@Composable
private fun GhostLink(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = NocturneAccent,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 6.dp, vertical = 6.dp)
    )
}

@Composable
private fun HostCta(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // box-shadow: 0 12px 26px rgba(237,28,36,.34)
            .shadow(elevation = 13.dp, shape = shape, ambientColor = UnoRed, spotColor = UnoRed)
            .clip(shape)
            // linear-gradient(115deg, #ED1C24, #b3151b)
            .background(
                // linear-gradient(115deg, ...) - runs right-and-down, not straight down.
                Brush.linearGradient(
                    colors = listOf(UnoRed, Color(0xFFB3151B)),
                    start = Offset.Zero,
                    end = Offset(x = Float.POSITIVE_INFINITY, y = Float.POSITIVE_INFINITY)
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        // msSheen: 4.5s ease-in-out, translateX(-120%) -> 220% by 55%, then holds to 100%.
        BoxWithConstraints(modifier = Modifier.matchParentSize()) {
            val band = 70.dp
            val sweep = rememberInfiniteTransition(label = "sheen")
            val x by sweep.animateFloat(
                initialValue = -1.2f,
                targetValue = 2.2f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 4500
                        -1.2f at 0
                        2.2f at 2475
                        2.2f at 4500
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "sheenX"
            )
            // Sweep across the whole slab: the mockup's percentages are relative to the 70px band,
            // which on a full-width button would only cross part of it - the visible intent is a
            // highlight travelling the length of the button.
            Box(
                modifier = Modifier
                    .offset(x = (maxWidth - band) * ((x + 1.2f) / 3.4f))
                    .width(band)
                    .fillMaxHeight()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent)
                        )
                    )
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 17.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_cards),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "HOST A TABLE",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                )
                Text(
                    "choose the deck and house rules",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.5.sp
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(17.dp).graphicsLayer { rotationZ = 180f }
            )
        }
    }
}

/**
 * One drifting card in the menu fan. [offsetXFromRight] is the card's right edge measured from the
 * screen's right edge (positive = hanging off-screen), matching the mockup's `right:` values once
 * its container offset is folded in.
 */
@Composable
private fun BoxScope.DriftingCard(
    color: Color,
    glyph: Int,
    rotationDeg: Float,
    opacity: Float,
    width: Dp,
    height: Dp,
    offsetXFromRight: Dp,
    offsetY: Dp,
    periodMs: Int,
    delayMs: Int,
    shadow: Boolean
) {
    val drift = rememberInfiniteTransition(label = "drift")
    // msDrift: 0%/100% translateY(0) rotate(r); 50% translateY(-9px) rotate(r + 1.5deg)
    val t by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = periodMs / 2 + delayMs
                0f at 0
                0f at delayMs
                1f at (periodMs / 2 + delayMs)
            },
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftT"
    )
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = offsetXFromRight, y = offsetY - (9.dp * t))
            .then(
                if (shadow) Modifier.shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(14.dp),
                    ambientColor = Color.Black,
                    spotColor = Color.Black
                ) else Modifier
            )
            .graphicsLayer { rotationZ = rotationDeg + 1.5f * t; alpha = opacity }
    ) {
        MenuCardFace(color = color, glyph = glyph, width = width, height = height)
    }
}
