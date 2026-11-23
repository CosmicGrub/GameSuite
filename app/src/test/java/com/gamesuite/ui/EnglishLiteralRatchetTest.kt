package com.gamesuite.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ratchet on hard-coded English in the screens: every `Text("...")`, `contentDescription = "..."`,
 * `label = "..."`, `onClickLabel = "..."` (and title/placeholder) literal that contains letters
 * counts as debt, because it cannot be translated. The per-file counts live in
 * `src/test/resources/english-literal-baseline.txt`; this test FAILS when any file's count rises or
 * a screen file appears that is not in the baseline with a literal, so the debt can only shrink.
 *
 * After moving strings to resources, regenerate the baseline (it only ever needs to go down):
 *   ENGLISH_BASELINE_REGEN=1 ./gradlew :app:testDebugUnitTest --tests '*EnglishLiteralRatchetTest*'
 * (or scripts/regen-english-baseline.sh). See docs/design/shared-platform.md.
 */
class EnglishLiteralRatchetTest {

    object Scanner {
        private val templates = Regex("""\$\{[^}]*\}|\$[A-Za-z_]\w*""")
        // Text("lit"  /  Text(text = "lit"  /  attr = "lit"
        private val patterns = listOf(
            Regex("""\bText\(\s*(?:text\s*=\s*)?"((?:[^"\\n]|\.)*)""""),
            Regex("""\b(?:contentDescription|label|onClickLabel|title|placeholder)\s*=\s*"((?:[^"\\n]|\.)*)"""")
        )

        fun count(source: String): Int {
            var n = 0
            for (line in source.lineSequence()) {
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) continue
                for (p in patterns) for (m in p.findAll(line)) {
                    val literal = templates.replace(m.groupValues[1], "")
                    if (literal.any { it.isLetter() }) n++
                }
            }
            return n
        }

        fun scan(dir: File): Map<String, Int> =
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") } ?: emptyArray())
                .sortedBy { it.name }
                .associate { it.name to count(it.readText()) }
                .filterValues { it > 0 }

        fun render(counts: Map<String, Int>): String =
            counts.toSortedMap().entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }

        fun parse(text: String): Map<String, Int> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { val (k, v) = it.split("="); k to v.toInt() }
    }

    private val uiDir = File("src/main/java/com/gamesuite/ui")
    private val baselineFile = File("src/test/resources/english-literal-baseline.txt")

    @Test fun scannerCountsLiteralsButNotTemplatesOrResources() {
        val src = """
            Text("Hello")
            Text(text = "World")
            Text(stringResource(R.string.x))
            Text("${'$'}score")
            Text("${'$'}{a} / ${'$'}{b}")
            Text("Score: ${'$'}score")
            Icon(contentDescription = "Close")
            Row(label = "Pause", onClickLabel = "Go")
            // Text("commented out")
            Text("1234")
        """.trimIndent()
        assertEquals(6, Scanner.count(src))
    }

    @Test fun englishLiteralDebtOnlyShrinks() {
        val current = Scanner.scan(uiDir)
        if (System.getenv("ENGLISH_BASELINE_REGEN") == "1") {
            baselineFile.parentFile.mkdirs()
            baselineFile.writeText(Scanner.render(current))
            return
        }
        assertTrue("baseline missing: run with ENGLISH_BASELINE_REGEN=1", baselineFile.exists())
        val baseline = Scanner.parse(baselineFile.readText())
        val worse = current.filter { (file, n) -> n > (baseline[file] ?: 0) }
        assertTrue(
            "Hard-coded English literals rose (move them to strings.xml, EN + ES): " +
                worse.map { (f, n) -> "$f ${baseline[f] ?: 0} -> $n" },
            worse.isEmpty()
        )
        val better = baseline.filter { (file, n) -> (current[file] ?: 0) < n }
        assertTrue(
            "Debt shrank; lock it in by regenerating the baseline (ENGLISH_BASELINE_REGEN=1): " +
                better.map { (f, n) -> "$f $n -> ${current[f] ?: 0}" },
            better.isEmpty()
        )
    }
}
