package com.mutsho.localuno.network

import java.io.IOException
import java.io.Reader

/**
 * A peer sent more than [limit] characters without a newline. The connection is not recoverable -
 * the framing is already lost - so callers drop it rather than trying to resynchronise.
 */
class LineTooLongException(val limit: Int) :
    IOException("peer sent more than $limit characters with no newline")

/**
 * [BufferedReader.readLine] with a ceiling.
 *
 * The wire is newline-delimited JSON and both ends read it with a bare `readLine()`, which has no
 * length limit at all: a peer that sends bytes and never sends a newline makes the reader grow its
 * buffer until the process dies. It needs no PIN and no successful join - just a TCP connection to
 * the advertised port - and it works in both directions, so a malicious host can do it to every
 * guest just as easily.
 *
 * The cap is deliberately far above anything legitimate. The largest message on this wire is a
 * StateUpdate carrying one player's hand, which is a few kilobytes even for an absurd No Mercy hand;
 * 256 KB is two orders of magnitude of headroom and still nowhere near enough to hurt.
 *
 * Reads one character at a time on purpose. Asking the underlying reader for a big block first
 * would reintroduce the very allocation this exists to bound.
 *
 * @return the line without its terminator, or null at end of stream.
 * @throws LineTooLongException once [limit] characters have arrived with no newline among them.
 */
fun Reader.readBoundedLine(limit: Int = MAX_LINE_CHARS): String? {
    val sb = StringBuilder()
    while (true) {
        val c = read()
        if (c == -1) return if (sb.isEmpty()) null else sb.toString()
        val ch = c.toChar()
        if (ch == '\n') return sb.toString()
        // Tolerated rather than preserved: a peer using CRLF is well-behaved, and \r would
        // otherwise ride along into the JSON parser as trailing junk.
        if (ch == '\r') continue
        if (sb.length >= limit) throw LineTooLongException(limit)
        sb.append(ch)
    }
}

/** 256 KB. See [readBoundedLine] for why this is the number. */
const val MAX_LINE_CHARS: Int = 256 * 1024
