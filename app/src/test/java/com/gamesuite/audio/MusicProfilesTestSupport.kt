package com.gamesuite.audio

/**
 * Shared test support for the audio-profile tests ([MusicProfilesValidityTest],
 * [PadSynthSignalQualityTest]): reflective discovery of every [MusicProfile] published by
 * [MusicProfiles], so a profile added by a later game is covered automatically with no test edit.
 *
 * It holds no `@Test`, so JUnit never runs it. It deliberately reads the same public getters the
 * production code exposes (every zero-arg `MusicProfiles.getXxx()` returning a [MusicProfile]) and
 * does not depend on any private state.
 */
internal object MusicProfilesTestSupport {

    /** Every public constant of [MusicProfiles], aliases included, as (NAME, profile) sorted by name. */
    fun discoverProfiles(): List<Pair<String, MusicProfile>> {
        val all = MusicProfiles::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == MusicProfile::class.java && it.name.startsWith("get") }
            .map { it.name.removePrefix("get") to (it.invoke(MusicProfiles) as MusicProfile) }
            .sortedBy { it.first }
        check(all.isNotEmpty()) { "reflective MusicProfiles discovery found nothing" }
        return all
    }

    /**
     * One entry per distinct object (identity, not equality), so aliases such as
     * `SOLITAIRE === PUZZLE_FOCUS` are exercised once. The names of all aliases of an object are
     * joined with `=` for failure messages.
     */
    fun distinctProfiles(): List<Pair<String, MusicProfile>> {
        val out = ArrayList<Pair<String, MusicProfile>>()
        for ((n, p) in discoverProfiles()) {
            val i = out.indexOfFirst { it.second === p }
            if (i >= 0) out[i] = "${out[i].first}=$n" to p else out += n to p
        }
        return out
    }
}
