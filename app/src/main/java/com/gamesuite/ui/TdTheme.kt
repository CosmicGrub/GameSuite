package com.gamesuite.ui

import androidx.compose.ui.graphics.Color
import com.gamesuite.R
import com.gamesuite.games.towerdefence.TdEnemyKind
import com.gamesuite.games.towerdefence.TdRole

/**
 * The Tower Defence theme system. A theme is a SKIN only: names, palette, ornament style, ambient
 * layer and base art. Stats belong to the role and the archetype ([com.gamesuite.games.towerdefence.TdRules]),
 * never to a theme, so one balance sheet serves every skin (docs/design/tower-defence/THEMES.md).
 * First release ships three: Castle Siege (default), Space Station and Toy Room. The other five
 * documented themes are intentionally not built.
 *
 * All art is original vector drawing (see TdArt.kt); names are original working titles.
 */
enum class TdThemeId(val key: String) {
    CASTLE("castle"), SPACE("space"), TOY("toy");

    companion object {
        fun fromKey(key: String?): TdThemeId = entries.firstOrNull { it.key == key } ?: CASTLE
    }
}

/** How the ambient layer of a theme behaves (see TdAmbient.kt). */
enum class TdAmbientKind { MEADOW, STARFIELD, NURSERY }

class TdSkin(
    val id: TdThemeId,
    val nameRes: Int,
    val blurbRes: Int,
    val towerNames: Map<TdRole, Int>,
    val enemyNames: Map<TdEnemyKind, Int>,
    val baseNameRes: Int,
    val ambient: TdAmbientKind,
    /** Kid-first themes: softer shapes, no red damage vignette, friendlier copy. */
    val kidFirst: Boolean,
    // Palette
    val skyTop: Color,
    val skyBottom: Color,
    val far: Color,
    val mid: Color,
    val ground: Color,
    val groundAlt: Color,
    val road: Color,
    val roadEdge: Color,
    val rough: Color,
    val rock: Color,
    val water: Color,
    val bridge: Color,
    val accent: Color,
    val accentOn: Color,
    val panel: Color,
    val panelText: Color,
    val towerBase: Color,
    val projectile: Color,
    val castleWall: Color,
    val castleRoof: Color,
    val flag: Color,
    val roleColor: Map<TdRole, Color>,
    val enemyColor: Map<TdEnemyKind, Color>
)

object TdSkins {
    val CASTLE = TdSkin(
        id = TdThemeId.CASTLE, nameRes = R.string.td_theme_castle, blurbRes = R.string.td_theme_castle_blurb,
        towerNames = mapOf(
            TdRole.BOLT to R.string.td_tower_castle_bolt, TdRole.BLAST to R.string.td_tower_castle_blast,
            TdRole.CONTROL to R.string.td_tower_castle_control, TdRole.SUPPORT to R.string.td_tower_castle_support
        ),
        enemyNames = mapOf(
            TdEnemyKind.RUNNER to R.string.td_enemy_castle_runner, TdEnemyKind.BRUTE to R.string.td_enemy_castle_brute,
            TdEnemyKind.FLYER to R.string.td_enemy_castle_flyer, TdEnemyKind.BOSS to R.string.td_enemy_castle_boss
        ),
        baseNameRes = R.string.td_base_castle, ambient = TdAmbientKind.MEADOW, kidFirst = false,
        skyTop = Color(0xFF87B8F0), skyBottom = Color(0xFFCFE4F7), far = Color(0xFF9FB3D1), mid = Color(0xFF7FA37A),
        ground = Color(0xFF5FA85A), groundAlt = Color(0xFF58A053), road = Color(0xFFC9AE7C), roadEdge = Color(0xFF9C8458),
        rough = Color(0xFF3F7F43), rock = Color(0xFF8C93A6), water = Color(0xFF4F8FD0), bridge = Color(0xFF8A6A3E),
        accent = Color(0xFFF4C24A), accentOn = Color(0xFF2A1E05), panel = Color(0xFF22304A), panelText = Color(0xFFF5F0E6),
        towerBase = Color(0xFFA3ABBE), projectile = Color(0xFFFFF1B8), castleWall = Color(0xFFA3ABBE),
        castleRoof = Color(0xFF3C6FD1), flag = Color(0xFFF4C24A),
        roleColor = mapOf(
            TdRole.BOLT to Color(0xFF3C6FD1), TdRole.BLAST to Color(0xFFB8413C),
            TdRole.CONTROL to Color(0xFF8E5BFF), TdRole.SUPPORT to Color(0xFFF4C24A)
        ),
        enemyColor = mapOf(
            TdEnemyKind.RUNNER to Color(0xFF6E9E3C), TdEnemyKind.BRUTE to Color(0xFF6B6F80),
            TdEnemyKind.FLYER to Color(0xFFE2603A), TdEnemyKind.BOSS to Color(0xFF3A3340)
        )
    )

