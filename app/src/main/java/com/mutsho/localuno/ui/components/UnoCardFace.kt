package com.mutsho.localuno.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutsho.localuno.R
import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor
import com.mutsho.localuno.model.CardType
import com.mutsho.localuno.model.GameRules
import com.mutsho.localuno.ui.theme.*
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A real No Mercy card face, per HANDOFF_CARDS.md and UnoCardFaces.dc.html.
 *
 * Everything is proportional to the card's own width - the handoff gives the whole spec in
 * percentages and `cqw` (1% of card width) for exactly this reason, so one composable serves a
 * 30dp card in a fanned hand and a 120dp card on the discard pile without a second set of numbers.
 *
 * This replaced three ad-hoc "card centre" treatments that varied per board skin. Those existed
 * only because there was no card art - they drew the card's NAME ("REV", "DISC", "SKIP+") in the
 * middle of a flat rectangle. The scanned deck is a single system, so the face is now the same
 * everywhere and the skins vary the board around it, which is what they were always describing.
 */
@Composable
fun UnoCardFace(
    card: Card,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    /** House rules in force - the 0 and 7 rule pips are suppressed when their rule is off. */
    rules: GameRules = GameRules(),
    /**
     * True for a card that's going to sit heavily overlapped by its neighbours - a fanned hand,
     * not the single face-up card on the discard pile.
     *
     * The handoff's proportions (a 62%-of-width centre numeral) were sized for a card seen whole.
     * Once a fanned hand overlaps two-thirds of each card - which the tighter tiers of a big hand
     * do by design, see BoardHand's fanTierFor - what's left showing of that giant numeral is a
     * cropped fragment, and a row of a dozen cropped fragments from adjacent cards reads as
     * exactly the illegible jumble it was: no single card's number was ever intended to be read
     * off a sliver of a numeral several times its own visible width.
     *
     * Compact mode inverts which element carries the card's identity: the corner index - already
     * positioned in the strip that overlap AND rotation both leave visible (draw order overlaps a
     * card's own right side with the next card, not its left) - grows to become the primary read,
     * the centre glyph shrinks to a supporting hint of colour and shape, and the fine wear texture
     * (a full card's worth of hairline scratches, at 1/3 the size) drops out entirely rather than
     * add static across a dozen small cards.
     */
    compact: Boolean = false,
    /**
     * The board's "Card symbols" preference. Adds a colourblind-safe shape beside each corner
     * index - see ColorSymbol. Off by default so nothing draws it unasked.
     */
    showSymbols: Boolean = false
) {
    BoxWithConstraints(modifier = modifier.size(width, height)) {
        val w = maxWidth
        val density = LocalDensity.current
        val art = cardArt(card)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(w * 0.11f))
                .background(CardInk)
                // The near-black frame is a uniform 5.5% bleed on all four sides. CSS resolves
                // percentage padding against the inline size, so this is 5.5% of WIDTH even on the
                // top and bottom edges - the frame is visibly thinner top-to-bottom, and matching
                // that is what keeps the proportions reading as the scanned card.
                .padding(w * 0.055f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(w * 0.075f))
                    .background(Brush.linearGradient(art.field))
            ) {
                val isWild = card.color == CardColor.WILD
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawGloss()
                    drawVignette()
                    // On a WILD card the oval is FILLED WHITE, and it is the card's whole identity -
                    // the printed card is a black rectangle whose only large feature is that oval
                    // with the colour pinwheel inside it.
                    //
                    // The handoff says "stroke only, never filled" for every face, and on a coloured
                    // card that reads fine: a #0A0A0A stroke on red is a dark decorative sweep. On
                    // the near-black wild field the same stroke is #0A0A0A on #141419 - invisible.
                    // Following the rule there produced a flat black card that looks nothing like
                    // the real one.
                    //
                    // Filled only for wilds, and deliberately not for the four colours: their centre
                    // is a WHITE numeral, so a white oval underneath would be white-on-white and the
                    // numeral would have to become the card's colour - a restyle of all 64 coloured
                    // faces, not a fix to this one.
                    if (isWild) {
                        drawOval(filled = true)
                    } else if (!compact) {
                        // A bold stroke crossing the panel corner to corner: a signature motif at
                        // full size, but a dozen overlapping at once is a dozen crossing diagonals
                        // competing with the numbers. Dropped in compact for the same reason as wear.
                        drawOval(filled = false)
                    }
                }

                // ── Centre ──────────────────────────────────────────────────
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val centreFraction = if (compact) 0.34f else 0.60f
                    if (art.glyph != null) {
                        Icon(
                            painter = painterResource(art.glyph),
                            contentDescription = null,
                            // The wild glyphs carry the four card colours internally and must
                            // never be tinted (HANDOFF_CARDS §4).
                            tint = Color.Unspecified,
                            modifier = Modifier
                                .size(w * centreFraction)
                                .rotate(-13f)
                        )
                    } else {
                        OutlinedGlyph(
                            text = art.centreText,
                            fontSize = with(density) { (w * (centreFraction + 0.02f)).toSp() },
                            strokeWidth = with(density) { (w * 0.052f).toPx() },
                            modifier = Modifier.rotate(-13f)
                        )
                    }
                }

                // ── Corner indices: top-left upright, bottom-right at 180°. The top-left is the
                // one doing the work in compact mode - see the parameter doc for why. ──
                CornerIndex(art, rules, w, density, Alignment.TopStart, false, compact, showSymbols, card.color)
                CornerIndex(art, rules, w, density, Alignment.BottomEnd, true, compact, showSymbols, card.color)

                // ── Wear, on top of everything - full size only ─────────────
                if (!compact) Canvas(modifier = Modifier.fillMaxSize()) { drawWear() }
            }
        }
    }
}

