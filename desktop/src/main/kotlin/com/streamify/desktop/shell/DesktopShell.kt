package com.streamify.desktop.shell

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.streamify.shared.components.MiniPlayerBar
import com.streamify.shared.components.MiniPlayerBarState
import com.streamify.shared.components.VolumeSlider
import com.streamify.shared.model.PlaybackStatus
import com.streamify.shared.model.PlaybackUiState
import com.streamify.shared.model.formatPlaybackTime
import com.streamify.shared.state.PlaybackController
import com.streamify.shared.theme.StreamifyPalette
import com.streamify.shared.theme.StreamifySpacing
import com.streamify.shared.theme.StreamifyTextStyles
import kotlinx.coroutines.launch

/**
 * Phase 5 (MP-UI04 / Beta): the responsive Desktop Compose Multiplatform
 * shell.
 *
 * Layout ladder:
 *  ```
 *  ┌──────────────────────────────────────────────────────┐
 *  │ WindowFrame (custom undecorated title bar)           │
 *  ├────────────┬─────────────────────────────────────────┤
 *  │            │  adaptive content pane                  │
 *  │ NavRail    │  (Home / Search / Library / Jam)        │
 *  │ (expand ↔  │                                         │
 *  │  compact)  │                                         │
 *  ├────────────┴─────────────────────────────────────────┤
 *  │ MiniPlayerBar (shared component, engine-backed)      │
 *  └──────────────────────────────────────────────────────┘
 *  ```
 *
 *  * ADAPTIVE LAYOUT: [BoxWithConstraints] resolves the rail mode per
 *    width band (>= 1000 dp expanded, below icon-only); the hamburger
 *    toggles it manually with band-crossing reset semantics
 *    (see ShellNavModel).
 *  * KEYBOARD SHORTCUTS: Space play/pause, Ctrl+Right/Left track skip,
 *    Ctrl+F focus search — dispatched from a BUBBLE-phase [onKeyEvent] so
 *    focused text entry consumes its own keys first.
 *  * ENGINE STATE: every rendered value (status, volume, position, buffer
 *    health) comes from the push flow via [PlaybackController.state] —
 *    playback position derives strictly from the hardware DAC
 *    presentation clock (MP-DEC-03); zero polling (MP-DEC-04).
 */
@Composable
fun WindowScope.DesktopShell(
    controller: PlaybackController?,
    windowState: WindowState,
    onMinimize: () -> Unit,
    onCloseRequest: () -> Unit,
) {
    val uiState: PlaybackUiState =
        controller?.state?.collectAsState()?.value ?: PlaybackUiState.IDLE

    var section by remember { mutableStateOf(ShellSection.HOME) }
    var manualRailToggle by remember { mutableStateOf<RailMode?>(null) }
    var manualRailBand by remember { mutableStateOf<RailMode?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    val rootFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(StreamifyPalette.BgBase)) {
        // Capture the constraint explicitly: the toggle callback below is a
        // plain (non-composable) lambda, where the BoxWithConstraintsScope
        // receiver is not implicitly in scope.
        val contentWidth = maxWidth
        val railMode = effectiveRailMode(contentWidth, manualRailToggle, manualRailBand)

        Column(modifier = Modifier.fillMaxSize()) {
            WindowFrame(
                windowState = windowState,
                onMinimize = onMinimize,
                onCloseRequest = onCloseRequest,
            )

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                NavigationRail(
                    mode = railMode,
                    current = section,
                    onSelect = { selected ->
                        if (selected == ShellSection.SEARCH) {
                            // Route focus into the query field on nav, so
                            // Ctrl+F and the rail agree on what "going to
                            // search" means.
                            scope.launch { searchFocusRequester.requestFocus() }
                        }
                        section = selected
                    },
                    onToggleExpanded = {
                        val next = if (railMode == RailMode.EXPANDED) {
                            RailMode.COMPACT
                        } else {
                            RailMode.EXPANDED
                        }
                        manualRailToggle = next
                        manualRailBand = defaultRailModeFor(contentWidth)
                    },
                )

                // Adaptive content pane: the keyboard-shortcut root. The
                // bubble phase guarantees text fields keep their keys.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .focusRequester(rootFocusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (shortcutFor(event.key, event.isCtrlPressed, event.isMetaPressed)) {
                                ShellShortcut.TOGGLE_PLAY_PAUSE -> {
                                    controller?.togglePlayPause(); true
                                }
                                ShellShortcut.NEXT_TRACK -> { controller?.next(); true }
                                ShellShortcut.PREVIOUS_TRACK -> { controller?.previous(); true }
                                ShellShortcut.FOCUS_SEARCH -> {
                                    section = ShellSection.SEARCH
                                    scope.launch { searchFocusRequester.requestFocus() }
                                    true
                                }
                                null -> false
                            }
                        },
                ) {
                    when (section) {
                        ShellSection.HOME -> HomePane(uiState, controller)
                        ShellSection.SEARCH -> SearchPane(searchFocusRequester)
                        ShellSection.LIBRARY -> LibraryPane()
                        ShellSection.JAM -> JamPane(controller)
                    }
                }
            }

            // Shared component dock: the engine-backed mini player.
            MiniPlayerBar(
                state = MiniPlayerBarState(
                    status = uiState.status,
                    track = uiState.currentTrack,
                    positionMs = uiState.positionMs,
                ),
                onTogglePlayPause = { controller?.togglePlayPause() },
                onNext = { controller?.next() },
                onPrevious = { controller?.previous() },
            )
        }
    }

    // Initial focus lands on the content root so bare Space works before
    // the user clicks anywhere.
    LaunchedEffect(Unit) {
        runCatching { rootFocusRequester.requestFocus() }
    }
}

