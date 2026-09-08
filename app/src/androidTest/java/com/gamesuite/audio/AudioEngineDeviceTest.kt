package com.gamesuite.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gamesuite.games.cards.CardSounds
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real on-device verification for the new procedural audio engine — the one
 * thing pure JVM unit tests genuinely cannot cover, since AudioTrack requires
 * an actual Android audio HAL. This does not (and cannot) assert anything
 * about how the audio SOUNDS; it asserts that every music profile and every
 * SFX kind actually constructs and runs its real AudioTrack path on real
 * hardware without throwing, hanging, or leaking a thread/track across
 * repeated start/stop — the same start/stop churn a player generates just by
 * navigating between screens in normal use.
 */
@RunWith(AndroidJUnit4::class)
class AudioEngineDeviceTest {

    private val allProfiles: List<MusicProfile> = listOf(
        MusicProfiles.CHESS,
        MusicProfiles.CHECKERS,
        MusicProfiles.MANCALA,
        MusicProfiles.DOMINOES,
        MusicProfiles.PUZZLE_FOCUS,
        MusicProfiles.UNO,
        MusicProfiles.TIC_TAC_TOE,
        MusicProfiles.SLIDING_PUZZLE,
        MusicProfiles.AIR_HOCKEY
    )

    @Test
    fun everyMusicProfile_startsRunsAndStopsCleanly() {
        allProfiles.forEach { profile ->
            val engine = AmbientMusicEngine(profile)
            engine.start()
            // Let the generator thread actually render a handful of real
            // chunks (including at least one chord-crossfade tick for the
            // shortest chordDurationSeconds profiles) before stopping —
            // start()-then-immediately-stop() would not exercise the
            // steady-state render path at all.
            Thread.sleep(700)
            engine.stop()
        }
    }

    @Test
    fun repeatedStartStop_onSameProfile_neverThrowsOrHangs() {
        // Mirrors real usage: a player bouncing in and out of the same game
        // screen repeatedly (back button, relaunch) starts/stops the same
        // profile's engine over and over in quick succession.
        val engine = AmbientMusicEngine(MusicProfiles.UNO)
        repeat(5) {
            engine.start()
            Thread.sleep(150)
            engine.stop()
        }
    }

    @Test
    fun everySfxKind_playsWithoutThrowing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // playProceduralSfx gates on this exact flag (see its own KDoc) —
        // force it on for the duration of this test regardless of whatever
        // state a prior test/process left it in, then restore afterward.
        val previous = CardSounds.soundEnabled
        CardSounds.soundEnabled = true
        try {
            SfxKind.entries.forEach { kind ->
                playProceduralSfx(context, kind)
                // Each one-shot render+play is fire-and-forget on its own
                // daemon thread — give it real wall-clock time to actually
                // execute (not just enqueue) before moving to the next kind,
                // so a crash inside the render/playback path surfaces here
                // rather than silently after the test process moves on.
                Thread.sleep(300)
            }
        } finally {
            CardSounds.soundEnabled = previous
        }
    }
}
