package com.gamesuite.games.drawguess

import java.text.Normalizer
import kotlin.random.Random

/** The language the word cards and typed guesses use. The screen picks it from the device locale. */
enum class WordLang { EN, ES;
    companion object {
        /** "es" (any region) is Spanish, everything else English. */
        fun fromLanguageTag(tag: String?): WordLang = if (tag != null && tag.lowercase().startsWith("es")) ES else EN
    }
}

enum class WordCategory { ANIMAL, FOOD, OBJECT, PLACE, ACTION }

/**
 * One drawable word. [key] is the English name and the stable identity used in snapshots (so a
 * reordered bank can never mix words up). [difficulty] is 1 (a few shapes) to 3 (needs a clever
 * picture); [glyph] is the picture spec the cards draw (see [DrawGuessGlyphs]).
 */
data class DrawWord(
    val key: String,
    val en: String,
    val es: String,
    val category: WordCategory,
    val difficulty: Int,
    val glyph: String
) {
    fun text(lang: WordLang): String = if (lang == WordLang.ES) es else en
}

/** The kid-safe word bank and the pure rules built on it: choices for the drawer, multiple-choice cards, typed-guess matching. */
object DrawGuessWordBank {
    /** `english|spanish|category|difficulty`; A animal, F food, O object, P place, X action. */
    private const val RAW = """
cat|gato|A|1
dog|perro|A|1
fish|pez|A|1
bird|pájaro|A|1
cow|vaca|A|2
pig|cerdo|A|2
horse|caballo|A|3
sheep|oveja|A|2
duck|pato|A|2
frog|rana|A|2
rabbit|conejo|A|2
mouse|ratón|A|2
bear|oso|A|2
lion|león|A|2
elephant|elefante|A|2
giraffe|jirafa|A|3
monkey|mono|A|3
turtle|tortuga|A|2
butterfly|mariposa|A|2
bee|abeja|A|2
snail|caracol|A|2
penguin|pingüino|A|3
owl|búho|A|3
whale|ballena|A|2
crab|cangrejo|A|3
chicken|gallina|A|3
dolphin|delfín|A|3
ladybug|mariquita|A|2
fox|zorro|A|3
octopus|pulpo|A|3
apple|manzana|F|1
banana|plátano|F|1
pizza|pizza|F|2
cake|pastel|F|2
ice cream|helado|F|2
bread|pan|F|2
cheese|queso|F|2
egg|huevo|F|1
carrot|zanahoria|F|1
tomato|tomate|F|1
grapes|uvas|F|2
orange|naranja|F|1
strawberry|fresa|F|2
watermelon|sandía|F|2
cookie|galleta|F|1
sandwich|sándwich|F|2
pear|pera|F|1
corn|maíz|F|2
lemon|limón|F|1
cherry|cereza|F|1
pineapple|piña|F|3
popcorn|palomitas|F|3
milk|leche|F|2
soup|sopa|F|3
donut|rosquilla|F|2
lollipop|piruleta|F|2
mushroom|seta|F|2
cupcake|magdalena|F|2
potato|patata|F|2
broccoli|brócoli|F|3
ball|pelota|O|1
book|libro|O|1
chair|silla|O|2
table|mesa|O|2
bed|cama|O|2
cup|taza|O|1
spoon|cuchara|O|1
fork|tenedor|O|1
clock|reloj|O|1
key|llave|O|1
door|puerta|O|1
window|ventana|O|1
lamp|lámpara|O|2
umbrella|paraguas|O|2
hat|sombrero|O|1
shoe|zapato|O|2
sock|calcetín|O|2
shirt|camiseta|O|2
glasses|gafas|O|2
bottle|botella|O|2
bag|bolso|O|2
backpack|mochila|O|2
pencil|lápiz|O|1
phone|teléfono|O|1
camera|cámara|O|2
guitar|guitarra|O|3
drum|tambor|O|2
bell|campana|O|1
balloon|globo|O|1
kite|cometa|O|2
candle|vela|O|1
bucket|cubo|O|2
car|coche|O|1
bus|autobús|O|2
train|tren|O|2
boat|barco|O|2
plane|avión|O|3
bicycle|bicicleta|O|3
rocket|cohete|O|2
truck|camión|O|2
sun|sol|O|1
moon|luna|O|1
star|estrella|O|1
cloud|nube|O|1
rainbow|arcoíris|O|2
tree|árbol|O|1
flower|flor|O|1
leaf|hoja|O|1
snowman|muñeco de nieve|O|2
gift|regalo|O|2
house|casa|P|1
castle|castillo|P|2
tent|tienda de campaña|P|2
bridge|puente|P|3
mountain|montaña|P|2
beach|playa|P|3
forest|bosque|P|3
island|isla|P|3
farm|granja|P|3
lighthouse|faro|P|3
windmill|molino|P|3
igloo|iglú|P|3
school|escuela|P|2
garden|jardín|P|3
jump|saltar|X|2
swim|nadar|X|2
sleep|dormir|X|1
eat|comer|X|1
run|correr|X|2
dance|bailar|X|2
sing|cantar|X|2
read|leer|X|1
write|escribir|X|2
laugh|reír|X|2
climb|trepar|X|3
fly|volar|X|3
drink|beber|X|1
wave|saludar|X|2
paint|pintar|X|2
cook|cocinar|X|3
hug|abrazar|X|2
clap|aplaudir|X|3
skate|patinar|X|3
walk|caminar|X|1
sit|sentarse|X|1
fishing|pescar|X|3
build|construir|X|3
sweep|barrer|X|3
swing|columpiarse|X|3
bounce|rebotar|X|3
"""

