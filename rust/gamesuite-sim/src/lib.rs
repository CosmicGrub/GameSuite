//! GameSuite real-time simulation core — Air Hockey pilot.
//!
//! A faithful, line-for-line port of the physics/collision/scoring math in
//! `shared/src/commonMain/kotlin/com/gamesuite/games/airhockey/AirHockeyGame.kt`
//! (Kotlin, JVM/ART — subject to GC pauses during `tick()`), moved here so the
//! real-time tick loop runs with no GC and predictable frame time. This is a
//! migration, not a redesign: every constant, branch, and ordering below mirrors
//! the Kotlin original, including its two documented physics-correctness fixes
//! (swept-segment paddle collision, free-flight drag) and its stall watchdog.
//!
//! Compose stays purely the renderer: this crate owns ALL simulation state
//! (ball, paddles, scores, CPU AI targeting, hit-stop, event bookkeeping). The
//! Kotlin side only ever calls [AirHockeySim::tick], the two
//! `move_*_paddle` setters (driven by touch/drag input), and reads
//! [AirHockeySim::snapshot] once per frame to update its own Compose `State`.
//!
//! Exposed to Kotlin via UniFFI's proc-macro API (`#[uniffi::export]` /
//! `#[derive(uniffi::Object/Record/Enum)]`) — no `.udl` file. See
//! `uniffi-bindgen.rs` for how the Kotlin bindings are generated from this.

use std::sync::{Arc, Mutex};

uniffi::setup_scaffolding!("gamesuite_sim");

// ---- Tunable constants — copied verbatim (same literal values) from
// AirHockeyGame.kt's companion object. Kept in sync by hand since the Kotlin
// side (AirHockeyScreen.kt, and the shared AirHockeyGame.kt companion object
// used as the fallback/reference implementation) reads its own copies of these
// for rendering — see this crate's top-level KDoc-equivalent above and the
// final report's "behavioral differences" section for why these aren't
// exposed over the FFI boundary instead of duplicated. ----
pub const PADDLE_RADIUS: f32 = 0.07;
pub const BALL_RADIUS: f32 = 0.035;
pub const GOAL_HALF_WIDTH: f32 = 0.16;
pub const MAX_SPEED: f32 = 1.6;
const BALL_DRAG_PER_SECOND: f32 = 0.2;
const CPU_HOME_Y: f32 = 0.15;
const DEEP_BALL_Y: f32 = 0.75;
const POST_CONCEDE_DEFENSE_SECONDS: f32 = 1.2;
const TRAIL_LENGTH_STANDARD: usize = 8;
const TRAIL_LENGTH_MAXIMUM: usize = 16;
const STRONG_IMPACT_SPEED: f32 = 0.95;
const GOAL_FREEZE_FRAMES: u32 = 10;
const STALL_TIMEOUT_SECONDS: f32 = 15.0;

// ---------------------------------------------------------------------------
// FFI value types (UniFFI Records/Enums) — mirror AirHockeyGame.kt's nested
// data classes/enum 1:1 in shape, using snake_case per Rust/Kotlin-codegen
// convention (UniFFI's Kotlin generator keeps field names as given here).
// ---------------------------------------------------------------------------

#[derive(uniffi::Record, Clone, Copy, Debug, PartialEq)]
pub struct Vec2 {
    pub x: f32,
    pub y: f32,
}

impl Vec2 {
    fn new(x: f32, y: f32) -> Self {
        Self { x, y }
    }
    fn scale(self, s: f32) -> Vec2 {
        Vec2::new(self.x * s, self.y * s)
    }
    fn length(self) -> f32 {
        (self.x * self.x + self.y * self.y).sqrt()
    }
}

#[derive(uniffi::Record, Clone, Copy, Debug)]
pub struct TrailPoint {
    pub pos: Vec2,
    pub speed: f32,
}

#[derive(uniffi::Record, Clone, Copy, Debug)]
pub struct PaddleImpactEvent {
    pub seq: i64,
    pub position: Vec2,
    pub speed: f32,
}

#[derive(uniffi::Record, Clone, Copy, Debug)]
pub struct WallBounceEvent {
    pub seq: i64,
    pub position: Vec2,
}

#[derive(uniffi::Record, Clone, Copy, Debug)]
pub struct GoalEvent {
    pub seq: i64,
    pub scored_by_player: bool,
    pub match_over: bool,
}

