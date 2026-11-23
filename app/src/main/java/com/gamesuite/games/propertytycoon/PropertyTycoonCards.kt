package com.gamesuite.games.propertytycoon

/** The two 16-card decks as data (design doc, "Card tables"). GENERATED from the doc's tables; text lives in the string resources. */
object PtCards {
    val tide: List<Card> = listOf(
        Card("t01", DeckKind.TIDE, "pt_card_tide_01", CardEffect.MoveTo(0, true)),
        Card("t02", DeckKind.TIDE, "pt_card_tide_02", CardEffect.MoveToNearest(TileType.TRANSIT, 2, 0)),
        Card("t03", DeckKind.TIDE, "pt_card_tide_03", CardEffect.MoveToNearest(TileType.UTILITY, 1, 10)),
        Card("t04", DeckKind.TIDE, "pt_card_tide_04", CardEffect.GoToTimeout),
        Card("t05", DeckKind.TIDE, "pt_card_tide_05", CardEffect.GetOutFree),
        Card("t06", DeckKind.TIDE, "pt_card_tide_06", CardEffect.MoveBy(-3)),
        Card("t07", DeckKind.TIDE, "pt_card_tide_07", CardEffect.MoveTo(39, true)),
        Card("t08", DeckKind.TIDE, "pt_card_tide_08", CardEffect.Gain(100)),
        Card("t09", DeckKind.TIDE, "pt_card_tide_09", CardEffect.Pay(60)),
        Card("t10", DeckKind.TIDE, "pt_card_tide_10", CardEffect.Repairs(35, 110)),
        Card("t11", DeckKind.TIDE, "pt_card_tide_11", CardEffect.PayEachRival(25)),
        Card("t12", DeckKind.TIDE, "pt_card_tide_12", CardEffect.CollectFromEachRival(20)),
        Card("t13", DeckKind.TIDE, "pt_card_tide_13", CardEffect.MoveTo(5, true)),
        Card("t14", DeckKind.TIDE, "pt_card_tide_14", CardEffect.Gain(150)),
        Card("t15", DeckKind.TIDE, "pt_card_tide_15", CardEffect.MoveTo(31, true)),
        Card("t16", DeckKind.TIDE, "pt_card_tide_16", CardEffect.MoveBy(2))
    )

    val guild: List<Card> = listOf(
        Card("g01", DeckKind.GUILD, "pt_card_guild_01", CardEffect.Gain(120)),
        Card("g02", DeckKind.GUILD, "pt_card_guild_02", CardEffect.Gain(45)),
        Card("g03", DeckKind.GUILD, "pt_card_guild_03", CardEffect.CollectFromEachRival(15)),
        Card("g04", DeckKind.GUILD, "pt_card_guild_04", CardEffect.Pay(70)),
        Card("g05", DeckKind.GUILD, "pt_card_guild_05", CardEffect.Repairs(30, 95)),
        Card("g06", DeckKind.GUILD, "pt_card_guild_06", CardEffect.GetOutFree),
        Card("g07", DeckKind.GUILD, "pt_card_guild_07", CardEffect.GoToTimeout),
        Card("g08", DeckKind.GUILD, "pt_card_guild_08", CardEffect.Gain(60)),
        Card("g09", DeckKind.GUILD, "pt_card_guild_09", CardEffect.Pay(25)),
        Card("g10", DeckKind.GUILD, "pt_card_guild_10", CardEffect.Gain(180)),
        Card("g11", DeckKind.GUILD, "pt_card_guild_11", CardEffect.Pay(140)),
        Card("g12", DeckKind.GUILD, "pt_card_guild_12", CardEffect.MoveTo(0, true)),
        Card("g13", DeckKind.GUILD, "pt_card_guild_13", CardEffect.Gain(100)),
        Card("g14", DeckKind.GUILD, "pt_card_guild_14", CardEffect.PayEachRival(20)),
        Card("g15", DeckKind.GUILD, "pt_card_guild_15", CardEffect.Gain(30)),
        Card("g16", DeckKind.GUILD, "pt_card_guild_16", CardEffect.Gain(80))
    )

    val all: List<Card> = tide + guild
    val byId: Map<String, Card> = all.associateBy { it.id }
}
