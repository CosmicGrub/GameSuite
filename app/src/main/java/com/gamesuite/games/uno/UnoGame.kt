package com.gamesuite.games.uno

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full UNO implementation: standard 108-card deck, classic rules plus
 * togglable house rules (draw stacking, 7-0, jump-in), CPU bots, and team
 * play. All state changes go through a single UnoState snapshot in
 * `state` (Compose-observable) so the UI stays a pure function of state.
 *
 * Call configureRules()/configurePlayers() are not needed — rules come from
 * `rules` (set before startMatch) and players come from GameContext.
 *
 * NETWORKED PLAY (PlayMode.LOCAL_AD_HOC): host-authoritative. Only the host
 * (context.localPlayerIndex == 0, by the lobby's own convention — see
 * NearbyHostLobbyScreen/NearbyJoinLobbyScreen) ever actually runs this
 * engine; every other device is a pure renderer of the host's broadcast
 * UnoState, and turns its own local player's taps into an Intent sent to
 * the host instead of mutating anything itself. See UnoNetMessage.kt's
 * KDoc for why a replicated-simulation design (every device running the
 * rules independently) doesn't work here: dealNewRound() shuffles with
 * local RNG, so independent devices would deal themselves different hands
 * for "the same" game — an instant desync. Every public mutating entry
 * point below starts with the same guard: a non-host device in a networked
 * game sends an Intent and returns without touching local state; a host
 * (networked or not) and any device in a non-networked game runs exactly
 * the same logic as before this pass, just routed through commitState()
 * instead of a bare `state.value =` so a host's mutation also broadcasts.
 */
class UnoGame : GameModule {
    override val gameId = "uno"
    override val displayName = "UNO"
    override val category = GameCategory.CARD
    override val minPlayers = 2
    override val maxPlayers = 10
    override val supportedModes = listOf(
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.LOCAL_AD_HOC
    )

    /** Set before startMatch() to pick house rules / team play. Defaults to classic rules. */
    var rules: UnoRules = UnoRules()

    val state = mutableStateOf<UnoState?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var drawPile: MutableList<UnoCard> = mutableListOf()

    // ---- Networked play bookkeeping ----
    private val isNetworked: Boolean get() = context.activeMode == PlayMode.LOCAL_AD_HOC
    private val isHost: Boolean get() = context.localPlayerIndex == 0
    private var stateVersion = 0
    private var lastAppliedRemoteVersion = 0

    override fun init(context: GameContext) {
        this.context = context
        if (context.activeMode == PlayMode.LOCAL_AD_HOC) {
            context.transport.onMessageReceived { fromPlayerId, payload -> handleNetworkMessage(fromPlayerId, payload) }
            if (!isHost) {
                // Covers the startup race where this device's listener registers after
                // the host has already dealt and broadcast the opening StateSync — ask
                // directly instead of waiting forever on a broadcast that already
                // happened before anything here could hear it.
                sendToHost(UnoNetMessage.RequestState)
            }
        }
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        if (isNetworked && !isHost) return // guest: wait for the host's broadcast (or its RequestState reply)
        dealNewRound(previousCumulative = emptyMap(), newRoundNumber = 1)
    }

    /** Official UNO plays multiple rounds/hands to 500 cumulative points — call this after a round ends
     * (roundOver=true, matchOver=false) to redeal and continue with the same cumulative scores. */
    fun startNextRound() {
        if (isNetworked && !isHost) return
        val s = state.value ?: return
        if (!s.roundOver || s.matchOver) return
        dealNewRound(previousCumulative = s.cumulativeScores, newRoundNumber = s.roundNumber + 1)
    }

