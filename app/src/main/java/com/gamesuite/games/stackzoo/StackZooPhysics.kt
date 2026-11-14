package com.gamesuite.games.stackzoo

/**
 * Every physics and rules constant in one place. Units: 1 u is about one animal-body height; the base log is 6 u wide.
 * Changing any value changes every replay and the golden hash of the determinism test on purpose: bump
 * [PHYSICS_VERSION] and update the golden value in the same change.
 */
object ZooPhysics {
    const val PHYSICS_VERSION = 1

    // ---- time: fixed step, the clock never enters the engine
    const val STEP_HZ = 120
    const val DT = 1.0 / STEP_HZ
    const val MAX_STEPS_PER_FRAME = 5

    // ---- world
    const val GRAVITY = 9.81
    const val FALL_Y = -1.2          // a piece whose centre is below this is "fallen"
    const val REMOVE_Y = -6.0        // fallen bodies are dropped from the simulation below this
    const val MAX_BODIES = 40        // one slot per piece id (a bag never exceeds 40); at most 24 stand at once in practice
    const val MAX_PARTS = 4
    const val MAX_CONTACTS = 128

    // ---- the rocking base (a plank turning about its own centre, spring-restored)
    const val BASE_WIDTH = 6.0
    const val BASE_THICKNESS = 0.4
    const val BASE_INERTIA = 30.0
    const val BASE_STIFFNESS = 460.0     // k = I * w0^2 with w0 = 2*pi/1.6 s
    const val BASE_DAMPING = 52.0        // zeta 0.22
    const val RELAXED_STIFFNESS = 920.0  // twice as stiff
    const val RELAXED_DAMPING = 199.0    // zeta 0.6
    const val BASE_MAX_ANGLE = 0.35      // radians (20 degrees): hard stop, inelastic
    const val BASE_FRICTION = 0.8
    const val BASE_KEEL = 1.5            // ballast keel: extra restoring torque = KEEL * g * (sum of mass * height above the pivot) * angle
    const val RELAXED_KEEL = 2.5
    const val BASE_DAMP_LOAD = 1.2       // extra damping per unit of that sum
    const val RELAXED_DAMP_LOAD = 3.0
    const val RELAXED_FRICTION_BONUS = 0.2
    const val LOG_SLEEP_SPIN = 0.02      // rad/s: the log counts as quiet below this

    // ---- contact solver (sequential impulses, warm started)
    const val VELOCITY_ITERATIONS = 10
    const val POSITION_ITERATIONS = 4
    const val BAUMGARTE = 0.2
    const val LINEAR_SLOP = 0.005
    const val RESTITUTION = 0.0

    // ---- sleeping and settling
    const val SLEEP_LINEAR = 0.05
    const val SLEEP_ANGULAR = 0.10
    const val SLEEP_STEPS = 60           // half a second at 120 Hz
    const val SETTLE_MAX_STEPS = 960     // 8 s: after this the turn is accepted as it stands

    // ---- placement
    const val QUANT = 1024.0             // release inputs are Ints in 1/1024 u and 1/1024 rad
    const val PLACE_GAP = 0.04
    const val SWEEP_TOLERANCE = 0.01
    const val X_LIMIT = 4.0              // release x is clamped to +-4 u
    const val FLAT_SNAP_SMALL_DEG = 12.0
    const val FLAT_SNAP_LARGE_DEG = 25.0
    const val FLAT_EDGE_MIN = 0.6        // hull edges at least this long can lie flat
    const val PIVOT_Y = -0.2             // the log turns about the middle of the plank
    const val CONTACT_MARGIN = 0.02      // speculative contact distance
    const val SWEEP_STEP = 0.1
    const val SWEEP_START = 3.0
    const val ANGLE_LIMIT_Q = 3217       // +-pi in 1/1024 rad
    const val X_LIMIT_Q = 4096
    const val HOLD_STEPS_PER_TURN_RESULT = 144 // 1.2 s of cheering
}

/** Game rules numbers (see the SCRIPT section). */
object ZooRules {
    const val COOP_HOLD_STEPS = 180      // the goal height must stay for 1.5 s
    const val COOP_STAR2_FRACTION = 0.25
    const val COOP_STAR3_FRACTION = 0.50
    const val TAKE_BACK_CAP = 3
    const val ROUND_CAP_FACTOR = 3       // turn cap = factor * hand size per player
    const val SOLO_LIVES = 3
    const val OFFER_SIZE = 3
    const val BAG_COPIES = 2             // 20 types, at most 2 copies each
    const val ASSIST_SMALL_RADIUS = 0.6
    const val ASSIST_SMALL_PULL = 0.5
    const val ASSIST_LARGE_RADIUS = 1.4
    const val ASSIST_LARGE_PULL = 0.85
    const val ASSIST_LARGE_FRICTION_BONUS = 0.2
    const val BOT_MIN_THINK_MS = 800L
}
