# ADR-1: Engine/Framework Choice for the PC Port (iOS scope withdrawn)

**Status:** Accepted (Android/PC scope), iOS scope withdrawn -- see Scope Update below
**Date:** September 2026
**Deciders:** the project owner

## Scope Update (2026-09-12)

**iOS is out of scope for this project, full stop -- not deferred, not gated on Mac
access, withdrawn.** The project owner does not want a Mac or an iPhone involved in this
project in any capacity. GameSuite's actual platform scope is **Android and PC/Windows
only.**

This changes the Decision below in one place: everywhere it says "iOS and PC ports" or
sequences "desktop first, iOS second," read that as **desktop only** -- the KMP/CMP
adoption decision itself stands (see Decision, Options Considered, Trade-off Analysis,
and Action Items 1-5, all of which are genuinely about Android+Desktop and unaffected by
this update), but Action Items 6, 7, and 8 (macOS build access, iOS pilots, and the
platform-parity follow-up work gated on an iOS target) are cancelled outright, not
postponed. Background research into macOS build-access options had already been
dispatched before this instruction arrived, but was stopped along with the rest of the
Apple-related work rather than left to finish -- no purchase or account was ever created,
and no findings document exists or should be fabricated. See Action Item 6 below.

## Context

GameSuite is a shipping, verified Android app, not a prototype: versionName
`2.0.0`, 13 games across board/cards/arcade/puzzle/word categories sharing one
shell (menu, settings, stats, local pass-and-play, same-room ad-hoc multiplayer
via Nearby Connections, online multiplayer via a self-hosted relay), built
end-to-end in Kotlin + Jetpack Compose and verified on two real physical
devices (Galaxy Z Fold 5, Galaxy Tab S9 FE). The README's own **Platform
status** section tracks this honestly: Android (`app/`) and an ESP32 arcade
cabinet (`esp32-tictactoe/`) are the two platforms that actually exist today.
**iOS and a PC/desktop build do not exist yet — no code, no branch, nothing.**
This ADR is the first real step toward closing that gap.

Two facts about how this codebase is actually built change how "team
familiarity" should be read for this decision. First, the entire codebase —
including a full legal-chess move generator (castling, en passant, check),
several minimax/alpha-beta AI ladders, and a genuine tick-based physics
simulation for Air Hockey — was built end-to-end by an AI coding agent
(Claude Code) operating autonomously from a solo owner's direction, not by a
traditional team. "Familiarity" here means fit for an agentic build loop:
a mainstream, deeply-documented language/tooling ecosystem, a real
Gradle/CLI-drivable build with no GUI-only step blocking headless iteration,
and — uniquely relevant here — whatever large body of idiomatic in-repo code
already exists for the agent to pattern-match against. Second, this project's
own README already recorded a lean on this exact question, written before
this ADR existed: *"Native Android is Android-only long-term. If iOS/desktop
ever becomes a real goal, that's a second codebase later (likely Flutter or
Compose Multiplatform) sharing game **rules** logic where practical, not a
rewrite of this decision now."* This ADR treats that note as a hypothesis to
verify against the real code, not a foregone conclusion — and the verification
below confirms it holds up, for reasons more specific than the note itself
anticipated.

**What direct inspection of the real code actually shows**, checked file by
file rather than assumed:

- `app/src/main/java/com/gamesuite/games/` (13 packages, **8,570 lines**) is
  close to pure, portable Kotlin. `TicTacToeGame.kt`, `UnoGame.kt`,
  `MancalaGame.kt`, `CheckersGame.kt`, `DominoGame.kt`, `SolitaireGame.kt`
  each have **exactly one** non-stdlib import:
  `androidx.compose.runtime.mutableStateOf`. `AirHockeyGame.kt` (574 lines,
  the real physics sim — collision resolution, tunneling fixes, positional
  correction) adds exactly one more:
  `androidx.compose.ui.geometry.Offset`. `HangmanGame.kt` and
  `SlidingPuzzleGame.kt` each have one mechanical platform seam
  (`android.content.Context` for a dictionary asset read;
  `android.os.SystemClock` for elapsed time). The one real exception is
  `ChessGame.kt` (1,022 lines): unlike every other game, it imports
  `android.content.Context` and four `androidx.datastore.preferences.*`
  symbols **directly into the same file** as its move generator and minimax
  search, rather than delegating persistence to a sibling `*PrefsStore.kt`
  the way `AirHockeyPrefsStore.kt`/`SolitairePrefsStore.kt`/etc. all do. This
  matters because `mutableStateOf` and `Offset` are not Android-only APIs —
  they are real, JetBrains-shipped Kotlin Multiplatform artifacts under
  Compose Multiplatform. Most of this project's hardest-won engineering is,
  by accident of how it was written rather than deliberate multiplatform
  planning, already sitting one import away from being platform-portable
  Kotlin.
- `app/src/main/java/com/gamesuite/ui/` is **14,988 lines** — 1.75x the size
  of the logic layer — and is Jetpack Compose, Android-only today. This is
  the actual bulk of the app by raw volume, not the algorithms.
