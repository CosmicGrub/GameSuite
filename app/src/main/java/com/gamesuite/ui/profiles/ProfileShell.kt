package com.gamesuite.ui.profiles

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.gamesuite.R
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameResult
import com.gamesuite.core.GameSessionManager
import com.gamesuite.core.LaunchPolicy
import com.gamesuite.core.ProfileScope
import com.gamesuite.core.SavedGameStore
import com.gamesuite.profiles.LimitState
import com.gamesuite.profiles.ProfileAccess
import com.gamesuite.profiles.ProfileGuard
import com.gamesuite.profiles.ProfilesViewModel
import com.gamesuite.ui.AppTab
import kotlinx.coroutines.delay

/**
 * The game specs the active profile may see (Games tab, Home, Hubs read this instead of GameHubSpecs.all).
 * FAIL-CLOSED default: the kid-safe subset (no network modes), so a screen composed outside the provider (or before
 * the shell has resolved access) can never show the full catalogue.
 */
val LocalVisibleSpecs = staticCompositionLocalOf<List<GameHubSpec>> {
    ProfileAccess.restricted(GameHubSpecs.all).visibleSpecs
}

/**
 * The [ProfileAccess] for the active profile + controls. Never null: until the profile list and the parent
 * controls have both been read (or when the stored list is unreadable) it is the restricted kid-safe access.
 */
@Composable
fun rememberProfileAccess(vm: ProfilesViewModel): ProfileAccess {
    val state by vm.profiles.collectAsStateWithLifecycle()
    val controls by vm.controls.collectAsStateWithLifecycle()
    val unlocked by vm.parentUnlocked.collectAsStateWithLifecycle()
    val loaded by vm.controlsLoaded.collectAsStateWithLifecycle()
    val unreadable by vm.controlsUnreadable.collectAsStateWithLifecycle()
    val active = state?.active
    // Keyed on what access actually depends on (not the whole controls: the 15 s usage ticks must not rebuild it).
    val hidden: Set<String> = if (active != null && active.isKid) controls.forKid(active.id).hiddenGameIds else emptySet()
    return remember(active, controls.onlinePlayEnabled, hidden, unlocked, loaded, unreadable) {
        ProfileAccess.resolve(state, controls, loaded, unreadable, unlocked, GameHubSpecs.all)
    }
}

/** Routes where no round is being played (a kid may be stopped at once there). */
private fun isIdleRoute(route: String?): Boolean =
    route == null || AppTab.forRoute(route) != null || route.startsWith("hub/") ||
        route == ProfileGuard.KIDS_HOME || route == ProfileGuard.PICKER || route == ProfileGuard.ADD_PROFILE ||
        route == ProfileGuard.PARENT_GATE || route == ProfileGuard.PARENT_DASHBOARD || route == "music"

private fun isParentArea(route: String?): Boolean =
    route == ProfileGuard.PARENT_GATE || route == ProfileGuard.PARENT_DASHBOARD ||
        route == ProfileGuard.PICKER || route == ProfileGuard.ADD_PROFILE

/**
 * THE one profile observer for the shell. It (1) mirrors the network switch into [LaunchPolicy], (2) re-points
 * saved games at the active profile's directory, (3) guards navigation: a route the profile may not open
 * (lobbies for kids / Online off, hidden games, any non-kids route for a kid) is redirected home, (4) shows the
 * "Who's playing?" picker once at launch when more than one profile exists, (5) accrues foreground play time
 * for a kid every 15 s, warns at 5 minutes left and shows the time-up screen when the limit or quiet hours
 * begin and no round is in progress (a round in progress is allowed to finish, up to a 10 minute grace).
 */
