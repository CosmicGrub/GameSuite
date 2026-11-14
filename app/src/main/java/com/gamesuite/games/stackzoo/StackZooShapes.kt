package com.gamesuite.games.stackzoo

/** Largest vertex count of one convex part. */
internal const val ZOO_MAX_VERTS = 8

/**
 * The physics view of one animal (or of the log): every part as flat arrays in the body frame, whose origin is the
 * centre of mass. Everything is derived once from the authored [AnimalSpec] (no randomness, no clock) and then never
 * changes, so the world only ever reads it.
 *
 * Layout: vertex `k` of part `p` is at index `p * ZOO_MAX_VERTS + k`. Edge `k` runs from vertex `k` to vertex
 * `(k + 1) % count`; [nx]/[ny] hold its outward unit normal (counter-clockwise parts).
 */
internal class ZooShape(
    val spec: AnimalSpec?,
    parts: List<ZooPart>,
    authoredMass: Double,
    val friction: Double
) {
    val partCount: Int = parts.size
    val vertCount = IntArray(ZooPhysics.MAX_PARTS)
    val vx = DoubleArray(ZooPhysics.MAX_PARTS * ZOO_MAX_VERTS)
    val vy = DoubleArray(ZooPhysics.MAX_PARTS * ZOO_MAX_VERTS)
    val nx = DoubleArray(ZooPhysics.MAX_PARTS * ZOO_MAX_VERTS)
    val ny = DoubleArray(ZooPhysics.MAX_PARTS * ZOO_MAX_VERTS)

    val mass: Double = authoredMass
    val invMass: Double = 1.0 / authoredMass

    /** Moment of inertia about the centre of mass, scaled to the authored mass. */
    val inertia: Double
    val invInertia: Double

    /** Centre of mass in the authored frame (the origin the vertex tables use). */
    val comX: Double
    val comY: Double
    val area: Double

    /** Largest distance from the centre of mass to any vertex. */
    val radius: Double

    /** Convex hull of the whole animal, counter-clockwise, in the body frame. */
    val hullX: DoubleArray
    val hullY: DoubleArray

    /** Body angles that lay a long hull edge flat on the bottom, with that edge's length (parallel arrays). */
    val flatAngles: DoubleArray
    val flatLengths: DoubleArray

    /** Silhouette box in the body frame. */
    val minX: Double
    val maxX: Double
    val minY: Double
    val maxY: Double

    init {
        require(parts.size in 1..ZooPhysics.MAX_PARTS) { "1 to ${ZooPhysics.MAX_PARTS} parts" }
        var totalArea = 0.0
        var cx = 0.0
        var cy = 0.0
        val areas = DoubleArray(parts.size)
        // Pass 1: area and centroid of each part in the authored frame.
        for ((p, part) in parts.withIndex()) {
            val n = part.vertices.size
            require(n in 3..ZOO_MAX_VERTS) { "3 to $ZOO_MAX_VERTS vertices" }
            var a2 = 0.0
            var sx = 0.0
            var sy = 0.0
            for (k in 0 until n) {
                val p0 = part.vertices[k]
                val p1 = part.vertices[(k + 1) % n]
                val cr = p0.x * p1.y - p1.x * p0.y
                a2 += cr
                sx += (p0.x + p1.x) * cr
                sy += (p0.y + p1.y) * cr
            }
            val a = a2 * 0.5
            require(a > 0.0) { "parts are counter-clockwise" }
            areas[p] = a
            totalArea += a
            cx += sx / (6.0 * a) * a
            cy += sy / (6.0 * a) * a
        }
        comX = cx / totalArea
        comY = cy / totalArea
        area = totalArea
        // Pass 2: store vertices shifted to the centre of mass, normals, polar moment about the origin.
        var polar = 0.0
        var maxR = 0.0
        var mnX = Double.MAX_VALUE
        var mxX = -Double.MAX_VALUE
        var mnY = Double.MAX_VALUE
        var mxY = -Double.MAX_VALUE
        for ((p, part) in parts.withIndex()) {
            val n = part.vertices.size
            vertCount[p] = n
            for (k in 0 until n) {
                val v = part.vertices[k]
                val x = v.x - comX
                val y = v.y - comY
                vx[p * ZOO_MAX_VERTS + k] = x
                vy[p * ZOO_MAX_VERTS + k] = y
                val r = Math.sqrt(x * x + y * y)
                if (r > maxR) maxR = r
                if (x < mnX) mnX = x
                if (x > mxX) mxX = x
                if (y < mnY) mnY = y
                if (y > mxY) mxY = y
            }
            for (k in 0 until n) {
                val i0 = p * ZOO_MAX_VERTS + k
                val i1 = p * ZOO_MAX_VERTS + (k + 1) % n
                val ex = vx[i1] - vx[i0]
                val ey = vy[i1] - vy[i0]
                val len = Math.sqrt(ex * ex + ey * ey)
                nx[i0] = ey / len
                ny[i0] = -ex / len
                val cr = vx[i0] * vy[i1] - vx[i1] * vy[i0]
                polar += cr * (vx[i0] * vx[i0] + vx[i0] * vx[i1] + vx[i1] * vx[i1] + vy[i0] * vy[i0] + vy[i0] * vy[i1] + vy[i1] * vy[i1]) / 12.0
            }
        }
        radius = maxR
        minX = mnX
        maxX = mxX
        minY = mnY
        maxY = mxY
        inertia = authoredMass * polar / totalArea
        invInertia = 1.0 / inertia
        // Hull of every vertex.
        val pts = ArrayList<DoubleArray>()
        for (p in 0 until partCount) for (k in 0 until vertCount[p]) pts += doubleArrayOf(vx[p * ZOO_MAX_VERTS + k], vy[p * ZOO_MAX_VERTS + k])
        val hull = convexHull(pts)
        hullX = DoubleArray(hull.size) { hull[it][0] }
        hullY = DoubleArray(hull.size) { hull[it][1] }
        val fa = ArrayList<Double>()
        val fl = ArrayList<Double>()
        for (i in hull.indices) {
            val j = (i + 1) % hull.size
            val ex = hullX[j] - hullX[i]
            val ey = hullY[j] - hullY[i]
            val len = Math.sqrt(ex * ex + ey * ey)
            if (len >= ZooPhysics.FLAT_EDGE_MIN) {
                fa += wrapAngle(-StrictMath.atan2(ey, ex))
                fl += len
            }
        }
        flatAngles = fa.toDoubleArray()
        flatLengths = fl.toDoubleArray()
    }

    /** Lowest point of the animal turned by [angle] (negative or zero: the COM sits above it). */
    fun lowestOffset(angle: Double): Double {
        val c = StrictMath.cos(angle)
        val s = StrictMath.sin(angle)
        var m = Double.MAX_VALUE
        for (i in hullX.indices) {
            val y = s * hullX[i] + c * hullY[i]
            if (y < m) m = y
        }
        return m
    }

    companion object {
        private fun convexHull(points: List<DoubleArray>): List<DoubleArray> {
            val sorted = points.sortedWith(compareBy<DoubleArray>({ it[0] }, { it[1] }))
            val uniq = ArrayList<DoubleArray>()
            for (p in sorted) if (uniq.isEmpty() || uniq.last()[0] != p[0] || uniq.last()[1] != p[1]) uniq += p
            if (uniq.size < 3) return uniq
            fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
            val lower = ArrayList<DoubleArray>()
            for (p in uniq) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 1e-12) lower.removeAt(lower.size - 1)
                lower += p
            }
            val upper = ArrayList<DoubleArray>()
            for (p in uniq.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 1e-12) upper.removeAt(upper.size - 1)
                upper += p
            }
            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }

        /** Wraps an angle into (-pi, pi]. */
        fun wrapAngle(a: Double): Double {
            var r = a
            while (r > Math.PI) r -= 2.0 * Math.PI
            while (r <= -Math.PI) r += 2.0 * Math.PI
            return r
        }
    }
}

/** The derived shapes of the 20 animals plus the log, built once. */
internal object ZooShapes {
    val animals: List<ZooShape> by lazy { ZooAnimals.all.map { ZooShape(it, it.parts, it.mass, it.friction) } }

    /** The rocking plank: a 6 x 0.4 box around its own centre; mass is unused (it never translates). */
    val log: ZooShape by lazy {
        val hw = ZooPhysics.BASE_WIDTH / 2.0
        val hh = ZooPhysics.BASE_THICKNESS / 2.0
        ZooShape(
            null,
            listOf(ZooPart(listOf(ZooVertex(-hw, -hh), ZooVertex(hw, -hh), ZooVertex(hw, hh), ZooVertex(-hw, hh)))),
            1.0, ZooPhysics.BASE_FRICTION
        )
    }
}