/**
 * A shape that stands for the card's colour, for players who can't rely on the colour itself.
 *
 * UNO is a colour-matching game, so red/green confusion (the most common form, affecting roughly
 * one man in twelve) hits the core mechanic rather than the decoration. This is what the board's
 * "Card symbols" preference has always promised - "show a shape next to each card's label" - and
 * what it stopped delivering when the drawn card faces replaced the old text-label cards, leaving
 * the toggle switched on and doing nothing.
 *
 * Drawn as geometry rather than five more VectorDrawables: these are a triangle, a circle, a square
 * and a diamond, they have to stay legible down to ~5dp in a tightly fanned hand, and generating
 * them here means they scale with the card instead of being resampled from a fixed viewport.
 * White fill over a dark outline, matching every other mark on the face.
 *
 * The shapes are chosen to stay distinguishable by silhouette alone at small size, which rules out
 * pairs that converge when they get small (a pentagon and a hexagon both read as "circle-ish").
 *
 * Public because card faces are not the only place colour alone carries meaning: the wild colour
 * picker is four unlabelled swatches, and the Round Table skin's active-colour orb is a bare
 * coloured circle. Both reuse this rather than growing their own shape vocabulary, so a player
 * learns "triangle = red" exactly once.
 */
@Composable
fun ColorSymbol(color: CardColor, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.16f
        val inset = stroke / 2f
        val path = Path()
        when (color) {
            CardColor.RED -> {
                // Triangle, point up.
                path.moveTo(w / 2f, inset)
                path.lineTo(w - inset, h - inset)
                path.lineTo(inset, h - inset)
                path.close()
            }
            CardColor.BLUE -> {
                // Diamond.
                path.moveTo(w / 2f, inset)
                path.lineTo(w - inset, h / 2f)
                path.lineTo(w / 2f, h - inset)
                path.lineTo(inset, h / 2f)
                path.close()
            }
            CardColor.GREEN -> {
                // Square.
                path.addRect(
                    androidx.compose.ui.geometry.Rect(
                        inset, inset, w - inset, h - inset
                    )
                )
            }
            CardColor.YELLOW -> {
                // Circle.
                path.addOval(
                    androidx.compose.ui.geometry.Rect(
                        inset, inset, w - inset, h - inset
                    )
                )
            }
            CardColor.WILD -> {
                // Four-pointed star - reads as "any colour" rather than as one of the four.
                val c = w / 2f
                val r = c - inset
                val notch = r * 0.34f
                path.moveTo(c, c - r)
                path.lineTo(c + notch, c - notch)
                path.lineTo(c + r, c)
                path.lineTo(c + notch, c + notch)
                path.lineTo(c, c + r)
                path.lineTo(c - notch, c + notch)
                path.lineTo(c - r, c)
                path.lineTo(c - notch, c - notch)
                path.close()
            }
        }
        drawPath(path, color = CardInk, style = Stroke(width = stroke))
        drawPath(path, color = Color.White)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerIndex(
    art: CardArt,
    rules: GameRules,
    cardWidth: Dp,
    density: androidx.compose.ui.unit.Density,
    align: Alignment,
    rotated: Boolean,
    compact: Boolean,
    showSymbols: Boolean,
    cardColor: CardColor
) {
    val pip = rulePip(art, rules)
    // The symbol alone is reason enough to draw this corner: a Wild has no corner text and no
    // corner glyph, and its colour is exactly what a colourblind player most needs told.
    if (!showSymbols && art.cornerText.isEmpty() && art.cornerGlyph == null && pip == null) return

    // Compact grows every element here - this corner is the card's whole identity once it's
    // overlapped down to a sliver, so it gets to be as big as the frame comfortably allows rather
    // than staying sized for a card being read whole.
    val textFraction = if (compact) 0.30f else 0.20f
    val pipFraction = if (compact) 0.16f else 0.12f
    val symbolFraction = if (compact) 0.17f else 0.13f

    // Wild Reverse Draw Four is the only face carrying BOTH a corner glyph and corner text, and it
    // needs the glyph sized against the text rather than against the card. At the standalone 19%
    // the icon box is a full 19% tall while a 20% font puts only ~14% of cap-height on the page, so
    // the arrows came out half again taller than the "+4" they belong to.
    val pairedWithText = art.cornerGlyph != null && art.cornerText.isNotEmpty()
    val glyphFraction = when {
        pairedWithText && compact -> 0.20f
        pairedWithText -> 0.13f
        compact -> 0.30f
        else -> 0.19f
    }

    Box(
        modifier = Modifier
            .align(align)
            // top:3.5%/left:6% and bottom:3.5%/right:6% of the panel.
            .padding(horizontal = cardWidth * 0.06f, vertical = cardWidth * 0.035f)
            .rotate(if (rotated) 180f else 0f)
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showSymbols) {
                ColorSymbol(
                    color = cardColor,
                    size = cardWidth * symbolFraction,
                    modifier = Modifier.padding(bottom = cardWidth * 0.012f)
                )
            }
            // Glyph and text sit SIDE BY SIDE, glyph first. The design stacks them in a column
            // (text above icon), but the printed Wild Reverse Draw Four reads "<arrows>+4" on one
            // line, and stacked they separate into two unrelated marks floating in the corner
            // rather than one compound index. Every other face has only one of the two, so a row
            // and a column are identical for them.
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                    cardWidth * 0.012f
                )
            ) {
                if (art.cornerGlyph != null) {
                    Icon(
                        painter = painterResource(art.cornerGlyph),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(cardWidth * glyphFraction)
                    )
                }
                if (art.cornerText.isNotEmpty()) {
                    OutlinedGlyph(
                        text = art.cornerText,
                        fontSize = with(density) { (cardWidth * textFraction).toSp() },
                        strokeWidth = with(density) { (cardWidth * 0.023f).toPx() }
                    )
                }
            }
            if (pip != null) {
                Icon(
                    painter = painterResource(pip),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .padding(top = cardWidth * 0.005f)
                        .size(cardWidth * pipFraction)
                )
            }
        }
    }
}

