package com.streamify.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.streamify.app.jam.JamEngine
import com.streamify.app.jam.JamPairing
import com.streamify.app.jam.JamTopology
import com.streamify.app.ui.components.yt.YtActiveEqualizer
import com.streamify.app.ui.components.yt.YtThumbnail
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.JamUiState
import com.streamify.app.viewmodel.JamViewModel
import com.streamify.app.viewmodel.PlayerViewModel
import kotlin.math.roundToInt

@Composable
fun JamSessionScreen(
    jamViewModel: JamViewModel,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val jamState by jamViewModel.uiState.collectAsState()
    val playerState by playerViewModel.playerState.collectAsState()
    val meshPeers by jamViewModel.meshPeers.collectAsState()
    val syncTelemetry by jamViewModel.syncTelemetry.collectAsState()
    var inputRoomCode by remember { mutableStateOf("") }
    var showAddSongSheet by remember { mutableStateOf(false) }
    var showRosterSheet by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Invite deep link: streamify://jam/CODE — auto-populate and auto-join.
    LaunchedEffect(Unit) {
        JamEngine.pendingInviteCode?.let { code ->
            inputRoomCode = code
        }
    }
    LaunchedEffect(jamState) {
        val pending = JamEngine.pendingInviteCode ?: return@LaunchedEffect
        if (jamState is JamUiState.Idle && pending.isNotBlank()) {
            JamEngine.pendingInviteCode = null
            jamViewModel.joinJam(pending, playerViewModel)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(bottom = 120.dp)
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextMain,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Streamify Jam",
                style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                color = TextMain
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (val state = jamState) {
            is JamUiState.Idle -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "🎧 Listen Together in Real-Time",
                        style = LocalAppTypography.current.headlineLarge.copy(fontSize = 22.sp),
                        color = TextMain,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Synchronize playback with friends anywhere in the world. Start a room or enter a 6-digit PIN code.",
                        style = LocalAppTypography.current.songArtist,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Start Jam Room Button
                    Button(
                        onClick = {
                            jamViewModel.startJam(playerState.currentTrack, playerViewModel.currentPositionMs())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ActiveControl),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Radio,
                            contentDescription = null,
                            tint = TextOnActiveChip,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start a New Jam Room",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 14.sp),
                            color = TextOnActiveChip
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Divider)
                        Text(
                            text = " OR JOIN ROOM ",
                            style = LocalAppTypography.current.songArtist.copy(
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            ),
                            color = TextTertiary,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = Divider)
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = inputRoomCode,
                        onValueChange = { if (it.length <= 220) inputRoomCode = it },
                        placeholder = { Text("6-char PIN, or paste an offline pairing link", color = TextTertiary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ActiveControl,
                            unfocusedBorderColor = Divider,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { jamViewModel.joinJam(inputRoomCode, playerViewModel) },
                        enabled = inputRoomCode.trim().length >= 6,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ActiveControl),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = "Join Room",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 14.sp),
                            color = if (inputRoomCode.trim().length >= 6) ActiveControl else TextTertiary
                        )
                    }
                }
            }

            is JamUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                }
            }

            is JamUiState.Active -> {
                val session = state.session
                val jamQueue by jamViewModel.jamQueue.collectAsState()
                // Gap #37: live democratic tally — collected at the same
                // granularity as jamQueue itself: a tally emission restarts
                // the Active branch exactly like a queue mutation does
                // (tap-rate, never a periodic tick).
                val jamQueueVotes by jamViewModel.queueVotes.collectAsState()
                val roomMembers by jamViewModel.members.collectAsState()
                val connStatus by jamViewModel.connStatus.collectAsState()
                val controlPolicy by jamViewModel.policy.collectAsState()
                // Regime-level read: topology flips are rare host intents, so a
                // branch-scoped collect is the zero-jank-correct granularity.
                val topology by jamViewModel.topology.collectAsState()

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    // Active Room Banner Card
                    Surface(
                        color = BgSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (state.isHost) "HOSTING JAM SESSION" else "CONNECTED TO JAM SESSION",
                                style = LocalAppTypography.current.songArtist.copy(
                                    fontSize = 11.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Primary
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            // Lockstep connection telemetry
                            Surface(
                                color = when (connStatus) {
                                    JamEngine.ConnStatus.LIVE -> Color(0xFF10B981).copy(alpha = 0.15f)
                                    JamEngine.ConnStatus.DEGRADED -> Color(0xFFF59E0B).copy(alpha = 0.18f)
                                    JamEngine.ConnStatus.OFFLINE -> Color(0xFFEF4444).copy(alpha = 0.16f)
                                },
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = when (connStatus) {
                                        JamEngine.ConnStatus.LIVE -> "● LOCKSTEP LIVE"
                                        JamEngine.ConnStatus.DEGRADED -> "● RECOVERING CLOCK…"
                                        JamEngine.ConnStatus.OFFLINE -> "○ OFFLINE"
                                    },
                                    style = LocalAppTypography.current.songArtist.copy(
                                        fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold
                                    ),
                                    color = when (connStatus) {
                                        JamEngine.ConnStatus.LIVE -> Color(0xFF10B981)
                                        JamEngine.ConnStatus.DEGRADED -> Color(0xFFF59E0B)
                                        JamEngine.ConnStatus.OFFLINE -> Color(0xFFEF4444)
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = session.sessionCode,
                                style = LocalAppTypography.current.headlineLarge.copy(
                                    fontSize = 38.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp
                                ),
                                color = ActiveControl
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            TextButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Jam PIN", session.sessionCode))
                                Toast.makeText(context, "PIN Copied!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy PIN to Share", color = TextSecondary, style = LocalAppTypography.current.chipText)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // ── RENDER TOPOLOGY (Gap #13): Party Mode ⇄ Multi-Room ──
                    TopologyBar(
                        topology = topology,
                        isHost = state.isHost,
                        onToggle = { jamViewModel.togglePartyMode() }
                    )

                    if (topology.isPartyMode) {
                        Spacer(modifier = Modifier.height(10.dp))
                        PartyModeBanner(
                            isHost = state.isHost,
                            hostName = roomMembers.firstOrNull { it.isHost }?.name?.ifBlank { null }
                                ?: "the host"
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── P2P MESH TOPOLOGY RADAR + ACOUSTIC SYNC GAUGE (v4) ──
                    JamMeshRadar(peers = meshPeers, isHost = state.isHost)

                    Spacer(modifier = Modifier.height(12.dp))

                    JamSyncGauge(telemetry = syncTelemetry)

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── OFFLINE PAIRING (QR / NFC) ─────────────────────────
                    Surface(
                        color = BgSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "OFFLINE PAIRING",
                                style = LocalAppTypography.current.songArtist.copy(
                                    fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold
                                ),
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Friends scan this with any camera app, tap NFC,\nor type the PIN — no server, no account, works on airplane mode",
                                style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                                color = TextTertiary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            JamPairingQr(payload = JamPairing.encodePayload(session))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Ephemeral key rotates with the room — it dies when the room dies",
                                style = LocalAppTypography.current.songArtist.copy(fontSize = 10.sp),
                                color = TextTertiary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Universal Synchronized Playback Control Card
                    Surface(
                        color = BgSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                ) {
                                    YtThumbnail(
                                        url = playerState.currentTrack?.coverArtPath,
                                        size = 52.dp,
                                        cornerRadius = 6.dp
                                    )
                                    if (playerState.isPlaying) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color.Black.copy(alpha = 0.55f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            YtActiveEqualizer()
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playerState.currentTrack?.title ?: "No track playing",
                                        style = LocalAppTypography.current.songTitle.copy(fontSize = 15.sp),
                                        color = TextMain,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = playerState.currentTrack?.artist ?: "Streamify Radio",
                                        style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                        color = TextSecondary,
                                        maxLines = 1
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Party Mode (Gap #13): for guests this card is an
                            // interactive REMOTE for the host's speaker — local
                            // audio is muted, intents route over the mesh.
                            if (topology.isPartyMode && !state.isHost) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Speaker,
                                        contentDescription = null,
                                        tint = ActiveControl,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "REMOTE CONTROL · routed to the host's speaker",
                                        style = LocalAppTypography.current.songArtist.copy(
                                            fontSize = 10.sp, letterSpacing = 0.5.sp
                                        ),
                                        color = ActiveControl
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                PartyRemoteSeekBar(playerViewModel = playerViewModel)
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            // Universal Control Buttons (Universal for all connected friends)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { playerViewModel.skipPrevious() },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipPrevious,
                                        contentDescription = "Previous",
                                        tint = TextMain,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(20.dp))

                                IconButton(
                                    onClick = { playerViewModel.togglePlayPause() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(ActiveControl, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = TextOnActiveChip,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(20.dp))

                                IconButton(
                                    onClick = {
                                        val nextInJamQ = jamQueue.firstOrNull()
                                        if (nextInJamQ != null) {
                                            jamViewModel.removeFromJamQueue(nextInJamQ)
                                            playerViewModel.playTrack(nextInJamQ)
                                        } else {
                                            playerViewModel.skipNext()
                                        }
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipNext,
                                        contentDescription = "Next",
                                        tint = TextMain,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Shared Collaborative Jam Queue
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SHARED JAM QUEUE (${jamQueue.size})",
                            style = LocalAppTypography.current.songArtist.copy(
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = TextSecondary
                        )
                        Button(
                            onClick = { showAddSongSheet = true },
                            colors = ButtonDefaults.buttonColors(containerColor = ActiveControl),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add Songs",
                                tint = TextOnActiveChip,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Add Songs",
                                style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp),
                                color = TextOnActiveChip
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = BgSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (jamQueue.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Jam Queue is empty\nFriends can add tracks from Search or Library",
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                    color = TextTertiary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            // The jam queue lives inside the screen's scrollable Column,
                            // so it cannot be a LazyColumn (nested scrolling would break).
                            // Bound the collapsed rendering instead of measuring/composing
                            // every row a distributed queue can grow to — and give every
                            // row a drag handle whose commit travels as a fractional-index
                            // OP_REORDER, so 32 members can reorder concurrently and the
                            // CRDT fold still converges on one order (Gap #11).
                            var jamQueueExpanded by remember { mutableStateOf(false) }
                            val maxCollapsedJamRows = 8
                            val visibleJamQueue = if (jamQueueExpanded) jamQueue else jamQueue.take(maxCollapsedJamRows)
                            JamQueueReorderList(
                                tracks = visibleJamQueue,
                                votes = jamQueueVotes,
                                onMove = { track, to -> jamViewModel.moveInJamQueue(track, to) },
                                onPlayNow = { track ->
                                    jamViewModel.removeFromJamQueue(track)
                                    playerViewModel.playTrack(track)
                                },
                                onRemove = { track -> jamViewModel.removeFromJamQueue(track) },
                                voteCountFor = { track -> jamViewModel.voteCountFor(track) },
                                hasVotedFor = { track -> jamViewModel.hasVotedFor(track) },
                                onUpvote = { track ->
                                    // Phase 5 — energetic rising tactile pitch as
                                    // the vote promotes the track.
                                    com.streamify.app.ui.util.HapticFeedbackManager.get()
                                        ?.jamUpvoteRising()
                                    jamViewModel.castUpvote(track)
                                }
                            )
                            if (jamQueue.size > maxCollapsedJamRows) {
                                Text(
                                    text = if (jamQueueExpanded) "Collapse queue" else "Show all ${jamQueue.size} tracks",
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                    color = ActiveControl,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { jamQueueExpanded = !jamQueueExpanded }
                                        .padding(vertical = 8.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── 32-MEMBER VIRTUALIZED ROSTER STRIP (Gap #11) ──────────
                    // Header carries the hard-cap census + the governance entry.
                    val roster = if (roomMembers.isEmpty())
                        listOf(JamEngine.Member(session.hostNonce, "Host", null, true, 0L))
                    else roomMembers.sortedByDescending { m -> m.isHost || m.isCoHost }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LISTENERS IN ROOM · ${roster.size}/32",
                            style = LocalAppTypography.current.songArtist.copy(
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = TextSecondary
                        )
                        Surface(
                            onClick = { showRosterSheet = true },
                            color = Primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ManageAccounts,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "MANAGE",
                                    style = LocalAppTypography.current.songArtist.copy(
                                        fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold
                                    ),
                                    color = Primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Virtualized avatar strip (Lockstep Engine): every
                    // fast-changing read (RTT radar @ ~1 Hz) is scoped to the
                    // avatar leaf below, so a ping recomposes exactly one cell —
                    // never this screen, never the root scaffold. Tap any avatar
                    // for the governance / report surface (Gaps #14 & #18).
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(roster, key = { it.userId + it.nonce }, contentType = { "rosterAvatar" }) { m ->
                            RosterStripAvatar(member = m, onOpen = { showRosterSheet = true })
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // INVITE + CONTROL POLICY row (Spotify-grade room management)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val text = jamViewModel.inviteShareText()
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "Invite to Jam")
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("INVITE", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        if (state.isHost) {
                            Surface(
                                onClick = { jamViewModel.cycleControlPolicy() },
                                color = if (controlPolicy == JamEngine.ControlPolicy.EVERYONE)
                                    Color(0xFF10B981).copy(alpha = 0.15f)
                                else Primary.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Text(
                                        text = if (controlPolicy == JamEngine.ControlPolicy.EVERYONE) "EVERYONE CONTROLS" else "HOST CONTROLS",
                                        fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp,
                                        color = if (controlPolicy == JamEngine.ControlPolicy.EVERYONE) Color(0xFF10B981) else Primary
                                    )
                                    Text(
                                        text = "tap to switch",
                                        fontSize = 9.sp, color = TextSecondary
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Leave Session Button
                    OutlinedButton(
                        onClick = { jamViewModel.leaveJam(endForEveryone = state.isHost) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Leave Jam Session",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 14.sp),
                            color = Primary
                        )
                    }
                }
            }

            is JamUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.message,
                            style = LocalAppTypography.current.songArtist,
                            color = Primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { jamViewModel.leaveJam() },
                            colors = ButtonDefaults.buttonColors(containerColor = BgSurfaceElevated)
                        ) {
                            Text("Try Again", color = TextMain)
                        }
                    }
                }
            }
        }

        if (showAddSongSheet) {
            JamAddSongModalBottomSheet(
                onDismiss = { showAddSongSheet = false },
                onAddTrack = { track -> jamViewModel.addToJamQueue(track) }
            )
        }

        // ── Governance + virtualized 32-roster sheet (Gaps #14 & #18) ──
        if (showRosterSheet) {
            JamRosterSheet(
                jamViewModel = jamViewModel,
                onDismiss = { showRosterSheet = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JamAddSongModalBottomSheet(
    onDismiss: () -> Unit,
    onAddTrack: (com.streamify.app.data.models.Track) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val allLocalTracks by com.streamify.app.data.repository.TrackRepository.allTracks.collectAsState()
    var onlineResults by remember { mutableStateOf<List<com.streamify.app.data.models.Track>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(searchQuery) {
        val q = searchQuery.trim()
        if (q.length >= 2) {
            isSearching = true
            try {
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.streamify.app.data.network.YouTubeMusicSearchApi.search(q, maxResults = 12)
                }
                onlineResults = res.map { r ->
                    com.streamify.app.data.models.Track(
                        id = -(r.title + r.uploader).hashCode(),
                        title = r.title,
                        artist = r.uploader,
                        album = "Jam Queue",
                        durationSec = r.duration,
                        filepath = r.url,
                        coverArtPath = r.thumbnail,
                        bpm = 120f,
                        key = "C",
                        lyricsPath = null,
                        source = "online_stream"
                    )
                }
            } catch (e: Exception) {
                onlineResults = emptyList()
            } finally {
                isSearching = false
            }
        } else {
            onlineResults = emptyList()
            isSearching = false
        }
    }

    // Key derivation: identity = id + source URL (local DB ids are unique;
    // online ids are title+uploader hashes that CAN duplicate across
    // distinct videos — e.g. "Track - Remastered" vs "Track - Live").
    // Deduping by the composite guarantees LazyColumn key uniqueness (a
    // duplicate key is a hard crash) without embedding the POSITION, which
    // would shift on every reorder and defeat item reuse (audit #22 item 5).
    fun trackKey(t: com.streamify.app.data.models.Track) = "${t.id}:${t.filepath}"

    val displayTracks = remember(searchQuery, onlineResults, allLocalTracks) {
        val raw = if (searchQuery.isNotBlank()) {
            if (onlineResults.isNotEmpty()) onlineResults else allLocalTracks.filter {
                it.title.contains(searchQuery, ignoreCase = true) || it.artist.contains(searchQuery, ignoreCase = true)
            }
        } else {
            allLocalTracks.take(20)
        }
        val seen = HashSet<String>(raw.size)
        raw.filter { seen.add(trackKey(it)) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgSurfaceElevated,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextTertiary) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "Add Songs to Jam",
                style = LocalAppTypography.current.headlineLarge.copy(fontSize = 18.sp),
                color = TextMain
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search songs or artists...", color = TextTertiary, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = Divider,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain,
                    focusedContainerColor = BgCard,
                    unfocusedContainerColor = BgCard
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isSearching) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    // Key by track identity ONLY (audit #22 item 5): the old
                    // "jam_${i}_${t.id}" embedded the LIST INDEX, so any
                    // reorder/remove shifted every subsequent key and defeated
                    // LazyColumn's item reuse + reorder animation. The roster
                    // strip below (userId+nonce) is the reference pattern;
                    // displayTracks is deduped by the same key upstream.
                    // contentType (Phase 5 120Hz): one stable reuse class for
                    // every row — identical layout shape, pooled reuse.
                    items(
                        displayTracks,
                        key = { trackKey(it) },
                        contentType = { "trackRow" }
                    ) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgCard)
                                .clickable {
                                    onAddTrack(track)
                                    Toast.makeText(context, "Added ${track.title} to Jam", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            YtThumbnail(
                                url = track.coverArtPath,
                                size = 42.dp,
                                cornerRadius = 6.dp,
                                title = track.title,
                                artist = track.artist
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                                    color = TextMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    onAddTrack(track)
                                    Toast.makeText(context, "Added ${track.title} to Jam", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Filled.AddCircle, contentDescription = "Add", tint = ActiveControl)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHASE 1 LEAF SURFACES — Render topology (Gap #13), party remote seek,
// drag-reorder fractional-index queue (Gap #11), 32-member roster strip.
//
// ZERO-JANK DISCIPLINE: every fast-changing read (playhead @ poll rate,
// radar RTT @ ~1 Hz) lives INSIDE these leaf composables, so those updates
// recompose exactly one leaf — never the Jam screen, never the scaffold.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Render-topology control (Gap #13). Host sees the two-segment switch
 * (Party Mode ⇄ Multi-Room Sync); guests see a passive readout of the
 * regime the host has broadcast.
 */
@Composable
private fun TopologyBar(
    topology: JamTopology,
    isHost: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = BgSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RENDER TOPOLOGY",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold
                    ),
                    color = TextSecondary
                )
                if (!isHost) {
                    Text(
                        text = "set by host",
                        style = LocalAppTypography.current.songArtist.copy(fontSize = 9.sp),
                        color = TextTertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isHost) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TopologySegment(
                        label = "Party Mode",
                        subtitle = "one speaker · zero echo",
                        selected = topology.isPartyMode,
                        onClick = { if (!topology.isPartyMode) onToggle() }
                    )
                    TopologySegment(
                        label = "Multi-Room Sync",
                        subtitle = "every device · phase-locked",
                        selected = !topology.isPartyMode,
                        onClick = { if (topology.isPartyMode) onToggle() }
                    )
                }
            } else {
                Text(
                    text = if (topology.isPartyMode)
                        "PARTY MODE · audio renders on the host's speaker"
                    else
                        "MULTI-ROOM SYNC · phase-locked audio on this phone",
                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                    color = TextMain
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TopologySegment(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) ActiveControl.copy(alpha = 0.16f) else BgCard,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.weight(1f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 10.dp)
        ) {
            Text(
                text = label,
                style = LocalAppTypography.current.songTitle.copy(
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                ),
                color = if (selected) ActiveControl else TextMain
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = LocalAppTypography.current.songArtist.copy(fontSize = 9.sp),
                color = TextTertiary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Party Mode banner (Gap #13). Host: celebration that this phone is the
 * room's single renderer. Guest: the "Playing on …'s Speaker" contract —
 * local audio suppressed, remote powers unlocked.
 */
@Composable
private fun PartyModeBanner(isHost: Boolean, hostName: String) {
    val glow = Color(0xFFF59E0B)
    Surface(
        color = glow.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(glow.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Speaker,
                    contentDescription = null,
                    tint = glow,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isHost) "PARTY MODE LIVE" else "Playing on $hostName's Speaker",
                    style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                    color = TextMain
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isHost)
                        "This phone is the room's single speaker — 32 friends, zero echo"
                    else
                        "Your audio is muted — you hold the remote. Add songs, reorder, seek.",
                    style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                    color = TextSecondary
                )
            }
        }
    }
}

/**
 * Guest scrubber in Party Mode: leaf-scoped playhead reads (the 1 Hz poll
 * recomposes only this slider) and the finished scrub routes a SEEK intent
 * over the mesh to the host's renderer.
 */
@Composable
private fun PartyRemoteSeekBar(playerViewModel: PlayerViewModel) {
    val positionMs by playerViewModel.positionMs.collectAsState()
    val durationMs by playerViewModel.durationMs.collectAsState()
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableStateOf(0f) }
    val duration = durationMs.coerceAtLeast(1L)

    Slider(
        value = if (scrubbing) scrubFraction
        else positionMs.coerceIn(0L, duration).toFloat() / duration,
        onValueChange = {
            scrubbing = true
            scrubFraction = it
        },
        onValueChangeFinished = {
            playerViewModel.seekTo((scrubFraction * duration).toLong())
            scrubbing = false
        },
        colors = SliderDefaults.colors(
            thumbColor = ActiveControl,
            activeTrackColor = ActiveControl,
            inactiveTrackColor = Divider
        )
    )
}

/**
 * Drag-to-reorder shared queue (Gap #11). The handle follows the finger
 * 1:1 (raw translation); neighbours shift on a spring; the commit emits a
 * fractional-index OP_REORDER that converges across all 32 replicas.
 */
@Composable
private fun JamQueueReorderList(
    tracks: List<com.streamify.app.data.models.Track>,
    /** Gap #37 tally snapshot — the recomposition key that refreshes counts live. */
    votes: Map<Long, Set<String>>,
    onMove: (com.streamify.app.data.models.Track, Int) -> Unit,
    onPlayNow: (com.streamify.app.data.models.Track) -> Unit,
    onRemove: (com.streamify.app.data.models.Track) -> Unit,
    voteCountFor: (com.streamify.app.data.models.Track) -> Int,
    hasVotedFor: (com.streamify.app.data.models.Track) -> Boolean,
    onUpvote: (com.streamify.app.data.models.Track) -> Unit
) {
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    var liveTarget by remember { mutableStateOf(-1) }
    var rowHeightPx by remember { mutableStateOf(1) }

    fun targetFor(offset: Float, from: Int): Int =
        (from + (offset / rowHeightPx).roundToInt()).coerceIn(0, tracks.size - 1)

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        tracks.forEachIndexed { index, track ->
            val isDragging = index == draggingIndex
            val shiftPx = when {
                draggingIndex < 0 -> 0f
                isDragging -> 0f
                draggingIndex < liveTarget && index in (draggingIndex + 1)..liveTarget -> -rowHeightPx.toFloat()
                liveTarget < draggingIndex && index in liveTarget until draggingIndex -> rowHeightPx.toFloat()
                else -> 0f
            }
            val animatedShift by animateFloatAsState(
                targetValue = shiftPx,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
                label = "queueNeighborShift"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .onSizeChanged { if (it.height > 0) rowHeightPx = it.height }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset else animatedShift
                        val lift = if (isDragging) 1.03f else 1f
                        scaleX = lift
                        scaleY = lift
                        alpha = if (isDragging) 0.92f else 1f
                    }
                    .background(
                        if (isDragging) BgSurfaceElevated else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Drag handle — the reorder affordance.
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .pointerInput(tracks, index) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    draggingIndex = index
                                    dragOffset = 0f
                                    liveTarget = index
                                },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                    if (draggingIndex >= 0) {
                                        liveTarget = targetFor(dragOffset, draggingIndex)
                                    }
                                },
                                onDragEnd = {
                                    if (draggingIndex in tracks.indices) {
                                        val target = targetFor(dragOffset, draggingIndex)
                                        if (target != draggingIndex) {
                                            onMove(tracks[draggingIndex], target)
                                        }
                                    }
                                    draggingIndex = -1
                                    dragOffset = 0f
                                    liveTarget = -1
                                },
                                onDragCancel = {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                    liveTarget = -1
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = "Reorder",
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = "${index + 1}",
                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                    color = TextTertiary,
                    modifier = Modifier.width(20.dp)
                )

                YtThumbnail(
                    url = track.coverArtPath,
                    size = 38.dp,
                    cornerRadius = 4.dp
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = LocalAppTypography.current.songTitle.copy(fontSize = 13.sp),
                        color = TextMain,
                        maxLines = 1
                    )
                    Text(
                        text = track.artist,
                        style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                        color = TextSecondary,
                        maxLines = 1
                    )
                }

                // ── Gap #37: democratic upvote — one tap, live count. The
                // float-up reorder rides the existing fractional-index spring
                // shifts, so a boosted track visibly rises through the queue.
                val voteCount = voteCountFor(track)
                val voted = hasVotedFor(track)
                val votePop by animateFloatAsState(
                    targetValue = if (voted) 1f else 0.8f,
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = 600f),
                    label = "votePop"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onUpvote(track) }
                        .padding(vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ThumbUp,
                        contentDescription = if (voted) "Retract vote" else "Upvote",
                        tint = if (voted) ActiveControl else TextTertiary,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer {
                                scaleX = votePop
                                scaleY = votePop
                            }
                    )
                    Text(
                        text = "$voteCount",
                        style = LocalAppTypography.current.songArtist.copy(fontSize = 10.sp),
                        color = if (voted) ActiveControl else TextTertiary,
                        maxLines = 1
                    )
                }

                IconButton(
                    onClick = { onPlayNow(track) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play Next",
                        tint = ActiveControl,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = { onRemove(track) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove",
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * One roster avatar cell (Gap #11). The ~1 Hz mesh-radar feed is collected
 * HERE — a ping recomposes this single cell, never the roster strip, never
 * the Jam screen. Ring colour = live RTT health; crown = host; note badge
 * = co-host (controller class); tap opens governance.
 */
@Composable
private fun RosterStripAvatar(
    member: JamEngine.Member,
    onOpen: () -> Unit
) {
    val peers by JamEngine.meshPeers.collectAsState()
    val rttMs = peers.firstOrNull { it.nonce == member.nonce }?.rttMs ?: -1f
    val isSelf = member.userId == JamEngine.myUserId()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onOpen)
    ) {
        Box {
            Box(modifier = Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                RosterHealthRing(rttMs = rttMs)
                if (!member.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = member.avatarUrl,
                        contentDescription = member.name,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(
                                if (member.isHost) 2.dp else 1.dp,
                                if (member.isHost) Primary else Color.Transparent,
                                CircleShape
                            )
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(BgSurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.name.take(1).uppercase().ifBlank { "?" },
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
            // Role badge: crown for the host, controller-note for co-hosts.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (member.isHost) Primary else ActiveControl),
                contentAlignment = Alignment.Center
            ) {
                if (member.isHost) {
                    Text(text = "★", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                } else if (member.isCoHost) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = "Co-host",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = member.name.ifBlank { "Listener" },
            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
            fontWeight = if (member.isHost) FontWeight.Bold else FontWeight.Normal,
            color = TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = when {
                member.isHost -> "HOST"
                member.isCoHost -> "CO-HOST"
                isSelf -> "YOU"
                else -> "LISTENER"
            },
            style = LocalAppTypography.current.songArtist.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
            color = when {
                member.isHost -> Primary
                member.isCoHost -> ActiveControl
                else -> TextSecondary
            }
        )
    }
}

/** RTT health ring: green < 80 ms, amber < 200 ms, red beyond, grey unknown. */
@Composable
private fun RosterHealthRing(rttMs: Float) {
    val color = when {
        rttMs < 0f -> TextTertiary.copy(alpha = 0.35f)
        rttMs < 80f -> Color(0xFF10B981)
        rttMs < 200f -> Color(0xFFF59E0B)
        else -> Color(0xFFEF4444)
    }
    Canvas(modifier = Modifier.size(58.dp)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2 + 1.dp.toPx()
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 300f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        )
    }
}
