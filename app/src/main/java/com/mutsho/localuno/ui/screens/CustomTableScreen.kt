package com.mutsho.localuno.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.model.GameSettings
import com.mutsho.localuno.ui.components.MenuSwitch
import com.mutsho.localuno.ui.theme.*

/**
 * Custom Table, matched to CustomTable.dc.html - the design's "Custom mode: pick the deck, set the
 * house rules".
 *
 * Deliberately NOT a duplicate of Host a Table. That screen is the quick path: a three-card deck
 * grid with a turn clock and opinionated defaults. This one is the deliberate path - rule presets,
 * deck rows carrying full descriptions, and a live summary card. The copy differs too; every string
 * below is the Custom Table wording, not Host a Table's.
 *
 * Its own spacing scale is the design system's: gaps of --space-6 (16.8dp) between sections and
 * --space-3 (8.4dp) within them.
 */

private data class Preset(
    val key: String,
    val label: String,
    val note: String,
    val mode: GameMode,
    val rules: GameRules
)

// Verbatim from CustomTable.dc.html's PRESETS / PRESET_NOTE maps.
private val PRESETS = listOf(
    Preset(
        "house", "House rules",
        "How it usually gets played at home — big deck, every rule on.",
        GameMode.NO_MERCY,
        GameRules(stacking = true, lastCardMustBeNumber = true, zeroRotates = true, sevenSwaps = true)
    ),
    Preset(
        "tournament", "Tournament",
        "Strict: no stacking, no wild endings, no hand shuffling.",
        GameMode.CLASSIC,
        GameRules(stacking = false, lastCardMustBeNumber = true, zeroRotates = false, sevenSwaps = false)
    ),
    Preset(
        "chaos", "Chaos",
        "Everything on and you can win on a power card.",
        GameMode.NO_MERCY,
        GameRules(stacking = true, lastCardMustBeNumber = false, zeroRotates = true, sevenSwaps = true)
    ),
    Preset(
        "flip", "Flip night",
        "Two-sided deck, plain rules — let the Flip cards do the work.",
        GameMode.FLIP,
        GameRules(stacking = true, lastCardMustBeNumber = false, zeroRotates = false, sevenSwaps = false)
    )
)

private data class DeckRow(val mode: GameMode, val name: String, val desc: String, val badge: Int)

private val DECK_ROWS = listOf(
    DeckRow(GameMode.CLASSIC, "Classic deck", "108 cards · 0–9, Skip, Reverse, +2, Wild", R.drawable.ic_badge_classic),
    DeckRow(GameMode.NO_MERCY, "No Mercy deck", "adds +6, +10, reverse +4 and extra Skips", R.drawable.ic_badge_mercy_bg),
    DeckRow(GameMode.FLIP, "Flip deck", "two-sided · adds +1, +5 and Flip cards", R.drawable.ic_badge_flip)
)

private data class CustomRule(
    val name: String,
    val desc: String,
    val noteOn: String,
    val noteOff: String
)

private val CUSTOM_RULES = listOf(
    CustomRule(
        "Stacking", "Pass a draw penalty on — but only with the same card.",
        "+2 answers +2, +4 answers +4, reverse +4 answers reverse +4 · +6 and +10 can never be stacked",
        "Whoever is hit draws immediately"
    ),
    CustomRule(
        "Last card must be a number", "You cannot win on Skip, Reverse, Wild or a draw card.",
        "Any card that would win is locked unless it's a number",
        "Any card can win"
    ),
    CustomRule(
        "Zero rotates hands", "Play a 0 and every hand shifts one seat in play direction.",
        "Active for all seats",
        "0 plays as a normal number"
    ),
    CustomRule(
        "Seven swaps hands", "Play a 7 and choose whose hand you take.",
        "You pick the seat you trade with",
        "7 plays as a normal number"
    )
)

