package com.gamesuite.games.findit

import org.junit.Ignore
import org.junit.Test

/**
 * Test-plan stubs (docs/design/kids-games/find-it.md, SCALE section). Every test is @Ignore until its milestone lands, so the
 * gate stays green. Un-ignore each one together with the code it covers.
 */
class FindItDeckPropertyTest {
    @Ignore("FI-D1: exhaustive over all pairs of the 13, 31 and 57 card decks")
    @Test
    fun fi_d1_every_pair_of_cards_shares_exactly_one_symbol_for_orders_3_5_7() {
        // TODO(findit): exhaustive over all pairs of the 13, 31 and 57 card decks
    }

    @Ignore("FI-D2: 4, 6 and 8 distinct symbol indexes per card, all in range")
    @Test
    fun fi_d2_every_card_has_order_plus_one_distinct_symbols() {
        // TODO(findit): 4, 6 and 8 distinct symbol indexes per card, all in range
    }

    @Ignore("FI-D3: dual property: each symbol on 4, 6 or 8 cards")
    @Test
    fun fi_d3_every_symbol_appears_on_exactly_order_plus_one_cards() {
        // TODO(findit): dual property: each symbol on 4, 6 or 8 cards
    }

    @Ignore("FI-D4: 13, 31, 57")
    @Test
    fun fi_d4_card_count_and_symbol_count_equal_order_squared_plus_order_plus_one() {
        // TODO(findit): 13, 31, 57
    }

    @Ignore("FI-D5: orders 0, 1, 2, 4, 6, 8, 9 and negatives are rejected without throwing")
    @Test
    fun fi_d5_unsupported_or_non_prime_order_returns_empty_deck() {
        // TODO(findit): orders 0, 1, 2, 4, 6, 8, 9 and negatives are rejected without throwing
    }

    @Ignore("FI-D6: random subsets of 5 to N cards, 200 seeds")
    @Test
    fun fi_d6_any_subset_of_the_deck_keeps_the_one_shared_symbol_property() {
        // TODO(findit): random subsets of 5 to N cards, 200 seeds
    }

    @Ignore("FI-D7: SplitMix64 Fisher-Yates determinism, golden first 8 card ids for seed 42")
    @Test
    fun fi_d7_same_seed_gives_identical_shuffle_and_different_seed_differs() {
        // TODO(findit): SplitMix64 Fisher-Yates determinism, golden first 8 card ids for seed 42
    }

    @Ignore("FI-D8: slots per card, cell diameter at least 56dp at the minimum card size, same seed same layout")
    @Test
    fun fi_d8_card_layout_is_deterministic_has_no_overlap_and_meets_minimum_cell() {
        // TODO(findit): slots per card, cell diameter at least 56dp at the minimum card size, same seed same layout
    }

    @Ignore("FI-D9: ids unique, 13 and 31 prefixes, each has EN and ES string resources")
    @Test
    fun fi_d9_symbol_catalog_has_57_unique_ids_with_names_and_nested_prefixes() {
        // TODO(findit): ids unique, 13 and 31 prefixes, each has EN and ES string resources
    }

    @Ignore("FI-D10: property test for the reshuffle edge case in Solo Dash")
    @Test
    fun fi_d10_next_card_never_equals_current_and_shares_exactly_one_symbol_over_1000_seeds() {
        // TODO(findit): property test for the reshuffle edge case in Solo Dash
    }
}
