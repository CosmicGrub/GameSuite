package com.gamesuite.games.stackzoo

/**
 * The tunable numbers of the rocking log. The defaults come from [ZooPhysics]; the M0 harness builds other instances to
 * sweep them. A world reads these every step, so changing one changes every replay (bump [ZooPhysics.PHYSICS_VERSION]).
 */
class ZooTuning(
    var stiffness: Double = ZooPhysics.BASE_STIFFNESS,
    var damping: Double = ZooPhysics.BASE_DAMPING,
    var keel: Double = ZooPhysics.BASE_KEEL,
    var dampLoad: Double = ZooPhysics.BASE_DAMP_LOAD,
    var logFriction: Double = ZooPhysics.BASE_FRICTION,
    /** Added to every animal's friction (Relaxed), capped at 1.0 per body. */
    var frictionBonus: Double = 0.0,
    var velocityIterations: Int = ZooPhysics.VELOCITY_ITERATIONS,
    var positionIterations: Int = ZooPhysics.POSITION_ITERATIONS
) {
    fun copy() = ZooTuning(stiffness, damping, keel, dampLoad, logFriction, frictionBonus, velocityIterations, positionIterations)

    companion object {
        fun normal() = ZooTuning()
        fun relaxed() = ZooTuning(
            stiffness = ZooPhysics.RELAXED_STIFFNESS, damping = ZooPhysics.RELAXED_DAMPING,
            keel = ZooPhysics.RELAXED_KEEL, dampLoad = ZooPhysics.RELAXED_DAMP_LOAD,
            frictionBonus = ZooPhysics.RELAXED_FRICTION_BONUS
        )
    }
}

/**
 * The rigid-body world of Stack Zoo: up to [ZooPhysics.MAX_BODIES] animals (slot = piece id) on a spring-restored log.
 * Pure Kotlin, [Double] only, trig only through [StrictMath], no clock, no random, no hash iteration, so the same inputs
 * give the same bits on every device. Nothing is allocated inside [step] once the world exists.
 *
 * Slots: `0 until MAX_BODIES` are animals, [LOG] is the plank, [CAND] is a scratch body for placement tests.
 */
internal class ZooWorld(initialTuning: ZooTuning = ZooTuning.normal()) {
    companion object {
        const val LOG = ZooPhysics.MAX_BODIES
        const val CAND = LOG + 1
        const val SLOTS = LOG + 2
        private const val MP = ZooPhysics.MAX_PARTS
        private const val MV = ZOO_MAX_VERTS
        private const val MC = ZooPhysics.MAX_CONTACTS
        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L
    }

    var tuning: ZooTuning = initialTuning
        set(v) {
            field = v
            fric[LOG] = v.logFriction
        }

    var stepIndex = 0L

    val x = DoubleArray(SLOTS)
    val y = DoubleArray(SLOTS)
    val a = DoubleArray(SLOTS)
    val vx = DoubleArray(SLOTS)
    val vy = DoubleArray(SLOTS)
    val w = DoubleArray(SLOTS)
    val invM = DoubleArray(SLOTS)
    val invI = DoubleArray(SLOTS)
    val mass = DoubleArray(SLOTS)
    val live = BooleanArray(SLOTS)
    val fallen = BooleanArray(SLOTS)
    val animal = IntArray(SLOTS) { -1 }
    val owner = IntArray(SLOTS)
    val fric = DoubleArray(SLOTS)
    val rest = IntArray(SLOTS)
    var logRest = ZooPhysics.SLEEP_STEPS
        private set

    /** Every live body and the log are quiet. */
    var asleep = true
        private set

    /** Every non-fallen body and the log have been quiet for [ZooPhysics.SLEEP_STEPS] steps (the "settled" test). */
    var restingAll = true
        private set

    /** Pieces flagged fallen since the last [clearFallEvents], in the order they fell. */
    val fellEvents = IntArray(ZooPhysics.MAX_BODIES)
    var fellCount = 0
        private set

    private val shape = arrayOfNulls<ZooShape>(SLOTS)

    // ---- per-step caches
    private val cs = DoubleArray(SLOTS)
    private val sn = DoubleArray(SLOTS)
    private val csAngle = DoubleArray(SLOTS) { Double.NaN }
    private val pCount = IntArray(SLOTS)
    private val vCount = IntArray(SLOTS * MP)
    private val wvx = DoubleArray(SLOTS * MP * MV)
    private val wvy = DoubleArray(SLOTS * MP * MV)
    private val wnx = DoubleArray(SLOTS * MP * MV)
    private val wny = DoubleArray(SLOTS * MP * MV)
    private val pMinX = DoubleArray(SLOTS * MP)
    private val pMaxX = DoubleArray(SLOTS * MP)
    private val pMinY = DoubleArray(SLOTS * MP)
    private val pMaxY = DoubleArray(SLOTS * MP)
    private val bMinX = DoubleArray(SLOTS)
    private val bMaxX = DoubleArray(SLOTS)
    private val bMinY = DoubleArray(SLOTS)
    private val bMaxY = DoubleArray(SLOTS)