@Composable
fun ProfileShellObserver(
    vm: ProfilesViewModel,
    access: ProfileAccess?,
    navController: NavHostController,
    sessionManager: GameSessionManager
) {
    val context = LocalContext.current
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val gameIdArg = backStack?.arguments?.getString("gameId")
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val controls by vm.controls.collectAsStateWithLifecycle()
    val profile = access?.profile

    // Fail-closed: only an access that resolved to "adult with Online play on" opens the network.
    SideEffect { LaunchPolicy.networkAllowed = access?.networkAllowed == true }

    // Saved games follow the profile.
    LaunchedEffect(profile?.id) {
        val id = profile?.id ?: return@LaunchedEffect
        sessionManager.savedGames = SavedGameStore(ProfileScope.savedGamesDir(context.filesDir, id))
    }

    // Navigation guard (also covers a deep route reaching a blocked screen).
    LaunchedEffect(route, gameIdArg, access) {
        if (access == null || route == null) return@LaunchedEffect
        if (!access.allowsRoute(route, gameIdArg)) {
            val home = ProfileGuard.homeRoute(access.profile)
            navController.navigate(home) {
                popUpTo("menu") { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // First-launch picker, only when there is a real choice.
    LaunchedEffect(profiles != null) {
        val st = profiles ?: return@LaunchedEffect
        if (!vm.pickerHandled) {
            vm.pickerHandled = true
            if (st.needsPicker) navController.navigate(ProfileGuard.PICKER) { launchSingleTop = true }
        }
    }

    // Play time: foreground ticks only (repeatOnLifecycle RESUMED), kid profiles only.
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(profile?.id, lifecycleOwner) {
        val pid = profile?.id
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last = SystemClock.elapsedRealtime()
            tick = System.currentTimeMillis() // re-evaluate the limit / quiet state right after a resume, not 15 s later
            try {
                while (true) {
                    delay(TICK_MILLIS)
                    val now = SystemClock.elapsedRealtime()
                    vm.accrue(now - last, pid)
                    last = now
                    tick = System.currentTimeMillis()
                }
            } finally {
                // Pause / profile switch: credit the partial interval to the profile it was played under.
                vm.accrue(SystemClock.elapsedRealtime() - last, pid)
            }
        }
    }

    // A time-up kid cannot start a NEW game (Hub Play, Continue, Resume are refused in LaunchPolicy).
    SideEffect { if (profile == null || !profile.isKid) LaunchPolicy.timeUp = false }
    if (profile == null || !profile.isKid) return
    val kc = controls.forKid(profile.id)
    val inRound = !isIdleRoute(route)
    val status = remember(kc, inRound, tick) { vm.limiter.check(kc, inRound, System.currentTimeMillis()) }
    SideEffect { LaunchPolicy.timeUp = !status.canStartNewGame }

    // Opening a game route while time is up (a launch was refused, or a stale back stack): back to Kids home,
    // where the time-up screen takes over. A round already running when the limit begins is not touched here.
    val routeHolder = remember { arrayOf<String?>(null) }
    LaunchedEffect(route, status.canStartNewGame) {
        val wasIdle = routeHolder[0] == null || isIdleRoute(routeHolder[0])
        routeHolder[0] = route
        if (!status.canStartNewGame && inRound && wasIdle) {
            navController.navigate(ProfileGuard.KIDS_HOME) { popUpTo("menu") { inclusive = false }; launchSingleTop = true }
        }
    }

    val warnText = stringResource(R.string.prof_warn_toast)
    var warnedDay by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(status.state) {
        val day = vm.limiter.epochDay()
        if (status.state == LimitState.WARN && warnedDay != day) {
            warnedDay = day
            Toast.makeText(context, warnText, Toast.LENGTH_LONG).show()
        }
    }

    // Overran the grace period inside a game: leave the game, the dialog below then takes over.
    LaunchedEffect(status.mustStopNow, inRound) {
        if (status.mustStopNow && inRound) {
            // Park the game first (the existing saved-game mechanism, same as GameChrome "Leave"), then end it as an
            // abort so nothing is recorded and no live context / transport is left behind.
            sessionManager.saveActiveProgress()
            sessionManager.endActiveGame(GameResult(wasAborted = true))
            navController.navigate(ProfileGuard.KIDS_HOME) { popUpTo("menu") { inclusive = false }; launchSingleTop = true }
        }
    }

    if (status.mustStopNow && !isParentArea(route)) {
        val quiet = status.state == LimitState.QUIET_HOURS
        val endText = "%02d:%02d".format(kc.quietEndMinute / 60, kc.quietEndMinute % 60)
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
        ) {
            TimeUpScreen(quiet = quiet, quietEndText = endText, onParent = {
                navController.navigate(ProfileGuard.PARENT_GATE) { launchSingleTop = true }
            })
        }
    }
}

private const val TICK_MILLIS = 15_000L

/** The friendly "time is up" (or quiet-time) screen for a kid. */
@Composable
fun TimeUpScreen(quiet: Boolean, quietEndText: String, onParent: () -> Unit) {
    KidsTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(if (quiet) "🌙" else "⏰", fontSize = 96.sp)
                Text(
                    stringResource(if (quiet) R.string.prof_timeup_quiet_title else R.string.prof_timeup_title),
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    if (quiet) stringResource(R.string.prof_timeup_quiet_body, quietEndText) else stringResource(R.string.prof_timeup_body),
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
                )
                TextButton(onClick = onParent, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.prof_timeup_parent))
                }
            }
        }
    }
}
