package com.gamesuite.update

import com.gamesuite.BuildConfig
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Play policy forbids self-updating outside Play: the updater, the REQUEST_INSTALL_PACKAGES permission and the update
 * FileProvider must exist only in debug + sideload builds. These checks run in the debug unit-test JVM (working dir app/).
 */
class SideloadGateTest {
    private fun read(path: String) = File(path).readText()

    @Test fun debugBuildHasTheFeatureOn() {
        assertTrue(BuildConfig.SIDELOAD_UPDATES)
    }

    @Test fun releaseBuildTypeSetsTheFlagFalseAndSideloadTrue() {
        val gradle = read("build.gradle.kts").replace("\r\n", "\n")
        fun block(header: String): String {
            val start = gradle.indexOf(header)
            assertTrue("missing $header", start >= 0)
            var depth = 0; var i = gradle.indexOf('{', start)
            val begin = i
            while (i < gradle.length) {
                if (gradle[i] == '{') depth++
                if (gradle[i] == '}') { depth--; if (depth == 0) break }
                i++
            }
            return gradle.substring(begin, i)
        }
        val release = block("        release {")
        assertTrue(release.contains("""buildConfigField("boolean", "SIDELOAD_UPDATES", "false")"""))
        assertFalse(release.contains("\"true\""))
        assertTrue(block("        debug {").contains("""buildConfigField("boolean", "SIDELOAD_UPDATES", "true")"""))
        assertTrue(block("        create(\"sideload\") {").contains("""buildConfigField("boolean", "SIDELOAD_UPDATES", "true")"""))
    }

    @Test fun installPermissionAndProviderAreAbsentFromTheMainManifest() {
        val main = read("src/main/AndroidManifest.xml")
        assertFalse(main.contains("REQUEST_INSTALL_PACKAGES"))
        assertFalse(main.contains("FileProvider"))
        assertFalse(main.contains(".updates"))
        assertFalse(File("src/release").exists() && File("src/release/AndroidManifest.xml").readText().contains("REQUEST_INSTALL_PACKAGES"))
    }

    @Test fun installPermissionLivesOnlyInDebugAndSideloadManifests() {
        for (v in listOf("debug", "sideload")) {
            val m = read("src/$v/AndroidManifest.xml")
            assertTrue(m.contains("REQUEST_INSTALL_PACKAGES"))
            assertTrue(m.contains("androidx.core.content.FileProvider"))
            assertTrue(m.contains("\${applicationId}.updates"))
        }
    }

    @Test fun networkConfigPinsTheFeatureHostsToHttps() {
        for (f in listOf("src/main/res/xml/network_security_config.xml", "src/debug/res/xml/network_security_config.xml")) {
            val x = read(f)
            for (h in listOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")) {
                assertTrue("$f $h", x.contains(">$h<"))
            }
            assertTrue(x.contains("<domain-config cleartextTrafficPermitted=\"false\">"))
        }
    }

    @Test fun noTokensOrSecretsInUpdaterSources() {
        val dir = File("src/main/java/com/gamesuite/update")
        val text = dir.listFiles()!!.joinToString("\n") { it.readText() }
        assertFalse(Regex("ghp_|github_pat_|Bearer ", RegexOption.IGNORE_CASE).containsMatchIn(text))
        // "Authorization" may appear only in the comment that says it is never sent.
        val authLines = text.lines().filter { it.contains("Authorization") && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") }
        assertTrue(authLines.toString(), authLines.isEmpty())
    }

    @Test fun workflowIsTagTriggeredAndOnlyItHasContentsWrite() {
        val wf = File("../.github/workflows/sideload.yml")
        if (!wf.exists()) return
        val t = wf.readText()
        assertTrue(t.contains("wave3-*"))
        assertTrue(t.contains("workflow_dispatch"))
        assertTrue(t.contains("contents: write"))
        assertFalse(File("../.github/workflows/android.yml").readText().contains("contents: write"))
    }
}