    private fun categoryOf(c: String): WordCategory = when (c) {
        "A" -> WordCategory.ANIMAL
        "F" -> WordCategory.FOOD
        "O" -> WordCategory.OBJECT
        "P" -> WordCategory.PLACE
        "X" -> WordCategory.ACTION
        else -> error("unknown category $c")
    }

    /** Every word, in bank order. A word without a glyph spec is a programming error caught by the tests, not at runtime. */
    val all: List<DrawWord> by lazy {
        RAW.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val p = line.split('|')
            require(p.size == 4) { "bad word row: $line" }
            DrawWord(
                key = p[0], en = p[0], es = p[1], category = categoryOf(p[2]),
                difficulty = p[3].toInt(), glyph = DrawGuessGlyphs.specFor(p[0]) ?: ""
            )
        }
    }

    private val byKeyMap: Map<String, DrawWord> by lazy { all.associateBy { it.key } }

    fun byKey(key: String): DrawWord? = byKeyMap[key]

    // ---- the drawer's three choices --------------------------------------------------------

    /**
     * Three different words for the drawer, easy first: one of each difficulty when the unused pool has them, and
     * different categories when that can be had in a few tries. [used] are the keys already drawn this game; when
     * fewer than three words are left the used list is ignored (a long game never runs dry).
     */
    fun choices(random: Random, used: Collection<String>, count: Int = 3): List<DrawWord> {
        var pool = all.filter { it.key !in used }
        if (pool.size < count + 3) pool = all
        var best: List<DrawWord> = emptyList()
        repeat(12) {
            val picked = ArrayList<DrawWord>(count)
            for (level in 1..count) {
                val tier = pool.filter { it.difficulty == level.coerceAtMost(3) && picked.none { p -> p.key == it.key } }
                val from = if (tier.isNotEmpty()) tier else pool.filter { p -> picked.none { it.key == p.key } }
                picked += from[random.nextInt(from.size)]
            }
            if (best.isEmpty()) best = picked
            if (picked.map { it.category }.toSet().size == picked.size) return picked.sortedBy { it.difficulty }
        }
        return best.sortedBy { it.difficulty }
    }

    // ---- guessers' cards --------------------------------------------------------------------

    /**
     * [size] distinct word cards that always include [answer], at a random position. At least three of the others
     * share the answer's category when the bank has that many (so the picture has to be read, not guessed by topic).
     */
    fun multipleChoice(answer: DrawWord, random: Random, size: Int = 6): List<DrawWord> {
        require(size >= 2)
        val others = all.filter { it.key != answer.key }
        val same = others.filter { it.category == answer.category }.shuffled(random)
        val rest = others.filter { it.category != answer.category }.shuffled(random)
        val picked = ArrayList<DrawWord>(size)
        picked += same.take(minOf(3, size - 1))
        for (w in rest) { if (picked.size >= size - 1) break; picked += w }
        for (w in same.drop(3)) { if (picked.size >= size - 1) break; picked += w }
        picked += answer
        return picked.shuffled(random)
    }

    // ---- typed guesses ----------------------------------------------------------------------

    private val articles = setOf("a", "an", "the", "to", "el", "la", "los", "las", "un", "una")

    /** Lower-case, accents and punctuation removed, a leading article dropped ("The Cat" and "gato" both work). */
    fun normalize(raw: String): String {
        val folded = Normalizer.normalize(raw.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val tokens = folded.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val trimmed = if (tokens.size > 1 && tokens.first() in articles) tokens.drop(1) else tokens
        return trimmed.joinToString("")
    }

    /**
     * Other accepted names per word [key] (either language): everyday synonyms, irregular plurals, and Latin American
     * Spanish where the bank's Spanish leans to Spain. Each goes through the same plural / verb-form rules as the main names.
     */
    private val aliases: Map<String, List<String>> = mapOf(
        "mouse" to listOf("mice"),
        "rabbit" to listOf("bunny"), "bicycle" to listOf("bike", "bici"), "plane" to listOf("airplane", "aeroplane"),
        "phone" to listOf("telephone", "cellphone", "mobile", "celular", "móvil"), "donut" to listOf("doughnut", "dona", "dónut"),
        "cookie" to listOf("biscuit"), "glasses" to listOf("glass", "eyeglasses", "spectacles", "lentes", "anteojos"),
        "backpack" to listOf("rucksack"), "house" to listOf("home"), "shirt" to listOf("t-shirt", "playera", "remera"),
        "ladybug" to listOf("ladybird", "catarina", "vaquita"), "chicken" to listOf("hen"),
        "car" to listOf("auto", "carro", "automóvil"), "bus" to listOf("colectivo", "guagua", "micro"),
        "potato" to listOf("papa"), "cake" to listOf("torta", "tarta"), "strawberry" to listOf("frutilla"),
        "mushroom" to listOf("hongo", "champiñón"), "banana" to listOf("banano"), "bag" to listOf("bolsa", "cartera"),
        "kite" to listOf("papalote", "barrilete"), "bucket" to listOf("balde"), "lollipop" to listOf("paleta", "chupetín"),
        "sock" to listOf("media"), "popcorn" to listOf("pochoclo", "canchita", "cabritas"),
        "tent" to listOf("carpa", "tienda"), "ball" to listOf("bola", "balón"), "bird" to listOf("ave"),
        "pig" to listOf("puerco", "chancho", "cochino", "marrano"), "tomato" to listOf("jitomate"),
        "school" to listOf("colegio"), "pineapple" to listOf("anana", "ananá"), "corn" to listOf("elote", "choclo"),
        "laugh" to listOf("riendo", "laughing"), "read" to listOf("leyendo"), "build" to listOf("construyendo"),
        "sleep" to listOf("durmiendo")
    )

    private fun isVowel(c: Char) = c in "aeiou"

    /** The spellings a correct guess may take for one normalised [name]: itself, plurals, a singular, and for actions the -ing / -ando / -iendo forms. */
    private fun forms(name: String, action: Boolean): Set<String> {
        val out = HashSet<String>()
        out += name
        out += name + "s"
        out += name + "es"
        if (name.length >= 3 && name.endsWith("y") && !isVowel(name[name.length - 2])) out += name.dropLast(1) + "ies"
        if (name.endsWith("z")) out += name.dropLast(1) + "ces"
        // A trailing s may be dropped, but only from a long enough word and never from -ss / -ses / -us / -is endings:
        // "grapes" -> "grape", yet "bus" never accepts "bu", nor "glasses" "glasse".
        if (name.length >= 4 && name.endsWith("s") && !name.endsWith("ss") && !name.endsWith("ses") && !name.endsWith("us") && !name.endsWith("is")) {
            out += name.dropLast(1)
        }
        if (action) {
            val bases = ArrayList<String>()
            bases += name
            if (name.length >= 6 && name.endsWith("se")) { bases += name.dropLast(2); out += name.dropLast(2) } // sentarse -> sentar
            for (b in bases) {
                out += b + "ing"
                if (b.endsWith("e") && !b.endsWith("ee")) out += b.dropLast(1) + "ing"
                if (b.length >= 3) {
                    val l = b[b.length - 1]
                    if (!isVowel(l) && l !in "wxy" && isVowel(b[b.length - 2]) && !isVowel(b[b.length - 3])) out += b + l + "ing" // run -> running
                }
                if (b.length >= 4 && b.endsWith("ar")) out += b.dropLast(2) + "ando"
                if (b.length >= 4 && (b.endsWith("er") || b.endsWith("ir"))) out += b.dropLast(2) + "iendo"
            }
        }
        return out
    }

    /**
     * True when [typed] names [word] in either language, ignoring case, accents, spaces, articles, plurals
     * (-s, -es, -ies), action forms (jumping, swimming, saltando, sentar for sentarse) and listed aliases.
     * A guess under 3 letters never matches, and a word under 4 letters is matched only by its exact name or a longer plural,
     * so "bu" is not "bus".
     */
    fun matches(word: DrawWord, typed: String): Boolean {
        val t = normalize(typed)
        if (t.length < 3 && !(t.length >= 2 && listOf(word.en, word.es).any { normalize(it) == t })) return false
        val action = word.category == WordCategory.ACTION
        val names = listOf(word.en, word.es) + (aliases[word.key] ?: emptyList())
        return names.any { raw ->
            val a = normalize(raw)
            // forms() never cuts letters off a word under 4 letters, so a short word needs its exact name or a longer form.
            t == a || t in forms(a, action)
        }
    }

    // ---- kid safety -------------------------------------------------------------------------

    /** Whole words that must never be in the bank (EN and ES): scary, violent, body parts, politics, brands. */
    val bannedWords: Set<String> = setOf(
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
    val bannedFragments: List<String> = listOf(
        "weapon", "murder", "zombie", "terror", "skeleton", "religio", "politic", "election", "cigarette", "alcohol", "poison",
        "asesin", "esqueleto", "religi", "politic", "elecci", "cigarr", "veneno", "cerveza", "desnud", "infiern", "disparar"
    )
}
