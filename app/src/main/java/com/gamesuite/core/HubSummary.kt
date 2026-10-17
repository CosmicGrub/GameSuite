package com.gamesuite.core

/**
 * A short human line describing a mode and its resolved options, e.g.
 * "vs Computer · Hard · You first" or "UNO vs Computers · 4 computers · Party". Pure Kotlin so it
 * is unit tested; the Hub shows it under the Play button.
 *
 * Starts with the mode title. Then, for each visible option (in declaration order): the standard
 * difficulty / first mover / computers / people options always appear, every other option appears
 * only when it differs from its default (a toggle only when switched on).
 */
fun summarizeHubChoices(mode: HubMode, choices: HubChoices): String {
    val c = mode.resolve(choices)
    val parts = mutableListOf(mode.title)
    // Keys a chosen preset already stands for ("Party" implies its house rules): not repeated
    // unless the player has since changed them away from the preset's value.
    val presetValues = mutableMapOf<String, String>()
    for (option in mode.options) {
        if (option is HubOption.Choice) {
            val picked = c.string(option.key, option.default)
            option.choices.firstOrNull { it.id == picked }?.let { presetValues += it.applies }
        }
    }
    for (option in mode.options) {
        val visible = option.visibleWhen?.invoke(c) ?: true
        if (!visible) continue
        val preset = presetValues[option.key]
        if (preset != null && c.asMap()[option.key] == preset) continue
        // The Settings-default difficulty says nothing worth showing.
        if (option.key == HubKeys.DIFFICULTY &&
            c.string(option.key, HubKeys.DIFFICULTY_DEFAULT) == HubKeys.DIFFICULTY_DEFAULT
        ) continue
        val always = option.key == HubKeys.DIFFICULTY || option.key == HubKeys.FIRST ||
            option.key == HubKeys.CPUS || option.key == HubKeys.HUMANS
        when (option) {
            is HubOption.Choice -> {
                val value = c.string(option.key, option.default)
                if (always || value != option.default) {
                    val label = option.choices.firstOrNull { it.id == value }?.label ?: value
                    parts += if (option.key == HubKeys.FIRST) "$label first" else label
                }
            }
            is HubOption.Toggle -> {
                val on = c.bool(option.key, option.default)
                if (on && on != option.default) parts += option.label
            }
            is HubOption.Stepper -> {
                val n = c.int(option.key, option.default)
                if (always || n != option.default) {
                    parts += when (option.key) {
                        HubKeys.CPUS -> if (n == 1) "1 computer" else "$n computers"
                        HubKeys.HUMANS -> if (n == 1) "1 player" else "$n players"
                        else -> "${option.label} $n"
                    }
                }
            }
        }
    }
    return parts.joinToString(" · ")
}