    private fun dealNewRound(previousCumulative: Map<String, Int>, newRoundNumber: Int) {
        val deck = UnoDeck.standardDeck().apply { shuffle() }
        drawPile = deck

        val players = context.players.mapIndexed { index, info ->
            UnoPlayerState(
                playerId = info.playerId,
                displayName = info.displayName,
                isBot = info.isBot,
                teamId = if (rules.teamPlay) info.teamId else -1,
                hand = drawFromPile(7)
            )
        }

        val firstDiscard = flipInitialCard()

        commitState(
            UnoState(
                players = players,
                drawPileSize = drawPile.size,
                discardPile = listOf(firstDiscard),
                currentColor = if (firstDiscard.isWild) UnoColor.RED else firstDiscard.color,
                currentPlayerIndex = 0,
                direction = 1,
                pendingDraw = 0,
                awaitingColorChoice = firstDiscard.isWild,
                lastAction = "Round $newRoundNumber started",
                cumulativeScores = previousCumulative,
                roundNumber = newRoundNumber
            )
        )

        // Apply the opening card's effect (skip/reverse/draw two) same as any played card, minus the player who "played" it.
        applyOpeningEffect(firstDiscard)
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        state.value?.copy(matchOver = true)?.let { commitState(it) }
        onMatchEnd?.invoke(result)
    }

    // ---- Player actions (called from UI or bot loop) ----

    fun playCard(playerIndex: Int, card: UnoCard) {
        if (isNetworked && !isHost) {
            sendIntent(UnoIntentPayload.PlayCard(playerIndex, card.instanceId))
            return
        }
        val s = state.value ?: return
        if (s.roundOver || s.matchOver || s.awaitingColorChoice || s.awaitingChallenge) return
        if (playerIndex != s.currentPlayerIndex) return // use jumpIn() for out-of-turn plays
        val player = s.players[playerIndex]
        if (!player.hand.any { it.instanceId == card.instanceId }) return
        if (!isLegalPlay(card, s)) return

        val newHand = player.hand.filterNot { it.instanceId == card.instanceId }
        var updatedPlayers = s.players.toMutableList()
        updatedPlayers[playerIndex] = player.copy(
            hand = newHand,
            calledUno = if (newHand.size != 1) false else player.calledUno
        )

        val newDiscard = s.discardPile + card
        var newColor = if (card.isWild) s.currentColor else card.color
        var newPendingDraw = s.pendingDraw

        // Resolve non-color-choice effects immediately; wild cards pause for color choice.
        if (card.isWild) {
            commitState(
                s.copy(
                    players = updatedPlayers,
                    discardPile = newDiscard,
                    awaitingColorChoice = true,
                    pendingDraw = if (card.rank == UnoRank.WILD_DRAW_FOUR) {
                        if (rules.stackDraw) s.pendingDraw + 4 else 4
                    } else s.pendingDraw,
                    // Remember the color that was in play before this Wild Draw Four overrode it —
                    // needed to judge a Challenge (official rule: Wild Draw Four is only legal to
                    // play when you have no card matching THIS color).
                    colorBeforeWildDrawFour = if (card.rank == UnoRank.WILD_DRAW_FOUR) s.currentColor else null,
                    lastAction = "${player.displayName} played ${card.displayLabel()}"
                )
            )
            checkWinAfterPlay(playerIndex, newHand)
            return
        }

        if (card.rank == UnoRank.DRAW_TWO) {
            newPendingDraw = if (rules.stackDraw) s.pendingDraw + 2 else 2
        }

        var nextIndex = playerIndex
        var direction = s.direction
        var actionLog = "${player.displayName} played ${card.displayLabel()}"

        when (card.rank) {
            UnoRank.REVERSE -> {
                direction = -direction
                if (s.players.size == 2) {
                    // Acts as Skip with 2 players.
                    nextIndex = advanceIndex(playerIndex, direction, s.players.size)
                }
            }
            UnoRank.SKIP -> {
                nextIndex = advanceIndex(playerIndex, direction, s.players.size)
            }
            UnoRank.SEVEN -> {} // handled below if rules.sevenZero
            else -> {}
        }

        if (rules.sevenZero && card.rank == UnoRank.SEVEN) {
            // Bot auto-picks a target; human UI should call swapHands() itself before this returns
            // in a full implementation. For now, auto-target lowest-hand opponent for both bots and humans.
            val targetIdx = UnoBot.chooseSwapTarget(s.copy(players = updatedPlayers), playerIndex)
            val tmp = updatedPlayers[playerIndex].hand
            updatedPlayers[playerIndex] = updatedPlayers[playerIndex].copy(hand = updatedPlayers[targetIdx].hand)
            updatedPlayers[targetIdx] = updatedPlayers[targetIdx].copy(hand = tmp)
            actionLog += " and swapped hands with ${updatedPlayers[targetIdx].displayName}"
        }
        if (rules.sevenZero && card.rank == UnoRank.ZERO) {
            updatedPlayers = rotateHands(updatedPlayers, direction)
            actionLog += " and rotated all hands"
        }

        // Draw Two without stacking: next player draws immediately and is skipped.
        if (card.rank == UnoRank.DRAW_TWO && !rules.stackDraw) {
            val victimIndex = advanceIndex(playerIndex, direction, s.players.size)
            val drawn = drawFromPile(2)
            updatedPlayers[victimIndex] = updatedPlayers[victimIndex].copy(
                hand = updatedPlayers[victimIndex].hand + drawn,
                calledUno = false
            )
            nextIndex = advanceIndex(victimIndex, direction, s.players.size)
            newPendingDraw = 0
        } else if (card.rank != UnoRank.DRAW_TWO) {
            nextIndex = advanceIndex(nextIndex, direction, s.players.size)
        }
        // else: stacking DRAW_TWO just passes the obligation on, handled by nextIndex advance below.
        if (card.rank == UnoRank.DRAW_TWO && rules.stackDraw) {
            nextIndex = advanceIndex(playerIndex, direction, s.players.size)
        }

        commitState(
            s.copy(
                players = updatedPlayers,
                discardPile = newDiscard,
                currentColor = newColor,
                currentPlayerIndex = nextIndex,
                direction = direction,
                pendingDraw = newPendingDraw,
                drawPileSize = drawPile.size,
                lastAction = actionLog
            )
        )

        checkWinAfterPlay(playerIndex, newHand)
    }

