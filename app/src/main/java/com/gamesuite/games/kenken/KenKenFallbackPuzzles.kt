package com.gamesuite.games.kenken

import com.gamesuite.settings.CpuDifficulty

/**
 * Known-good KenKen puzzles bundled as data, used only when generation fails (see KenKenGame.generateOrFallback).
 * Each line: `size;solution;cages` where solution is the digits row by row and cages are `cell,cell,...:OP:target`
 * joined by `/` (OP is A add, S subtract, M multiply, D divide, - none). The unit tests prove every one has the
 * right size, a valid Latin square, cages that tile the board, clues that match the solution, and exactly one solution.
 */
internal object KenKenFallbackPuzzles {
    class Spec(val size: Int, val solution: IntArray, val cages: List<KenKenCage>)

    private val raw: Map<CpuDifficulty, List<String>> = mapOf(
        CpuDifficulty.EASY to listOf(
            "4;4132124334212314;0,1:D:4/2,3,7:A:8/4,8,12:A:6/5,6,9:A:10/10,11,15:A:7/13,14:D:3",
            "4;4123341223411234;0,1:D:4/2,3,7:A:7/4,8,12:M:6/5,6,9:M:12/10,11,15:M:16/13,14:S:1",
            "4;4312324114232134;0,1:S:1/2,3,7:M:2/4,8,12:M:6/5,6,9:M:32/10,11,15:M:24/13,14:S:2"
        ),
        CpuDifficulty.MEDIUM to listOf(
            "6;615324243156426513561432134265352641;0,6,7,8:M:144/1:-:1/2:-:5/3,9:S:2/4,10,16:M:10/5:-:4/11:-:6/12:-:4/13,19:D:3/14:-:6/15:-:5/17:-:3/18:-:5/20,21,22:M:12/23,29:S:3/24,30:S:2/25,31:S:2/26,32:D:2/27,28,33:A:14/34,35:S:3",
            "6;143265421653364512235146516324652431;0:-:1/1,2,7,8:M:24/3,9:D:3/4:-:6/5,11:S:2/6:-:4/10,16,17,22:A:12/12,13,18,19:M:108/14,15:S:1/20:-:5/21:-:1/23:-:6/24,25,30:A:12/26,32:D:3/27:-:3/28,29:S:2/31:-:5/33,34,35:M:12",
            "6;623415164253532164416532251346345621;0,6,7,8:A:17/1:-:2/2:-:3/3,9:D:2/4,10,16:M:30/5:-:5/11:-:3/12:-:5/13,19:D:3/14:-:2/15:-:1/17:-:4/18:-:4/20,21,22:M:90/23,29:D:3/24,30:S:1/25,31:S:1/26,32:S:4/27,28,33:A:13/34,35:D:2"
        ),
        CpuDifficulty.HARD to listOf(
            "9;956247381489326157318962574574681932197835426845713269732198645263459718621574893;0:-:9/1:-:5/2,11:S:3/3,4,12:A:9/5,14,15:A:14/6,7:S:5/8:-:1/9,10:S:4/13,22:D:3/16:-:5/17:-:7/18:-:3/19,20:D:8/21:-:9/23,24,33,42:A:20/25:-:7/26:-:4/27:-:5/28:-:7/29,30:S:2/31:-:8/32:-:1/34,35,43,44:A:13/36,45:D:8/37,46,54,55:A:23/38:-:7/39,48:S:1/40,49,50:A:7/41:-:5/47:-:5/51,52,53:M:108/56:-:2/57,58:S:8/59,60:S:2/61,62:S:1/63,64,73:A:10/65,66:S:1/67,68,69,70:A:22/71,80:S:5/72:-:6/74,75,76:M:35/77:-:4/78:-:8/79:-:9",
            "9;148297365487153296923561784894376521739845612561932478216784953375628149652419837;0,9:S:3/1:-:4/2,3,11,12:M:112/4,13:S:4/5,6:S:4/7:-:6/8:-:5/10:-:8/14:-:3/15,16:S:7/17:-:6/18,19,20,28:M:486/21,29,30:A:12/22:-:6/23:-:1/24:-:7/25,26:S:4/27:-:8/31:-:7/32,39,40,41:M:960/33:-:5/34,35,44:M:4/36:-:7/37:-:3/38:-:9/42,51,52:A:17/43:-:1/45,54:S:3/46:-:6/47,48,57:A:17/49,50:S:1/53:-:8/55:-:1/56:-:6/58:-:8/59:-:4/60,69:S:8/61,62:S:2/63,64:S:4/65:-:5/66:-:6/67,76,77:A:12/68:-:8/70,71:S:5/72:-:6/73:-:5/74:-:2/75:-:4/78:-:8/79:-:3/80:-:7",
            "9;812364975356928741265487319123675498681249537438791652974532186597813264749156823;0:-:8/1:-:1/2,11:D:3/3,4,12:M:162/5,14,15:A:19/6,7:S:2/8:-:5/9,10:S:2/13,22:D:4/16:-:4/17:-:1/18:-:2/19,20:S:1/21:-:4/23,24,33,42:A:19/25:-:1/26:-:9/27:-:1/28:-:2/29,30:S:3/31:-:7/32:-:5/34,35,43,44:M:1512/36,45:S:2/37,46,54,55:M:1512/38:-:1/39,48:S:5/40,49,50:A:14/41:-:9/47:-:8/51,52,53:A:13/56:-:4/57,58:S:2/59,60:S:1/61,62:S:2/63,64,73:A:18/65,66:S:1/67,68,69,70:M:36/71,80:S:1/72:-:7/74,75,76:A:15/77:-:6/78:-:8/79:-:2"
        )
    )

    fun all(tier: CpuDifficulty): List<Spec> = (raw[tier] ?: emptyList()).map { parse(it) }

    fun pick(tier: CpuDifficulty, n: Int): Spec {
        val list = all(tier).ifEmpty { all(CpuDifficulty.MEDIUM) }
        return list[n % list.size]
    }

    fun parse(line: String): Spec {
        val (sizeText, solText, cageText) = line.split(';')
        val size = sizeText.toInt()
        val solution = IntArray(size * size) { solText[it] - '0' }
        val cages = cageText.split('/').mapIndexed { id, part ->
            val (cells, op, target) = part.split(':')
            val operator = when (op) {
                "A" -> KenKenOperator.ADD
                "S" -> KenKenOperator.SUB
                "M" -> KenKenOperator.MUL
                "D" -> KenKenOperator.DIV
                else -> null
            }
            KenKenCage(id = id, cellIndices = cells.split(',').map { it.toInt() }, operator = operator, target = target.toInt())
        }
        return Spec(size, solution, cages)
    }

    fun encode(size: Int, solution: List<Int>, cages: List<KenKenCage>): String =
        "$size;${solution.joinToString("")};" + cages.joinToString("/") { c ->
            val op = when (c.operator) {
                KenKenOperator.ADD -> "A"
                KenKenOperator.SUB -> "S"
                KenKenOperator.MUL -> "M"
                KenKenOperator.DIV -> "D"
                null -> "-"
            }
            "${c.cellIndices.joinToString(",")}:$op:${c.target}"
        }
}
