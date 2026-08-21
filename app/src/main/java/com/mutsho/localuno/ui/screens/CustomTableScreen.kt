package com.mutsho.localuno.ui.screens

import java.security.SecureRandom
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.mutsho.localuno.model.HouseRule
import com.mutsho.localuno.model.TimeoutAction
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
    val rules: GameRules,
    /** Card count, or what the table is for when it has no deck of its own. */
    val sub: String,
    val badge: Int,
    /**
     * Drawn over the card art.
     *
     * `ic_badge_mercy_bg` is only the background layer - the name says so - so the No Mercy card
     * rendered as a plain red rectangle with nothing on it, while the design shows it carrying the
     * +10 that the deck is known for. Classic's badge has its white oval and Flip's has its glyph
     * built in; this is the one that needs its face put back.
     */
    val badgeMark: String? = null
)

// Verbatim from CustomTable.dc.html's PRESETS / PRESET_NOTE maps.
private val PRESETS = listOf(
    Preset(
        "nomercy", "No Mercy",
        "The brutal deck: +6, +10, reverse +4, and you're out at 25 cards.",
        GameMode.NO_MERCY,
        GameRules(stacking = true, lastCardMustBeNumber = true, zeroRotates = true, sevenSwaps = true),
        "168 cards", R.drawable.ic_badge_mercy_bg, badgeMark = "+10"
    ),
    Preset(
        "classic", "Classic",
        "The original game, played straight. No house rules.",
        GameMode.CLASSIC,
        GameRules(stacking = false, lastCardMustBeNumber = false, zeroRotates = false, sevenSwaps = false),
        "108 cards", R.drawable.ic_badge_classic
    ),
    Preset(
        "custom", "Custom",
        "Your table: choose the deck, then the rules that run on it.",
        GameMode.CLASSIC,
        GameRules(stacking = true, lastCardMustBeNumber = false, zeroRotates = false, sevenSwaps = false),
        "your rules", R.drawable.ic_uno_wild
    ),
    Preset(
        "flip", "Flip",
        "Two-sided deck, plain rules - let the Flip cards do the work.",
        GameMode.FLIP,
        GameRules(stacking = true, lastCardMustBeNumber = false, zeroRotates = false, sevenSwaps = false),
        "112 cards", R.drawable.ic_badge_flip
    )
)

private data class DeckRow(val mode: GameMode, val name: String, val desc: String, val badge: Int)

private val DECK_ROWS = listOf(
    DeckRow(GameMode.CLASSIC, "Classic", "108 cards", R.drawable.ic_badge_classic),
    DeckRow(GameMode.NO_MERCY, "No Mercy", "168 cards", R.drawable.ic_badge_mercy_bg),
    DeckRow(GameMode.FLIP, "Flip", "112 cards", R.drawable.ic_badge_flip)
)

/**
 * The setup screen's view of a house rule: the shared name and description, plus the two notes only
 * this screen shows (what switching it on or off actually changes).
 *
 * [name] and [desc] deliberately come from [HouseRule] rather than being written again here. They
 * used to be local strings, which meant the same rule could be - and now is - described on three
 * screens, and nothing kept those descriptions honest with each other.
 */
private data class CustomRule(
    val rule: HouseRule,
    val noteOn: String,
    val noteOff: String
) {
    val name: String get() = rule.label
    val desc: String get() = rule.detail
}

private val CUSTOM_RULES = listOf(
    CustomRule(
        HouseRule.STACKING,
        "+2 answers +2, +4 answers +4, reverse +4 answers reverse +4 · +6 and +10 can never be stacked",
        "Whoever is hit draws immediately"
    ),
    CustomRule(
        HouseRule.LAST_CARD_NUMBER,
        "Any card that would win is locked unless it's a number",
        "Any card can win"
    ),
    CustomRule(
        HouseRule.ZERO_ROTATES,
        "Active for all seats",
        "0 plays as a normal number"
    ),
    CustomRule(
        HouseRule.SEVEN_SWAPS,
        "You pick the seat you trade with",
        "7 plays as a normal number"
    )
)

