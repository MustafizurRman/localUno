package com.mutsho.localuno.model

/**
 * Cleans the two strings a peer gets to choose: their display name and their emoji reaction.
 *
 * Both arrive from another device, are stored on the host's roster, and are broadcast to everyone
 * at the table. Neither was bounded or filtered in any way, which made them the only untrusted text
 * in the app with a straight path onto every screen at the table.
 *
 * Applied at ingress - once, where the message arrives - rather than defensively at each render
 * site. There are a dozen places a name is drawn and one place it enters, and the dozen will not
 * stay in step.
 *
 * Three separate problems, only one of which is about length:
 *
 *  - **Size.** Nothing capped these. A name a few hundred kilobytes long fits comfortably inside
 *    the wire's line limit and is then laid out by Compose on every device at the table.
 *  - **Newlines.** The move log renders lines like "Ada played a red 7". A name containing a
 *    newline lets its owner write additional lines into everyone else's log, saying whatever they
 *    like - the log has no other author, so there is nothing to tell them apart.
 *  - **Invisible characters.** Zero-width and control characters make two different players
 *    indistinguishable on screen, which is the whole basis of the app's "who is at this table".
 */
object Sanitize {

    /** Long enough for any real name, short enough to lay out in a seat label. */
    const val MAX_NAME = 20

    /** A couple of emoji with skin-tone or ZWJ modifiers, and no more. */
    const val MAX_EMOJI = 8

    /** Shown when a name cleans away to nothing, so a seat is never nameless. */
    const val FALLBACK_NAME = "Player"

    /**
     * A display name safe to store and draw.
     *
     * Whitespace is collapsed rather than merely trimmed: interior newlines and tabs are the part
     * that matters, and a name of forty spaces should not read as a blank seat.
     */
    fun displayName(raw: String?): String = clean(raw, MAX_NAME).ifEmpty { FALLBACK_NAME }

    /**
     * An emoji reaction safe to relay, or null if there is nothing worth sending.
     *
     * Null rather than a fallback: an unusable reaction should vanish, not become a substitute one
     * the sender did not choose.
     */
    fun emoji(raw: String?): String? = clean(raw, MAX_EMOJI).ifEmpty { null }

    /**
     * Shared cleaning: line breaks and other control characters become a SPACE, invisible
     * characters are deleted outright, runs of whitespace collapse, and the result is trimmed and
     * capped.
     *
     * The distinction between "becomes a space" and "is deleted" is the whole subtlety, and it took
     * two attempts. A newline is a SEPARATOR: deleting it turns "Ada\nLovelace" into
     * "AdaLovelace", running two words together, so whitespace becomes a space. A BEL or an escape
     * is not a separator and should simply vanish, and neither should a zero-width or bidi
     * character, which have no business widening a name.
     *
     * Every variant satisfies the security property - no line break survives any of them - which is
     * exactly why the wrong one is easy to ship and why each is pinned by its own test.
     */
    private fun clean(raw: String?, max: Int): String =
        (raw ?: "")
            .map { if (it.isWhitespace()) ' ' else it }
            .filter { it.isPrintableForName() }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(max)
            .trim()

    /**
     * Excludes control characters, line separators and the zero-width/bidi range.
     *
     * Deliberately permissive about scripts - names in any language are welcome, and a filter that
     * allowed only Latin would be a bug, not a security control. It rejects characters that are
     * invisible or that move the cursor, which is a different question from which alphabet they
     * belong to.
     */
    private fun Char.isPrintableForName(): Boolean {
        if (isISOControl()) return false
        return when (this) {
            '­',                      // soft hyphen
            '​', '‌', '‍',  // zero-width space / non-joiner / joiner
            '‎', '‏',            // left-to-right / right-to-left marks
            ' ', ' ',            // line / paragraph separator
            '‪', '‫', '‬', '‭', '‮',  // bidi overrides
            '﻿' -> false              // zero-width no-break space
            else -> true
        }
    }
}