#[derive(uniffi::Record, Clone, Copy, Debug)]
pub struct StaleRallyResetEvent {
    pub seq: i64,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum CpuDifficulty {
    Easy,
    Medium,
    Hard,
}

#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum MotionTier {
    Standard,
    Maximum,
}

/// Everything the Kotlin/Compose side needs to render one frame — read back
/// once per tick via [AirHockeySim::snapshot]. Shape matches
/// `AirHockeyGame.AirHockeyState` field-for-field so the Kotlin wrapper can
/// translate this straight into that same type (see the app module's
/// `AirHockeyRustGame.kt`), keeping `AirHockeyScreen.kt`'s existing
/// `state.ballPos` / `state.lastPaddleImpact?.seq` / etc. reads unchanged.
#[derive(uniffi::Record, Clone, Debug)]
pub struct AirHockeySnapshot {
    pub ball_pos: Vec2,
    pub ball_vel: Vec2,
    pub player_paddle: Vec2,
    pub cpu_paddle: Vec2,
    pub player_score: i32,
    pub cpu_score: i32,
    pub match_over: bool,
    pub winner_is_player: bool,
    pub ball_trail: Vec<TrailPoint>,
    pub last_paddle_impact: Option<PaddleImpactEvent>,
    pub last_wall_bounce: Option<WallBounceEvent>,
    pub goal_event: Option<GoalEvent>,
    pub stale_rally_reset: Option<StaleRallyResetEvent>,
}

// ---------------------------------------------------------------------------
// Internal simulation state — not exposed over FFI directly (UniFFI Objects
// require interior mutability since instances are shared behind an Arc), only
// via AirHockeySim's Mutex<SimState> and the snapshot() it produces.
// ---------------------------------------------------------------------------

struct SimState {
    ball_pos: Vec2,
    ball_vel: Vec2,
    player_paddle: Vec2,
    cpu_paddle: Vec2,
    player_score: i32,
    cpu_score: i32,
    match_over: bool,
    winner_is_player: bool,
    ball_trail: Vec<TrailPoint>,
    last_paddle_impact: Option<PaddleImpactEvent>,
    last_wall_bounce: Option<WallBounceEvent>,
    goal_event: Option<GoalEvent>,
    stale_rally_reset: Option<StaleRallyResetEvent>,

    // Mirror AirHockeyGame's private instance fields — NOT reset by
    // start_match() (the Kotlin original's startMatch() only ever reassigns
    // `state.value`; these separate private fields keep whatever value they
    // last held across a rematch, a deliberately-preserved quirk — see the
    // final report's "behavioral differences" section).
    last_player_paddle: Vec2,
    last_top_paddle: Vec2,
    hit_stop_frames_remaining: u32,
    event_seq: i64,
    post_concede_defense_timer: f32,
    seconds_since_last_goal: f32,

    // Config pushed down from Kotlin (there is no GameContext/PlayMode here —
    // topPaddleIsBot's source PlayMode check stays in Kotlin, which resolves
    // it once and forwards the bool via set_top_paddle_is_bot).
    difficulty: CpuDifficulty,
    match_target: i32,
    motion_tier: MotionTier,
    top_paddle_is_bot: bool,
}

impl SimState {
    fn new() -> Self {
        Self {
            ball_pos: Vec2::new(0.5, 0.5),
            ball_vel: Vec2::new(0.0, 0.0),
            player_paddle: Vec2::new(0.5, 0.85),
            cpu_paddle: Vec2::new(0.5, 0.15),
            player_score: 0,
            cpu_score: 0,
            match_over: false,
            winner_is_player: false,
            ball_trail: Vec::new(),
            last_paddle_impact: None,
            last_wall_bounce: None,
            goal_event: None,
            stale_rally_reset: None,
            last_player_paddle: Vec2::new(0.5, 0.85),
            last_top_paddle: Vec2::new(0.5, 0.15),
            hit_stop_frames_remaining: 0,
            event_seq: 0,
            post_concede_defense_timer: 0.0,
            seconds_since_last_goal: 0.0,
            difficulty: CpuDifficulty::Medium,
            match_target: 7,
            motion_tier: MotionTier::Standard,
            top_paddle_is_bot: true,
        }
    }