// ── Navigation rail ──────────────────────────────────────────────────────────

@Composable
private fun NavigationRail(
    mode: RailMode,
    current: ShellSection,
    onSelect: (ShellSection) -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val expanded = mode == RailMode.EXPANDED

    Surface(
        color = StreamifyPalette.BgSurface,
        modifier = Modifier
            .fillMaxHeight()
            .width(if (expanded) RailWidthExpanded else RailWidthCompact)
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = StreamifySpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Xs),
        ) {
            IconButton(
                onClick = onToggleExpanded,
                modifier = Modifier.padding(horizontal = StreamifySpacing.Sm),
            ) {
                Icon(
                    imageVector = if (expanded) {
                        Icons.AutoMirrored.Filled.MenuOpen
                    } else {
                        Icons.Filled.Menu
                    },
                    contentDescription = if (expanded) "Collapse rail" else "Expand rail",
                    tint = StreamifyPalette.TextSecondary,
                )
            }

            ShellSection.entries.forEach { destination ->
                RailDestination(
                    icon = destination.icon,
                    label = destination.label,
                    selected = destination == current,
                    expanded = expanded,
                    onClick = { onSelect(destination) },
                )
            }
        }
    }
}

private val ShellSection.icon: ImageVector
    get() = when (this) {
        ShellSection.HOME -> Icons.Filled.Home
        ShellSection.SEARCH -> Icons.Filled.Search
        ShellSection.LIBRARY -> Icons.Filled.LibraryMusic
        ShellSection.JAM -> Icons.Filled.Groups
    }

@Composable
private fun RailDestination(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) StreamifyPalette.ActiveControl else StreamifyPalette.TextSecondary
    Row(
        modifier = Modifier
            .padding(horizontal = StreamifySpacing.Sm)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) StreamifyPalette.BgSurfaceElevated else StreamifyPalette.BgSurface,
            )
            .clickable { onClick() }
            .padding(
                horizontal = StreamifySpacing.Sm,
                vertical = StreamifySpacing.Sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(StreamifyPalette.Primary),
            )
            Spacer(modifier = Modifier.width(StreamifySpacing.Sm))
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        if (expanded) {
            Text(
                text = label,
                style = StreamifyTextStyles.body,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = StreamifySpacing.Md),
            )
        }
    }
}

private val RailWidthExpanded = 220.dp
private val RailWidthCompact = 72.dp

// ── Content panes ────────────────────────────────────────────────────────────

