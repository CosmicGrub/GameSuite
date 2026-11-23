package com.gamesuite.update

data class InstalledVersion(val versionCode: Long, val versionName: String)

data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: String?) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        // A version with a pre-release part sorts BELOW the same version without one.
        return when {
            pre == null && other.pre == null -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> pre.compareTo(other.pre)
        }
    }

    companion object {
        private val re = Regex("""^[vV]?(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-([0-9A-Za-z.\-]+))?(?:\+[0-9A-Za-z.\-]+)?$""")
        fun parse(text: String?): SemVer? {
            val m = re.matchEntire(text?.trim() ?: return null) ?: return null
            return SemVer(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].ifEmpty { null })
        }
    }
}

enum class Relation { NEWER, SAME, OLDER, UNKNOWN }

/** Extracts and compares versions. CI tag format: `wave3-<versionCode>-<shortsha>`; semver tags are also accepted. */
object ReleaseVersions {
    private val ciTag = Regex("""^wave3-(\d{1,10})-([0-9a-fA-F]{4,40})$""")
    private val apkName = Regex("""-(\d{1,10})-[0-9a-fA-F]{4,40}\.apk$""", RegexOption.IGNORE_CASE)

    fun versionCodeOf(release: ReleaseInfo): Long? =
        ciTag.matchEntire(release.tag)?.groupValues?.get(1)?.toLongOrNull()
            ?: release.apk?.let { apkName.find(it.name)?.groupValues?.get(1)?.toLongOrNull() }

    fun semverOf(release: ReleaseInfo): SemVer? = SemVer.parse(release.tag)

    fun shortShaOf(release: ReleaseInfo): String? = ciTag.matchEntire(release.tag)?.groupValues?.get(2)

    fun relation(release: ReleaseInfo, installed: InstalledVersion): Relation {
        versionCodeOf(release)?.let { c ->
            return when {
                c > installed.versionCode -> Relation.NEWER
                c == installed.versionCode -> Relation.SAME
                else -> Relation.OLDER
            }
        }
        val rel = semverOf(release)
        val inst = SemVer.parse(installed.versionName)
        if (rel != null && inst != null) {
            val c = rel.compareTo(inst)
            return if (c > 0) Relation.NEWER else if (c == 0) Relation.SAME else Relation.OLDER
        }
        return Relation.UNKNOWN
    }
}

data class ReleaseEntry(
    val release: ReleaseInfo,
    val apk: ReleaseAsset?,
    val versionCode: Long?,
    val relation: Relation,
    val inChannel: Boolean,
    /** Offered for install: has an APK and is newer than installed (or reinstall guidance is on). */
    val installable: Boolean
)

/** The full, newest-first release list as the UI shows it, with the badge count and the "install newest" pick. */
data class ReleaseCatalog(val entries: List<ReleaseEntry>) {
    /** Releases newer than installed, with an APK, in the followed channel. */
    val newerEntries: List<ReleaseEntry> get() = entries.filter { it.relation == Relation.NEWER && it.apk != null && it.inChannel }
    val badgeCount: Int get() = newerEntries.size
    val newest: ReleaseEntry? get() = newerEntries.firstOrNull()

    companion object {
        val EMPTY = ReleaseCatalog(emptyList())

        fun build(
            releases: List<ReleaseInfo>,
            installed: InstalledVersion,
            channel: UpdateChannel,
            allowReinstall: Boolean = false
        ): ReleaseCatalog {
            val entries = releases.filter { !it.draft }.map { r ->
                val rel = ReleaseVersions.relation(r, installed)
                val apk = r.apk
                val offerable = apk != null && (rel == Relation.NEWER ||
                    (allowReinstall && (rel == Relation.OLDER || rel == Relation.SAME)))
                ReleaseEntry(r, apk, ReleaseVersions.versionCodeOf(r), rel, channel == UpdateChannel.PREVIEW || !r.prerelease, offerable)
            }
            val sorted = entries.sortedWith(
                compareByDescending<ReleaseEntry> { it.versionCode ?: Long.MIN_VALUE }
                    .thenByDescending { ReleaseVersions.semverOf(it.release) }
                    .thenByDescending { it.release.publishedAt.orEmpty() }
            )
            return ReleaseCatalog(sorted)
        }
    }
}
