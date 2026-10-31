package com.gamesuite.games.holdem

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import com.gamesuite.transport.MultiplayerTransport

/** Card shorthand for tests: "As" is the ace of spades, "Td" the ten of diamonds, "2c" the deuce of clubs. */
object T {
    fun c(s: String): Int {
        val rank = "23456789TJQKA".indexOf(s[0]) + 2
        val suit = "cdhs".indexOf(s[1])
        require(rank >= 2 && suit >= 0) { "bad card $s" }
        return HoldemCards.make(rank, suit)
    }

    fun cards(s: String): List<Int> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::c)

    /**
     * A 52-card order that deals [holes] (one "xx yy" pair per dealt seat, in dealing order: the seat left of
     * the button first) and then [board] ("flop turn river", five cards) with a burn card before each street.
     */
    fun deck(holes: List<String>, board: String): List<Int> {
        val h = holes.map { cards(it) }
        val b = cards(board)
        require(b.size == 5)
        val used = (h.flatten() + b).toMutableSet()
        require(used.size == h.size * 2 + 5) { "duplicate cards in script" }
        val spare = (0 until 52).filter { it !in used }.toMutableList()
        fun burn(): Int = spare.removeAt(0).also { used += it }
        val out = ArrayList<Int>()
        h.forEach { out += it[0] }
        h.forEach { out += it[1] }
        out += burn(); out += b.subList(0, 3)
        out += burn(); out += b[3]
        out += burn(); out += b[4]
        out += (0 until 52).filter { it !in out }
        check(out.size == 52 && out.toSet().size == 52)
        return out
    }

    fun players(humans: Int, bots: Int): List<PlayerInfo> =
        List(humans) { PlayerInfo("p${it + 1}", "Player ${it + 1}", false) } +
            List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", true) }

    fun localContext(players: List<PlayerInfo>, mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT): GameContext =
        GameContext(mode, players, 0, LocalPassAndPlayTransport())

    /** A fresh engine state with every seat on [chips] (or the per-seat stacks, whose sum must be seats * startingChips). */
    fun match(seats: Int, startingChips: Int = 1000, button: Int = 0, speed: String = "off", engine: HoldemEngine): HoldemState =
        engine.newMatch(players(seats, 0), HoldemConfig(startingChips = startingChips, blindSpeed = speed), button)

    fun withStacks(s: HoldemState, stacks: List<Int>): HoldemState =
        s.copy(seats = s.seats.mapIndexed { i, p -> p.copy(chips = stacks[i]) })
}

/** Records every send and exposes the listeners a game registered; a [HdHub] routes between endpoints like a relay. */
class HdFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray)

    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val reconnectedListeners = mutableListOf<() -> Unit>()
    var router: ((source: HdFakeTransport, from: String, to: String?, payload: ByteArray) -> Unit)? = null

    override fun connect() {}
    override fun disconnect() {}

    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        sent += Sent(fromPlayerId, toPlayerId, payload)
        router?.invoke(this, fromPlayerId, toPlayerId, payload)
    }

    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) { messageListeners += listener }
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) { joinedListeners += listener }
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) { leftListeners += listener }
    override fun onReconnected(listener: () -> Unit) { reconnectedListeners += listener }

    fun inject(fromPlayerId: String, payload: ByteArray) = messageListeners.toList().forEach { it(fromPlayerId, payload) }
    fun fireLeft(playerId: String) = leftListeners.toList().forEach { it(playerId) }
    fun fireJoined(playerId: String) = joinedListeners.toList().forEach { it(playerId) }
    fun fireReconnected() = reconnectedListeners.toList().forEach { it() }
}

class HdHub {
    val endpoints = linkedMapOf<String, HdFakeTransport>()

    /** Messages whose claimed sender differed from the endpoint they really came from. */
    val senderLies = mutableListOf<String>()

    fun endpoint(playerId: String): HdFakeTransport {
        val t = HdFakeTransport()
        endpoints[playerId] = t
        t.router = { source, from, to, payload ->
            val owner = endpoints.entries.first { it.value === source }.key
            if (from != owner) senderLies += "endpoint $owner claimed to be $from"
            val targets = if (to != null) listOfNotNull(endpoints[to]) else endpoints.values.filter { it !== source }
            targets.forEach { it.inject(owner, payload) }
        }
        return t
    }
}

/** A networked table: seat 0 is the host. */
class HdTable(val seats: Int, mode: PlayMode = PlayMode.LOCAL_AD_HOC, extraSpectators: Int = 0, seed: Long = 7) {
    val hub = HdHub()
    val ids = List(seats) { if (it == 0) "h" else "g$it" }
    val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
    val games = ArrayList<HoldemGame>()
    val transports = ArrayList<HdFakeTransport>()
    val spectators = ArrayList<HoldemGame>()
    val spectatorTransports = ArrayList<HdFakeTransport>()

    init {
        for (i in 0 until seats) {
            val t = hub.endpoint(ids[i])
            val g = HoldemGame(kotlin.random.Random(if (i == 0) seed else 1000L + i))
            g.equityIterationsOverride = 20
            g.init(GameContext(mode, players, i, t))
            games += g
            transports += t
        }
        for (k in 0 until extraSpectators) {
            val id = "w$k"
            val t = hub.endpoint(id)
            val g = HoldemGame()
            g.init(GameContext(mode, players, -1, t))
            spectators += g
            spectatorTransports += t
        }
    }

    val host get() = games[0]
    fun start() = host.startMatch()
}