    /** Called after a Wild/Wild Draw Four is played, by the UI (color picker) or the bot. */
    fun chooseColor(color: UnoColor) {
        if (isNetworked && !isHost) {
            sendIntent(UnoIntentPayload.ChooseColor(color))
            return
        }
        val s = state.value ?: return
        if (!s.awaitingColorChoice) return

        val playedCard = s.discardPile.last()

        if (playedCard.rank == UnoRank.WILD_DRAW_FOUR && !rules.stackDraw) {
            // Don't auto-resolve the draw — pause for the victim to accept or Challenge (official rule).
            val victimIndex = advanceIndex(s.currentPlayerIndex, s.direction, s.players.size)
            commitState(
                s.copy(
                    currentColor = color,
                    awaitingColorChoice = false,
                    awaitingChallenge = true,
                    challengeVictimIndex = victimIndex,
                    challengePlayedByIndex = s.currentPlayerIndex,
                    currentPlayerIndex = victimIndex,
                    lastAction = "${s.players[victimIndex].displayName}: accept the draw 4, or Challenge?"
                )
            )
            return
        }

        val nextIndex = advanceIndex(s.currentPlayerIndex, s.direction, s.players.size)
        commitState(
            s.copy(
                currentColor = color,
                currentPlayerIndex = nextIndex,
                awaitingColorChoice = false,
                lastAction = "Color changed to $color"
            )
        )
    }