- `server/` (confirmed by reading `server/package.json` and
  `server/README.md`) is plain Node.js + `ws`, a byte relay that never parses
  or validates game state. It is **already platform-agnostic** — any future
  iOS/PC client speaks the same JSON-over-WebSocket protocol
  `OnlineTransport.kt` already speaks, with zero server-side changes. The one
  client-side wrinkle: `OnlineTransport.kt` currently uses `okhttp3.*`
  (JVM-only) and `java.util.Base64` — both need swapping (Ktor's WebSocket
  client; `kotlin.io.encoding.Base64`) if the client ever needs to run
  outside a JVM.
- `ui/effects/PremiumShaders.kt` (AGSL `RuntimeShader`, gated
  `Build.VERSION.SDK_INT >= TIRAMISU`) backs the **"Premium 2026 Vision"**
  polish pitch — a real, delivered proposal that is **not yet greenlit**.
  Nothing here is shipped gameplay, which lowers the urgency of solving
  AGSL-equivalence as part of this decision.
- `audio/AmbientMusicEngine.kt` (544 lines) and `audio/ProceduralSfx.kt`
  (261 lines) are real-time PCM synthesis via `android.media.AudioTrack`,
  chosen specifically so GameSuite never has a licensed/sourced-audio
  question. The DSP math itself is `kotlin.math`; only the "hand this buffer
  to the OS" layer is Android-specific.
- `haptics/Haptics.kt` defines a 7-signal vocabulary
  (`LIGHT_TICK`…`CELEBRATION`) decoupled from any specific vibration API —
  the cleanest abstraction boundary in the platform-specific set, but backed
  today by `android.os.Vibrator`/`VibrationEffect` with no equivalent
  anywhere else.
- `foldable/FoldState.kt`, `DeviceClass.kt`, `AdaptiveTwoPane.kt` encode a
  genuinely portable *design pattern* (posture-then-width-class branching,
  phone/tablet/desktop layout) on top of two APIs that are not portable at
  all: Jetpack WindowManager's live hinge-posture signal has no iOS or
  desktop equivalent, for the obvious hardware reason. This is a real feature
  reduction on every non-Android target, independent of which candidate is
  chosen.
- `transport/NearbyConnectionsTransport.kt` (same-room ad-hoc multiplayer via
  Google Play Services' Nearby Connections, `docs/DEVICE_SPECIFIC_PLAN.md`
  §2) is 100% Android/Play-Services-specific. There is no mature,
  drop-in cross-platform equivalent under **any** candidate evaluated below —
  iOS's structural analog (Multipeer Connectivity) has current (2026)
  developer reports describing it as flaky, and desktop has no ad-hoc-radio
  story at all under any framework. This is a real, named engineering gap
  regardless of what this ADR decides.
- The user's standing policy is that every platform GameSuite ships on is
  discoverable in the repository (Android under `app/`, ESP32 firmware under
  `esp32-tictactoe/`). This document is the actual first real step toward
  iOS and PC closing that same gap honestly.

## Decision

**Adopt Kotlin Multiplatform + Compose Multiplatform (KMP/CMP) as the
engine/framework for GameSuite's PC port.** ~~Sequence desktop first..., and
treat acquiring a macOS build host as an explicit, named prerequisite task to
complete before iOS work begins...~~ **Superseded by the Scope Update above:
iOS is withdrawn from this project entirely, not sequenced for later.** The
desktop (Windows/JVM) target, buildable today in this project's actual
Windows/Gradle/CLI environment with zero new infrastructure, is now this
decision's only non-Android target.

Android's existing, shipping, twice-verified native Kotlin/Compose app is not
touched by this decision in the near term. See **Consequences** and
**Action Items** for exactly how and when shared-module adoption should
reach Android.

## Options Considered

### 1. Kotlin Multiplatform + Compose Multiplatform

