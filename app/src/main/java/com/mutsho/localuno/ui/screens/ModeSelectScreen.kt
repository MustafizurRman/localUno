package com.mutsho.localuno.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.ui.components.MenuSwitch
import com.mutsho.localuno.ui.theme.*

private data class DeckOption(
    val mode: GameMode,
    val name: String,
    val count: String,
    val badge: Int,
    val note: String,
    val felt: Int
)

// Order, copy, card counts and notes are taken verbatim from MenuScreens.dc.html's DECKS array.
private val DECK_OPTIONS = listOf(
    DeckOption(
        GameMode.CLASSIC, "Classic", "108 cards", R.drawable.ic_badge_classic,
        "The Mattel deck — 0–9, Skip, Reverse, +2 and Wilds. The safe pick for new players.",
        R.drawable.ic_felt_classic
    ),
    DeckOption(
        GameMode.NO_MERCY, "No Mercy", "168 cards", R.drawable.ic_badge_mercy_bg,
        "Adds +6, +10 and reverse +4, and knocks a player out at 25 cards. Rounds get brutal fast.",
        R.drawable.ic_felt_mercy
    ),
    DeckOption(
        GameMode.FLIP, "Flip", "112 cards", R.drawable.ic_badge_flip,
        "Two-sided deck with +1, +5 and Flip cards — playing a Flip turns the whole table dark-side.",
        R.drawable.ic_felt_classic
    )
)

private data class RuleSpec(
    val name: String,
    val desc: String,
    val noteOn: String,
    val noteOff: String
)

private val RULE_SPECS = listOf(
    RuleSpec(
        "Stacking", "Pass a draw penalty on instead of taking it.",
        "Only a matching card answers — +2 with +2, +4 with +4. +6 and +10 can never be stacked.",
        "Whoever gets hit draws straight away."
    ),
    RuleSpec(
        "Last card must be a number", "You cannot win on a power card.",
        "Any card that would win the round locks unless it's a number.",
        "Any card can win the round."
    ),
    RuleSpec(
        "Zero rotates hands", "Play a 0 and every hand shifts one seat.",
        "Follows the current play direction.",
        "0 plays as an ordinary number."
    ),
    RuleSpec(
        "Seven swaps hands", "Play a 7 and trade with someone.",
        "You pick the seat you trade with.",
        "7 plays as an ordinary number."
    )
)

