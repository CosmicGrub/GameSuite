package com.gamesuite.core

import com.gamesuite.ui.Wcag

/*
 * The Club layer's player profile (docs/roadmap/CLUBHOUSE-PARITY.md): a display name, an original
 * critter avatar, a favourite game and which coach cards the player has already seen.
 *
 * Pure model, no Android: the avatar is DATA (a character recipe + a colour + a frame) and
 * ui/club/AvatarBadge.kt turns it into vector art. Persistence is ClubProfileStore (DataStore).
 * Avatars never carry identity: players are still identified by their transport player ids.
 */

/** How a critter's ears are drawn. */
enum class AvatarEars { NONE, ROUND, POINT, LONG, HORNS, ANTENNA, LEAF, TUFT }

/** How its eyes are drawn. */
enum class AvatarEyes { DOT, HAPPY, WIDE, SLEEPY, WINK }

/** How its mouth is drawn. */
enum class AvatarMouth { SMILE, GRIN, OPEN, CAT, TINY }

/** The accessory it wears. */
enum class AvatarProp { NONE, BOW, GLASSES, CROWN, SCARF, BEANIE, FLOWER }

/**
 * The sixteen critters. Each has a distinct silhouette-plus-accessory combination so two avatars
 * differ by shape and detail, never by colour alone. [id] is the persisted key and the suffix of
 * its `club_avatar_<id>` name string.
 */
enum class AvatarCharacter(
    val id: String,
    val ears: AvatarEars,
    val eyes: AvatarEyes,
    val mouth: AvatarMouth,
    val prop: AvatarProp
) {
    PIP("pip", AvatarEars.ROUND, AvatarEyes.DOT, AvatarMouth.SMILE, AvatarProp.NONE),
    MOCHI("mochi", AvatarEars.NONE, AvatarEyes.HAPPY, AvatarMouth.TINY, AvatarProp.BOW),
    BOLT("bolt", AvatarEars.ANTENNA, AvatarEyes.WIDE, AvatarMouth.OPEN, AvatarProp.GLASSES),
    NOVA("nova", AvatarEars.TUFT, AvatarEyes.WINK, AvatarMouth.GRIN, AvatarProp.CROWN),
    FERN("fern", AvatarEars.LEAF, AvatarEyes.SLEEPY, AvatarMouth.SMILE, AvatarProp.FLOWER),
    EMBER("ember", AvatarEars.HORNS, AvatarEyes.DOT, AvatarMouth.GRIN, AvatarProp.SCARF),
    WHISK("whisk", AvatarEars.POINT, AvatarEyes.DOT, AvatarMouth.CAT, AvatarProp.BOW),
    BUN("bun", AvatarEars.LONG, AvatarEyes.HAPPY, AvatarMouth.TINY, AvatarProp.NONE),
    DASH("dash", AvatarEars.POINT, AvatarEyes.WIDE, AvatarMouth.GRIN, AvatarProp.BEANIE),
    PEBBLE("pebble", AvatarEars.NONE, AvatarEyes.SLEEPY, AvatarMouth.TINY, AvatarProp.BEANIE),
    SPROUT("sprout", AvatarEars.LEAF, AvatarEyes.DOT, AvatarMouth.OPEN, AvatarProp.NONE),
    ZIP("zip", AvatarEars.ANTENNA, AvatarEyes.WINK, AvatarMouth.CAT, AvatarProp.SCARF),
    TOAST("toast", AvatarEars.ROUND, AvatarEyes.HAPPY, AvatarMouth.SMILE, AvatarProp.GLASSES),
    GIZMO("gizmo", AvatarEars.TUFT, AvatarEyes.WIDE, AvatarMouth.TINY, AvatarProp.GLASSES),
    PUFF("puff", AvatarEars.ROUND, AvatarEyes.SLEEPY, AvatarMouth.OPEN, AvatarProp.FLOWER),
    REX("rex", AvatarEars.HORNS, AvatarEyes.WINK, AvatarMouth.SMILE, AvatarProp.CROWN);

    companion object {
        fun fromId(id: String?): AvatarCharacter? = values().firstOrNull { it.id == id }
    }
}

/**
 * The eight Okabe-Ito colours, chosen for colour-blind readers. Every body colour keeps at least
 * 4.5:1 contrast with the face ink ([AvatarPalette.inkFor]); colour is never the only cue because the
 * character itself differs.
 */
