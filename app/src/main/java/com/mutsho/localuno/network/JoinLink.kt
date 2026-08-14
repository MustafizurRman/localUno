package com.mutsho.localuno.network

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Everything a phone needs to join a table, in one scannable string.
 *
 * Deliberately plain text parsed by hand rather than `android.net.Uri`: this is the one piece of
 * the QR path where a mistake is silent (a wrong port reads as "host not responding", a dropped
 * PIN flag as a rejected join), and hand-rolled parsing can be unit-tested on the JVM while
 * `Uri` cannot.
 *
 * Shape: `localuno://join?h=<ipv4>&p=<port>&n=<url-encoded name>&pin=<0|1>`
 *
 * Unknown query keys are ignored and missing optional ones fall back, so a QR produced by a later
 * build that carries extra fields still parses here instead of failing shut - the two phones at a
 * table are frequently not on the same version.
 */
data class JoinLink(
    val host: String,
    val port: Int,
    val lobbyName: String,
    val hasPin: Boolean
) {
    fun encode(): String {
        val n = URLEncoder.encode(lobbyName, Charsets.UTF_8.name())
        return "$SCHEME://$AUTHORITY?h=$host&p=$port&n=$n&pin=${if (hasPin) 1 else 0}"
    }

    companion object {
        const val SCHEME = "localuno"
        const val AUTHORITY = "join"

        /** Null for anything that isn't one of our links - a scanner sees every QR in the world. */
        fun decode(raw: String): JoinLink? {
            val trimmed = raw.trim()
            val prefix = "$SCHEME://$AUTHORITY?"
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return null

            val params = HashMap<String, String>()
            trimmed.substring(prefix.length)
                .split('&')
                .forEach { pair ->
                    val eq = pair.indexOf('=')
                    if (eq <= 0) return@forEach
                    val key = pair.substring(0, eq)
                    val value = pair.substring(eq + 1)
                    params[key] = try {
                        URLDecoder.decode(value, Charsets.UTF_8.name())
                    } catch (_: Exception) {
                        return@forEach
                    }
                }

            val host = params["h"]?.takeIf { it.isNotBlank() } ?: return null
            // A port outside the ephemeral/user range is not something we ever emit, and connecting
            // to a privileged port on a stranger's say-so is not a thing to do quietly.
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return JoinLink(
                host = host,
                port = port,
                lobbyName = params["n"].orEmpty(),
                hasPin = params["pin"] == "1"
            )
        }
    }
}
