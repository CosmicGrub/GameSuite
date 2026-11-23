package com.gamesuite.style

/** One sprite's rectangle inside an atlas bitmap. */
data class SpriteRect(val name: String, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * The line manifest written by scripts/gen_gem_cascade_atlas.py:
 * `# comment`, `size <px>`, then `<name> <x> <y> <w> <h>` per sprite. Pure and JVM-tested.
 */
class AtlasManifest(val size: Int, val sprites: Map<String, SpriteRect>) {
    val atlasWidth: Int get() = sprites.values.maxOfOrNull { it.x + it.w } ?: 0
    val atlasHeight: Int get() = sprites.values.maxOfOrNull { it.y + it.h } ?: 0
    /** Decoded ARGB_8888 size of the atlas bitmap. */
    val decodedBytes: Long get() = atlasWidth.toLong() * atlasHeight * 4L
    operator fun get(name: String): SpriteRect? = sprites[name]

    companion object {
        fun parse(text: String): AtlasManifest? {
            var size = 0
            val map = LinkedHashMap<String, SpriteRect>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val p = line.split(' ').filter { it.isNotEmpty() }
                if (p[0] == "size" && p.size == 2) { size = p[1].toIntOrNull() ?: return null; continue }
                if (p.size != 5) return null
                val n = p.drop(1).map { it.toIntOrNull() ?: return null }
                if (n.any { it < 0 } || n[2] <= 0 || n[3] <= 0) return null
                map[p[0]] = SpriteRect(p[0], n[0], n[1], n[2], n[3])
            }
            if (size <= 0 || map.isEmpty()) return null
            return AtlasManifest(size, map)
        }
    }
}

/** Memory budgets for decoded sprite atlases (RECOMMENDATION.md section 6): <= 28 MB high/tablet, <= 18 MB mid, none on low. */
object AtlasBudget {
    const val MB = 1024L * 1024L
    fun maxDecodedBytes(ram: RamClass): Long = when (ram) { RamClass.HIGH -> 28 * MB; RamClass.MID -> 18 * MB; RamClass.LOW -> 0L }
    /** The atlas size to load: 160 px sprites on high-RAM devices, 96 px on mid-range. Null = no 3D (low RAM). */
    fun atlasSizeFor(ram: RamClass): Int? = when (ram) { RamClass.HIGH -> 160; RamClass.MID -> 96; RamClass.LOW -> null }
    /** Compressed pack budget for the pilot (the PNGs on disk). */
    const val PILOT_PACK_MAX_BYTES = 2L * MB
}

/** A least-recently-used cache bounded by total [sizeOf] bytes. An entry bigger than the budget is refused. */
class BudgetLru<K, V>(val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    var usedBytes: Long = 0L
        private set
    val size: Int get() = map.size

    operator fun get(k: K): V? = map[k]

    /** True when stored; false when [v] alone exceeds the budget. Evicts least-recently-used entries to make room. */
    fun put(k: K, v: V): Boolean {
        val sz = sizeOf(v)
        if (sz > maxBytes) return false
        map.remove(k)?.let { usedBytes -= sizeOf(it) }
        val it = map.entries.iterator()
        while (usedBytes + sz > maxBytes && it.hasNext()) {
            val e = it.next(); usedBytes -= sizeOf(e.value); it.remove()
        }
        map[k] = v
        usedBytes += sz
        return true
    }

    fun clear() { map.clear(); usedBytes = 0L }
    fun keys(): List<K> = map.keys.toList()
}

/**
 * Per-frame draw-command budget guard. A renderer calls [tryUse] before an optional embellishment (shadow, highlight);
 * when the budget is spent it skips it and draws the plain version, so a busy board can never blow the frame.
 */
class DrawCommandBudget(var limit: Int) {
    var used: Int = 0
        private set
    var skipped: Int = 0
        private set
    fun reset() { used = 0; skipped = 0 }
    fun tryUse(n: Int = 1): Boolean {
        if (used + n > limit) { skipped += n; return false }
        used += n
        return true
    }
    /** Essential commands always draw, but still count (so embellishments stop first). */
    fun force(n: Int = 1) { used += n }
    val exceeded: Boolean get() = used > limit

    companion object {
        fun limitFor(q: QualityLevel): Int = when (q) { QualityLevel.BATTERY_SAVER -> 800; QualityLevel.BALANCED -> 1500; else -> 2400 }
    }
}
