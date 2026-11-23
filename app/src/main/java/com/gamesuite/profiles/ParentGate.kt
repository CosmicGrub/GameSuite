package com.gamesuite.profiles

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The ways a grown-up can pass the gate. [SUMS] is the original maths question; [WORD_CODE] a spelled-out number
 * typed as digits; [PIN] a parent-chosen 4-digit PIN; [PHONE_LOCK] the device's own screen lock.
 */
enum class GateMethod { PHONE_LOCK, WORD_CODE, PIN, SUMS }

/** A challenge an adult can answer in seconds and a young child cannot guess (a maths question or a word code). */
data class GateQuestion(val text: String, val answer: Int)

sealed interface GateResult {
    object Correct : GateResult
    /** Wrong answer; [attemptsLeft] tries remain before the lockout. A fresh question has already been drawn. */
    data class Wrong(val attemptsLeft: Int) : GateResult
    /** Too many wrong answers (or tried during a lockout): wait [secondsLeft] seconds. */
    data class LockedOut(val secondsLeft: Int) : GateResult
    /** Not a number (or not a complete PIN). Does not count as an attempt. */
    object NotANumber : GateResult
    /** The phone-lock prompt was cancelled or failed. Does not count as an attempt. */
    object CredentialNotConfirmed : GateResult
}

/** Which methods the gate may offer and which one is shown first (pure, so it is unit tested). */
object GateMethods {
    /** Every usable method in display order: SUMS and WORD_CODE always; PIN only when one is set; PHONE_LOCK only on a secured device. */
    fun available(isDeviceSecure: Boolean, hasPin: Boolean): List<GateMethod> =
        GateMethod.values().filter {
            when (it) {
                GateMethod.PHONE_LOCK -> isDeviceSecure
                GateMethod.PIN -> hasPin
                else -> true
            }
        }

    /** The parent's explicit choice when it is still usable, else automatic: PHONE_LOCK on a secured device, otherwise WORD_CODE. */
    fun defaultFor(configured: GateMethod?, isDeviceSecure: Boolean, hasPin: Boolean): GateMethod {
        val usable = available(isDeviceSecure, hasPin)
        if (configured != null && configured in usable) return configured
        return if (isDeviceSecure) GateMethod.PHONE_LOCK else GateMethod.WORD_CODE
    }

    /** Word-code length: 3 digits for the youngest band or an adult, 4 for ages 7 to 12. */
    fun wordDigits(band: AgeBand?): Int = if (band == AgeBand.AGE_7_9 || band == AgeBand.AGE_10_12) 4 else 3
}

/** "seven four two" -> 742. The digit words (zero..nine, 10 entries) come from string resources so the domain stays Android-free. */
object WordCode {
    const val WORD_COUNT = 10

    fun generate(random: Random, digits: Int, words: List<String>): GateQuestion {
        require(words.size == WORD_COUNT) { "need exactly 10 digit words" }
        require(digits in 1..9)
        val ds = IntArray(digits) { i -> if (i == 0) random.nextInt(1, 10) else random.nextInt(0, 10) } // no leading zero
        val text = ds.joinToString(" ") { words[it] }
        val answer = ds.fold(0) { acc, d -> acc * 10 + d }
        return GateQuestion(text, answer)
    }
}

/** Salted PBKDF2 hashing for the parent PIN (java.security only, so it is JVM-testable and has no dependency). */
object PinHasher {
    const val PIN_LENGTH = 4
    const val ITERATIONS = 50_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    /** A salt and hash as lowercase hex, ready for [ControlsState]. */
    data class Stored(val salt: String, val hash: String)

    fun isValidPin(pin: String?): Boolean = pin != null && pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Compares without stopping at the first difference (length differences are folded in too). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = if (i < a.size) a[i].toInt() else 0
            val y = if (i < b.size) b[i].toInt() else 0
            diff = diff or (x xor y)
        }
        return diff == 0
    }

    /** Null when [pin] is not exactly 4 digits. A fresh random salt every call. */
    fun create(pin: String, random: SecureRandom = SecureRandom()): Stored? {
        if (!isValidPin(pin)) return null
        val salt = newSalt(random)
        return Stored(toHex(salt), toHex(hash(pin, salt)))
    }

    /** False for a malformed [pin], a missing or malformed stored value, or a wrong PIN. */
    fun verify(pin: String, saltHex: String?, hashHex: String?): Boolean {
        if (!isValidPin(pin)) return false
        val salt = fromHex(saltHex) ?: return false
        val expected = fromHex(hashHex) ?: return false
        if (salt.isEmpty() || expected.isEmpty()) return false
        return constantTimeEquals(hash(pin, salt), expected)
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun fromHex(h: String?): ByteArray? {
        if (h == null || h.length % 2 != 0) return null
        return try {
            ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) { null }
    }
}

