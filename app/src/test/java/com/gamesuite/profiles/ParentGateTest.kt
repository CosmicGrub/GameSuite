package com.gamesuite.profiles

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentGateTest {
    private var t = 1_000_000L      // wall clock
    private var mono = 50_000L      // monotonic clock
    private var store = InMemoryGateStateStore()

    /** Wall and monotonic time normally advance together; tests move them separately to model tampering. */
    private fun advance(ms: Long) { t += ms; mono += ms }

    private fun gate(seed: Int = 1, s: GateStateStore = store) = ParentGate({ t }, Random(seed), { mono }, s)
    private fun ParentGate.wrong() = question.answer + 1

    private fun evaluate(text: String): Int {
        val parts = text.split(" ")
        val a = parts[0].toInt()
        val b = parts[2].toInt()
        return when (parts[1]) {
            "+" -> a + b
            "-" -> a - b
            "×" -> a * b
            else -> error("unknown operator in $text")
        }
    }

    @Test fun `correct answer opens and draws a fresh question`() {
        val g = gate()
        val q = g.question
        assertEquals(GateResult.Correct, g.submit(q.answer.toString()))
        assertNotEquals(q.text, g.question.text)
    }

    @Test fun `questions are fresh each time and carry the right answer`() {
        val g = gate(7)
        val seen = mutableSetOf<String>()
        repeat(60) {
            val q = g.question
            assertEquals(evaluate(q.text), q.answer)
            assertTrue("answer must be positive: ${q.text}", q.answer > 0)
            seen += q.text
            g.submit(q.answer.toString())
        }
        assertTrue(seen.size > 40)
    }

    @Test fun `question pool is large and mixes the three shapes`() {
        val g = gate(11)
        val texts = (0 until 3000).map { g.next().text }
        assertTrue("only ${texts.toSet().size} distinct questions", texts.toSet().size > 1500)
        assertTrue(texts.any { "×" in it })
        assertTrue(texts.any { " + " in it })
        assertTrue(texts.any { " - " in it })
        val answers = texts.map { evaluate(it) }.toSet()
        assertTrue("only ${answers.size} distinct answers", answers.size > 600)
    }

    @Test fun `products are two digit by one digit and sums and differences use three digits`() {
        val g = gate(5)
        repeat(1000) {
            val parts = g.next().text.split(" ")
            val a = parts[0].toInt()
            val b = parts[2].toInt()
            when (parts[1]) {
                "×" -> { assertTrue(a in 12..99); assertTrue(b in 3..9) }
                "+" -> { assertTrue(a in 100..999); assertTrue(b in 100..999) }
                "-" -> { assertTrue(a in 300..999); assertTrue(b in 100..999); assertTrue(a - b >= 100) }
            }
        }
    }

    @Test fun `consecutive questions never repeat`() {
        val g = gate(3)
        var prev = g.question.text
        repeat(500) {
            val n = g.next().text
            assertNotEquals(prev, n)
            prev = n
        }
    }

    @Test fun `three wrong answers lock for 30 seconds then reopen`() {
        val g = gate()
        assertEquals(GateResult.Wrong(2), g.submit(g.wrong().toString()))
        assertEquals(GateResult.Wrong(1), g.submit(g.wrong().toString()))
        assertEquals(GateResult.LockedOut(30), g.submit(g.wrong().toString()))
        // even the right answer is refused during the lockout
        assertTrue(g.submit(g.question.answer.toString()) is GateResult.LockedOut)
        advance(29_000)
        assertEquals(1, g.lockoutSecondsLeft())
        advance(1_000)
        assertEquals(0, g.lockoutSecondsLeft())
        assertEquals(GateResult.Correct, g.submit(g.question.answer.toString()))
    }

    @Test fun `failures reset after a correct answer`() {
        val g = gate()
        g.submit(g.wrong().toString())
        g.submit(g.wrong().toString())
        assertEquals(GateResult.Correct, g.submit(g.question.answer.toString()))
        assertEquals(GateResult.Wrong(2), g.submit(g.wrong().toString()))
    }

    @Test fun `after a lockout the attempt counter starts again`() {
        val g = gate()
        repeat(3) { g.submit(g.wrong().toString()) }
        advance(31_000)
        assertEquals(GateResult.Wrong(2), g.submit(g.wrong().toString()))
    }

    @Test fun `non numbers are not attempts`() {
        val g = gate()
        assertEquals(GateResult.NotANumber, g.submit("abc"))
        assertEquals(GateResult.NotANumber, g.submit(""))
        assertEquals(GateResult.Wrong(2), g.submit(g.wrong().toString()))
    }

    // ---- state outlives the screen: a NEW gate instance continues where the last one stopped ----

    @Test fun `reopening the gate keeps the failure count`() {
        val first = gate(1)
        first.submit(first.wrong().toString())
        first.submit(first.wrong().toString())
        val reopened = gate(2) // a new ParentGate, as ParentGateScreen builds on every open
        assertEquals(GateResult.LockedOut(30), reopened.submit(reopened.wrong().toString()))
    }

    @Test fun `reopening the gate during a lockout is still locked`() {
        val first = gate(1)
        repeat(3) { first.submit(first.wrong().toString()) }
        advance(10_000)
        val reopened = gate(9)
        assertEquals(20, reopened.lockoutSecondsLeft())
        assertTrue(reopened.submit(reopened.question.answer.toString()) is GateResult.LockedOut)
    }

    @Test fun `lockouts escalate to 5 then 30 minutes and a correct answer resets the level`() {
        var g = gate()
        repeat(3) { g.submit(g.wrong().toString()) }
        assertEquals(30, g.lockoutSecondsLeft())
        advance(31_000)
        g = gate()
        repeat(3) { g.submit(g.wrong().toString()) }
        assertEquals(5 * 60, g.lockoutSecondsLeft())
        advance(5 * 60_000 + 1_000)
        g = gate()
        repeat(3) { g.submit(g.wrong().toString()) }
        assertEquals(30 * 60, g.lockoutSecondsLeft())
        advance(30 * 60_000 + 1_000)
        g = gate()
        repeat(3) { g.submit(g.wrong().toString()) }
        assertEquals("stays at 30 minutes", 30 * 60, g.lockoutSecondsLeft())
        advance(30 * 60_000 + 1_000)
        g = gate()
        assertEquals(GateResult.Correct, g.submit(g.question.answer.toString()))
        assertEquals(GateLockState(), store.load())
        repeat(3) { g.submit(g.wrong().toString()) }
        assertEquals(30, g.lockoutSecondsLeft())
    }

    // ---- clock tampering ----

    @Test fun `moving the wall clock forward does not skip the lockout`() {
        val g = gate()
        repeat(3) { g.submit(g.wrong().toString()) }
        t += 3_600_000L // wall clock +1 h, monotonic unchanged
        assertEquals(30, g.lockoutSecondsLeft())
        assertEquals(30, gate(4).lockoutSecondsLeft())
    }

    @Test fun `moving the wall clock backward does not extend or break the lockout`() {
        val g = gate()
        repeat(3) { g.submit(g.wrong().toString()) }
        t -= 7 * 24 * 3_600_000L
        advance(0)
        mono += 10_000
        assertEquals(20, g.lockoutSecondsLeft())
        mono += 20_000
        assertEquals(0, g.lockoutSecondsLeft())
    }

    @Test fun `after a reboot the persisted wall time is the fallback and a backwards clock keeps the lockout`() {
        val g = gate()
        repeat(3) { g.submit(g.wrong().toString()) }
        mono = 100L // rebooted: the monotonic clock restarted below the anchor
        t += 12_000L
        assertEquals(18, g.lockoutSecondsLeft())
        t -= 600_000L // wall clock set back
        assertEquals(30, g.lockoutSecondsLeft())
        t += 600_000L + 60_000L
        assertEquals(0, g.lockoutSecondsLeft())
    }

    @Test fun `a lockout never reads longer than its length`() {
        val s = GateLockState(failures = 3, level = 1, lockLengthMillis = 30_000, lockStartWall = 5_000, lockStartElapsed = 9_000)
        assertEquals(30_000, GateLockout.remainingMillis(s, wallNow = 1_000, elapsedNow = 9_000))
        assertEquals(0, GateLockout.remainingMillis(GateLockState(), 1, 1))
        assertEquals(0, GateLockout.remainingMillis(s, wallNow = 0, elapsedNow = 9_000 + 31_000))
    }

    @Test fun `gate state round trips and an unreadable value is a clean state`() {
        val s = GateLockState(failures = 2, level = 2, lockLengthMillis = 300_000, lockStartWall = 7, lockStartElapsed = 9)
        assertEquals(s, GateStateCodec.decode(GateStateCodec.encode(s)))
        assertEquals(GateLockState(), GateStateCodec.decode("garbage"))
        assertEquals(GateLockState(), GateStateCodec.decode(null))
    }

    @Test fun `the process wide store is shared and notifies its persistence hook`() {
        val shared = ProcessGateStateStore.instance
        val saved = mutableListOf<GateLockState>()
        val before = shared.persist
        try {
            shared.save(GateLockState())
            shared.persist = { saved += it }
            val a = ParentGate({ t }, Random(1), { mono }, shared)
            a.submit(a.wrong().toString())
            val b = ParentGate({ t }, Random(2), { mono }, shared)
            assertEquals(GateResult.Wrong(1), b.submit(b.wrong().toString()))
            assertEquals(2, saved.size)
            assertFalse(saved.last().locked)
            shared.hydrate(GateLockState(failures = 2))
            val c = ParentGate({ t }, Random(3), { mono }, shared)
            assertTrue(c.submit(c.wrong().toString()) is GateResult.LockedOut)
        } finally {
            shared.persist = before
            shared.hydrate(GateLockState())
        }
    }
}