    val SPACE = TdSkin(
        id = TdThemeId.SPACE, nameRes = R.string.td_theme_space, blurbRes = R.string.td_theme_space_blurb,
        towerNames = mapOf(
            TdRole.BOLT to R.string.td_tower_space_bolt, TdRole.BLAST to R.string.td_tower_space_blast,
            TdRole.CONTROL to R.string.td_tower_space_control, TdRole.SUPPORT to R.string.td_tower_space_support
        ),
        enemyNames = mapOf(
            TdEnemyKind.RUNNER to R.string.td_enemy_space_runner, TdEnemyKind.BRUTE to R.string.td_enemy_space_brute,
            TdEnemyKind.FLYER to R.string.td_enemy_space_flyer, TdEnemyKind.BOSS to R.string.td_enemy_space_boss
        ),
        baseNameRes = R.string.td_base_space, ambient = TdAmbientKind.STARFIELD, kidFirst = true,
        skyTop = Color(0xFF0E1330), skyBottom = Color(0xFF1B2250), far = Color(0xFF2A2F66), mid = Color(0xFF39407E),
        ground = Color(0xFF232A55), groundAlt = Color(0xFF282F5C), road = Color(0xFF4A5486), roadEdge = Color(0xFF7C8AA8),
        rough = Color(0xFF1B2147), rock = Color(0xFF5A6188), water = Color(0xFF3A1F5E), bridge = Color(0xFF7C8AA8),
        accent = Color(0xFF4FE3FF), accentOn = Color(0xFF04202A), panel = Color(0xFF111738), panelText = Color(0xFFE8EEFF),
        towerBase = Color(0xFFD5DCEB), projectile = Color(0xFF9DF3FF), castleWall = Color(0xFFD5DCEB),
        castleRoof = Color(0xFF4FE3FF), flag = Color(0xFFFF4FD8),
        roleColor = mapOf(
            TdRole.BOLT to Color(0xFF4FE3FF), TdRole.BLAST to Color(0xFFFFB547),
            TdRole.CONTROL to Color(0xFF8AB4FF), TdRole.SUPPORT to Color(0xFF7CF0A8)
        ),
        enemyColor = mapOf(
            TdEnemyKind.RUNNER to Color(0xFFFF4FD8), TdEnemyKind.BRUTE to Color(0xFFB07A5A),
            TdEnemyKind.FLYER to Color(0xFFFFD84F), TdEnemyKind.BOSS to Color(0xFFFF6B6B)
        )
    )

    val TOY = TdSkin(
        id = TdThemeId.TOY, nameRes = R.string.td_theme_toy, blurbRes = R.string.td_theme_toy_blurb,
        towerNames = mapOf(
            TdRole.BOLT to R.string.td_tower_toy_bolt, TdRole.BLAST to R.string.td_tower_toy_blast,
            TdRole.CONTROL to R.string.td_tower_toy_control, TdRole.SUPPORT to R.string.td_tower_toy_support
        ),
        enemyNames = mapOf(
            TdEnemyKind.RUNNER to R.string.td_enemy_toy_runner, TdEnemyKind.BRUTE to R.string.td_enemy_toy_brute,
            TdEnemyKind.FLYER to R.string.td_enemy_toy_flyer, TdEnemyKind.BOSS to R.string.td_enemy_toy_boss
        ),
        baseNameRes = R.string.td_base_toy, ambient = TdAmbientKind.NURSERY, kidFirst = true,
        skyTop = Color(0xFFFFE9B8), skyBottom = Color(0xFFFFF4DA), far = Color(0xFFF2D6A0), mid = Color(0xFFE9C58B),
        ground = Color(0xFFF1D9A8), groundAlt = Color(0xFFEBCF98), road = Color(0xFF8E5BFF), roadEdge = Color(0xFF6A3FD0),
        rough = Color(0xFFDDB878), rock = Color(0xFFFF5A5F), water = Color(0xFF3A86FF), bridge = Color(0xFFE9C58B),
        accent = Color(0xFFFF5A5F), accentOn = Color(0xFFFFFFFF), panel = Color(0xFF4A3A66), panelText = Color(0xFFFFF8E8),
        towerBase = Color(0xFFFFFFFF), projectile = Color(0xFFFFD23F), castleWall = Color(0xFFE9C58B),
        castleRoof = Color(0xFFFF5A5F), flag = Color(0xFF3DDC97),
        roleColor = mapOf(
            TdRole.BOLT to Color(0xFF3A86FF), TdRole.BLAST to Color(0xFFFF5A5F),
            TdRole.CONTROL to Color(0xFF3DDC97), TdRole.SUPPORT to Color(0xFFFFD23F)
        ),
        enemyColor = mapOf(
            TdEnemyKind.RUNNER to Color(0xFF8E5BFF), TdEnemyKind.BRUTE to Color(0xFFD9822B),
            TdEnemyKind.FLYER to Color(0xFF2BB5B0), TdEnemyKind.BOSS to Color(0xFFC2306B)
        )
    )

    val ALL = listOf(CASTLE, SPACE, TOY)

    fun of(id: TdThemeId): TdSkin = when (id) {
        TdThemeId.CASTLE -> CASTLE
        TdThemeId.SPACE -> SPACE
        TdThemeId.TOY -> TOY
    }

    /** Colour-blind safe accents (blue, vermillion, yellow, bluish green) used for roles and enemies; shapes stay. */
    val CB_ROLE = mapOf(
        TdRole.BOLT to Color(0xFF0072B2), TdRole.BLAST to Color(0xFFD55E00),
        TdRole.CONTROL to Color(0xFFCC79A7), TdRole.SUPPORT to Color(0xFFF0E442)
    )
    val CB_ENEMY = mapOf(
        TdEnemyKind.RUNNER to Color(0xFF009E73), TdEnemyKind.BRUTE to Color(0xFF999999),
        TdEnemyKind.FLYER to Color(0xFFE69F00), TdEnemyKind.BOSS to Color(0xFF000000)
    )
}
