package com.gamesuite.core

/**
 * The glue words [summarizeHubChoices] builds its sentence from ("2 computers", "You first").
 * They are catalog text like any other: the UI resolves each through the same string-resource
 * mapping, and HubLocalizationTest requires every one of them to have a translation.
 */
val HUB_SUMMARY_WORDS: List<String> = listOf("first", "computer", "computers", "player", "players")

private const val HUB_KEY_MAX_SLUG = 48
private val HUB_KEY_NON_ALNUM = Regex("[^a-z0-9]+")

/**
 * The Android string-resource name of an English catalog string (game titles, mode blurbs, option
 * labels ...): `hub_` + a slug of the text. The slug is lowercase with every run of characters
 * outside [a-z0-9] collapsed to one `_` and trimmed of `_`. A slug longer than 48 characters is
 * cut to 48 and gets `_` + the last six hex digits of the text's hashCode appended (the low bits, which
 * is where texts that differ only near their end differ), so two long texts that share a prefix
 * still get different names. A text with no letters or digits at all
 * ("+") gets `x` as its slug plus that hash. Pure and deterministic: the UI looks the name up at
 * runtime and the unit tests check every catalog string has a resource under it.
 */
fun hubKey(text: String): String {
    val slug = text.lowercase().replace(HUB_KEY_NON_ALNUM, "_").trim('_')
    if (slug.isNotEmpty() && slug.length <= HUB_KEY_MAX_SLUG) return "hub_$slug"
    val cut = slug.take(HUB_KEY_MAX_SLUG).trimEnd('_').ifEmpty { "x" }
    val hash = Integer.toHexString(text.hashCode()).padStart(8, '0').takeLast(6)
    return "hub_${cut}_$hash"
}

/**
 * A short human line describing a mode and its resolved options, e.g.
 * "vs Computer · Hard · You first" or "UNO vs Computers · 4 computers · Party". Pure Kotlin so it
 * is unit tested; the Hub shows it under the Play button.
 *
 * Starts with the mode title. Then, for each visible option (in declaration order): the standard
 * difficulty / first mover / computers / people options always appear, every other option appears
 * only when it differs from its default (a toggle only when switched on).
 */
fun summarizeHubChoices(mode: HubMode, choices: HubChoices, text: (String) -> String = { it }): String {
    val c = mode.resolve(choices)
    val parts = mutableListOf(text(mode.title))
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
                    val label = option.choices.firstOrNull { it.id == value }?.label?.let(text) ?: value
                    parts += if (option.key == HubKeys.FIRST) "$label ${text("first")}" else label
                }
            }
            is HubOption.Toggle -> {
                val on = c.bool(option.key, option.default)
                if (on && on != option.default) parts += text(option.label)
            }
            is HubOption.Stepper -> {
                val n = c.int(option.key, option.default)
                if (always || n != option.default) {
                    parts += when (option.key) {
                        HubKeys.CPUS -> if (n == 1) "1 ${text("computer")}" else "$n ${text("computers")}"
                        HubKeys.HUMANS -> if (n == 1) "1 ${text("player")}" else "$n ${text("players")}"
                        else -> "${text(option.label)} $n"
                    }
                }
            }
        }
    }
    return parts.joinToString(" · ")
}