    fn snapshot(&self) -> AirHockeySnapshot {
        AirHockeySnapshot {
            ball_pos: self.ball_pos,
            ball_vel: self.ball_vel,
            player_paddle: self.player_paddle,
            cpu_paddle: self.cpu_paddle,
            player_score: self.player_score,
            cpu_score: self.cpu_score,
            match_over: self.match_over,
            winner_is_player: self.winner_is_player,
            ball_trail: self.ball_trail.clone(),
            last_paddle_impact: self.last_paddle_impact,
            last_wall_bounce: self.last_wall_bounce,
            goal_event: self.goal_event,
            stale_rally_reset: self.stale_rally_reset,
        }
    }
}

/// The FFI-facing handle Kotlin holds one of per match (mirrors one
/// `AirHockeyGame()` instance). A UniFFI `Object` — shared behind an `Arc`
/// across the FFI boundary, so all mutation goes through the internal
/// `Mutex<SimState>` rather than `&mut self`.
#[derive(uniffi::Object)]
pub struct AirHockeySim {
    inner: Mutex<SimState>,
}

#[uniffi::export]
impl AirHockeySim {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::new(SimState::new()),
        })
    }

    /// Mirrors AirHockeyGame.init()'s `secondsSinceLastGoal = 0f` — harmless
    /// (already 0 from `new()`) but kept for exact call-shape parity.
    pub fn init(&self) {
        self.inner.lock().unwrap().seconds_since_last_goal = 0.0;
    }

    pub fn set_difficulty(&self, difficulty: CpuDifficulty) {
        self.inner.lock().unwrap().difficulty = difficulty;
    }

    pub fn set_match_target(&self, target: i32) {
        self.inner.lock().unwrap().match_target = target;
    }

    pub fn set_motion_tier(&self, tier: MotionTier) {
        self.inner.lock().unwrap().motion_tier = tier;
    }

    /// Pushed down once from Kotlin's `topPaddleIsBot` (`context.activeMode ==
    /// PlayMode.SINGLE_PLAYER_VS_BOT`) — see [SimState]'s field doc.
    pub fn set_top_paddle_is_bot(&self, is_bot: bool) {
        self.inner.lock().unwrap().top_paddle_is_bot = is_bot;
    }

    /// Mirrors `AirHockeyGame.startMatch()`: resets the visible match state to
    /// a fresh serve, but deliberately does NOT touch `last_player_paddle` /
    /// `last_top_paddle` / `hit_stop_frames_remaining` / `event_seq` /
    /// `post_concede_defense_timer` — same as the Kotlin original.
    pub fn start_match(&self) {
        let mut s = self.inner.lock().unwrap();
        let dir_x = if rand_below_half() { -0.5 } else { 0.5 };
        s.ball_pos = Vec2::new(0.5, 0.5);
        s.ball_vel = Vec2::new(dir_x, 0.5);
        s.player_paddle = Vec2::new(0.5, 0.85);
        s.cpu_paddle = Vec2::new(0.5, 0.15);
        s.player_score = 0;
        s.cpu_score = 0;
        s.match_over = false;
        s.winner_is_player = false;
        s.ball_trail.clear();
        s.last_paddle_impact = None;
        s.last_wall_bounce = None;
        s.goal_event = None;
        s.stale_rally_reset = None;
        s.seconds_since_last_goal = 0.0;
    }

    /// Mirrors `AirHockeyGame.movePlayerPaddle` exactly, including the
    /// bottom-half clamp.
    pub fn move_player_paddle(&self, x: f32, y: f32) {
        let mut s = self.inner.lock().unwrap();
        if s.match_over {
            return;
        }
        let clamped_y = y.clamp(0.5 + PADDLE_RADIUS, 1.0 - PADDLE_RADIUS);
        let clamped_x = x.clamp(PADDLE_RADIUS, 1.0 - PADDLE_RADIUS);
        s.player_paddle = Vec2::new(clamped_x, clamped_y);
    }

    /// Mirrors `AirHockeyGame.moveTopPaddle` exactly, including the no-op when
    /// the top paddle is CPU-controlled.
    pub fn move_top_paddle(&self, x: f32, y: f32) {
        let mut s = self.inner.lock().unwrap();
        if s.match_over || s.top_paddle_is_bot {
            return;
        }
        let clamped_y = y.clamp(PADDLE_RADIUS, 0.5 - PADDLE_RADIUS);
        let clamped_x = x.clamp(PADDLE_RADIUS, 1.0 - PADDLE_RADIUS);
        s.cpu_paddle = Vec2::new(clamped_x, clamped_y);
    }

    /// One frame of simulation — see [tick_impl] for the actual port.
    pub fn tick(&self, dt_seconds: f32) {
        let mut s = self.inner.lock().unwrap();
        tick_impl(&mut s, dt_seconds);
    }

    /// Read-back for Compose: the only way state leaves this object.
    pub fn snapshot(&self) -> AirHockeySnapshot {
        self.inner.lock().unwrap().snapshot()
    }
}

