package com.gamesuite.games.findit

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The guaranteed-one-match deck: a finite projective plane of prime order n. n*n+n+1 cards, n*n+n+1 pictures,
 * n+1 pictures per card, any two cards share exactly one picture. Orders 3, 5 and 7 give 13, 31 and 57.
 *
 * Construction (picture indexes): affine point (x, y) -> x*n + y; slope point m -> n*n + m; vertical point -> n*n + n.
 *  - for m in 0 until n, b in 0 until n: card = { (x, (m*x + b) mod n) for x in 0 until n } + { slope m }
 *  - for c in 0 until n: card = { (c, y) for y in 0 until n } + { vertical point }
 *  - last card = all slope points + the vertical point
 * Proof sketch and edge cases: docs/design/kids-games/find-it.md (SCRIPT, "Deck generation").
 */
object ProjectiveDeck {
    private const val TAG_LAYOUT = 0x4C41L
    private const val TAG_SCENE = 0x5343L

    /** Only prime orders 3, 5 and 7 are supported (4 and 8 would need finite-field arithmetic). */
    fun isSupportedOrder(order: Int): Boolean = order == 3 || order == 5 || order == 7

    fun cardCount(order: Int): Int = order * order + order + 1

    /** The deck of [order]; an unsupported order returns an empty list, never throws. */
    fun generate(order: Int): List<List<Int>> {
        if (!isSupportedOrder(order)) return emptyList()
        val n = order
        val cards = ArrayList<List<Int>>(cardCount(n))
        for (m in 0 until n) for (b in 0 until n) {
            val card = ArrayList<Int>(n + 1)
            for (x in 0 until n) card += x * n + (m * x + b) % n
            card += n * n + m
            cards += card
        }
        for (c in 0 until n) {
            val card = ArrayList<Int>(n + 1)
            for (y in 0 until n) card += c * n + y
            card += n * n + n
            cards += card
        }
        cards += (0 until n).map { n * n + it } + (n * n + n)
        return cards
    }

    /** Deck order for [seed]: a seeded Fisher-Yates shuffle of 0 until [deckSize]. */
    fun shuffledOrder(deckSize: Int, seed: Long): List<Int> {
        if (deckSize <= 0) return emptyList()
        val list = MutableList(deckSize) { it }
        FindItRng(seed).shuffle(list)
        return list
    }

    /** The card size a card of [symbolCount] pictures belongs to (null for any other count). */
    fun sizeOfCard(symbolCount: Int): FindItCardSize? = FindItCardSize.values().firstOrNull { it.symbolsPerCard == symbolCount }

    /** Radius of a ring slot's cell in the unit disc. */
    fun cellRadius(size: FindItCardSize): Float = when (size) {
        FindItCardSize.TINY -> 0.414f
        FindItCardSize.LITTLE -> 0.333f
        FindItCardSize.STANDARD -> 0.30f
    }

    /** Radius of slot [slotIndex]'s cell (the STANDARD card's slot 0 is the larger centre slot). */
    fun cellRadius(size: FindItCardSize, slotIndex: Int): Float =
        if (size == FindItCardSize.STANDARD && slotIndex == 0) 0.40f else cellRadius(size)

    /** Minimum card diameter in dp that keeps every cell at least [FindItRules.MIN_CELL_DP] across. */
    fun minCardDiameterDp(size: FindItCardSize): Float = FindItRules.MIN_CELL_DP / cellRadius(size)

    /**
     * Layout of one card in the unit disc: TINY a ring of 4 at radius 0.586, LITTLE a ring of 6 at radius 0.667,
     * STANDARD one centre slot (scale 0.8) plus a ring of 7 at radius 0.697; seeded jitter of at most 0.03; the
     * picture-to-slot assignment is a seeded permutation; scale ranges TINY 0.90..1.00, LITTLE 0.80..1.00, STANDARD
     * 0.70..1.00; rotation within 45 degrees (TINY, LITTLE) or 0..359 (STANDARD). Deterministic for the same
     * ([card], [seed], [cardIndex]). Slot order is slot index order (STANDARD: centre first); symbols follow the permutation.
     */
    fun layoutCard(card: List<Int>, seed: Long, cardIndex: Int): List<FindItCardSlot> {
        val size = sizeOfCard(card.size) ?: return emptyList()
        val rng = FindItRng(findItDerive(seed, TAG_LAYOUT, cardIndex.toLong()))
        val order = card.toMutableList()
        rng.shuffle(order)
        val ringCount = if (size == FindItCardSize.STANDARD) 7 else card.size
        val ringRadius = when (size) {
            FindItCardSize.TINY -> 0.586f
            FindItCardSize.LITTLE -> 0.667f
            FindItCardSize.STANDARD -> 0.697f
        }
        val lo: Float
        val hi: Float
        when (size) {
            FindItCardSize.TINY -> { lo = 0.90f; hi = 1.00f }
            FindItCardSize.LITTLE -> { lo = 0.80f; hi = 1.00f }
            FindItCardSize.STANDARD -> { lo = 0.70f; hi = 1.00f }
        }
        val phase = rng.nextDouble() * 2 * PI
        val slots = ArrayList<FindItCardSlot>(card.size)
        var next = 0
        fun jitter() = ((rng.nextDouble() * 2 - 1) * 0.03).toFloat()
        fun rotation() = if (size == FindItCardSize.STANDARD) rng.nextInt(360).toFloat() else ((rng.nextDouble() * 2 - 1) * 45).toFloat()
        if (size == FindItCardSize.STANDARD) {
            slots += FindItCardSlot(order[next++], jitter(), jitter(), 0.8f, rotation())
        }
        for (i in 0 until ringCount) {
            val a = phase + i * 2 * PI / ringCount
            val scale = lo + (hi - lo) * rng.nextDouble().toFloat()
            slots += FindItCardSlot(
                order[next++],
                (cos(a) * ringRadius).toFloat() + jitter(), (sin(a) * ringRadius).toFloat() + jitter(),
                scale, rotation()
            )
        }
        return slots
    }

