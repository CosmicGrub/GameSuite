#!/usr/bin/env node
// Generates server/seat-ranges.json: the seat range of every networked game, which the matchmaking queue uses to
// clamp and validate a ticket's minSeats/maxSeats (a forged range can only waste the forger's own time).
//
// Source of truth is the Kotlin Game Hub specs, parsed here as plain text (no Gradle, no JVM):
//   app/src/main/java/com/gamesuite/core/GameHubSpecs.kt          networkedGames, ONLINE_MAX_SEATS, twoSeat(...) and inline specs
//   app/src/main/java/com/gamesuite/games/**/*HubSpecs.kt         per-family GameHubSpec(gameId = "...") blocks
// and the rule GameHubSpecs.networkModes() applies: Online minSeats = the smallest minSeats among the game's
// shared-table modes (everything except SOLO and DAILY, default 2), Online maxSeats = the largest maxSeats of any
// mode, clamped to ONLINE_MAX_SEATS. A HubMode that names no minSeats/maxSeats seats 2 (the Kotlin default).
//
// Usage:
//   node gen-seat-ranges.js            rewrite seat-ranges.json
//   node gen-seat-ranges.js --check    exit 1 if the checked-in file differs from what the Kotlin sources say
//
// A Kotlin-side test (HubSpecServerParityTest, design doc 7.2) can additionally compare GameHubSpecs with the JSON;
// this script plus seat-ranges-test.js is the part that does not need Gradle.
const fs = require('fs')
const path = require('path')

const REPO_ROOT = path.resolve(__dirname, '..')
const CORE = path.join(REPO_ROOT, 'app', 'src', 'main', 'java', 'com', 'gamesuite', 'core', 'GameHubSpecs.kt')
const GAMES_DIR = path.join(REPO_ROOT, 'app', 'src', 'main', 'java', 'com', 'gamesuite', 'games')
const OUT = path.join(__dirname, 'seat-ranges.json')

function findHubSpecFiles(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name)
    if (entry.isDirectory()) out.push(...findHubSpecFiles(p))
    else if (/HubSpecs\.kt$/.test(entry.name)) out.push(p)
  }
  return out.sort()
}

/** Source with // line comments and block comments blanked out (same length), so KDoc examples never match. */
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, (m) => ' '.repeat(m.length))
}

/** The text of the balanced parenthesis group whose '(' is at `open`. */
function balanced(src, open) {
  let depth = 0
  let inString = false
  for (let i = open; i < src.length; i++) {
    const ch = src[i]
    if (inString) {
      if (ch === '\\') i++
      else if (ch === '"') inString = false
      continue
    }
    if (ch === '"') inString = true
    else if (ch === '(') depth++
    else if (ch === ')') {
      depth--
      if (depth === 0) return src.slice(open, i + 1)
    }
  }
  throw new Error('unbalanced parentheses in Kotlin source')
}

/** [{gameId, modes:[{kind, min, max}]}] for every spec in `src`. A spec starts at `gameId = "x"` or `twoSeat("x"`
 *  and runs to the next one (or the end of the file); the HubMode(...) calls inside are its modes. */
