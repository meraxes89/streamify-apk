package com.streamify.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.streamify.app.jam.JamEngine
import com.streamify.app.jam.JamWire
import com.streamify.app.ui.theme.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * JAM SESSION WIDGETS v4 — Mesh Topology Radar, Acoustic Sync Gauge, QR Pairing
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The live instruments of the zero-server Jam room:
 *
 *  • [JamMeshRadar] — real-time P2P mesh topology on a radar canvas: this
 *    device at the center, peers orbit-positioned by a stable identity hash
 *    and ranged by measured RTT rings, colour-coded by transport link type
 *    (Wi-Fi Direct / 5 GHz LAN UDP / WebRTC) with an animated sweep.
 *
 *  • [JamSyncGauge] — the acoustic offset instrument: clock drift in
 *    microseconds, the Sinc-resampler rate scalar, PTP lock state and the
 *    last Merkle convergence latency.
 *
 *  • [JamPairingQr] — the offline onboarding artefact: a QR encoding the
 *    room's ephemeral cryptographic pairing payload, renderable with zero
 *    extra dependencies beyond the pure-JVM zxing core.
 */

// ── Palette (module-local; keeps the theme file untouched) ──────────────────

private val LinkWifiDirect = Color(0xFF38BDF8)   // sky
private val LinkLan5G = Color(0xFF34D399)        // emerald
private val LinkWebRtc = Color(0xFFA78BFA)       // violet
private val LinkUnknown = Color(0xFF717171)      // TextTertiary
private val RadarBg = Color(0xFF0B0F0D)
private val RadarRing = Color(0x1434D399)
private val RadarSweep = Color(0x2E34D399)
private val SelfNode = Color(0xFFFF4444)

private fun linkColor(linkType: Int): Color = when (linkType) {
    JamWire.LinkType.WIFI_DIRECT -> LinkWifiDirect
    JamWire.LinkType.LAN_5GHZ_UDP -> LinkLan5G
    JamWire.LinkType.WEBRTC -> LinkWebRtc
    else -> LinkUnknown
}

private fun linkLabel(linkType: Int): String = when (linkType) {
    JamWire.LinkType.WIFI_DIRECT -> "Wi-Fi Direct"
    JamWire.LinkType.LAN_5GHZ_UDP -> "5GHz LAN"
    JamWire.LinkType.WEBRTC -> "WebRTC"
    else -> "mesh"
}

// ═════════ P2P Mesh Topology Radar ═════════

/**
 * Radar view of the mesh. Peers are placed on concentric RTT rings
 * (≤ 50 ms / ≤ 150 ms / beyond) at angles derived from a stable hash of
 * their nonce, so nodes never jitter between frames. The host wears a crown
 * ring; the sweep line conveys liveness at a glance.
 */
@Composable
fun JamMeshRadar(
    peers: List<JamEngine.MeshPeer>,
    isHost: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(260.dp)) {
            val c = center
            val maxR = size.minDimension / 2f - 22f

            // Backplate.
            drawCircle(RadarBg, radius = maxR + 18f, center = c)

            // RTT rings: 50ms / 150ms / far.
            val rings = listOf(0.36f, 0.68f, 1.0f)
            rings.forEach { f -> drawCircle(RadarRing, radius = maxR * f, center = c, style = Stroke(1.2f)) }
            drawCircle(RadarRing, radius = 6f, center = c)

            // Sweep beam.
            rotate(sweep, pivot = c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.995f to Color.Transparent,
                        1.0f to RadarSweep
                    ),
                    startAngle = -4f,
                    sweepAngle = 8f,
                    useCenter = true,
                    topLeft = Offset(c.x - maxR, c.y - maxR),
                    size = androidx.compose.ui.geometry.Size(maxR * 2, maxR * 2)
                )
            }

            // Cross-hairs.
            drawLine(RadarRing, Offset(c.x - maxR, c.y), Offset(c.x + maxR, c.y), 1f)
            drawLine(RadarRing, Offset(c.x, c.y - maxR), Offset(c.x, c.y + maxR), 1f)

            // Self node at the center.
            drawCircle(SelfNode, radius = 9f, center = c)
            drawCircle(SelfNode.copy(alpha = 0.35f), radius = 15f, center = c)

            // Peers: stable angle from nonce hash; radius from RTT.
            val indexed = peers.withIndex()
            val n = maxOf(peers.size, 1)
            indexed.forEach { (i, p) ->
                // Golden-angle base + index offset keeps nodes separated and
                // re-derivable without state.
                val angleDeg = (p.nonce.hashCode() % 360 + 360) % 360 + (i * 7f / n)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val rtt = if (p.rttMs >= 0f) p.rttMs else 180f
                val ringF = when {
                    rtt <= 50f -> 0.36f
                    rtt <= 150f -> 0.68f
                    else -> 0.94f
                }
                val px = c.x + (maxR * ringF * cos(angleRad)).toFloat()
                val py = c.y + (maxR * ringF * sin(angleRad)).toFloat()
                val nodeColor = linkColor(p.linkType)

                drawCircle(nodeColor.copy(alpha = 0.25f), radius = 13f, center = Offset(px, py))
                drawCircle(nodeColor, radius = 7f, center = Offset(px, py))
                if (p.isHost) {
                    drawCircle(Color(0xFFFACC15), radius = 11f, center = Offset(px, py), style = Stroke(2f))
                }
            }
        }

        // Peer chips column (labels ride above the canvas as normal text).
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
        ) {
            if (peers.isEmpty()) {
                Text(
                    text = "MESH STANDBY — waiting for peers…",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            } else {
                peers.take(4).forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(linkColor(p.linkType))
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = buildString {
                                append(p.name.take(10))
                                if (p.isHost) append(" ★")
                                append("  ")
                                append(if (p.rttMs >= 0f) "${p.rttMs.toInt()}ms" else "…")
                                append("  ")
                                append(linkLabel(p.linkType))
                            },
                            color = TextSecondary,
                            fontSize = 9.sp,
                            letterSpacing = 0.4.sp
                        )
                    }
                }
                if (peers.size > 4) {
                    Text("+${peers.size - 4} more", color = TextTertiary, fontSize = 9.sp)
                }
            }
        }
    }
}

