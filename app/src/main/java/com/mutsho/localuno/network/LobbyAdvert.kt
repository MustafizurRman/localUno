package com.mutsho.localuno.network

import com.mutsho.localuno.model.GameMode
import com.mutsho.localuno.model.Sanitize
import com.mutsho.localuno.model.WireLimits

/**
 * Validates what an mDNS advertisement claims about a table.
 *
 * Everything in a service announcement is chosen by whoever broadcast it, and mDNS is
 * unauthenticated: anybody on the network can advertise a table, with any name, any mode and any
 * numbers. None of it was checked. The lobby name went straight onto the Join screen, the mode
 * string was rendered raw when it matched no known mode, and the counts were displayed as-is.
 *
 * The same values arriving by QR code ARE checked - JoinLink refuses a port outside 1..65535, with
 * a comment about not connecting to a privileged port on a stranger's say-so. Discovery is the same
 * data from a less trustworthy source and had none of that, which is the sort of gap that opens
 * when one path is written carefully and its sibling is written later.
 *
 * Pure, and separate from NsdHelper, so it can be tested without Android's NSD stack.
 */
object LobbyAdvert {

    /** Enough for a table name, short enough for a list row. */
    const val MAX_LOBBY_NAME = 28

    /** Shown when a name cleans away to nothing, so a row is never blank. */
    const val FALLBACK_LOBBY = "Unnamed table"

    /**
     * A table name safe to render. Same treatment as a player name - bounded, no line breaks, no
     * invisible characters - because it lands in the same kind of list and comes from a less
     * trusted place.
     */
    fun name(raw: String?, fallback: String? = null): String {
        val cleaned = Sanitize.displayName(raw)
            .takeIf { it != Sanitize.FALLBACK_NAME }
            ?.take(MAX_LOBBY_NAME)
        if (cleaned != null) return cleaned
        // The service name is the next best label, and it is also attacker-chosen, so it gets the
        // same treatment rather than being trusted for being structural.
        val fromService = Sanitize.displayName(fallback)
            .takeIf { it != Sanitize.FALLBACK_NAME }
            ?.take(MAX_LOBBY_NAME)
        return fromService ?: FALLBACK_LOBBY
    }

    /**
     * The advertised mode, normalised to one this app actually has.
     *
     * The Join screen fell back to rendering the raw string when it matched no GameMode, which made
     * an arbitrary attacker-chosen value into on-screen text. An unrecognised mode is far more
     * likely to be junk than a mode from the future, so it reads as Classic.
     */
    fun mode(raw: String?): String =
        GameMode.entries.firstOrNull { it.name == raw }?.name ?: GameMode.CLASSIC.name

    /** Seat counts clamped to something a table could plausibly have. */
    fun seats(raw: Int?, default: Int): Int =
        (raw ?: default).coerceIn(1, WireLimits.MAX_PLAYERS)

    /**
     * The advertised port, or null if the advertisement should be ignored entirely.
     *
     * Matches JoinLink's rule deliberately: a lobby we cannot legitimately connect to is noise at
     * best, and a privileged port is somewhere we should not be pointing a socket because a
     * stranger's TXT record said so.
     */
    fun port(raw: Int): Int? = raw.takeIf { it in 1..65535 }

    /** The resolved address, or null if there is nothing usable to connect to. */
    fun host(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
