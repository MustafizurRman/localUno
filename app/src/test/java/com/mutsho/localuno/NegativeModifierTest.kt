package com.mutsho.localuno

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source scan for modifier arguments that Compose rejects at RUNTIME.
 *
 * `Modifier.padding((-6).dp)` compiles cleanly and throws `IllegalArgumentException: Padding must
 * be non-negative` the moment it composes. That is a crash reachable only by opening the exact
 * screen, and two of them shipped: the in-game skin picker crashed every time it was opened, and
 * the 7's swap picker crashed the moment it appeared.
 *
 * The swap one is the instructive case. It sat harmless for weeks because a SEPARATE bug meant the
 * picker auto-resolved before it ever rendered - fixing that bug is what turned a dormant line into
 * a crash on every 7 played. Nothing in the type system, the compiler, or the rest of this suite
 * had an opinion about either.
 *
 * A negative offset is the right way to express a CSS-style negative margin: `offset` shifts where
 * a thing is drawn, while `padding` asks the layout for space and cannot be given less than none.
 *
 * Deliberately a source scan rather than a Compose UI test - it needs no device, costs nothing, and
 * covers every screen at once, including the ones nobody has opened lately.
 */
class NegativeModifierTest {

    private val sourceRoot = File("src/main/java/com/mutsho/localuno")

    private fun kotlinSources(): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the source tree is present`() {
        // Guards the guard: a wrong working directory would make every scan below vacuously pass.
        val files = kotlinSources()
        assertTrue("found no Kotlin sources under $sourceRoot", files.size > 20)
    }

    @Test
    fun `no modifier is given a negative padding`() {
        val offenders = scan(listOf("padding"))
        assertTrue(
            "negative padding throws the moment it composes - use offset() instead:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `no modifier is given a negative size`() {
        val offenders = scan(
            listOf("size", "width", "height", "requiredSize", "requiredWidth", "requiredHeight")
        )
        assertTrue(
            "negative size throws at runtime - use offset() to move something instead:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `the scanner actually detects a negative value`() {
        // Proves the detector works. Without this the suite could pass because the matcher is
        // broken rather than because the tree is clean - which is exactly what happened on the
        // first attempt: a regex that treated `(...)` as one opaque group stepped straight over
        // `(-6).dp` and reported a live crash as healthy.
        assertTrue(hasNegative("Modifier.padding(top = (-6).dp)", "padding"))
        assertTrue(hasNegative("Modifier.padding(-4.dp)", "padding"))
        assertTrue(hasNegative("Modifier.padding(top = (-8).dp)", "padding"))
        assertTrue(hasNegative("Modifier.size(width = (-2).dp, height = 4.dp)", "size"))

        // ...and does not fire on healthy code.
        assertTrue(!hasNegative("Modifier.padding(top = 6.dp)", "padding"))
        assertTrue(!hasNegative("Modifier.padding(horizontal = w * 0.06f)", "padding"))
        assertTrue(!hasNegative("Modifier.offset(y = (-6).dp)", "padding"))
        assertTrue(!hasNegative("Modifier.size(cardW, cardH)", "size"))
    }

    // ── scanner ─────────────────────────────────────────────────────────────

    private val negative = Regex("""-\s*\d""")

    private fun hasNegative(line: String, name: String) =
        callArgs(line, name).any { negative.containsMatchIn(it) }

    /**
     * The argument text of every `.name(...)` call on a line, with nested parentheses balanced.
     *
     * A plain regex is not enough, and getting that wrong is how the first version of this scan
     * passed while a live negative padding sat in the tree: the value is normally written
     * `(-6).dp`, so the minus lives inside its own parentheses.
     */
    private fun callArgs(line: String, name: String): List<String> {
        val out = mutableListOf<String>()
        val needle = ".$name("
        var from = 0
        while (true) {
            val start = line.indexOf(needle, from)
            if (start < 0) return out
            var depth = 0
            var i = start + needle.length - 1
            val sb = StringBuilder()
            while (i < line.length) {
                val c = line[i]
                if (c == '(') depth++
                if (c == ')') {
                    depth--
                    if (depth == 0) break
                }
                if (depth >= 1 && !(depth == 1 && c == '(')) sb.append(c)
                i++
            }
            out += sb.toString()
            from = start + needle.length
        }
    }

    private fun scan(names: List<String>): List<String> {
        val offenders = mutableListOf<String>()
        for (file in kotlinSources()) {
            file.readLines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) {
                    return@forEachIndexed
                }
                for (name in names) {
                    if (hasNegative(line, name)) {
                        offenders += "${file.name}:${i + 1}  ${line.trim()}"
                    }
                }
            }
        }
        return offenders
    }
}