// ---------------------------------------------------------------------------
// Physics — line-for-line port of AirHockeyGame.kt's `tick()` and its private
// helpers. Kept as free functions (rather than inherent methods on
// AirHockeySim) so the unit tests below can drive them directly against a
// bare SimState, without going through the Arc<Mutex<>> FFI object at all —
// see the crate-level doc and the final report's Testability section.
// ---------------------------------------------------------------------------

struct PaddleCollisionResult {
    ball_pos: Vec2,
    ball_vel: Vec2,
    hit: bool,
    impact_speed: f32,
}

/// Closest point on segment a→b to point p — degenerates to `a` (== `b`) when
/// the segment has zero length (a stationary paddle).
fn closest_point_on_segment(a: Vec2, b: Vec2, p: Vec2) -> Vec2 {
    let abx = b.x - a.x;
    let aby = b.y - a.y;
    let len_sq = abx * abx + aby * aby;
    if len_sq < 1e-12 {
        return a;
    }
    let t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / len_sq).clamp(0.0, 1.0);
    Vec2::new(a.x + abx * t, a.y + aby * t)
}

/// Resolves one ball/paddle collision, if any — tunneling fix (tests against
/// the segment the paddle swept this frame, not just its current position)
/// plus positional correction (nudges the ball back onto the paddle's edge on
/// a hit). See AirHockeyGame.kt's `resolvePaddleCollision` KDoc for the full
/// rationale; this is a direct, unabridged port of that function.
fn resolve_paddle_collision(
    ball: Vec2,
    ball_vel: Vec2,
    paddle: Vec2,
    paddle_vel: Vec2,
    paddle_from: Vec2,
) -> PaddleCollisionResult {
    let min_dist = BALL_RADIUS + PADDLE_RADIUS;
    let contact = closest_point_on_segment(paddle_from, paddle, ball);

    let dx = ball.x - contact.x;
    let dy = ball.y - contact.y;
    let dist = (dx * dx + dy * dy).sqrt();
    if dist >= min_dist || dist == 0.0 {
        return PaddleCollisionResult { ball_pos: ball, ball_vel, hit: false, impact_speed: 0.0 };
    }

    let nx = dx / dist;
    let ny = dy / dist;
    let rel_vel = Vec2::new(ball_vel.x - paddle_vel.x, ball_vel.y - paddle_vel.y);
    let speed_along_normal = rel_vel.x * nx + rel_vel.y * ny;
    if speed_along_normal > 0.0 {
        // already separating
        return PaddleCollisionResult { ball_pos: ball, ball_vel, hit: false, impact_speed: 0.0 };
    }

    let bounced = Vec2::new(
        ball_vel.x - 2.0 * speed_along_normal * nx + paddle_vel.x * 0.3,
        ball_vel.y - 2.0 * speed_along_normal * ny + paddle_vel.y * 0.3,
    );
    let bounced_len = bounced.length();
    let speed = bounced_len.clamp(0.4, MAX_SPEED);
    let normalized = if bounced_len > 0.0 { bounced.scale(speed / bounced_len) } else { bounced };

    let corrected_ball = Vec2::new(contact.x + nx * min_dist, contact.y + ny * min_dist);
    PaddleCollisionResult {
        ball_pos: corrected_ball,
        ball_vel: normalized,
        hit: true,
        impact_speed: speed_along_normal.abs(),
    }
}

