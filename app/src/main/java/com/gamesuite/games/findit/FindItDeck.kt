package com.gamesuite.games.findit

/**
 * The guaranteed-one-match deck: a finite projective plane of prime order n. n*n+n+1 cards, n*n+n+1 pictures,
 * n+1 pictures per card, any two cards share exactly one picture. Orders 3, 5 and 7 give 13, 31 and 57.
 *
 * Construction (picture indexes): affine point (x, y) -> x*n + y; slope point m -> n*n + m; vertical point -> n*n + n.
 *  - for m in 0 until n, b in 0 until n: card = { (x, (m*x + b) mod n) for x in 0 until n } + { slope m }
 *  - for c in 0 until n: card = { (c, y) for y in 0 until n } + { vertical point }
 *  - last card = all slope points + the vertical point
 * Proof sketch and edge cases: docs/design/kids-games/find-it.md (SCRIPT, "Deck generation").
 *
 * All stubs below return safe values until the engine milestone M0 (TODO(find-it)).
 */
object ProjectiveDeck {
    /** Only prime orders 3, 5 and 7 are supported (4 and 8 would need finite-field arithmetic). */
    fun isSupportedOrder(order: Int): Boolean = order == 3 || order == 5 || order == 7

    fun cardCount(order: Int): Int = order * order + order + 1

    /** TODO(find-it): the construction above. Unsupported order returns an empty list, never throws. */
    fun generate(order: Int): List<List<Int>> = emptyList()

    /** TODO(find-it): deck order for [seed]: `FindItRng(seed).shuffle` over 0 until [deckSize]. */
    fun shuffledOrder(deckSize: Int, seed: Long): List<Int> = emptyList()

    /**
     * TODO(find-it): layout of one card in the unit disc: TINY a ring of 4 at radius 0.586, LITTLE a ring of 6 at radius 0.667,
     * STANDARD one centre slot (scale 0.8) plus a ring of 7 at radius 0.697; seeded jitter of at most 0.03; the
     * picture-to-slot assignment is a seeded permutation; scale ranges TINY 0.90..1.00, LITTLE 0.80..1.00, STANDARD
     * 0.70..1.00; rotation within 45 degrees (TINY, LITTLE) or 0..359 (STANDARD). Deterministic for the same
     * ([card], [seed], [cardIndex]).
     */
    fun layoutCard(card: List<Int>, seed: Long, cardIndex: Int): List<FindItCardSlot> = emptyList()

    /**
     * TODO(find-it): best-candidate scene for Group Hunt on the virtual 1.0 x 1.333 canvas (x 0..1, y 0..1.333): [targets] exactly
     * once, decoys at most twice, [itemCount] items, item radius 0.085 x scale, minimum centre distance 1.15 x the sum
     * of the two radii, scale 0.90..1.10, rotation within 35 degrees, 30 candidates per item, capacity fallback in the doc.
     */
    fun buildScene(targets: List<Int>, itemCount: Int, pictureCount: Int, seed: Long): FindItScene =
        FindItScene(items = emptyList(), targets = targets)
}