/** Home: the now-playing surface — transport, progress, volume, engine health. */
@Composable
private fun HomePane(uiState: PlaybackUiState, controller: PlaybackController?) {
    if (controller == null) {
        EngineUnavailablePane(
            hint = "Start the desktop client on a host with the Rust engine lane " +
                "(cargo on PATH, no -Pstreamify.skipRust) to light the control plane up.",
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(StreamifySpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Lg),
    ) {
        Column {
            Text(
                text = "Now Playing",
                style = StreamifyTextStyles.headline,
                color = StreamifyPalette.TextMain,
            )
            Text(
                text = when (uiState.status) {
                    PlaybackStatus.IDLE -> "Engine idle — initialize to begin"
                    PlaybackStatus.BUFFERING -> "Buffering…"
                    PlaybackStatus.PLAYING -> "Playing"
                    PlaybackStatus.PAUSED -> "Paused"
                    PlaybackStatus.ERROR -> "Engine error state"
                },
                style = StreamifyTextStyles.songArtist,
                color = StreamifyPalette.TextSecondary,
            )
        }

        Surface(
            color = StreamifyPalette.BgCard,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(StreamifySpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Md),
            ) {
                Text(
                    text = uiState.currentTrack?.title
                        ?: "No track loaded — control-plane session",
                    style = StreamifyTextStyles.title,
                    color = StreamifyPalette.TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = uiState.currentTrack?.artist
                        ?: "Track metadata surfaces with the storage contract",
                    style = StreamifyTextStyles.songArtist,
                    color = StreamifyPalette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Progress: DAC-clock position (MP-DEC-03), determinate when
                // a duration is known, indeterminate otherwise.
                val fraction = uiState.progressFraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = StreamifyPalette.Primary,
                        trackColor = StreamifyPalette.BgSurfaceElevated,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = StreamifyPalette.Primary,
                        trackColor = StreamifyPalette.BgSurfaceElevated,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = uiState.positionMs?.let { formatPlaybackTime(it) } ?: "--:--",
                        style = StreamifyTextStyles.caption,
                        color = StreamifyPalette.TextTertiary,
                    )
                    Text(
                        text = uiState.durationMs?.let { formatPlaybackTime(it) } ?: "--:--",
                        style = StreamifyTextStyles.caption,
                        color = StreamifyPalette.TextTertiary,
                    )
                }

                // Transport row.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { controller.previous() }) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Previous",
                            tint = StreamifyPalette.TextMain,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    IconButton(
                        onClick = { controller.togglePlayPause() },
                        modifier = Modifier
                            .padding(horizontal = StreamifySpacing.Lg)
                            .size(64.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(StreamifyPalette.Primary),
                    ) {
                        if (uiState.isBuffering) {
                            CircularProgressIndicator(
                                color = StreamifyPalette.BgBase,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                imageVector = if (uiState.isPlaying) {
                                    Icons.Filled.Pause
                                } else {
                                    Icons.Filled.PlayArrow
                                },
                                contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                tint = StreamifyPalette.BgBase,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    IconButton(onClick = { controller.next() }) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Next",
                            tint = StreamifyPalette.TextMain,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                // Output gain — engine-authoritative round-trip.
                VolumeSlider(
                    volume = uiState.volume,
                    onVolumeChange = { controller.setVolume(it) },
                    onToggleMute = {
                        if (uiState.volume > 0f) controller.setVolume(0f)
                        else controller.setVolume(0.8f)
                    },
                )
            }
        }

        // Engine health card — the push flow's bookkeeping, in the open.
        Surface(
            color = StreamifyPalette.BgCard,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(StreamifySpacing.Lg),
                verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Xs),
            ) {
                Text(
                    text = "Engine health",
                    style = StreamifyTextStyles.title,
                    color = StreamifyPalette.TextMain,
                )
                val buffer = uiState.snapshot.buffer
                Metric("revision", "rev ${uiState.snapshot.rev}")
                Metric(
                    "buffer",
                    buffer?.let {
                        "${it.bufferedDurationMs / 1000L}s buffered · " +
                            "${it.underrunCount} underruns · " +
                            "${(it.fillRatio * 100f).toInt()}% fill"
                    } ?: "no buffer stats yet",
                )
                Metric(
                    "dac clock",
                    uiState.snapshot.clock?.let { clock ->
                        "${clock.framePosition} frames @ ${clock.sampleRateHz} Hz " +
                            "(monotonic ${clock.monotonicNs / 1_000_000L} ms)"
                    } ?: "no hardware sample yet",
                )
                Metric("volume", "${(uiState.volume * 100f).toInt()}%")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = StreamifyTextStyles.caption,
            color = StreamifyPalette.TextTertiary,
            modifier = Modifier.weight(0.25f),
        )
        Text(
            text = value,
            style = StreamifyTextStyles.mono,
            color = StreamifyPalette.TextSecondary,
            modifier = Modifier.weight(0.75f),
        )
    }
}

/** Search: query field (Ctrl+F destination) + catalog integration note. */
@Composable
private fun SearchPane(searchFocusRequester: FocusRequester) {
    var query by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(StreamifySpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Lg),
    ) {
        Text(
            text = "Search",
            style = StreamifyTextStyles.headline,
            color = StreamifyPalette.TextMain,
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text("Search tracks, artists, sessions… (Ctrl+F)")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = StreamifySpacing.Xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Sm),
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = StreamifyPalette.TextTertiary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = "Catalog search lands with the storage contract",
                style = StreamifyTextStyles.body,
                color = StreamifyPalette.TextSecondary,
            )
            Text(
                text = "The engine control plane (playback, jam sessions) is live; " +
                    "track search over the local encrypted library is the next " +
                    "contract surface.",
                style = StreamifyTextStyles.caption,
                color = StreamifyPalette.TextTertiary,
            )
        }
    }
}

