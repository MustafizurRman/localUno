package com.mutsho.localuno

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No composable lambda may be left early.
 *
 * This is the invariant that, broken in exactly one line, killed the app intermittently for weeks:
 *
 *     Column(...) {
 *         if (!isChooser) {
 *             Text("SWAPPING HANDS")
 *             return@Column          // <- here
 *         }
 *         ...
 *     }
 *
 * `Column` is an INLINE composable, so that is a non-local return: it jumps straight out of the
 * enclosing function, past the end-group calls the Compose compiler generated for every group being
 * unwound. The composition is then left carrying more group ends than starts, and Compose does not
 * fail where it happened - the slot table simply no longer balances, and some LATER frame dies
 * popping an empty group stack:
 *
 *     java.lang.IndexOutOfBoundsException: Index -1 out of bounds for length 0
 *       at androidx.compose.runtime.Stack.pop(Stack.kt:26)
 *       at androidx.compose.runtime.ComposerImpl.exitGroup(Composer.kt:2333)
 *       at androidx.compose.runtime.ComposerImpl.endRoot(Composer.kt:1481)
 *
 * with not one line of application code on the trace. That absence is the whole problem: the report
 * names the frame that tripped, never the one that broke the ground. This one hid behind a branch
 * that needs somebody ELSE to play a 7 with seven-swaps on, so it presented as a random crash, and
 * it survived a Compose runtime bump, a dozen overlay bisects, and being declared ruled out twice.
 *
 * It reads as ordinary Kotlin, which is exactly why a person will write it again. So it is checked
 * mechanically instead of remembered.
 *
 * Scope: this looks for `return@X` where X is a composable layout or container whose content lambda
 * is inline. Returns from `forEach`, `let`, `LaunchedEffect`, `drawBehind`, `clickable` and friends
 * are ordinary Kotlin control flow with no composition groups involved, and are left alone.
 */
class ComposableReturnGuardTest {

    /**
     * Content-lambda names it is never safe to return out of. All of these are inline composables
     * (or take an inline composable content slot), so a labelled return crosses group boundaries.
     */
    private val forbidden = listOf(
        "Column", "Row", "Box", "BoxWithConstraints", "Surface", "Card",
        "Scaffold", "LazyColumn", "LazyRow", "FlowRow", "FlowColumn",
        "AnimatedVisibility", "AnimatedContent", "Crossfade", "key", "CompositionLocalProvider"
    )

