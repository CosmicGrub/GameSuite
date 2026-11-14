package com.gamesuite.ui.profiles

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameSessionManager
import com.gamesuite.profiles.Profile
import com.gamesuite.profiles.ProfileAccess
import com.gamesuite.profiles.ProfileGuard
import com.gamesuite.profiles.ProfilesViewModel
import com.gamesuite.rewards.RewardsStore
import com.gamesuite.rewards.StickerBook
import com.gamesuite.rewards.StickerBookScreen

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Registers the five profile routes ("profile-picker", "profile-add", "parent-gate", "parent-dashboard",
 * "kids-home") in the NavHost. MainActivity adds exactly this one call inside its NavHost builder.
 */
fun NavGraphBuilder.profileRoutes(
    vm: ProfilesViewModel,
    sessionManager: GameSessionManager,
    navController: NavHostController,
    access: ProfileAccess?
) {
    /** After choosing a profile: back to the shell root, then straight into Kids home for a kid. */
    fun enter(p: Profile) {
        navController.popBackStack("menu", inclusive = false)
        if (p.isKid) navController.navigate(ProfileGuard.KIDS_HOME) { launchSingleTop = true }
    }

    composable(ProfileGuard.PICKER) {
        ProfilePickerScreen(
            viewModel = vm,
            onPicked = { p -> vm.switchTo(p.id); enter(p) },
            onAddProfile = { navController.navigate(ProfileGuard.ADD_PROFILE) },
            onBack = if (navController.previousBackStackEntry != null) ({ navController.popBackStack() }) else null
        )
    }
    composable(ProfileGuard.ADD_PROFILE) {
        AddProfileScreen(viewModel = vm, onDone = { navController.popBackStack() }, onCancel = { navController.popBackStack() })
    }
    composable(ProfileGuard.PARENT_GATE) {
        val gateControls by vm.controls.collectAsStateWithLifecycle()
        ParentGateScreen(
            controls = gateControls,
            ageBand = access?.profile?.ageBand,
            onUnlocked = {
                vm.setParentUnlocked(true)
                navController.navigate(ProfileGuard.PARENT_DASHBOARD) {
                    popUpTo(ProfileGuard.PARENT_GATE) { inclusive = true }
                    launchSingleTop = true
                }
            },
            onCancel = { navController.popBackStack() }
        )
    }
    composable(ProfileGuard.PARENT_DASHBOARD) {
        ParentDashboardScreen(
            viewModel = vm,
            allSpecs = GameHubSpecs.all,
            onSwitchProfile = { navController.navigate(ProfileGuard.PICKER) { launchSingleTop = true } },
            onBack = {
                val kid = access?.profile?.isKid == true
                if (!navController.popBackStack()) navController.navigate(if (kid) ProfileGuard.KIDS_HOME else "menu")
            }
        )
    }
    composable(ProfileGuard.KIDS_HOME) {
        // A kid is back in kid land: the parent window is closed again.
        LaunchedEffect(Unit) { vm.setParentUnlocked(false) }
        // Back on Kids home must not pop to "menu" (the guard would push Kids home straight back: a loop that also
        // composes the adult menu for a frame). Put the app in the background instead, like Home does for an adult.
        val hostContext = LocalContext.current
        BackHandler { hostContext.findActivity()?.moveTaskToBack(true) }
        val controls by vm.controls.collectAsStateWithLifecycle()
        val profile = access?.profile
        if (profile != null && profile.isKid) {
            val kc = controls.forKid(profile.id)
            val total = kc.dailyMinutes
            val played = vm.limiter.playedToday(kc)
            val leftMinutes: Int? = if (total <= 0) null else (((total * 60_000L) - played).coerceAtLeast(0L) / 60_000L).toInt()
            val fraction = if (total <= 0) 1f else (leftMinutes ?: 0).toFloat() / total
            // Rewards: this kid's own sticker book (profile-scoped store) and the sticker book screen as a state of this
            // route (so the kid allow-list in ProfileGuard needs no new route). The reward pop-up lives in MainActivity.
            val rewardsStore = remember { RewardsStore(hostContext.applicationContext) }
            val book by rewardsStore.book.collectAsStateWithLifecycle(initialValue = StickerBook())
            var showBook by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = showBook) { showBook = false }
            if (showBook) {
                KidsTheme { StickerBookScreen(book = book, onBack = { showBook = false }) }
            } else KidsHomeScreen(
                profile = profile,
                stickerCount = book.collectedCount,
                onOpenStickers = { showBook = true },
                specs = access.visibleSpecs,
                sessionManager = sessionManager,
                timeLeftMinutes = leftMinutes,
                timeLeftFraction = fraction,
                onNavigate = { route -> navController.navigate(route) },
                onOpenParent = { navController.navigate(ProfileGuard.PARENT_GATE) { launchSingleTop = true } }
            )
        }
    }
}