function parseSpecs(src) {
  const clean = stripComments(src)
  const markers = []
  const re = /\bgameId\s*=\s*"([a-z0-9-]+)"|\btwoSeat\(\s*"([a-z0-9-]+)"/g
  let m
  while ((m = re.exec(clean)) !== null) markers.push({ id: m[1] || m[2], at: m.index, twoSeat: !!m[2] })
  const specs = []
  markers.forEach((marker, i) => {
    const end = i + 1 < markers.length ? markers[i + 1].at : clean.length
    const body = clean.slice(marker.at, end)
    const modes = []
    if (marker.twoSeat) {
      // twoSeat() builds a VS_CPU and a PASS_AND_PLAY mode, both 2..2.
      modes.push({ kind: 'VS_CPU', min: 2, max: 2 }, { kind: 'PASS_AND_PLAY', min: 2, max: 2 })
    } else {
      const modeRe = /\bHubMode\(/g
      let mm
      while ((mm = modeRe.exec(body)) !== null) {
        const call = balanced(body, mm.index + 'HubMode'.length)
        const kind = /\bkind\s*=\s*HubModeKind\.([A-Z_]+)/.exec(call)
        if (!kind) throw new Error(`HubMode without a literal kind in spec ${marker.id}`)
        const min = /\bminSeats\s*=\s*(\d+)/.exec(call)
        const max = /\bmaxSeats\s*=\s*(\d+)/.exec(call)
        modes.push({ kind: kind[1], min: min ? parseInt(min[1], 10) : 2, max: max ? parseInt(max[1], 10) : 2 })
      }
    }
    specs.push({ gameId: marker.id, modes })
  })
  return specs
}

function generate() {
  const core = fs.readFileSync(CORE, 'utf8')
  const cleanCore = stripComments(core)

  const onlineMax = /const\s+val\s+ONLINE_MAX_SEATS\s*:\s*Int\s*=\s*(\d+)/.exec(cleanCore)
  if (!onlineMax) throw new Error('ONLINE_MAX_SEATS not found in GameHubSpecs.kt')
  const ONLINE_MAX_SEATS = parseInt(onlineMax[1], 10)

  const netBlock = /val\s+networkedGames\s*:\s*Set<String>\s*=\s*setOf\(([\s\S]*?)\)\s*\n\s*val\s+all/.exec(cleanCore)
  if (!netBlock) throw new Error('networkedGames set not found in GameHubSpecs.kt')
  const networked = [...netBlock[1].matchAll(/"([a-z0-9-]+)"/g)].map((x) => x[1])
  if (networked.length === 0) throw new Error('networkedGames parsed as empty')

  const specs = new Map()
  for (const file of [CORE, ...findHubSpecFiles(GAMES_DIR)]) {
    for (const spec of parseSpecs(fs.readFileSync(file, 'utf8'))) {
      if (spec.modes.length > 0) specs.set(spec.gameId, spec)
    }
  }

  const games = {}
  for (const id of networked.slice().sort()) {
    const spec = specs.get(id)
    if (!spec) throw new Error(`networked game "${id}" has no parsable Game Hub spec`)
    const shared = spec.modes.filter((mode) => mode.kind !== 'SOLO' && mode.kind !== 'DAILY')
    const min = shared.length ? Math.min(...shared.map((mode) => mode.min)) : 2
    const max = Math.min(Math.max(...spec.modes.map((mode) => mode.max)), ONLINE_MAX_SEATS)
    games[id] = { min, max }
  }
  return {
    _comment: 'GENERATED by server/gen-seat-ranges.js from app/src/main/java/com/gamesuite/core/GameHubSpecs.kt and games/**/*HubSpecs.kt. Do not edit by hand; run `node gen-seat-ranges.js`. min/max are the Online seat range of each networked game (GameHubSpecs.networkModes).',
    onlineMaxSeats: ONLINE_MAX_SEATS,
    games,
  }
}

function render(data) {
  return JSON.stringify(data, null, 2) + '\n'
}

if (require.main === module) {
  const text = render(generate())
  if (process.argv.includes('--check')) {
    const current = fs.existsSync(OUT) ? fs.readFileSync(OUT, 'utf8').replace(/\r\n/g, '\n') : ''
    if (current !== text) {
      console.error('server/seat-ranges.json is out of date with GameHubSpecs.kt: run `node gen-seat-ranges.js` in server/')
      process.exit(1)
    }
    console.log('seat-ranges.json is up to date')
  } else {
    fs.writeFileSync(OUT, text)
    console.log(`wrote ${OUT}`)
  }
}

module.exports = { generate, render, parseSpecs, stripComments }