/// True when the ball's hit circle overlaps the paddle's, regardless of
/// travel direction.
fn ball_overlaps_paddle(ball: Vec2, paddle: Vec2) -> bool {
    let dx = ball.x - paddle.x;
    let dy = ball.y - paddle.y;
    let min_dist = BALL_RADIUS + PADDLE_RADIUS;
    dx * dx + dy * dy < min_dist * min_dist
}

fn cpu_speed_for(difficulty: CpuDifficulty) -> f32 {
    match difficulty {
        CpuDifficulty::Easy => 0.55,
        CpuDifficulty::Medium => 0.9, // the original, single fixed speed this ladder replaces
        CpuDifficulty::Hard => 1.3,
    }
}

fn choose_cpu_target_x(ball: Vec2, ball_vel: Vec2, difficulty: CpuDifficulty) -> f32 {
    if ball.y >= 0.5 {
        return 0.5;
    }
    match difficulty {
        CpuDifficulty::Easy => ball.x + (rand_unit() - 0.5) * 0.24,
        CpuDifficulty::Medium => ball.x,
        CpuDifficulty::Hard => ball.x + ball_vel.x * 0.15,
    }
}

fn choose_cpu_target_y(ball_y: f32, just_conceded: bool, difficulty: CpuDifficulty) -> f32 {
    if just_conceded || ball_y > DEEP_BALL_Y {
        return CPU_HOME_Y;
    }
    if ball_y < 0.5 {
        return cpu_forward_y_for(difficulty);
    }
    CPU_HOME_Y
}

fn cpu_forward_y_for(difficulty: CpuDifficulty) -> f32 {
    match difficulty {
        CpuDifficulty::Easy => 0.22,
        CpuDifficulty::Medium => 0.30,
        CpuDifficulty::Hard => 0.40,
    }
}

/// `Random.nextDouble() < 0.5` equivalent — see this crate's top doc comment
/// on why `rand` (not a hand-rolled RNG) is the right call here.
fn rand_below_half() -> bool {
    rand::random::<f64>() < 0.5
}

/// `Random.nextFloat()` equivalent (uniform in `[0, 1)`).
fn rand_unit() -> f32 {
    rand::random::<f32>()
}