    /**
     * Resolves a pending Wild Draw Four Challenge. accept=true: the victim just draws 4 and
     * loses their turn. accept=false (Challenge): if the player who played the Wild Draw Four
     * DID have a card matching the prior color (an illegal play), they draw 4 instead and the
     * victim's turn proceeds normally; if the play was legal, the challenge fails and the victim
     * draws 6 instead of 4.
     */
    fun resolveChallenge(accept: Boolean) {
        if (isNetworked && !isHost) {
            sendIntent(UnoIntentPayload.ResolveChallenge(accept))
            return
        }
        val s = state.value ?: return
        if (!s.awaitingChallenge) return
        val victimIndex = s.challengeVictimIndex ?: return
        val playedByIndex = s.challengePlayedByIndex ?: return
        val colorBefore = s.colorBeforeWildDrawFour

        val updatedPlayers = s.players.toMutableList()
        val nextIndex: Int
        val actionLog: String

        if (accept) {
            updatedPlayers[victimIndex] = updatedPlayers[victimIndex].copy(
                hand = updatedPlayers[victimIndex].hand + drawFromPile(4),
                calledUno = false
            )
            nextIndex = advanceIndex(victimIndex, s.direction, s.players.size)
            actionLog = "${s.players[victimIndex].displayName} draws 4"
        } else {
            val playedByHadMatch = colorBefore != null &&
                updatedPlayers[playedByIndex].hand.any { !it.isWild && it.color == colorBefore }

            if (playedByHadMatch) {
                updatedPlayers[playedByIndex] = updatedPlayers[playedByIndex].copy(
                    hand = updatedPlayers[playedByIndex].hand + drawFromPile(4),
                    calledUno = false
                )
                nextIndex = victimIndex
                actionLog = "Challenge succeeds! ${updatedPlayers[playedByIndex].displayName} draws 4"
            } else {
                updatedPlayers[victimIndex] = updatedPlayers[victimIndex].copy(
                    hand = updatedPlayers[victimIndex].hand + drawFromPile(6),
                    calledUno = false
                )
                nextIndex = advanceIndex(victimIndex, s.direction, s.players.size)
                actionLog = "Challenge fails — ${updatedPlayers[victimIndex].displayName} draws 6"
            }
        }

        commitState(
            s.copy(
                players = updatedPlayers,
                awaitingChallenge = false,
                challengeVictimIndex = null,
                challengePlayedByIndex = null,
                colorBeforeWildDrawFour = null,
                pendingDraw = 0,
                currentPlayerIndex = nextIndex,
                drawPileSize = drawPile.size,
                lastAction = actionLog
            )
        )
    }

    /** Out-of-turn play: any player holding an exact color+rank match of the top card may jump in (rules.jumpIn only). */
    fun jumpIn(playerIndex: Int, card: UnoCard) {
        if (isNetworked && !isHost) {
            sendIntent(UnoIntentPayload.JumpIn(playerIndex, card.instanceId))
            return
        }
        val s = state.value ?: return
        if (!rules.jumpIn) return
        if (s.roundOver || s.matchOver || s.awaitingColorChoice || s.awaitingChallenge) return
        if (playerIndex == s.currentPlayerIndex) return // that's just their normal turn, not a jump
        val top = s.topCard
        if (card.color != top.color || card.rank != top.rank) return
        if (!s.players[playerIndex].hand.any { it.instanceId == card.instanceId }) return

        commitState(
            s.copy(
                currentPlayerIndex = playerIndex,
                lastAction = "${s.players[playerIndex].displayName} jumped in!"
            )
        )
        playCard(playerIndex, card)
    }

    fun drawCard(playerIndex: Int) {
        if (isNetworked && !isHost) {
            sendIntent(UnoIntentPayload.DrawCard(playerIndex))
            return
        }
        val s = state.value ?: return
        if (s.roundOver || s.matchOver || s.awaitingColorChoice || s.awaitingChallenge || playerIndex != s.currentPlayerIndex) return

        val player = s.players[playerIndex]
        val updatedPlayers = s.players.toMutableList()

        if (s.pendingDraw > 0) {
            val drawn = drawFromPile(s.pendingDraw)
            updatedPlayers[playerIndex] = player.copy(hand = player.hand + drawn, calledUno = false)
            val nextIndex = advanceIndex(playerIndex, s.direction, s.players.size)
            commitState(
                s.copy(
                    players = updatedPlayers,
                    pendingDraw = 0,
                    currentPlayerIndex = nextIndex,
                    drawPileSize = drawPile.size,
                    lastAction = "${player.displayName} drew ${s.pendingDraw}"
                )
            )
            return
        }

        val drawn = drawFromPile(1).first()
        val newHand = player.hand + drawn
        updatedPlayers[playerIndex] = player.copy(hand = newHand, calledUno = false)

        val stillPlayable = isLegalPlay(drawn, s)
        val advanceTurn = !(rules.forcePlayDrawnCard && stillPlayable)

        commitState(
            s.copy(
                players = updatedPlayers,
                currentPlayerIndex = if (advanceTurn) advanceIndex(playerIndex, s.direction, s.players.size) else playerIndex,
                drawPileSize = drawPile.size,
                lastAction = "${player.displayName} drew a card"
            )
        )
    }