    // ---- contacts
    private var cc = 0
    private val cA = IntArray(MC)
    private val cB = IntArray(MC)
    private val cKey = LongArray(MC)
    private val cX = DoubleArray(MC)
    private val cY = DoubleArray(MC)
    private val cNx = DoubleArray(MC)
    private val cNy = DoubleArray(MC)
    private val cSep = DoubleArray(MC)
    private val cMu = DoubleArray(MC)
    private val cPn = DoubleArray(MC)
    private val cPt = DoubleArray(MC)
    private val cMassN = DoubleArray(MC)
    private val cMassT = DoubleArray(MC)
    private val cBias = DoubleArray(MC)
    private val cRax = DoubleArray(MC)
    private val cRay = DoubleArray(MC)
    private val cRbx = DoubleArray(MC)
    private val cRby = DoubleArray(MC)
    private val cSepNow = DoubleArray(MC)
    private var contactsSorted = true

    private var pcc = 0
    private val pKey = LongArray(MC)
    private val pPn = DoubleArray(MC)
    private val pPt = DoubleArray(MC)
    private var prevSorted = true

    private val dispX = DoubleArray(SLOTS)
    private val dispY = DoubleArray(SLOTS)
    private var sepEdge = 0
    private var hacc = 0L

    init {
        x[LOG] = 0.0
        y[LOG] = ZooPhysics.PIVOT_Y
        live[LOG] = true
        shape[LOG] = ZooShapes.log
        invM[LOG] = 0.0
        invI[LOG] = 1.0 / ZooPhysics.BASE_INERTIA
        mass[LOG] = 0.0
        fric[LOG] = tuning.logFriction
        pCount[LOG] = 1
        vCount[LOG * MP] = 4
        transform(LOG)
    }

    val contactCount: Int get() = cc

    // ---- bodies -----------------------------------------------------------------------------------------------

    /** Puts piece [slot] into the world at rest-with-zero-velocity; the log and anything touching wake up. */
    fun addBody(slot: Int, animalIndex: Int, ownerSeat: Int, px: Double, py: Double, angle: Double, friction: Double) {
        require(slot in 0 until ZooPhysics.MAX_BODIES) { "slot" }
        setBody(slot, animalIndex, ownerSeat, px, py, angle, 0.0, 0.0, 0.0, 0, false, friction)
    }

    fun setBody(
        slot: Int, animalIndex: Int, ownerSeat: Int, px: Double, py: Double, angle: Double,
        pvx: Double, pvy: Double, pw: Double, restSteps: Int, isFallen: Boolean, friction: Double
    ) {
        val sh = ZooShapes.animals[animalIndex]
        shape[slot] = sh
        animal[slot] = animalIndex
        owner[slot] = ownerSeat
        x[slot] = px; y[slot] = py; a[slot] = angle
        vx[slot] = pvx; vy[slot] = pvy; w[slot] = pw
        invM[slot] = sh.invMass
        invI[slot] = sh.invInertia
        mass[slot] = sh.mass
        fric[slot] = friction
        live[slot] = true
        fallen[slot] = isFallen
        rest[slot] = restSteps
        pCount[slot] = sh.partCount
        for (p in 0 until sh.partCount) vCount[slot * MP + p] = sh.vertCount[p]
        csAngle[slot] = Double.NaN
        transform(slot)
        wake()
    }

    fun removeBody(slot: Int) {
        live[slot] = false
        fallen[slot] = false
        animal[slot] = -1
        wake()
    }

    fun wake() {
        asleep = false
        restingAll = false
    }

    fun clearFallEvents() { fellCount = 0 }

    /** Empties the world and puts the log back to rest at angle zero. */
    fun reset() {
        for (i in 0 until ZooPhysics.MAX_BODIES) removeBody(i)
        a[LOG] = 0.0
        w[LOG] = 0.0
        csAngle[LOG] = Double.NaN
        transform(LOG)
        stepIndex = 0L
        logRest = ZooPhysics.SLEEP_STEPS
        asleep = true
        restingAll = true
        fellCount = 0
        pcc = 0
        cc = 0
    }

    fun liveCount(): Int {
        var n = 0
        for (i in 0 until ZooPhysics.MAX_BODIES) if (live[i] && !fallen[i]) n++
        return n
    }

