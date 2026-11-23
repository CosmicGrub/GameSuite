package com.gamesuite.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReleaseModelTest {
    private val installed = InstalledVersion(10, "2.0.3")

    @Test fun parserIgnoresUnknownFieldsAndSkipsMalformedEntries() {
        val json = """[${Fixtures.releaseJson(11)}, {"no_tag":true}, 5, {"tag_name":"v1.0.0","assets":"oops"},
            {"tag_name":"x","assets":[{"name":"a.apk"},{"browser_download_url":"https://x"}]}]"""
        val list = ReleaseParser.parseList(json)
        assertEquals(listOf("wave3-11-abc1234", "v1.0.0", "x"), list.map { it.tag })
        assertEquals(2, list[0].assets.size)
        assertTrue(list[0].prerelease)
        assertTrue(list[1].assets.isEmpty())
        assertTrue(list[2].assets.isEmpty())
    }

    @Test fun parserRejectsNonArrayAndGarbage() {
        for (bad in listOf("{}", "not json", "\"x\"")) {
            try { ReleaseParser.parseList(bad); fail("accepted $bad") } catch (e: UpdateException) {
                assertEquals(UpdateFailure.BadResponse, e.failure)
            }
        }
        assertTrue(ReleaseParser.parseList("[]").isEmpty())
    }

    @Test fun versionCodeFromTagAndAssetName() {
        val r = ReleaseParser.parseList(Fixtures.list(Fixtures.releaseJson(42)))[0]
        assertEquals(42L, ReleaseVersions.versionCodeOf(r))
        assertEquals("abc1234", ReleaseVersions.shortShaOf(r))
        // Tag that is not the CI format: falls back to the asset name.
        val odd = r.copy(tag = "nightly")
        assertEquals(42L, ReleaseVersions.versionCodeOf(odd))
        assertNull(ReleaseVersions.versionCodeOf(odd.copy(assets = emptyList())))
    }

    @Test fun semverTagsCompareAgainstInstalledVersionName() {
        fun rel(tag: String) = ReleaseInfo(tag, tag, "", false, false, null, null, emptyList())
        assertEquals(Relation.NEWER, ReleaseVersions.relation(rel("v2.0.4"), installed))
        assertEquals(Relation.SAME, ReleaseVersions.relation(rel("2.0.3"), installed))
        assertEquals(Relation.OLDER, ReleaseVersions.relation(rel("v1.9.9"), installed))
        assertEquals(Relation.OLDER, ReleaseVersions.relation(rel("v2.0.3-rc1"), installed))
        assertEquals(Relation.UNKNOWN, ReleaseVersions.relation(rel("banana"), installed))
        assertTrue(SemVer.parse("1.2.3")!! < SemVer.parse("1.10.0")!!)
    }

    @Test fun catalogListsEveryReleaseNewestFirstAndBadgesOnlyNewer() {
        val releases = ReleaseParser.parseList(
            Fixtures.list(
                Fixtures.releaseJson(9), Fixtures.releaseJson(12), Fixtures.releaseJson(10),
                Fixtures.releaseJson(11, prerelease = false), Fixtures.releaseJson(13)
            )
        )
        val c = ReleaseCatalog.build(releases, installed, UpdateChannel.PREVIEW)
        assertEquals(listOf(13L, 12L, 11L, 10L, 9L), c.entries.map { it.versionCode })
        assertEquals(3, c.badgeCount)
        assertEquals(13L, c.newest?.versionCode)
        assertEquals(listOf(true, true, true, false, false), c.entries.map { it.installable })
        assertEquals(Relation.SAME, c.entries[3].relation)
        assertEquals(Relation.OLDER, c.entries[4].relation)
    }

    @Test fun stableChannelBadgeIgnoresPrereleasesButStillListsThem() {
        val releases = ReleaseParser.parseList(Fixtures.list(Fixtures.releaseJson(12), Fixtures.releaseJson(11, prerelease = false)))
        val c = ReleaseCatalog.build(releases, installed, UpdateChannel.STABLE)
        assertEquals(2, c.entries.size)
        assertEquals(1, c.badgeCount)
        assertEquals(11L, c.newest?.versionCode)
        assertFalse(c.entries.first { it.versionCode == 12L }.inChannel)
        assertTrue(c.entries.first { it.versionCode == 12L }.installable) // owner may still pick it explicitly
    }

    @Test fun olderBuildsOnlyOfferedWithExplicitReinstallOptIn() {
        val releases = ReleaseParser.parseList(Fixtures.list(Fixtures.releaseJson(9), Fixtures.releaseJson(10)))
        assertTrue(ReleaseCatalog.build(releases, installed, UpdateChannel.PREVIEW).entries.none { it.installable })
        assertTrue(ReleaseCatalog.build(releases, installed, UpdateChannel.PREVIEW, allowReinstall = true).entries.all { it.installable })
    }

    @Test fun draftsAndApklessReleasesAreNotOffered() {
        val a = ReleaseParser.parseList(Fixtures.list(Fixtures.releaseJson(12)))[0]
        val draft = a.copy(draft = true)
        val noApk = a.copy(tag = "wave3-13-abc1234", assets = emptyList())
        val c = ReleaseCatalog.build(listOf(draft, noApk), installed, UpdateChannel.PREVIEW)
        assertEquals(1, c.entries.size)
        assertEquals(0, c.badgeCount)
        assertFalse(c.entries[0].installable)
    }

    @Test fun assetWithUntrustedUrlIsNotAnApk() {
        val r = ReleaseParser.parseList(Fixtures.list(Fixtures.releaseJson(12)))[0]
        val evil = r.copy(assets = r.assets.map { it.copy(downloadUrl = "https://evil.example/x/GameSuite-sideload-12-abc1234.apk") })
        assertNull(evil.apk)
        val http = r.copy(assets = r.assets.map { it.copy(downloadUrl = it.downloadUrl.replace("https", "http")) })
        assertNull(http.apk)
        val otherRepo = r.copy(assets = r.assets.map { it.copy(downloadUrl = it.downloadUrl.replace("CosmicGrub/GameSuite", "someone/else")) })
        assertNull(otherRepo.apk)
    }
}