| Dimension | Assessment |
|---|---|
| Complexity | Medium. The hard part is not learning a new paradigm — it's a bounded set of named platform-specific leaf features (Nearby-equivalent, native audio sink, AGSL→SkSL shader parity) behind interfaces the shell already draws cleanly (`MultiplayerTransport`, a would-be audio-sink `expect`/`actual`). |
| Cost | Low. Kotlin/Native and Compose Multiplatform are Apache 2.0, no revenue share, no seat licenses. Tooling is free (IntelliJ CE / Android Studio's KMP plugin). The only real costs — Apple Developer Program ($99/yr) and macOS build access — are universal to *any* iOS-targeting candidate, not specific to this one. |
| Scalability | High. Confirmed directly: the "mostly-pure-Kotlin logic plus one shared shell contract" pattern this project already leans on for Android extends to `commonMain` with near-zero change for most games today, and the same pattern applies to every future game (the ESP32 project's own multi-wave roadmap already anticipates Mancala/Dominoes/Solitaire/Mahjong-style growth) without inventing a second implementation. |
| Team familiarity (AI-agent fit) | Very high. Same language, same declarative UI paradigm, and — uniquely among every candidate below — the agent's entire existing 27,017-line corpus of idiomatic GameSuite Kotlin stays directly reusable as pattern-matching context for the same agent doing the port, not discarded. Kotlin and Jetpack Compose have deep, mainstream LLM training coverage; Compose Multiplatform is intentionally the same API surface. |

**Pros**
- The most valuable, hardest-won part of this codebase — full chess
  legality, several minimax AIs, real physics — is already one import away
  from portable Kotlin, confirmed by direct inspection, not assumed.
- The project's own existing JUnit suite (`ChessGameTest`-equivalents,
  `MancalaGameTest`, `SlidingPuzzleGameTest`, `UnoBotTest`, etc.) can migrate
  into `commonTest` and keep validating the ported logic on every target —
  a real regression safety net no rewrite candidate keeps.
- The exact Jetpack libraries already in use for the shell — DataStore
  Preferences, `navigation-compose`, `lifecycle-viewmodel-compose`,
  `material3-adaptive` — all have JetBrains-maintained multiplatform
  mirrors; this is a continuation of the stack already chosen, not a bolt-on.
- `server/` needs zero changes; online multiplayer is already done for any
  future client.
- Compose Multiplatform for iOS has been Stable since 1.8.0 (May 2025), with
  a genuine cross-platform shader story (AGSL on Android, SkSL — nearly
  syntactically identical — everywhere else via Skiko) that directly answers
  the `PremiumShaders.kt` question, whenever that pitch is greenlit.
- Desktop (JVM) has no Apple-toolchain dependency at all and builds/runs
  today on this project's actual Windows development machine.
- Directly matches the project's own pre-recorded design intent.

**Cons**
- iOS builds require a macOS host — Kotlin/Native's Apple cinterop
  "must use a macOS host" per Kotlin's own current docs — and this
  project's environment is Windows. Not unique to this candidate (every
  iOS-targeting option below hits the same wall), but real and must be
  solved before iOS work starts.
- `ChessGame.kt` needs a small refactor (peeling its DataStore/`Context`
  persistence out of the same file as its move generator) before it can move
  to `commonMain` cleanly — the one file that doesn't already match the
  rest of the codebase's clean separation.
- Nearby Connections has no multiplatform library; iOS needs real Multipeer
  Connectivity `cinterop` work with only experimental community precedent,
  and desktop has no ad-hoc-radio equivalent at all.
- Real-time audio (`AudioTrack`) and AGSL shaders each need genuine
  platform-specific implementation work per target, though bounded and
  behind clean seams.
- Compose Multiplatform on iOS, while Stable, still has documented rough
  edges as of 2026 (text-field behavior under iOS low-power mode,
  accessibility not yet at full native parity) that need direct
  verification, not assumption.

### 2. Flutter (Dart)

