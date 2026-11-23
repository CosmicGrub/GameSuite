package com.gamesuite.games.towerdefence

import com.gamesuite.core.DailySeed
import kotlin.math.roundToInt

/**
 * Game modes of the open "Castle Siege" maps (docs/design/tower-defence/SCOPE.md section 3, TDModes board):
 *  - [CAMPAIGN]: the 10-wave siege on a map and tier the player picks (the shipped mode);
 *  - [ENDLESS]: the same rules without a last wave; the run ends only when the castle falls and the
 *    score is the wave reached;
 *  - [DAILY]: one shared 10-wave siege per UTC day. The seed ([DailySeed]) only picks the map, the
 *    theme and one modifier; difficulty is fixed to Medium so everyone plays the same day.
 * Classic fixed-lane maps only exist in Campaign: another mode switches to the first open map.
 */
enum class TdMode { CAMPAIGN, ENDLESS, DAILY }

/** The one twist of a Daily siege, applied on top of the Medium numbers. */
enum class TdModifier {
    /** 30 percent more enemies. */
    SWARM,
    /** 30 percent more hit points. */
    TOUGH,
    /** 20 percent faster enemies. */
    SWIFT,
    /** Starts with 70 gold instead of 100. */
    LEAN;

    fun apply(c: TdTierConfig): TdTierConfig = when (this) {
        SWARM -> c.copy(countMultiplier = c.countMultiplier * 1.3f)
        TOUGH -> c.copy(hpMultiplier = c.hpMultiplier * 1.3f)
        SWIFT -> c.copy(speedMultiplier = c.speedMultiplier * 1.2f)
        LEAN -> c.copy(startingGold = (c.startingGold * 0.7f).roundToInt())
    }
}

/** What one UTC day's Daily siege is: pure function of the day number. [themeIndex] indexes the skin list (the screen maps it). */
data class TdDailyPlan(val epochDay: Long, val mapId: String, val themeIndex: Int, val modifier: TdModifier)

object TdDaily {
    const val DAILY_ID = "tower-defence"

    /** The plan of [epochDay]; [themeCount] is how many skins the screen has. Same inputs give the same plan on every device. */
    fun plan(epochDay: Long, themeCount: Int): TdDailyPlan {
        val seed = DailySeed.forDay(DAILY_ID, 0, epochDay)
        val maps = TdMaps.ALL
        val mapIdx = Math.floorMod(seed ushr 3, maps.size.toLong()).toInt()
        val themeIdx = Math.floorMod(seed ushr 21, themeCount.coerceAtLeast(1).toLong()).toInt()
        val mod = TdModifier.entries[Math.floorMod(seed ushr 37, TdModifier.entries.size.toLong()).toInt()]
        return TdDailyPlan(epochDay, maps[mapIdx].id, themeIdx, mod)
    }

    fun today(themeCount: Int): TdDailyPlan = plan(DailySeed.todayEpochDay(), themeCount)

    /** The local best-score key of one day's siege (map-id shaped, so [TdStatsKeys] accepts it). */
    fun statsId(epochDay: Long): String = "daily-$epochDay"

    /** Daily score: waves reached first, castle health left as the tie-break (a win with a healthy castle scores most). */
    fun score(waveReached: Int, castleLeft: Int): Int = waveReached * 100 + castleLeft.coerceIn(0, 99)
}
