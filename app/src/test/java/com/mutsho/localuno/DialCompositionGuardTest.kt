package com.mutsho.localuno

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The dial's spin angle must never be read by the composition.
 *
 * `theta` is rewritten on every frame by the physics loop. While anything composed subscribed to
 * it, the app died on the emulator inside two rounds of bot play - in the Compose runtime, popping
 * an empty group stack at `endRoot`, with no application code anywhere on the trace. A report like
 * that names the frame that tripped, not the one that broke the ground, which is why this survived
 * six readings of the stack and one Compose-runtime upgrade hypothesis before a soak test caught it
 * by bisection.
 *
 * Two shapes were tried and both crashed: reading `theta` straight into the composition, and then
 * hiding it behind `derivedStateOf`. That second failure is the one worth remembering, because it
 * looks like the textbook fix. `derivedStateOf` narrows how OFTEN the composition is invalidated;
 * it does not stop the composition being a subscriber to a frame-rate value, and the subscription
 * is the part that does not survive. Freezing the loop made three soak rounds clean; publishing the
 * selection out of the loop instead made eight clean while the dial still spun.
 *
 * So: `theta` belongs to `offset {}` and `graphicsLayer {}` - layout and draw, the deferred phases,
 * where a 60fps value is free - and to the loop and drag handler that write it. Nowhere else.
 *
 * Scope, stated plainly: this catches `theta` being READ in composition. It cannot catch the same
 * mistake laundered through a helper - `val a = angleNow()` at composition level mentions no
 * `theta` and would pass. The soak driver in scratchpad/drive.py is what covers that; this test is
 * the cheap half that runs on every build.
 */
class DialCompositionGuardTest {

    private val handArc = File("src/main/java/com/mutsho/localuno/ui/components/HandArc.kt")

    /** Lambdas and declarations where reading a per-frame value is correct. */
    private val safeOpeners = listOf(
        "object DialMath",          // Compose-free arithmetic; `theta` there is a parameter name
        "LaunchedEffect(",          // the physics loop - it owns theta
        ".offset {",                // layout phase
        ".graphicsLayer {",         // draw phase
        ".drawBehind {",            // draw phase
        "pointerInput(",            // the drag handler
        "clickable(",               // event callback - runs on a tap, not during composition
        "withoutReadObservation",   // explicitly opted out of subscribing
        "fun angleNow",             // local fun: evaluated at call time, not here
        "fun opacityNow"
    )

    /** The declaration itself is not a read. */
    private val declaration = Regex("""var\s+theta\s+by\s+remember""")

    @Test
    fun `the dial source is present`() {
        // Guards the guard - a moved file would make the scan below vacuously pass.
        assertTrue("HandArc.kt not found at $handArc", handArc.isFile)
    }

    @Test
    fun `theta is never read during composition`() {
        val offenders = scan(handArc.readLines())
        assertTrue(
            "reading the dial's spin angle in composition makes the composition run at frame " +
                "rate, which crashes the Compose runtime - move the read into offset {} or " +
                "graphicsLayer {}:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `the scanner actually detects a violation`() {
        // Without this the suite could pass because the scanner is broken rather than because the
        // file is clean. That has happened here before, more than once.
        val planted = listOf(
            "        val selected by remember { derivedStateOf { nearest(n, theta) } }"
        )
        assertTrue("scanner missed a composition-level read", scan(planted).isNotEmpty())

        val safe = listOf(
            "                            .offset {",
            "                                val rad = angleNow() * theta",
            "                            }"
        )
        assertTrue("scanner fired on a read inside offset {}", scan(safe).isEmpty())
    }

    // ── scanner ─────────────────────────────────────────────────────────────

    private val theta = Regex("""\btheta\b""")

    /**
     * Every line reading `theta` from outside a [safeOpeners] region.
     *
     * Tracks brace depth and remembers which line opened each level, so a read is judged by the
     * lambda it actually sits in rather than by how it is spelled.
     */
    private fun scan(lines: List<String>): List<String> {
        val offenders = mutableListOf<String>()
        val openers = ArrayDeque<String>()

        lines.forEachIndexed { i, line ->
            val code = line.substringBefore("//")
            val trimmed = line.trim()
            val isComment = trimmed.startsWith("//") || trimmed.startsWith("*")

            if (!isComment && theta.containsMatchIn(code) && !declaration.containsMatchIn(code)) {
                val safe = openers.any { opener -> safeOpeners.any { it in opener } } ||
                    safeOpeners.any { it in code }
                if (!safe) offenders += "HandArc.kt:${i + 1}  $trimmed"
            }

            // Depth bookkeeping last, so a read on the same line as its opener is judged safe.
            for (c in code) {
                when (c) {
                    '{', '(' -> openers.addLast(code)
                    '}', ')' -> openers.removeLastOrNull()
                }
            }
        }
        return offenders
    }
}