enum class AvatarColor(val id: String, val argb: Long) {
    ORANGE("orange", 0xFFE69F00),
    SKY("sky", 0xFF56B4E9),
    GREEN("green", 0xFF009E73),
    YELLOW("yellow", 0xFFF0E442),
    BLUE("blue", 0xFF0072B2),
    VERMILION("vermilion", 0xFFBF4D00),
    PINK("pink", 0xFFCC79A7),
    GREY("grey", 0xFFB0B4BA);

    companion object {
        fun fromId(id: String?): AvatarColor? = values().firstOrNull { it.id == id }
    }
}

/** The badge frame behind the critter. A second, non-colour way to tell avatars apart. */
enum class AvatarFrame(val id: String) {
    CIRCLE("circle"), ROUNDED_SQUARE("square"), HEXAGON("hexagon"), DIAMOND("diamond");

    companion object {
        fun fromId(id: String?): AvatarFrame? = values().firstOrNull { it.id == id }
    }
}

object AvatarPalette {
    const val INK_DARK: Long = 0xFF1B1B22
    const val INK_LIGHT: Long = 0xFFFFFFFF

    /** Face ink (eyes, mouth, glasses) for a body of colour [body]: whichever of dark/white contrasts more. */
    fun inkFor(body: Long): Long {
        val dark = Wcag.contrast(INK_DARK, body)
        val light = Wcag.contrast(INK_LIGHT, body)
        return if (dark >= light) INK_DARK else INK_LIGHT
    }
}

data class Avatar(
    val character: AvatarCharacter = AvatarCharacter.PIP,
    val color: AvatarColor = AvatarColor.SKY,
    val frame: AvatarFrame = AvatarFrame.CIRCLE
) {
    /** "pip|sky|circle". */
    fun encode(): String = "${character.id}|${color.id}|${frame.id}"

    companion object {
        val DEFAULT = Avatar()

        /** Lenient: any malformed or unknown part falls back to the default part, never throws. */
        fun decode(raw: String?): Avatar {
            val parts = raw?.split('|').orEmpty()
            return Avatar(
                character = AvatarCharacter.fromId(parts.getOrNull(0)) ?: DEFAULT.character,
                color = AvatarColor.fromId(parts.getOrNull(1)) ?: DEFAULT.color,
                frame = AvatarFrame.fromId(parts.getOrNull(2)) ?: DEFAULT.frame
            )
        }

        /** A stable avatar for a seed (a name or id), so a guest with no profile still gets a face. */
        fun forSeed(seed: String): Avatar {
            val h = seed.fold(17) { acc, c -> acc * 31 + c.code }.let { if (it == Int.MIN_VALUE) 0 else Math.abs(it) }
            val chars = AvatarCharacter.values()
            val colors = AvatarColor.values()
            val frames = AvatarFrame.values()
            return Avatar(chars[h % chars.size], colors[(h / 16) % colors.size], frames[(h / 128) % frames.size])
        }
    }
}

/**
 * [displayName] is what the player typed for the Club ("" = not set: use their lobby name, see
 * [displayNameOr]). [favoriteGameId] is a Hub game id or null. [coachSeen] are the game ids whose coach
 * card has been shown, so it auto-opens only the first time.
 */
data class ClubProfile(
    val displayName: String = "",
    val avatar: Avatar = Avatar.DEFAULT,
    val favoriteGameId: String? = null,
    val coachSeen: Set<String> = emptySet()
) {
    /** The name to show: the Club name, else [fallback] (usually PlayerNameStore.lobbyName(default)). */
    fun displayNameOr(fallback: String): String = cleanPlayerName(displayName, fallback)

    fun withName(raw: String): ClubProfile = copy(displayName = cleanPlayerName(raw, ""))

    /** Keeps only ids in [known]; anything else clears the favourite. */
    fun withFavorite(gameId: String?, known: Set<String>): ClubProfile =
        copy(favoriteGameId = gameId?.takeIf { it in known })

    fun withAvatar(avatar: Avatar): ClubProfile = copy(avatar = avatar)

    fun shouldAutoCoach(gameId: String): Boolean = gameId !in coachSeen

    fun markCoachSeen(gameId: String): ClubProfile = copy(coachSeen = coachSeen + gameId)
}
