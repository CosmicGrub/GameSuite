package com.gamesuite.rewards

import com.gamesuite.core.DayStreak
import com.gamesuite.core.GameTeaches
import com.gamesuite.core.LocalOutcome
import com.gamesuite.core.currentOn
import com.gamesuite.core.recordPlay
import kotlinx.serialization.Serializable

/** One remembered play (a day and a game), kept for a short window for "try something new" and the weekly report. */
@Serializable
data class PlayEntry(val day: Long, val gameId: String, val won: Boolean = false)

/**
 * One child's sticker book. Every field has a default so a blob from an older or newer build decodes tolerantly.
 *  - [earned]: sticker id -> local epoch day it was earned on (at most [StickerCatalog.size] entries);
 *  - [pending]: earned but not yet collected by the child's tap (a subset of [earned]); the book only shows collected ones;
 *  - [granted]: reason keys already rewarded (the idempotency ledger), bounded by [RewardEngine.MAX_GRANTED];
 *  - [plays]: the last [RewardEngine.PLAY_WINDOW_DAYS] days of plays, at most [RewardEngine.MAX_PLAYS];
 *  - [recentEvents]: the last few event ids, so the same finished match can never count twice.
 */
@Serializable
data class StickerBook(
    val earned: Map<String, Long> = emptyMap(),
    val pending: List<String> = emptyList(),
    val granted: List<String> = emptyList(),
    val totalGames: Int = 0,
    val streak: DayStreak = DayStreak(),
    val plays: List<PlayEntry> = emptyList(),
    val recentEvents: List<String> = emptyList()
) {
    val collected: Set<String> get() = earned.keys - pending.toSet()
    val collectedCount: Int get() = collected.size
    val isComplete: Boolean get() = earned.size >= StickerCatalog.size
}

/** A finished match, as the rewards engine sees it. [eventId] (optional) makes a replayed delivery a no-op. */
data class RewardEvent(
    val gameId: String,
    val outcome: LocalOutcome?,
    val atEpochMillis: Long,
    val eventId: String? = null
)

/** Why a sticker was given. Stable [id]s: each has a `rw_reason_<id>` string. */
enum class RewardReason(val id: String) {
    FIRST_GAME_OF_DAY("first_of_day"),
    FINISHED_GAME("finished"),
    WIN("win"),
    FIRST_WIN("first_win"),
    GAMES_3("games_3"),
    GAMES_5("games_5"),
    GAMES_10("games_10"),
    GAMES_25("games_25"),
    STREAK_3("streak_3"),
    STREAK_7("streak_7"),
    FIRST_TIME_GAME("first_time"),
    TRY_SOMETHING_NEW("try_new")
}

/** What earns a sticker, as data. */
sealed interface Trigger {
    /** The first finished game of each local day. */
    object FirstGameOfDay : Trigger
    /** The very first game a child ever finishes. */
    object FirstFinish : Trigger
    /** A win, or a co-op success (the local outcome is WIN), once per day. */
    object WinOfDay : Trigger
    /** The first win ever. */
    object FirstWin : Trigger
    /** The Nth finished game in total. */
    data class GamesTotal(val n: Int) : Trigger
    /** A play streak of N days in a row. */
    data class DayStreakOf(val days: Int) : Trigger
    /** The first time a child plays a particular kid game. */
    object FirstTimeGame : Trigger
    /** A game not played in the last 7 days, once per day. */
    object NewThisWeek : Trigger
}

data class RewardRule(val reason: RewardReason, val trigger: Trigger)

/** What one recorded match produced. [awarded] pairs the reason with the sticker given for it. */
data class RewardResult(
    val book: StickerBook,
    val awarded: List<Pair<RewardReason, Sticker>>,
    /** True when the event was ignored (adult / arena / duplicate): the book is untouched. */
    val ignored: Boolean = false
)

/** The "one more game" card: what is the nearest thing that earns the next sticker. */
sealed interface NextGoal {
    object BookComplete : NextGoal
    /** No game finished yet today: one game earns today's sticker. */
    object PlayToday : NextGoal
    /** [remaining] more games reach the [target]-game milestone. */
    data class MoreGames(val remaining: Int, val target: Int) : NextGoal
    /** Play on [days] more day(s) in a row. */
    data class StreakDays(val days: Int) : NextGoal
}

object RewardEngine {
    const val MAX_PER_MATCH = 3
    const val MAX_GRANTED = 256
    const val MAX_PLAYS = 300
    const val PLAY_WINDOW_DAYS = 14
    const val MAX_RECENT_EVENTS = 16
    const val NEW_WEEK_DAYS = 7

    /** The rules table, evaluated top to bottom for every finished match. */
    val rules: List<RewardRule> = listOf(
        RewardRule(RewardReason.FIRST_GAME_OF_DAY, Trigger.FirstGameOfDay),
        RewardRule(RewardReason.FINISHED_GAME, Trigger.FirstFinish),
        RewardRule(RewardReason.FIRST_WIN, Trigger.FirstWin),
        RewardRule(RewardReason.WIN, Trigger.WinOfDay),
        RewardRule(RewardReason.GAMES_3, Trigger.GamesTotal(3)),
        RewardRule(RewardReason.GAMES_5, Trigger.GamesTotal(5)),
        RewardRule(RewardReason.GAMES_10, Trigger.GamesTotal(10)),
        RewardRule(RewardReason.GAMES_25, Trigger.GamesTotal(25)),
        RewardRule(RewardReason.STREAK_3, Trigger.DayStreakOf(3)),
        RewardRule(RewardReason.STREAK_7, Trigger.DayStreakOf(7)),
        RewardRule(RewardReason.FIRST_TIME_GAME, Trigger.FirstTimeGame),
        RewardRule(RewardReason.TRY_SOMETHING_NEW, Trigger.NewThisWeek)
    )