@Composable
fun CustomTableScreen(
    savedPresetKey: String?,
    onPresetSaved: (String?) -> Unit,
    onBack: () -> Unit,
    onOpenTable: (GameSettings) -> Unit
) {
    // The design restores the last preset from localStorage on open, defaulting to "house".
    // A preset built on an unplayable deck is treated as absent: "flip" could otherwise be sitting
    // in prefs from an earlier build and would restore a deck the picker below refuses to select.
    val initial = PRESETS.firstOrNull { it.key == savedPresetKey && it.mode.isPlayable }
        ?: PRESETS.first()

    // `initial` already resolves an unknown/absent key to "house", matching the design's
    // `PRESETS[saved] || PRESETS.house`, so the key follows it directly.
    var presetKey by remember { mutableStateOf(initial.key) }
    var mode by remember { mutableStateOf(initial.mode) }
    var rules by remember { mutableStateOf(initial.rules) }
    var seats by remember { mutableIntStateOf(4) }
    // Only meaningful in No Mercy - GameEngine reads it purely as GameSettings.mercyThreshold and
    // Classic/Flip never check it. Default matches GameSettings()'s own default, so a table that
    // never touches this control still plays the same knockout line as before this existed.
    var mercyThreshold by remember { mutableIntStateOf(25) }

    // Any manual edit drops the preset badge - the config is no longer that preset.
    fun edit(block: () -> Unit) {
        block()
        presetKey = ""
        onPresetSaved(null)
    }

    val pin = rememberSaveable { (1000..9999).random().toString() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // linear-gradient(#0F3460, var(--color-bg))
            .background(Brush.verticalGradient(listOf(ChromeGradientTop, Background)))
    ) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {

            // ── Header: padding 6/16/10, gap 10 ─────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "Back",
                    tint = Neutral300,
                    modifier = Modifier.size(18.dp).clickable { onBack() }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CUSTOM TABLE",
                        color = NocturneText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp
                    )
                    Text("Pick the deck, then the house rules", color = Neutral500, fontSize = 10.5.sp)
                }
                Tag(text = "CUSTOM")
            }

            // ── Scroll body: padding 0/16/12, gap --space-6 ─────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.8.dp)
            ) {
                // 1 ── PRESETS ─────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.4.dp)) {
                    SectionLabel("PRESETS")
                    FlowRowSimple {
                        PRESETS.forEach { preset ->
                            val selected = preset.key == presetKey
                            val available = preset.mode.isPlayable
                            Tag(
                                text = preset.label,
                                selected = selected,
                                // A preset is only as available as the deck it bundles.
                                modifier = Modifier
                                    .alpha(if (available) 1f else 0.4f)
                                    .clickable(enabled = available) {
                                        presetKey = preset.key
                                        mode = preset.mode
                                        rules = preset.rules
                                        onPresetSaved(preset.key)
                                    }
                            )
                        }
                    }
                    Text(
                        text = PRESETS.firstOrNull { it.key == presetKey }?.note
                            ?: "Custom — tweak anything below.",
                        color = Neutral500,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                // 2 ── WHICH CARDS ────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.4.dp)) {
                    SectionLabel("WHICH CARDS", icon = R.drawable.ic_cards)
                    DECK_ROWS.forEach { row ->
                        val selected = row.mode == mode
                        val available = row.mode.isPlayable
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) NocturneAccent.copy(alpha = 0.14f)
                                    else Color.White.copy(alpha = 0.05f)
                                )
                                .border(
                                    1.5.dp,
                                    if (selected) Accent600 else Neutral800,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable(enabled = available) { edit { mode = row.mode } }
                                .padding(11.dp)
                        ) {
                            Icon(
                                painter = painterResource(row.badge),
                                contentDescription = null,
                                tint = Color.Unspecified,
                                modifier = Modifier
                                    .size(width = 34.dp, height = 48.dp)
                                    .alpha(if (available) 1f else 0.4f)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    row.name,
                                    color = if (available) NocturneText else Neutral500,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    // The row has a description line to spare, so the reason lands
                                    // where the player is already looking instead of in a toast.
                                    text = if (available) row.desc else "${row.desc} — not playable yet",
                                    color = Neutral500,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                )
                            }
                            when {
                                !available -> Text(
                                    "SOON",
                                    color = UnoYellow,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                selected -> Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = "Selected",
                                    tint = NocturneAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // 3 ── HOUSE RULES ────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.4.dp)) {
                    SectionLabel("HOUSE RULES")
                    val values = listOf(
                        rules.stacking, rules.lastCardMustBeNumber, rules.zeroRotates, rules.sevenSwaps
                    )
                    val setters = listOf<(Boolean) -> Unit>(
                        { rules = rules.copy(stacking = it) },
                        { rules = rules.copy(lastCardMustBeNumber = it) },
                        { rules = rules.copy(zeroRotates = it) },
                        { rules = rules.copy(sevenSwaps = it) }
                    )
                    CUSTOM_RULES.forEachIndexed { i, spec ->
                        val on = values[i]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (on) NocturneAccent.copy(alpha = 0.10f)
                                    else Color.White.copy(alpha = 0.04f)
                                )
                                .border(1.dp, if (on) Accent700 else Neutral800, RoundedCornerShape(14.dp))
                                .clickable { edit { setters[i](!on) } }
                                .padding(11.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(spec.name, color = NocturneText, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(spec.desc, color = Neutral500, fontSize = 10.sp, lineHeight = 14.sp)
                                Text(
                                    if (on) spec.noteOn else spec.noteOff,
                                    color = Accent300,
                                    fontSize = 9.5.sp,
                                    lineHeight = 13.sp,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                            // 40x23 track, 19dp knob - this screen's own size.
                            MenuSwitch(
                                on = on,
                                trackWidth = 40.dp,
                                trackHeight = 23.dp,
                                knobSize = 19.dp
                            )
                        }
                    }
                }

                // 4 ── SEATS ──────────────────────────────────────────────
                Column(verticalArrangement = Arrangement.spacedBy(8.4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        SectionLabel("SEATS", icon = R.drawable.ic_users, modifier = Modifier.weight(1f))
                        Text("$seats", color = NocturneText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        (2..8).forEach { n ->
                            val selected = n == seats
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) NocturneAccent else Color.White.copy(alpha = 0.06f))
                                    .clickable { edit { seats = n } },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$n",
                                    color = if (selected) Neutral900 else Neutral400,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // 4b ── MERCY THRESHOLD (No Mercy only) ─────────────────────
                // Not a toggle - a knockout line. It belongs beside SEATS rather than in HOUSE
                // RULES because it isn't independent of the deck the way stacking/zero/seven are:
                // Classic and Flip never read it at all, so showing it for those decks would offer
                // a control with no effect. Hidden rather than disabled, since a greyed-out control
                // still implies "this does something here."
                if (mode == GameMode.NO_MERCY) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.4.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            SectionLabel(
                                "MERCY THRESHOLD",
                                icon = R.drawable.ic_skull,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "$mercyThreshold cards",
                                color = NocturneText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            "Knocked out on reaching this many cards in hand.",
                            color = Neutral500,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf(15, 20, 25, 30, 35, 40).forEach { n ->
                                val selected = n == mercyThreshold
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) NocturneAccent else Color.White.copy(alpha = 0.06f)
                                        )
                                        .clickable { edit { mercyThreshold = n } },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "$n",
                                        color = if (selected) Neutral900 else Neutral400,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // 5 ── SUMMARY ────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surface)
                        // --shadow-sm is a 1px ring, not a drop shadow.
                        .border(1.dp, Neutral800, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        "SUMMARY",
                        color = Neutral500,
                        fontSize = 9.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    val active = buildList {
                        if (rules.stacking) add("stacking")
                        if (rules.lastCardMustBeNumber) add("number to win")
                        if (rules.zeroRotates) add("0 rotates")
                        if (rules.sevenSwaps) add("7 swaps")
                        if (mode == GameMode.NO_MERCY) add("knockout at $mercyThreshold")
                    }
                    val deckName = DECK_ROWS.first { it.mode == mode }.name
                    Text(
                        text = "$deckName · $seats seats · " +
                                (if (active.isEmpty()) "no house rules" else active.joinToString(", ")),
                        color = Neutral300,
                        fontSize = 11.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            // ── Footer: padding 10/16/18, gap 9 ─────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 18.dp)
            ) {
                // .btn.btn-secondary
                Box(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, NocturneDivider, RoundedCornerShape(8.dp))
                        .clickable {
                            // Design's reset(): classic deck, stacking only, 4 seats.
                            mode = GameMode.CLASSIC
                            rules = GameRules(
                                stacking = true,
                                lastCardMustBeNumber = false,
                                zeroRotates = false,
                                sevenSwaps = false
                            )
                            seats = 4
                            mercyThreshold = 25
                            presetKey = ""
                            onPresetSaved(null)
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Reset", color = NocturneText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                // .btn.btn-primary.btn-block
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, NocturneAccent, RoundedCornerShape(8.dp))
                        .clickable {
                            onOpenTable(
                                GameSettings(
                                    gameMode = mode,
                                    maxPlayers = seats,
                                    lobbyName = "",
                                    pin = pin,
                                    rules = rules,
                                    // Custom Table has no turn-clock section; keep the app default
                                    // rather than silently disabling the timer.
                                    turnTimeoutSeconds = 15,
                                    mercyThreshold = mercyThreshold
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
                        "OPEN THE TABLE",
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
private fun SectionLabel(text: String, icon: Int? = null, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Neutral500,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(text, color = Neutral500, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    }
}

/** `.tag` from the design system: 11sp, 3/10 padding, radius-md * 0.75. */
@Composable
private fun Tag(text: String, selected: Boolean = true, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Accent800 else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) Accent600 else Neutral800, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = if (selected) Accent200 else Neutral400,
            fontSize = 11.sp,
            letterSpacing = 0.02.sp
        )
    }
}

/** Minimal wrapping row - the presets row is `flex-wrap:wrap` with a 6px gap. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowSimple(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}
