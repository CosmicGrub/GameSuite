package com.gamesuite.games.egggarden

import org.junit.Ignore
import org.junit.Test

/**
 * Test plan stubs (Persistence). Every test is @Ignore'd until the engine exists; the names and reasons are the spec.
 * See docs/design/kids-games/egg-garden.md, section SCALE > Test plan. Nothing here has been run.
 */
class EggGardenPersistenceTest {
    @Ignore("TODO EG-R1: restore then hash equals hash before snapshot")
    @Test fun snapshotRestoreRoundTripsTheStateHash() {}

    @Ignore("TODO EG-R2: returns false and leaves the engine untouched")
    @Test fun restoreRejectsAWrongRosterOrInvalidGarden() {}

    @Ignore("TODO EG-R3: random bytes and truncated JSON return false")
    @Test fun aCorruptSnapshotNeverThrows() {}

    @Ignore("TODO EG-R4: also null before start")
    @Test fun snapshotIsNullOnceTheMatchIsOver() {}
}
