package com.mutsho.localuno.ui.components

import com.mutsho.localuno.model.Card
import com.mutsho.localuno.model.CardColor

private val displayColorOrder = mapOf(
    CardColor.RED to 0,
    CardColor.YELLOW to 1,
    CardColor.GREEN to 2,
    CardColor.BLUE to 3,
    CardColor.WILD to 4
)

/**
 * Display-only ordering (by color, then by point value - which conveniently already puts
 * numbers 0-9 before action cards before wilds). Never mutates game/network state, purely
 * how a hand renders on this device.
 */
fun List<Card>.sortedForDisplay(): List<Card> =
    sortedWith(compareBy({ displayColorOrder[it.color] ?: 9 }, { it.points }))