    /** The slot of [layout] a tap at ([x], [y]) in the unit disc selects, or -1 (a tap in a gap is never a wrong tap). */
    fun hitSlot(size: FindItCardSize, layout: List<FindItCardSlot>, x: Float, y: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in layout.indices) {
            val d = hypot(layout[i].cx - x, layout[i].cy - y)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0) return -1
        return if (bestD <= 1.15f * cellRadius(size, best)) best else -1
    }

    /**
     * Best-candidate scene for Group Hunt on the virtual 1.0 x 1.333 canvas: [targets] exactly once, decoys at most
     * twice, up to [itemCount] items, item radius 0.085 x scale, minimum centre distance 1.15 x the sum of the two
     * radii, scale 0.90..1.10, rotation within 35 degrees, 30 candidates per item, capacity fallback as in the doc.
     * Decoys come from the [pictureCount] pictures that are not targets. Deterministic for the same arguments.
     */
    fun buildScene(targets: List<Int>, itemCount: Int, pictureCount: Int, seed: Long): FindItScene {
        val rng = FindItRng(findItDerive(seed, TAG_SCENE))
        val uniqueTargets = targets.distinct()
        val decoyPool = (0 until pictureCount).filter { it !in uniqueTargets }.toMutableList()
        rng.shuffle(decoyPool)
        val wanted = (itemCount - uniqueTargets.size).coerceAtLeast(0)
        val decoys = ArrayList<Int>()
        var pass = 0
        while (decoys.size < wanted && pass < 2 && decoyPool.isNotEmpty()) {
            for (d in decoyPool) { if (decoys.size < wanted) decoys += d }
            pass++
        }
        val placed = ArrayList<FindItSceneItem>()
        fun place(symbol: Int, mustPlace: Boolean): Boolean {
            var low = 0.90f
            var attempts = 0
            while (true) {
                var best: FindItSceneItem? = null
                var bestScore = -1f
                for (c in 0 until 30) {
                    val scale = low + (1.10f - low) * rng.nextDouble().toFloat()
                    val r = FindItScene.ITEM_RADIUS * scale
                    val x = r + (1f - 2 * r) * rng.nextDouble().toFloat()
                    val y = r + (FindItScene.HEIGHT - 2 * r) * rng.nextDouble().toFloat()
                    val rot = ((rng.nextDouble() * 2 - 1) * 35).toFloat()
                    val cand = FindItSceneItem(symbol, x, y, scale, rot)
                    var minGap = Float.MAX_VALUE
                    var ok = true
                    for (p in placed) {
                        val d = hypot(p.x - x, p.y - y)
                        if (d < 1.15f * (FindItScene.ITEM_RADIUS * p.scale + r)) { ok = false; break }
                        if (d < minGap) minGap = d
                    }
                    if (ok && minGap > bestScore) { bestScore = minGap; best = cand }
                }
                val found = best
                if (found != null) { placed += found; return true }
                attempts++
                if (low > 0.80f + 0.0001f) { low = (low - 0.05f).coerceAtLeast(0.80f); continue }
                if (!mustPlace || attempts > 40) return false
            }
        }
        for (t in uniqueTargets) place(t, mustPlace = true)
        for (d in decoys) place(d, mustPlace = false)
        return FindItScene(placed.toList(), uniqueTargets)
    }

    /** The seed stream a Group Hunt scene uses, so a retry after a sunset rebuilds the very same scene. */
    fun sceneSeed(gameSeed: Long, sceneIndex: Int): Long = findItDerive(gameSeed, 0x5345L, sceneIndex.toLong())
}
