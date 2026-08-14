package com.mutsho.localuno.ui.components

/** Which visual board skin is rendering the current game - a device-local preference, not synced. */
enum class BoardSkin(val storageKey: String, val displayName: String, val description: String) {
    ROUND_TABLE("round", "Round Table", "Felt oval, seats in an arc, classic oval card faces"),
    SIDE_RAILS("rails", "Side Rails", "Opponents pinned to the rails, no-text geometric card faces"),
    NO_MERCY("mercy", "No Mercy", "Red felt, knockout meters, stacking penalty tower");

    companion object {
        fun fromStorageKey(key: String?): BoardSkin =
            entries.find { it.storageKey == key } ?: ROUND_TABLE
    }
}
