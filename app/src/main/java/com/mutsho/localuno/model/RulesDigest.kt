package com.mutsho.localuno.model

/**
 * One line of "here is what is true at this table".
 *
 * [label] is the rule's name as the host screen names it; [detail] says what it does in the terms a
 * player at the table would use, not the terms the engine implements it in.
 */
data class RuleLine(val label: String, val detail: String)

/**
 * The four optional rules a host can switch on, with the wording the app uses for them everywhere.
 *
 * Canonical here rather than in the host screen so the lobby, the board and the setup screen cannot
 * drift into describing the same rule three different ways - a rule that is explained one way when
 * you switch it on and another way when you are losing to it is worse than one nobody explains.
 */
enum class HouseRule(val label: String, val detail: String) {
    STACKING(
        "Stacking",
        "Pass a draw penalty on — but only with the same card."
    ),
    LAST_CARD_NUMBER(
        "Last card must be a number",
        "You cannot win on Skip, Reverse, Wild or a draw card."
    ),
    ZERO_ROTATES(
        "Zero rotates hands",
        "Play a 0 and every hand shifts one seat in play direction."
    ),
    SEVEN_SWAPS(
        "Seven swaps hands",
        "Play a 7 and choose whose hand you take."
    )
}

/** Which of the optional rules this table actually has on, in a stable order. */
fun GameRules.enabled(): List<HouseRule> = buildList {
    if (stacking) add(HouseRule.STACKING)
    if (lastCardMustBeNumber) add(HouseRule.LAST_CARD_NUMBER)
    if (zeroRotates) add(HouseRule.ZERO_ROTATES)
    if (sevenSwaps) add(HouseRule.SEVEN_SWAPS)
}

/**
 * What is in force at this table, in a form any screen can render.
 *
 * Exists because every guest already HAS this - GameSettings travels whole inside LobbyUpdate and
 * sits on GameState.settings - and no screen has ever shown it. That mattered most for
 * [HouseRule.LAST_CARD_NUMBER], which can lose you a round you believed you had won, with nothing
 * anywhere to tell you why.
 *
 * Reports what is TRUE, not what is unusual. An earlier sketch of this listed only rules that
 * differed from the mode's own baseline, which would have meant a No Mercy table quietly omitting
 * the sevens and zeros on the grounds that they are "part of the mode" - a distinction that matters
 * to whoever configured the table and not at all to whoever is sitting at it.
 */
object RulesDigest {

    /**
     * Every line worth showing, ordered by how likely it is to surprise someone: the optional rules
     * first, then the knockout line, then the clock.
     *
     * Never empty - the clock line always applies, so there is always something to say.
     */
    fun activeRules(settings: GameSettings): List<RuleLine> = buildList {
        settings.rules.enabled().forEach { add(RuleLine(it.label, it.detail)) }

        // Only No Mercy knocks anyone out; the threshold is meaningless elsewhere and showing it
        // would imply a danger that does not exist at a Classic table.
        if (settings.gameMode == GameMode.NO_MERCY) {
            add(
                RuleLine(
                    "Knocked out at ${settings.mercyThreshold} cards",
                    "Reach that many and you are out for the rest of the round."
                )
            )
        }

        val seconds = settings.turnTimeoutSeconds
        add(
            if (seconds == null) {
                RuleLine("No turn clock", "Take as long as you like on your turn.")
            } else {
                RuleLine("${seconds}s turn clock", settings.turnTimeoutAction.blurb)
            }
        )
    }

    /**
     * A one-line summary for somewhere there is no room for the list.
     *
     * Counts only the optional rules, because that is the number people mean when they ask "are we
     * playing any house rules?" - the clock is always there and the knockout line is the mode.
     */
    fun headline(settings: GameSettings): String {
        val n = settings.rules.enabled().size
        return when (n) {
            0 -> "Standard rules"
            1 -> "1 house rule"
            else -> "$n house rules"
        }
    }
}
