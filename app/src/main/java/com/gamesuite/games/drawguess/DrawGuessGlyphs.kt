package com.gamesuite.games.drawguess

/**
 * The little pictures on the word cards, as data: a 100 x 100 grid, y down, a list of shapes separated by `;`.
 * Plain geometry (no emoji, no fonts), so every card looks the same on every device and a pre-reader can match
 * a card to the drawing.
 *
 * Shapes (numbers then a colour letter):
 *  - `o cx cy rx ry C`   filled ellipse          - `r x y w h C`  filled rectangle
 *  - `t x1 y1 x2 y2 x3 y3 C` filled triangle      - `p x1 y1 x2 y2 ... C` filled polygon (3+ points)
 *  - `l x1 y1 x2 y2 w C` line, round caps         - `a cx cy r start sweep w C` arc (degrees, 0 = east, clockwise)
 *  - `e cx cy rx ry w C` ring (outlined ellipse)
 * Colour letters: see [GLYPH_COLORS]. The renderer outlines every filled shape.
 */
sealed class GlyphOp {
    abstract val color: Char
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, override val color: Char) : GlyphOp()
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, override val color: Char) : GlyphOp()
    data class Poly(val xy: FloatArray, override val color: Char) : GlyphOp()
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val w: Float, override val color: Char) : GlyphOp()
    data class Arc(val cx: Float, val cy: Float, val r: Float, val start: Float, val sweep: Float, val w: Float, override val color: Char) : GlyphOp()
    data class Ring(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val w: Float, override val color: Char) : GlyphOp()
}

/** ARGB of each colour letter. A white tile sits behind every glyph, so these never depend on the app theme. */
val GLYPH_COLORS: Map<Char, Long> = mapOf(
    'k' to 0xFF2B2B2BL, 'w' to 0xFFFFFFFFL, 'r' to 0xFFD1342BL, 'o' to 0xFFF28C28L, 'y' to 0xFFF6C90EL,
    'g' to 0xFF4CAF50L, 'd' to 0xFF1E6B34L, 'b' to 0xFF2E7DD1L, 'c' to 0xFF8FD3F4L, 'p' to 0xFF8E5BC6L,
    'n' to 0xFF8B5A2BL, 't' to 0xFFD9B382L, 'e' to 0xFF9AA0A6L, 's' to 0xFFF4B9A0L, 'm' to 0xFFEFA6C8L
)

object DrawGuessGlyphs {
    /** Parses [spec]; throws IllegalArgumentException on anything malformed (the tests parse every glyph). */
    fun parse(spec: String): List<GlyphOp> = spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { stmt ->
        val tok = stmt.split(' ').filter { it.isNotEmpty() }
        require(tok.size >= 3) { "short shape: $stmt" }
        val op = tok[0]
        val colorTok = tok.last()
        require(colorTok.length == 1 && colorTok[0] in GLYPH_COLORS) { "bad colour in: $stmt" }
        val c = colorTok[0]
        val n = tok.subList(1, tok.size - 1).map { it.toFloatOrNull() ?: throw IllegalArgumentException("bad number in: $stmt") }
        fun need(count: Int) = require(n.size == count) { "expected $count numbers in: $stmt" }
        when (op) {
            "o" -> { need(4); GlyphOp.Ellipse(n[0], n[1], n[2], n[3], c) }
            "r" -> { need(4); GlyphOp.Rect(n[0], n[1], n[2], n[3], c) }
            "t" -> { need(6); GlyphOp.Poly(n.toFloatArray(), c) }
            "p" -> { require(n.size >= 6 && n.size % 2 == 0) { "polygon needs 3+ points: $stmt" }; GlyphOp.Poly(n.toFloatArray(), c) }
            "l" -> { need(5); GlyphOp.Line(n[0], n[1], n[2], n[3], n[4], c) }
            "a" -> { need(6); GlyphOp.Arc(n[0], n[1], n[2], n[3], n[4], n[5], c) }
            "e" -> { need(5); GlyphOp.Ring(n[0], n[1], n[2], n[3], n[4], c) }
            else -> throw IllegalArgumentException("unknown shape $op")
        }
    }

    private val cache = HashMap<String, List<GlyphOp>>()

    /** The parsed shapes of [key]'s glyph (cached), or an empty list for an unknown key. */
    @Synchronized
    fun ops(key: String): List<GlyphOp> = cache.getOrPut(key) { specFor(key)?.let { parse(it) } ?: emptyList() }

    fun specFor(key: String): String? = specs[key]

    val keys: Set<String> get() = specs.keys

    private val specs: Map<String, String> by lazy {
        RAW.lines().map { it.trim() }.filter { it.isNotEmpty() }.associate { line ->
            val i = line.indexOf('=')
            line.substring(0, i) to line.substring(i + 1)
        }
    }

