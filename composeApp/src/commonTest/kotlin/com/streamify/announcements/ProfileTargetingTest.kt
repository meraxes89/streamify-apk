package com.streamify.announcements

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileTargetingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun testSha256KnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc"),
        )
    }

    @Test
    fun testTargetingDeserializationBackwardCompatibility() {
        val raw = """
            {
              "minVersion": "6.0.0",
              "platforms": ["desktop"],
              "locales": ["en"]
            }
        """.trimIndent()
        val targeting = json.decodeFromString<Targeting>(raw)
        assertEquals("6.0.0", targeting.minVersion)
        assertEquals(listOf("desktop"), targeting.platforms)
        assertTrue(targeting.targetUserHashes.isEmpty())
        assertEquals(null, targeting.rolloutPercent)
        assertEquals(null, targeting.minPlays)
    }

    @Test
    fun testEngagementAndGenreTargeting() {
        val targeting = Targeting(
            minPlays = 10,
            maxPlays = 500,
            minListeningHours = 1.5,
            favoriteGenres = listOf("Synthwave", "Lo-Fi"),
        )

        val eligibleProfile = UserProfileContext(
            userId = "user-123",
            totalPlays = 42,
            listeningSeconds = 7200L, // 2.0h
            favoriteGenre = "lo-fi",
            isAuthenticated = true,
        )
        assertTrue(
            TargetingEvaluator.isEligible(
                targeting = targeting,
                currentVersion = "6.0.0",
                currentPlatform = "desktop",
                currentLocale = "en",
                profile = eligibleProfile,
            ),
        )

        // Too few plays
        assertFalse(
            TargetingEvaluator.isEligible(
                targeting = targeting,
                currentVersion = "6.0.0",
                currentPlatform = "desktop",
                currentLocale = "en",
                profile = eligibleProfile.copy(totalPlays = 3),
            ),
        )

        // Insufficient listening hours
        assertFalse(
            TargetingEvaluator.isEligible(
                targeting = targeting,
                currentVersion = "6.0.0",
                currentPlatform = "desktop",
                currentLocale = "en",
                profile = eligibleProfile.copy(listeningSeconds = 3600L), // 1.0h < 1.5h
            ),
        )

        // Genre mismatch / "All" default
        assertFalse(
            TargetingEvaluator.isEligible(
                targeting = targeting,
                currentVersion = "6.0.0",
                currentPlatform = "desktop",
                currentLocale = "en",
                profile = eligibleProfile.copy(favoriteGenre = "All"),
            ),
        )
    }

    @Test
    fun testAccountAgeAndDormancyTargeting() {
        val dayMs = 24L * 60 * 60 * 1000L
        val now = 1_750_000_000_000L
        val targeting = Targeting(
            minAccountAgeDays = 7,
            maxAccountAgeDays = 90,
            minDormantDays = 14,
        )

        val profile = UserProfileContext(
            userId = "user-456",
            createdAtEpochMs = now - 30L * dayMs,
            lastActiveAtEpochMs = now - 20L * dayMs,
            isAuthenticated = true,
        )
        assertTrue(
            TargetingEvaluator.isEligible(
                targeting = targeting,
                currentVersion = "6.0.0",
                currentPlatform = "android",
                currentLocale = "en",
                profile = profile,
                nowEpochMs = now,
            ),
        )

        // Active yesterday -> not dormant enough
        assertFalse(
            TargetingEvaluator.isEligible(
                targeting = targeting,
                currentVersion = "6.0.0",
                currentPlatform = "android",
                currentLocale = "en",
                profile = profile.copy(lastActiveAtEpochMs = now - dayMs),
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun testPrivacyPreservingUserHashTargeting() {
        val announcementId = "vip_notice_01"
        val targetHash = TargetingEvaluator.userTargetHash(announcementId, "uuid-target-user")
        val targeting = Targeting(targetUserHashes = listOf(targetHash))

        assertTrue(
            TargetingEvaluator.isEligible(
                targeting = targeting,
                currentVersion = "6.0.0",
                currentPlatform = "desktop",
                currentLocale = "en",
                profile = UserProfileContext(userId = "uuid-target-user", isAuthenticated = true),
                announcementId = announcementId,
            ),
        )

        assertFalse(
            TargetingEvaluator.isEligible(
                targeting = targeting,
                currentVersion = "6.0.0",
                currentPlatform = "desktop",
                currentLocale = "en",
                profile = UserProfileContext(userId = "uuid-other-user", isAuthenticated = true),
                announcementId = announcementId,
            ),
        )

        assertFalse(
            TargetingEvaluator.isEligible(
                targeting = targeting,
                currentVersion = "6.0.0",
                currentPlatform = "desktop",
                currentLocale = "en",
                profile = UserProfileContext(userId = null),
                announcementId = announcementId,
            ),
        )
    }

    @Test
    fun testDeterministicRolloutPercent() {
        val announcementId = "rollout_test_01"
        val bucket = TargetingEvaluator.rolloutBucket(announcementId, "user-abc")
        assertTrue(bucket in 0..99)
        assertEquals(bucket, TargetingEvaluator.rolloutBucket(announcementId, "user-abc"))

        // 0% excludes everyone; 100% includes everyone
        assertFalse(
            TargetingEvaluator.isEligible(
                targeting = Targeting(rolloutPercent = 0),
                currentVersion = "6.0.0",
                currentPlatform = "desktop",
                currentLocale = "en",
                profile = UserProfileContext(userId = "user-abc"),
                announcementId = announcementId,
            ),
        )
        assertTrue(
            TargetingEvaluator.isEligible(
                targeting = Targeting(rolloutPercent = 100),
                currentVersion = "6.0.0",
                currentPlatform = "desktop",
                currentLocale = "en",
                profile = UserProfileContext(userId = null),
                announcementId = announcementId,
            ),
        )
    }

    @Test
    fun testLocalCopyPersonalizationWithAndWithoutProfile() {
        val base = Announcement(
            id = "personalized_01",
            content = mapOf(
                "en" to LocalizedContent(
                    title = "Hey {{display_name}}!",
                    body = "You've played {{total_plays}} tracks ({{listening_hours}}h) in {{favorite_genre}}, led by {{top_track}}.",
                    actionLabel = "Play {{favorite_genre}}",
                ),
            ),
        )

        val rich = TargetingEvaluator.personalize(
            base,
            UserProfileContext(
                displayName = "Sireen",
                totalPlays = 120,
                listeningSeconds = 18000L, // 5h
                favoriteGenre = "Synthwave",
                topTrack = "Nightcall",
            ),
        )
        val richEn = rich.content.getValue("en")
        assertEquals("Hey Sireen!", richEn.title)
        assertEquals("You've played 120 tracks (5h) in Synthwave, led by Nightcall.", richEn.body)
        assertEquals("Play Synthwave", richEn.actionLabel)

        val anon = TargetingEvaluator.personalize(base, UserProfileContext())
        val anonEn = anon.content.getValue("en")
        assertEquals("Hey there!", anonEn.title)
        assertEquals("You've played 0 tracks (0h) in music, led by your favorite track.", anonEn.body)
        assertEquals("Play music", anonEn.actionLabel)
    }
}
