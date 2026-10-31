package com.gamesuite.games.hanafuda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Where every Hanafuda card rests, in portrait and landscape. */
class HanafudaLayoutTest {
    private val portrait = HanafudaLayout.metrics(360f, 560f)
    private val landscape = HanafudaLayout.metrics(780f, 330f)

    @Test fun orientationFollowsTheAvailableShape() {
        assertFalse(portrait.landscape)
        assertTrue(landscape.landscape)
    }

    @Test fun theZonesFitTheAvailableSpaceInBothOrientations() {
        for ((m, w) in listOf(portrait to 360f, landscape to 780f)) {
            for (z in listOf(m.oppHand, m.oppCaptured, m.field, m.myCaptured, m.myHand)) {
                assertTrue(z.x >= -0.01f && z.right <= w + 0.01f)
                assertTrue(z.y >= -0.01f && z.bottom <= m.height + 0.01f)
            }
        }
        assertTrue(portrait.height <= 560f + 1f)
        assertTrue(landscape.height <= 330f + 1f)
    }

    @Test fun zonesDoNotOverlapEachOther() {
        for (m in listOf(portrait, landscape)) {
            val zones = listOf(m.oppHand, m.oppCaptured, m.field, m.myCaptured, m.myHand)
            for (i in zones.indices) for (j in i + 1 until zones.size) {
                val a = zones[i]
                val b = zones[j]
                val overlap = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom
                assertFalse("zones $i and $j overlap", overlap)
            }
        }
    }

    @Test fun handCardsAreAtLeastTheMinimumSizeForReading() {
        assertTrue(portrait.cardW >= 40f)
        assertEquals(portrait.cardW * HanafudaLayout.ASPECT, portrait.cardH, 0.01f)
        assertTrue(landscape.cardW >= 30f)
    }

    @Test fun everyVisibleCardHasOnePlacementAndHiddenOnesAreFaceDown() {
        val r = KoiRules.deal(Random(1), 0, YakuSet.BASIC).redactedFor(0)
        val p = HanafudaLayout.placements(r, 0, portrait)
        assertEquals(8 + 8 + 8 + 24, p.size)
        assertTrue(r.hands[0].all { p.getValue(it).faceUp && p.getValue(it).zone == HfZone.MY_HAND })
        assertTrue(r.hands[1].all { !p.getValue(it).faceUp && p.getValue(it).zone == HfZone.OPP_HAND })
        assertTrue(r.deck.all { !p.getValue(it).faceUp && p.getValue(it).zone == HfZone.DECK })
        assertTrue(r.field.all { p.getValue(it).faceUp && p.getValue(it).zone == HfZone.FIELD })
    }

    @Test fun handCardsAreSpreadLeftToRightInsideTheirZone() {
        val r = KoiRules.deal(Random(2), 0, YakuSet.BASIC).redactedFor(0)
        for (m in listOf(portrait, landscape)) {
            val p = HanafudaLayout.placements(r, 0, m)
            val xs = r.hands[0].map { p.getValue(it).x }
            assertEquals(xs.sorted(), xs)
            assertTrue(xs.first() >= m.myHand.x - 0.01f)
            assertTrue(xs.last() + m.cardW <= m.myHand.right + 0.01f)
            assertEquals(8, xs.distinct().size)
            // Neighbouring cards overlap by less than half, so each stays tappable.
            val step = xs[1] - xs[0]
            assertTrue("step $step too small for width ${m.cardW}", step >= m.cardW * 0.45f)
        }
    }

    @Test fun fieldCardsFormAGridNextToTheDeckAndDoNotCollide() {
        val r = KoiRules.deal(Random(3), 0, YakuSet.BASIC).redactedFor(0)
        val p = HanafudaLayout.placements(r, 0, portrait)
        val spots = r.field.map { p.getValue(it).x to p.getValue(it).y }
        assertEquals(spots.size, spots.toSet().size)
        assertTrue(r.field.all { p.getValue(it).x >= portrait.field.x + portrait.fieldCellW - 0.01f })
        for (a in r.field.indices) for (b in a + 1 until r.field.size) {
            val pa = p.getValue(r.field[a])
            val pb = p.getValue(r.field[b])
            val overlap = pa.x < pb.x + pb.w - 0.5f && pb.x < pa.x + pa.w - 0.5f && pa.y < pb.y + pb.h - 0.5f && pb.y < pa.y + pa.h - 0.5f
            assertFalse("field cards $a and $b overlap", overlap)
        }
    }

