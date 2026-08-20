package com.mutsho.localuno.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.mutsho.localuno.ui.theme.*

/**
 * How the cards themselves look, independent of the table they are played on.
 *
 * Ported from `CardSkins.dc.html`. Local to the device by design - "everyone at the table keeps
 * whichever skin they picked" - so this never travels over the wire and is not part of GameSettings.
 * It sits in Settings alongside [BoardSkin] for the same reason that one does: changing how the
 * board is drawn mid-round is a source of bugs, not a feature.
 *
 * ## Why a style object and not 68 images
 *
 * There are 68 hand-drawn Noir faces sitting in the design project, and the obvious move is to
 * import them. The design says not to, and it is right:
 *
 *   > Noir is not a tint of Classic - seven geometry values differ (thinner oval, gentler -9° tilt,
 *   > smaller numerals and indices) and the numerals drop the black keyline for a lit glow. Treat it
 *   > as a second style object on the shared card composable.
 *
 * A baked face is also a face that cannot answer questions the board asks it. [UnoCardFace] dims
 * unplayable cards, suppresses the 0 and 7 rule pips when those house rules are off, swaps to a
 * compact layout in a tightly fanned hand, and adds colourblind symbols on request. A PNG or a
 * VectorDrawable of a finished card can do none of that - and VectorDrawable silently drops `<text>`,
 * which is every numeral on forty of those faces.
 *
 * So each skin is the handful of values that actually differ, and one card composable reads them.
 */
enum class CardSkin(
    val storageKey: String,
    val displayName: String,
    val description: String
) {
    CLASSIC(
        "classic", "Classic",
        "The printed deck — glossy field, cream keyline, honest card wear."
    ),
    NOIR(
        "noir", "Noir",
        "Ink-black card. The colour survives only in the oval, keyline and numerals."
    ),
    NEON(
        "neon", "Neon",
        "Arcade glow — lit rim, hollow numerals, no texture at all."
    ),
    PAPER(
        "paper", "Paper",
        "Matte litho stock. Softer inks, hairline keyline, no shine."
    ),
    FOIL(
        "foil", "Foil",
        "Brushed metal sweep with a silver keyline. Catches the light."
    ),
    BOLD(
        "bold", "Bold",
        "Flat colour, oversized index, maximum contrast. Best for accessibility."
    );

    companion object {
        fun fromStorageKey(key: String?): CardSkin =
            entries.firstOrNull { it.storageKey == key } ?: CLASSIC
    }
}

/**
 * The values a skin is allowed to change, and nothing else.
 *
 * Deliberately narrow. Everything a skin does in `CardSkins.dc.html` is a handful of CSS overrides
 * on one shared card, so anything a skin cannot express here is a sign the shared card should grow
 * a hook rather than the skin growing a special case.
 */
data class CardSkinStyle(
    /**
     * Replaces the suit gradient. Null keeps the card its own colour.
     *
     * A function of the suit rather than a fixed list, because Foil's sweep is built FROM the suit -
     * dark, suit, near-white, suit, dark - and a constant list could only ever be one suit's foil.
     */
    val field: ((suit: Color) -> List<Color>)? = null,
    /** The near-black bleed around the panel. */
    val bleed: Color = CardInk,
    /** Highlight sweep across the panel. 0 removes it. */
    val glossAlpha: Float = 1f,
    /** Darkening toward the edges. 0 removes it. */
    val vignetteAlpha: Float = 1f,
    /** Hairline scratches. 0 removes them. */
    val wearAlpha: Float = 1f,
    /** Suit-coloured inset keyline, as a fraction of card width. 0 draws none. */
    val keylineWidth: Float = 0f,
    /** The signature ellipse. Null hides it; otherwise the stroke colour, `null` inside meaning "the suit". */
    val ovalStroke: OvalStroke? = OvalStroke(),
    /** Centre numeral and corner index. */
    val numeral: NumeralStyle = NumeralStyle(),
    /** Degrees the centre mark is rotated. Classic leans hard; Noir is gentler. */
    val tilt: Float = -13f,
    /** Centre numeral size as a fraction of card width. */
    val numeralFraction: Float = 0.62f,
    /** Corner index size as a fraction of card width. */
    val indexFraction: Float = 0.20f,
    /** A suit-coloured glow behind glyphs and numerals, as a fraction of card width. 0 = none. */
    val glow: Float = 0f,
    /**
     * Whether a wild's oval is filled white.
     *
     * True for the printed deck, where the wild IS a black card whose only large feature is that
     * white oval. False for Noir, whose whole premise is that every face is the same ink body with
     * the colour reduced to line work - a filled white oval there is the one card breaking the rule
     * the skin is named for, and it looked like a Classic card that had wandered in.
     */
    val fillWildOval: Boolean = true
) {
    data class OvalStroke(
        /** Null means "use the suit colour". */
        val color: Color? = null,
        /** Stroke width in the ellipse's own 200x310 viewport units. */
        val width: Float = 12f
    )

    data class NumeralStyle(
        /** Null means white; otherwise a fixed colour, or the suit tone via [useSuitTone]. */
        val fill: Color? = null,
        val useSuitTone: Boolean = false,
        /** Outline colour. Null draws no outline - Noir and Neon both drop it. */
        val outline: Color? = CardInk,
        /** Outline width as a fraction of card width. */
        val outlineFraction: Float = 0.052f,
        /** Draw the numeral hollow: outline only, transparent fill. */
        val hollow: Boolean = false
    )
}