/**
 * Host a Table, matched to MenuScreens.dc.html (screen 2).
 *
 * Structure, spacing, copy and initial state all follow that file: four sections in order (deck /
 * seats / house rules / turn clock), a header carrying a PIN tag, and a sticky footer with a felt
 * swatch, a live summary line and an outlined CTA. Defaults match its `this.state` exactly -
 * No Mercy, 4 seats, 15s clock, all four house rules on.
 *
 * Two notes on things the mockup implies but doesn't itself resolve:
 *  - It shows a PIN unconditionally, so a PIN is now always generated for a table rather than being
 *    an optional toggle. There is no lobby-name field in the design either, so the table name is
 *    derived instead of typed.
 *  - Flip is present because the design shows three decks, but its dark-side ruleset isn't built in
 *    the engine yet, so the card is shown dimmed and marked SOON and cannot be picked. The gate is
 *    GameMode.isPlayable, not a name check here; see GameEngine's CardType.FLIP handling for what
 *    is still missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectScreen(
    onBack: () -> Unit,
    onCreateLobby: (GameSettings) -> Unit,
    /**
     * Dresses the same screen for solo play: no PIN (nobody is joining), and the seats you pick are
     * you plus that many bots. Everything else - deck, house rules, turn clock - is identical,
     * which is the point: solo is the same table with the other chairs filled, not a cut-down mode
     * with its own settings that could drift out of step with this one.
     */
    solo: Boolean = false
) {
    var selectedMode by remember { mutableStateOf(GameMode.NO_MERCY) }
    var seats by remember { mutableIntStateOf(4) }
    var clock by remember { mutableStateOf<Int?>(15) }
    var stacking by remember { mutableStateOf(true) }
    var lastCardMustBeNumber by remember { mutableStateOf(true) }
    var zeroRotates by remember { mutableStateOf(true) }
    var sevenSwaps by remember { mutableStateOf(true) }

    // The design always shows a PIN, so one is minted per visit to this screen rather than toggled.
    val pin = rememberSaveable { (1000..9999).random().toString() }

    val deck = DECK_OPTIONS.first { it.mode == selectedMode }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // linear-gradient(#0d1730, var(--color-bg) 62%)
            .background(Brush.verticalGradient(0f to MenuGradientTop, 0.62f to Background, 1f to Background))
    ) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // ── Header: padding 8/18/12, gap 12 ─────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 12.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "Back",
                    tint = Neutral300,
                    modifier = Modifier
                        .size(19.dp)
                        .clickable { onBack() }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (solo) "PLAY SOLO" else "HOST A TABLE",
                        color = NocturneText,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp
                    )
                    Text(
                        if (solo) "You against the bots" else "Deck, seats and house rules",
                        color = Neutral500,
                        fontSize = 10.5.sp
                    )
                }
                // .tag with accent-800 bg / accent-200 text / accent-700 border.
                // Solo swaps the PIN out for the bot count: a PIN is meaningless with no network
                // running, and the tag slot is where this screen already puts "who can get in".
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Accent800)
                        .border(1.dp, Accent700, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    if (solo) {
                        Text(text = "${seats - 1} BOTS", color = Accent200, fontSize = 11.sp)
                    } else {
                        Text(text = "PIN", color = Accent200, fontSize = 11.sp)
                        Text(text = pin, color = Accent200, fontSize = 11.sp)
                    }
                }
            }

            // ── Scroll body: padding 0/18/14, gap 20 ────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1 ── THE DECK ─────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SectionHeader(R.drawable.ic_cards, "THE DECK")
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        DECK_OPTIONS.forEach { option ->
                            DeckCard(
                                option = option,
                                isSelected = option.mode == selectedMode,
                                onClick = { selectedMode = option.mode },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Text(deck.note, color = Accent300, fontSize = 10.5.sp, lineHeight = 16.sp)
                }

                // 2 ── SEATS ────────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(R.drawable.ic_users, "SEATS", modifier = Modifier.weight(1f))
                        Text("$seats", color = NocturneText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        (2..8).forEach { n ->
                            val selected = n == seats
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) NocturneAccent else Color(0x0FF3F5FE))
                                    .clickable { seats = n },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$n",
                                    color = if (selected) Neutral900 else Neutral400,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        val dots = seats.coerceAtMost(6)
                        // margin-right:-6px per avatar. Expressed as negative Arrangement spacing so
                        // the Row's measured width actually shrinks - Modifier.offset would move the
                        // dots visually while still reserving full width, which then made the
                        // trailing spacer resolve to a negative Dp.
                        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                            repeat(dots) { i ->
                                val swatch = AvatarColors.getOrElse(i % AvatarColors.size) { AvatarColors[0] }
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .graphicsLayer { alpha = if (i == 0) 1f else 0.72f }
                                        .background(Brush.linearGradient(listOf(swatch, swatch.copy(alpha = 0.72f))))
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = when {
                                seats > 6 -> "big table — hands get small"
                                seats == 2 -> "head to head"
                                else -> "you plus ${seats - 1}"
                            },
                            color = Neutral500,
                            fontSize = 10.sp
                        )
                    }
                }

                // 3 ── HOUSE RULES ─────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SectionHeader(R.drawable.ic_sort, "HOUSE RULES")
                    val values = listOf(stacking, lastCardMustBeNumber, zeroRotates, sevenSwaps)
                    val setters = listOf<(Boolean) -> Unit>(
                        { stacking = it },
                        { lastCardMustBeNumber = it },
                        { zeroRotates = it },
                        { sevenSwaps = it }
                    )
                    RULE_SPECS.forEachIndexed { i, spec ->
                        HouseRuleRow(spec = spec, on = values[i], onToggle = setters[i])
                    }
                }

                // Entry to Custom Table. The design has two table-setup screens - this quick one
                // (MenuScreens.dc.html) and the deliberate one with rule presets
                // (CustomTable.dc.html) - but never draws a link between them. This is one
                // .btn-ghost from the system's own vocabulary, appended after the last designed
                // section so the matched layout above is untouched.
                // 4 ── TURN CLOCK ──────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SectionHeader(R.drawable.ic_timer, "TURN CLOCK")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf<Pair<Int?, Pair<String, String>>>(
                            null to ("Off" to "no timer"),
                            10 to ("10s" to "brisk"),
                            15 to ("15s" to "default"),
                            30 to ("30s" to "relaxed")
                        ).forEach { (value, labels) ->
                            val selected = clock == value
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) NocturneAccent.copy(alpha = 0.14f) else Color(0x0DF3F5FE)
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) Accent600 else Neutral800,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { clock = value }
                            ) {
                                Text(
                                    labels.first,
                                    color = if (selected) Accent200 else Neutral400,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(labels.second, color = Neutral600, fontSize = 8.5.sp)
                            }
                        }
                    }
                }

            }

            // ── Sticky footer: border-top 1px neutral-800, bg neutral-100 @4% ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x0AF3F5FE))
                    // After .background so the hairline paints over the fill, not under it.
                    .drawBehind {
                        drawRect(color = Neutral800, size = Size(size.width, 1.dp.toPx()))
                    }
                    .padding(start = 18.dp, end = 18.dp, top = 11.dp, bottom = 18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    Icon(
                        painter = painterResource(deck.felt),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                    )
                    val activeRules = buildList {
                        if (stacking) add("stacking")
                        if (lastCardMustBeNumber) add("number to win")
                        if (zeroRotates) add("0 rotates")
                        if (sevenSwaps) add("7 swaps")
                    }
                    Text(
                        text = "${deck.name} · " +
                                (if (solo) "you + ${seats - 1} bots" else "$seats seats") + " · " +
                                (clock?.let { "${it}s turns" } ?: "no clock") + " · " +
                                (if (activeRules.isEmpty()) "no house rules" else activeRules.joinToString(", ")),
                        color = Neutral400,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
                // .btn.btn-primary.btn-block — outlined accent, not filled
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, NocturneAccent, RoundedCornerShape(8.dp))
                        .clickable {
                            onCreateLobby(
                                GameSettings(
                                    gameMode = selectedMode,
                                    maxPlayers = seats,
                                    // Left blank on purpose - the caller stamps a name that
                                    // includes the host, since the design has no name field and a
                                    // deck-derived name would collide between hosts.
                                    lobbyName = "",
                                    // No server is started in solo, so there is nothing for a PIN
                                    // to guard - carrying one would only show up in the lobby
                                    // header as a code nobody can use.
                                    pin = if (solo) null else pin,
                                    rules = GameRules(
                                        stacking = stacking,
                                        lastCardMustBeNumber = lastCardMustBeNumber,
                                        zeroRotates = zeroRotates,
                                        sevenSwaps = sevenSwaps
                                    ),
                                    turnTimeoutSeconds = clock
                                )
                            )
                        }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_play),
                        contentDescription = null,
                        tint = NocturneAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        if (solo) "START THE ROUND" else "OPEN THE TABLE",
                        color = NocturneAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: Int, label: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Neutral500,
            modifier = Modifier.size(13.dp)
        )
        Text(
            label,
            color = Neutral500,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DeckCard(
    option: DeckOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val available = option.mode.isPlayable
    val lift by animateDpAsState(
        targetValue = if (isSelected) (-3).dp else 0.dp,
        label = "deckLift"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .offset(y = lift)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (isSelected) NocturneAccent.copy(alpha = 0.15f) else Color(0x0DF3F5FE)
            )
            .border(
                width = 1.5.dp,
                color = if (isSelected) NocturneAccent else Neutral800,
                shape = RoundedCornerShape(18.dp)
            )
            // An unplayable deck stays on screen with its artwork - the design shows three - but
            // must not be selectable, so it takes no click at all rather than one that lies.
            .clickable(enabled = available) { onClick() }
            .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 11.dp)
    ) {
        Icon(
            painter = painterResource(option.badge),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier
                .size(width = 44.dp, height = 62.dp)
                .clip(RoundedCornerShape(6.dp))
                .alpha(if (available) 1f else 0.4f)
        )
        Text(
            option.name,
            color = when {
                !available -> Neutral500
                isSelected -> Accent200
                else -> NocturneText
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            lineHeight = 14.sp
        )
        // Occupies the count's line rather than adding one, so all three cards stay the same height.
        Text(
            text = if (available) option.count else "SOON",
            color = if (available) Neutral500 else UnoYellow,
            fontSize = 9.sp,
            fontWeight = if (available) FontWeight.Normal else FontWeight.Bold,
            letterSpacing = if (available) 0.sp else 1.sp
        )
    }
}

@Composable
private fun HouseRuleRow(spec: RuleSpec, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) NocturneAccent.copy(alpha = 0.10f) else Color(0x0AF3F5FE))
            .border(
                width = 1.dp,
                color = if (on) Accent700 else Neutral800,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onToggle(!on) }
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(spec.name, color = NocturneText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                spec.desc,
                color = Neutral500,
                fontSize = 10.sp,
                lineHeight = 14.5.sp,
                modifier = Modifier.padding(top = 1.dp)
            )
            Text(
                text = if (on) spec.noteOn else spec.noteOff,
                color = if (on) Accent300 else Neutral600,
                fontSize = 9.5.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        MenuSwitch(on = on)
    }
}
