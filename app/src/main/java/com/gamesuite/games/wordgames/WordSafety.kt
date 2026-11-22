package com.gamesuite.games.wordgames

import java.text.Normalizer

/**
 * One shared word-safety filter for every game that CHOOSES a word and shows it to a player:
 * Hangman, Word Search, Word Guess answers, the Word Tiles bot's plays, and the Draw and Guess /
 * Act It Out word banks (whose tests reuse [familyBannedWords] / [familyBannedFragments]).
 *
 * Why it exists: the shared offline dictionary is the raw ~359k-word dwyl list, which contains
 * slurs and profanity. Pure Kotlin (no Android types) so it is covered by plain JVM tests.
 *
 * Matching rules, designed to avoid the Scunthorpe problem (a harmless word that merely CONTAINS
 * a bad one):
 *  - [blockedWords] match a whole token only (token = a run of letters). A token also matches
 *    after dropping a plain suffix (s, es, ed, ing, er, ers), so "sluts" and "raping" are caught
 *    without listing every form. Short words (ass, tit, cock, sex, rape, hell ...) live here, so
 *    "class", "title", "peacock", "essex" and "grape" stay allowed.
 *  - [blockedStems] match anywhere inside a token. They are only long, distinctive stems that do
 *    not occur inside ordinary words (checked against the whole dictionary by the tests).
 *  - Spanish entries are compared accent-folded (cabrón matches cabron) with one deliberate
 *    exception: "coño" is matched unfolded, because the folded form "cono" is a cone.
 *
 * The lists cover slurs, profanity, sexual terms, drug names and violent terms in English and
 * Spanish. They are a floor, not a promise: they are intentionally easy to extend.
 */
object WordSafety {

    /** Whole-token matches (English). Inflections are handled by suffix stripping, irregular ones are listed. */
    private val blockedWordsEn: Set<String> = setOf(
        // slurs
        "nigger", "niggers", "nigga", "niggas", "negro", "negroes", "darkie", "coon", "coons", "spic", "spics", "spick", "kike", "kikes",
        "chink", "chinks", "gook", "gooks", "wetback", "wetbacks", "beaner", "paki", "pakis", "honky", "honkey", "faggot", "faggots", "fag",
        "fags", "dyke", "dykes", "homo", "homos", "tranny", "trannies", "shemale", "retard", "retards", "retarded", "spaz", "mongoloid",
        "cripple", "gypsy", "gyp", "injun", "squaw", "redskin", "redskins", "jap", "japs", "raghead", "towelhead", "camel jockey",
        // profanity
        "cunt", "cunts", "fuck", "fucks", "fucked", "fucker", "fuckers", "fucking", "motherfucker", "shit", "shits", "shitty", "shitting",
        "bullshit", "bitch", "bitches", "bitchy", "bastard", "bastards", "asshole", "assholes", "ass", "asses", "arse", "arses", "arsehole",
        "dick", "dicks", "dickhead", "cock", "cocks", "cocksucker", "prick", "pricks", "pussy", "pussies", "twat", "twats", "wank",
        "wanker", "wankers", "bollocks", "bugger", "slut", "sluts", "slutty", "whore", "whores", "tit", "tits", "titty", "titties",
        "boob", "boobs", "boobie", "boobies", "douche", "douchebag", "damn", "damned", "goddamn", "crap", "crappy", "piss", "pissed",
        "pissing", "turd", "turds", "tosser", "slag", "hell",
        // sexual
        "sex", "sexy", "sexual", "sexes", "sexist", "porn", "porno", "pornography", "penis", "penises", "vagina", "vaginas", "vulva",
        "anus", "anal", "scrotum", "testicle", "testicles", "testes", "erection", "orgasm", "orgasms", "ejaculate", "semen",
        "sperm", "clitoris", "clit", "dildo", "dildos", "horny", "nude", "nudes", "naked", "nipple", "nipples", "breast", "breasts",
        "intercourse", "fellatio", "cunnilingus", "masturbate", "masturbation", "genital", "genitals", "genitalia", "prostitute",
        "prostitutes", "hooker", "hookers", "pimp", "pimps", "brothel", "orgy", "incest", "rape", "rapes", "raped", "raping", "rapist",
        "rapists", "molest", "molested", "molester", "pedophile", "pedophiles", "paedophile", "lust", "lewd", "kinky", "fetish",
        "stripper", "strippers", "bondage", "viagra", "condom", "condoms", "foreplay", "sodomy", "bestiality", "boner",
        // drugs
        "cocaine", "heroin", "meth", "methamphetamine", "marijuana", "cannabis", "hashish", "opium", "morphine",
        "fentanyl", "oxycodone", "lsd", "ecstasy", "mdma", "amphetamine", "amphetamines", "narcotic", "narcotics", "opioid", "opioids",
        "bong", "bongs", "reefer", "junkie", "junkies", "overdose", "overdosed", "steroid", "steroids", "crackhead",
        // violent
        "kill", "kills", "killed", "killer", "killers", "killing", "murder", "murders", "murdered", "murderer", "suicide", "suicidal",
        "massacre", "massacres", "genocide", "homicide", "stab", "stabs", "stabbed", "stabbing", "torture", "tortured", "slaughter",
        "slaughtered", "behead", "beheaded", "beheading", "decapitate", "mutilate", "mutilated", "lynch", "lynched", "lynching",
        "strangle", "strangled", "assassin", "assassins", "assassinate", "terrorist", "terrorists", "terrorism", "bomber",
        "executioner", "corpse", "corpses", "cadaver", "gory", "carnage", "noose", "gunman"
    )

