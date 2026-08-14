package com.mutsho.localuno.model

/**
 * @param isPlayable whether the engine can actually run a round in this mode. The setup screens are
 *   matched to mockups that show all three decks, so Flip stays on screen and keeps its artwork -
 *   it is simply not selectable until its ruleset exists. This flag is the single source of truth
 *   for that; every place that offers a mode reads it rather than naming FLIP directly, so nothing
 *   has to be hunted down when the mode does land.
 */
enum class GameMode(
    val displayName: String,
    val description: String,
    val isPlayable: Boolean = true
) {
    CLASSIC(
        "Classic UNO",
        "Standard Mattel ruleset. First player to empty their hand wins."
    ),
    NO_MERCY(
        "UNO No Mercy",
        "168 cards. Stack draw cards by value. 25+ cards = knocked out. 7's swap hands, 0's rotate all hands."
    ),

    // Deck.createFlipDeck() builds the light side and GameEngine toggles GameState.isFlipDarkSide,
    // but no card carries a darkColor/darkType, so a Flip card currently flips the table to a side
    // that looks and plays identically. Until the dark faces and their ruleset exist, offering this
    // would promise a mode that silently deals a Classic-shaped round.
    FLIP(
        "UNO Flip",
        "Two-sided deck. A Flip card toggles between light side and brutal dark side.",
        isPlayable = false
    )
}
