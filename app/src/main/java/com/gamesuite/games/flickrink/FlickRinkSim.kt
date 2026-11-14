package com.gamesuite.games.flickrink

import kotlin.math.sqrt

/**
 * The Flick Rink physics: a fixed-step (120 Hz) simulation of circles on an icy rink. Pure Kotlin, Double math only, no
 * pow/exp/trig inside the step loop, no randomness: the same bodies and the same velocity always give the same trajectory.
 *
 * One flick is run to rest in one go ([runFlick]); the screen plays the recorded frames back, so the display refresh rate can
 * never change an outcome. [beginFlick] / [step] / [finishFlick] are the same loop opened up (tests drive it from a fake render loop).
 *
 * Bodies are the puck pals (fish run, one per seat) or the bullseye discs (in throw order; a disc takes part once it is
 * [alive]). Step order, fixed for determinism: 1 integrate (damping, Coulomb, position), 2 bodies vs wall segments then posts,
 * 3 body vs body in index pairs, 4 puddles, pads, fish pick-up (and Relaxed banking), 5 rest check.
 *
 * Nothing inside [step] allocates except the rare [FlickEvent] objects; the arrays are made once per rink.
 */
internal class FlickSim(
    val level: RinkLevel,
    val bodyCount: Int,
    private val fishRun: Boolean,
    private val relaxed: Boolean,
    private val coop: Boolean
) {
    val bodyRadius: Double = if (fishRun) FlickRinkRules.PAL_R else FlickRinkRules.DISC_R
    private val n = bodyCount

    // ---- static geometry (never changes during a rink) ----
    private val nPosts = level.posts.size
    private val postData = DoubleArray(nPosts * 3).also { a -> level.posts.forEachIndexed { i, p -> a[i * 3] = p.x.toDouble(); a[i * 3 + 1] = p.y.toDouble(); a[i * 3 + 2] = p.r.toDouble() } }
    private val nDrifts = level.slow.size
    private val driftData = DoubleArray(nDrifts * 4).also { a ->
        level.slow.forEachIndexed { i, r -> a[i * 4] = r.x.toDouble(); a[i * 4 + 1] = r.y.toDouble(); a[i * 4 + 2] = (r.x + r.w).toDouble(); a[i * 4 + 3] = (r.y + r.h).toDouble() }
    }
    private val nPuddles = if (fishRun) level.puddles.size else 0
    private val puddleData = DoubleArray(nPuddles * 3).also { a -> for (i in 0 until nPuddles) { val p = level.puddles[i]; a[i * 3] = p.x.toDouble(); a[i * 3 + 1] = p.y.toDouble(); a[i * 3 + 2] = p.r + FlickRinkRules.SPLASH_MARGIN } }
    val nGates = level.gates.size
    private val padData = DoubleArray(nGates * 3).also { a -> level.gates.forEachIndexed { i, g -> a[i * 3] = g.pad.x.toDouble(); a[i * 3 + 1] = g.pad.y.toDouble(); a[i * 3 + 2] = g.pad.r.toDouble() } }
    val nFish = if (fishRun) level.fish.size else 0
    private val fishX = DoubleArray(nFish) { level.fish[it].x.toDouble() }
    private val fishY = DoubleArray(nFish) { level.fish[it].y.toDouble() }
    private val nSliders = level.sliders.size
    private val maxSegs = 4 + level.walls.size + nGates + nSliders
    private val segs = DoubleArray(maxSegs * 4)
    private var segCount = 0
    private val pickR: Double = if (relaxed) FlickRinkRules.PICKUP_R_RELAXED else FlickRinkRules.PICKUP_R
    private val homeR: Double = (if (relaxed) FlickRinkRules.HOME_R_RELAXED else FlickRinkRules.HOME_R).toDouble()
    private val wallE: Double = if (relaxed) FlickRinkRules.E_WALL_RELAXED else FlickRinkRules.E_WALL

    // ---- dynamic state ----
    val x = DoubleArray(n)
    val y = DoubleArray(n)
    val vx = DoubleArray(n)
    val vy = DoubleArray(n)
    val startX = DoubleArray(n)
    val startY = DoubleArray(n)
    val alive = BooleanArray(n)
    private val frozen = BooleanArray(n)
    /** The fish each body carries, or -1. */
    val carry = IntArray(n) { -1 }
    /** 0 free, 1 carried, 2 banked. */
    val fishState = IntArray(nFish)
    val fishCarrier = IntArray(nFish) { -1 }
    val bankedBy = IntArray(nFish) { -1 }
    val gateOpen = BooleanArray(nGates)
    val sliderPose = IntArray(nSliders)
    private val padInside = BooleanArray(n * nGates)

    var stepNo = 0
        private set
    private var restCount = 0
    /** Wall and post contacts that reflected a velocity (any speed); the aim helper counts bounces with it. */
    var wallHits = 0
        private set
    var recordEvents = true
    val events = ArrayList<FlickEvent>()
    private var frames: ArrayList<FloatArray>? = null
    private var aliveCount = 0

    init {
        rebuildSegments()
    }

    // ---- set-up ----

    /** New rink: fish on the ice, gates shut, sliders in pose A, nobody carrying. Bodies keep their [alive] flags and positions. */
    fun resetRink() {
        for (f in 0 until nFish) { fishState[f] = 0; fishCarrier[f] = -1; bankedBy[f] = -1 }
        for (g in 0 until nGates) gateOpen[g] = false
        for (s in 0 until nSliders) sliderPose[s] = 0
        for (i in 0 until n) { carry[i] = -1; frozen[i] = false; vx[i] = 0.0; vy[i] = 0.0 }
        java.util.Arrays.fill(padInside, false)
        rebuildSegments()
    }

    fun place(i: Int, px: Double, py: Double) {
        x[i] = px; y[i] = py; vx[i] = 0.0; vy[i] = 0.0; alive[i] = true; frozen[i] = false
    }

    fun clearBody(i: Int) { alive[i] = false; x[i] = -1000.0; y[i] = -1000.0; vx[i] = 0.0; vy[i] = 0.0; carry[i] = -1 }

    /** Copies the dynamic state of [o] (a sim of the same level and mode) into this one. */
    fun copyFrom(o: FlickSim) {
        System.arraycopy(o.x, 0, x, 0, n); System.arraycopy(o.y, 0, y, 0, n)
        System.arraycopy(o.vx, 0, vx, 0, n); System.arraycopy(o.vy, 0, vy, 0, n)
        System.arraycopy(o.startX, 0, startX, 0, n); System.arraycopy(o.startY, 0, startY, 0, n)
        System.arraycopy(o.alive, 0, alive, 0, n); System.arraycopy(o.frozen, 0, frozen, 0, n)
        System.arraycopy(o.carry, 0, carry, 0, n)
        System.arraycopy(o.fishState, 0, fishState, 0, nFish); System.arraycopy(o.fishCarrier, 0, fishCarrier, 0, nFish)
        System.arraycopy(o.bankedBy, 0, bankedBy, 0, nFish)
        System.arraycopy(o.gateOpen, 0, gateOpen, 0, nGates); System.arraycopy(o.sliderPose, 0, sliderPose, 0, nSliders)
        System.arraycopy(o.padInside, 0, padInside, 0, padInside.size)
        stepNo = o.stepNo; restCount = o.restCount; wallHits = 0
        events.clear()
        rebuildSegments()
    }

    /** Rebuilds the segment list: the rink border, the walls, the shut gates and the sliders in their current pose. */
    private fun rebuildSegments() {
        var c = 0
        fun put(x1: Double, y1: Double, x2: Double, y2: Double) {
            val b = c * 4
            segs[b] = x1; segs[b + 1] = y1; segs[b + 2] = x2; segs[b + 3] = y2
            c++
        }
        val w = FlickRinkRules.RINK_W.toDouble()
        val h = FlickRinkRules.RINK_H.toDouble()
        put(0.0, 0.0, w, 0.0); put(w, 0.0, w, h); put(w, h, 0.0, h); put(0.0, h, 0.0, 0.0)
        for (s in level.walls) put(s.x1.toDouble(), s.y1.toDouble(), s.x2.toDouble(), s.y2.toDouble())
        for (g in 0 until nGates) if (!gateOpen[g]) { val s = level.gates[g].seg; put(s.x1.toDouble(), s.y1.toDouble(), s.x2.toDouble(), s.y2.toDouble()) }
        for (i in 0 until nSliders) {
            val sl = level.sliders[i]
            val s = if (sliderPose[i] == 0) sl.a else sl.b
            put(s.x1.toDouble(), s.y1.toDouble(), s.x2.toDouble(), s.y2.toDouble())
        }
        segCount = c
    }

    /** End of a round: every sliding wall swaps pose; bodies in the way are pushed out by the shortest push. Returns true when anything moved. */
    fun swapSliders(): Boolean {
        if (nSliders == 0) return false
        for (i in 0 until nSliders) sliderPose[i] = 1 - sliderPose[i]
        rebuildSegments()
        settle()
        return true
    }

    // ---- one flick ----

    fun beginFlick(body: Int, velX: Double, velY: Double, record: Boolean) {
        events.clear()
        stepNo = 0; restCount = 0; wallHits = 0
        aliveCount = 0
        for (i in 0 until n) {
            vx[i] = 0.0; vy[i] = 0.0; frozen[i] = false
            startX[i] = x[i]; startY[i] = y[i]
            if (alive[i]) aliveCount++
        }
        vx[body] = velX; vy[body] = velY
        // A body that already stands on a pad does not flip it again until it has left and come back.
        for (i in 0 until n) for (g in 0 until nGates) {
            val dx = x[i] - padData[g * 3]; val dy = y[i] - padData[g * 3 + 1]
            padInside[i * nGates + g] = alive[i] && dx * dx + dy * dy <= padData[g * 3 + 2] * padData[g * 3 + 2]
        }
        if (record) {
            val f = ArrayList<FloatArray>(MAX_FRAMES)
            f.add(frame())
            frames = f
        } else frames = null
    }

    /** One 1/120 s step. Returns true when the slide is over (everything at rest, or the step cap). */
    fun step(): Boolean {
        stepNo++
        integrate()
        collideStatics(0)
        collideStatics(1)
        collideBodies()
        hazards()
        val rested = restCheck()
        if (frames != null && stepNo % FlickRinkRules.FRAME_EVERY == 0) frames!!.add(frame())
        return rested || stepNo >= FlickRinkRules.MAX_STEPS
    }

    /** Settles after the last step: splashed bodies go back, speeds are zeroed, overlaps are pushed apart, resting carriers bank. */
    fun finishFlick() {
        for (i in 0 until n) {
            vx[i] = 0.0; vy[i] = 0.0
            if (frozen[i]) {
                frozen[i] = false
                x[i] = startX[i]; y[i] = startY[i]
            }
        }
        settle()
        if (fishRun) {
            for (i in 0 until n) {
                if (!alive[i] || carry[i] < 0) continue
                if (inHome(x[i], y[i])) bank(i)
            }
        }
        frames?.add(frame())
    }

    /** Runs a whole flick to rest and returns the recorded frames (empty when [record] is false). */
    fun runFlick(body: Int, velX: Double, velY: Double, record: Boolean): List<FloatArray> {
        beginFlick(body, velX, velY, record)
        while (!step()) { /* fixed 120 Hz steps */ }
        finishFlick()
        return frames ?: emptyList()
    }

    fun framesOrEmpty(): List<FloatArray> = frames ?: emptyList()

    /** The aim helper: the path the body [body] would take alone on the ice (other bodies ignored), as x, y pairs in [out]. Returns floats written. */
    fun trace(body: Int, velX: Double, velY: Double, maxLength: Double, maxBounces: Int, out: FloatArray): Int {
        recordEvents = false
        for (i in 0 until n) if (i != body) alive[i] = false
        beginFlick(body, velX, velY, false)
        var count = 0
        fun add(px: Double, py: Double) { if (count + 2 <= out.size) { out[count++] = px.toFloat(); out[count++] = py.toFloat() } }
        add(x[body], y[body])
        var travelled = 0.0
        var lastX = x[body]; var lastY = y[body]
        var bounces = 0
        var done = false
        while (!done) {
            val hitsBefore = wallHits
            done = step()
            val dx = x[body] - lastX; val dy = y[body] - lastY
            val seg = sqrt(dx * dx + dy * dy)
            if (travelled + seg >= maxLength) {
                val t = if (seg > 0.0) (maxLength - travelled) / seg else 0.0
                add(lastX + dx * t, lastY + dy * t)
                return count
            }
            travelled += seg
            lastX = x[body]; lastY = y[body]
            if (wallHits != hitsBefore) {
                bounces++
                add(lastX, lastY)
                if (bounces > maxBounces) return count
            }
        }
        add(lastX, lastY)
        return count
    }

    // ---- the step ----

    private fun integrate() {
        val dt = FlickRinkRules.DT
        for (i in 0 until n) {
            if (!alive[i]) continue
            if (frozen[i]) { vx[i] = 0.0; vy[i] = 0.0; continue }
            var ux = vx[i]
            var uy = vy[i]
            if (ux == 0.0 && uy == 0.0) continue
            val k = if (inDrift(x[i], y[i])) FlickRinkRules.DAMPING_PER_SECOND * FlickRinkRules.DRIFT_DAMPING_FACTOR else FlickRinkRules.DAMPING_PER_SECOND
            val f = 1.0 - k * dt
            ux *= f; uy *= f
            val sp = sqrt(ux * ux + uy * uy)
            val dec = FlickRinkRules.COULOMB_DECEL * dt
            if (sp <= dec) { ux = 0.0; uy = 0.0 } else { val m = (sp - dec) / sp; ux *= m; uy *= m }
            vx[i] = ux; vy[i] = uy
            x[i] += ux * dt; y[i] += uy * dt
        }
    }

    private fun inDrift(px: Double, py: Double): Boolean {
        for (d in 0 until nDrifts) {
            val b = d * 4
            if (px >= driftData[b] && px <= driftData[b + 2] && py >= driftData[b + 1] && py <= driftData[b + 3]) return true
        }
        return false
    }

    private fun collideStatics(pass: Int) {
        val rr = bodyRadius + FlickRinkRules.WALL_HALF
        val rr2 = rr * rr
        for (i in 0 until n) {
            if (!alive[i] || frozen[i]) continue
            for (s in 0 until segCount) {
                val b = s * 4
                val ax = segs[b]; val ay = segs[b + 1]
                val ex = segs[b + 2] - ax; val ey = segs[b + 3] - ay
                val len2 = ex * ex + ey * ey
                var t = if (len2 > 0.0) ((x[i] - ax) * ex + (y[i] - ay) * ey) / len2 else 0.0
                if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
                val dx = x[i] - (ax + t * ex)
                val dy = y[i] - (ay + t * ey)
                val d2 = dx * dx + dy * dy
                if (d2 >= rr2) continue
                var nx: Double
                var ny: Double
                var d = sqrt(d2)
                if (d > 1e-9) { nx = dx / d; ny = dy / d } else {
                    val l = sqrt(len2)
                    if (l > 1e-9) { nx = -ey / l; ny = ex / l } else { nx = 0.0; ny = -1.0 }
                    d = 0.0
                }
                x[i] += nx * (rr - d); y[i] += ny * (rr - d)
                reflect(i, nx, ny, wallE, pass)
            }
            for (p in 0 until nPosts) {
                val b = p * 3
                val lim = bodyRadius + postData[b + 2]
                val dx = x[i] - postData[b]; val dy = y[i] - postData[b + 1]
                val d2 = dx * dx + dy * dy
                if (d2 >= lim * lim) continue
                var d = sqrt(d2)
                val nx: Double
                val ny: Double
                if (d > 1e-9) { nx = dx / d; ny = dy / d } else { nx = 0.0; ny = -1.0; d = 0.0 }
                x[i] += nx * (lim - d); y[i] += ny * (lim - d)
                reflect(i, nx, ny, FlickRinkRules.E_POST, pass)
            }
        }
    }

    private fun reflect(i: Int, nx: Double, ny: Double, e: Double, pass: Int) {
        val vn = vx[i] * nx + vy[i] * ny
        if (vn >= 0.0) return
        vx[i] -= (1.0 + e) * vn * nx
        vy[i] -= (1.0 + e) * vn * ny
        wallHits++
        if (pass == 0 && recordEvents && -vn >= FlickRinkRules.BUMP_EVENT_MIN) events.add(FlickEvent.WallBump(stepNo, i, (-vn).toFloat()))
    }

    private fun collideBodies() {
        val md = 2.0 * bodyRadius
        val md2 = md * md
        for (i in 0 until n - 1) {
            if (!alive[i]) continue
            for (j in i + 1 until n) {
                if (!alive[j]) continue
                val dx = x[j] - x[i]; val dy = y[j] - y[i]
                val d2 = dx * dx + dy * dy
                if (d2 >= md2) continue
                var d = sqrt(d2)
                val nx: Double
                val ny: Double
                if (d > 1e-9) { nx = dx / d; ny = dy / d } else { nx = 1.0; ny = 0.0; d = 0.0 }
                val half = (md - d) * 0.5
                x[i] -= nx * half; y[i] -= ny * half
                x[j] += nx * half; y[j] += ny * half
                val vn = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
                if (vn <= 0.0) continue
                val jj = (1.0 + FlickRinkRules.E_PAL) * vn * 0.5
                vx[i] -= jj * nx; vy[i] -= jj * ny
                vx[j] += jj * nx; vy[j] += jj * ny
                if (recordEvents && vn >= FlickRinkRules.BUMP_EVENT_MIN) events.add(FlickEvent.PalBump(stepNo, i, j, vn.toFloat()))
                if (coop && fishRun && vn >= FlickRinkRules.RELAY_MIN_BUMP_SPEED) {
                    if (carry[i] >= 0 && carry[j] < 0) relay(i, j) else if (carry[j] >= 0 && carry[i] < 0) relay(j, i)
                }
            }
        }
    }

    /** The fish hops from [from] to [to] (co-op relay hand-off). */
    private fun relay(from: Int, to: Int) {
        val f = carry[from]
        carry[from] = -1; carry[to] = f
        fishCarrier[f] = to
        if (recordEvents) events.add(FlickEvent.RelayPass(stepNo, from, to, f))
    }

    private fun hazards() {
        for (i in 0 until n) {
            if (!alive[i] || frozen[i]) continue
            val px = x[i]; val py = y[i]
            if (fishRun) {
                for (p in 0 until nPuddles) {
                    val b = p * 3
                    val dx = px - puddleData[b]; val dy = py - puddleData[b + 1]
                    val lim = puddleData[b + 2]
                    if (dx * dx + dy * dy < lim * lim) { splash(i, puddleData[b], puddleData[b + 1], lim); break }
                }
                if (frozen[i]) continue
            }
            for (g in 0 until nGates) {
                val b = g * 3
                val dx = px - padData[b]; val dy = py - padData[b + 1]
                val inside = dx * dx + dy * dy <= padData[b + 2] * padData[b + 2]
                val idx = i * nGates + g
                if (inside && !padInside[idx]) {
                    gateOpen[g] = !gateOpen[g]
                    rebuildSegments()
                    if (recordEvents) events.add(FlickEvent.GateFlipped(stepNo, level.gates[g].id, gateOpen[g]))
                }
                padInside[idx] = inside
            }
            if (fishRun) {
                if (carry[i] < 0) {
                    for (f in 0 until nFish) {
                        if (fishState[f] != 0) continue
                        val dx = px - fishX[f]; val dy = py - fishY[f]
                        if (dx * dx + dy * dy <= pickR * pickR) {
                            carry[i] = f; fishState[f] = 1; fishCarrier[f] = i
                            if (recordEvents) events.add(FlickEvent.FishCollected(stepNo, i, f))
                            break
                        }
                    }
                } else if (relaxed && inHome(px, py) && vx[i] * vx[i] + vy[i] * vy[i] < FlickRinkRules.RELAXED_BANK_SPEED * FlickRinkRules.RELAXED_BANK_SPEED) {
                    bank(i)
                }
            }
        }
    }

    private fun splash(i: Int, cx: Double, cy: Double, lim: Double) {
        vx[i] = 0.0; vy[i] = 0.0
        if (recordEvents) events.add(FlickEvent.Splash(stepNo, i))
        if (relaxed) {
            // Relaxed: the pal just stops at the puddle edge (lifted out), it is not sent back.
            var dx = x[i] - cx; var dy = y[i] - cy
            val d = sqrt(dx * dx + dy * dy)
            if (d > 1e-9) { dx /= d; dy /= d } else { dx = 0.0; dy = 1.0 }
            x[i] = cx + dx * (lim + 0.5); y[i] = cy + dy * (lim + 0.5)
        } else {
            frozen[i] = true
        }
    }

    private fun inHome(px: Double, py: Double): Boolean {
        val dx = px - FlickRinkRules.HOME_X; val dy = py - FlickRinkRules.HOME_Y
        return dx * dx + dy * dy <= homeR * homeR
    }

    private fun bank(i: Int) {
        val f = carry[i]
        if (f < 0) return
        carry[i] = -1
        fishState[f] = 2; fishCarrier[f] = -1; bankedBy[f] = i
        if (recordEvents) events.add(FlickEvent.FishBanked(stepNo, i, f))
    }

    private fun restCheck(): Boolean {
        val stop2 = FlickRinkRules.STOP_SPEED * FlickRinkRules.STOP_SPEED
        var all = true
        for (i in 0 until n) {
            if (!alive[i] || frozen[i]) continue
            if (vx[i] * vx[i] + vy[i] * vy[i] >= stop2) { all = false; break }
        }
        if (all) restCount++ else restCount = 0
        return restCount >= FlickRinkRules.STOP_HOLD_STEPS
    }

    /** Pushes overlapping bodies apart and out of walls and posts, without touching velocities. Deterministic, a few fixed passes. */
    fun settle() {
        val md = 2.0 * bodyRadius
        val rr = bodyRadius + FlickRinkRules.WALL_HALF
        for (pass in 0 until 6) {
            for (i in 0 until n - 1) {
                if (!alive[i]) continue
                for (j in i + 1 until n) {
                    if (!alive[j]) continue
                    val dx = x[j] - x[i]; val dy = y[j] - y[i]
                    val d2 = dx * dx + dy * dy
                    if (d2 >= md * md) continue
                    var d = sqrt(d2)
                    val nx: Double
                    val ny: Double
                    if (d > 1e-9) { nx = dx / d; ny = dy / d } else { nx = 1.0; ny = 0.0; d = 0.0 }
                    val half = (md - d) * 0.5 + 0.01
                    x[i] -= nx * half; y[i] -= ny * half; x[j] += nx * half; y[j] += ny * half
                }
            }
            for (i in 0 until n) {
                if (!alive[i]) continue
                for (s in 0 until segCount) {
                    val b = s * 4
                    val ax = segs[b]; val ay = segs[b + 1]
                    val ex = segs[b + 2] - ax; val ey = segs[b + 3] - ay
                    val len2 = ex * ex + ey * ey
                    var t = if (len2 > 0.0) ((x[i] - ax) * ex + (y[i] - ay) * ey) / len2 else 0.0
                    if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
                    val dx = x[i] - (ax + t * ex); val dy = y[i] - (ay + t * ey)
                    val d2 = dx * dx + dy * dy
                    if (d2 >= rr * rr) continue
                    var d = sqrt(d2)
                    val nx: Double
                    val ny: Double
                    if (d > 1e-9) { nx = dx / d; ny = dy / d } else {
                        val l = sqrt(len2)
                        if (l > 1e-9) { nx = -ey / l; ny = ex / l } else { nx = 0.0; ny = -1.0 }
                        d = 0.0
                    }
                    x[i] += nx * (rr - d + 0.01); y[i] += ny * (rr - d + 0.01)
                }
                for (p in 0 until nPosts) {
                    val b = p * 3
                    val lim = bodyRadius + postData[b + 2]
                    val dx = x[i] - postData[b]; val dy = y[i] - postData[b + 1]
                    val d2 = dx * dx + dy * dy
                    if (d2 >= lim * lim) continue
                    var d = sqrt(d2)
                    val nx: Double
                    val ny: Double
                    if (d > 1e-9) { nx = dx / d; ny = dy / d } else { nx = 0.0; ny = -1.0; d = 0.0 }
                    x[i] += nx * (lim - d + 0.01); y[i] += ny * (lim - d + 0.01)
                }
            }
        }
    }

    /** True when body [i] rests (or is) inside a snow drift. */
    fun inDriftNow(i: Int): Boolean = inDrift(x[i], y[i])

    /** Bullseye: points of disc [i] where it lies, for a target at ([tx], [ty]) and ring [scale]. */
    fun discPoints(i: Int, tx: Double, ty: Double, scale: Double): Int {
        val dx = x[i] - tx; val dy = y[i] - ty
        return FlickRinkRules.bullseyePoints(sqrt(dx * dx + dy * dy), scale)
    }

    fun fishFreeCount(): Int { var c = 0; for (f in 0 until nFish) if (fishState[f] == 0) c++; return c }
    fun fishBankedCount(): Int { var c = 0; for (f in 0 until nFish) if (fishState[f] == 2) c++; return c }
    fun gatesOpenCount(): Int { var c = 0; for (g in 0 until nGates) if (gateOpen[g]) c++; return c }

    private fun frame(): FloatArray {
        val f = FloatArray(aliveCount * 2)
        var k = 0
        for (i in 0 until n) {
            if (!alive[i]) continue
            f[k++] = x[i].toFloat(); f[k++] = y[i].toFloat()
        }
        return f
    }

    companion object {
        private const val MAX_FRAMES = 460

        private val cosTable = DoubleArray(3600)
        private val sinTable = DoubleArray(3600)

        init {
            for (a in 0 until 3600) {
                val rad = a * (StrictMath.PI / 1800.0)
                cosTable[a] = StrictMath.cos(rad)
                sinTable[a] = StrictMath.sin(rad)
            }
        }

        /** The unit direction of an angle in tenths of a degree (clockwise from +x, y down): the only trig, outside the step loop. */
        fun dirX(angleDeci: Int): Double = cosTable[Math.floorMod(angleDeci, 3600)]
        fun dirY(angleDeci: Int): Double = sinTable[Math.floorMod(angleDeci, 3600)]
    }
}