    /** Sets the log pose directly (restore). */
    fun setLog(angle: Double, omega: Double, restSteps: Int) {
        a[LOG] = angle
        w[LOG] = omega
        logRest = restSteps
        csAngle[LOG] = Double.NaN
        transform(LOG)
        wake()
    }

    /** Recomputes the cached world polygons of every live body (after a restore or an external change). */
    fun refresh() {
        for (i in 0..LOG) if (live[i]) transform(i)
    }

    fun copyFrom(o: ZooWorld) {
        tuning = o.tuning.copy()
        stepIndex = o.stepIndex
        System.arraycopy(o.x, 0, x, 0, SLOTS)
        System.arraycopy(o.y, 0, y, 0, SLOTS)
        System.arraycopy(o.a, 0, a, 0, SLOTS)
        System.arraycopy(o.vx, 0, vx, 0, SLOTS)
        System.arraycopy(o.vy, 0, vy, 0, SLOTS)
        System.arraycopy(o.w, 0, w, 0, SLOTS)
        System.arraycopy(o.invM, 0, invM, 0, SLOTS)
        System.arraycopy(o.invI, 0, invI, 0, SLOTS)
        System.arraycopy(o.mass, 0, mass, 0, SLOTS)
        System.arraycopy(o.live, 0, live, 0, SLOTS)
        System.arraycopy(o.fallen, 0, fallen, 0, SLOTS)
        System.arraycopy(o.animal, 0, animal, 0, SLOTS)
        System.arraycopy(o.owner, 0, owner, 0, SLOTS)
        System.arraycopy(o.fric, 0, fric, 0, SLOTS)
        System.arraycopy(o.rest, 0, rest, 0, SLOTS)
        System.arraycopy(o.shape, 0, shape, 0, SLOTS)
        System.arraycopy(o.pCount, 0, pCount, 0, SLOTS)
        System.arraycopy(o.vCount, 0, vCount, 0, SLOTS * MP)
        logRest = o.logRest
        asleep = o.asleep
        restingAll = o.restingAll
        pcc = o.pcc
        prevSorted = o.prevSorted
        System.arraycopy(o.pKey, 0, pKey, 0, MC)
        System.arraycopy(o.pPn, 0, pPn, 0, MC)
        System.arraycopy(o.pPt, 0, pPt, 0, MC)
        fellCount = 0
        java.util.Arrays.fill(csAngle, Double.NaN)
        refresh()
    }

    // ---- geometry ---------------------------------------------------------------------------------------------

    private fun transform(i: Int) {
        val sh = shape[i] ?: return
        val ang = a[i]
        if (ang != csAngle[i]) {
            cs[i] = StrictMath.cos(ang)
            sn[i] = StrictMath.sin(ang)
            csAngle[i] = ang
        }
        val c = cs[i]
        val s = sn[i]
        val px = x[i]
        val py = y[i]
        var bx0 = Double.MAX_VALUE
        var bx1 = -Double.MAX_VALUE
        var by0 = Double.MAX_VALUE
        var by1 = -Double.MAX_VALUE
        for (p in 0 until sh.partCount) {
            val base = (i * MP + p) * MV
            val sbase = p * MV
            val n = sh.vertCount[p]
            var x0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE
            var y0 = Double.MAX_VALUE
            var y1 = -Double.MAX_VALUE
            for (k in 0 until n) {
                val lx = sh.vx[sbase + k]
                val ly = sh.vy[sbase + k]
                val wx = px + c * lx - s * ly
                val wy = py + s * lx + c * ly
                wvx[base + k] = wx
                wvy[base + k] = wy
                val mx = sh.nx[sbase + k]
                val my = sh.ny[sbase + k]
                wnx[base + k] = c * mx - s * my
                wny[base + k] = s * mx + c * my
                if (wx < x0) x0 = wx
                if (wx > x1) x1 = wx
                if (wy < y0) y0 = wy
                if (wy > y1) y1 = wy
            }
            pMinX[i * MP + p] = x0
            pMaxX[i * MP + p] = x1
            pMinY[i * MP + p] = y0
            pMaxY[i * MP + p] = y1
            if (x0 < bx0) bx0 = x0
            if (x1 > bx1) bx1 = x1
            if (y0 < by0) by0 = y0
            if (y1 > by1) by1 = y1
        }
        bMinX[i] = bx0
        bMaxX[i] = bx1
        bMinY[i] = by0
        bMaxY[i] = by1
    }

