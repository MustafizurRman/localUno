package com.mutsho.localuno.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.ui.components.BoardCardFace
import com.mutsho.localuno.ui.components.cardDisplayName
import com.mutsho.localuno.ui.theme.*

/**
 * A test harness for the card art: every face the game can deal, in one scrollable list, with a
 * detail view that renders the tapped card exactly as the board renders it.
 *
 * This exists because card faces are otherwise only reachable by playing until the deck happens to
 * deal you the one you want to look at - a Wild Colour Roulette is four cards in a 112-card deck, so
 * confirming a fix to it meant restarting rounds until it turned up. Every face is built from the
 * same [Card] the engine deals and drawn by the same [BoardCardFace] the board uses, so what is
 * shown here is not a mock-up of the card: a bug visible here is a bug in the game.
 *
 * The toggles matter as much as the list. A card is drawn differently depending on context - the
 * hand's compact treatment drops the oval and the wear and promotes the corner index; an unplayable
 * card takes a scrim; the 0 and 7 pips disappear when their house rule is off - and every one of
 * those variants has had its own bug. They are switches here rather than four separate galleries
 * because the useful comparison is the same card before and after one thing changes.
 */
@Composable
fun CardGalleryScreen(onBack: () -> Unit) {
    var selected by remember { mutableStateOf<Card?>(null) }
    var compact by remember { mutableStateOf(false) }
    var dimmed by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    var zeroRotates by remember { mutableStateOf(true) }
    var sevenSwaps by remember { mutableStateOf(true) }

    val rules = remember(zeroRotates, sevenSwaps) {
        GameRules(zeroRotates = zeroRotates, sevenSwaps = sevenSwaps)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NocturneBg)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "Back",
                    tint = Neutral300,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onBack() }
                        .padding(5.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "CARD INSPECTOR",
                        color = NocturneText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp
                    )
                    Text(
                        "${GALLERY.sumOf { it.cards.size }} faces · tap one to inspect",
                        color = Neutral500,
                        fontSize = 10.5.sp
                    )
                }
            }

            // ── Render-mode switches ────────────────────────────────────────
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle("Compact (in hand)", compact) { compact = !compact }
                    Toggle("Unplayable", dimmed) { dimmed = !dimmed }
                    Toggle("Symbols", symbols) { symbols = !symbols }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle("0 rotates", zeroRotates) { zeroRotates = !zeroRotates }
                    Toggle("7 swaps", sevenSwaps) { sevenSwaps = !sevenSwaps }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── The deck ────────────────────────────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 76.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                GALLERY.forEach { section ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                            Text(
                                section.title.uppercase(),
                                color = Accent300,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp
                            )
                            Text(
                                section.note,
                                color = Neutral600,
                                fontSize = 9.5.sp
                            )
                        }
                    }
                    items(section.cards, key = { it.id }) { card ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { selected = card }
                            ) {
                                BoardCardFace(
                                    card = card,
                                    rules = rules,
                                    showSymbols = symbols,
                                    dimmed = dimmed,
                                    width = 68.dp,
                                    height = 105.dp,
                                    compact = compact
                                )
                            }
                            Text(
                                text = cardDisplayName(card),
                                color = Neutral600,
                                fontSize = 8.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Detail ──────────────────────────────────────────────────────────
        selected?.let { card ->
            CardDetail(
                card = card,
                rules = rules,
                dimmed = dimmed,
                symbols = symbols,
                onDismiss = { selected = null }
            )
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (on) Accent800 else Color(0x0AF3F5FE))
            .border(
                1.dp,
                if (on) NocturneAccent else Neutral700,
                RoundedCornerShape(7.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (on) Accent200 else Neutral500,
            fontSize = 10.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
        )
    }
}

/**
 * The tapped card, big, plus what the engine actually believes about it.
 *
 * Both render modes are shown side by side rather than following the gallery's toggle: the compact
 * treatment is a genuinely different drawing of the same card, and nearly every card-art bug so far
 * has been one mode being right while the other was wrong.
 */
