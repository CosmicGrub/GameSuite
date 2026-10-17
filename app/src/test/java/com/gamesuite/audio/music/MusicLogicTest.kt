package com.gamesuite.audio.music

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicLogicTest {

    // ---- route -> context ----

    @Test fun menuRoutes() {
        listOf("menu", "games", "stats", "settings", "music", "hub/chess", "hub/anything", "party-toolkit")
            .forEach { assertEquals(it, MusicContext.MENU, contextForRoute(it)) }
    }

    @Test fun boardRoutes() {
        listOf(
            "chess", "checkers", "reversi", "mancala", "connect-four", "dots-and-boxes", "dominoes",
            "word-tiles", "tic-tac-toe", "tic-tac-toe-misere", "tic-tac-toe-wild"
        ).forEach { assertEquals(it, MusicContext.BOARD, contextForRoute(it)) }
    }

    @Test fun cardRoutes() {
        listOf("uno", "uno-custom", "uno-house-rules", "uno-teams", "solitaire")
            .forEach { assertEquals(it, MusicContext.CARD, contextForRoute(it)) }
    }

    @Test fun puzzleAndWordRoutes() {
        listOf(
            "hangman", "word-guess", "word-guess-daily", "word-search", "crossword", "sudoku", "sudoku-daily",
            "kenken", "kakuro", "nonogram", "minesweeper", "lights-out", "sliding-puzzle", "color-flood",
            "edge-match", "mastermind", "mastermind-daily"
        ).forEach { assertEquals(it, MusicContext.PUZZLE_WORD, contextForRoute(it)) }
    }

    @Test fun arcadeRoutes() {
        listOf("breakout", "tower-defence", "air-hockey")
            .forEach { assertEquals(it, MusicContext.ARCADE, contextForRoute(it)) }
    }

    @Test fun wordTilesIsBoardNotPuzzle() {
        assertEquals(MusicContext.BOARD, contextForRoute("word-tiles"))
    }

    @Test fun unknownAndMessyRoutesFallBackToMenu() {
        assertEquals(MusicContext.MENU, contextForRoute(null))
        assertEquals(MusicContext.MENU, contextForRoute(""))
        assertEquals(MusicContext.MENU, contextForRoute("   "))
        assertEquals(MusicContext.MENU, contextForRoute("totally-unknown"))
        assertEquals(MusicContext.BOARD, contextForRoute("/Chess/"))
        assertEquals(MusicContext.CARD, contextForRoute("uno?mode=x"))
        assertEquals(MusicContext.ARCADE, contextForRoute("breakout/level/3"))
    }

    // ---- playlists ----

    private fun ids(vararg s: String) = s.toList()

    @Test fun reconcileDefaultsToEveryTrackEnabled() {
        val out = Playlists.reconcile(emptyList(), ids("a", "b", "c"))
        assertEquals(ids("a", "b", "c"), out.map { it.trackId })
        assertTrue(out.all { it.enabled })
    }

    @Test fun reconcileKeepsOrderAndFlagsDropsMissingAppendsNew() {
        val existing = listOf(PlaylistEntry("c", false), PlaylistEntry("gone", true), PlaylistEntry("a", true))
        val out = Playlists.reconcile(existing, ids("a", "b", "c"))
        assertEquals(ids("c", "a", "b"), out.map { it.trackId })
        assertFalse(out[0].enabled)
        assertTrue(out[2].enabled)
    }

    @Test fun reconcileDeduplicates() {
        val out = Playlists.reconcile(listOf(PlaylistEntry("a"), PlaylistEntry("a")), ids("a", "a", "b"))
        assertEquals(ids("a", "b"), out.map { it.trackId })
    }

    @Test fun toggleAndEnableAll() {
        val base = Playlists.reconcile(emptyList(), ids("a", "b"))
        val off = Playlists.toggle(base, "b", false)
        assertEquals(ids("a"), Playlists.enabledIds(off))
        assertEquals(ids("a", "b"), Playlists.enabledIds(Playlists.enableAll(off)))
        assertEquals(base, Playlists.toggle(base, "missing", false))
    }

    @Test fun moveReordersAndIgnoresOutOfRange() {
        val base = Playlists.reconcile(emptyList(), ids("a", "b", "c"))
        assertEquals(ids("b", "a", "c"), Playlists.move(base, 1, -1).map { it.trackId })
        assertEquals(ids("a", "c", "b"), Playlists.move(base, 1, +1).map { it.trackId })
        assertEquals(base, Playlists.move(base, 0, -1))
        assertEquals(base, Playlists.move(base, 2, +1))
        assertEquals(base, Playlists.move(base, 9, -1))
        assertEquals(base, Playlists.move(base, 1, 0))
    }

    // ---- playback queue ----

    @Test fun sequentialWrapsAround() {
        val q = PlaybackQueue(Random(1))
        q.setList(ids("a", "b", "c"))
        assertEquals("a", q.next(false))
        assertEquals("b", q.next(false))
        assertEquals("c", q.next(false))
        assertEquals("a", q.next(false))
    }

    @Test fun shuffleNeverRepeatsUntilAllPlayed() {
        repeat(50) { seed ->
            val q = PlaybackQueue(Random(seed))
            val list = ids("a", "b", "c", "d", "e")
            q.setList(list)
            val round1 = List(5) { q.next(true)!! }
            assertEquals(list.toSet(), round1.toSet())
            // The next round must not start with the track that just finished.
            val first = q.next(true)
            assertTrue(first != round1.last())
        }
    }

    @Test fun shuffleLongRunNeverImmediateRepeat() {
        val q = PlaybackQueue(Random(7))
        q.setList(ids("a", "b", "c"))
        var prev: String? = null
        repeat(200) {
            val n = q.next(true)
            assertTrue(n != prev)
            prev = n
        }
    }

    @Test fun singleTrackRepeatsAndEmptyIsNull() {
        val q = PlaybackQueue(Random(2))
        q.setList(ids("only"))
        assertEquals("only", q.next(true))
        assertEquals("only", q.next(true))
        assertEquals("only", q.next(false))
        q.setList(emptyList())
        assertNull(q.next(true))
        assertNull(q.next(false))
    }

    @Test fun previousWalksHistory() {
        val q = PlaybackQueue(Random(3))
        q.setList(ids("a", "b", "c"))
        q.next(false); q.next(false); q.next(false) // a b c
        assertEquals("b", q.previous())
        assertEquals("a", q.previous())
        assertNull(q.previous())
    }

    @Test fun contextSwitchKeepsCurrentWhenStillEnabled() {
        val q = PlaybackQueue(Random(4))
        q.setList(ids("a", "b", "c"))
        q.next(false) // a
        q.next(false) // b
        q.setList(ids("b", "c"))
        assertTrue(q.contains(q.current))
        q.setList(ids("c"))
        assertFalse(q.contains(q.current))
        assertEquals("c", q.next(false))
    }

    // ---- serialization ----

    @Test fun codecRoundTrip() {
        val data = MusicLibraryData(
            folderUri = "content://tree/music",
            tracks = listOf(
                MusicTrack("u1", "content://u1", "Song \"One\"", "Artist", 1234L),
                MusicTrack("u2", "content://u2", "Two")
            ),
            playlists = mapOf(
                MusicContext.BOARD to listOf(PlaylistEntry("u2", false), PlaylistEntry("u1", true))
            )
        )
        val back = MusicLibraryCodec.decode(MusicLibraryCodec.encode(data))
        assertEquals(data, back)
    }

    @Test fun codecToleratesGarbage() {
        listOf(null, "", "   ", "not json", "[]", "42", "{", "{\"tracks\": 5}", "{\"tracks\":[1,null,\"x\",{}]}")
            .forEach { assertEquals(it, MusicLibraryData(), MusicLibraryCodec.decode(it)) }
    }

    @Test fun codecSkipsBadEntriesAndUnknownContexts() {
        val text = """
            {"folderUri":"f","tracks":[{"id":"a","uri":"ua","title":"A","durationMs":"oops"},
              {"id":"","uri":"x"},{"uri":"nid"},{"id":"a","uri":"dup"}],
             "playlists":{"BOARD":[{"id":"a","on":false},{"on":true},7],"NOPE":[{"id":"a"}],"CARD":"bad"},
             "extra":1}
        """.trimIndent()
        val d = MusicLibraryCodec.decode(text)
        assertEquals("f", d.folderUri)
        assertEquals(1, d.tracks.size)
        assertEquals(0L, d.tracks[0].durationMs)
        assertEquals(listOf(PlaylistEntry("a", false)), d.playlists[MusicContext.BOARD])
        assertEquals(emptyList<PlaylistEntry>(), d.playlists[MusicContext.CARD])
        assertEquals(setOf(MusicContext.BOARD, MusicContext.CARD), d.playlists.keys)
    }

    @Test fun playlistForReconcilesAgainstTracks() {
        val d = MusicLibraryData(
            tracks = listOf(MusicTrack("a", "a", "A"), MusicTrack("b", "b", "B")),
            playlists = mapOf(MusicContext.ARCADE to listOf(PlaylistEntry("zzz"), PlaylistEntry("b", false)))
        )
        assertEquals(listOf(PlaylistEntry("b", false), PlaylistEntry("a", true)), d.playlistFor(MusicContext.ARCADE))
        assertEquals(listOf(PlaylistEntry("a"), PlaylistEntry("b")), d.playlistFor(MusicContext.MENU))
    }

    // ---- crossfade math ----

    @Test fun equalPowerKeepsConstantPower() {
        for (i in 0..20) {
            val (inG, outG) = Crossfade.equalPower(i / 20f)
            assertEquals(1.0, (inG * inG + outG * outG).toDouble(), 1e-5)
        }
        val (i0, o0) = Crossfade.equalPower(0f)
        assertEquals(0f, i0, 1e-6f); assertEquals(1f, o0, 1e-6f)
        val (i1, o1) = Crossfade.equalPower(1f)
        assertEquals(1f, i1, 1e-6f); assertEquals(0f, o1, 1e-6f)
    }

    @Test fun progressClampsAndHandlesZeroDuration() {
        assertEquals(0.5f, Crossfade.progress(500, 1000), 1e-6f)
        assertEquals(1f, Crossfade.progress(5000, 1000), 1e-6f)
        assertEquals(0f, Crossfade.progress(-5, 1000), 1e-6f)
        assertEquals(1f, Crossfade.progress(10, 0), 1e-6f)
    }

    @Test fun effectiveCrossfadeNeverExceedsHalfTheTrack() {
        assertEquals(3000L, Crossfade.effectiveMs(3, 180_000))
        assertEquals(2500L, Crossfade.effectiveMs(8, 5_000))
        assertEquals(0L, Crossfade.effectiveMs(0, 180_000))
        assertEquals(8000L, Crossfade.effectiveMs(99, 0)) // unknown duration: clamp to 8 s
        assertEquals(0L, Crossfade.effectiveMs(-4, 100_000))
    }

    @Test fun nearEndWindow() {
        assertFalse(Crossfade.shouldStartNext(10_000, 60_000, 3_000))
        assertTrue(Crossfade.shouldStartNext(57_000, 60_000, 3_000))
        assertFalse(Crossfade.shouldStartNext(59_000, 60_000, 0))
        assertFalse(Crossfade.shouldStartNext(1_000, 0, 3_000))
    }

    // ---- file detection ----

    @Test fun audioDetection() {
        assertTrue(AudioFiles.isAudio("x.bin", "audio/mpeg"))
        assertTrue(AudioFiles.isAudio("song.FLAC", "application/octet-stream"))
        assertTrue(AudioFiles.isAudio("song.mp3", null))
        assertFalse(AudioFiles.isAudio("photo.jpg", "image/jpeg"))
        assertFalse(AudioFiles.isAudio("notes.txt", null))
        assertFalse(AudioFiles.isAudio(null, null))
    }

    @Test fun titleFromFileName() {
        assertEquals("01 - My Song", AudioFiles.titleFromFileName("01 - My Song.mp3"))
        assertEquals("noext", AudioFiles.titleFromFileName("noext"))
        assertEquals("", AudioFiles.titleFromFileName(null))
        assertEquals(".hidden", AudioFiles.titleFromFileName(".hidden"))
    }

    // ---- review fixes ----

    @get:Rule val tmpDir = TemporaryFolder()

    @Test fun nullListingIsAScanFailureNotAnEmptyFolder() {
        try {
            ScanGuard.requireListing<Any>(null, "doc1")
            fail("expected ScanFailedException")
        } catch (e: ScanFailedException) {
            assertTrue(e.message!!.contains("doc1"))
        }
        val listing = Any()
        assertSame(listing, ScanGuard.requireListing(listing, "doc1"))
    }

    @Test fun crossfadeOverlapCappedByBothTracks() {
        // short outgoing (2 s -> 1 s cap), next track would allow 3 s: overlap is 1 s
        assertEquals(1_000L, Crossfade.overlapMs(3, 2_000, 180_000))
        // normal outgoing, short incoming
        assertEquals(1_000L, Crossfade.overlapMs(3, 180_000, 2_000))
        // both long
        assertEquals(3_000L, Crossfade.overlapMs(3, 180_000, 200_000))
        // unknown durations fall back to the setting; disabled stays 0
        assertEquals(3_000L, Crossfade.overlapMs(3, 0, 0))
        assertEquals(0L, Crossfade.overlapMs(0, 180_000, 180_000))
    }

    @Test fun cancelledAdvanceDoesNotMoveQueuePosition() {
        val q = PlaybackQueue(Random(5))
        q.setList(ids("a", "b", "c"))
        q.next(false) // a is audible
        val committed = q.snapshot()
        assertEquals("b", q.next(false)) // b queued but never becomes active
        q.restore(committed)
        assertEquals("a", q.current)
        assertNull(q.previous()) // b never entered history
        assertEquals("b", q.next(false)) // the cancelled track is not skipped
    }

    @Test fun restoreAfterListChangeDropsRemovedIds() {
        val q = PlaybackQueue(Random(6))
        q.setList(ids("a", "b", "c"))
        q.next(false); q.next(false) // a, b
        val snap = q.snapshot()
        q.next(false) // c
        q.setList(ids("b", "c"))
        q.restore(snap)
        assertEquals("b", q.current)
        assertEquals("c", q.next(false))
    }

    @Test fun restoreDoesNotBringBackACurrentTrackThatLeftTheList() {
        val q = PlaybackQueue(Random(8))
        q.setList(ids("a", "b", "c"))
        q.next(false); q.next(false) // a, b
        val snap = q.snapshot()      // current = b
        q.setList(ids("a", "c"))     // b was disabled or removed
        q.restore(snap)
        assertNull("a disabled track must not be resumable through the queue", q.current)
    }

    @Test fun handoffWaitsUntilTheFadeWindowRemains() {
        // 3 s left when the next track is ready but its fade is only 1 s: wait 2 s so the tail is not cut.
        assertEquals(2000L, Crossfade.handoffDelayMs(remainingMs = 3000L, fadeMs = 1000L))
        // the fade is as long as what remains (or longer): start at once
        assertEquals(0L, Crossfade.handoffDelayMs(3000L, 3000L))
        assertEquals(0L, Crossfade.handoffDelayMs(2000L, 3000L))
        // a difference within the slack is not worth waiting for
        assertEquals(0L, Crossfade.handoffDelayMs(3100L, 3000L))
        // no fade or nothing left: never wait
        assertEquals(0L, Crossfade.handoffDelayMs(3000L, 0L))
        assertEquals(0L, Crossfade.handoffDelayMs(0L, 1000L))
    }

    // ---- regression guard: explicit track changes must never wait for a crossfade window ----------

    @Test fun anExplicitTrackChangeNeverWaitsHoweverMuchOfTheCurrentTrackRemains() {
        // Next/Previous with two minutes left and a 3 s fade: start at once.
        assertEquals(0L, Crossfade.handoffDelayMs(nearEnd = false, remainingMs = 120_000L, fadeMs = 3_000L))
        assertEquals(0L, Crossfade.handoffDelayMs(nearEnd = false, remainingMs = 3_500L, fadeMs = 1_000L))
        // only the natural near-end hand-over waits (3 s left, 1 s fade -> wait 2 s)
        assertEquals(2_000L, Crossfade.handoffDelayMs(nearEnd = true, remainingMs = 3_000L, fadeMs = 1_000L))
        assertEquals(0L, Crossfade.handoffDelayMs(nearEnd = true, remainingMs = 1_000L, fadeMs = 1_000L))
    }

    private fun musicManagerSource(): String {
        val rel = "src/main/java/com/gamesuite/audio/music/MusicManager.kt"
        val f = listOf(java.io.File(rel), java.io.File("app/$rel")).first { it.exists() }
        return f.readText().replace("\r\n", "\n")
    }

    /** The text from [signature] up to the next member declaration, so a test inspects one function body. */
    private fun bodyOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        val next = Regex("\n    (private |override |internal |fun |/\\*\\*)").find(src, start + signature.length)
        return src.substring(start, next?.range?.first ?: src.length)
    }

    @Test fun onlyTheNaturalAdvanceEverRequestsAHeldHandoff() {
        val src = musicManagerSource()
        // The explicit paths must not mention nearEnd at all: they use playById's default (false).
        for (sig in listOf("fun skipNext()", "fun skipPrevious()")) {
            assertFalse("$sig must not request a held hand-over", bodyOf(src, sig).contains("nearEnd"))
        }
        // Exactly one call site passes a non-false nearEnd, and it is the natural advance, derived from the near-end flag.
        val passes = Regex("nearEnd\\s*=\\s*[^\\n,)]+").findAll(src).map { it.value.replace(" ", "") }.toList()
        assertEquals("only one call may pass nearEnd, got $passes", 1, passes.count { it != "nearEnd=false" })
        assertTrue(bodyOf(src, "private fun advanceAfterEnd").contains("nearEnd = fadeMs < 0L"))
    }

    @Test fun atomicWriteReplacesAndKeepsOldFileOnFailure() {
        val target = java.io.File(tmpDir.root, "lib.json")
        val f = AtomicTextFile(target)
        assertTrue(f.write("one"))
        assertTrue(f.write("two"))
        assertEquals("two", f.read())
        assertFalse(java.io.File(tmpDir.root, "lib.json.tmp").exists())

        // Make the temp path unwritable (a directory): the write fails and the old file survives.
        assertTrue(java.io.File(tmpDir.root, "lib.json.tmp").mkdir())
        assertFalse(f.write("three"))
        assertEquals("two", f.read())
    }
}
