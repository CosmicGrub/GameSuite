package com.gamesuite.profiles

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentGateMethodsTest {
    private val en = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val es = listOf("cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve")

    private var t = 1_000_000L
    private var mono = 50_000L
    private val store = InMemoryGateStateStore()
    private fun advance(ms: Long) { t += ms; mono += ms }

    private fun gate(
        method: GateMethod,
        seed: Int = 1,
        words: List<String> = en,
        digits: Int = 3,
        pin: String = "4821",
        s: GateStateStore = store
    ) = ParentGate({ t }, Random(seed), { mono }, s, method, words, digits, { it == pin })

    private fun ParentGate.wrongAnswer(): String = when (method) {
        GateMethod.PIN -> "0000"
        else -> (question.answer + 1).toString()
    }

    /** "seven four two" -> 742 using the given digit words. */
    private fun decode(text: String, words: List<String>): Int =
        text.split(" ").fold(0) { acc, w -> acc * 10 + words.indexOf(w).also { assertTrue("unknown word $w", it >= 0) } }

    // ---- WORD_CODE generator ----

    @Test fun `word code length follows the age band`() {
        assertEquals(3, GateMethods.wordDigits(null))
        assertEquals(3, GateMethods.wordDigits(AgeBand.AGE_4_6))
        assertEquals(4, GateMethods.wordDigits(AgeBand.AGE_7_9))
        assertEquals(4, GateMethods.wordDigits(AgeBand.AGE_10_12))
        val r = Random(2)
        assertEquals(3, WordCode.generate(r, GateMethods.wordDigits(AgeBand.AGE_4_6), en).text.split(" ").size)
        assertEquals(4, WordCode.generate(r, GateMethods.wordDigits(AgeBand.AGE_7_9), en).text.split(" ").size)
        assertEquals(4, WordCode.generate(r, GateMethods.wordDigits(AgeBand.AGE_10_12), en).text.split(" ").size)
    }

    @Test fun `word code is spelled correctly in English and Spanish and the answer equals the digits`() {
        for ((words, label) in listOf(en to "en", es to "es")) {
            val r = Random(9)
            repeat(300) {
                for (n in 3..4) {
                    val q = WordCode.generate(r, n, words)
                    assertEquals("$label ${q.text}", decode(q.text, words), q.answer)
                    assertTrue(q.answer.toString().length == n) // no leading zero, so it is exactly n digits
                    assertTrue(q.text.split(" ").all { it in words })
                }
            }
        }
        assertEquals("seven four two", listOf(7, 4, 2).joinToString(" ") { en[it] })
        assertEquals("siete cuatro dos", listOf(7, 4, 2).joinToString(" ") { es[it] })
    }

    @Test fun `word code is deterministic for a seed and varies between seeds`() {
        val a = (0 until 20).map { WordCode.generate(Random(5), 3, en) }
        assertEquals(1, a.toSet().size)
        val r1 = Random(5); val r2 = Random(5)
        repeat(50) { assertEquals(WordCode.generate(r1, 4, es), WordCode.generate(r2, 4, es)) }
        val distinct = (0 until 200).map { WordCode.generate(Random(it), 3, en).text }.toSet()
        assertTrue(distinct.size > 100)
    }

    @Test fun `word code never repeats twice in a row through the gate`() {
        val g = gate(GateMethod.WORD_CODE, seed = 3, digits = 3)
        var prev = g.question.text
        repeat(500) {
            val n = g.next().text
            assertNotEquals(prev, n)
            prev = n
        }
    }

    @Test fun `word code gate accepts the digits and rejects others`() {
        val g = gate(GateMethod.WORD_CODE)
        val ans = g.question.answer
        assertEquals(GateResult.Wrong(2), g.submit((ans + 1).toString()))
        assertEquals(GateResult.Correct, g.submit(g.question.answer.toString()))
        assertEquals(GateResult.NotANumber, g.submit("seven"))
    }

    @Test fun `word code needs exactly ten words`() {
        try {
            WordCode.generate(Random(1), 3, en.take(9))
            error("should have thrown")
        } catch (_: IllegalArgumentException) { }
        // the gate itself falls back to a sum instead of crashing
        val g = ParentGate({ t }, Random(1), { mono }, InMemoryGateStateStore(), GateMethod.WORD_CODE, emptyList(), 3)
        assertTrue(g.question.text.isNotEmpty())
    }

    // ---- PIN ----

    @Test fun `pin validation is exactly four ascii digits`() {
        assertTrue(PinHasher.isValidPin("0000"))
        assertTrue(PinHasher.isValidPin("4821"))
        for (bad in listOf("", "123", "12345", "12a4", " 123", "١٢٣٤", "12 4")) assertFalse(bad, PinHasher.isValidPin(bad))
        assertFalse(PinHasher.isValidPin(null))
        assertNull(PinHasher.create("12"))
    }

    @Test fun `pin verifies and a wrong pin does not`() {
        val s = PinHasher.create("4821")!!
        assertTrue(PinHasher.verify("4821", s.salt, s.hash))
        assertFalse(PinHasher.verify("4822", s.salt, s.hash))
        assertFalse(PinHasher.verify("482", s.salt, s.hash))
        assertFalse(PinHasher.verify("4821", null, s.hash))
        assertFalse(PinHasher.verify("4821", s.salt, null))
        assertFalse(PinHasher.verify("4821", "zz", s.hash))
        assertFalse(PinHasher.verify("4821", "", ""))
        assertFalse("the PIN itself is never stored", s.hash.contains("4821") || s.salt.contains("4821"))
    }

    @Test fun `salt is 16 random bytes and unique per call so equal pins hash differently`() {
        val a = PinHasher.create("1111")!!
        val b = PinHasher.create("1111")!!
        assertEquals(32, a.salt.length) // 16 bytes as hex
        assertEquals(64, a.hash.length) // 256 bits as hex
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.hash, b.hash)
        assertTrue(PinHasher.verify("1111", a.salt, a.hash))
        assertTrue(PinHasher.verify("1111", b.salt, b.hash))
        // a hash made with another salt does not verify
        assertFalse(PinHasher.verify("1111", a.salt, b.hash))
        assertTrue(PinHasher.ITERATIONS >= 10_000)
    }

    @Test fun `constant time compare`() {
        assertTrue(PinHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(PinHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(PinHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(PinHasher.constantTimeEquals(byteArrayOf(), byteArrayOf(0)))
        assertTrue(PinHasher.constantTimeEquals(byteArrayOf(), byteArrayOf()))
        assertArrayEquals(byteArrayOf(0, 15, -1), PinHasher.fromHex(PinHasher.toHex(byteArrayOf(0, 15, -1))))
        assertNull(PinHasher.fromHex("abc"))
        assertNull(PinHasher.fromHex("gg"))
    }

    @Test fun `clearing the pin removes it and nothing verifies`() {
        val s = PinHasher.create("4821")!!
        val with = ControlsState(gateMethod = GateMethod.PIN, pinSalt = s.salt, pinHash = s.hash)
        assertTrue(with.hasPin)
        val cleared = with.copy(pinSalt = null, pinHash = null, gateMethod = null)
        assertFalse(cleared.hasPin)
        assertFalse(PinHasher.verify("4821", cleared.pinSalt, cleared.pinHash))
        assertFalse(ControlsState(pinSalt = "", pinHash = "").hasPin)
        assertFalse(ControlsState(pinSalt = "aa", pinHash = null).hasPin)
    }

    @Test fun `pin gate needs four digits, which is not an attempt, and uses the checker`() {
        val g = gate(GateMethod.PIN)
        assertEquals(GateResult.NotANumber, g.submit("12"))
        assertEquals(GateResult.NotANumber, g.submit("abcd"))
        assertEquals(GateResult.Wrong(2), g.submit("0000"))
        assertEquals(GateResult.Correct, g.submit("4821"))
    }

    // ---- shared lockout ----

    @Test fun `three wrong answers in any mix of methods lock and switching does not reset`() {
        val g = gate(GateMethod.SUMS)
        assertEquals(GateResult.Wrong(2), g.submit(g.wrongAnswer()))
        g.useMethod(GateMethod.WORD_CODE)
        assertEquals(GateResult.Wrong(1), g.submit(g.wrongAnswer()))
        g.useMethod(GateMethod.PIN)
        assertEquals(GateResult.LockedOut(30), g.submit("0000"))
        g.useMethod(GateMethod.SUMS)
        assertTrue(g.submit(g.question.answer.toString()) is GateResult.LockedOut)
        g.useMethod(GateMethod.WORD_CODE)
        assertTrue(g.submit(g.question.answer.toString()) is GateResult.LockedOut)
        advance(30_000)
        assertEquals(GateResult.Correct, g.submit(g.question.answer.toString()))
    }

    @Test fun `a separate gate in another method continues the same attempts`() {
        val a = gate(GateMethod.PIN)
        a.submit("0000")
        a.submit("0000")
        val b = gate(GateMethod.SUMS, seed = 4)
        assertTrue(b.submit(b.wrongAnswer()) is GateResult.LockedOut)
    }

    @Test fun `lockout still escalates across methods`() {
        var g = gate(GateMethod.SUMS)
        repeat(3) { g.submit(g.wrongAnswer()) }
        assertEquals(30, g.lockoutSecondsLeft())
        advance(31_000)
        g = gate(GateMethod.PIN)
        repeat(3) { g.submit("0000") }
        assertEquals(5 * 60, g.lockoutSecondsLeft())
    }

    // ---- phone lock ----

    @Test fun `a failed or cancelled credential prompt is not an attempt`() {
        val g = gate(GateMethod.PHONE_LOCK)
        repeat(10) { assertEquals(GateResult.CredentialNotConfirmed, g.credentialFailed()) }
        assertEquals(GateLockState(), store.load())
        // so three real wrong answers are still needed to lock
        g.useMethod(GateMethod.SUMS)
        assertEquals(GateResult.Wrong(2), g.submit(g.wrongAnswer()))
    }

    @Test fun `a confirmed credential opens and clears the failures and escalation`() {
        val g = gate(GateMethod.SUMS)
        g.submit(g.wrongAnswer())
        g.submit(g.wrongAnswer())
        g.useMethod(GateMethod.PHONE_LOCK)
        assertEquals(GateResult.Correct, g.credentialSucceeded())
        assertEquals(GateLockState(), store.load())
    }

    @Test fun `a confirmed credential is refused during a lockout`() {
        val g = gate(GateMethod.SUMS)
        repeat(3) { g.submit(g.wrongAnswer()) }
        g.useMethod(GateMethod.PHONE_LOCK)
        assertTrue(g.credentialSucceeded() is GateResult.LockedOut)
        advance(30_000)
        assertEquals(GateResult.Correct, g.credentialSucceeded())
    }

    @Test fun `typing into the phone lock method never opens`() {
        val g = gate(GateMethod.PHONE_LOCK)
        assertEquals(GateResult.NotANumber, g.submit("0"))
        assertEquals(GateLockState(), store.load())
    }

    @Test fun `prompts are rate limited to one per two seconds`() {
        var now = 10_000L
        val rl = PromptRateLimiter(2_000L) { now }
        assertTrue(rl.tryAcquire())
        now += 500
        assertFalse(rl.tryAcquire())
        now += 1_499
        assertFalse(rl.tryAcquire())
        now += 1
        assertTrue(rl.tryAcquire())
        now += 100
        assertFalse(rl.tryAcquire())
    }

    // ---- availability and default ----

    @Test fun `availability rules`() {
        assertEquals(listOf(GateMethod.WORD_CODE, GateMethod.SUMS), GateMethods.available(isDeviceSecure = false, hasPin = false))
        assertEquals(listOf(GateMethod.PHONE_LOCK, GateMethod.WORD_CODE, GateMethod.SUMS), GateMethods.available(true, false))
        assertEquals(listOf(GateMethod.WORD_CODE, GateMethod.PIN, GateMethod.SUMS), GateMethods.available(false, true))
        assertEquals(GateMethod.values().toList(), GateMethods.available(true, true))
    }

    @Test fun `auto default is phone lock on a secure device else word code`() {
        assertEquals(GateMethod.PHONE_LOCK, GateMethods.defaultFor(null, true, false))
        assertEquals(GateMethod.PHONE_LOCK, GateMethods.defaultFor(null, true, true))
        assertEquals(GateMethod.WORD_CODE, GateMethods.defaultFor(null, false, false))
        assertEquals(GateMethod.WORD_CODE, GateMethods.defaultFor(null, false, true))
    }

    @Test fun `an explicit choice wins only while it is usable`() {
        assertEquals(GateMethod.SUMS, GateMethods.defaultFor(GateMethod.SUMS, true, true))
        assertEquals(GateMethod.PIN, GateMethods.defaultFor(GateMethod.PIN, false, true))
        assertEquals(GateMethod.PHONE_LOCK, GateMethods.defaultFor(GateMethod.PHONE_LOCK, true, false))
        // PIN chosen but removed, phone lock chosen but the device lock is gone: back to automatic
        assertEquals(GateMethod.PHONE_LOCK, GateMethods.defaultFor(GateMethod.PIN, true, false))
        assertEquals(GateMethod.WORD_CODE, GateMethods.defaultFor(GateMethod.PIN, false, false))
        assertEquals(GateMethod.WORD_CODE, GateMethods.defaultFor(GateMethod.PHONE_LOCK, false, false))
    }

    // ---- codec ----

    @Test fun `new controls fields round trip`() {
        val s = PinHasher.create("2468")!!
        val c = ControlsState(
            onlinePlayEnabled = false,
            kids = mapOf("k1" to KidControls(dailyMinutes = 30)),
            gateMethod = GateMethod.PIN,
            pinSalt = s.salt,
            pinHash = s.hash
        )
        val back = ControlsCodec.decode(ControlsCodec.encode(c))
        assertEquals(c, back)
        assertTrue(back.hasPin)
        assertTrue(PinHasher.verify("2468", back.pinSalt, back.pinHash))
        assertEquals(ControlsRead.Ok(c), ControlsCodec.read(ControlsCodec.encode(c)))
    }

    @Test fun `old controls json without the new fields decodes to defaults`() {
        val old = """{"onlinePlayEnabled":false,"kids":{"k1":{"dailyMinutes":45}}}"""
        val c = ControlsCodec.decode(old)
        assertNull(c.gateMethod)
        assertNull(c.pinSalt)
        assertNull(c.pinHash)
        assertFalse(c.hasPin)
        assertFalse(c.onlinePlayEnabled)
        assertEquals(45, c.forKid("k1").dailyMinutes)
        assertTrue(ControlsCodec.read(old) is ControlsRead.Ok)
    }

    @Test fun `an unknown stored gate method falls back to automatic instead of corrupting the controls`() {
        val c = ControlsCodec.decode("""{"onlinePlayEnabled":true,"gateMethod":"RETINA"}""")
        assertNull(c.gateMethod)
        assertNotNull(ControlsCodec.read("""{"gateMethod":"RETINA"}""") as? ControlsRead.Ok)
    }
}
