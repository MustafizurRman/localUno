package com.mutsho.localuno.ui.theme

import androidx.compose.ui.graphics.Color

// UNO Card Colors — the game's card identity, unrelated to the app's chrome theme
val UnoRed = Color(0xFFED1C24)
val UnoYellow = Color(0xFFFFDE00)
val UnoGreen = Color(0xFF00A651)
val UnoBlue = Color(0xFF0072BC)
val UnoBlack = Color(0xFF1A1A2E)
val UnoDarkPurple = Color(0xFF16213E)
val CardBack = Color(0xFF2C2C54)

// Chrome screens (menu/lobby/board) fade from this old brand navy into the new
// Nocturne background — a deliberate blend of old identity + new ground, per the redesign.
val ChromeGradientTop = Color(0xFF0F3460)

// ─── Nocturne design tokens (from styles.css) ───────────────────────────────

val NocturneBg = Color(0xFF161826)
val NocturneSurface = Color(0xFF232532)
val NocturneText = Color(0xFFE9E9ED)
val NocturneAccent = Color(0xFF9184D9)
val NocturneAccent2 = Color(0xFFA7A1DB)
val NocturneDivider = Color(0x29E9E9ED) // #e9e9ed at 16% alpha

// Neutral ramp — same lightness scale across every role
val Neutral100 = Color(0xFFF3F5FE)
val Neutral200 = Color(0xFFE4E7F5)
val Neutral300 = Color(0xFFCFD3E5)
val Neutral400 = Color(0xFFB2B6CA)
val Neutral500 = Color(0xFF9397AB)
val Neutral600 = Color(0xFF75798C)
val Neutral700 = Color(0xFF595D6C)
val Neutral800 = Color(0xFF3F424D)
val Neutral900 = Color(0xFF292B31)

// Accent ramp
val Accent100 = Color(0xFFF5F4FF)
val Accent200 = Color(0xFFE7E5FE)
val Accent300 = Color(0xFFD2CEFD)
val Accent400 = Color(0xFFB5ABFC)
val Accent500 = Color(0xFF968AE0)
val Accent600 = Color(0xFF796CBF)
val Accent700 = Color(0xFF5D5294)
val Accent800 = Color(0xFF423A6A)
val Accent900 = Color(0xFF2B2741)

// Deck section-divider ground — deck-scale fills only, not interface colors
val SectionBg = Color(0xFF262A60)
val SectionGlow = Color(0xFF353B80)
val SectionGhost = Color(0xFF4C5397)

// Radii
val RadiusSm = 4
val RadiusMd = 8
val RadiusLg = 14

// App Theme Colors (Material scheme mapping)
val Primary = NocturneAccent
val PrimaryVariant = Accent700
val Secondary = NocturneAccent2
val Background = NocturneBg
val Surface = NocturneSurface
val SurfaceVariant = Neutral900
val OnPrimary = Neutral900
val OnSecondary = Neutral900
val OnBackground = NocturneText
val OnSurface = NocturneText

// Player Avatar Colors
val AvatarColors = listOf(
    Color(0xFFFF6B6B),
    Color(0xFF4ECDC4),
    Color(0xFFFFE66D),
    Color(0xFF95E1D3),
    Color(0xFFF38181),
    Color(0xFFAA96DA),
    Color(0xFFFCBF49),
    Color(0xFF2EC4B6)
)

// Menu screens sit on a deeper navy than the rest of the app: the design opens both with
// `linear-gradient(#0d1730, var(--color-bg) …)`. That hex is a literal in the mockup, not a design
// token, which is why it lives here rather than in the ramps above.
//
// There used to be a whole MenuNeutral*/MenuAccent* ramp beside it, transcribed from
// HANDOFF_MENUS.md's palette table. That table disagrees with the design system it claims to
// document - every entry sits roughly one step darker than the token of the same name in
// styles.css / _ds_manifest.json - and the mockups render with `var(--color-neutral-*)`, i.e. the
// real tokens. The menus now use the shared ramps below, which already carry the correct values.
// If a menu colour ever looks off again, trust _ds_manifest.json over the handoff prose.
val MenuGradientTop = Color(0xFF0D1730)

// ── Card faces (HANDOFF_CARDS.md §2) ────────────────────────────────────────────────────────────
// Sampled from the 68 scanned No Mercy cards and deliberately NOT the bright Mattel set used for
// the app's chrome (UnoRed etc. above). The handoff is explicit that these run deeper; substituting
// #ED1C24 here makes the deck look like a different product from the one that was scanned.
val CardFieldRed = listOf(Color(0xFFD91B0A), Color(0xFFB01309), Color(0xFF8E0F07))
val CardFieldYellow = listOf(Color(0xFFF2C41B), Color(0xFFE5B012), Color(0xFFC9930A))
val CardFieldGreen = listOf(Color(0xFF4A8F22), Color(0xFF367014), Color(0xFF28550F))
val CardFieldBlue = listOf(Color(0xFF2A63A2), Color(0xFF1D5188), Color(0xFF123C66))
val CardFieldWild = listOf(Color(0xFF26262E), Color(0xFF141419), Color(0xFF0B0B0E))

/** Flat token per colour - for chips, meters and anywhere a single value stands in for the field. */
val CardRed = Color(0xFFCB1607)
val CardYellow = Color(0xFFEDBE15)
val CardGreen = Color(0xFF3C7A1C)
val CardBlue = Color(0xFF235690)
val CardWild = Color(0xFF26262E)

/** The near-black the frame, the oval and every glyph outline are drawn in. */
val CardInk = Color(0xFF0A0A0A)
