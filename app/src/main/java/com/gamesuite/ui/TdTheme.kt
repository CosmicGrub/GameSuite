package com.gamesuite.ui

import androidx.compose.ui.graphics.Color
import com.gamesuite.R
import com.gamesuite.games.towerdefence.TdEnemyKind
import com.gamesuite.games.towerdefence.TdRole

/**
 * The Tower Defence theme system. A theme is a SKIN only: names, palette, ornament style, ambient
 * layer and base art. Stats belong to the role and the archetype ([com.gamesuite.games.towerdefence.TdRules]),
 * never to a theme, so one balance sheet serves every skin (docs/design/tower-defence/THEMES.md).
 * Five ship: Castle Siege (default), Space Station, Toy Room, Roman Frontier and Cherry Ridge. The other three
 * documented themes (Sunstone Oasis, Frostfjord, Coral Reef) are not built.
 *
 * All art is original vector drawing (see TdArt.kt); names are original working titles.
 */
enum class TdThemeId(val key: String) {
    CASTLE("castle"), SPACE("space"), TOY("toy"), ROMAN("roman"), CHERRY("cherry");

    companion object {
        fun fromKey(key: String?): TdThemeId = entries.firstOrNull { it.key == key } ?: CASTLE
    }
}

/** How the ambient layer of a theme behaves (see TdAmbient.kt). */
enum class TdAmbientKind { MEADOW, STARFIELD, NURSERY, PETALS }

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

    /**
     * Roman Frontier (THEMES.md 3.3): crimson, gold, marble and terracotta on a sand-and-olive frontier.
     * Original art only; "Roman" is a setting label, no legion numbers or lettering appear.
     */
    val ROMAN = TdSkin(
        id = TdThemeId.ROMAN, nameRes = R.string.td_theme_roman, blurbRes = R.string.td_theme_roman_blurb,
        towerNames = mapOf(
            TdRole.BOLT to R.string.td_tower_roman_bolt, TdRole.BLAST to R.string.td_tower_roman_blast,
            TdRole.CONTROL to R.string.td_tower_roman_control, TdRole.SUPPORT to R.string.td_tower_roman_support
        ),
        enemyNames = mapOf(
            TdEnemyKind.RUNNER to R.string.td_enemy_roman_runner, TdEnemyKind.BRUTE to R.string.td_enemy_roman_brute,
            TdEnemyKind.FLYER to R.string.td_enemy_roman_flyer, TdEnemyKind.BOSS to R.string.td_enemy_roman_boss
        ),
        baseNameRes = R.string.td_base_roman, ambient = TdAmbientKind.MEADOW, kidFirst = false,
        skyTop = Color(0xFF8FC1E8), skyBottom = Color(0xFFF6E3C0), far = Color(0xFFC9B58F), mid = Color(0xFF9AA66A),
        ground = Color(0xFFD9B77A), groundAlt = Color(0xFFD0AD6F), road = Color(0xFFEBD8AE), roadEdge = Color(0xFFB08A50),
        rough = Color(0xFF7A8B4A), rock = Color(0xFFB9B2A2), water = Color(0xFF4C8FB8), bridge = Color(0xFFC2553A),
        accent = Color(0xFFE3B23C), accentOn = Color(0xFF2A1A05), panel = Color(0xFF5A1A22), panelText = Color(0xFFF6EBD2),
        towerBase = Color(0xFFE8DFCB), projectile = Color(0xFFFFE6A0), castleWall = Color(0xFFE8DFCB),
        castleRoof = Color(0xFFC2553A), flag = Color(0xFFA8242F),
        roleColor = mapOf(
            TdRole.BOLT to Color(0xFF7A5A32), TdRole.BLAST to Color(0xFFA8242F),
            TdRole.CONTROL to Color(0xFF4A7A8A), TdRole.SUPPORT to Color(0xFFE3B23C)
        ),
        enemyColor = mapOf(
            TdEnemyKind.RUNNER to Color(0xFF6B5A8C), TdEnemyKind.BRUTE to Color(0xFF4F4A44),
            TdEnemyKind.FLYER to Color(0xFF8C5A2B), TdEnemyKind.BOSS to Color(0xFF6E6A76)
        )
    )

    /**
     * Cherry Ridge (THEMES.md 3.4): tile blue-grey, lacquer red, plaster, blossom pink and moss at dusk.
     * Folklore creatures only; the crane mark is an original simplified shape.
     */
    val CHERRY = TdSkin(
        id = TdThemeId.CHERRY, nameRes = R.string.td_theme_cherry, blurbRes = R.string.td_theme_cherry_blurb,
        towerNames = mapOf(
            TdRole.BOLT to R.string.td_tower_cherry_bolt, TdRole.BLAST to R.string.td_tower_cherry_blast,
            TdRole.CONTROL to R.string.td_tower_cherry_control, TdRole.SUPPORT to R.string.td_tower_cherry_support
        ),
        enemyNames = mapOf(
            TdEnemyKind.RUNNER to R.string.td_enemy_cherry_runner, TdEnemyKind.BRUTE to R.string.td_enemy_cherry_brute,
            TdEnemyKind.FLYER to R.string.td_enemy_cherry_flyer, TdEnemyKind.BOSS to R.string.td_enemy_cherry_boss
        ),
        baseNameRes = R.string.td_base_cherry, ambient = TdAmbientKind.PETALS, kidFirst = false,
        skyTop = Color(0xFF3B3560), skyBottom = Color(0xFFE9A9A0), far = Color(0xFF6D5E8A), mid = Color(0xFF4F6A5A),
        ground = Color(0xFF6FA06B), groundAlt = Color(0xFF679863), road = Color(0xFFE5D3C0), roadEdge = Color(0xFF8C7A70),
        rough = Color(0xFF4C7A52), rock = Color(0xFF5E6A80), water = Color(0xFF4A78A8), bridge = Color(0xFFC8372D),
        accent = Color(0xFFF4A6B8), accentOn = Color(0xFF3A0F1A), panel = Color(0xFF2F3B52), panelText = Color(0xFFF2EFE6),
        towerBase = Color(0xFFF2EFE6), projectile = Color(0xFFFFE3EA), castleWall = Color(0xFFF2EFE6),
        castleRoof = Color(0xFF2F3B52), flag = Color(0xFFC8372D),
        roleColor = mapOf(
            TdRole.BOLT to Color(0xFF2F3B52), TdRole.BLAST to Color(0xFFC8372D),
            TdRole.CONTROL to Color(0xFFD97A94), TdRole.SUPPORT to Color(0xFFE8B84A)
        ),
        enemyColor = mapOf(
            TdEnemyKind.RUNNER to Color(0xFF8A9BB0), TdEnemyKind.BRUTE to Color(0xFF8C2F2F),
            TdEnemyKind.FLYER to Color(0xFFE6D8B8), TdEnemyKind.BOSS to Color(0xFF3B2A52)
        )
    )

    val ALL = listOf(CASTLE, SPACE, TOY, ROMAN, CHERRY)

    fun of(id: TdThemeId): TdSkin = when (id) {
        TdThemeId.CASTLE -> CASTLE
        TdThemeId.SPACE -> SPACE
        TdThemeId.TOY -> TOY
        TdThemeId.ROMAN -> ROMAN
        TdThemeId.CHERRY -> CHERRY
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