    @Test fun aFullFieldOfFifteenStillFits() {
        val base = KoiRules.deal(Random(4), 0, YakuSet.BASIC)
        val field = (0 until 15).toList()
        val table = base.copy(field = field, hands = listOf(emptyList(), emptyList()), deck = emptyList(), captured = listOf(emptyList(), emptyList()))
        val p = HanafudaLayout.placements(table, 0, portrait)
        field.forEach {
            val pl = p.getValue(it)
            assertTrue(pl.x + pl.w <= portrait.width + 0.5f)
            assertTrue(pl.y + pl.h <= portrait.field.bottom + 0.5f)
        }
    }

    @Test fun capturedCardsAreGroupedByClassInsideTheirStrip() {
        val captured = listOf(HanafudaCards.idOf(HanaSpecial.BOAR), 2, HanafudaCards.idOf(HanaSpecial.CRANE), 6, 1, 5)
        val r = KoiRound(
            YakuSet.BASIC, 0, 0, KoiPhase.PLAY_HAND, deck = emptyList(), field = emptyList(),
            hands = listOf(emptyList(), emptyList()), captured = listOf(captured, emptyList())
        )
        val p = HanafudaLayout.placements(r, 0, portrait)
        val order = captured.sortedBy { p.getValue(it).x }
        val kinds = order.map { HanafudaCards.card(it).kind.ordinal }
        assertEquals(kinds.sorted(), kinds)
        captured.forEach {
            val pl = p.getValue(it)
            assertTrue(pl.x >= portrait.myCaptured.x - 0.01f)
            assertTrue(pl.x + pl.w <= portrait.myCaptured.right + 0.5f)
            assertEquals(HfZone.MY_CAPTURED, pl.zone)
        }
    }

    @Test fun manyCapturedCardsCompressToStayInsideTheStrip() {
        val many = (0 until 30).toList()
        val r = KoiRound(
            YakuSet.BASIC, 0, 0, KoiPhase.PLAY_HAND, deck = emptyList(), field = emptyList(),
            hands = listOf(emptyList(), emptyList()), captured = listOf(many, emptyList())
        )
        val p = HanafudaLayout.placements(r, 0, portrait)
        many.forEach {
            val pl = p.getValue(it)
            assertTrue(pl.x + pl.w <= portrait.myCaptured.right + 1f)
            assertTrue(pl.x >= portrait.myCaptured.x - 0.01f)
        }
    }

    @Test fun viewingFromTheOtherSeatSwapsTopAndBottom() {
        val full = KoiRules.deal(Random(5), 0, YakuSet.BASIC)
        val a = HanafudaLayout.placements(full.redactedFor(0), 0, portrait)
        val b = HanafudaLayout.placements(full.redactedFor(1), 1, portrait)
        val myA = full.hands[0].first()
        val myB = full.hands[1].first()
        assertEquals(HfZone.MY_HAND, a.getValue(myA).zone)
        assertEquals(HfZone.MY_HAND, b.getValue(myB).zone)
        assertTrue(a.getValue(myA).y > portrait.field.bottom)
    }

    @Test fun theDrawnCardHasItsOwnSlotAndTheDeckIsOneStack() {
        val base = KoiRules.deal(Random(6), 0, YakuSet.BASIC)
        val drawn = base.deck.last()
        val r = base.copy(deck = base.deck.dropLast(1), drawn = drawn, phase = KoiPhase.CHOOSE_DRAW_MATCH).redactedFor(0)
        val p = HanafudaLayout.placements(r, 0, portrait)
        assertEquals(HfZone.DRAWN, p.getValue(drawn).zone)
        assertTrue(p.getValue(drawn).faceUp)
        val deckXs = r.deck.map { p.getValue(it).x }
        assertTrue(deckXs.max() - deckXs.min() < 3f)
    }

    @Test fun fieldIndexAtFindsTheCardUnderAPoint() {
        val r = KoiRules.deal(Random(7), 0, YakuSet.BASIC).redactedFor(0)
        r.field.indices.forEach { i ->
            val (x, y) = HanafudaLayout.fieldSlot(i, portrait)
            assertEquals(i, HanafudaLayout.fieldIndexAt(x + portrait.fieldCellW / 2f, y + portrait.fieldCellH / 2f, r, portrait))
        }
        assertEquals(-1, HanafudaLayout.fieldIndexAt(-5f, -5f, r, portrait))
    }
}