    /** Player declares "UNO" — call any time their hand is at (or about to reach) 1 card. */
    fun callUno(playerIndex: Int) {
        if (isNetworked && !isHost) {
            sendIntent(UnoIntentPayload.CallUno(playerIndex))
            return
        }
        val s = state.value ?: return
        val updated = s.players.toMutableList()
        updated[playerIndex] = updated[playerIndex].copy(calledUno = true)
        commitState(s.copy(players = updated, lastAction = "${updated[playerIndex].displayName} called UNO!"))
    }

    /** Any player may catch another who has 1 card and never called UNO — penalty: draw 2. */
    fun catchUnoFailure(accuserIndex: Int, targetIndex: Int) {
        if (isNetworked && !isHost) {
            sendIntent(UnoIntentPayload.CatchUnoFailure(accuserIndex, targetIndex))
            return
        }
        val s = state.value ?: return
        if (accuserIndex == targetIndex) return
        val target = s.players[targetIndex]
        if (target.hand.size != 1 || target.calledUno) return

        val updated = s.players.toMutableList()
        updated[targetIndex] = target.copy(hand = target.hand + drawFromPile(2), calledUno = false)
        commitState(
            s.copy(
                players = updated,
                drawPileSize = drawPile.size,
                lastAction = "${s.players[accuserIndex].displayName} caught ${target.displayName} — draw 2 penalty"
            )
        )
    }

    // ---- Bot driver ----

    /** Call from the UI (e.g. a LaunchedEffect) when the current player is a bot. Never
     *  fires in networked play — LOCAL_AD_HOC games are human-only (no shared "who runs
     *  the bot" authority), enforced by the lobby never offering bot seats there. */
    fun playBotTurn() {
        val s = state.value ?: return
        if (s.matchOver) return

        if (s.awaitingChallenge) {
            // Bounded heuristic: bots always accept rather than challenge (no bluff-reading logic).
            val victimIndex = s.challengeVictimIndex ?: return
            if (s.players[victimIndex].isBot) resolveChallenge(accept = true)
            return
        }

        if (s.awaitingColorChoice) {
            val botIndex = s.currentPlayerIndex
            chooseColor(UnoBot.chooseColor(s.players[botIndex].hand))
            return
        }

        val botIndex = s.currentPlayerIndex
        val bot = s.players[botIndex]
        if (!bot.isBot) return

        val move = UnoBot.chooseMove(bot.hand, s, rules)
        if (move != null) {
            playCard(botIndex, move)
            if (state.value?.players?.get(botIndex)?.hand?.size == 1) {
                callUno(botIndex)
            }
        } else {
            drawCard(botIndex)
            // rules.forcePlayDrawnCard: when the drawn card is immediately playable, drawCard()
            // deliberately leaves currentPlayerIndex on this bot instead of advancing the turn.
            // That's an unchanged Int, so the UI's LaunchedEffect (keyed on currentPlayerIndex
            // among other state fields) won't recompose/re-fire to call us again — drive the
            // bot's forced follow-up play ourselves so its turn actually completes.
            val after = state.value
            if (after != null && !after.roundOver && !after.matchOver && after.currentPlayerIndex == botIndex) {
                playBotTurn()
            }
        }
    }

    // ---- Networked play internals ----

    /** Every mutation funnels through here instead of a bare `state.value =` — the one
     *  place that also broadcasts the result when this device is the networked host. */
    private fun commitState(newState: UnoState) {
        state.value = newState
        if (isNetworked && isHost) {
            stateVersion++
            broadcastState(newState, toPlayerId = null)
        }
    }