/// One frame of simulation. Direct port of `AirHockeyGame.tick(dtSeconds)` —
/// see that function's KDoc in the Kotlin source for the full rationale behind
/// each fix (tunneling, free-flight drag, stall watchdog); this only restates
/// the mechanics, not the "why".
fn tick_impl(s: &mut SimState, dt_seconds: f32) {
    if s.match_over {
        return;
    }
    let dt = dt_seconds.clamp(0.0, 0.05); // clamp to avoid huge steps on frame hitches
    let dt_safe = dt.max(0.001);

    // Paddle velocity tracking — deliberately unconditional, even during a
    // hit-stop freeze below (see the Kotlin KDoc on why).
    let player_vel = Vec2::new(
        (s.player_paddle.x - s.last_player_paddle.x) / dt_safe,
        (s.player_paddle.y - s.last_player_paddle.y) / dt_safe,
    );
    let player_paddle_from = s.last_player_paddle;
    s.last_player_paddle = s.player_paddle;

    let top_paddle_vel = Vec2::new(
        (s.cpu_paddle.x - s.last_top_paddle.x) / dt_safe,
        (s.cpu_paddle.y - s.last_top_paddle.y) / dt_safe,
    );
    let top_paddle_from = s.last_top_paddle;
    s.last_top_paddle = s.cpu_paddle;

    // Hit-stop: skip this frame's ball/CPU simulation entirely.
    if s.hit_stop_frames_remaining > 0 {
        s.hit_stop_frames_remaining -= 1;
        return;
    }

    s.seconds_since_last_goal += dt;

    let mut ball = s.ball_pos;
    let mut vel = s.ball_vel;

    ball = Vec2::new(ball.x + vel.x * dt, ball.y + vel.y * dt);

    // Wall bounce (left/right).
    let mut wall_bounce_event = s.last_wall_bounce;
    if ball.x - BALL_RADIUS < 0.0 {
        ball.x = BALL_RADIUS;
        vel = Vec2::new(-vel.x, vel.y);
        s.event_seq += 1;
        wall_bounce_event = Some(WallBounceEvent { seq: s.event_seq, position: ball });
    }
    if ball.x + BALL_RADIUS > 1.0 {
        ball.x = 1.0 - BALL_RADIUS;
        vel = Vec2::new(-vel.x, vel.y);
        s.event_seq += 1;
        wall_bounce_event = Some(WallBounceEvent { seq: s.event_seq, position: ball });
    }

    s.post_concede_defense_timer = (s.post_concede_defense_timer - dt).max(0.0);

    // Top paddle: CPU AI targeting, only when it isn't a second local player.
    let mut cpu_paddle = s.cpu_paddle;
    if s.top_paddle_is_bot {
        let target_x = choose_cpu_target_x(ball, vel, s.difficulty);
        let cpu_speed = cpu_speed_for(s.difficulty);
        let dx = (target_x - cpu_paddle.x).clamp(-cpu_speed * dt, cpu_speed * dt);
        let just_conceded = s.post_concede_defense_timer > 0.0;
        let target_y = choose_cpu_target_y(ball.y, just_conceded, s.difficulty);
        let dy = (target_y - cpu_paddle.y).clamp(-cpu_speed * dt, cpu_speed * dt);
        cpu_paddle = Vec2::new(
            (cpu_paddle.x + dx).clamp(PADDLE_RADIUS, 1.0 - PADDLE_RADIUS),
            (cpu_paddle.y + dy).clamp(PADDLE_RADIUS, 0.5 - PADDLE_RADIUS),
        );
    }

    // Paddle collisions — resolved before the goal check below.
    let player_hit = resolve_paddle_collision(ball, vel, s.player_paddle, player_vel, player_paddle_from);
    ball = player_hit.ball_pos;
    vel = player_hit.ball_vel;
    let top_vel_for_collision = if s.top_paddle_is_bot { Vec2::new(0.0, 0.0) } else { top_paddle_vel };
    let top_hit = resolve_paddle_collision(ball, vel, cpu_paddle, top_vel_for_collision, top_paddle_from);
    ball = top_hit.ball_pos;
    vel = top_hit.ball_vel;
    let paddle_hit_this_frame = player_hit.hit || top_hit.hit;
    let blocked_by_paddle = paddle_hit_this_frame
        || ball_overlaps_paddle(ball, s.player_paddle)
        || ball_overlaps_paddle(ball, cpu_paddle);

    // Real paddle-contact event + hit-stop.
    let mut paddle_impact_event = s.last_paddle_impact;
    if paddle_hit_this_frame {
        let impact_speed = player_hit.impact_speed.max(top_hit.impact_speed);
        s.event_seq += 1;
        paddle_impact_event = Some(PaddleImpactEvent { seq: s.event_seq, position: ball, speed: impact_speed });
        s.hit_stop_frames_remaining = if impact_speed > STRONG_IMPACT_SPEED { 2 } else { 1 };
    }

    // Free-flight drag — never on a frame that just resolved a paddle hit.
    if !paddle_hit_this_frame {
        vel = vel.scale((1.0 - BALL_DRAG_PER_SECOND * dt).max(0.0));
    }

    // Goal check.
    let mut player_score = s.player_score;
    let mut cpu_score = s.cpu_score;
    let mut scored = false;
    let mut scored_by_player = false;

    if ball.y - BALL_RADIUS < 0.0 {
        if (ball.x - 0.5).abs() < GOAL_HALF_WIDTH && !blocked_by_paddle {
            player_score += 1;
            scored = true;
            scored_by_player = true;
            s.post_concede_defense_timer = POST_CONCEDE_DEFENSE_SECONDS;
        } else {
            ball.y = BALL_RADIUS;
            if !blocked_by_paddle {
                vel = Vec2::new(vel.x, -vel.y);
                s.event_seq += 1;
                wall_bounce_event = Some(WallBounceEvent { seq: s.event_seq, position: ball });
            }
        }
    }
    if ball.y + BALL_RADIUS > 1.0 {
        if (ball.x - 0.5).abs() < GOAL_HALF_WIDTH && !blocked_by_paddle {
            cpu_score += 1;
            scored = true;
            scored_by_player = false;
        } else {
            ball.y = 1.0 - BALL_RADIUS;
            if !blocked_by_paddle {
                vel = Vec2::new(vel.x, -vel.y);
                s.event_seq += 1;
                wall_bounce_event = Some(WallBounceEvent { seq: s.event_seq, position: ball });
            }
        }
    }

    // Trail: ring buffer of recent ball positions/speeds, capped per motion tier.
    let trail_cap = if s.motion_tier == MotionTier::Maximum { TRAIL_LENGTH_MAXIMUM } else { TRAIL_LENGTH_STANDARD };
    let mut new_trail = s.ball_trail.clone();
    new_trail.push(TrailPoint { pos: ball, speed: vel.length() });
    if new_trail.len() > trail_cap {
        let excess = new_trail.len() - trail_cap;
        new_trail.drain(0..excess);
    }

    if scored {
        s.seconds_since_last_goal = 0.0;
        let match_over = player_score >= s.match_target || cpu_score >= s.match_target;
        s.event_seq += 1;
        s.hit_stop_frames_remaining = GOAL_FREEZE_FRAMES;

        let dir_x = if rand_below_half() { -0.5 } else { 0.5 };
        let dir_y = if player_score > s.player_score { -0.6 } else { 0.6 };

        // A fresh state, same as `AirHockeyState(...)` in the Kotlin original —
        // every field not explicitly set below reverts to its "fresh serve"
        // value (trail cleared, both non-goal events cleared).
        s.ball_pos = Vec2::new(0.5, 0.5);
        s.ball_vel = Vec2::new(dir_x, dir_y);
        // s.player_paddle unchanged (explicitly carried over in the Kotlin original too).
        s.cpu_paddle = cpu_paddle;
        s.player_score = player_score;
        s.cpu_score = cpu_score;
        s.match_over = match_over;
        s.winner_is_player = player_score >= s.match_target;
        s.ball_trail = Vec::new();
        s.last_paddle_impact = None;
        s.last_wall_bounce = None;
        s.goal_event = Some(GoalEvent { seq: s.event_seq, scored_by_player, match_over });
        s.stale_rally_reset = None;
    } else if s.seconds_since_last_goal >= STALL_TIMEOUT_SECONDS {
        s.event_seq += 1;
        s.seconds_since_last_goal = 0.0;
        s.hit_stop_frames_remaining = GOAL_FREEZE_FRAMES;

        let dir_x = if rand_below_half() { -0.5 } else { 0.5 };
        let dir_y = if rand_below_half() { -0.6 } else { 0.6 };

        // Also a fresh state — including matchOver/winnerIsPlayer reverting to
        // their defaults (false), exactly as `AirHockeyState(...)` would.
        s.ball_pos = Vec2::new(0.5, 0.5);
        s.ball_vel = Vec2::new(dir_x, dir_y);
        // s.player_paddle unchanged (explicitly carried over as `s.playerPaddle` in Kotlin).
        s.cpu_paddle = cpu_paddle;
        s.player_score = player_score;
        s.cpu_score = cpu_score;
        s.match_over = false;
        s.winner_is_player = false;
        s.ball_trail = Vec::new();
        s.last_paddle_impact = None;
        s.last_wall_bounce = None;
        s.goal_event = None;
        s.stale_rally_reset = Some(StaleRallyResetEvent { seq: s.event_seq });
    } else {
        // Kotlin's `s.copy(...)` here — every field NOT listed keeps its
        // previous value, so match_over/winner_is_player/goal_event/
        // stale_rally_reset/player_paddle are deliberately left untouched.
        s.ball_pos = ball;
        s.ball_vel = vel;
        s.cpu_paddle = cpu_paddle;
        s.player_score = player_score;
        s.cpu_score = cpu_score;
        s.ball_trail = new_trail;
        s.last_paddle_impact = paddle_impact_event;
        s.last_wall_bounce = wall_bounce_event;
    }
}

