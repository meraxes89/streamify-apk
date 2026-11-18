package com.streamify.app.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * SEC-04: updater certificate pinning.
 *
 * Verifies that a downloaded APK archive is signed by the SAME certificate
 * as the currently running app BEFORE the ACTION_VIEW package-installer
 * intent is fired. Without this check, anyone able to tamper with the
 * download (compromised mirror, MITM on the release-asset URL, or a
 * malicious app squatting on the shared Downloads directory) could hand
 * the user a hijacked "update" — and because the installer prompt is
 * driven by our own installer dispatch, the user has every reason to
 * trust it. Pinning the archive signature up front means a forged archive
 * never reaches the install surface at all.
 *
 * The pin is self-referential: the running app's own signer certificate
 * set IS the trusted anchor. That is exactly the continuity guarantee
 * Android itself enforces at package-replace time, applied one step
 * earlier; debug builds only accept debug-signed updates and release
 * builds only accept release-signed updates automatically.
 *
 * Fail-closed semantics: corrupt archives, unsigned archives, signature
 * schemes the platform cannot parse from an archive, package-name
 * mismatches and digest mismatches are all rejected — callers must
 * refuse to install when verification does not return [Verification.Trusted].
 */
object ApkSignatureVerifier {

    /** Outcome of a pinning verification for a downloaded update archive. */
    sealed interface Verification {
        /** Every archive signer certificate matches the running app's pin. */
        object Trusted : Verification

        /** The archive must NOT be handed to the package installer. */
        data class Rejected(val reason: String) : Verification
    }

    /**
     * Verifies a downloaded APK against the updater certificate pin.
     *
     * Checks, in order:
     *  1. The running app's signer certificate digests can be resolved.
     *  2. The archive parses as an APK package and carries the SAME
     *     package name as the running app (blocks side-loading foreign
     *     apps through Streamify's updater UI).
     *  3. The archive is signed and EVERY signer's certificate SHA-256
     *     digest matches a pinned digest of the running app.
     */
    fun verifyUpdateCertificate(context: Context, apk: File): Verification {
        val pm = context.packageManager

        val pinned = pinnedSignerDigests(pm, context.packageName)
            ?: return Verification.Rejected("unable to resolve running app signature")

        // Archive parse WITHOUT installing: identity + signer certificates.
        // Both signature flags are requested so that archives signed with
        // only v2/v3 schemes resolve on API 28+ while v1 (JAR) signatures
        // still resolve on API 26/27 where signingInfo does not exist.
        @Suppress("DEPRECATION")
        val archiveFlags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val archiveInfo = pm.getPackageArchiveInfo(apk.absolutePath, archiveFlags)
            ?: return Verification.Rejected("not a parseable APK archive")

        if (archiveInfo.packageName != context.packageName) {
            return Verification.Rejected(
                "archive package name mismatch: ${archiveInfo.packageName}"
            )
        }

        val archiveDigests = signerDigests(archiveInfo)
            ?: return Verification.Rejected("archive is unsigned or its signature scheme is unsupported")

        val trusted = archiveDigests.isNotEmpty() && archiveDigests.all { it in pinned }
        return if (trusted) {
            Verification.Trusted
        } else {
            Verification.Rejected("signer certificate SHA-256 mismatch (possible update hijack)")
        }
    }

    /** SHA-256 digests of the signer certificates of the RUNNING app. */
    private fun pinnedSignerDigests(pm: PackageManager, packageName: String): Set<String>? {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        return try {
            val info = pm.getPackageInfo(packageName, flags)
            signerDigests(info)
        } catch (e: Exception) {
            null
        }
    }

    /** SHA-256 digests of the signer certificates of a parsed [PackageInfo]. */
    private fun signerDigests(info: PackageInfo): Set<String>? {
        val signatures = resolveSignatures(info) ?: return null
        return signatures
            .takeIf { it.isNotEmpty() }
            ?.map { sha256Hex(it.toByteArray()) }
            ?.toSet()
    }

    /**
     * Resolves signer certificates across API levels: signingInfo
     * (v2/v3 signing blocks, API 28+) with the legacy signatures array
     * (v1 JAR signatures) as fallback — the archive parser does not
     * populate signingInfo on every platform revision, so the legacy
     * field remains a live resolution path, not just a pre-28 one.
     */
    private fun resolveSignatures(info: PackageInfo): Array<Signature>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.let { return it }
        }
        @Suppress("DEPRECATION")
        return info.signatures
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