    private fun broadcastState(s: UnoState, toPlayerId: String?) {
        val message: UnoNetMessage = UnoNetMessage.StateSync(stateVersion, s)
        val payload = Json.encodeToString(message).toByteArray(Charsets.UTF_8)
        context.transport.send(fromPlayerId = context.players[0].playerId, toPlayerId = toPlayerId, payload = payload)
    }

    private fun sendIntent(intent: UnoIntentPayload) {
        sendToHost(UnoNetMessage.Intent(intent))
    }

    private fun sendToHost(message: UnoNetMessage) {
        val payload = Json.encodeToString(message).toByteArray(Charsets.UTF_8)
        val hostId = context.players[0].playerId
        val localId = context.players.getOrNull(context.localPlayerIndex)?.playerId ?: return
        context.transport.send(fromPlayerId = localId, toPlayerId = hostId, payload = payload)
    }

    private fun applyRemoteState(version: Int, newState: UnoState) {
        // Nearby's reliable path doesn't guarantee in-order delivery across all mediums —
        // drop anything older than what's already applied rather than rewinding the board.
        if (version <= lastAppliedRemoteVersion) return
        lastAppliedRemoteVersion = version
        state.value = newState
    }

    private fun handleNetworkMessage(fromPlayerId: String, payload: ByteArray) {
        val message = try {
            Json.decodeFromString<UnoNetMessage>(String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            return // malformed or foreign payload — ignore rather than crash the match
        }
        when (message) {
            is UnoNetMessage.StateSync -> applyRemoteState(message.version, message.state)
            is UnoNetMessage.RequestState -> {
                if (isHost) state.value?.let { broadcastState(it, toPlayerId = fromPlayerId) }
            }
            is UnoNetMessage.Intent -> {
                if (isHost) applyIntent(message.intent)
            }
        }
    }

    private fun applyIntent(intent: UnoIntentPayload) {
        when (intent) {
            is UnoIntentPayload.PlayCard -> findCardInHand(intent.playerIndex, intent.cardInstanceId)?.let { playCard(intent.playerIndex, it) }
            is UnoIntentPayload.ChooseColor -> chooseColor(intent.color)
            is UnoIntentPayload.ResolveChallenge -> resolveChallenge(intent.accept)
            is UnoIntentPayload.JumpIn -> findCardInHand(intent.playerIndex, intent.cardInstanceId)?.let { jumpIn(intent.playerIndex, it) }
            is UnoIntentPayload.DrawCard -> drawCard(intent.playerIndex)
            is UnoIntentPayload.CallUno -> callUno(intent.playerIndex)
            is UnoIntentPayload.CatchUnoFailure -> catchUnoFailure(intent.accuserIndex, intent.targetIndex)
        }
    }

    private fun findCardInHand(playerIndex: Int, cardInstanceId: Int): UnoCard? {
        val s = state.value ?: return null
        return s.players.getOrNull(playerIndex)?.hand?.firstOrNull { it.instanceId == cardInstanceId }
    }

    // ---- Internals ----

    private fun isLegalPlay(card: UnoCard, s: UnoState): Boolean {
        if (s.pendingDraw > 0) {
            val top = s.topCard
            return when {
                top.rank == UnoRank.DRAW_TWO -> card.rank == UnoRank.DRAW_TWO ||
                    (rules.stackDrawFourOnDrawTwo && card.rank == UnoRank.WILD_DRAW_FOUR)
                top.rank == UnoRank.WILD_DRAW_FOUR -> card.rank == UnoRank.WILD_DRAW_FOUR
                else -> false
            }
        }
        return card.isWild || card.color == s.currentColor || card.rank == s.topCard.rank
    }

    /** Official UNO plays to 500 cumulative points, not a single hand — see startNextRound(). */
    private val matchTargetScore = 500

    private fun checkWinAfterPlay(playerIndex: Int, newHand: List<UnoCard>) {
        if (newHand.isNotEmpty()) return
        val s = state.value ?: return
        val winner = s.players[playerIndex]

        val roundPoints = s.players.filter { it.playerId != winner.playerId }
            .sumOf { it.hand.sumOf { c -> c.scoreValue } }

        val newCumulative = s.cumulativeScores.toMutableMap()
        if (rules.teamPlay) {
            // Simplification: every teammate's cumulative total is credited the full round score
            // (rather than splitting it), so either can be read as "the team's score".
            s.players.filter { it.teamId == winner.teamId }.forEach {
                newCumulative[it.playerId] = (newCumulative[it.playerId] ?: 0) + roundPoints
            }
        } else {
            newCumulative[winner.playerId] = (newCumulative[winner.playerId] ?: 0) + roundPoints
        }

        val matchWon = newCumulative.values.any { it >= matchTargetScore }

        commitState(
            s.copy(
                roundOver = true,
                matchOver = matchWon,
                winnerPlayerId = if (!rules.teamPlay) winner.playerId else null,
                winningTeamId = if (rules.teamPlay) winner.teamId else null,
                cumulativeScores = newCumulative,
                lastAction = "${winner.displayName} wins round ${s.roundNumber} (+$roundPoints)"
            )
        )

        if (matchWon) {
            val scores = s.players.map { p ->
                PlayerScore(
                    playerId = p.playerId,
                    score = newCumulative[p.playerId] ?: 0,
                    isWinner = (newCumulative[p.playerId] ?: 0) >= matchTargetScore
                )
            }
            endMatch(GameResult(scores = scores))
        }
    }

    private fun advanceIndex(from: Int, direction: Int, count: Int): Int {
        return ((from + direction) % count + count) % count
    }

    private fun rotateHands(players: MutableList<UnoPlayerState>, direction: Int): MutableList<UnoPlayerState> {
        val hands = players.map { it.hand }
        val n = players.size
        val result = players.toMutableList()
        for (i in 0 until n) {
            val sourceIndex = ((i - direction) % n + n) % n
            result[i] = players[i].copy(hand = hands[sourceIndex])
        }
        return result
    }

    private fun drawFromPile(count: Int): List<UnoCard> {
        ensureDrawPile(count)
        val taken = drawPile.take(count)
        drawPile = drawPile.drop(count).toMutableList()
        return taken
    }

    private fun ensureDrawPile(need: Int) {
        if (drawPile.size >= need) return
        // Reshuffle discard pile (minus current top card) back into the draw pile.
        val s = state.value ?: return
        if (s.discardPile.size <= 1) return
        val top = s.discardPile.last()
        val reshuffled = s.discardPile.dropLast(1).toMutableList()
        reshuffled.shuffle()
        drawPile.addAll(reshuffled)
        // Deliberately NOT routed through commitState: this is an intermediate mutation
        // inside a larger operation whose caller commits the real final state itself
        // once it's done (which — pre-existing behavior, not something this pass
        // introduces — already doesn't preserve this exact reshuffle in every caller;
        // out of scope for the networking retrofit to fix).
        state.value = s.copy(discardPile = listOf(top))
    }

    private fun flipInitialCard(): UnoCard {
        // Redraw if the flipped card is a Wild Draw Four (official rule); otherwise keep it, even Wild.
        var card = drawFromPile(1).first()
        while (card.rank == UnoRank.WILD_DRAW_FOUR) {
            drawPile.add(card)
            drawPile.shuffle()
            card = drawFromPile(1).first()
        }
        return card
    }

    private fun applyOpeningEffect(card: UnoCard) {
        val s = state.value ?: return
        when (card.rank) {
            UnoRank.SKIP -> commitState(s.copy(currentPlayerIndex = advanceIndex(0, 1, s.players.size)))
            UnoRank.REVERSE -> commitState(s.copy(direction = -1))
            UnoRank.DRAW_TWO -> {
                val updated = s.players.toMutableList()
                updated[0] = updated[0].copy(hand = updated[0].hand + drawFromPile(2))
                commitState(
                    s.copy(
                        players = updated,
                        currentPlayerIndex = advanceIndex(0, 1, s.players.size),
                        drawPileSize = drawPile.size
                    )
                )
            }
            else -> {}
        }
    }
}