    /** Whole-token matches (Spanish), accent-folded except where noted in the class doc. */
    private val blockedWordsEs: Set<String> = setOf(
        "puta", "putas", "puto", "putos", "putita", "putada", "hijueputa", "hijoputa", "mierda", "mierdas", "coño", "cojones", "cojon",
        "joder", "jodido", "jodida", "jodete", "jodan", "follar", "follando", "follada", "follado", "folla", "pendejo", "pendeja",
        "pendejos", "pendejas", "cabron", "cabrona", "cabrones", "marica", "maricas", "maricon", "maricones", "verga", "vergas", "pene",
        "penes", "vagina", "vaginas", "culo", "culos", "polla", "pollas", "chingar", "chingada", "chingado", "chingon", "zorra", "zorras",
        "gilipollas", "capullo", "cagar", "cagada", "cagado", "mamada", "pinche", "boludo", "pelotudo", "joto", "jotos", "negrata",
        "sudaca", "retrasado", "mongolo", "sexo", "sexual", "sexy", "porno", "pornografia", "desnudo", "desnuda", "tetas", "teta",
        "pechos", "orgasmo", "erotico", "prostituta", "prostitutas", "violacion", "violador", "violar", "violada", "pedofilo",
        "incesto", "droga", "drogas", "drogado", "cocaina", "heroina", "marihuana", "mariguana", "cannabis", "metanfetamina",
        "opio", "morfina", "fentanilo", "extasis", "porro", "porros", "matar", "mata", "matado", "asesino", "asesinos", "asesinar",
        "asesinato", "masacre", "genocidio", "homicidio", "suicidio", "suicida", "tortura", "torturar", "decapitar", "degollar",
        "terrorista", "terroristas", "terrorismo", "cadaver", "cadaveres", "cuchillada", "apuñalar", "disparar"
    )

    /** Long distinctive stems matched ANYWHERE in a token (accent-folded). Verified against the whole dictionary by the tests. */
    val blockedStems: List<String> = listOf(
        "fuck", "shit", "nigger", "niggr", "nigga", "faggot", "bitch", "bastard", "asshole", "arsehole", "wanker", "motherfuck",
        "cocksuck", "dickhead", "douchebag", "whore", "slutt", "pornogr", "masturbat", "ejaculat", "clitor", "pedophil", "paedophil",
        "fellatio", "cunnilingus", "genitali", "bestialit", "incest", "molest", "murder", "suicid", "massacr", "genocid",
        "homicid", "assassin", "terroris", "decapitat", "mutilat", "cocaine", "marijuana", "methamphet", "amphetamin",
        "narcotic", "fentanyl", "oxycodon",
        "mierd", "cojon", "pendej", "maricon", "gilipoll", "hijueput", "hijoput", "pornogra", "violacion", "pedofil", "asesin",
        "masacre", "suicid", "terroris", "marihuana", "cocaina", "chingad", "chingon"
    ).map { it.trim() }