@Composable
fun CustomTableScreen(
    savedPresetKey: String?,
    onPresetSaved: (String?) -> Unit,
    onBack: () -> Unit,
    onOpenTable: (GameSettings) -> Unit,
    /** Same table, no lobby and no server - straight onto the board against bots. */
    onPlaySolo: (GameSettings) -> Unit
) {
    // The design restores the last preset from localStorage on open, defaulting to "house".
    // A preset built on an unplayable deck is treated as absent: "flip" could otherwise be sitting
    // in prefs from an earlier build and would restore a deck the picker below refuses to select.
    // Classic, always - the actual game. Not "Classic unless something is remembered".
    //
    // This used to restore [savedPresetKey], which sounds friendly and isn't: a No Mercy table
    // chosen once, or written by a stray tap, silently became the table you opened every time
    // afterwards. That is how the app ended up treating No Mercy as its baseline - the default
    // preset was literally the No Mercy one, and the memory kept it there.
    //
    // Opening the host screen is the moment you decide what to play, so it starts from the game
    // everybody means by UNO and asks you to pick anything else deliberately.
    val initial = PRESETS.first { it.key == "classic" }

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
    var timeoutAction by remember { mutableStateOf(TimeoutAction.SKIP) }
    // null = the clock is off. It was hardcoded to 15s with no way to switch it off, so every
    // table had a turn clock whether the room wanted one or not.
    var turnClock by remember { mutableStateOf<Int?>(15) }

    // "Custom" is the only preset that exposes the rule toggles. An edited preset counts too: the
    // moment you change anything the badge clears and the table IS a custom one.
    val isCustom = presetKey == "custom"

    // Editing no longer clears the selection. The three chips are a deliberate choice of table,
    // and every control that can be edited lives under Custom anyway - so an edit confirms the
    // choice rather than abandoning it. Seats are common to all three and belong to none.
    fun edit(block: () -> Unit) = block()

    // SecureRandom, not Kotlin's default Random, which is a seedable PRNG never intended to produce
    // anything anyone has to guess. Still four digits: this is read aloud across a room, and the
    // real defence against guessing is the attempt limiter in PinGate, not the length. A longer PIN
    // would cost every honest guest and buy far less than rate limiting already does.
    val pin = rememberSaveable { (SecureRandom().nextInt(9000) + 1000).toString() }

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
                        "HOST A TABLE",
                        color = NocturneText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp
                    )
                    Text("Pick the table, then open it", color = Neutral500, fontSize = 10.5.sp)
                }
                // The PIN guests need, stated up front rather than only in the lobby.
                Tag(text = "PIN $pin")
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
                // 1 ── THE TABLE ──────────────────────────────────────────
                // The card tiles ARE the choice of table. There used to be two rows here - text
                // chips picking the table, then card tiles showing the deck - which said the same
                // thing twice and left Classic looking like a nearly empty screen. One row of
                // cards carries both: what you are playing, and what it is made of.
                Column(verticalArrangement = Arrangement.spacedBy(8.4.dp)) {
                    SectionLabel("THE TABLE", icon = R.drawable.ic_cards)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PRESETS.forEach { preset ->
                            val selected = preset.key == presetKey
                            val available = preset.mode.isPlayable
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .weight(1f)
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
                                    .clickable(enabled = available) {
                                        presetKey = preset.key
                                        mode = preset.mode
                                        rules = preset.rules
                                        onPresetSaved(preset.key)
                                    }
                                    .padding(vertical = 11.dp, horizontal = 4.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(preset.badge),
                                        contentDescription = null,
                                        tint = Color.Unspecified,
                                        modifier = Modifier
                                            .size(width = 34.dp, height = 48.dp)
                                            .alpha(if (available) 1f else 0.4f)
                                    )
                                    preset.badgeMark?.let { mark ->
                                        Text(
                                            mark,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier.alpha(if (available) 1f else 0.4f)
                                        )
                                    }
                                }
                                Text(
                                    preset.label,
                                    color = if (available) NocturneText else Neutral500,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (available) preset.sub else "SOON",
                                    color = if (available) Neutral500 else UnoYellow,
                                    fontSize = 9.5.sp,
                                    fontWeight = if (available) FontWeight.Normal else FontWeight.Bold
                                )
                            }
                        }
                    }
                    Text(
                        text = PRESETS.firstOrNull { it.key == presetKey }?.note.orEmpty(),
                        color = Neutral500,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                // 2 ── WHICH DECK (custom tables only) ─────────────────────
                // Custom is the one table with no deck of its own, so it is the one table that has
                // to ask. The other three ARE their decks.
                if (isCustom) {
                Column(verticalArrangement = Arrangement.spacedBy(8.4.dp)) {
                    SectionLabel("WHICH DECK", icon = R.drawable.ic_cards)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        DECK_ROWS.forEach { row ->
                            val selected = row.mode == mode
                            val available = row.mode.isPlayable
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) NocturneAccent.copy(alpha = 0.14f)
                                        else Color.White.copy(alpha = 0.05f)
                                    )
                                    .border(
                                        1.5.dp,
                                        if (selected) Accent600 else Neutral800,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable(enabled = available) { edit { mode = row.mode } }
                                    .padding(vertical = 9.dp, horizontal = 4.dp)
                            ) {
                                Text(
                                    row.name,
                                    color = if (available) NocturneText else Neutral500,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (available) row.desc else "SOON",
                                    color = if (available) Neutral500 else UnoYellow,
                                    fontSize = 9.5.sp,
                                    fontWeight = if (available) FontWeight.Normal else FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                }

                // 3 ── HOUSE RULES (custom tables only) ───────────────────
                // A named preset is a promise about how the game plays: "Classic" means the
                // original game, and offering four toggles underneath invited you to change it
                // while still calling it Classic. The toggles belong to the table you are building
                // yourself, so they appear when - and only when - that is what you picked.
                if (isCustom) {
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
                }

                // 4 ── SEATS (every table) ────────────────────────────────
                // Outside the custom-only block: how many people are playing is not a house rule,
                // and it was briefly swallowed by that `if`, which left Classic with no way to
                // change the seat count at all.
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

                // ── TURN CLOCK (every table) ───────────────────────────────
                // Not a house rule - it is about the room, not the game, so it sits with SEATS
                // rather than behind Custom. Off is a real choice: a table playing in person does
                // not need a countdown, and with no clock there is no timeout and so no penalty.
                Column(verticalArrangement = Arrangement.spacedBy(8.4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        SectionLabel("TURN CLOCK", modifier = Modifier.weight(1f))
                        Text(
                            turnClock?.let { "${it}s" } ?: "Off",
                            color = NocturneText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf<Int?>(null, 10, 15, 30, 45).forEach { secs ->
                            val selected = secs == turnClock
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) NocturneAccent
                                        else Color.White.copy(alpha = 0.06f)
                                    )
                                    .clickable { edit { turnClock = secs } },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    secs?.let { "${it}s" } ?: "Off",
                                    color = if (selected) Neutral900 else Neutral400,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // ── The turn clock's teeth (custom tables only) ────────────
                // Hidden when the clock is off, because there is then no moment for it to describe
                // - offering a penalty for running out of a clock nobody is running would be a
                // control that silently does nothing.
                if (isCustom && turnClock != null) {
                TimeoutRuleSection(
                    action = timeoutAction,
                    onPick = { edit { timeoutAction = it } }
                )
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

            // Built once and handed to whichever action is tapped - solo and hosting configure a
            // table identically, and the only difference is whether anyone else is invited to it.
            fun buildSettings() = GameSettings(
                gameMode = mode,
                maxPlayers = seats,
                lobbyName = "",
                pin = pin,
                rules = rules,
                turnTimeoutSeconds = turnClock,
                mercyThreshold = mercyThreshold,
                // Non-custom tables always skip: it is the rule people expect and the one that
                // needs no announcement.
                turnTimeoutAction = if (isCustom) timeoutAction else TimeoutAction.SKIP
            )

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
                            onOpenTable(buildSettings())
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

            // ── Solo, on the same screen ────────────────────────────────────
            // Solo used to be its own destination running its own copy of this screen, which meant
            // two places to configure a table and two places for them to drift apart. It is the
            // same table; it just skips the lobby because there is nobody to wait for.
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.04f))
                    .clickable { onPlaySolo(buildSettings()) }
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    "Play solo vs bots",
                    color = Accent300,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * What the turn clock does when it runs out.
 *
 * Two mutually exclusive outcomes rather than a switch, because "on/off" would not say what the
 * thing does - and until now it did nothing whatever: the timeout called a draw that politely
 * declines for anyone holding a playable card, so a table whose player walked away simply stopped.
 */
@Composable
private fun TimeoutRuleSection(
    action: TimeoutAction,
    onPick: (TimeoutAction) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.4.dp)) {
        SectionLabel("WHEN THE CLOCK RUNS OUT")
        TimeoutAction.values().forEach { option ->
            val selected = option == action
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Accent900 else Color.White.copy(alpha = 0.04f))
                    .border(
                        width = 1.dp,
                        color = if (selected) NocturneAccent else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onPick(option) }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .border(
                            width = if (selected) 6.dp else 2.dp,
                            color = if (selected) NocturneAccent else Neutral600,
                            shape = CircleShape
                        )
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        option.label,
                        color = NocturneText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(option.blurb, color = Neutral400, fontSize = 11.5.sp)
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