// ---------------------------------------------------------------------------
// Native unit tests — run via `cargo test`, entirely independent of
// Android/JNI (no .so loading involved). These port the three physics-
// correctness regression tests from
// shared/src/commonTest/kotlin/com/gamesuite/games/airhockey/AirHockeyGameTest.kt
// scenario-for-scenario, as this crate's half of the Testability story — see
// the final report for why this, plus leaving the Kotlin reference
// implementation and its existing test suite completely untouched, was judged
// preferable to trying to run real JNI-backed instrumentation tests under a
// plain JVM unit test (which is not possible: JNI needs the actual .so loaded
// on a real ART/JVM, which cargo test never provides).
// ---------------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;

    fn new_state() -> SimState {
        SimState::new()
    }

    /// Mirrors `AirHockeyGameTest.flickAcrossBall_stillBounces`: a paddle that
    /// crosses clear from one side of the ball to the other within a single
    /// frame must still be caught by the swept-segment tunneling fix.
    #[test]
    fn flick_across_ball_still_bounces() {
        let mut s = new_state();

        s.ball_pos = Vec2::new(0.05, 0.5);
        s.ball_vel = Vec2::new(0.0, 0.0);
        s.player_paddle = Vec2::new(0.15, 0.8);
        s.cpu_paddle = Vec2::new(0.5, 0.15);
        tick_impl(&mut s, 0.001); // bakes last_player_paddle as the segment start

        s.ball_pos = Vec2::new(0.5, 0.79);
        s.ball_vel = Vec2::new(0.0, 0.0);
        // movePlayerPaddle(0.85, 0.8) — same clamp movePlayerPaddle applies (a no-op here).
        let clamped_y = 0.8f32.clamp(0.5 + PADDLE_RADIUS, 1.0 - PADDLE_RADIUS);
        let clamped_x = 0.85f32.clamp(PADDLE_RADIUS, 1.0 - PADDLE_RADIUS);
        s.player_paddle = Vec2::new(clamped_x, clamped_y);
        tick_impl(&mut s, 0.016);

        let ball_vel = s.ball_vel;
        assert!(
            ball_vel.length() > 1.0,
            "expected the swept-path tunneling fix to catch the flick and bounce the ball, \
             but ball velocity is still {:?}",
            ball_vel
        );
    }

    /// Mirrors `AirHockeyGameTest.ballSlowsDownInFreeFlight`: pure horizontal
    /// flight, dead center in y, must measurably decay over ~2s thanks to the
    /// free-flight drag fix (no paddle hit ever re-floors its speed).
    #[test]
    fn ball_slows_down_in_free_flight() {
        let mut s = new_state();
        s.ball_pos = Vec2::new(0.5, 0.5);
        s.ball_vel = Vec2::new(MAX_SPEED, 0.0);
        s.player_paddle = Vec2::new(0.5, 0.85);
        s.cpu_paddle = Vec2::new(0.5, 0.15);
        let initial_speed = s.ball_vel.length();

        for _ in 0..120 {
            tick_impl(&mut s, 0.016); // ~2 seconds of free flight
        }

        let final_speed = s.ball_vel.length();
        assert!(
            final_speed < initial_speed * 0.95,
            "expected free-flight drag to bleed off speed over ~2s of play (started at \
             {initial_speed}), but final speed was {final_speed} -- rallies should decay, not \
             coast forever at MAX_SPEED"
        );
    }

    /// Mirrors `AirHockeyGameTest.stalledRallyIsForceResetAfterTheTimeout`: a
    /// ball bouncing forever between the two side walls (never scoring) must
    /// be force-reset once STALL_TIMEOUT_SECONDS elapses with no goal.
    #[test]
    fn stalled_rally_is_force_reset_after_the_timeout() {
        let mut s = new_state();
        s.ball_pos = Vec2::new(0.5, 0.5);
        s.ball_vel = Vec2::new(0.3, 0.0);
        s.player_paddle = Vec2::new(0.5, 0.85);
        s.cpu_paddle = Vec2::new(0.5, 0.15);

        let mut elapsed = 0.0f32;
        while elapsed < STALL_TIMEOUT_SECONDS - 0.5 {
            tick_impl(&mut s, 0.05);
            elapsed += 0.05;
        }
        assert!(
            s.stale_rally_reset.is_none(),
            "must not force-reset before the stall timeout has actually elapsed"
        );

        for _ in 0..20 {
            tick_impl(&mut s, 0.05); // crosses the timeout
        }

        assert!(
            s.stale_rally_reset.is_some(),
            "expected the stall watchdog to fire once STALL_TIMEOUT_SECONDS of no-goal play elapsed"
        );
        assert_eq!(s.ball_pos, Vec2::new(0.5, 0.5), "stalled ball must be recentered");
        assert_eq!(s.player_score, 0, "a stall reset must not award either player a point");
        assert_eq!(s.cpu_score, 0, "a stall reset must not award either player a point");
    }
}
