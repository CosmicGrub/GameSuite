# Google Play Families Policy checklist (draft)

Whether to opt in is an **owner decision** (TEAM-PLAN "kids-category listing"). There are two routes:

- **A. General audience app with a parental-control feature** (adults and children both use it). No Families
  programme enrolment; target age groups are 13+ or "all ages including children" is *not* ticked.
- **B. Designed for Families / child-directed.** Selecting any age group under 13 as target audience triggers the
  Families Policy for the whole app. Because the catalog has adult-only and gambling-themed games in the same app,
  route B needs the five gambling-themed games and the adult-only word games removed or split out, or the app
  being declared as mixed-audience with extra care (a mixed-audience app that appeals to children must still meet
  the policy's data rules for children).

Status below is against the code at 3d69c97. Pass = true in code; Gap = needs work; Owner = a decision or
Console action.

| # | Requirement | Status | Evidence / action |
| --- | --- | --- | --- |
| 1 | Ads: none, or only certified family-safe ad SDKs | Pass | No ad SDK in `app/build.gradle.kts`. |
| 2 | No personal or sensitive data collected from children; no transmission of advertising ID, device IDs, location | Pass | Child profiles have no Online/Nearby (`ProfileGuard`, `visibleFor`); no analytics/crash SDK; no location read; name/avatar/age band stay on device (see PRIVACY_POLICY.md). |
| 3 | Age band, not exact age or birthdate | Pass | `AgeBand` 4-6 / 7-9 / 10-12 only. |
| 4 | Only APIs and SDKs approved for child-directed use | Gap (verify) | Google Play Services Nearby is only reachable from adult profiles. Confirm in Console's SDK check after upload; it may list Play Services components regardless of reach. |
| 5 | No real-money gambling, no simulated gambling in a kids app | Pass for kid profiles, Gap for route B | Five games are blocked from kid profiles (`KID_BLOCKED_GAME_IDS`) but still ship in the same APK. Under route B they must go. |
| 6 | Content suitable for the age group (no inappropriate violence, language, themes) | Owner | Review tank-battle and toy-boxing for the lowest bands; dictionary content in word games is adult-only in code but confirm word lists are filtered (see CONTENT-RATING.md). |
| 7 | No misleading or manipulative design: no dark patterns, fake countdowns, pressure to play | Owner | Review the rewards engine/sticker book (Team B) when it lands; it must not use streak guilt or time-limited pressure on children. |
| 8 | No in-app purchases or links out without a parental gate | Pass | No purchases, no external links found in app code. Re-check after any "rate this app" or "privacy policy" link is added: it must sit behind the parent gate. |
| 9 | Parental gate protects anything leaving the child area | Pass | `ParentGateScreen` (PIN, system screen lock, or arithmetic question); the controls are only reachable through it. |
| 10 | Accurate Data safety form and a privacy policy link | Gap | Policy drafted (PRIVACY_POLICY.md) and form sheet drafted (DATA-SAFETY.md); the policy must be hosted at a public URL. |
| 11 | Privacy policy readable by parents and states child-data handling | Pass (draft) | "Children" section rewritten for profiles. Owner review of wording. |
| 12 | No user-generated content or chat shared with children; moderation if any | Pass | No free-text chat; child profiles have no multiplayer. |
| 13 | Time and usage controls honest | Pass | Daily limit, quiet hours, clock-tamper-resistant limiter (`PlayTimeLimiter`). |
| 14 | App works without sign-in | Pass | No accounts. |
| 15 | Store listing for children: no screenshots with adult content or gambling art, no unsupported claims ("educational" claims need substance) | Owner | Use the screenshots in SCREENSHOTS.md; "what this teaches" lines (Team B) can support honest educational wording. |
| 16 | Target API level meets current policy | Gap | See TARGET-SDK.md. |
| 17 | Content rating questionnaire answered truthfully, consistent with Families targeting | Owner | See CONTENT-RATING.md: the simulated-gambling answer conflicts with a kids-only listing. |
| 18 | Spanish and English listing both accurate | Owner | Native Spanish review pending. |

## Decision matrix

| If the owner picks | Needed before submission |
| --- | --- |
| **A, general audience with parental controls** | Rating will reflect gambling themes. Say plainly in the listing that child profiles exist and hide those games. Rows 4, 10, 16 closed. |
| **B, Designed for Families** | Remove or move out blackjack, texas-holdem, hanafuda, slot-cars, pig-dice, backgammon (doubling cube) and the adult-only word games; rows 4 to 18 all closed; Play will review for the programme. |
