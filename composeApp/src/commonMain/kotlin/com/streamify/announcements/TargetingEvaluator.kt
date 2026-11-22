package com.streamify.announcements

/**
 * Validates targeting parameters against the current client environment.
 */
object TargetingEvaluator {

    fun isEligible(
        targeting: Targeting,
        currentVersion: String,
        currentPlatform: String,
        currentLocale: String
    ): Boolean {
        // Platform check
        if (targeting.platforms.isNotEmpty() && !targeting.platforms.contains(currentPlatform.lowercase())) {
            return false
        }

        // Locale check
        if (targeting.locales.isNotEmpty() && !targeting.locales.any { currentLocale.startsWith(it, ignoreCase = true) }) {
            return false
        }

        // Semver simple comparison
        targeting.minVersion?.let { minVer ->
            if (compareVersions(currentVersion, minVer) < 0) return false
        }

        targeting.maxVersion?.let { maxVer ->
            if (compareVersions(currentVersion, maxVer) > 0) return false
        }

        return true
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val s1 = v1.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val s2 = v2.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(s1.size, s2.size)

        for (i in 0 until maxLen) {
            val p1 = s1.getOrElse(i) { 0 }
            val p2 = s2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }
}
