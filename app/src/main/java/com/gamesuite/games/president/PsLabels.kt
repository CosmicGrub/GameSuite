package com.gamesuite.games.president

import android.content.Context
import com.gamesuite.R
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import com.gamesuite.ui.isYouName

/**
 * Words for cards, plays and seats in the player's language (res/values{,-es}/president_sevens_strings.xml).
 * Sentences that put a seat's name in front of a verb come in two forms, one for another player and one for
 * "You" (the name a lone person's own seat carries), so neither language ever says "You is" or "You jugó".
 */
class PsLabels(private val ctx: Context) {
    private val ranks: Array<String> = ctx.resources.getStringArray(R.array.ps_rank_names)
    private val rankPlurals: Array<String> = ctx.resources.getStringArray(R.array.ps_rank_plurals)
    private val suits: Array<String> = ctx.resources.getStringArray(R.array.ps_suit_names)

    fun rankName(rank: Rank): String = ranks[rank.ordinal]
    fun suitName(suit: Suit): String = suits[suit.ordinal]

    /** "King of Spades" */
    fun cardDesc(id: Int): String {
        if (id < 0) return ctx.getString(R.string.ps_hidden_card)
        val c = PsDeck.card(id)
        return ctx.getString(R.string.ps_card_desc, rankName(c.rank), suitName(c.suit))
    }

    /** "one card: King of Spades", "a pair: Kings", "three of a kind: Queens", "four of a kind: Aces" */
    fun groupText(ids: List<Int>): String {
        if (ids.isEmpty()) return ""
        val c = PsDeck.card(ids[0])
        return when (ids.size) {
            1 -> ctx.getString(R.string.ps_group_1, cardDesc(ids[0]))
            2 -> ctx.getString(R.string.ps_group_2, rankPlurals[c.rank.ordinal])
            3 -> ctx.getString(R.string.ps_group_3, rankPlurals[c.rank.ordinal])
            else -> ctx.getString(R.string.ps_group_4, rankPlurals[c.rank.ordinal])
        }
    }

    /** A card list for display ("K♠, A♥"). */
    fun shortList(ids: List<Int>): String = ids.joinToString(", ") { if (it < 0) "?" else PsDeck.card(it).label }

    /** A seat's name as shown: the local "You" label in the player's language. */
    fun displayName(raw: String): String = if (ctx.isYouName(raw)) ctx.getString(R.string.hub_you) else raw

    /** [other] with the name first, or the second-person [you] string when the seat is "You" (extras follow in both). */
    fun aware(name: String, other: Int, you: Int, vararg extra: Any): String =
        if (ctx.isYouName(name)) ctx.getString(you, *extra) else ctx.getString(other, name, *extra)

    fun roleName(role: PresidentRole): String = ctx.getString(
        when (role) {
            PresidentRole.PRESIDENT -> R.string.ps_role_president
            PresidentRole.VICE_PRESIDENT -> R.string.ps_role_vice
            PresidentRole.NEUTRAL -> R.string.ps_role_neutral
            PresidentRole.VICE_SCUM -> R.string.ps_role_vice_scum
            PresidentRole.SCUM -> R.string.ps_role_scum
        }
    )

    fun roleOf(name: String): String? = PresidentRole.values().firstOrNull { it.name == name }?.let(::roleName)

    fun cardsLeft(n: Int): String = ctx.resources.getQuantityString(R.plurals.ps_cards_left, n, n)
}