@Composable
private fun CardDetail(
    card: Card,
    rules: GameRules,
    dimmed: Boolean,
    symbols: Boolean,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE60A0A12))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = cardDisplayName(card),
                color = NocturneText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center
            )

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BoardCardFace(
                        card = card,
                        rules = rules,
                        showSymbols = symbols,
                        dimmed = dimmed,
                        width = 132.dp,
                        height = 205.dp,
                        compact = false
                    )
                    Text(
                        "FULL — discard pile, dial",
                        color = Neutral500,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BoardCardFace(
                        card = card,
                        rules = rules,
                        showSymbols = symbols,
                        dimmed = dimmed,
                        width = 132.dp,
                        height = 205.dp,
                        compact = true
                    )
                    Text(
                        "COMPACT — overlapped in a fan",
                        color = Neutral500,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(NocturneSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Fact("type", card.type.name)
                Fact("colour", card.color.name)
                Fact("points", card.points.toString())
                Fact(
                    "rule pip",
                    when (card.type) {
                        CardType.ZERO -> if (rules.zeroRotates) "shown (0 rotates on)" else "hidden (0 rotates off)"
                        CardType.SEVEN -> if (rules.sevenSwaps) "shown (7 swaps on)" else "hidden (7 swaps off)"
                        else -> "none"
                    }
                )
            }

            Text(
                "tap anywhere to close",
                color = Neutral600,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Neutral600, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Text(value, color = Neutral300, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ── The deck, as data ───────────────────────────────────────────────────────────────────────────

private class Section(val title: String, val note: String, val cards: List<Card>)

private val COLOURS = listOf(CardColor.RED, CardColor.YELLOW, CardColor.GREEN, CardColor.BLUE)

/**
 * Every distinct FACE, not every card in the deck.
 *
 * A real deck holds many copies of most of these; a gallery of 112 tiles that repeats each face
 * eight times would bury the four faces that only appear once. Ids are synthetic and sequential -
 * nothing here is dealt, so they only need to be unique for the list's keys.
 */
private val GALLERY: List<Section> = buildList {
    var id = 0
    fun next() = id++

    add(
        Section(
            "Numbers",
            "0 and 7 carry a rule pip; 6 and 9 are underlined so they can't be read upside down",
            buildList {
                val numbers = listOf(
                    CardType.ZERO, CardType.ONE, CardType.TWO, CardType.THREE, CardType.FOUR,
                    CardType.FIVE, CardType.SIX, CardType.SEVEN, CardType.EIGHT, CardType.NINE
                )
                COLOURS.forEach { c -> numbers.forEach { t -> add(Card(next(), c, t)) } }
            }
        )
    )

    add(
        Section(
            "Coloured actions",
            "the centre glyph is repeated small in the corner",
            buildList {
                val actions = listOf(
                    CardType.SKIP, CardType.REVERSE, CardType.DRAW_TWO,
                    CardType.DRAW_FOUR, CardType.SKIP_EVERYONE, CardType.DISCARD_ALL
                )
                COLOURS.forEach { c -> actions.forEach { t -> add(Card(next(), c, t)) } }
            }
        )
    )

    add(
        Section(
            "Wilds",
            "Wild is Classic-only; the rest are No Mercy. Their glyphs carry the four colours and must never be tinted",
            listOf(
                Card(next(), CardColor.WILD, CardType.WILD),
                Card(next(), CardColor.WILD, CardType.WILD_DRAW_SIX),
                Card(next(), CardColor.WILD, CardType.WILD_DRAW_TEN),
                Card(next(), CardColor.WILD, CardType.WILD_REVERSE_DRAW_FOUR),
                Card(next(), CardColor.WILD, CardType.WILD_COLOR_ROULETTE)
            )
        )
    )

    // A wild that has been played and had a colour chosen is drawn on the WILD field but reports a
    // real colour - the one combination that has to keep the black face while the engine treats it
    // as red/yellow/green/blue. Worth being able to see rather than reason about.
    add(
        Section(
            "Wilds after a colour is chosen",
            "engine colour is set, face stays black — check the symbol follows the chosen colour",
            COLOURS.map { c -> Card(next(), c, CardType.WILD) }
        )
    )
}
