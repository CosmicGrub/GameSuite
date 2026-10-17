package com.gamesuite.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamesuite.R
import com.gamesuite.audio.music.MusicContext
import com.gamesuite.audio.music.MusicManager
import com.gamesuite.audio.music.MusicMessage
import com.gamesuite.settings.MusicSource
import com.gamesuite.settings.SettingsViewModel

/**
 * Music settings: source selector, the user's music folder, per-context playlists, playback
 * options and Now Playing controls. Talks to [MusicManager] for library state and to
 * [SettingsViewModel] for the persisted options.
 */
@Composable
fun MusicSettingsScreen(settingsViewModel: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = remember { MusicManager.get(context) }
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    var tabName by rememberSaveable { mutableStateOf(MusicContext.MENU.name) }
    val tab = MusicContext.values().firstOrNull { it.name == tabName } ?: MusicContext.MENU

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) manager.setFolder(uri)
    }

    val lib = state.library
    val playlist = remember(lib, tab) { lib.playlistFor(tab) }
    val byId = remember(lib.tracks) { lib.tracks.associateBy { it.id } }
    val libraryOn = settings.musicSource == MusicSource.LIBRARY

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp)
        ) {
            item {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("← " + stringResource(R.string.music_back))
                }
                Text(stringResource(R.string.music_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                MusicHeader(stringResource(R.string.music_section_source))
            }

            item {
                SourceRow(
                    selected = settings.musicSource == MusicSource.OFF,
                    title = stringResource(R.string.music_source_off),
                    description = stringResource(R.string.music_source_off_desc),
                    onSelect = { settingsViewModel.setMusicSource(MusicSource.OFF) }
                )
                SourceRow(
                    selected = settings.musicSource == MusicSource.AMBIENCE,
                    title = stringResource(R.string.music_source_ambience),
                    description = stringResource(R.string.music_source_ambience_desc),
                    onSelect = { settingsViewModel.setMusicSource(MusicSource.AMBIENCE) }
                )
                SourceRow(
                    selected = libraryOn,
                    title = stringResource(R.string.music_source_library),
                    description = stringResource(R.string.music_source_library_desc),
                    onSelect = { settingsViewModel.setMusicSource(MusicSource.LIBRARY) }
                )
                SourceRow(
                    selected = settings.musicSource == MusicSource.OTHER_APPS,
                    title = stringResource(R.string.music_source_other_apps),
                    description = stringResource(R.string.music_source_other_apps_desc),
                    onSelect = { settingsViewModel.setMusicSource(MusicSource.OTHER_APPS) }
                )
                Spacer(Modifier.height(16.dp))
            }

            state.message?.let { msg ->
                item {
                    Text(
                        stringResource(messageRes(msg)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (libraryOn) {
                item { NowPlayingCard(state.nowPlaying?.title, state.nowPlaying?.artist, state.isPlaying,
                    state.emptyPlaylist, manager::skipPrevious, manager::togglePlayPause, manager::skipNext) }
            }

            item {
                MusicHeader(stringResource(R.string.music_section_library))
                val folder = lib.folderUri
                if (folder == null) {
                    Text(stringResource(R.string.music_no_folder), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        stringResource(R.string.music_folder_current, folderLabel(folder)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (state.scanning) stringResource(R.string.music_scanning, state.scanCount)
                        else stringResource(R.string.music_track_count, lib.tracks.size),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(if (folder == null) R.string.music_choose_folder else R.string.music_change_folder))
                    }
                    if (folder != null) {
                        OutlinedButton(
                            onClick = { manager.rescan() },
                            enabled = !state.scanning,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.music_rescan)) }
                        OutlinedButton(
                            onClick = { manager.forgetFolder() },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.music_forget_folder)) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            item {
                MusicHeader(stringResource(R.string.music_section_playback))
                val pct = (settings.musicVolume * 100f).toInt()
                Text(stringResource(R.string.music_volume, pct), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.musicVolume,
                    onValueChange = settingsViewModel::setMusicVolume,
                    valueRange = 0f..1f,
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(
                            value = settings.musicShuffle,
                            role = Role.Switch,
                            onValueChange = settingsViewModel::setMusicShuffle
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.music_shuffle), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.music_shuffle_desc), style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = settings.musicShuffle, onCheckedChange = null)
                }
                Text(
                    if (settings.musicCrossfadeSeconds == 0) stringResource(R.string.music_crossfade_off)
                    else stringResource(R.string.music_crossfade_label, settings.musicCrossfadeSeconds),
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.musicCrossfadeSeconds.toFloat(),
                    onValueChange = { settingsViewModel.setMusicCrossfadeSeconds(it.toInt()) },
                    valueRange = 0f..8f,
                    steps = 7,
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                Spacer(Modifier.height(24.dp))
            }

            item {
                MusicHeader(stringResource(R.string.music_section_playlists))
                Text(stringResource(R.string.music_playlists_hint), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MusicContext.values().forEach { c ->
                        FilterChip(
                            selected = c == tab,
                            onClick = { tabName = c.name },
                            label = { Text(stringResource(contextRes(c))) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { manager.useAllTracks(tab) },
                        enabled = playlist.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(stringResource(R.string.music_use_all)) }
                }
            }

            itemsIndexed(playlist, key = { _, e -> e.trackId }) { index, entry ->
                val track = byId[entry.trackId]
                val title = track?.title ?: entry.trackId
                val artist = track?.artist?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.music_unknown_artist)
                val enabledDesc = stringResource(R.string.music_track_enabled, title)
                val upDesc = stringResource(R.string.music_move_up, title)
                val downDesc = stringResource(R.string.music_move_down, title)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = entry.enabled,
                        onCheckedChange = { manager.setTrackEnabled(tab, entry.trackId, it) },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = enabledDesc }
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(artist, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    IconButton(
                        onClick = { manager.moveTrack(tab, index, -1) },
                        enabled = index > 0,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = upDesc }
                    ) { Text("▲") }
                    IconButton(
                        onClick = { manager.moveTrack(tab, index, +1) },
                        enabled = index < playlist.lastIndex,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = downDesc }
                    ) { Text("▼") }
                }
            }
        }
    }
}

