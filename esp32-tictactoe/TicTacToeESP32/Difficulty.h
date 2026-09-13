#pragma once
#include <Arduino.h>

// Shared AI strength tiers -- deliberately its own tiny header rather than
// living inside any one game's Logic.h: Mancala is the first ESP32 game to
// actually use it, but the standing project roadmap already calls for
// retrofitting the same three tiers onto Tic-Tac-Toe/Checkers/Chess/UNO's
// existing single-strength AIs later. When that retrofit happens, those
// games should #include "Difficulty.h" and reuse this exact enum rather
// than each declaring their own -- one "AI difficulty" concept/type across
// the whole arcade instead of N incompatible copies that all mean the same
// thing. Mirrors GameSuite's own KMP CpuDifficulty (com.gamesuite.settings)
// tier-for-tier: EASY < MEDIUM < HARD, weakest to strongest.
enum class CpuDifficulty : uint8_t { EASY, MEDIUM, HARD };
