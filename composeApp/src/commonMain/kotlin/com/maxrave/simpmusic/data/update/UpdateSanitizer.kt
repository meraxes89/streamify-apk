package com.maxrave.simpmusic.data.update

object UpdateSanitizer {
    /**
     * Extracts pure changelog features and fixes, filtering out raw multiplatform download tables
     * and verification checksum blocks that clutter mobile and desktop screens.
     */
    fun sanitizeChangelog(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()

        // If the markdown contains a dedicated "What's New" section, extract only that section
        val whatsNewIndex = trimmed.indexOf("## What's New")
        if (whatsNewIndex != -1) {
            return trimmed.substring(whatsNewIndex).trim()
        }

        // Otherwise, filter line-by-line to strip out platform download tables and checksum blocks
        val lines = trimmed.lines()
        val result = mutableListOf<String>()
        var skippingPlatformSection = false

        for (line in lines) {
            val lineTrimmed = line.trim()
            if (lineTrimmed.startsWith("## Downloads by Platform") ||
                lineTrimmed.startsWith("### Android") ||
                lineTrimmed.startsWith("### Linux") ||
                lineTrimmed.startsWith("### Windows") ||
                lineTrimmed.startsWith("### macOS") ||
                lineTrimmed.startsWith("### Web") ||
                lineTrimmed.startsWith("### Verification Checksums")
            ) {
                skippingPlatformSection = true
                continue
            }

            if (skippingPlatformSection) {
                if (lineTrimmed.startsWith("## ") || (lineTrimmed == "---" && !lineTrimmed.startsWith("### "))) {
                    skippingPlatformSection = false
                    if (lineTrimmed == "---") continue
                } else {
                    continue
                }
            }

            // Drop any loose table rows mentioning binary extensions
            if (lineTrimmed.startsWith("|") && (
                    lineTrimmed.contains(".apk", ignoreCase = true) ||
                    lineTrimmed.contains(".deb", ignoreCase = true) ||
                    lineTrimmed.contains(".rpm", ignoreCase = true) ||
                    lineTrimmed.contains(".msi", ignoreCase = true) ||
                    lineTrimmed.contains(".dmg", ignoreCase = true)
                )
            ) {
                continue
            }

            result.add(line)
        }

        return result.joinToString("\n").trim()
    }
}