/** Allows at most one system-credential prompt per [minGapMillis]. */
class PromptRateLimiter(private val minGapMillis: Long = 2_000L, private val now: () -> Long = ::monotonicMillis) {
    private var last: Long? = null
    fun tryAcquire(): Boolean {
        val t = now()
        val l = last
        if (l != null && t - l in 0 until minGapMillis) return false
        last = t
        return true
    }
}

/**
 * The device's own screen lock (PIN / pattern / password / fingerprint) as a gate method. The Android
 * implementation is AndroidDeviceCredential (KeyguardManager confirm-credential intent).
 */
interface DeviceCredentialFallback {
    fun isAvailable(): Boolean
    /** Shows the system credential prompt; true when the adult authenticated. */
    suspend fun authenticate(): Boolean
}

/** Monotonic milliseconds that keep counting while the app is in the background and cannot be set by the user. */
internal fun monotonicMillis(): Long =
    try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { System.nanoTime() / 1_000_000L }

/**
 * Everything the gate remembers between screen visits (and, via [GateStateStore], between process restarts).
 * [lockLengthMillis] 0 = not locked. [level] counts the lockouts since the last correct answer and picks the
 * next lockout length (escalating). The lock is anchored to BOTH clocks: [lockStartElapsed] (monotonic, used
 * first) and [lockStartWall] (only a fallback after a reboot, when the monotonic clock restarted).
 */
@Serializable
data class GateLockState(
    val failures: Int = 0,
    val level: Int = 0,
    val lockLengthMillis: Long = 0L,
    val lockStartWall: Long = 0L,
    val lockStartElapsed: Long = 0L
) {
    val locked: Boolean get() = lockLengthMillis > 0L
}

object GateLockout {
    /** Escalating lockout lengths: the 1st lockout lasts 30 s, the 2nd 5 min, the 3rd and every later one 30 min. */
    val LOCKOUT_STEPS_MILLIS: List<Long> = listOf(30_000L, 5 * 60_000L, 30 * 60_000L)

    fun lengthForLevel(level: Int): Long = LOCKOUT_STEPS_MILLIS[level.coerceIn(0, LOCKOUT_STEPS_MILLIS.lastIndex)]

    /**
     * Milliseconds of lockout left. While the monotonic clock still reads at or after the lock's anchor (same
     * boot) ONLY the monotonic clock counts, so moving the wall clock forward or backward changes nothing.
     * After a reboot the monotonic clock restarted (it reads below the anchor): fall back to the persisted wall
     * time; a wall clock that went BACKWARDS keeps the full lockout.
     */
    fun remainingMillis(s: GateLockState, wallNow: Long, elapsedNow: Long): Long {
        if (!s.locked) return 0L
        val passed = if (elapsedNow >= s.lockStartElapsed) {
            elapsedNow - s.lockStartElapsed
        } else {
            val w = wallNow - s.lockStartWall
            if (w < 0L) 0L else w
        }
        return (s.lockLengthMillis - passed).coerceIn(0L, s.lockLengthMillis)
    }
}

/** Where [ParentGate] keeps its [GateLockState]; synchronous so a new gate instance sees the old failures at once. */
interface GateStateStore {
    fun load(): GateLockState
    fun save(state: GateLockState)
}

/** Volatile state for tests and previews. */
class InMemoryGateStateStore(initial: GateLockState = GateLockState()) : GateStateStore {
    @Volatile var state: GateLockState = initial
    override fun load(): GateLockState = state
    override fun save(state: GateLockState) { this.state = state }
}

/**
 * The process-wide gate state: every [ParentGate] built with [instance] shares it, so leaving the gate screen and
 * opening it again no longer hands out fresh attempts. [persist] (installed by the profile bootstrap) writes every
 * change to the "profile_controls" DataStore so it also survives a process restart; [hydrate] loads that copy.
 */
class ProcessGateStateStore private constructor() : GateStateStore {
    @Volatile private var state = GateLockState()
    @Volatile var persist: ((GateLockState) -> Unit)? = null

    override fun load(): GateLockState = state

    override fun save(state: GateLockState) {
        this.state = state
        persist?.invoke(state)
    }

    fun hydrate(stored: GateLockState) { state = stored }

    companion object {
        val instance = ProcessGateStateStore()
    }
}

object GateStateCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(s: GateLockState): String = json.encodeToString(GateLockState.serializer(), s)
    /** Unreadable or blank = a clean state (the gate holds no user data worth quarantining). */
    fun decode(raw: String?): GateLockState =
        if (raw.isNullOrBlank()) GateLockState()
        else runCatching { json.decodeFromString(GateLockState.serializer(), raw) }.getOrDefault(GateLockState())
}

/**
 * Pure parent-gate logic. A new random question every time ([next]), never the same as the previous one;
 * [MAX_ATTEMPTS] wrong answers in a row lock the gate, for 30 s, then 5 min, then 30 min on each repeat
 * ([GateLockout]). Attempts and the lockout live in the injected [store] (a process-wide one in the app), so a
 * fresh gate instance continues where the last one stopped. [elapsed] is the monotonic clock the lockout is
 * measured with; [clock] (wall) is only persisted as the after-reboot fallback.
 */
class ParentGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val elapsed: () -> Long = ::monotonicMillis,
    private val store: GateStateStore = InMemoryGateStateStore(),
    initialMethod: GateMethod = GateMethod.SUMS,
    private val digitWords: List<String> = emptyList(),
    private val wordDigits: Int = 3,
    private val pinCheck: (String) -> Boolean = { false }
) {
    /** The method the screen shows now. Switching ([useMethod]) never touches the attempts or the lockout, which live in [store]. */
    var method: GateMethod = initialMethod
        private set

    var question: GateQuestion = generate()
        private set

    /** Shows another method with a fresh challenge; attempts and lockout are kept. */
    fun useMethod(m: GateMethod) {
        method = m
        question = generate()
    }

    /** Seconds of lockout left (0 when open). */
    fun lockoutSecondsLeft(): Int {
        val left = GateLockout.remainingMillis(store.load(), clock(), elapsed())
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /** Draws a fresh question (different from the current one) and returns it. */
    fun next(): GateQuestion {
        if (!hasQuestion()) return question
        var q = generate()
        var guard = 0
        while (q.text == question.text && guard++ < 50) q = generate()
        question = q
        return q
    }

    fun submit(input: String): GateResult {
        var s = store.load()
        val secs = lockoutSecondsLeft()
        if (secs > 0) return GateResult.LockedOut(secs)
        if (s.locked) { // the lockout just ran out: a fresh set of attempts, the escalation level stays
            s = s.copy(failures = 0, lockLengthMillis = 0L, lockStartWall = 0L, lockStartElapsed = 0L)
            store.save(s)
        }
        val correct = when (method) {
            GateMethod.PIN -> {
                val t = input.trim()
                if (!PinHasher.isValidPin(t)) return GateResult.NotANumber
                pinCheck(t)
            }
            GateMethod.PHONE_LOCK -> return GateResult.NotANumber // nothing is typed; see credentialSucceeded()
            GateMethod.WORD_CODE, GateMethod.SUMS -> {
                val n = input.trim().toIntOrNull() ?: return GateResult.NotANumber
                n == question.answer
            }
        }
        if (correct) {
            store.save(GateLockState()) // success clears failures AND the escalation level
            next()
            return GateResult.Correct
        }
        val failures = s.failures + 1
        next()
        if (failures >= MAX_ATTEMPTS) {
            store.save(
                s.copy(
                    failures = failures,
                    level = s.level + 1,
                    lockLengthMillis = GateLockout.lengthForLevel(s.level),
                    lockStartWall = clock(),
                    lockStartElapsed = elapsed()
                )
            )
            return GateResult.LockedOut(lockoutSecondsLeft())
        }
        store.save(s.copy(failures = failures))
        return GateResult.Wrong(MAX_ATTEMPTS - failures)
    }

    /** The system credential prompt succeeded: opens (and clears failures and the escalation level), unless locked out. */
    fun credentialSucceeded(): GateResult {
        val secs = lockoutSecondsLeft()
        if (secs > 0) return GateResult.LockedOut(secs)
        store.save(GateLockState())
        return GateResult.Correct
    }

    /** A cancelled or failed credential prompt: NOT a wrong attempt, the stored state is not touched. */
    fun credentialFailed(): GateResult = GateResult.CredentialNotConfirmed

    private fun hasQuestion(): Boolean = method == GateMethod.SUMS || method == GateMethod.WORD_CODE

    private fun generate(): GateQuestion = when {
        method == GateMethod.WORD_CODE && digitWords.size == WordCode.WORD_COUNT ->
            WordCode.generate(random, wordDigits, digitWords)
        method == GateMethod.SUMS || method == GateMethod.WORD_CODE -> generateSum()
        else -> GateQuestion("", 0) // PIN and PHONE_LOCK show no question
    }

    /**
     * Three question shapes (about 600 x 810,000 x ~400,000 combinations, answers up to 1998): a two-digit by
     * one-digit product, a sum of two three-digit numbers, and a difference of two three-digit numbers.
     */
    private fun generateSum(): GateQuestion = when (random.nextInt(3)) {
        0 -> {
            val a = random.nextInt(12, 100)
            val b = random.nextInt(3, 10)
            GateQuestion("$a × $b", a * b)
        }
        1 -> {
            val a = random.nextInt(100, 1000)
            val b = random.nextInt(100, 1000)
            GateQuestion("$a + $b", a + b)
        }
        else -> {
            val a = random.nextInt(300, 1000)
            val b = random.nextInt(100, a - 99) // keeps the answer 100 or more, never negative
            GateQuestion("$a - $b", a - b)
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        /** The first lockout length (the later ones are in [GateLockout.LOCKOUT_STEPS_MILLIS]). */
        const val LOCKOUT_MILLIS = 30_000L
    }
}