    /** Max separation of polygon B from the edges of polygon A (negative: penetration). Sets [sepEdge]. */
    private fun maxSep(baseA: Int, nA: Int, baseB: Int, nB: Int, stopAbove: Double): Double {
        var best = -1e30
        var bestEdge = 0
        for (i in 0 until nA) {
            val nxi = wnx[baseA + i]
            val nyi = wny[baseA + i]
            val vxi = wvx[baseA + i]
            val vyi = wvy[baseA + i]
            var si = 1e30
            for (j in 0 until nB) {
                val s = nxi * (wvx[baseB + j] - vxi) + nyi * (wvy[baseB + j] - vyi)
                if (s < si) si = s
            }
            if (si > best) {
                best = si
                bestEdge = i
                if (best > stopAbove) break
            }
        }
        sepEdge = bestEdge
        return best
    }

    private fun addContact(ia: Int, ib: Int, pa: Int, pb: Int, px: Double, py: Double, nx: Double, ny: Double, sep: Double, feat: Int) {
        var idx = cc
        if (cc >= MC) {
            var far = 0
            for (k in 1 until MC) if (cSep[k] > cSep[far]) far = k
            if (cSep[far] <= sep) return
            idx = far
            contactsSorted = false
        } else {
            cc++
        }
        cA[idx] = ia
        cB[idx] = ib
        cKey[idx] = ((((ia.toLong() * 64L + ib) * 4L + pa) * 4L + pb) * 256L) + feat
        cX[idx] = px
        cY[idx] = py
        cNx[idx] = nx
        cNy[idx] = ny
        cSep[idx] = sep
    }

    private fun collidePoly(ia: Int, pa: Int, ib: Int, pb: Int) {
        val ai = ia * MP + pa
        val bi = ib * MP + pb
        val m = ZooPhysics.CONTACT_MARGIN
        if (pMinX[ai] > pMaxX[bi] + m || pMinX[bi] > pMaxX[ai] + m || pMinY[ai] > pMaxY[bi] + m || pMinY[bi] > pMaxY[ai] + m) return
        val baseA = ai * MV
        val baseB = bi * MV
        val nA = vCount[ai]
        val nB = vCount[bi]
        val sA = maxSep(baseA, nA, baseB, nB, m)
        if (sA > m) return
        val eA = sepEdge
        val sB = maxSep(baseB, nB, baseA, nA, m)
        if (sB > m) return
        val eB = sepEdge
        val flip = sB > sA + 0.0005
        val refBase: Int
        val refN: Int
        val refEdge: Int
        val incBase: Int
        val incN: Int
        if (!flip) {
            refBase = baseA; refN = nA; refEdge = eA; incBase = baseB; incN = nB
        } else {
            refBase = baseB; refN = nB; refEdge = eB; incBase = baseA; incN = nA
        }
        val rnx = wnx[refBase + refEdge]
        val rny = wny[refBase + refEdge]
        var inc = 0
        var minDot = 1e30
        for (k in 0 until incN) {
            val d = rnx * wnx[incBase + k] + rny * wny[incBase + k]
            if (d < minDot) {
                minDot = d
                inc = k
            }
        }
        val i2 = if (inc + 1 == incN) 0 else inc + 1
        var c0x = wvx[incBase + inc]
        var c0y = wvy[incBase + inc]
        var c1x = wvx[incBase + i2]
        var c1y = wvy[incBase + i2]
        val v12i = if (refEdge + 1 == refN) 0 else refEdge + 1
        val v11x = wvx[refBase + refEdge]
        val v11y = wvy[refBase + refEdge]
        val v12x = wvx[refBase + v12i]
        val v12y = wvy[refBase + v12i]
        val tx = -rny
        val ty = rnx
        // plane 1: keep tangent coordinate >= v11's
        run {
            val t11 = tx * v11x + ty * v11y
            val d0 = t11 - (tx * c0x + ty * c0y)
            val d1 = t11 - (tx * c1x + ty * c1y)
            if (d0 > 0.0 && d1 > 0.0) return
            val o0x = c0x; val o0y = c0y; val o1x = c1x; val o1y = c1y
            if (d0 > 0.0) {
                val f = d0 / (d0 - d1)
                c0x = o0x + f * (o1x - o0x); c0y = o0y + f * (o1y - o0y)
            } else if (d1 > 0.0) {
                val f = d1 / (d1 - d0)
                c1x = o1x + f * (o0x - o1x); c1y = o1y + f * (o0y - o1y)
            }
        }
        // plane 2: keep tangent coordinate <= v12's
        run {
            val t12 = tx * v12x + ty * v12y
            val d0 = (tx * c0x + ty * c0y) - t12
            val d1 = (tx * c1x + ty * c1y) - t12
            if (d0 > 0.0 && d1 > 0.0) return
            val o0x = c0x; val o0y = c0y; val o1x = c1x; val o1y = c1y
            if (d0 > 0.0) {
                val f = d0 / (d0 - d1)
                c0x = o0x + f * (o1x - o0x); c0y = o0y + f * (o1y - o0y)
            } else if (d1 > 0.0) {
                val f = d1 / (d1 - d0)
                c1x = o1x + f * (o0x - o1x); c1y = o1y + f * (o0y - o1y)
            }
        }
        val nx = if (flip) -rnx else rnx
        val ny = if (flip) -rny else rny
        val flipBit = if (flip) 1 else 0
        val featBase = flipBit or (refEdge shl 1) or (inc shl 4)
        val s0 = rnx * (c0x - v11x) + rny * (c0y - v11y)
        if (s0 <= m) addContact(ia, ib, pa, pb, c0x - 0.5 * s0 * rnx, c0y - 0.5 * s0 * rny, nx, ny, s0, featBase)
        val s1 = rnx * (c1x - v11x) + rny * (c1y - v11y)
        if (s1 <= m) addContact(ia, ib, pa, pb, c1x - 0.5 * s1 * rnx, c1y - 0.5 * s1 * rny, nx, ny, s1, featBase or 128)
    }