/**
 * Noir's lighter per-suit tone, for numerals and glyph glow.
 *
 * The deck carries two colours per suit - a line colour and a lighter glyph tone - so the numeral
 * reads as lit rather than painted. Values are the design's own (`NoirDeck.dc.html`, `--cg`).
 */
fun noirGlyphTone(base: Color): Color = when (base.value) {
    UnoRed.value -> Color(0xFFFF6B57)
    UnoYellow.value -> Color(0xFFFFDD5C)
    UnoGreen.value -> Color(0xFF84DB4E)
    UnoBlue.value -> Color(0xFF74AEEC)
    else -> Color(0xFFFFFFFF)
}

/** Noir's line colour per suit (`--ca`), a touch softer than the printed deck's. */
fun noirLineTone(base: Color): Color = when (base.value) {
    UnoRed.value -> Color(0xFFF04A36)
    UnoYellow.value -> Color(0xFFF5CB2A)
    UnoGreen.value -> Color(0xFF63B92F)
    UnoBlue.value -> Color(0xFF4E8FD6)
    else -> Color(0xFFDDE3F2)
}

fun styleFor(skin: CardSkin): CardSkinStyle = when (skin) {
    CardSkin.CLASSIC -> CardSkinStyle()

    // Ink body in every suit; the colour withdraws to line work. The numeral loses its black
    // keyline and gains a glow, which is what makes the card read as lit from behind rather than
    // printed. Wear drops to a whisper - a black card shows every scratch.
    CardSkin.NOIR -> CardSkinStyle(
        field = { listOf(Color(0xFF16161D), Color(0xFF0B0B10), Color(0xFF08080C)) },
        bleed = Color(0xFF0D0D12),
        glossAlpha = 0.38f,
        vignetteAlpha = 0.7f,
        wearAlpha = 0.28f,
        keylineWidth = 0.010f,
        ovalStroke = CardSkinStyle.OvalStroke(width = 4.5f),
        numeral = CardSkinStyle.NumeralStyle(useSuitTone = true, outline = null),
        tilt = -9f,
        numeralFraction = 0.54f,
        indexFraction = 0.17f,
        glow = 0.09f,
        fillWildOval = false
    )

    // Black field, lit rim, hollow type. No oval, no texture: the glow is the whole card.
    CardSkin.NEON -> CardSkinStyle(
        field = { listOf(Color(0xFF0B0B16), Color(0xFF05050A), Color(0xFF05050A)) },
        bleed = Color(0xFF05050A),
        glossAlpha = 0f,
        vignetteAlpha = 0f,
        wearAlpha = 0f,
        keylineWidth = 0.024f,
        ovalStroke = null,
        numeral = CardSkinStyle.NumeralStyle(useSuitTone = true, outline = null, hollow = true),
        glow = 0.10f
    )

    // Matte litho: no shine at all, a hairline keyline in the stock's own cream.
    CardSkin.PAPER -> CardSkinStyle(
        bleed = Color(0xFFEFE9DC),
        glossAlpha = 0f,
        vignetteAlpha = 0.55f,
        wearAlpha = 0.5f,
        ovalStroke = CardSkinStyle.OvalStroke(color = Color(0xFFEFE9DC), width = 7f),
        numeral = CardSkinStyle.NumeralStyle(
            fill = Color(0xFFF6F1E6),
            outline = Color(0xFF241A12),
            outlineFraction = 0.030f
        )
    )

    // Brushed metal sweep with a silver keyline.
    CardSkin.FOIL -> CardSkinStyle(
        // linear-gradient(118deg, dark, suit 26%, #f4f6ff 42%, suit 56%, dark 78%, suit) - the
        // near-white band is the highlight travelling across brushed metal.
        field = { suit ->
            val dark = lerp(suit, Color.Black, 0.45f)
            listOf(dark, suit, Color(0xFFF4F6FF), suit, dark, suit)
        },
        bleed = Color(0xFF15161C),
        glossAlpha = 0.55f,
        vignetteAlpha = 0.8f,
        wearAlpha = 0.2f,
        ovalStroke = CardSkinStyle.OvalStroke(color = Color(0xFFE8ECF7), width = 11f),
        numeral = CardSkinStyle.NumeralStyle(
            fill = Color(0xFFF4F6FF),
            outline = Color(0xFF15161C),
            outlineFraction = 0.046f
        )
    )

    // Flat colour, oversized index, no texture. The accessibility pick, so nothing decorative is
    // allowed to cost contrast.
    CardSkin.BOLD -> CardSkinStyle(
        glossAlpha = 0f,
        vignetteAlpha = 0f,
        wearAlpha = 0f,
        ovalStroke = CardSkinStyle.OvalStroke(color = Color.White, width = 10f),
        numeral = CardSkinStyle.NumeralStyle(outlineFraction = 0.060f),
        numeralFraction = 0.70f,
        indexFraction = 0.26f
    )
}

/**
 * The skin every card face reads.
 *
 * A CompositionLocal rather than a parameter: cards are drawn from the hand dial, the discard pile,
 * the seat rails, the card gallery and the settings preview, and threading a skin through every one
 * of those would touch a dozen signatures to deliver a value none of them care about.
 */
val LocalCardSkin = staticCompositionLocalOf { CardSkin.CLASSIC }
