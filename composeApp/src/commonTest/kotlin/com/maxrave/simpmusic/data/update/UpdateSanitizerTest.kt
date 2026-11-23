package com.maxrave.simpmusic.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateSanitizerTest {

    @Test
    fun extractWhatsNewSectionWhenPresent() {
        val raw = """
            # Streamify v1.0.3-beta

            ## Downloads by Platform

            ### Android
            | Package | Architecture | Description |
            | :--- | :--- | :--- |
            | [Streamify-v1.0.3-beta-arm64-v8a-release.apk](https://...) | ARM64 | Modern 64-bit |

            ---
            ### Verification Checksums
            SHA-256 checksums in SHA256SUMS.

            ---
            ## What's New in v1.0.3-beta
            - Brand new glassy player pill
            - Unmetered update manifests
        """.trimIndent()

        val sanitized = UpdateSanitizer.sanitizeChangelog(raw)

        assertTrue(sanitized.startsWith("## What's New"))
        assertTrue(sanitized.contains("Brand new glassy player pill"))
        assertTrue(sanitized.contains("Unmetered update manifests"))
        assertFalse(sanitized.contains("Downloads by Platform"))
        assertFalse(sanitized.contains("Verification Checksums"))
        assertFalse(sanitized.contains("| Package |"))
    }

    @Test
    fun stripsPlatformTablesWhenWhatsNewHeaderIsMissing() {
        val raw = """
            ## Downloads by Platform

            ### Android
            | Package | Architecture | Description |
            | :--- | :--- | :--- |
            | [Streamify.apk](https://...) | Universal | All devices |

            ### Linux (Desktop)
            | Package | Target Distribution | Installation |
            | :--- | :--- | :--- |
            | [streamify.deb](https://...) | Ubuntu | sudo apt install |

            ## Fixes and Improvements
            - Resolved audio focus loss on phone calls
            - Fixed background notification icon
        """.trimIndent()

        val sanitized = UpdateSanitizer.sanitizeChangelog(raw)

        assertFalse(sanitized.contains("Downloads by Platform"))
        assertFalse(sanitized.contains("Ubuntu"))
        assertFalse(sanitized.contains("Streamify.apk"))
        assertTrue(sanitized.contains("## Fixes and Improvements"))
        assertTrue(sanitized.contains("Resolved audio focus loss on phone calls"))
    }

    @Test
    fun passthroughCleanChangelogWithoutModification() {
        val clean = """
            - Modern glassy account pill
            - Fastly CDN manifest integration
        """.trimIndent()

        val sanitized = UpdateSanitizer.sanitizeChangelog(clean)

        assertEquals(clean, sanitized)
    }

    @Test
    fun handlesEmptyOrBlankInputSafely() {
        assertEquals("", UpdateSanitizer.sanitizeChangelog(""))
        assertEquals("", UpdateSanitizer.sanitizeChangelog("   \n\t  "))
    }
}
