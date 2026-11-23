package com.gamesuite.games.nonogram

import com.gamesuite.settings.CpuDifficulty

/**
 * Known-good Nonogram pictures bundled as data, used only when generation fails (see NonogramGame.generateOrFallback).
 * Each entry is `size;bits` with bits row by row (1 filled). The unit tests prove every one has the right size for its
 * tier, is solved uniquely by its own clues and, for EASY and MEDIUM, is solvable by line logic alone.
 */
internal object NonogramFallbackPuzzles {
    class Spec(val size: Int, val solution: List<Boolean>)

    private val raw: Map<CpuDifficulty, List<String>> = mapOf(
        CpuDifficulty.EASY to listOf(
            "5;1011110111110100111111100",
            "5;0010001100101101000101011",
            "5;0101100110111000101110001"
        ),
        CpuDifficulty.MEDIUM to listOf(
            "10;1011110111110100111111100100010010000001101000110100000000101000010010101011010111111100010001000011",
            "10;0000011101111100010011101011101110000101010100010101101010001111011000100001010010000101001101100111",
            "10;0010001111001001011001100110011111010001000001011000100000010101100000101010001101111101010100111011"
        ),
        CpuDifficulty.HARD to listOf(
            "15;011000001100101110100000111100110000110110000100111101100100100100100101100100000101101000101001111101011111101000110000011110010000000011100100100110110001011001111001111100001000000011010001111111001101000110001110000000000",
            "15;100011100001000101100000100101101001100110101000111111000110000010111011110100010010011100011100001101000111000000101000001100000101000010001100000100011001101100001100010000101101110110000111100000110000000110001011000001100",
            "15;110010111010110101011110010101100010100010110101100111011011001011100110101100011100100001001110000000101010010100001100111011001100110011011001111110000010110111101001010001110110011111111000001000001110101011100010011010001"
        )
    )

    fun all(tier: CpuDifficulty): List<Spec> = (raw[tier] ?: emptyList()).map { parse(it) }

    fun pick(tier: CpuDifficulty, n: Int): Spec {
        val list = all(tier).ifEmpty { all(CpuDifficulty.MEDIUM) }
        return list[n % list.size]
    }

    fun parse(line: String): Spec {
        val (sizeText, bits) = line.split(';')
        return Spec(sizeText.toInt(), bits.map { it == '1' })
    }

    fun encode(size: Int, solution: List<Boolean>): String = "$size;" + solution.joinToString("") { if (it) "1" else "0" }
}
