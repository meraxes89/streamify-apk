package com.streamify.app.jam

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * JAM GOVERNANCE — Host ACLs, Co-Hosts & In-Room Moderation
 * (BEHIND.md Gaps #14 + #18, Phase 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Pure-Kotlin governance brain, unit-testable in complete isolation from the
 * mesh. The engine mirrors its decisions onto the wire (GOVERNANCE frames)
 * and enforces them at intent ingress (epoch-gated drop).
 *
 * ACL model (per member, carried by the room's governance table):
 *
 *   allowControl   — may emit transport intents (play/pause/skip/seek) and
 *                    queue mutations. Host-off override of ControlPolicy.
 *   allowVolume    — route-aware volume ACL (Gap #16 groundwork): ignored
 *                    when the host is on BT/smart-speaker routes where the
 *                    OS owns gain.
 *   role           — HOST / CO_HOST / MEMBER. Co-hosts may emit leader-grade
 *                    intents (epoch-fenced) EXCEPT governance mutations —
 *                    only the host demotes/promotes.
 *
 * Moderation (Gap #18):
 *   kick           — targeted removal: the kicked device receives a
 *                    KICK frame (SESSION_END-shaped, see engine) and its
 *                    nonce enters the room's block list for the room
 *                    lifetime; rejoin attempts are refused at admission.
 *   ban            — block-list entry without prior kick (queue spamming).
 *   report         — guest affordance: report a disruptive member to the
 *                    host with a reason code. Rate-limited per reporter
 *                    (default: max 3 per 60 s window) so the report channel
 *                    itself cannot be spammed.
 */
object JamGovernance {

    // ── Roles ──────────────────────────────────────────────────────────────

    enum class Role { HOST, CO_HOST, MEMBER }

    enum class ReportReason(val wireCode: Int, val label: String) {
        DISRUPTIVE_PLAYBACK(1, "Disruptive playback"),
        QUEUE_SPAM(2, "Queue spamming"),
        HARASSMENT(3, "Harassment"),
        OTHER(4, "Other");

        companion object {
            fun fromWire(code: Int): ReportReason? = entries.firstOrNull { it.wireCode == code }
        }
    }

    // ── ACL row ────────────────────────────────────────────────────────────

    /**
     * Per-member permission row. The HOST row is implicit (always allowed);
     * the table stores guest rows only.
     * [androidx.compose.runtime.Immutable] — pure value row for the governance UI.
     */
    @androidx.compose.runtime.Immutable
    data class MemberAcl(
        val nonce: String,
        val role: Role = Role.MEMBER,
        val allowControl: Boolean = true,
        val allowVolume: Boolean = true
    ) {
        val isCoHost: Boolean get() = role == Role.CO_HOST
    }

    /**
     * One guest report, relayed to the host surface (Governance sheet).
     * [androidx.compose.runtime.Immutable] — pure value row for the report list.
     */
    @androidx.compose.runtime.Immutable
    data class MemberReport(
        val reporterNonce: String,
        val targetNonce: String,
        val reason: ReportReason,
        val atMs: Long
    )

    /**
     * Governance decision outcome — mirrors [TopologyDecision] ergonomics.
     */
    sealed class Decision {
        data class Applied(val description: String) : Decision()
        data class Refused(val reason: String) : Decision()
    }

    // ── State ──────────────────────────────────────────────────────────────

    /**
     * The full governance table for one room. The engine owns one instance
     * per active session (reset on endLocally). All ops are O(members).
     */
    class Table {
        private val acl = LinkedHashMap<String, MemberAcl>()
        private val blocked = LinkedHashSet<String>()
        private val reports = ArrayList<MemberReport>()
        private val reportTimestamps = HashMap<String, ArrayDeque<Long>>()

        /** Default admission: everyone controls unless the host narrowed it. */
        var defaultAllowControl: Boolean = true
        var defaultAllowVolume: Boolean = true

        // ── Admission & membership ──────────────────────────────────────────

        /**
         * Gap #11 + #18: admission gate — room cap 32 AND block list. Kicked
         * or banned nonces are refused for the room lifetime.
         */
        fun admits(nonce: String, currentMemberCount: Int): Boolean =
            !blocked.contains(nonce) && currentMemberCount < JamTopologyMachine.MAX_MEMBERS_HARD_CAP

        fun upsert(row: MemberAcl) {
            require(row.nonce.isNotBlank()) { "governance row requires a nonce" }
            acl[row.nonce] = row
        }

        fun aclOf(nonce: String): MemberAcl? = acl[nonce]

        fun rowOf(nonce: String): MemberAcl =
            acl[nonce] ?: MemberAcl(
                nonce = nonce,
                allowControl = defaultAllowControl,
                allowVolume = defaultAllowVolume
            )

        fun remove(nonce: String): MemberAcl? = acl.remove(nonce)

        fun members(): List<MemberAcl> = acl.values.toList()

        // ── Host actions ────────────────────────────────────────────────────

        /**
         * Host/co-host authority gate for GOVERNANCE mutations. Only the
         * HOST may mutate governance (co-hosts get everything else).
         */
        fun mayGovern(actorNonce: String, hostNonce: String): Boolean =
            actorNonce.isNotBlank() && actorNonce == hostNonce

        /** Host flips a guest's transport control permission. */
        fun setAllowControl(hostNonce: String, actorNonce: String, target: String, allow: Boolean): Decision {
            if (!mayGovern(actorNonce, hostNonce)) return Decision.Refused("governance is host-only")
            if (target == hostNonce) return Decision.Refused("cannot target the host")
            if (blocked.contains(target)) return Decision.Refused("target is blocked")
            val row = rowOf(target).copy(allowControl = allow)
            acl[target] = row
            return Decision.Applied(if (allow) "control granted to $target" else "control revoked from $target")
        }

        /**
         * Route-aware volume ACL (Gap #16 groundwork): the host can share or
         * lock guest volume. [routeOwnedByOs] additionally forces refusal on
         * BT/smart-speaker routes where the OS owns gain — the engine checks
         * it at ingress, the sheet shows the lock.
         */
        fun setAllowVolume(hostNonce: String, actorNonce: String, target: String, allow: Boolean): Decision {
            if (!mayGovern(actorNonce, hostNonce)) return Decision.Refused("governance is host-only")
            if (target == hostNonce) return Decision.Refused("cannot target the host")
            if (blocked.contains(target)) return Decision.Refused("target is blocked")
            val row = rowOf(target).copy(allowVolume = allow)
            acl[target] = row
            return Decision.Applied(if (allow) "volume granted to $target" else "volume locked for $target")
        }

        /** Promote to co-host (or demote back to member). Host cannot be demoted. */
        fun setRole(hostNonce: String, actorNonce: String, target: String, role: Role): Decision {
            if (!mayGovern(actorNonce, hostNonce)) return Decision.Refused("governance is host-only")
            if (target == hostNonce) return Decision.Refused("the host role is fixed")
            if (blocked.contains(target)) return Decision.Refused("target is blocked")
            val row = rowOf(target).copy(role = role)
            acl[target] = row
            return Decision.Applied("$target is now ${role.name}")
        }

        /**
         * Kick (Gap #14): removes the ACL row AND blocks the nonce for the
         * room lifetime. Rejoin is refused at admission.
         */
        fun kick(hostNonce: String, actorNonce: String, target: String): Decision {
            if (!mayGovern(actorNonce, hostNonce)) return Decision.Refused("kick is host-only")
            if (target == hostNonce) return Decision.Refused("cannot kick the host")
            acl.remove(target)
            blocked.add(target)
            return Decision.Applied("$target kicked and blocked for this room")
        }

        /**
         * Ban without kick — for queue spammers the host has not removed yet.
         */
        fun ban(hostNonce: String, actorNonce: String, target: String): Decision {
            if (!mayGovern(actorNonce, hostNonce)) return Decision.Refused("ban is host-only")
            if (target == hostNonce) return Decision.Refused("cannot ban the host")
            blocked.add(target)
            return Decision.Applied("$target banned from this room")
        }

        fun isBlocked(nonce: String): Boolean = blocked.contains(nonce)
        fun blockedNonces(): List<String> = blocked.toList()

        // ── Ingress enforcement ─────────────────────────────────────────────

        /**
         * The intent ingress gate. Called by the engine BEFORE any guest
         * frame mutates playback/queue. Combines the coarse ControlPolicy
         * with the per-member ACL (host/co-host always pass; host-off ACLs
         * override EVERYONE policy per member).
         */
        fun mayEmitControlIntent(
            nonce: String,
            hostNonce: String,
            policy: JamEngine.ControlPolicy
        ): Boolean {
            if (nonce == hostNonce) return true
            if (blocked.contains(nonce)) return false
            val row = rowOf(nonce)
            if (row.role == Role.CO_HOST) return true
            return policy == JamEngine.ControlPolicy.EVERYONE && row.allowControl
        }

        /**
         * Volume intents are additionally gated by the per-member volume ACL.
         */
        fun mayEmitVolumeIntent(
            nonce: String,
            hostNonce: String,
            policy: JamEngine.ControlPolicy,
            routeOwnedByOs: Boolean = false
        ): Boolean {
            if (routeOwnedByOs) return false // BT/smart-speaker: OS owns gain
            if (nonce == hostNonce) return true
            if (blocked.contains(nonce)) return false
            return rowOf(nonce).allowVolume && mayEmitControlIntent(nonce, hostNonce, policy)
        }

        // ── Reporting (Gap #18) ─────────────────────────────────────────────

        /**
         * Guest files a report. Rate limit: at most [maxReports] per
         * [windowMs] sliding window per reporter — the report channel itself
         * must be spam-proof.
         */
        fun fileReport(
            reporterNonce: String,
            targetNonce: String,
            reason: ReportReason,
            nowMs: Long,
            maxReports: Int = 3,
            windowMs: Long = 60_000L
        ): Decision {
            if (reporterNonce == targetNonce) return Decision.Refused("cannot report yourself")
            if (targetNonce.isBlank()) return Decision.Refused("report target required")
            if (blocked.contains(reporterNonce)) return Decision.Refused("blocked members cannot report")
            val stamps = reportTimestamps.getOrPut(reporterNonce) { ArrayDeque() }
            while (stamps.isNotEmpty() && nowMs - stamps.first() > windowMs) stamps.removeFirst()
            if (stamps.size >= maxReports) return Decision.Refused("rate limited: max $maxReports reports per ${windowMs / 1000}s")
            stamps.addLast(nowMs)
            reports.add(MemberReport(reporterNonce, targetNonce, reason, nowMs))
            return Decision.Applied("report filed against $targetNonce")
        }

        /** Host-facing report inbox (oldest first), draining optional. */
        fun pendingReports(): List<MemberReport> = reports.toList()

        fun clearReports(): Unit { reports.clear() }

        /** Full wipe between tests / room end. */
        fun reset() {
            acl.clear()
            blocked.clear()
            reports.clear()
            reportTimestamps.clear()
            defaultAllowControl = true
            defaultAllowVolume = true
        }
    }
}