    /** Game-count milestones, ascending, from the rules table. */
    val milestones: List<Int> = rules.mapNotNull { (it.trigger as? Trigger.GamesTotal)?.n }.sorted()

    private fun dayKey(prefix: String, day: Long) = "d:$prefix:$day"

    /**
     * Folds one finished match into [book]. Pure: [today] is the local epoch day (see `epochDayOf`, so the caller's
     * injected clock and zone decide where midnight is).
     *  - [isKidProfile] false or [arena] true: nothing is recorded (adults and Computer Arena matches never earn);
     *  - an [RewardEvent.eventId] already seen: ignored;
     *  - each reason is rewarded at most once per its key, so replaying a match or resuming never double-awards.
     * [isKidGame] decides which games get a "first time" sticker (default: the "what this teaches" table).
     */
    fun record(
        book: StickerBook,
        event: RewardEvent,
        today: Long,
        isKidProfile: Boolean,
        arena: Boolean = false,
        isKidGame: (String) -> Boolean = GameTeaches::covers
    ): RewardResult {
        if (!isKidProfile || arena) return RewardResult(book, emptyList(), ignored = true)
        if (event.eventId != null && event.eventId in book.recentEvents) return RewardResult(book, emptyList(), ignored = true)

        val won = event.outcome == LocalOutcome.WIN
        val weekStart = today - (NEW_WEEK_DAYS - 1)
        val playedThisWeek = book.plays.any { it.gameId == event.gameId && it.day >= weekStart }
        val hadFirstTime = "first:${event.gameId}" in book.granted

        var b = book.copy(
            totalGames = (book.totalGames + 1).coerceAtMost(1_000_000),
            streak = book.streak.recordPlay(today),
            plays = book.plays + PlayEntry(today, event.gameId, won),
            recentEvents = if (event.eventId == null) book.recentEvents
            else (book.recentEvents + event.eventId).takeLast(MAX_RECENT_EVENTS)
        )
        val awarded = ArrayList<Pair<RewardReason, Sticker>>()
        var firstTimeGivenNow = false
        for (rule in rules) {
            val key: String? = when (val t = rule.trigger) {
                Trigger.FirstGameOfDay -> dayKey("first", today)
                Trigger.FirstFinish -> "finish:first"
                Trigger.WinOfDay -> if (won) dayKey("win", today) else null
                Trigger.FirstWin -> if (won) "win:first" else null
                is Trigger.GamesTotal -> if (b.totalGames >= t.n) "games:${t.n}" else null
                is Trigger.DayStreakOf -> if (b.streak.current >= t.days) "streak:${t.days}" else null
                Trigger.FirstTimeGame -> if (isKidGame(event.gameId)) "first:${event.gameId}" else null
                Trigger.NewThisWeek ->
                    if (hadFirstTime && !firstTimeGivenNow && !playedThisWeek) dayKey("new", today) else null
            }
            if (key == null || key in b.granted) continue
            // A full book has nothing left to give: mark the reason and move on. At the per-match cap the reason is NOT
            // marked, so it pays out on the next finished match instead of being lost.
            if (b.isComplete) { b = b.copy(granted = b.granted + key); continue }
            if (awarded.size >= MAX_PER_MATCH) continue
            val next = StickerCatalog.awardOrder.firstOrNull { it.id !in b.earned } ?: continue
            if (rule.trigger == Trigger.FirstTimeGame) firstTimeGivenNow = true
            b = b.copy(granted = b.granted + key)
            b = b.copy(earned = b.earned + (next.id to today), pending = b.pending + next.id)
            awarded += rule.reason to next
        }
        return RewardResult(prune(b, today), awarded)
    }

    /** Drops what is out of the window and enforces the storage bounds (never touches [StickerBook.earned]). */
    fun prune(book: StickerBook, today: Long): StickerBook {
        val keepFrom = today - PLAY_WINDOW_DAYS
        val plays = book.plays.filter { it.day > keepFrom }.takeLast(MAX_PLAYS)
        var granted = book.granted.filter { k ->
            val day = dayOf(k)
            day == null || day > keepFrom
        }
        if (granted.size > MAX_GRANTED) granted = granted.takeLast(MAX_GRANTED)
        val pending = book.pending.filter { it in book.earned }.distinct()
        return book.copy(plays = plays, granted = granted, pending = pending)
    }

    private fun dayOf(key: String): Long? =
        if (key.startsWith("d:")) key.substringAfterLast(':').toLongOrNull() else null

    /** The child tapped the reward: [stickerId] moves from pending into the visible book. */
    fun collect(book: StickerBook, stickerId: String): StickerBook =
        if (stickerId in book.pending) book.copy(pending = book.pending - stickerId) else book

    fun collectAll(book: StickerBook): StickerBook = if (book.pending.isEmpty()) book else book.copy(pending = emptyList())

    /** Which sticker the reward moment shows next (oldest pending), or null. */
    fun nextPending(book: StickerBook): Sticker? = book.pending.firstNotNullOfOrNull { StickerCatalog.byId(it) }

    fun nextGoal(book: StickerBook, today: Long): NextGoal {
        if (book.isComplete) return NextGoal.BookComplete
        if (dayKey("first", today) !in book.granted) return NextGoal.PlayToday
        milestones.firstOrNull { it > book.totalGames }?.let { return NextGoal.MoreGames(it - book.totalGames, it) }
        val cur = book.streak.currentOn(today)
        listOf(3, 7).firstOrNull { it > cur }?.let { return NextGoal.StreakDays(it - cur) }
        return NextGoal.PlayToday
    }
}