    private const val RAW = """
cat=t 22 44 24 14 40 30 o;t 78 44 76 14 60 30 o;o 50 58 30 26 o;o 40 54 4 5 k;o 60 54 4 5 k;t 46 62 54 62 50 67 m;l 50 67 50 72 2 k;l 18 62 38 66 2 k;l 18 72 38 68 2 k;l 82 62 62 66 2 k;l 82 72 62 68 2 k
dog=o 22 52 9 20 n;o 78 52 9 20 n;o 50 55 26 26 t;o 40 50 4 5 k;o 60 50 4 5 k;o 50 66 12 9 w;o 50 62 5 4 k;l 50 66 50 72 2 k
fish=t 72 50 92 32 92 68 o;o 46 50 30 20 o;t 44 32 58 32 50 22 y;o 34 44 4 4 w;o 34 44 2 2 k;l 22 54 28 58 2 k
bird=t 22 52 6 40 30 62 c;o 46 56 26 20 b;o 70 40 12 12 b;t 80 40 94 44 80 46 o;o 73 38 3 3 w;o 73 38 1.5 1.5 k;l 40 76 40 88 3 o;l 54 76 54 88 3 o
cow=o 20 48 10 6 k;o 80 48 10 6 k;t 30 40 28 16 42 32 t;t 70 40 72 16 58 32 t;o 50 52 26 22 w;o 34 40 7 6 k;o 50 68 20 12 m;o 43 68 3 4 k;o 57 68 3 4 k;o 40 50 3.5 4 k;o 62 50 3.5 4 k
pig=t 24 34 28 16 42 28 m;t 76 34 72 16 58 28 m;o 50 54 30 28 m;o 50 62 14 10 s;o 45 62 2.5 3.5 k;o 55 62 2.5 3.5 k;o 38 48 3.5 4 k;o 62 48 3.5 4 k
horse=p 34 88 38 40 52 14 66 18 72 34 90 60 84 76 66 72 58 56 56 88 n;t 52 14 56 4 62 16 n;l 52 18 38 52 4 k;o 64 30 3 3 k;o 82 66 2.5 2.5 k
sheep=o 30 44 14 14 w;o 50 36 16 14 w;o 70 44 14 14 w;o 38 58 14 12 w;o 62 58 14 12 w;o 50 62 14 18 k;o 44 58 2.5 3 w;o 56 58 2.5 3 w
duck=t 18 56 6 44 30 62 y;o 44 62 30 20 y;o 66 38 14 14 y;t 76 38 94 42 76 48 o;o 69 35 2.5 2.5 k;l 8 88 92 88 3 c
frog=o 32 38 12 12 g;o 68 38 12 12 g;o 50 58 34 24 g;o 32 36 5 5 w;o 68 36 5 5 w;o 32 37 2.5 2.5 k;o 68 37 2.5 2.5 k;a 50 56 18 20 140 2.5 k
rabbit=o 38 22 8 22 w;o 62 22 8 22 w;o 38 24 4 16 m;o 62 24 4 16 m;o 50 62 26 24 w;o 42 58 3 4 k;o 58 58 3 4 k;o 50 66 3 2.5 m;l 50 68 50 72 2 k
mouse=o 28 36 12 12 e;o 72 36 12 12 e;o 28 36 7 7 m;o 72 36 7 7 m;o 50 58 24 24 e;o 42 54 3 4 k;o 58 54 3 4 k;o 50 70 4 3.5 m;l 20 64 36 68 1.5 k;l 80 64 64 68 1.5 k
bear=o 28 34 11 11 n;o 72 34 11 11 n;o 50 56 28 26 n;o 50 66 12 9 t;o 42 52 3 4 k;o 58 52 3 4 k;o 50 62 4 3 k
lion=o 50 54 38 38 n;o 50 56 24 24 y;o 42 52 3 4 k;o 58 52 3 4 k;t 46 62 54 62 50 67 k;l 50 67 50 72 2 k
elephant=o 28 50 18 26 e;o 56 46 24 24 e;l 62 56 70 82 9 e;l 56 62 50 76 3 w;o 60 42 3 3 k
giraffe=r 38 36 14 54 y;o 52 28 18 11 y;l 44 18 44 10 3 n;l 54 18 54 10 3 n;o 45 56 4 4 n;o 45 72 4 4 n;o 46 44 3 3 n;o 58 26 2.5 2.5 k
monkey=o 28 46 10 10 n;o 72 46 10 10 n;o 50 48 26 26 n;o 50 58 20 16 t;o 42 44 3.5 4 k;o 58 44 3.5 4 k;o 45 58 2 2 k;o 55 58 2 2 k;a 50 62 8 20 140 2 k
turtle=o 32 82 8 6 g;o 68 82 8 6 g;o 84 62 10 8 g;o 50 58 32 22 g;l 36 44 44 72 2 d;l 64 44 56 72 2 d;l 34 58 66 58 2 d;o 88 60 2 2 k
butterfly=o 32 38 18 18 p;o 68 38 18 18 p;o 34 66 14 14 m;o 66 66 14 14 m;r 47 28 6 50 k;l 48 28 38 14 2 k;l 52 28 62 14 2 k
bee=o 40 34 10 14 c;o 60 34 10 14 c;o 50 56 24 18 y;l 42 44 42 68 7 k;l 58 44 58 68 7 k;t 74 56 86 56 74 52 k
snail=o 56 80 36 8 t;o 88 66 6 12 t;l 86 56 84 44 2 k;l 92 56 96 46 2 k;o 40 56 22 22 o;a 40 56 12 0 270 3 n
penguin=o 50 56 24 34 k;o 50 64 15 24 w;o 43 38 3 3 w;o 57 38 3 3 w;o 43 38 1.5 1.5 k;o 57 38 1.5 1.5 k;t 45 44 55 44 50 50 o;o 38 88 8 4 o;o 62 88 8 4 o
owl=t 26 30 30 14 42 28 n;t 74 30 70 14 58 28 n;o 50 56 28 34 n;o 50 74 14 12 t;o 38 42 11 11 w;o 62 42 11 11 w;o 38 42 5 5 k;o 62 42 5 5 k;t 45 52 55 52 50 62 o
whale=l 40 34 40 22 3 c;l 40 22 32 16 3 c;l 40 22 48 16 3 c;t 78 52 94 36 92 62 b;o 46 56 36 22 b;o 46 64 30 10 c;o 30 50 3 3 k
crab=l 28 52 14 40 4 r;o 12 36 7 7 r;l 72 52 86 40 4 r;o 88 36 7 7 r;l 32 70 20 82 3 r;l 42 74 36 88 3 r;l 58 74 64 88 3 r;l 68 70 80 82 3 r;o 50 58 26 18 r;o 42 44 3 5 w;o 58 44 3 5 w;o 42 45 1.5 2 k;o 58 45 1.5 2 k
chicken=t 20 50 10 36 28 58 w;o 46 60 26 22 w;o 66 36 12 12 w;o 66 22 5 5 r;o 73 24 4 4 r;t 76 36 90 40 76 44 o;o 70 34 2 2 k;o 72 46 3 5 r;l 40 82 40 90 3 o;l 54 82 54 90 3 o
dolphin=l 8 90 92 90 3 c;t 48 40 56 24 62 42 b;p 12 56 36 40 62 40 82 54 94 48 90 66 70 70 38 72 b;o 24 54 2.5 2.5 k
ladybug=l 44 22 38 12 2 k;l 56 22 62 12 2 k;o 50 56 30 28 r;o 50 30 14 10 k;l 50 36 50 84 2.5 k;o 38 52 5 5 k;o 62 52 5 5 k;o 38 70 5 5 k;o 62 70 5 5 k
fox=t 20 18 18 54 44 38 o;t 80 18 82 54 56 38 o;p 18 46 50 40 82 46 50 84 o;p 30 56 50 84 70 56 50 62 w;o 50 82 4 3 k;o 40 50 3 4 k;o 60 50 3 4 k
octopus=l 30 56 22 82 5 p;l 42 60 40 88 5 p;l 58 60 60 88 5 p;l 70 56 78 82 5 p;o 50 40 26 24 p;o 42 38 4 5 w;o 58 38 4 5 w;o 42 39 2 2.5 k;o 58 39 2 2.5 k
apple=l 50 32 52 20 3 n;o 50 56 28 28 r;o 62 24 9 5 g
banana=a 50 36 40 20 120 14 y;l 20 62 16 56 6 n
pizza=p 50 90 14 24 86 24 y;r 12 20 76 9 t;o 44 44 5 5 r;o 58 40 5 5 r;o 50 62 5 5 r;o 52 30 3 3 g
cake=r 14 82 72 5 e;r 20 52 60 30 m;r 20 44 60 8 w;r 20 64 60 4 w;r 48 28 4 16 b;o 50 22 3.5 5 y
ice cream=t 36 50 64 50 50 90 t;l 40 54 52 80 1.5 n;o 50 44 17 14 m;o 50 30 12 11 s;o 50 18 4 4 r
bread=p 16 70 16 52 28 38 72 38 84 52 84 70 t;l 36 44 42 56 3 n;l 52 42 58 56 3 n;l 66 44 70 56 3 n
cheese=p 14 70 14 44 86 30 86 70 y;o 32 58 5 5 o;o 56 56 6 6 o;o 74 44 4 4 o;o 66 64 4 4 o
egg=o 50 55 36 28 w;o 50 55 14 14 y
carrot=l 40 30 34 12 4 g;l 50 30 50 8 4 g;l 60 30 66 12 4 g;t 30 30 70 30 50 90 o;l 40 44 50 46 2 r;l 44 62 56 64 2 r
tomato=o 50 58 32 28 r;o 40 36 10 5 g;o 60 36 10 5 g;r 48 24 4 12 g
grapes=l 50 22 50 30 3 n;o 62 24 10 5 g;o 34 38 9 9 p;o 50 38 9 9 p;o 66 38 9 9 p;o 42 54 9 9 p;o 58 54 9 9 p;o 50 70 9 9 p
orange=o 50 56 30 30 o;o 50 28 8 4 g;a 50 56 18 200 60 2.5 y
strawberry=p 20 36 80 36 62 84 50 90 38 84 r;p 30 36 40 24 50 34 60 24 70 36 g;o 38 50 2 2.5 y;o 52 48 2 2.5 y;o 62 58 2 2.5 y;o 44 66 2 2.5 y;o 50 78 2 2.5 y
watermelon=p 12 30 14 46 24 62 38 72 50 75 62 72 76 62 86 46 88 30 r;a 50 30 40 0 180 6 g;l 12 30 88 30 3 w;o 40 48 2 3.5 k;o 56 52 2 3.5 k;o 50 40 2 3.5 k
cookie=o 50 52 34 34 t;o 38 42 4 4 n;o 58 38 4 4 n;o 60 60 4 4 n;o 42 62 4 4 n;o 50 50 3 3 n
sandwich=r 16 28 68 12 t;r 16 42 68 6 g;r 16 48 68 8 r;r 14 56 72 8 y;r 16 66 68 12 t
pear=l 50 22 52 12 3 n;o 50 66 26 24 g;o 50 40 15 20 g;o 62 18 8 4 d
corn=t 30 90 34 46 50 70 g;t 70 90 66 46 50 70 g;o 50 56 14 34 y;l 44 30 44 76 1.5 o;l 56 30 56 76 1.5 o;l 38 46 62 46 1.5 o;l 38 60 62 60 1.5 o
lemon=t 18 54 10 50 22 58 y;t 82 54 90 50 78 58 y;o 50 54 32 22 y;o 66 34 8 4 g
cherry=l 34 70 50 22 2.5 g;l 66 66 50 22 2.5 g;o 62 20 12 5 g;o 34 72 12 12 r;o 66 68 12 12 r
pineapple=t 38 40 42 14 46 36 g;t 46 36 50 8 54 36 g;t 54 36 58 14 62 40 g;o 50 62 20 28 y;l 40 44 60 80 2 o;l 60 44 40 80 2 o;l 36 62 64 62 2 o
popcorn=o 34 38 12 12 w;o 50 32 13 13 w;o 66 38 12 12 w;p 28 50 72 50 66 90 34 90 r;l 40 50 38 90 4 w;l 60 50 62 90 4 w
milk=p 32 36 68 36 62 22 38 22 w;r 32 36 36 52 w;r 38 52 24 14 b;l 40 22 60 22 3 b
soup=l 40 44 36 30 3 e;l 60 44 64 30 3 e;p 14 52 86 52 78 72 62 84 38 84 22 72 b;o 50 52 36 6 o
donut=o 50 52 36 30 t;o 50 50 32 26 m;o 50 52 12 10 w;l 30 46 36 42 2 y;l 62 38 68 42 2 c;l 40 64 46 68 2 r;l 66 58 72 54 2 b
lollipop=l 50 56 50 92 5 e;o 50 38 26 26 m;e 50 38 8 8 3 w;e 50 38 18 18 3 w
mushroom=r 40 52 20 34 t;p 14 52 20 36 34 24 50 20 66 24 80 36 86 52 r;o 36 40 6 5 w;o 58 34 6 5 w;o 66 46 4 3 w
cupcake=p 28 56 72 56 64 90 36 90 b;l 40 58 42 88 1.5 w;l 50 58 50 88 1.5 w;l 60 58 58 88 1.5 w;o 50 48 26 18 m;o 50 34 16 12 m;o 50 22 4 4 r
potato=o 50 56 34 24 t;o 36 50 3 2 n;o 56 62 3 2 n;o 64 48 3 2 n;o 44 66 2.5 2 n
broccoli=r 44 50 12 36 d;o 38 40 14 14 g;o 62 40 14 14 g;o 50 30 16 16 g
ball=o 50 52 32 32 b;l 18 52 82 52 3 y;e 50 52 14 32 3 y
book=r 24 24 52 64 b;r 24 24 9 64 p;r 38 34 30 8 w;r 38 48 22 5 w
chair=r 30 20 8 40 n;r 30 52 44 8 n;r 30 60 8 28 n;r 66 60 8 28 n
table=r 16 40 68 8 n;r 22 48 8 40 n;r 70 48 8 40 n
bed=r 12 38 8 50 n;r 80 56 8 32 n;r 20 58 60 14 b;o 32 52 12 7 w;r 44 50 36 14 r
cup=a 70 56 13 -90 180 6 r;r 28 34 40 48 r;l 40 22 36 10 3 e;l 54 22 58 12 3 e
spoon=o 50 28 14 20 e;l 50 46 50 88 6 e
fork=l 34 14 34 40 4 e;l 50 14 50 40 4 e;l 66 14 66 40 4 e;r 30 36 40 8 e;l 50 44 50 90 6 e
clock=o 50 50 36 36 w;e 50 50 36 36 4 k;l 50 50 50 28 4 k;l 50 50 66 56 4 k;o 50 50 3 3 k
key=e 28 50 16 16 7 y;l 44 50 88 50 7 y;l 72 50 72 64 6 y;l 84 50 84 62 6 y
door=r 30 14 40 74 n;r 36 22 28 22 t;r 36 52 28 28 t;o 60 52 3 3 y
window=r 18 18 64 64 n;r 24 24 24 24 c;r 52 24 24 24 c;r 24 52 24 24 c;r 52 52 24 24 c
lamp=p 34 20 66 20 78 52 22 52 y;l 50 52 50 80 4 e;r 34 80 32 6 e
umbrella=l 50 52 50 80 4 e;a 42 80 8 0 180 4 e;p 10 52 18 34 32 22 50 18 68 22 82 34 90 52 r;l 50 18 50 52 2 w;l 30 24 28 52 2 w;l 70 24 72 52 2 w
hat=o 50 70 40 10 y;o 50 50 22 24 y;r 28 56 44 8 r
shoe=p 14 62 14 46 40 46 52 58 80 62 86 74 86 82 14 82 b;r 14 76 72 6 w;l 36 50 44 56 2 w
sock=p 38 14 66 14 66 56 88 70 88 86 64 86 38 62 r;r 38 14 28 8 w
shirt=p 36 18 64 18 86 34 76 48 66 42 66 84 34 84 34 42 24 48 14 34 b;o 50 20 8 5 w
glasses=e 30 52 18 18 5 k;e 70 52 18 18 5 k;l 48 50 52 50 4 k;l 12 50 6 44 3 k;l 88 50 94 44 3 k
bottle=p 44 14 56 14 56 36 70 48 70 88 30 88 30 48 44 36 b;r 34 62 32 14 w;r 42 8 16 6 r
bag=a 50 40 18 180 180 4 p;p 22 40 78 40 84 86 16 86 p
backpack=a 50 30 14 180 180 4 g;r 28 30 44 58 g;r 34 56 32 20 d
pencil=p 22 70 70 22 80 32 32 80 y;t 22 70 32 80 14 86 t;p 70 22 80 32 86 26 76 16 m;t 18 78 24 84 14 86 k
phone=r 32 12 36 76 k;r 36 20 28 52 c;o 50 80 4 4 w
camera=r 20 26 18 10 e;r 14 34 72 46 e;o 50 56 16 16 k;o 50 56 9 9 c;o 74 44 4 4 y
guitar=l 52 54 80 24 6 t;p 78 14 90 24 84 30 72 18 n;o 36 68 20 18 n;o 48 56 14 12 n;o 42 62 6 6 k
drum=l 20 14 44 40 4 n;l 80 14 56 40 4 n;r 18 40 64 36 r;o 50 76 32 12 r;o 50 40 32 12 w;l 22 44 40 74 2 w;l 78 44 60 74 2 w
bell=p 32 70 30 50 40 28 60 28 70 50 68 70 y;r 24 70 52 6 y;o 50 82 6 5 y;o 50 22 4 4 y
balloon=o 50 38 24 28 r;t 44 66 56 66 50 72 r;l 50 72 46 84 1.5 e;l 46 84 54 92 1.5 e;o 40 30 5 8 w
kite=p 50 10 78 40 50 70 22 40 r;l 50 10 50 70 2 w;l 22 40 78 40 2 w;l 50 70 40 86 2 e;o 40 84 4 3 b;o 34 90 4 3 y
candle=r 38 42 24 46 b;l 50 42 50 34 2 k;o 50 26 6 10 o;o 50 28 3 6 y
bucket=a 50 40 28 180 180 3 e;p 22 40 78 40 70 88 30 88 b;r 24 50 52 5 y
car=r 10 54 80 20 r;p 24 54 34 34 64 34 76 54 r;r 36 38 12 14 c;r 52 38 12 14 c;o 28 76 10 10 k;o 72 76 10 10 k;o 28 76 4 4 e;o 72 76 4 4 e
bus=r 8 28 84 50 y;r 14 36 14 14 c;r 34 36 14 14 c;r 54 36 14 14 c;r 74 36 12 20 c;o 26 80 8 8 k;o 74 80 8 8 k
train=r 20 26 12 14 k;r 10 40 56 36 b;r 66 52 24 24 b;r 14 46 14 12 c;r 34 46 14 12 c;o 22 80 8 8 k;o 46 80 8 8 k;o 78 80 8 8 k;o 24 18 6 5 e
boat=l 50 62 50 16 3 n;t 54 18 54 56 82 56 w;t 46 24 46 56 22 56 y;p 14 62 86 62 74 82 26 82 r;l 8 88 92 88 3 b
plane=p 40 50 58 50 40 80 32 80 b;p 40 50 58 50 40 20 32 20 b;p 14 50 26 50 20 34 12 34 b;o 50 50 38 9 e;o 62 48 2.5 2.5 c;o 52 48 2.5 2.5 c
bicycle=e 24 68 18 18 5 k;e 76 68 18 18 5 k;l 24 68 44 44 3.5 r;l 44 44 66 68 3.5 r;l 44 44 72 44 3.5 r;l 24 68 66 68 3.5 r;l 72 44 76 68 3.5 k;l 40 40 50 40 5 k
rocket=p 36 52 22 76 36 72 r;p 64 52 78 76 64 72 r;o 50 46 14 32 w;p 36 34 50 6 64 34 r;o 50 44 6 6 c;t 42 78 58 78 50 94 o
truck=r 10 36 50 38 b;p 60 46 78 46 90 58 90 74 60 74 r;r 66 50 14 10 c;o 26 78 9 9 k;o 74 78 9 9 k
sun=l 78 50 90 50 4 o;l 69.8 69.8 78.3 78.3 4 o;l 50 78 50 90 4 o;l 30.2 69.8 21.7 78.3 4 o;l 22 50 10 50 4 o;l 30.2 30.2 21.7 21.7 4 o;l 50 22 50 10 4 o;l 69.8 30.2 78.3 21.7 4 o;o 50 50 20 20 y
moon=p 66.7 79.6 50 84 33 79.4 20.6 67 16 50 20.6 33 33 20.6 50 16 66.7 20.4 51.7 21.8 39 30.7 32.5 44.8 32.5 55.2 39 69.3 51.7 78.2 y;o 76 38 3 3 y
star=p 50 12 60 38.2 88 39.6 66.2 57.3 73.5 84.4 50 69 26.5 84.4 33.8 57.3 12 39.6 40 38.2 y;o 50 48 6 6 o
cloud=o 34 60 16 14 c;o 52 48 20 18 c;o 68 62 16 12 c;r 34 62 36 12 c
rainbow=a 50 78 36 180 180 6 r;a 50 78 28 180 180 6 y;a 50 78 20 180 180 6 b;o 14 82 10 8 c;o 86 82 10 8 c
tree=r 44 56 12 32 n;o 50 38 26 26 g;o 36 50 14 12 g;o 64 50 14 12 g
flower=l 50 38 50 90 4 g;o 62 70 10 5 g;o 50 20 8 8 m;o 63.3 29.7 8 8 m;o 58.2 45.3 8 8 m;o 41.8 45.3 8 8 m;o 36.7 29.7 8 8 m;o 50 34 8 8 y
leaf=p 50 12 74 30 80 56 62 78 50 88 38 78 20 56 26 30 g;l 50 20 50 90 3 d;l 50 50 66 40 2 d;l 50 62 34 52 2 d
snowman=o 50 70 24 22 w;o 50 38 17 17 w;o 44 34 2.5 2.5 k;o 56 34 2.5 2.5 k;t 50 38 66 42 50 44 o;o 50 62 3 3 k;o 50 74 3 3 k;r 34 22 32 5 k;r 40 10 20 14 k
gift=r 20 42 60 46 b;r 16 32 68 14 b;r 45 32 10 56 y;o 40 26 10 7 y;o 60 26 10 7 y
house=r 22 48 56 40 t;p 16 50 50 16 84 50 r;r 44 62 14 26 n;r 30 56 10 10 c
castle=r 20 44 60 44 e;r 12 30 20 58 e;r 68 30 20 58 e;r 12 22 6 8 e;r 26 22 6 8 e;r 68 22 6 8 e;r 82 22 6 8 e;p 42 88 42 68 50 60 58 68 58 88 n
tent=t 50 20 10 86 90 86 r;t 50 52 38 86 62 86 y;l 4 88 96 88 3 g
bridge=l 6 90 94 90 3 b;r 8 46 84 8 t;a 50 90 30 180 180 8 n;l 20 46 20 34 3 n;l 80 46 80 34 3 n;l 20 34 80 34 2 n
mountain=p 6 86 40 26 94 86 e;p 50 86 72 44 96 86 g;p 40 26 31 44 40 40 49 46 w
beach=r 6 50 88 18 b;r 6 66 88 22 t;o 78 26 10 10 y;l 30 66 30 34 3 n;t 30 34 12 30 22 44 g;t 30 34 48 30 38 44 g
forest=r 27 60 6 14 n;t 30 18 14 60 46 60 d;r 65 72 6 14 n;t 68 28 52 72 84 72 d
island=l 6 90 94 90 3 b;o 50 78 38 12 t;l 50 78 50 34 4 n;t 50 34 28 28 40 46 g;t 50 34 72 28 60 46 g;t 50 34 50 16 56 34 g;o 46 48 3 3 n
farm=r 24 44 52 44 r;p 16 46 50 18 84 46 n;r 42 60 16 28 w;l 42 60 58 88 2 r;l 58 60 42 88 2 r;r 74 36 14 52 e;o 81 36 7 5 e
lighthouse=l 34 22 14 18 3 y;l 66 22 86 18 3 y;p 38 88 62 88 58 28 42 28 w;p 40 70 60 70 59 56 41 56 r;p 43 46 57 46 58 36 42 36 r;r 40 18 20 10 y;t 38 18 62 18 50 6 r
windmill=p 38 88 62 88 56 50 44 50 t;p 40 50 60 50 50 36 r;l 22 16 78 72 5 e;l 78 16 22 72 5 e;o 50 44 5 5 r
igloo=p 12 80 16 62 28 46 50 38 72 46 84 62 88 80 w;l 20 62 80 62 1.5 e;l 36 46 34 62 1.5 e;l 64 46 66 62 1.5 e;p 44 80 44 70 50 64 56 70 56 80 b;l 12 80 88 80 3 e
school=r 20 44 60 44 r;p 14 44 50 20 86 44 n;r 40 66 20 22 n;r 28 54 10 10 c;r 62 54 10 10 c;o 50 36 7 7 w;l 50 36 50 31 1.5 k;l 50 36 54 38 1.5 k;l 50 20 50 6 2 k;t 50 6 64 10 50 14 y
garden=r 6 78 88 12 n;l 24 78 24 50 3 g;o 24 44 8 8 r;l 50 78 50 40 3 g;o 50 34 8 8 y;l 76 78 76 54 3 g;o 76 48 8 8 p
jump=l 24 88 76 88 2 e;o 50 24 9 9 s;l 50 33 50 60 4 b;l 50 40 30 22 4 b;l 50 40 70 22 4 b;l 50 60 36 76 4 b;l 50 60 64 76 4 b
swim=a 25 70 10 0 180 3 b;a 45 70 10 0 180 3 b;a 65 70 10 0 180 3 b;a 85 70 10 0 180 3 b;o 30 50 9 9 s;l 40 58 60 52 4 b;l 60 52 76 40 4 b
sleep=r 14 62 72 14 b;o 28 52 10 10 s;r 38 56 44 12 m;l 60 28 72 28 3 e;l 72 28 60 40 3 e;l 60 40 72 40 3 e
eat=o 42 52 28 28 s;o 34 46 3 4 k;o 50 46 3 4 k;o 42 64 9 6 k;l 66 40 88 62 4 e;o 90 66 8 5 e
run=l 8 60 20 60 2 e;l 4 70 14 70 2 e;o 58 22 9 9 s;l 54 31 44 58 4 b;l 52 38 70 48 4 b;l 52 38 34 46 4 b;l 44 58 64 72 4 b;l 64 72 62 88 4 b;l 44 58 28 70 4 b;l 28 70 14 66 4 b
dance=o 50 24 9 9 s;l 50 33 50 60 4 m;l 50 40 28 30 4 m;l 50 40 72 52 4 m;l 50 60 38 86 4 m;l 50 60 66 78 4 m;o 82 24 5 4 k;l 86 24 86 10 2.5 k;l 86 10 94 14 2.5 k
sing=o 36 50 24 24 s;o 30 44 3 4 k;o 44 44 3 4 k;o 38 60 6 7 k;o 70 36 5 4 k;l 75 36 75 18 2.5 k;l 75 18 84 22 2.5 k;o 80 62 5 4 k;l 85 62 85 46 2.5 k
read=p 50 30 50 80 14 74 14 24 w;p 50 30 50 80 86 74 86 24 w;l 50 30 50 80 2 k;l 22 38 42 42 2 e;l 22 50 42 54 2 e;l 58 42 78 38 2 e;l 58 54 78 50 2 e;l 14 84 86 84 4 b
write=r 12 28 40 56 w;l 18 40 46 40 2 e;l 18 52 46 52 2 e;l 18 64 36 64 2 e;p 40 60 76 24 84 32 48 68 y;t 40 60 48 68 34 74 k;p 76 24 84 32 90 26 82 18 m
laugh=o 50 50 34 34 y;a 38 44 6 200 140 3 k;a 62 44 6 200 140 3 k;p 30 58 70 58 62 74 50 78 38 74 k;o 50 72 8 4 r;o 24 54 3 5 c;o 76 54 3 5 c
climb=l 30 10 30 90 4 n;l 62 10 62 90 4 n;l 30 24 62 24 3 n;l 30 44 62 44 3 n;l 30 64 62 64 3 n;l 30 84 62 84 3 n;o 46 26 8 8 s;l 46 34 46 56 4 b;l 46 40 30 30 4 b;l 46 40 62 28 4 b;l 46 56 32 70 4 b;l 46 56 62 66 4 b
fly=l 10 56 30 36 4 k;l 30 36 50 54 4 k;l 50 54 70 36 4 k;l 70 36 90 56 4 k;o 24 78 12 8 c;o 40 80 10 7 c
drink=p 30 28 70 28 62 88 38 88 c;p 33 48 67 48 62 88 38 88 b;l 54 12 50 60 4 r
wave=o 46 28 9 9 s;l 46 37 46 66 4 b;l 46 44 66 30 4 b;l 46 44 30 58 4 b;l 46 66 34 88 4 b;l 46 66 58 88 4 b;a 74 26 10 -60 120 2.5 e;a 74 26 16 -60 120 2.5 e
paint=o 46 56 36 28 t;o 32 50 5 5 r;o 46 42 5 5 y;o 62 46 5 5 b;o 64 64 5 5 g;o 34 66 6 6 w;l 56 62 88 20 5 n;p 84 14 92 22 88 26 80 18 k
cook=l 36 36 32 22 3 e;l 50 36 54 20 3 e;l 64 36 60 24 3 e;r 24 48 52 34 e;r 18 44 64 6 e;l 12 56 24 56 4 e;l 88 56 76 56 4 e;o 36 90 5 5 o;o 50 90 5 5 o;o 64 90 5 5 o
hug=o 36 28 8 8 s;o 64 28 8 8 s;l 36 66 30 88 4 b;l 36 66 44 88 4 b;l 64 66 56 88 4 r;l 64 66 72 88 4 r;l 36 36 36 66 4 b;l 64 36 64 66 4 r;l 36 46 66 48 4 b;l 64 46 34 48 4 r;o 46 12 5 5 r;o 54 12 5 5 r;t 41 14 59 14 50 24 r
clap=o 50 22 9 9 s;l 50 31 50 62 4 b;l 50 40 42 54 4 b;l 50 40 58 54 4 b;l 50 62 40 86 4 b;l 50 62 60 86 4 b;l 36 40 26 34 2.5 o;l 64 40 74 34 2.5 o;l 34 50 22 50 2.5 o;l 66 50 78 50 2.5 o;l 40 30 34 20 2.5 o;l 60 30 66 20 2.5 o
skate=o 50 18 8 8 s;l 50 26 50 46 4 b;l 50 32 34 40 4 b;l 50 32 66 40 4 b;l 50 46 40 62 4 b;l 50 46 60 62 4 b;r 12 66 76 8 o;o 28 80 8 8 k;o 72 80 8 8 k
walk=o 14 88 4 2 e;o 26 88 4 2 e;o 50 22 9 9 s;l 50 31 50 58 4 b;l 50 38 40 54 4 b;l 50 38 62 52 4 b;l 50 58 40 86 4 b;l 50 58 64 84 4 b;l 64 84 70 84 4 b
sit=r 20 30 6 36 n;r 20 60 40 6 n;r 20 66 6 22 n;r 54 66 6 22 n;o 44 28 8 8 s;l 44 36 44 60 4 b;l 44 60 66 60 4 b;l 66 60 66 86 4 b;l 44 44 58 56 4 b
fishing=l 8 70 92 70 3 b;l 18 12 62 44 4 n;l 62 44 62 78 1.5 e;o 62 82 10 6 o;t 70 82 80 76 80 88 o
build=r 20 72 28 16 r;r 52 72 28 16 b;r 36 56 28 16 y;r 40 40 20 16 g
sweep=o 74 84 3 3 e;o 82 80 2 2 e;l 62 12 44 66 4 n;p 36 62 56 70 52 90 28 84 y
swing=l 14 86 30 14 3 n;l 86 86 70 14 3 n;l 30 14 70 14 4 n;l 42 14 42 62 2 e;l 58 14 58 62 2 e;r 36 62 28 6 r;o 50 40 7 7 s;l 50 47 50 62 4 b
bounce=l 30 88 70 88 3 e;l 50 60 50 66 3 e;l 50 72 50 76 3 e;o 50 36 16 16 r
"""
}
