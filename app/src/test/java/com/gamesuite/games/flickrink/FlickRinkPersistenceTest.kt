package com.gamesuite.games.flickrink

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Persistence). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/flick-rink.md, section SCALE > Test plan. Nothing here has been run.
 */
class FlickRinkPersistenceTest {
    @Ignore("TODO FR-S1: restore then hash equals hash before snapshot")
    @Test fun snapshotRestoreRoundTripsTheStateHash() {}

    @Ignore("TODO FR-S2: returns false and leaves the engine untouched")
    @Test fun restoreRejectsAWrongRosterOrPhysicsVersion() {}

    @Ignore("TODO FR-S3: random bytes and truncated JSON return false")
    @Test fun aCorruptSnapshotNeverThrows() {}

    @Ignore("TODO FR-S4: also null before start and in a non-resumable mode")
    @Test fun snapshotIsNullOnceTheMatchIsOver() {}
}