// ═════════ Acoustic Sync Gauge ═════════

/**
 * The acoustic offset instrument: needle gauges clock drift (±2 000 µs
 * window), digital readouts carry the drift, the Sinc-resampler rate scalar,
 * PTP lock state, and the last Merkle convergence latency.
 */
@Composable
fun JamSyncGauge(
    telemetry: JamEngine.SyncTelemetry,
    modifier: Modifier = Modifier
) {
    val driftUs = telemetry.clockDriftNanos / 1_000.0
    val ratePct = (telemetry.resamplerRateScalar - 1.0f) * 100.0
    val locked = telemetry.ptpLocked
    val needleF = (driftUs / 2000.0).coerceIn(-1.0, 1.0)

    Surface(
        color = BgSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Gauge: 180° arc, needle at drift position.
            Canvas(modifier = Modifier.size(92.dp, 64.dp)) {
                val c = Offset(size.width / 2f, size.height * 0.86f)
                val r = size.width * 0.44f
                val start = 180f
                val sweep = 180f

                // Track.
                drawArc(
                    color = Color(0xFF2A2A2A),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(7f, cap = StrokeCap.Round)
                )
                // Green lock band around zero (±150 µs).
                drawArc(
                    color = Color(0xFF34D399).copy(alpha = 0.75f),
                    startAngle = start + sweep * 0.4625f,
                    sweepAngle = sweep * 0.075f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(7f, cap = StrokeCap.Butt)
                )
                // Filled value arc from center to needle.
                val needleAngle = start + sweep * ((needleF + 1.0) / 2.0).toFloat()
                val fillSweep = (needleAngle - (start + sweep / 2f)).let {
                    if (it >= 0) it else 360f + it
                }.let { if (abs(it) < 0.5f) 0.5f else it }
                drawArc(
                    color = if (abs(needleF) < 0.075) Color(0xFF34D399) else Color(0xFFF59E0B),
                    startAngle = minOf(start + sweep / 2f, needleAngle),
                    sweepAngle = abs(fillSweep),
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(7f, cap = StrokeCap.Round)
                )
                // Needle.
                val na = Math.toRadians((needleAngle).toDouble())
                val tip = Offset(
                    c.x + (r - 14f) * cos(na).toFloat(),
                    c.y + (r - 14f) * sin(na).toFloat()
                )
                drawLine(Color.White, c, tip, 2.4f, cap = StrokeCap.Round)
                drawCircle(Color.White, radius = 3.6f, center = c)
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ACOUSTIC SYNC",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = when {
                        !locked && abs(driftUs) < 1.0 -> "±${abs(driftUs).toInt()} µs · calibrating…"
                        abs(driftUs) < 150 -> "±${abs(driftUs).toInt()} µs · LOCKED"
                        else -> "±${abs(driftUs).toInt()} µs · slewing"
                    },
                    color = when {
                        abs(driftUs) < 150 && locked -> Color(0xFF34D399)
                        else -> Color(0xFFF59E0B)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    GaugeStat("rate", "${if (ratePct >= 0) "+" else ""}${"%.3f".format(ratePct)}%")
                    Spacer(modifier = Modifier.width(14.dp))
                    GaugeStat(
                        "merkle",
                        if (telemetry.merkleConvergenceNanos >= 0)
                            "${"%.1f".format(telemetry.merkleConvergenceNanos / 1_000_000.0)} ms"
                        else "—"
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    GaugeStat("epoch", "${telemetry.authorityEpoch}")
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Sinc resampler · ${telemetry.electionState.lowercase()}",
                    color = TextTertiary,
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun GaugeStat(label: String, value: String) {
    Column {
        Text(text = value, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = TextTertiary, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}

// ═════════ Offline Pairing QR ═════════

/**
 * Renders the room's pairing payload as a scannable QR (zxing core, pure
 * JVM). Error-correction level M keeps it robust on worn screens; the quiet
 * zone is painted by the card padding.
 */
@Composable
fun JamPairingQr(
    payload: String,
    qrSize: Dp = 188.dp,
    modifier: Modifier = Modifier
) {
    val matrix = rememberQrMatrix(payload)
    Box(
        modifier = modifier
            .size(qrSize)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (matrix != null) {
            Canvas(modifier = Modifier.size(qrSize - 16.dp)) {
                val n = matrix.size
                if (n == 0) return@Canvas
                val cell = minOf(size.width, size.height) / n
                for (y in 0 until n) {
                    for (x in 0 until n) {
                        if (matrix[y][x]) {
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(x * cell, y * cell),
                                size = androidx.compose.ui.geometry.Size(cell + 0.6f, cell + 0.6f)
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                text = "QR unavailable\ntoo much data",
                color = Color(0xFF666666),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun rememberQrMatrix(payload: String): Array<BooleanArray>? {
    return androidx.compose.runtime.remember(payload) {
        try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 0,
                EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            )
            val m = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
            Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
        } catch (_: Exception) {
            null
        }
    }
}