/**
 * White fill over a black outline.
 *
 * The design does this with `-webkit-text-stroke` + `paint-order: stroke fill`, which paints the
 * stroke UNDERNEATH the fill so only its outer half shows. Compose has no paint-order, so the two
 * are drawn as separate Texts in that order - the handoff calls out this exact substitution.
 */
@Composable
private fun OutlinedGlyph(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    strokeWidth: Float,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val base = TextStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.02f).em(fontSize),
            lineHeight = fontSize * 0.82f,
            textAlign = TextAlign.Center
        )
        Text(
            text = text,
            style = base.copy(
                color = CardInk,
                drawStyle = Stroke(width = strokeWidth, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            ),
            maxLines = 1
        )
        Text(text = text, style = base.copy(color = Color.White), maxLines = 1)
    }
}

private fun Float.em(size: androidx.compose.ui.unit.TextUnit) = (this * size.value).sp

// ── Painting ────────────────────────────────────────────────────────────────────────────────────

/** radial-gradient(58% 44% at 44% 34%, rgba(255,255,255,.26), transparent 72%) */
private fun DrawScope.drawGloss() {
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = 0.26f),
                0.72f to Color.Transparent,
                1f to Color.Transparent
            ),
            center = Offset(size.width * 0.44f, size.height * 0.34f),
            radius = size.width * 0.58f
        )
    )
}