@Composable
private fun MusicHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SourceRow(selected: Boolean, title: String, description: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun NowPlayingCard(
    title: String?,
    artist: String?,
    isPlaying: Boolean,
    emptyPlaylist: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        MusicHeader(stringResource(R.string.music_section_now_playing))
        Text(
            title ?: stringResource(R.string.music_nothing_playing),
            style = MaterialTheme.typography.bodyLarge
        )
        if (!artist.isNullOrBlank()) Text(artist, style = MaterialTheme.typography.labelSmall)
        if (emptyPlaylist) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.music_empty_playlist), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPrevious, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.music_previous))
            }
            Button(onClick = onPlayPause, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (isPlaying) R.string.music_pause else R.string.music_play))
            }
            OutlinedButton(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.music_next))
            }
        }
    }
}

private fun messageRes(m: MusicMessage): Int = when (m) {
    MusicMessage.FOLDER_ACCESS_LOST -> R.string.music_msg_folder_access_lost
    MusicMessage.SCAN_FAILED -> R.string.music_msg_scan_failed
    MusicMessage.NO_TRACKS_FOUND -> R.string.music_msg_no_tracks
    MusicMessage.PLAYBACK_FAILED -> R.string.music_msg_playback_failed
    MusicMessage.FOCUS_DENIED -> R.string.music_msg_focus_denied
    MusicMessage.SAVE_FAILED -> R.string.music_msg_save_failed
}

private fun contextRes(c: MusicContext): Int = when (c) {
    MusicContext.MENU -> R.string.music_context_menu
    MusicContext.BOARD -> R.string.music_context_board
    MusicContext.CARD -> R.string.music_context_card
    MusicContext.PUZZLE_WORD -> R.string.music_context_puzzle
    MusicContext.ARCADE -> R.string.music_context_arcade
}

/** "content://.../tree/primary%3AMusic" -> "primary:Music". */
private fun folderLabel(folderUri: String): String =
    try { Uri.parse(folderUri).lastPathSegment ?: folderUri } catch (_: Exception) { folderUri }
