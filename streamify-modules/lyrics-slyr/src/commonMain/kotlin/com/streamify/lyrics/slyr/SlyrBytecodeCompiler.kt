package com.streamify.lyrics.slyr

/**
 * High-performance SLYR Lyrics Bytecode Compiler.
 *
 * Compiles raw LRC and syllable-tagged text `<mm:ss.xx>word` into
 * phase-aligned, validated monotonic timeline structures (comm18 Decision 4).
 */
object SlyrBytecodeCompiler {

    private val offsetRegex = Regex("""\[offset:\s*(-?\d+)\]""", RegexOption.IGNORE_CASE)
    private val lineRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")
    private val multiTimestampRegex = Regex("""(\[(\d{2}):(\d{2})\.(\d{2,3})\])+""")
    private val timestampTagRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\]""")
    private val wordRegex = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>([^<\[]*)""")

    fun compile(lrcContent: String): LyricsData {
        if (lrcContent.isBlank()) return LyricsData(emptyList(), isSynced = false)

        // LRC offset sign convention: [offset:+/-ms]. Positive offset means timestamps are delayed.
        var offsetMs = 0L
        offsetRegex.find(lrcContent)?.let {
            offsetMs = it.groupValues[1].toLongOrNull() ?: 0L
        }

        val rawLines = lrcContent.split("\n")
        val parsedSyncedLines = mutableListOf<LyricsLine>()
        val unsyncedLines = mutableListOf<LyricsLine>()

        for (raw in rawLines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") ||
                trimmed.startsWith("[al:") || trimmed.startsWith("[by:") ||
                trimmed.startsWith("[offset:")
            ) {
                continue
            }

            // Check for multi-timestamp lines (e.g. [01:10.00][02:20.00]Chorus)
            val allTimestamps = timestampTagRegex.findAll(trimmed).toList()
            if (allTimestamps.isNotEmpty()) {
                val content = trimmed.substring(allTimestamps.last().range.last + 1).trim()
                val wordMatches = wordRegex.findAll(content).toList()

                for (ts in allTimestamps) {
                    val (min, sec, msStr) = ts.destructured
                    val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                    val lineStartMs = (min.toLong() * 60_000) + (sec.toLong() * 1000) + ms + offsetMs

                    if (wordMatches.isNotEmpty()) {
                        val syllables = mutableListOf<Syllable>()
                        val plainText = StringBuilder()

                        for (i in wordMatches.indices) {
                            val match = wordMatches[i]
                            val (wMin, wSec, wMsStr) = match.destructured
                            val wMs = if (wMsStr.length == 2) wMsStr.toLong() * 10 else wMsStr.toLong()
                            val wStartMs = (wMin.toLong() * 60_000) + (wSec.toLong() * 1000) + wMs + offsetMs
                            val wordText = match.groupValues[4]
                            plainText.append(wordText)

                            val wEndMs = if (i + 1 < wordMatches.size) {
                                val nextMatch = wordMatches[i + 1]
                                val (nMin, nSec, nMsStr) = nextMatch.destructured
                                val nMs = if (nMsStr.length == 2) nMsStr.toLong() * 10 else nMsStr.toLong()
                                (nMin.toLong() * 60_000) + (nSec.toLong() * 1000) + nMs + offsetMs
                            } else {
                                // Default last word span bounded to 600ms
                                wStartMs + 600L
                            }

                            // Ensure syllable timeline monotonicity and non-negative duration
                            val validEndMs = wEndMs.coerceAtLeast(wStartMs)
                            syllables.add(
                                Syllable(
                                    text = wordText,
                                    startMs = wStartMs,
                                    endMs = validEndMs
                                )
                            )
                        }

                        parsedSyncedLines.add(
                            LyricsLine(
                                timeMs = lineStartMs,
                                text = plainText.toString().ifBlank { content },
                                syllables = syllables
                            )
                        )
                    } else {
                        parsedSyncedLines.add(
                            LyricsLine(
                                timeMs = lineStartMs,
                                text = content,
                                syllables = emptyList()
                            )
                        )
                    }
                }
            } else {
                unsyncedLines.add(LyricsLine(timeMs = 0L, text = trimmed))
            }
        }

        if (parsedSyncedLines.isEmpty()) {
            return LyricsData(
                lines = unsyncedLines,
                isSynced = false,
                globalOffsetMs = 0L
            )
        }

        // Sort lines monotonically by timeMs (normalizes out-of-order & multi-timestamp lines)
        val sortedLines = parsedSyncedLines.sortedBy { it.timeMs }.toMutableList()

        // Normalize durations and bound syllables to line bounds
        for (i in sortedLines.indices) {
            val cur = sortedLines[i]
            val nextTime = if (i + 1 < sortedLines.size) sortedLines[i + 1].timeMs else cur.timeMs + 4000L
            val lineDuration = (nextTime - cur.timeMs).coerceAtLeast(500L)

            // Bound the last syllable's endMs by the line boundary
            val boundedSyllables = if (cur.syllables.isNotEmpty()) {
                val lastIdx = cur.syllables.lastIndex
                cur.syllables.mapIndexed { idx, syl ->
                    if (idx == lastIdx && syl.endMs > nextTime) {
                        syl.copy(endMs = nextTime.coerceAtLeast(syl.startMs))
                    } else {
                        syl
                    }
                }
            } else {
                emptyList()
            }

            sortedLines[i] = cur.copy(
                durationMs = lineDuration,
                syllables = boundedSyllables
            )
        }

        return LyricsData(
            lines = sortedLines,
            isSynced = true,
            globalOffsetMs = offsetMs
        )
    }
}