/** radial-gradient(118% 88% at 50% 50%, transparent 40%, rgba(0,0,0,.4)) */
private fun DrawScope.drawVignette() {
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.40f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.4f)
            ),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = size.width * 1.18f
        )
    )
}

/**
 * The signature ellipse: STROKE ONLY, never filled, and big enough that it runs off all four edges
 * of the panel and is cut by the panel's own clip.
 *
 * Drawn in the design's own 200×310 space and stretched to the panel, matching the source SVG's
 * `preserveAspectRatio="none"` - so the oval is genuinely non-uniformly scaled rather than fitted,
 * which is what puts its sweep where the scans have it.
 */
private fun DrawScope.drawOval(filled: Boolean) {
    withTransform({
        scale(size.width / 200f, size.height / 310f, pivot = Offset.Zero)
        rotate(-31f, pivot = Offset(100f, 155f))
    }) {
        val topLeft = Offset(100f - 84f, 155f - 152f)
        val ovalSize = Size(168f, 304f)
        // Fill first, so the ink stroke reads as the oval's own rim rather than a line sitting on
        // top of it - the printed card has a hard dark edge around the white.
        if (filled) drawOval(color = Color.White, topLeft = topLeft, size = ovalSize)
        drawOval(
            color = CardInk,
            topLeft = topLeft,
            size = ovalSize,
            style = Stroke(width = 12f)
        )
    }
}

/**
 * The scanned cards are visibly used, and the handoff is explicit that this is what stops the faces
 * looking sterile: screen-blended hairline scratches over multiply-blended scuffs.
 *
 * Spacing is proportional rather than the design's literal pixel periods - those were authored
 * against a 156px card, and reused verbatim they would crowd a 34dp card in a fanned hand into
 * solid haze.
 */
