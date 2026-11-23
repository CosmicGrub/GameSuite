package com.gamesuite.uikit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * The bookkeeping behind FLAG_SECURE, with no Android in it (so it is unit tested): any number of owners may want the
 * window secure at once (two screens overlapping during a transition, say), and the flag is on exactly while at least one
 * of them does. [apply] is called only when the combined answer changes, never repeatedly with the same value.
 */
class SecureWindowState(private val apply: (Boolean) -> Unit) {
    private val owners = LinkedHashSet<Any>()

    /** True while at least one owner wants the window secure. */
    var secure: Boolean = false
        private set

    /** Owner [owner] now wants ([wants] true) or no longer wants the window secure. Safe to call with the same value again. */
    fun set(owner: Any, wants: Boolean) {
        val changed = if (wants) owners.add(owner) else owners.remove(owner)
        if (changed) refresh()
    }

    /** Drops [owner]'s claim (what leaving a screen does). */
    fun release(owner: Any) = set(owner, false)

    /** Drops every claim and clears the flag. */
    fun clear() {
        owners.clear()
        refresh()
    }

    val ownerCount: Int get() = owners.size

    private fun refresh() {
        val now = owners.isNotEmpty()
        if (now != secure) {
            secure = now
            apply(now)
        }
    }
}

/**
 * Keeps the Activity window out of screenshots and the Recents thumbnail (FLAG_SECURE) ONLY while something secret
 * (a card to act out, the word to draw) is on screen, so a glance at the task switcher cannot spoil it. The flag is
 * cleared the moment [active] turns false or the composable leaves, so screenshots work everywhere else in the app.
 */
object SecureWindow {
    private val states = WeakHashMap<Window, SecureWindowState>()

    private fun stateFor(window: Window): SecureWindowState = states.getOrPut(window) {
        SecureWindowState { on ->
            if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun set(window: Window, owner: Any, wants: Boolean) = stateFor(window).set(owner, wants)

    internal fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/** See [SecureWindow]: the window is secure while [active] is true and this composable is in the composition. */
@Composable
fun SecureWindowEffect(active: Boolean) {
    val window = SecureWindow.activityOf(LocalContext.current)?.window ?: return
    val owner = remember { Any() }
    DisposableEffect(window, active) {
        SecureWindow.set(window, owner, active)
        onDispose { SecureWindow.set(window, owner, false) }
    }
}
