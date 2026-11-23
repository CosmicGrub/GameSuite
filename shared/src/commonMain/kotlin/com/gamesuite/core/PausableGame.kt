package com.gamesuite.core

/**
 * Optional companion to [GameModule.pause]/[GameModule.resume]: lets the shell ask whether the game
 * is ALREADY paused (the player pressed the game's own Pause, or the app is backgrounded). The shared
 * overlay-pause mechanism in GameChrome (see `OverlayPauseController`) checks it before pausing, so
 * it never double-pauses and never resumes a game it did not pause itself. Games that do not
 * implement it are treated as "not paused".
 */
interface PausableGame {
    val isPaused: Boolean
}