private fun DrawScope.drawWear() {
    val diag = hypot(size.width, size.height)
    fun hatch(degrees: Float, period: Float, thickness: Float, color: Color, blend: BlendMode) {
        val rad = Math.toRadians(degrees.toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        // Step perpendicular to the line direction so spacing is true regardless of angle.
        val nx = -dy
        val ny = dx
        val centre = Offset(size.width / 2f, size.height / 2f)
        var t = -diag
        while (t < diag) {
            val p = Offset(centre.x + nx * t, centre.y + ny * t)
            drawLine(
                color = color,
                start = Offset(p.x - dx * diag, p.y - dy * diag),
                end = Offset(p.x + dx * diag, p.y + dy * diag),
                strokeWidth = thickness,
                blendMode = blend
            )
            t += period
        }
    }
    val unit = size.width
    hatch(64f, unit * 0.62f, unit * 0.006f, Color.White.copy(alpha = 0.17f), BlendMode.Screen)
    hatch(-38f, unit * 0.85f, unit * 0.005f, Color.White.copy(alpha = 0.10f), BlendMode.Screen)
    hatch(107f, unit * 0.57f, unit * 0.006f, Color.Black.copy(alpha = 0.12f), BlendMode.Multiply)
}

// ── Card -> art mapping ─────────────────────────────────────────────────────────────────────────

private class CardArt(
    val field: List<Color>,
    /** Centre glyph, or null when the centre is a numeral. */
    val glyph: Int?,
    val centreText: String,
    val cornerText: String,
    /** Corner glyph - action cards repeat their own mark instead of a character. */
    val cornerGlyph: Int?,
    val pipRule: PipRule
)

private enum class PipRule { NONE, ZERO_ROTATES, SEVEN_SWAPS }

private fun rulePip(art: CardArt, rules: GameRules): Int? = when (art.pipRule) {
    // "if the host turns Zero rotates or Seven swaps off, suppress the matching pip" - the face
    // states a rule, so a face that keeps the pip while the rule is off is simply lying about how
    // this table plays.
    PipRule.ZERO_ROTATES -> R.drawable.ic_uno_rotate_pip.takeIf { rules.zeroRotates }
    PipRule.SEVEN_SWAPS -> R.drawable.ic_uno_pip_swap.takeIf { rules.sevenSwaps }
    PipRule.NONE -> null
}

private fun fieldFor(color: CardColor): List<Color> = when (color) {
    CardColor.RED -> CardFieldRed
    CardColor.YELLOW -> CardFieldYellow
    CardColor.GREEN -> CardFieldGreen
    CardColor.BLUE -> CardFieldBlue
    CardColor.WILD -> CardFieldWild
}

/**
 * 6 and 9 carry a combining low line (U+0332) so they cannot be misread upside down - the corner
 * index appears at 180° on every card, which is exactly when that ambiguity bites.
 */
private fun numeral(n: Int): String = if (n == 6 || n == 9) "$n̲" else "$n"

private fun cardArt(card: Card): CardArt {
    val field = fieldFor(card.color)

    fun numbered(n: Int, pip: PipRule = PipRule.NONE) = CardArt(
        field = field, glyph = null, centreText = numeral(n),
        cornerText = numeral(n), cornerGlyph = null, pipRule = pip
    )

    /** Action cards use the SAME glyph twice - full size in the centre, 19% in the corner. */
    fun action(glyph: Int) = CardArt(
        field = field, glyph = glyph, centreText = "",
        cornerText = "", cornerGlyph = glyph, pipRule = PipRule.NONE
    )

    /** Only +2 and +4 use text in the corner; their centre is the card-cluster icon. */
    fun draw(glyph: Int, corner: String) = CardArt(
        field = field, glyph = glyph, centreText = "",
        cornerText = corner, cornerGlyph = null, pipRule = PipRule.NONE
    )

    return when (card.type) {
        CardType.ZERO -> numbered(0, PipRule.ZERO_ROTATES)
        CardType.ONE -> numbered(1)
        CardType.TWO -> numbered(2)
        CardType.THREE -> numbered(3)
        CardType.FOUR -> numbered(4)
        CardType.FIVE -> numbered(5)
        CardType.SIX -> numbered(6)
        CardType.SEVEN -> numbered(7, PipRule.SEVEN_SWAPS)
        CardType.EIGHT -> numbered(8)
        CardType.NINE -> numbered(9)

        CardType.SKIP -> action(R.drawable.ic_uno_skip)
        CardType.REVERSE -> action(R.drawable.ic_uno_reverse)
        CardType.SKIP_EVERYONE -> action(R.drawable.ic_uno_skip_everyone)
        CardType.DISCARD_ALL -> action(R.drawable.ic_uno_discardall)

        CardType.DRAW_TWO -> draw(R.drawable.ic_uno_draw2, "+2")
        CardType.DRAW_FOUR -> draw(R.drawable.ic_uno_draw4, "+4")

        CardType.WILD -> CardArt(field, R.drawable.ic_uno_wild, "", "", null, PipRule.NONE)
        CardType.WILD_DRAW_SIX -> draw(R.drawable.ic_uno_wilddraw, "+6")
        CardType.WILD_DRAW_TEN -> draw(R.drawable.ic_uno_wilddraw, "+10")
        CardType.WILD_REVERSE_DRAW_FOUR -> CardArt(
            field = field, glyph = R.drawable.ic_uno_wildrev, centreText = "",
            cornerText = "+4", cornerGlyph = R.drawable.ic_uno_reverse, pipRule = PipRule.NONE
        )

        // Not among the 68 scanned faces, so it's drawn from the printed card instead: the four
        // sad faces, clustered in the centre and repeated small in the corner exactly as the real
        // card does it. Deliberately NOT the colour pinwheel - that's the Wild face, and reusing
        // it here would make the two cards indistinguishable at a glance in a fanned hand.
        CardType.WILD_COLOR_ROULETTE -> action(R.drawable.ic_uno_roulette)

        // Flip's dark side isn't built (GameMode.isPlayable), so these are placeholders rather
        // than designed faces - the Flip deck ships with no art in this handoff either.
        else -> CardArt(
            field = field, glyph = null, centreText = shortLabel(card.type),
            cornerText = shortLabel(card.type), cornerGlyph = null, pipRule = PipRule.NONE
        )
    }
}

private fun shortLabel(type: CardType): String = when (type) {
    CardType.FLIP, CardType.DARK_FLIP -> "F"
    CardType.DARK_SKIP -> "S"
    CardType.DARK_REVERSE -> "R"
    CardType.DARK_WILD -> "W"
    CardType.DARK_DRAW_FIVE, CardType.DARK_DRAW_FIVE_NUMBER -> "5"
    CardType.DARK_ONE -> "1"
    CardType.DARK_TWO -> "2"
    CardType.DARK_THREE -> "3"
    CardType.DARK_FOUR -> "4"
    CardType.DARK_FIVE -> "5"
    CardType.DARK_SIX -> "6̲"
    CardType.DARK_SEVEN -> "7"
    CardType.DARK_EIGHT -> "8"
    CardType.DARK_NINE -> "9̲"
    else -> "?"
}