/** Library: catalog placeholder (honest empty state). */
@Composable
private fun LibraryPane() {
    EmptyPane(
        title = "Library",
        body = "Your library mirror renders here once the device-local encrypted " +
            "storage contract ships. Nothing is fabricated in the meantime.",
    )
}

/** Jam Sessions: the engine's real CRDT session surface. */
@Composable
private fun JamPane(controller: PlaybackController?) {
    var sessionName by remember { mutableStateOf("Desktop listening room") }
    var sessionInfo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(StreamifySpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Lg),
    ) {
        Text(
            text = "Jam Sessions",
            style = StreamifyTextStyles.headline,
            color = StreamifyPalette.TextMain,
        )
        Text(
            text = "The engine's local CRDT mirror — create a session, then inspect " +
                "its deterministic BLAKE3 state hash (the Merkle reconciliation " +
                "currency).",
            style = StreamifyTextStyles.songArtist,
            color = StreamifyPalette.TextSecondary,
        )

        if (controller == null) {
            EngineUnavailablePane(
                hint = "Jam sessions need the engine lane — rerun without " +
                    "-Pstreamify.skipRust on a host with cargo.",
            )
            return@Column
        }

        OutlinedTextField(
            value = sessionName,
            onValueChange = { sessionName = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
        )

        IconButton(
            onClick = {
                busy = true
                scope.launch {
                    val created = controller.createJamSessionAndAwait(sessionName)
                    val hash = controller.jamStateHash()
                    sessionInfo = when {
                        created == null -> "Session rejected (blank or oversized name)"
                        else -> "Session '$created' live — state hash " +
                            (hash ?: "unavailable")
                    }
                    busy = false
                }
            },
            enabled = !busy,
        ) {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = "Create jam session",
                tint = StreamifyPalette.Primary,
            )
        }

        sessionInfo?.let { info ->
            Surface(
                color = StreamifyPalette.BgCard,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = info,
                    style = StreamifyTextStyles.mono,
                    color = StreamifyPalette.TextSecondary,
                    modifier = Modifier.padding(StreamifySpacing.Lg),
                )
            }
        }
    }
}

@Composable
private fun EmptyPane(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(StreamifySpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Md),
    ) {
        Text(
            text = title,
            style = StreamifyTextStyles.headline,
            color = StreamifyPalette.TextMain,
        )
        Text(
            text = body,
            style = StreamifyTextStyles.songArtist,
            color = StreamifyPalette.TextSecondary,
        )
    }
}

@Composable
private fun EngineUnavailablePane(hint: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(StreamifySpacing.Xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Md),
        ) {
            Text(
                text = "Engine lane unavailable",
                style = StreamifyTextStyles.title,
                color = StreamifyPalette.TextSecondary,
            )
            Text(
                text = hint,
                style = StreamifyTextStyles.caption,
                color = StreamifyPalette.TextTertiary,
            )
        }
    }
}
