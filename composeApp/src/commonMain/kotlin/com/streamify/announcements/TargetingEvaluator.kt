package com.streamify.announcements

/**
 * Validates targeting parameters against the current client environment and user profile,
 * and interpolates safe local personalization placeholders.
 */
object TargetingEvaluator {
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000L

    fun isEligible(
        targeting: Targeting,
        currentVersion: String,
        currentPlatform: String,
        currentLocale: String,
        profile: UserProfileContext = UserProfileContext(),
        announcementId: String = "",
        nowEpochMs: Long = 0L,
    ): Boolean {
        // Platform check
        if (targeting.platforms.isNotEmpty() && !targeting.platforms.any { it.equals(currentPlatform, ignoreCase = true) }) {
            return false
        }

        // Locale check with fallback handling
        if (targeting.locales.isNotEmpty()) {
            val effectiveLocale = if (currentLocale.isBlank() || currentLocale.equals("system", ignoreCase = true)) {
                "en"
            } else {
                currentLocale
            }
            if (!targeting.locales.any { effectiveLocale.startsWith(it, ignoreCase = true) }) {
                return false
            }
        }

        // Semver comparison
        targeting.minVersion?.let { minVer ->
            if (compareVersions(currentVersion, minVer) < 0) return false
        }

        targeting.maxVersion?.let { maxVer ->
            if (compareVersions(currentVersion, maxVer) > 0) return false
        }

        // Channel / flavor targeting
        if (targeting.channels.isNotEmpty()) {
            val channel = profile.channel?.takeIf { it.isNotBlank() } ?: return false
            if (!targeting.channels.any { it.equals(channel, ignoreCase = true) }) return false
        }

        // Authentication & admin role checks
        targeting.requireAuthenticated?.let { required ->
            val hasAuth = profile.isAuthenticated && !profile.userId.isNullOrBlank()
            if (hasAuth != required) return false
        }

        targeting.requireAdmin?.let { required ->
            if (profile.isAdmin != required) return false
        }

        // Engagement & listening thresholds
        targeting.minPlays?.let { min ->
            if (profile.totalPlays < min) return false
        }
        targeting.maxPlays?.let { max ->
            if (profile.totalPlays > max) return false
        }
        targeting.minListeningHours?.let { minHours ->
            val hours = profile.listeningSeconds / 3600.0
            if (hours < minHours) return false
        }

        // Favorite genre targeting
        if (targeting.favoriteGenres.isNotEmpty()) {
            val genre = profile.favoriteGenre?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
                ?: return false
            if (!targeting.favoriteGenres.any { it.equals(genre, ignoreCase = true) }) return false
        }

        // Account age & dormancy checks
        if (targeting.minAccountAgeDays != null || targeting.maxAccountAgeDays != null) {
            val createdAt = profile.createdAtEpochMs ?: return false
            if (nowEpochMs <= 0L) return false
            val ageDays = ((nowEpochMs - createdAt).coerceAtLeast(0L)) / MS_PER_DAY
            targeting.minAccountAgeDays?.let { if (ageDays < it) return false }
            targeting.maxAccountAgeDays?.let { if (ageDays > it) return false }
        }

        targeting.minDormantDays?.let { minDormant ->
            val lastActive = profile.lastActiveAtEpochMs ?: return false
            if (nowEpochMs <= 0L) return false
            val dormantDays = ((nowEpochMs - lastActive).coerceAtLeast(0L)) / MS_PER_DAY
            if (dormantDays < minDormant) return false
        }

        // Privacy-preserving 1:1 user targeting via salted SHA-256("$announcementId:$userId")
        if (targeting.targetUserHashes.isNotEmpty()) {
            val uid = profile.userId?.takeIf { it.isNotBlank() } ?: return false
            if (announcementId.isBlank()) return false
            val expectedHash = userTargetHash(announcementId, uid)
            if (!targeting.targetUserHashes.any { it.equals(expectedHash, ignoreCase = true) }) {
                return false
            }
        }

        // Deterministic percentage rollout (0..100) keyed on userId + announcementId
        targeting.rolloutPercent?.let { rawPct ->
            val pct = rawPct.coerceIn(0, 100)
            if (pct == 0) return false
            if (pct < 100) {
                val uid = profile.userId?.takeIf { it.isNotBlank() } ?: return false
                val bucket = rolloutBucket(announcementId, uid)
                if (bucket >= pct) return false
            }
        }

        return true
    }

    /**
     * Computes the privacy-preserving target hash for a specific user and announcement.
     */
    fun userTargetHash(announcementId: String, userId: String): String =
        Sha256.hex("$announcementId:$userId")

    /**
     * Maps (announcementId, userId) deterministically to [0, 99].
     */
    fun rolloutBucket(announcementId: String, userId: String): Int {
        val digest = Sha256.digest("$announcementId:$userId".encodeToByteArray())
        val unsigned =
            ((digest[0].toLong() and 0xffL) shl 24) or
                ((digest[1].toLong() and 0xffL) shl 16) or
                ((digest[2].toLong() and 0xffL) shl 8) or
                (digest[3].toLong() and 0xffL)
        return (unsigned % 100L).toInt()
    }

    /**
     * Interpolates safe local profile placeholders in announcement copy.
     */
    fun personalize(announcement: Announcement, profile: UserProfileContext): Announcement {
        val displayName = profile.displayName?.takeIf { it.isNotBlank() } ?: "there"
        val favoriteGenre = profile.favoriteGenre?.takeIf { it.isNotBlank() && !it.equals("All", ignoreCase = true) } ?: "music"
        val totalPlays = profile.totalPlays.toString()
        val listeningHours = (profile.listeningSeconds / 3600L).toString()
        val topTrack = profile.topTrack?.takeIf { it.isNotBlank() } ?: "your favorite track"

        fun interpolate(text: String): String =
            text
                .replace("{{display_name}}", displayName)
                .replace("{{favorite_genre}}", favoriteGenre)
                .replace("{{total_plays}}", totalPlays)
                .replace("{{listening_hours}}", listeningHours)
                .replace("{{top_track}}", topTrack)

        val localized = announcement.content.mapValues { (_, item) ->
            item.copy(
                title = interpolate(item.title),
                body = interpolate(item.body),
                actionLabel = item.actionLabel?.let(::interpolate),
            )
        }
        return announcement.copy(content = localized)
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val clean1 = v1.trim().removePrefix("v").removePrefix("V")
        val clean2 = v2.trim().removePrefix("v").removePrefix("V")
        val s1 = clean1.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val s2 = clean2.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(s1.size, s2.size)

        for (i in 0 until maxLen) {
            val p1 = s1.getOrElse(i) { 0 }
            val p2 = s2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }
}