| Dimension | Assessment |
|---|---|
| Complexity | Medium-high. A full, ground-up rewrite of both the logic and UI layers — Dart's simplicity and a fully headless/CLI-drivable toolchain (`flutter build`, `flutter test`) keep per-file complexity low, but real, concentrated risk sits in local ad-hoc multiplayer, where the plugin ecosystem is thin and partly stale. |
| Cost | Low. BSD-3, no revenue share. The Apple Developer fee and macOS-build-access cost apply identically to this candidate — `flutter build ios` requires the same Xcode/macOS toolchain as every other iOS path, a fact that does not change simply because Flutter is cross-platform. |
| Scalability | Medium. Widest single-codebase reach of any candidate (iOS + Windows + macOS + Linux from one Dart tree), but every future GameSuite game must be authored **twice, forever** — once in Kotlin for Android, once in Dart for everything else — since no logic crosses the language boundary at all. |
| Team familiarity (AI-agent fit) | High for Dart itself (mainstream, close enough to Kotlin's syntax/idioms for confident transliteration, deep LLM training coverage), but the agent starts every one of the ~27,000 rewritten lines from zero in-repo precedent, unlike KMP. |

**Pros**
- Genuinely the widest simultaneous cross-platform reach of any realistic
  candidate, and 2026 brought real desktop-maturity investment (Canonical
  now stewards Flutter's Windows/macOS/Linux desktop target).
- `flutter_soloud` is a real, current, MIT-licensed, 5-platform answer to
  procedural audio synthesis specifically.
- `server/` needs no changes; a standard `web_socket_channel` client speaks
  the existing relay protocol unmodified.
- Dart's simplicity and fully CLI-drivable toolchain are a genuinely strong
  match for an autonomous-agent build loop, considered purely as a rewrite
  target.

**Cons**
- This is a full, literal rewrite: **every** sampled game-logic file already
  has `mutableStateOf`/`Offset`/DataStore coupling threaded through it (even
  the "pure" ones), so there is no partial-lift shortcut even in principle —
  100% of the 8,570-line logic layer, and its entire JUnit suite, is
  re-authored from scratch in a second language.
- Directly cuts against the project's own recorded intent to share game
  rules logic "where practical, not a rewrite."
- Nearby Connections' closest iOS analog plugin is ~2 years stale; desktop's
  best available substitute (`bonsoir`, mDNS-based) requires both devices to
  already share a Wi-Fi network — a materially weaker feature than what
  ships today.
- Forces a permanent choice between two independently-maintained
  codebases (Kotlin/Compose for Android, Dart/Flutter for everything else)
  or a second full rewrite of the already-shipping Android app.
- Third-party package licensing is not guaranteed to stay
  permissively-licensed forever (a real 2025 precedent: `flutter_blue_plus`
  moved to a dual commercial/nonprofit license) — a risk category that
  doesn't exist for first-party Jetpack APIs.

### 3. Unity (C#)

| Dimension | Assessment |
|---|---|
| Complexity | High. A full rewrite across a fundamentally different rendering/UI paradigm — sprite/mesh-based UI Toolkit versus Compose's immediate-mode vector `Canvas`, which every one of GameSuite's ~24 screens currently relies on with essentially zero bitmap assets in `res/`. |
| Cost | Effectively zero given GameSuite's permanent no-monetization stance (Unity Personal is free under $200k trailing revenue/funding, and the 2023 per-install Runtime Fee is fully, permanently cancelled) — but Personal forces an unremovable splash screen, and the Editor's 8.5–10GB install footprint reopens the exact disk-space objection this project's own README already used to reject Unity once. |
| Scalability | Medium. True one-project export to iOS+Windows+macOS+Linux, but every future game is a second full implementation, and two of GameSuite's actual differentiators — Nearby-based local play and fold-aware layout — have **no Unity-side path at all**, meaning they'd need bespoke, unprecedented native-plugin work every time they matter, not a library swap. |
| Team familiarity (AI-agent fit) | High for mainstream Unity/C# work generally (deep LLM training coverage, and a brand-new, first-party Sept-2026 Claude Code plugin with Unity CLI + MCP support) — but this is a real tailwind on a currently-untested-at-scale feature, and it provides zero benefit from the existing Kotlin corpus, which is discarded entirely. |

**Pros**
- Real, mature, first-party iOS+Windows+macOS+Linux export from one project.
- Air Hockey's hand-rolled physics solver could be replaced by Unity's
  mature `Rigidbody2D`/`CircleCollider2D` — likely *less* code than today.
- Genuinely current, first-party investment in agent-drivable workflows
  (the Sept 10, 2026 Claude Code plugin, a new headless Unity CLI) is
  unusually well-timed for this project's exact build model.
- Zero licensing cost under GameSuite's stated no-monetization policy.

**Cons**
- A full rewrite of all game logic and UI, discarding real, adversarially-
  reviewed engineering work (full chess legality, several AI ladders, a
  physics sim) that Unity's own clean logic/UI separation confers zero
  benefit toward reusing.
- No Nearby Connections equivalent at all — not stale, not thin, simply
  absent — and no Jetpack WindowManager hinge-posture equivalent either;
  both of GameSuite's real differentiators regress with no idiomatic
  Unity path to fall back on.
- Reopens the disk-space and "no direct Jetpack WindowManager access"
  objections the project's own README already recorded as the reasons
  Unity lost this exact decision once before — neither has changed.
- Forced splash-screen branding at the tier this project would actually use.
- Unity's own center of gravity (3D, action, VFX-heavy) is a poor match for
  a 2D board/card/word-game suite with no 3D ambitions.

### 4. Godot (GDScript or C#)

| Dimension | Assessment |
|---|---|
| Complexity | High. A full rewrite, with two named platform-maturity gaps landing on exactly the two platforms this ADR targets: GDScript carries a real, current LLM-training-skew risk (Godot 4's rewritten syntax versus the still-dominant Godot-3-era training corpus, producing code that compiles — GDScript has no compile-time type enforcement by default — but silently calls removed/renamed APIs at runtime), and C# on iOS is still labeled "experimental" in Godot's own current docs and forums. |
| Cost | Lowest of any candidate on paper — MIT-licensed, no revenue share, no seat cost, a well-funded 2026 ecosystem (an $18M Series B led by Tencent into the Godot-adjacent commercial ecosystem) — but the engine's own documentation flags real-time audio synthesis from script as a performance soft spot, which cuts directly at GameSuite's deliberately licensed-audio-free identity. |
| Scalability | Medium. A genuinely improved native shader and 2D-engine story versus what GameSuite has today, but every future game is again a full second implementation, and Nearby/haptics/fold-layout all regress with no engine-level equivalent to build on, same as Unity. |
| Team familiarity (AI-agent fit) | Medium. GDScript's training-corpus skew is a *silent* failure mode — exactly the kind an autonomous agent is worst-equipped to catch without a runtime check on every generated screen — and C#, while more LLM-fluent generally, carries the iOS-experimental risk instead. Neither language in this candidate is a clean, risk-free answer. |

**Pros**
- Genuinely free, MIT, durable licensing with no Unity-style pricing history
  to worry about.
- A real, purpose-built 2D engine — native shaders and 2D tooling on every
  platform, not an Android-33+-only AGSL gate.
- `server/` needs no changes; Godot's `WebSocketPeer` speaks the existing
  relay protocol directly.
- Air Hockey's hand-rolled tick physics sidesteps a real, currently-
  documented stability weakness in Godot's own built-in 2D physics server —
  a case where GameSuite's existing approach turns out to already avoid a
  problem this engine has.

**Cons**
- Zero code reuse from the existing, verified Kotlin codebase, despite that
  logic already being cleanly separated and portable in principle — Godot
  is the one candidate that cannot use any of it, directly contradicting
  the project's own recorded intent to share rules logic.
- GDScript's Godot-3/4 training-skew risk is a specific, current,
  documented failure mode for exactly this project's AI-agent-driven
  workflow.
- C# on iOS is explicitly "experimental" per Godot's own docs — real risk
  on one of the two named target platforms.
- No built-in haptic-waveform richness on iOS/desktop, and no local-discovery
  primitive of any kind — both are fully bespoke, unprecedented work.
- A real (if likely non-fatal) 2024 community-governance wobble is part of
  this engine's recent history.

### 5. Native per-platform (SwiftUI iOS + separate native PC)

| Dimension | Assessment |
|---|---|
| Complexity | Highest of any candidate. Two independent from-scratch UI rebuilds (not one) — SwiftUI for iOS, a wholly separate native toolchain (WinUI 3, or platform-native equivalents) for PC — on top of the same full logic rewrite every non-Kotlin candidate requires. |
| Cost | Highest ongoing. The same universal Apple Developer/macOS-build costs as any iOS path, plus a real, separate PC-toolchain investment (and Windows code-signing certificate costs, ~$250–580/yr, if distributing outside the Microsoft Store), with no code-sharing benefit anywhere to offset it. |
| Scalability | Lowest. This is the one candidate where the shell's own founding principle — "build once, every game gets it for free" — is defeated by construction: every future game and every future shell feature is authored **three times**, forever, across Android/iOS/PC. |
| Team familiarity (AI-agent fit) | Medium for SwiftUI alone (mainstream, CLI-buildable via `xcodebuild`, but zero prior in-repo precedent) and low for a from-scratch native-PC stack this project and this agent have never touched — and this candidate carries the hardest infrastructure precondition of any option (a fully Mac-mediated iOS build loop) for the least code-sharing payoff of solving it. |

**Pros**
- Maximum achievable per-platform fidelity — no cross-platform rendering
  compromise on either target.
- Zero risk to the existing Android app; purely additive.
- `server/` is free reuse here too, same as every other candidate.
- Each platform gets its own genuinely best answer for shaders/audio/haptics
  rather than a shared lowest-common-denominator API.

**Cons**
- By far the largest total rebuild surface of any strategy: the 14,988-line
  UI layer is rebuilt **twice** (once for SwiftUI, once for PC), on top of
  the full 8,570-line logic rewrite every non-KMP candidate already pays.
- Hard-blocked on macOS/Xcode access for the iOS half, exactly like KMP and
  Flutter, but here with none of the "at least logic came along for free"
  consolation.
- A genuinely native PC build is a third, brand-new toolchain with zero
  prior project investment — the deepest unfamiliar-territory step of any
  candidate for this specific agent-driven workflow.
- Permanently multiplies the maintenance tax on every future feature and
  every future game.

## Trade-off Analysis

The real decision is between the three candidates that are actually
defensible on their own terms: **KMP/CMP**, **Flutter**, and **Native
per-platform**. Unity and Godot are each dispatched by the same two facts
regardless of their individual merits: both demand discarding the one asset
this codebase's own inspection shows is already close to portable
(8,570 lines of adversarially-reviewed logic), and both leave GameSuite's two
real differentiators — Nearby-based same-room play and fold-aware layout —
with **no engine-level path at all**, not even a weaker substitute. Unity was
also already evaluated and explicitly rejected once by this exact project for
reasons (disk space, no direct Jetpack WindowManager access) that remain true
today; nothing in this research overturns either one. Godot's genuine
strengths (MIT licensing, a real 2D engine, an improved native shader story)
would make it the right call for a *greenfield* 2D game — it is not the
right call for a codebase whose deepest investment is exactly what Godot
cannot use.

**KMP/CMP vs. Flutter** comes down to what each is actually trading away.
Flutter's headline advantage — the widest single-codebase reach (iOS +
Windows + macOS + Linux) — does not differentiate it here: Compose
Multiplatform Desktop reaches the same three desktop OSes from the same
Kotlin codebase, so Flutter's reach advantage evaporates against GameSuite's
actual stated target ("iOS and/or PC"), and what's left is the case that
Dart is a *safer full rewrite* than the alternatives. That case is real —
Dart's simplicity and CLI-native tooling are genuinely good for an
autonomous agent — but it is answering the wrong question. KMP's advantage
isn't "which language is more pleasant for an LLM to write in a vacuum," it's
"how much of GameSuite's hardest-won 8,570 lines does the agent get to skip
re-deriving and re-verifying from scratch." That is a categorically larger
win than language ergonomics, and it is not hypothetical: it was confirmed
by grepping the actual files, not inferred from the framework's reputation.
It also comes with a second, concrete asset Flutter cannot carry forward at
any price — the existing JUnit regression suite (chess/UNO/mancala/sliding-
puzzle correctness tests) ports into `commonTest` and keeps validating the
ported logic on every new target, rather than needing to be re-proven from
zero in Dart. Worth naming plainly: the Flutter research consulted for this
ADR did not surface that `flutter build ios` needs the identical Xcode/macOS
toolchain every other iOS path needs — Flutter does not actually dodge the
one infrastructure problem common to every candidate here, and the ADR
should not credit it as if it did.

**KMP/CMP vs. Native per-platform** is more one-sided. Native per-platform is
the correct fidelity ceiling to have measured this decision against, and its
own researcher's conclusion — "the right thing to have written down and
rejected with reasons, not the thing to build" — holds up under this ADR's
own numbers: at 8,570 logic lines versus 14,988 UI lines, the expensive part
of any port was never really the algorithms this candidate is built around
protecting — it's the UI and shell layers, and native-per-platform is the
one strategy that pays that cost **twice**, for a codebase that already
proved (via KMP) it doesn't have to pay it even once for the logic layer.
SwiftUI itself is mature enough for this app class (Canvas+TimelineView
holds real frame rates for the Air Hockey case; Core Haptics and
AVAudioEngine are both comparably capable to what Android offers today) — the
framework is not the problem. The problem is that none of that fidelity
advantage offsets a rebuild this much larger, for a project whose own
distinguishing engineering investment is exactly the part KMP can carry
forward and native-per-platform cannot.

**The one factor common to every iOS-capable candidate, and therefore not a
tiebreaker between them:** Kotlin/Native, Flutter, Unity, Godot, and plain
SwiftUI all require an actual macOS host to build, run, or sign an iOS
target — this project's current environment (Windows) has none of them
today. This is a real, structural gap to close, but it does not favor one
candidate over another, so it should not be allowed to silently tip the
decision toward whichever candidate's own research happened to mention it
loudest. It is treated below as a named prerequisite, sequenced so it never
blocks the parts of this decision that don't need it.

## Consequences

**Easier, once KMP/CMP is adopted:**
- New games keep following the pattern already established for Android
  (mostly-pure Kotlin logic behind a thin, per-platform-shell contract) and
  that pattern now reaches Android, Windows, macOS, iOS, and Linux at once
  instead of Android alone — a real, compounding benefit as the roadmap
  keeps growing (further board/card/puzzle games).
- The existing JUnit suite becomes a genuine cross-platform regression net
  rather than an Android-only one.
- `server/` and the online-multiplayer protocol need no rework for any new
  client.
- Desktop specifically needs zero new infrastructure and can start
  immediately in this project's actual development environment.

**Harder, and worth naming honestly:**
- `ChessGame.kt` needs a real (if small) refactor — separating its
  DataStore/`Context` persistence from its move generator — before it moves
  to `commonMain` cleanly; every other game does not need this.
- Nearby Connections has no multiplatform answer. Same-room ad-hoc play on
  iOS and desktop will need its own bespoke design (Multipeer Connectivity
  via Kotlin/Native `cinterop` on iOS; LAN/mDNS discovery reusing the
  existing host-authoritative `MultiplayerTransport` contract on desktop) —
  a real, separate piece of engineering, not a side effect of this decision.
- Real-time audio (`AudioTrack`) and AGSL shaders each need a genuine
  native-platform implementation per target behind their existing seams —
  bounded, but not free.
- Haptics and fold-posture both hit real, unavoidable hardware ceilings on
  non-Android targets (no vibration-hardware story on most desktops, no
  fold sensor on iOS/desktop) — an explicit, accepted degradation, not a
  surprise to discover mid-port.
- The iOS build/iterate loop is gated on acquiring macOS build access — a
  genuinely new kind of cost and workflow this project has not needed
  before, and one that turns "the agent iterates autonomously" into
  "the agent iterates against a remote or rented Mac" for that platform
  specifically, at least until that infrastructure is in place.

**What will need to be revisited later, deliberately, not silently:**
- Whether and when the *Android* app itself moves onto the new shared
  `commonMain` modules once they exist for a given game, so the project
  doesn't end up permanently maintaining two divergent copies of the same
  chess engine. See Action Items for the recommended sequencing — this is
  an eventual, game-by-game migration, not a requirement of this decision
  today.
- The actual design of same-room ad-hoc multiplayer on iOS/desktop, once
  the core port is proven — treated here as a deliberately separate,
  later decision rather than bundled into the initial port risk.
- Whether the not-yet-greenlit "Premium 2026 Vision" shader work happens
  before or after this port. Recommendation: **after** — AGSL/SkSL parity
  work is no harder to do once the port exists than before it, and there's
  no reason to let an ungreenlit polish pitch add scope to the riskier,
  more foundational milestone this ADR is actually deciding.

## Action Items

1. **Stand up the shared-module skeleton and validate the riskiest
   assumption first**, before touching any UI: create a new Kotlin
   Multiplatform module (e.g. `:shared`) targeting `androidCompose` +
   `desktopCompose` (JVM) to start — no iOS target yet, since it needs no
   new infrastructure and runs entirely on this project's existing Windows
   machine. Move **one simple, already-portable game's logic** —
   `TicTacToeGame.kt` is the best first pilot, given its small size and the
   project's own prior familiarity with its minimax/misère/wild logic — into
   `commonMain`, confirm it compiles with no changes beyond what direct
   inspection already predicted, and stand up a minimal Compose
   Multiplatform Desktop window around it. This is the actual test of the
   ADR's central claim; do it before committing to anything larger.
2. **Migrate that game's JUnit tests into `commonTest`** alongside step 1,
   and confirm they pass identically against both the Android and Desktop
   targets from one shared source file. This proves the regression-safety
   claim, not just the compile claim.
3. **Run a second, harder pilot on `AirHockeyGame.kt`** — the real physics
   sim, and the file with the additional `Offset` dependency and a genuine
   `withFrameNanos`-driven tick loop. This exercises the part of the "mostly
   pure Kotlin" claim this ADR leaned on hardest and is the natural next
   proof point before committing to the remaining ~11 games.
4. **Do the `ChessGame.kt` untangling as its own small, isolated pass**:
   split its `ChessPrefsStore` (DataStore/`Context`) out into a sibling file
   matching every other game's established `*PrefsStore.kt` pattern, with
   no behavior change, verified against the existing chess test coverage
   before it is ever ported. Treat this as prerequisite cleanup, not part
   of the port itself.
5. **Decide Android's own migration path explicitly, once 2-3 games have
   proven the pattern**: the recommendation is that Android's app module
   switches to depend on each game's new `commonMain` implementation as it
   lands (retiring the old Android-only copy of that one file), rather than
   maintaining two permanently-diverging implementations of the same rules.
   This should happen at the same unhurried, one-game-at-a-time cadence the
   project already uses for its per-game passes — not a single cutover, and
   not a requirement to finish before desktop/iOS work continues. Android's
   Compose **UI** layer is not required to move to Compose Multiplatform as
   part of this decision at all; it can stay native Jetpack Compose
   indefinitely, since the shared-logic module doesn't force a shared-UI
   module. Migrating the UI later, too, is a reasonable option given how
   similar the two APIs are — but it is a separate, later decision, not
   something this ADR resolves now.

   **Resolved.** Executed immediately after the three pilots above (Tic-Tac-Toe,
   Air Hockey, Chess) were each ported and independently verified, per this
   item's own "once 2-3 games have proven the pattern" trigger. `:app` now
   depends on `:shared` (`implementation(project(":shared"))`) and its own
   duplicate copies of `TicTacToeGame.kt`/`AirHockeyGame.kt`/`ChessGame.kt`
   (plus their now-redundant Android-side unit tests, superseded by
   `:shared`'s `commonTest` coverage of the identical class) are gone.

   Two real technical findings surfaced only by actually doing this, not by
   static analysis beforehand:
   - `:app` could not keep its own copies of `GameModule.kt`,
     `MultiplayerTransport.kt`, `LocalPassAndPlayTransport.kt`, and
     `CpuDifficulty` (previously declared inline in `settings/AppSettings.kt`)
     once depending on `:shared` -- both sides declare the exact same
     fully-qualified class names, so real convergence meant deleting `:app`'s
     copies of these four foundational types too, not just the three piloted
     games' own logic files. This is a genuine, if quiet, win: all 13 games
     (not just the 3 ported so far) now compile against one single canonical
     `GameModule`/transport/`CpuDifficulty`, at zero behavior change for the
     other 10, since those four types were already byte-identical duplicates.
     (Oddly, `:app:assembleDebug` did not actually fail with both copies
     briefly present on the classpath at once mid-migration -- AGP/D8
     tolerated it silently rather than erroring via
     `checkDebugDuplicateClasses`. That "it happened to build" state was
     deliberately not kept as the end state: which copy would have won at
     runtime was undefined, so the duplicates were removed regardless of the
     lucky green build.)
   - Converging onto `:shared`'s `ChessState` then broke `ChessScreen.kt`'s
     compile with "Smart cast to 'kotlin.Int' is impossible, because ... is a
     public API property declared in different module" at every
     `s.lastFrom != null && s.lastTo != null` site. This is a real, documented
     Kotlin compiler restriction: smart-casts never apply to a nullable `val`
     property -- even a plain stored one with no custom getter -- once it's
     declared in a separately-compiled module. Fixed with the standard
     workaround (bind to a local `val` first, smart-cast that instead) at
     each of the three affected sites.

   Verified, not assumed: full `:app` compile, the app's whole 37-test unit
   suite, `:shared`'s own test suite (both targets) re-confirmed unaffected,
   and a real install-and-play check on the tablet -- both a migrated game
   (Chess: a real move played, a real CPU reply, no crash) and a deliberately
   *unmigrated* game (Mancala: same check) to confirm the core-layer
   convergence didn't disturb any of the other 10 games.

   Future pilots (the remaining ~10 not-yet-ported games from Action Item 3)
   should fold this same migration step into their own pass rather than
   leaving a second, growing backlog of ported-but-not-yet-migrated `:shared`
   copies.
6. ~~Solve macOS build access as its own explicit, parallel decision...~~
   **CANCELLED (2026-09-12) — see Scope Update at the top of this document.**
   iOS is out of scope for this project entirely; no Mac or Apple toolchain
   is being acquired. Background research into the physical-Mac vs
   cloud-Mac-CI vs Apple-Developer-Program options had been dispatched
   before this instruction arrived, but was stopped along with the rest of
   the Apple-related work rather than left to finish — the scope-narrowing
   instruction was "stop attempting to build anything apple related," not
   "pause and archive first." No findings document exists, and none should
   be created; if iOS scope is ever reconsidered, that research would need
   to be redone from scratch against whatever's current at the time.
7. ~~Once macOS access exists, repeat pilots 1 and 3 against an iOS
   target...~~ **CANCELLED (2026-09-12) — see Scope Update.** No iOS target,
   simulator or device, is planned.
8. **Defer same-room ad-hoc multiplayer, AGSL/SkSL shader parity, haptics
   richness, and fold-aware layout work on Desktop until after** the core
   "this game's logic runs identically on Android and Windows" claim is
   proven for at least two pilot games (already true as of Action Items 1
   and 3). None of these four are needed to validate this ADR's central
   decision, and bundling them in early would obscure whether a build
   failure is a KMP/CMP problem or a Nearby/shader/haptics problem. Online
   multiplayer (already platform-agnostic via `server/`) is sufficient to
   prove cross-platform multiplayer works at all in the meantime, without
   touching Nearby. (Originally written with iOS included in "the new
   platforms" — narrowed to Desktop only per the Scope Update; the
   reasoning is otherwise unchanged.)

## Addendum: The True-3D (Filament/SceneView) Bet — Declined Again (2026-09-12)

This is a separate architectural question from the rest of this ADR — it has nothing to
do with the PC port — recorded here because it's this project's other live "which
rendering stack do we take on" decision, and because it directly echoes this ADR's own
Options Considered reasoning (a heavier engine only pays for itself if the app actually
needs its differentiators; GameSuite's don't).

**Background.** The "Premium 2026 Vision" pitch (delivered 2026-09-07, its Android
haptics/shader/material work already shipped) named one architectural bet across three
games: an opt-in, real Filament/SceneView-rendered true-3D moment for Checkers' king
promotion, Chess's checkmate replay, and UNO's Wild Draw Four cutscene — "one underlying
investment wearing three costumes," each reusing the app's existing `card3DFlip`-based
pseudo-3D (`graphicsLayer` transforms, no real geometry) for everything else and dropping
into a real 3D render for that one rare moment only. That pitch's own go/no-go framing
("The One Big Architectural Bet" section) already flagged this as needing a real
rendering dependency (Filament/SceneView, real per-ABI APK size), a from-scratch 3D asset
pipeline (modeling, materials, a lighting rig) nothing else in this codebase has ever
needed, meaningfully more maintenance surface than everything else in that pitch
combined, and — confirmed by the ESP32 hardware audit — no ESP32 equivalent ever
(TFT_eSPI is a 2D-only SPI display with no GPU), making it a permanently Android-only
investment. Its own "If You Only Greenlight Three Things" section did not include this
bet among the three it actually recommended funding.

**Why this is being revisited now.** This is the *second* time a real-3D engine has come
up for this project. The first time (2026-09-06, during the original animation/juice
pitch) a full 3D engine for the app broadly was judged "architecturally disproportionate"
and `Card3D.kt`'s `graphicsLayer`-based pseudo-3D was built instead — the system already
in production use by Chess's promotion flip and UNO's card animations today. The second
time (the Premium 2026 Vision pitch itself, 2026-09-07) this *narrower*, three-moment
version was raised and explicitly declined ("Not this round"). Per the project owner's
"2, then 1, then 3" sequencing on 2026-09-12, revisiting this specific bet was the first
item taken up in this pass.

**Decision: declined a second time, not deferred to a specific future trigger.** Nothing
material has changed since either prior pass that would flip the cost/benefit call: the
cost side is identical (new Gradle dependency, new asset pipeline, larger APK, Android-
only, no ESP32 path), and the benefit side is the same three once-per-game rare moments
the pitch itself estimated already get "~90%" of the premium feeling from the much
cheaper 2.5D/shader/haptics work in the same document — nearly all of which shipped
2026-09-07. Two independent passes at two different scopes (whole-app engine, then a
three-moment scoped version) have now reached the same verdict for the same underlying
reason: a real 3D pipeline is a large, permanent, single-platform investment being
weighed against animation polish, not against any gameplay or platform-reach need. This
addendum records that the bet was looked at again, not skipped — it's not being carried
forward as an open question pending some future trigger; if it's ever worth raising a
third time, that should come from a specific, named reason to want a hero moment (e.g. a
store-listing/marketing need for a shareable moment), not from periodic re-litigation of
the same three-year-old-in-spirit cost/benefit tradeoff.

**What this does not affect**: `Card3D.kt`'s existing pseudo-3D system is unaffected and
keeps shipping; the rest of the Premium 2026 Vision pitch's Android per-game
recommendations (table-material identity, haptics, shaders, hit-stop/camera-shake, sound)
and its ESP32 section are untouched by this call and remain open work, tracked by that
document's own "Recommended Sequencing" and "If You Only Greenlight Three Things"
sections — next up per the 2026-09-12 sequencing is the ESP32 hardware side of that
pitch (the quick-win bundle: Tic-Tac-Toe/Checkers/Chess stamp-ins, drop-shadow ports,
promotion pop, capture hit-stop, and the checkmate-string fix), all scoped to the
hardware audit's confirmed real capabilities.