    private fun buildContacts() {
        cc = 0
        contactsSorted = true
        for (i in 0 until LOG) {
            if (!live[i] || fallen[i]) continue
            for (j in i + 1..LOG) {
                if (!live[j] || fallen[j]) continue
                val m = ZooPhysics.CONTACT_MARGIN
                if (bMinX[i] > bMaxX[j] + m || bMinX[j] > bMaxX[i] + m || bMinY[i] > bMaxY[j] + m || bMinY[j] > bMaxY[i] + m) continue
                for (pa in 0 until pCount[i]) for (pb in 0 until pCount[j]) collidePoly(i, pa, j, pb)
            }
        }
    }

    private fun findPrev(key: Long): Int {
        if (prevSorted) {
            var lo = 0
            var hi = pcc - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val k = pKey[mid]
                if (k < key) lo = mid + 1 else if (k > key) hi = mid - 1 else return mid
            }
            return -1
        }
        for (k in 0 until pcc) if (pKey[k] == key) return k
        return -1
    }

    // ---- the step ---------------------------------------------------------------------------------------------

    fun advance(steps: Int) {
        for (k in 0 until steps) step()
    }

    /** The load sum S = sum of mass * height above the pivot over standing animals. */
    fun loadSum(): Double {
        var s = 0.0
        for (i in 0 until ZooPhysics.MAX_BODIES) {
            if (!live[i] || fallen[i]) continue
            val dy = y[i] - ZooPhysics.PIVOT_Y
            if (dy > 0.0) s += mass[i] * dy
        }
        return s
    }

    fun step() {
        stepIndex++
        if (asleep) return
        val dt = ZooPhysics.DT
        val t = tuning
        // 2. velocities
        val load = loadSum()
        val th = a[LOG]
        val om = w[LOG]
        val torque = -(t.stiffness + t.keel * ZooPhysics.GRAVITY * load) * th - (t.damping + t.dampLoad * load) * om
        w[LOG] = om + torque * invI[LOG] * dt
        val gdt = ZooPhysics.GRAVITY * dt
        for (i in 0 until LOG) if (live[i]) vy[i] -= gdt
        // 3-4. geometry and contacts
        for (i in 0..LOG) if (live[i]) transform(i)
        buildContacts()
        // 5-6. constraints and warm start
        for (c in 0 until cc) {
            val ia = cA[c]
            val ib = cB[c]
            val nx = cNx[c]
            val ny = cNy[c]
            val rax = cX[c] - x[ia]
            val ray = cY[c] - y[ia]
            val rbx = cX[c] - x[ib]
            val rby = cY[c] - y[ib]
            cRax[c] = rax; cRay[c] = ray; cRbx[c] = rbx; cRby[c] = rby
            val rnA = rax * ny - ray * nx
            val rnB = rbx * ny - rby * nx
            val kN = invM[ia] + invM[ib] + invI[ia] * rnA * rnA + invI[ib] * rnB * rnB
            cMassN[c] = if (kN > 0.0) 1.0 / kN else 0.0
            val tx = ny
            val ty = -nx
            val rtA = rax * ty - ray * tx
            val rtB = rbx * ty - rby * tx
            val kT = invM[ia] + invM[ib] + invI[ia] * rtA * rtA + invI[ib] * rtB * rtB
            cMassT[c] = if (kT > 0.0) 1.0 / kT else 0.0
            cMu[c] = StrictMath.sqrt(fric[ia] * fric[ib])
            cBias[c] = if (cSep[c] > 0.0) cSep[c] / dt else 0.0
            val pi = findPrev(cKey[c])
            var pn = 0.0
            var pt = 0.0
            if (pi >= 0) {
                pn = pPn[pi]
                pt = pPt[pi]
            }
            cPn[c] = pn
            cPt[c] = pt
            val px = pn * nx + pt * tx
            val py = pn * ny + pt * ty
            vx[ia] -= invM[ia] * px
            vy[ia] -= invM[ia] * py
            w[ia] -= invI[ia] * (rax * py - ray * px)
            vx[ib] += invM[ib] * px
            vy[ib] += invM[ib] * py
            w[ib] += invI[ib] * (rbx * py - rby * px)
        }
        // 7. velocity iterations
        for (it in 0 until t.velocityIterations) {
            for (c in 0 until cc) {
                val ia = cA[c]
                val ib = cB[c]
                val nx = cNx[c]
                val ny = cNy[c]
                val tx = ny
                val ty = -nx
                val rax = cRax[c]
                val ray = cRay[c]
                val rbx = cRbx[c]
                val rby = cRby[c]
                // friction
                var dvx = vx[ib] - w[ib] * rby - vx[ia] + w[ia] * ray
                var dvy = vy[ib] + w[ib] * rbx - vy[ia] - w[ia] * rax
                val vt = dvx * tx + dvy * ty
                var dPt = -cMassT[c] * vt
                val maxPt = cMu[c] * cPn[c]
                var newPt = cPt[c] + dPt
                if (newPt > maxPt) newPt = maxPt else if (newPt < -maxPt) newPt = -maxPt
                dPt = newPt - cPt[c]
                cPt[c] = newPt
                var px = dPt * tx
                var py = dPt * ty
                vx[ia] -= invM[ia] * px
                vy[ia] -= invM[ia] * py
                w[ia] -= invI[ia] * (rax * py - ray * px)
                vx[ib] += invM[ib] * px
                vy[ib] += invM[ib] * py
                w[ib] += invI[ib] * (rbx * py - rby * px)
                // normal
                dvx = vx[ib] - w[ib] * rby - vx[ia] + w[ia] * ray
                dvy = vy[ib] + w[ib] * rbx - vy[ia] - w[ia] * rax
                val vn = dvx * nx + dvy * ny
                var dPn = -cMassN[c] * (vn + cBias[c])
                var newPn = cPn[c] + dPn
                if (newPn < 0.0) newPn = 0.0
                dPn = newPn - cPn[c]
                cPn[c] = newPn
                px = dPn * nx
                py = dPn * ny
                vx[ia] -= invM[ia] * px
                vy[ia] -= invM[ia] * py
                w[ia] -= invI[ia] * (rax * py - ray * px)
                vx[ib] += invM[ib] * px
                vy[ib] += invM[ib] * py
                w[ib] += invI[ib] * (rbx * py - rby * px)
            }
        }
        // 8. log stops
        val maxA = ZooPhysics.BASE_MAX_ANGLE
        if ((a[LOG] >= maxA && w[LOG] > 0.0) || (a[LOG] <= -maxA && w[LOG] < 0.0)) w[LOG] = 0.0
        // predicted separation after the solve
        for (c in 0 until cc) {
            val ia = cA[c]
            val ib = cB[c]
            val dvx = vx[ib] - w[ib] * cRby[c] - vx[ia] + w[ia] * cRay[c]
            val dvy = vy[ib] + w[ib] * cRbx[c] - vy[ia] - w[ia] * cRax[c]
            cSepNow[c] = cSep[c] + dt * (dvx * cNx[c] + dvy * cNy[c])
        }
        // save the cache for the next step
        pcc = cc
        prevSorted = contactsSorted
        for (c in 0 until cc) {
            pKey[c] = cKey[c]
            pPn[c] = cPn[c]
            pPt[c] = cPt[c]
        }
        // 9. positions
        for (i in 0 until LOG) {
            if (!live[i]) continue
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            a[i] += w[i] * dt
            dispX[i] = 0.0
            dispY[i] = 0.0
        }
        dispX[LOG] = 0.0
        dispY[LOG] = 0.0
        var na = a[LOG] + w[LOG] * dt
        if (na > maxA) na = maxA else if (na < -maxA) na = -maxA
        a[LOG] = na
        // 10. position iterations
        val slop = ZooPhysics.LINEAR_SLOP
        for (it in 0 until t.positionIterations) {
            for (c in 0 until cc) {
                val ia = cA[c]
                val ib = cB[c]
                val nx = cNx[c]
                val ny = cNy[c]
                val sep = cSepNow[c] + (dispX[ib] - dispX[ia]) * nx + (dispY[ib] - dispY[ia]) * ny
                val pen = -sep
                if (pen > slop) {
                    var corr = ZooPhysics.BAUMGARTE * (pen - slop)
                    if (corr > 0.2) corr = 0.2
                    val total = invM[ia] + invM[ib]
                    if (total > 0.0) {
                        val fa = invM[ia] / total * corr
                        val fb = invM[ib] / total * corr
                        x[ia] -= fa * nx; y[ia] -= fa * ny
                        dispX[ia] -= fa * nx; dispY[ia] -= fa * ny
                        x[ib] += fb * nx; y[ib] += fb * ny
                        dispX[ib] += fb * nx; dispY[ib] += fb * ny
                    }
                }
            }
        }
        // 11. sleep bookkeeping
        var allRest = true
        var anyFallen = false
        val sl2 = ZooPhysics.SLEEP_LINEAR * ZooPhysics.SLEEP_LINEAR
        val cap = ZooPhysics.SLEEP_STEPS * 2
        for (i in 0 until LOG) {
            if (!live[i]) continue
            if (fallen[i]) {
                anyFallen = true
                continue
            }
            val sp2 = vx[i] * vx[i] + vy[i] * vy[i]
            if (sp2 < sl2 && Math.abs(w[i]) < ZooPhysics.SLEEP_ANGULAR) {
                if (rest[i] < cap) rest[i]++
            } else {
                rest[i] = 0
            }
            if (rest[i] < ZooPhysics.SLEEP_STEPS) allRest = false
        }
        if (Math.abs(w[LOG]) < ZooPhysics.LOG_SLEEP_SPIN) {
            if (logRest < cap) logRest++
        } else {
            logRest = 0
        }
        if (logRest < ZooPhysics.SLEEP_STEPS) allRest = false
        // 12. fall check
        for (i in 0 until LOG) {
            if (!live[i]) continue
            val bad = !(x[i] == x[i] && y[i] == y[i] && a[i] == a[i]) || Math.abs(x[i]) > 60.0
            if (bad) {
                if (!fallen[i] && fellCount < fellEvents.size) fellEvents[fellCount++] = i
                live[i] = false
                fallen[i] = false
                continue
            }
            if (!fallen[i] && y[i] < ZooPhysics.FALL_Y) {
                fallen[i] = true
                if (fellCount < fellEvents.size) fellEvents[fellCount++] = i
                anyFallen = true
            }
            if (fallen[i] && y[i] < ZooPhysics.REMOVE_Y) {
                live[i] = false
                fallen[i] = false
                animal[i] = -1
                // keep the fall recorded; the slot is free again only after the engine reads fellEvents
            }
        }
        restingAll = allRest
        if (allRest && !anyFallen && !anyFallenLive()) {
            asleep = true
            for (i in 0 until LOG) {
                vx[i] = 0.0; vy[i] = 0.0; w[i] = 0.0
            }
            w[LOG] = 0.0
        }
    }

    private fun anyFallenLive(): Boolean {
        for (i in 0 until LOG) if (live[i] && fallen[i]) return true
        return false
    }

    // ---- measures ---------------------------------------------------------------------------------------------

    /** Highest vertex of any standing animal above the log's top surface, in the log's own frame (0 when empty). */
    fun towerHeight(): Double {
        val c = StrictMath.cos(a[LOG])
        val s = StrictMath.sin(a[LOG])
        var best = 0.0
        for (i in 0 until LOG) {
            if (!live[i] || fallen[i]) continue
            transform(i)
            for (p in 0 until pCount[i]) {
                val base = (i * MP + p) * MV
                for (k in 0 until vCount[i * MP + p]) {
                    val dx = wvx[base + k]
                    val dy = wvy[base + k] - ZooPhysics.PIVOT_Y
                    val ly = -s * dx + c * dy
                    val h = ly - ZooPhysics.BASE_THICKNESS / 2.0
                    if (h > best) best = h
                }
            }
        }
        return best
    }

    /** Highest world y of any vertex of a standing animal or of the log. */
    private fun highestPoint(): Double {
        refresh()
        var top = bMaxY[LOG]
        for (i in 0 until LOG) if (live[i] && !fallen[i] && bMaxY[i] > top) top = bMaxY[i]
        return top
    }

    private fun overlapsAny(): Boolean {
        val c = CAND
        val m = 0.0
        for (j in 0..LOG) {
            if (!live[j] || (j < LOG && fallen[j])) continue
            if (bMinX[c] > bMaxX[j] + m || bMinX[j] > bMaxX[c] + m || bMinY[c] > bMaxY[j] + m || bMinY[j] > bMaxY[c] + m) continue
            for (pa in 0 until pCount[c]) for (pb in 0 until pCount[j]) {
                val ai = c * MP + pa
                val bi = j * MP + pb
                if (pMinX[ai] > pMaxX[bi] || pMinX[bi] > pMaxX[ai] || pMinY[ai] > pMaxY[bi] || pMinY[bi] > pMaxY[ai]) continue
                val sA = maxSep(ai * MV, vCount[ai], bi * MV, vCount[bi], 0.0)
                if (sA >= -1e-9) continue
                val sB = maxSep(bi * MV, vCount[bi], ai * MV, vCount[ai], 0.0)
                if (sB >= -1e-9) continue
                return true
            }
        }
        return false
    }

    private fun placeCandidate(animalIndex: Int, px: Double, py: Double, angle: Double) {
        val sh = ZooShapes.animals[animalIndex]
        if (shape[CAND] !== sh) {
            shape[CAND] = sh
            pCount[CAND] = sh.partCount
            for (p in 0 until sh.partCount) vCount[CAND * MP + p] = sh.vertCount[p]
        }
        x[CAND] = px
        y[CAND] = py
        a[CAND] = angle
        transform(CAND)
    }

    /**
     * The hand-set-down height: lowers the animal from above everything, 0.1 u at a time, until it first overlaps the log or
     * a standing animal, bisects to 0.01 u and returns the centre-of-mass y that leaves a [ZooPhysics.PLACE_GAP] gap.
     */
    fun sweepDown(animalIndex: Int, px: Double, angle: Double): Double {
        val sh = ZooShapes.animals[animalIndex]
        val low = sh.lowestOffset(angle)
        var yb = highestPoint() + ZooPhysics.SWEEP_START
        val floorB = -0.8
        var guard = 0
        placeCandidate(animalIndex, px, yb - low, angle)
        while (overlapsAny() && guard < 200) {
            yb += ZooPhysics.SWEEP_STEP
            placeCandidate(animalIndex, px, yb - low, angle)
            guard++
        }
        var yFree = yb
        var yOver: Double
        while (true) {
            val next = yFree - ZooPhysics.SWEEP_STEP
            if (next < floorB) return floorB - low
            placeCandidate(animalIndex, px, next - low, angle)
            if (overlapsAny()) {
                yOver = next
                break
            }
            yFree = next
        }
        while (yFree - yOver > ZooPhysics.SWEEP_TOLERANCE) {
            val mid = 0.5 * (yFree + yOver)
            placeCandidate(animalIndex, px, mid - low, angle)
            if (overlapsAny()) yOver = mid else yFree = mid
        }
        return yFree + ZooPhysics.PLACE_GAP - low
    }

    /** True when the animal at ([px], [py], [angle]) overlaps the log or a standing animal (for tests). */
    fun overlapsAt(animalIndex: Int, px: Double, py: Double, angle: Double): Boolean {
        refresh()
        placeCandidate(animalIndex, px, py, angle)
        return overlapsAny()
    }

    /** Deepest penetration over the current contacts (0 when none), recomputed from the live poses. */
    fun maxPenetration(): Double {
        for (i in 0..LOG) if (live[i]) transform(i)
        buildContacts()
        var worst = 0.0
        for (c in 0 until cc) if (-cSep[c] > worst) worst = -cSep[c]
        return worst
    }

    // ---- hash -------------------------------------------------------------------------------------------------

    private fun mix(v: Int) {
        var h = hacc
        var vv = v
        for (k in 0 until 4) {
            h = (h xor (vv and 0xff).toLong()) * FNV_PRIME
            vv = vv ushr 8
        }
        hacc = h
    }

    private fun quant(v: Double): Int {
        val q = StrictMath.floor(v * ZooPhysics.QUANT + 0.5)
        return if (q > 2.0e9) 2_000_000_000 else if (q < -2.0e9) -2_000_000_000 else q.toInt()
    }

    /** FNV-1a 64 over the quantised pose of every standing body in id order, the log pose and the step index. */
    fun stateHash(): Long {
        hacc = FNV_OFFSET
        for (i in 0 until LOG) {
            if (!live[i] || fallen[i]) continue
            mix(i)
            mix(quant(x[i]))
            mix(quant(y[i]))
            mix(quant(a[i]))
        }
        mix(quant(a[LOG]))
        mix(quant(w[LOG]))
        mix(stepIndex.toInt())
        mix((stepIndex ushr 32).toInt())
        return hacc
    }

    /** Raw IEEE bits of every body pose, for the bit-identical test. */
    fun poseBits(out: LongArray): Int {
        var n = 0
        for (i in 0..LOG) {
            if (!live[i]) continue
            out[n++] = i.toLong()
            out[n++] = java.lang.Double.doubleToRawLongBits(x[i])
            out[n++] = java.lang.Double.doubleToRawLongBits(y[i])
            out[n++] = java.lang.Double.doubleToRawLongBits(a[i])
            out[n++] = java.lang.Double.doubleToRawLongBits(vx[i])
            out[n++] = java.lang.Double.doubleToRawLongBits(vy[i])
            out[n++] = java.lang.Double.doubleToRawLongBits(w[i])
        }
        return n
    }
}
