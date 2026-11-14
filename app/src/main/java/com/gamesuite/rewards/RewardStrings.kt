package com.gamesuite.rewards

import androidx.annotation.StringRes
import com.gamesuite.R
import com.gamesuite.core.Skill

/** Explicit resource lookups (no getIdentifier, so shrinking keeps every string). The parity test checks the names. */
@StringRes
fun Sticker.nameRes(): Int = when (id) {
    "animals_cat" -> R.string.rw_st_animals_cat
    "animals_dog" -> R.string.rw_st_animals_dog
    "animals_rabbit" -> R.string.rw_st_animals_rabbit
    "animals_frog" -> R.string.rw_st_animals_frog
    "animals_owl" -> R.string.rw_st_animals_owl
    "animals_fish" -> R.string.rw_st_animals_fish
    "animals_bear" -> R.string.rw_st_animals_bear
    "animals_penguin" -> R.string.rw_st_animals_penguin
    "space_rocket" -> R.string.rw_st_space_rocket
    "space_planet" -> R.string.rw_st_space_planet
    "space_star" -> R.string.rw_st_space_star
    "space_moon" -> R.string.rw_st_space_moon
    "space_comet" -> R.string.rw_st_space_comet
    "space_alien" -> R.string.rw_st_space_alien
    "space_helmet" -> R.string.rw_st_space_helmet
    "space_ufo" -> R.string.rw_st_space_ufo
    "food_apple" -> R.string.rw_st_food_apple
    "food_banana" -> R.string.rw_st_food_banana
    "food_cupcake" -> R.string.rw_st_food_cupcake
    "food_pizza" -> R.string.rw_st_food_pizza
    "food_icecream" -> R.string.rw_st_food_icecream
    "food_strawberry" -> R.string.rw_st_food_strawberry
    "food_cookie" -> R.string.rw_st_food_cookie
    "food_carrot" -> R.string.rw_st_food_carrot
    else -> R.string.rw_book_title
}

@StringRes
fun StickerSet.nameRes(): Int = when (this) {
    StickerSet.ANIMALS -> R.string.rw_set_animals
    StickerSet.SPACE -> R.string.rw_set_space
    StickerSet.FOOD -> R.string.rw_set_food
}

@StringRes
fun RewardReason.nameRes(): Int = when (this) {
    RewardReason.FIRST_GAME_OF_DAY -> R.string.rw_reason_first_of_day
    RewardReason.FINISHED_GAME -> R.string.rw_reason_finished
    RewardReason.WIN -> R.string.rw_reason_win
    RewardReason.FIRST_WIN -> R.string.rw_reason_first_win
    RewardReason.GAMES_3 -> R.string.rw_reason_games_3
    RewardReason.GAMES_5 -> R.string.rw_reason_games_5
    RewardReason.GAMES_10 -> R.string.rw_reason_games_10
    RewardReason.GAMES_25 -> R.string.rw_reason_games_25
    RewardReason.STREAK_3 -> R.string.rw_reason_streak_3
    RewardReason.STREAK_7 -> R.string.rw_reason_streak_7
    RewardReason.FIRST_TIME_GAME -> R.string.rw_reason_first_time
    RewardReason.TRY_SOMETHING_NEW -> R.string.rw_reason_try_new
}

@StringRes
fun Skill.nameRes(): Int = when (this) {
    Skill.MEMORY -> R.string.rw_skill_memory
    Skill.COUNTING -> R.string.rw_skill_counting
    Skill.PLANNING -> R.string.rw_skill_planning
    Skill.DEDUCTION -> R.string.rw_skill_deduction
    Skill.TURN_TAKING -> R.string.rw_skill_turn_taking
    Skill.FINE_MOTOR -> R.string.rw_skill_fine_motor
    Skill.TEAMWORK -> R.string.rw_skill_teamwork
    Skill.LANGUAGE -> R.string.rw_skill_language
    Skill.CREATIVITY -> R.string.rw_skill_creativity
}

@StringRes
fun Skill.lineRes(): Int = when (this) {
    Skill.MEMORY -> R.string.rw_skill_memory_line
    Skill.COUNTING -> R.string.rw_skill_counting_line
    Skill.PLANNING -> R.string.rw_skill_planning_line
    Skill.DEDUCTION -> R.string.rw_skill_deduction_line
    Skill.TURN_TAKING -> R.string.rw_skill_turn_taking_line
    Skill.FINE_MOTOR -> R.string.rw_skill_fine_motor_line
    Skill.TEAMWORK -> R.string.rw_skill_teamwork_line
    Skill.LANGUAGE -> R.string.rw_skill_language_line
    Skill.CREATIVITY -> R.string.rw_skill_creativity_line
}
