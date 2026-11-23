// seat-ranges.json is generated from the Kotlin Game Hub specs (gen-seat-ranges.js) and checked in. This test keeps
// it honest: it must equal what the Kotlin sources say today, be internally sane, and its online seat cap must equal the
// relay's MAX_PLAYERS_PER_ROOM (the Kotlin constant ONLINE_MAX_SEATS had no test tying it to the relay: design doc GAP).
// When the Kotlin sources are not in the checkout (a server-only Docker context), the freshness check is skipped, loudly.
const fs = require('fs')
const path = require('path')

let pass = 0
let fail = 0
function check(label, cond, detail) {
  if (cond) { pass++; console.log('PASS:', label) } else { fail++; console.log('FAIL:', label + (detail !== undefined ? `   [${JSON.stringify(detail)}]` : '')) }
}

const checkedIn = require('./seat-ranges.json')
const gen = require('./gen-seat-ranges')
const kotlinPresent = fs.existsSync(path.resolve(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'gamesuite', 'core', 'GameHubSpecs.kt'))

if (kotlinPresent) {
  const fresh = gen.generate()
  check('[seats] seat-ranges.json equals what GameHubSpecs.kt and the per-family specs say (run `node gen-seat-ranges.js` if this fails)',
    JSON.stringify(fresh) === JSON.stringify(checkedIn), { regenerate: 'node gen-seat-ranges.js' })
  const onDisk = fs.readFileSync(path.join(__dirname, 'seat-ranges.json'), 'utf8').replace(/\r\n/g, '\n')
  check('[seats] the checked-in file is byte-identical to the generator output (stable formatting)', onDisk === gen.render(fresh))
} else {
  console.log('SKIP: Kotlin sources not in this checkout; seat-ranges.json freshness not checked')
}

const indexSrc = fs.readFileSync(path.join(__dirname, 'index.js'), 'utf8')
const cap = /const MAX_PLAYERS_PER_ROOM = (\d+)/.exec(indexSrc)
check('[seats] onlineMaxSeats equals the relay\'s MAX_PLAYERS_PER_ROOM (Kotlin ONLINE_MAX_SEATS <-> index.js parity)', !!cap && parseInt(cap[1], 10) === checkedIn.onlineMaxSeats, { relay: cap && cap[1], file: checkedIn.onlineMaxSeats })

const games = Object.entries(checkedIn.games)
check('[seats] the 42 networked games of the hub are all present', games.length === 42, games.length)
check('[seats] every range is 1 <= min <= max <= onlineMaxSeats', games.every(([, r]) => Number.isInteger(r.min) && Number.isInteger(r.max) && r.min >= 1 && r.min <= r.max && r.max <= checkedIn.onlineMaxSeats), games.filter(([, r]) => !(r.min >= 1 && r.min <= r.max && r.max <= checkedIn.onlineMaxSeats)))
check('[seats] every id is a valid queue gameId', games.every(([id]) => /^[a-z0-9-]{1,32}$/.test(id)))
const spot = (id, min, max) => checkedIn.games[id] && checkedIn.games[id].min === min && checkedIn.games[id].max === max
check('[seats] spot checks: chess 2-2, ludo 2-4, president 3-6, uno 2-8 (clamped from 10), chinese-checkers 2-6, texas-holdem 2-6',
  spot('chess', 2, 2) && spot('ludo', 2, 4) && spot('president', 3, 6) && spot('uno', 2, 8) && spot('chinese-checkers', 2, 6) && spot('texas-holdem', 2, 6), checkedIn.games)

// the generator's parser on a synthetic spec, so a change in how specs are written fails here with a clear message
const parsed = gen.parseSpecs(`
  GameHubSpec(gameId = "demo", modes = listOf(
    HubMode(id = "solo", kind = HubModeKind.SOLO, route = "demo", minSeats = 1, maxSeats = 1),
    HubMode(id = "pass", kind = HubModeKind.PASS_AND_PLAY, route = "demo", minSeats = 3, maxSeats = 5, options = listOf(f(1, 2))),
    // HubMode(id = "x", kind = HubModeKind.VS_CPU, maxSeats = 99)
    HubMode(id = "cpu", kind = HubModeKind.VS_CPU, route = "demo")
  ))
  twoSeat("tiny", "Tiny", "tagline")
`)
check('[generator] parses explicit modes (seats, defaults of 2, nested parentheses) and ignores commented-out ones',
  parsed.length === 2 && parsed[0].gameId === 'demo' && JSON.stringify(parsed[0].modes) === JSON.stringify([{ kind: 'SOLO', min: 1, max: 1 }, { kind: 'PASS_AND_PLAY', min: 3, max: 5 }, { kind: 'VS_CPU', min: 2, max: 2 }]), parsed)
check('[generator] twoSeat(...) is a 2-seat game', parsed[1].gameId === 'tiny' && parsed[1].modes.every((m) => m.min === 2 && m.max === 2))

console.log(`\nseat-ranges-test: ${pass} passed, ${fail} failed`)
process.exit(fail === 0 ? 0 : 1)
