package com.streamify.lyrics.slyr

/**
 * High-performance SLYR Lyrics Bytecode Compiler.
 *
 * Compiles raw LRC and syllable-tagged text `<mm:ss.xx>word` into
 * phase-aligned sub-millisecond timeline structures.
 */
object SlyrBytecodeCompiler {

    private val offsetRegex = Regex("""\[offset:\s*(-?\d+)\]""", RegexOption.IGNORE_CASE)
    private val lineRegex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")
    private val wordRegex = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>([^<\[]*)""")

    fun compile(lrcContent: String): LyricsData {
        if (lrcContent.isBlank()) return LyricsData(emptyList(), isSynced = false)

        var offsetMs = 0L
        offsetRegex.find(lrcContent)?.let {
            offsetMs = it.groupValues[1].toLongOrNull() ?: 0L
        }

        val rawLines = lrcContent.split("\n")
        val syncedLines = mutableListOf<LyricsLine>()
        val unsyncedLines = mutableListOf<LyricsLine>()

        for (raw in rawLines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue

            val lineMatch = lineRegex.find(trimmed)
            if (lineMatch != null) {
                val (min, sec, msStr) = lineMatch.destructured
                val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                val lineStartMs = (min.toLong() * 60_000) + (sec.toLong() * 1000) + ms + offsetMs
                val content = lineMatch.groupValues[4].trim()

                // Parse word-level/syllable-level timestamps
                val syllables = mutableListOf<Syllable>()
                val wordMatches = wordRegex.findAll(content).toList()

                if (wordMatches.isNotEmpty()) {
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
                            wStartMs + 500L
                        }

                        syllables.add(
                            Syllable(
                                text = wordText,
                                startMs = wStartMs,
                                endMs = wEndMs
                            )
                        )
                    }
                    syncedLines.add(
                        LyricsLine(
                            timeMs = lineStartMs,
                            text = plainText.toString().ifBlank { content },
                            syllables = syllables
                        )
                    )
                } else {
                    syncedLines.add(
                        LyricsLine(
                            timeMs = lineStartMs,
                            text = content,
                            syllables = emptyList()
                        )
                    )
                }
            } else {
                unsyncedLines.add(LyricsLine(timeMs = 0L, text = trimmed))
            }
        }

        // Compute line durations
        for (i in syncedLines.indices) {
            val cur = syncedLines[i]
            val duration = if (i + 1 < syncedLines.size) {
                (syncedLines[i + 1].timeMs - cur.timeMs).coerceAtLeast(1000L)
            } else {
                4000L
            }
            syncedLines[i] = cur.copy(durationMs = duration)
        }

        return if (syncedLines.isNotEmpty()) {
            LyricsData(
                lines = syncedLines.sortedBy { it.timeMs },
                isSynced = true,
                globalOffsetMs = offsetMs
            )
        } else {
            LyricsData(
                lines = unsyncedLines,
                isSynced = false,
                globalOffsetMs = 0L
            )
        }
    }
}