    private fun uiSources(): List<File> {
        val root = File("src/main/java/com/mutsho/localuno/ui")
        assertTrue("UI sources not found at ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test fun `no composable lambda is returned out of`() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                forbidden.forEach { name ->
                    if (Regex("""return@$name\b""").containsMatchIn(code)) {
                        offenders += "${file.name}:${i + 1}  ${line.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "A labelled return out of an inline composable unbalances Compose's group stack and " +
                "kills a later frame from inside the runtime, with no application code on the " +
                "trace. Use if/else instead.\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * The same bug without a label on it.
     *
     * `return@Column` is the recognisable form, but a BARE `return` inside an inline composable
     * lambda is a non-local return too - it leaves the enclosing function from inside the lambda,
     * past exactly the same end-group calls:
     *
     *     Column {
     *         if (x) return      // <- identical damage, no label to grep for
     *         Text("hi")
     *     }
     *
     * It is legal Kotlin and reads as even more ordinary than the labelled version, so it is the
     * one a person is likelier to write. The labelled guard above would never see it.
     *
     * A bare `return` at the TOP of a composable function, before any lambda is open, is a
     * different thing and is fine - the compiler emits the end calls on that path. Only returns
     * made while an inline composable lambda is open are counted, which is why this tracks a stack
     * of open lambdas rather than just matching text.
     */
    @Test fun `no inline composable lambda is returned out of without a label either`() {
        val offenders = mutableListOf<String>()
        uiSources().forEach { file ->
            offenders += scanBareReturns(file.readLines()).map { (line, text) ->
                "${file.name}:$line  $text"
            }
        }
        assertTrue(
            "A bare `return` inside an inline composable lambda is a non-local return and " +
                "unbalances the group stack exactly as `return@Column` does.\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * Returns (lineNumber, text) for every bare `return` made while an inline composable lambda is
     * open. Brace counting, not parsing - good enough because it only has to be right about which
     * scopes are open, and a stray brace inside a string would have to also coincide with a bare
     * return to matter.
     */
    private fun scanBareReturns(lines: List<String>): List<Pair<Int, String>> {
        val opensLambda = Regex("""\b(${forbidden.joinToString("|")})\s*[({]""")
        val localFun = Regex("""\bfun\s+\w+\s*\(""")
        val bareReturn = Regex("""(^|[\s{;])return\s*($|[^@\w])""")
        val openDepths = ArrayDeque<Int>()
        // Local helper functions declared inside a composable. A `return` in one of those is an
        // ordinary return from that helper - it never crosses a composition group. Without this
        // the guard fires on HandTransferOverlay's seatOffset/easedAt and HandArc's, all of which
        // are correct code, and a guard that cries wolf gets deleted rather than fixed.
        val funDepths = ArrayDeque<Int>()
        var depth = 0
        val out = mutableListOf<Pair<Int, String>>()
        lines.forEachIndexed { i, raw ->
            val code = raw.substringBefore("//")
            if (openDepths.isNotEmpty() && funDepths.isEmpty() && bareReturn.containsMatchIn(code)) {
                out += (i + 1) to raw.trim()
            }
            val before = depth
            depth += code.count { it == '{' } - code.count { it == '}' }
            if (code.contains('{')) {
                // `before > 0` is what tells a LOCAL helper from the composable's own declaration.
                // Without it the outer `fun Foo() {` matched too, which left a function scope open
                // for the whole file and quietly disabled this guard everywhere - it reported a
                // clean tree by scanning nothing. The proving case below is the only reason that
                // was caught.
                if (before > 0 && localFun.containsMatchIn(code)) funDepths.addLast(before)
                else if (opensLambda.containsMatchIn(code)) openDepths.addLast(before)
            }
            while (funDepths.isNotEmpty() && depth <= funDepths.last()) funDepths.removeLast()
            while (openDepths.isNotEmpty() && depth <= openDepths.last()) openDepths.removeLast()
        }
        return out
    }

    @Test fun `a return from a local helper function is left alone`() {
        // The shape that made this guard fail on correct code the first time it ran: a local fun
        // declared inside a composable, inside a layout. Its return is its own.
        val sample = listOf(
            "@Composable fun Fine() {",
            "    Box {",
            "        fun easedAt(t: Float): Float {",
            "            return t * t",
            "        }",
            "        Text(\"\${easedAt(1f)}\")",
            "    }",
            "}"
        )
        assertTrue("a local fun's return is not a non-local return", scanBareReturns(sample).isEmpty())
    }

    @Test fun `the unlabelled guard catches a bare return inside a Column`() {
        // Same discipline as the labelled guard: a guard nobody has seen fail is a guard nobody
        // knows works.
        val sample = listOf(
            "@Composable fun Bad() {",
            "    Column {",
            "        if (x) return",
            "        Text(\"hi\")",
            "    }",
            "}"
        )
        assertTrue("must flag the bare return", scanBareReturns(sample).size == 1)
    }

    @Test fun `a guard clause at the top of a composable is left alone`() {
        // The safe and extremely common shape - ConnectionQualityDot, ReconnectingOverlay and
        // several others all do this today. Flagging it would make the guard unusable.
        val sample = listOf(
            "@Composable fun Fine(ageMs: Long?) {",
            "    if (ageMs == null) return",
            "    Box {",
            "        Text(\"hi\")",
            "    }",
            "}"
        )
        assertTrue("must not flag a top-level guard clause", scanBareReturns(sample).isEmpty())
    }

    @Test fun `the guard would catch the line that actually crashed the app`() {
        // A guard nobody has seen fail is a guard nobody knows works. This is the exact text that
        // was in SwapPickerOverlay.
        val sample = listOf(
            "                Text(\"SWAPPING HANDS\")",
            "                return@Column",
            "            }"
        )
        val hits = sample.count { line ->
            forbidden.any { Regex("""return@$it\b""").containsMatchIn(line.substringBefore("//")) }
        }
        assertTrue("the guard must match the real offender", hits == 1)
    }

    @Test fun `the guard leaves ordinary kotlin returns alone`() {
        // These are all in the tree today and all fine - no composition group is involved.
        val innocent = listOf(
            "if (color == Color.Transparent) return@drawBehind",
            "if (opacityNow() <= 0.01f) return@clickable",
            "if (trigger == 0) return@LaunchedEffect",
            "val server = lobbyViewModel.getServer() ?: return@LaunchedEffect",
            "players.forEach { if (it.isBot) return@forEach }"
        )
        innocent.forEach { line ->
            assertTrue(
                "false positive on: $line",
                forbidden.none { Regex("""return@$it\b""").containsMatchIn(line) }
            )
        }
    }
}