    /** Words an everyday player never wants in a children's drawing or charades bank (scary, body parts, politics, brands, adult themes). */
    val familyBannedWords: Set<String> = setOf(
        "gun", "knife", "sword", "bomb", "blood", "kill", "dead", "death", "ghost", "monster", "spider", "snake", "skull", "witch", "devil", "fight",
        "war", "vote", "flag", "president", "party", "hand", "foot", "feet", "eye", "nose", "mouth", "ear", "arm", "leg", "tooth", "teeth", "hair",
        "finger", "toe", "belly", "bottom", "head", "nike", "lego", "disney", "pepsi", "google", "ikea",
        "pistola", "cuchillo", "espada", "bomba", "sangre", "matar", "muerte", "fantasma", "monstruo", "araña", "serpiente", "bruja", "diablo",
        "guerra", "bandera", "voto", "presidente", "mano", "pie", "ojo", "nariz", "boca", "oreja", "brazo", "pierna", "diente", "pelo", "dedo",
        "barriga", "cabeza", "fiesta",
        // Adult or unkind themes and rude words (EN, then ES). Whole words only, so harmless words that merely contain them stay allowed.
        "beer", "wine", "drug", "naked", "sex", "toilet", "poop", "butt", "bum", "breast", "grave", "coffin", "hell", "hate", "gamble", "casino",
        "smoke", "shoot", "hang",
        "arma", "droga", "cerveza", "vino", "desnudo", "culo", "caca", "tumba", "infierno", "odio", "apuesta", "humo", "disparar"
    )

    /** Fragments (6+ letters, so no accidental hits inside a harmless word) that must not appear anywhere. */
    val familyBannedFragments: List<String> = listOf(
        "weapon", "murder", "zombie", "terror", "skeleton", "religio", "politic", "election", "cigarette", "alcohol", "poison",
        "asesin", "esqueleto", "religi", "politic", "elecci", "cigarr", "veneno", "cerveza", "desnud", "infiern", "disparar"
    )

    private val suffixes = listOf("ers", "ing", "es", "ed", "er", "s")

    private val exactRaw: Set<String> = (blockedWordsEn + blockedWordsEs).toSet()
    private val exactFolded: Set<String> = exactRaw.map { fold(it) }.filter { it != "cono" }.toSet()

    /** Lowercase, accent-stripped (ñ becomes n). */
    fun fold(text: String): String {
        val low = text.lowercase()
        if (low.all { it.code < 128 }) return low
        return Normalizer.normalize(low, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    private fun tokensOf(text: String): List<String> =
        Regex("\\p{L}+").findAll(text.lowercase()).map { it.value }.toList()

    private fun tokenBlocked(raw: String): Boolean {
        if (raw in exactRaw) return true
        val folded = fold(raw)
        if (folded in exactFolded) return true
        for (s in suffixes) {
            if (folded.length >= s.length + 5 && folded.endsWith(s)) {
                val base = folded.dropLast(s.length)
                if (base in exactFolded) return true
            }
        }
        for (stem in blockedStems) if (folded.contains(stem)) return true
        return false
    }

    /** True when [text] (a word, or a phrase of several words) contains a blocklisted word or stem. */
    fun isBlocked(text: String): Boolean = tokensOf(text).any { tokenBlocked(it) }

    /** True when [text] is fine to show. */
    fun isSafe(text: String): Boolean = !isBlocked(text)

    /** Order-preserving filter. */
    fun filterSafe(words: Collection<String>): List<String> = words.filter { isSafe(it) }
}
