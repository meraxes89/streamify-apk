//! local_sync.rs — Two-way local audio file LAN sync engine (Phase 3,
//! directive §2.3 / roadmap gap #43).
//!
//! MISSION: a desktop and a phone (or any two Jam peers) on the same LAN
//! reconcile their LOCAL audio libraries peer-to-peer — no cloud round
//! trip, no full file listing transfer, no blind overwrites.
//!
//! PROTOCOL (three phases, all pure logic — the transport layer injects
//! progress and executes the plan):
//!
//!   1. CATALOG EXCHANGE — each side serializes a compact
//!      [`LibraryCatalog`]: one entry per file, `[path u16-len][size u64]
//!      [mtime u64][Blake3 32]` (~50 B + path). Two fast paths keep this
//!      cheap: a 32-byte catalog [`fingerprint`] exchange proves the
//!      libraries identical BEFORE any catalog body moves, and the
//!      catalog body itself is wire-strict (LE, exact bounds, path
//!      traversal rejected at parse).
//!   2. DELTA COMPUTATION — [`CatalogDelta::compute`] diffs the two
//!      manifests locally: remote-only (pull candidates), local-only
//!      (push candidates), diverged (both present, hashes differ),
//!      identical (skip — zero bytes move).
//!   3. PLAN + SESSION — [`SyncPolicy`] gates the delta into a
//!      [`SyncPlan`] (bi-directional pull/push permission policies,
//!      conflict resolution, push byte budget), and a [`SyncSession`]
//!      drives per-file transfer tracking with pause/resume/cancel
//!      intents and a [`SyncEvent`] stream for the JNI callback bridge.
//!
//! FILE BYTES ride the Phase 3 chunk swarm / verifier — this engine never
//! touches payloads; it plans, gates, and tracks. That separation is what
//! makes the chaos suite (10 nodes, 20% loss, random drops) able to prove
//! convergence properties deterministically.
//!
//! CONFLICT SEMANTICS (documented, deterministic):
//!   • SkipDiverged  — nothing moves; the divergence is surfaced.
//!   • NewerWins     — mtime arbitrates; an mtime TIE with diverged
//!                     hashes is suspicious and skipped loudly.
//!   • KeepBoth      — the peer's copy is pulled to `<path>.peer` on this
//!                     side; the peer's mirrored session does the
//!                     symmetric pull. Nobody overwrites anybody.
//!
//! Like `gossip.rs` / `chunk_swarmer.rs`, this module is pure logic: no
//! clocks, no I/O, no RNG — deterministic and unit-testable.

use std::collections::BTreeMap;
use std::fmt;

use crate::chunk_verifier::{hash_file, ChunkHash};

/// Catalog wire magic (v1).
pub const CATALOG_MAGIC: &[u8; 4] = b"SLS1";
/// Per-path sanity ceiling (bytes).
pub const MAX_PATH_LEN: usize = 1024;
/// Per-catalog entry ceiling — hostile wire input must not OOM us.
pub const MAX_CATALOG_ENTRIES: u32 = 100_000;
/// Suffix appended to the peer's copy under `KeepBoth`.
pub const KEEP_BOTH_SUFFIX: &str = ".peer";

// ─────────────────────────────────────────────────────────── errors

/// LAN-sync layer errors.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SyncError {
    MalformedCatalog,
    CatalogTooLarge { entries: u32 },
    InvalidPath { path: String },
    UnknownJob,
}

impl fmt::Display for SyncError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SyncError::MalformedCatalog => write!(f, "catalog wire payload malformed"),
            SyncError::CatalogTooLarge { entries } => {
                write!(
                    f,
                    "catalog declares {entries} > {MAX_CATALOG_ENTRIES} entries"
                )
            }
            SyncError::InvalidPath { path } => write!(f, "path rejected: {path:?}"),
            SyncError::UnknownJob => write!(f, "no such transfer job in this session"),
        }
    }
}

impl std::error::Error for SyncError {}

// ─────────────────────────────────────────────────────────── catalog

/// One local file: identity (path) + content hash + metadata.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatalogEntry {
    /// Canonical relative path, forward slashes, never absolute, never
    /// traversing (`..` components are rejected).
    pub path: String,
    pub size: u64,
    /// Wall-clock mtime in milliseconds since epoch.
    pub mtime_ms: u64,
    /// Blake3 of the whole file — the identity that decides "diverged".
    pub content_hash: ChunkHash,
}

impl CatalogEntry {
    pub fn new(path: impl Into<String>, size: u64, mtime_ms: u64, content_hash: ChunkHash) -> Self {
        CatalogEntry {
            path: path.into(),
            size,
            mtime_ms,
            content_hash,
        }
    }
}

/// A local audio library manifest, canonically ordered by path.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LibraryCatalog {
    entries: BTreeMap<String, CatalogEntry>,
}

impl LibraryCatalog {
    /// Builds a catalog, validating every path (traversal/absolute/NUL
    /// rejection — a hostile peer catalog can never point outside the
    /// music directory).
    pub fn from_entries(entries: Vec<CatalogEntry>) -> Result<Self, SyncError> {
        if entries.len() > MAX_CATALOG_ENTRIES as usize {
            return Err(SyncError::CatalogTooLarge {
                entries: entries.len() as u32,
            });
        }
        let mut map = BTreeMap::new();
        for e in entries {
            if !valid_rel_path(&e.path) {
                return Err(SyncError::InvalidPath { path: e.path });
            }
            map.insert(e.path.clone(), e);
        }
        Ok(LibraryCatalog { entries: map })
    }

    pub fn entry_count(&self) -> u32 {
        self.entries.len() as u32
    }

    pub fn entries(&self) -> impl Iterator<Item = &CatalogEntry> {
        self.entries.values()
    }

    pub fn get(&self, path: &str) -> Option<&CatalogEntry> {
        self.entries.get(path)
    }

    /// 32-byte fingerprint over the canonical (path-sorted) encoding:
    /// exchanging JUST this digest lets peers prove "identical
    /// libraries" before any catalog body moves. Conservative: mtime is
    /// included, so a re-copied library fingerprints differently even
    /// when content matches — the delta pass then correctly reports
    /// identical files and moves zero bytes.
    pub fn fingerprint(&self) -> ChunkHash {
        let mut buf = Vec::new();
        for e in self.entries.values() {
            encode_entry_head(&mut buf, e);
        }
        hash_file(&buf)
    }

    /// Wire layout per entry (LE): `[path_len u16][path bytes][size u64]
    /// [mtime_ms u64][content_hash 32]`, entries in canonical path order.
    pub fn to_wire(&self) -> Vec<u8> {
        let mut p = Vec::with_capacity(8 + 58 * self.entries.len());
        p.extend_from_slice(CATALOG_MAGIC);
        p.extend_from_slice(&(self.entries.len() as u32).to_le_bytes());
        for e in self.entries.values() {
            encode_entry(&mut p, e);
        }
        p
    }

    /// Strict parse: magic, exact bounds, entry ceiling, path validation.
    /// Unknown trailing bytes are ignored (forward compatibility).
    pub fn from_wire(payload: &[u8]) -> Result<Self, SyncError> {
        if payload.len() < 8 || &payload[0..4] != CATALOG_MAGIC {
            return Err(SyncError::MalformedCatalog);
        }
        let count = u32::from_le_bytes(payload[4..8].try_into().unwrap());
        if count > MAX_CATALOG_ENTRIES {
            return Err(SyncError::CatalogTooLarge { entries: count });
        }
        let mut entries = Vec::with_capacity(count as usize);
        let mut off = 8usize;
        for _ in 0..count {
            if off + 2 > payload.len() {
                return Err(SyncError::MalformedCatalog);
            }
            let plen = u16::from_le_bytes(payload[off..off + 2].try_into().unwrap()) as usize;
            off += 2;
            if off + plen + 48 > payload.len() {
                return Err(SyncError::MalformedCatalog);
            }
            let path = match std::str::from_utf8(&payload[off..off + plen]) {
                Ok(s) => s.to_string(),
                Err(_) => return Err(SyncError::MalformedCatalog),
            };
            off += plen;
            let size = u64::from_le_bytes(payload[off..off + 8].try_into().unwrap());
            off += 8;
            let mtime_ms = u64::from_le_bytes(payload[off..off + 8].try_into().unwrap());
            off += 8;
            let content_hash: ChunkHash = payload[off..off + 32].try_into().unwrap();
            off += 32;
            entries.push(CatalogEntry {
                path,
                size,
                mtime_ms,
                content_hash,
            });
        }
        LibraryCatalog::from_entries(entries)
    }
}

fn encode_entry_head(buf: &mut Vec<u8>, e: &CatalogEntry) {
    buf.extend_from_slice(&(e.path.len() as u16).to_le_bytes());
    buf.extend_from_slice(e.path.as_bytes());
    buf.extend_from_slice(&e.size.to_le_bytes());
    buf.extend_from_slice(&e.mtime_ms.to_le_bytes());
    buf.extend_from_slice(&e.content_hash);
}

fn encode_entry(buf: &mut Vec<u8>, e: &CatalogEntry) {
    encode_entry_head(buf, e);
}

/// Canonical relative-path gate: forward slashes only, no drive letters,
/// no `..`/`.` components, no NUL, bounded depth and length.
pub fn valid_rel_path(path: &str) -> bool {
    if path.is_empty() || path.len() > MAX_PATH_LEN {
        return false;
    }
    if path.starts_with('/') || path.contains('\\') || path.contains('\0') {
        return false;
    }
    let depth = path.split('/').count();
    if depth > 32 {
        return false;
    }
    path.split('/')
        .all(|c| !c.is_empty() && c != "." && c != "..")
}

// ─────────────────────────────────────────────────────────── delta

/// Transfer direction (two-way sessions run both).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Direction {
    /// Remote → local.
    Pull,
    /// Local → remote.
    Push,
}

impl fmt::Display for Direction {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Direction::Pull => write!(f, "pull"),
            Direction::Push => write!(f, "push"),
        }
    }
}

/// The raw diff between two catalogs, before any policy gating.
#[derive(Debug, Clone, Default)]
pub struct CatalogDelta {
    /// Present only on the remote: pull candidates.
    pub pull: Vec<CatalogEntry>,
    /// Present only locally: push candidates.
    pub push: Vec<CatalogEntry>,
    /// Present on both sides with diverged content hashes.
    pub diverged: Vec<(CatalogEntry, CatalogEntry)>, // (local, remote)
    /// Present on both sides, byte-identical: zero bytes move.
    pub identical: u32,
}

impl CatalogDelta {
    /// Diffs two catalogs. Pure, deterministic, O(n log n) via the
    /// canonical path ordering.
    pub fn compute(local: &LibraryCatalog, remote: &LibraryCatalog) -> Self {
        let mut delta = CatalogDelta::default();
        for (path, l) in &local.entries {
            match remote.entries.get(path) {
                Some(r) => {
                    if l.content_hash == r.content_hash {
                        delta.identical += 1;
                    } else {
                        delta.diverged.push((l.clone(), r.clone()));
                    }
                }
                None => delta.push.push(l.clone()),
            }
        }
        for (path, r) in &remote.entries {
            if !local.entries.contains_key(path) {
                delta.pull.push(r.clone());
            }
        }
        delta
    }

    pub fn is_empty(&self) -> bool {
        self.pull.is_empty() && self.push.is_empty() && self.diverged.is_empty()
    }
}

// ─────────────────────────────────────────────────────────── policy

/// How diverged files (both sides hold a DIFFERENT version) resolve.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConflictPolicy {
    /// Surface the divergence, move nothing.
    SkipDiverged,
    /// mtime arbitrates; an mtime tie with diverged hashes is skipped.
    NewerWins,
    /// Pull the peer's copy to `<path>.peer`, never overwrite; the
    /// peer's mirrored session does the symmetric pull on its side.
    KeepBoth,
}

/// Bi-directional pull/push permission policies.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncPolicy {
    /// Remote → local transfers allowed at all.
    pub allow_pull: bool,
    /// Local → remote transfers allowed at all.
    pub allow_push: bool,
    pub conflict: ConflictPolicy,
    /// Courtesy cap on total push bytes per session (`None` = no cap) —
    /// a phone on metered Wi-Fi hot-spot protects itself.
    pub max_push_bytes: Option<u64>,
}

impl Default for SyncPolicy {
    fn default() -> Self {
        SyncPolicy {
            allow_pull: true,
            allow_push: true,
            conflict: ConflictPolicy::NewerWins,
            max_push_bytes: None,
        }
    }
}

/// Why a planned file did not become a transfer job.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SkipReason {
    /// The session policy denied this direction.
    PolicyDenied,
    /// SkipDiverged conflict policy.
    ConflictSkipped,
    /// NewerWins found an mtime tie with diverged hashes.
    SuspiciousTie,
    /// Push byte budget exhausted.
    PushBudgetExhausted,
}

impl fmt::Display for SkipReason {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SkipReason::PolicyDenied => write!(f, "direction denied by policy"),
            SkipReason::ConflictSkipped => write!(f, "diverged file skipped by policy"),
            SkipReason::SuspiciousTie => write!(f, "mtime tie on diverged content"),
            SkipReason::PushBudgetExhausted => write!(f, "push byte budget exhausted"),
        }
    }
}

/// One gated transfer job.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncJob {
    pub direction: Direction,
    /// Local-side destination path (pull) or local-side source (push).
    pub path: String,
    pub bytes_total: u64,
}

/// The policy-gated plan derived from a delta.
#[derive(Debug, Clone, Default)]
pub struct SyncPlan {
    pub jobs: Vec<SyncJob>,
    pub skipped: Vec<(String, SkipReason)>,
}

impl SyncPlan {
    /// Gates a delta through a policy into concrete jobs.
    pub fn from_delta(delta: &CatalogDelta, policy: &SyncPolicy) -> Self {
        let mut plan = SyncPlan::default();
        let mut push_budget_left = policy.max_push_bytes;

        let pull = |plan: &mut SyncPlan, entry: &CatalogEntry, path: String| {
            if !policy.allow_pull {
                plan.skipped.push((path, SkipReason::PolicyDenied));
                return;
            }
            plan.jobs.push(SyncJob {
                direction: Direction::Pull,
                path,
                bytes_total: entry.size,
            });
        };
        let mut push = |plan: &mut SyncPlan, entry: &CatalogEntry, path: String| {
            if !policy.allow_push {
                plan.skipped.push((path, SkipReason::PolicyDenied));
                return;
            }
            if let Some(budget) = push_budget_left {
                if entry.size > budget {
                    plan.skipped.push((path, SkipReason::PushBudgetExhausted));
                    return;
                }
                push_budget_left = Some(budget - entry.size);
            }
            plan.jobs.push(SyncJob {
                direction: Direction::Push,
                path,
                bytes_total: entry.size,
            });
        };

        // Remote-only files: pull candidates.
        for r in &delta.pull {
            pull(&mut plan, r, r.path.clone());
        }
        // Local-only files: push candidates.
        for l in &delta.push {
            push(&mut plan, l, l.path.clone());
        }
        // Diverged files: policy arbitrates.
        for (l, r) in &delta.diverged {
            match policy.conflict {
                ConflictPolicy::SkipDiverged => {
                    plan.skipped
                        .push((l.path.clone(), SkipReason::ConflictSkipped));
                }
                ConflictPolicy::NewerWins => match r.mtime_ms.cmp(&l.mtime_ms) {
                    std::cmp::Ordering::Greater => pull(&mut plan, r, l.path.clone()),
                    std::cmp::Ordering::Less => push(&mut plan, l, l.path.clone()),
                    // Same mtime, different bytes: do NOT guess.
                    std::cmp::Ordering::Equal => {
                        plan.skipped
                            .push((l.path.clone(), SkipReason::SuspiciousTie));
                    }
                },
                ConflictPolicy::KeepBoth => {
                    // Our copy stays; the peer's copy lands beside it.
                    let peer_path = format!("{}{}", l.path, KEEP_BOTH_SUFFIX);
                    pull(&mut plan, r, peer_path);
                    // No push: the peer's mirrored KeepBoth session pulls
                    // our copy to `<path>.peer` on ITS side. Symmetric,
                    // no overwrites, both versions survive on both sides.
                }
            }
        }
        plan
    }

    pub fn total_bytes(&self) -> u64 {
        self.jobs.iter().map(|j| j.bytes_total).sum()
    }
}

// ─────────────────────────────────────────────────────────── session

/// Session lifecycle state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionState {
    /// Plan computed; awaiting `start()`.
    Negotiating,
    /// Transfers in flight (accepting progress pushes).
    Syncing,
    /// Hold: no new progress is accepted (transfers may drain).
    Paused,
    /// All jobs done.
    Completed,
    /// User-cancelled.
    Cancelled,
}

/// Transfer-tracking snapshot for one file job.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileTransfer {
    pub direction: Direction,
    pub path: String,
    pub bytes_total: u64,
    pub bytes_done: u64,
    pub done: bool,
}

impl FileTransfer {
    /// Progress fraction in `[0, 1]`; empty files are instantly complete.
    pub fn progress(&self) -> f32 {
        if self.bytes_total == 0 {
            if self.done {
                1.0
            } else {
                0.0
            }
        } else {
            (self.bytes_done as f64 / self.bytes_total as f64).min(1.0) as f32
        }
    }
}

/// Terminal session report.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize)]
pub struct SyncSummary {
    pub files_planned: u32,
    pub bytes_planned: u64,
    pub files_pulled: u32,
    pub files_pushed: u32,
    pub bytes_pulled: u64,
    pub bytes_pushed: u64,
    pub files_skipped: u32,
    pub conflicts: u32,
}

/// Events surfaced to the JNI callback bridge (and the chaos suite).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SyncEvent {
    SessionStarted {
        peer: String,
        files_planned: u32,
        bytes_planned: u64,
    },
    CatalogsExchanged {
        local_entries: u32,
        remote_entries: u32,
        pull_candidates: u32,
        push_candidates: u32,
        diverged: u32,
        identical: u32,
    },
    FileTransferStarted {
        direction: Direction,
        path: String,
        bytes_total: u64,
    },
    /// One chunk landed: the "chunk completion callback" the app layer
    /// subscribes to for per-file progress UI.
    FileChunkCompleted {
        direction: Direction,
        path: String,
        bytes_done: u64,
        bytes_total: u64,
    },
    FileCompleted {
        direction: Direction,
        path: String,
        bytes_total: u64,
    },
    FileSkipped {
        path: String,
        reason: SkipReason,
    },
    SessionCompleted(SyncSummary),
    SessionCancelled(SyncSummary),
}

/// Two-way sync coordinator: plans, gates, and tracks. The transport
/// layer (mesh swarmer / JNI bridge) pushes byte-level progress in and
/// drains [`SyncEvent`]s out; this engine never touches payloads.
pub struct SyncSession {
    peer: String,
    policy: SyncPolicy,
    state: SessionState,
    files: BTreeMap<(Direction, String), FileTransfer>,
    order: Vec<(Direction, String)>,
    summary: SyncSummary,
    events: std::collections::VecDeque<SyncEvent>,
}

impl SyncSession {
    /// Negotiates a session from the two catalogs + a policy: computes
    /// the delta, gates the plan, and queues the opening events. The
    /// caller has NOT started transferring until `start()`.
    pub fn new(
        peer: &str,
        local: &LibraryCatalog,
        remote: &LibraryCatalog,
        policy: SyncPolicy,
    ) -> Self {
        let delta = CatalogDelta::compute(local, remote);
        let plan = SyncPlan::from_delta(&delta, &policy);
        let mut events = std::collections::VecDeque::new();
        events.push_back(SyncEvent::CatalogsExchanged {
            local_entries: local.entry_count(),
            remote_entries: remote.entry_count(),
            pull_candidates: delta.pull.len() as u32,
            push_candidates: delta.push.len() as u32,
            diverged: delta.diverged.len() as u32,
            identical: delta.identical,
        });
        for (path, reason) in &plan.skipped {
            events.push_back(SyncEvent::FileSkipped {
                path: path.clone(),
                reason: *reason,
            });
        }
        let mut files = BTreeMap::new();
        let mut order = Vec::with_capacity(plan.jobs.len());
        for job in &plan.jobs {
            let key = (job.direction, job.path.clone());
            files.insert(
                key.clone(),
                FileTransfer {
                    direction: job.direction,
                    path: job.path.clone(),
                    bytes_total: job.bytes_total,
                    bytes_done: 0,
                    done: false,
                },
            );
            order.push(key);
        }
        let summary = SyncSummary {
            files_planned: plan.jobs.len() as u32,
            bytes_planned: plan.total_bytes(),
            files_skipped: plan.skipped.len() as u32,
            conflicts: delta.diverged.len() as u32,
            ..SyncSummary::default()
        };
        SyncSession {
            peer: peer.to_string(),
            policy,
            state: SessionState::Negotiating,
            files,
            order,
            summary,
            events,
        }
    }

    pub fn peer(&self) -> &str {
        &self.peer
    }

    pub fn state(&self) -> SessionState {
        self.state
    }

    pub fn policy(&self) -> &SyncPolicy {
        &self.policy
    }

    /// Planned jobs in deterministic order (pull/push, then path).
    pub fn jobs(&self) -> Vec<&FileTransfer> {
        self.order
            .iter()
            .filter_map(|k| self.files.get(k))
            .collect()
    }

    /// Starts the session (idempotent: re-starting a live session is a
    /// no-op that returns false).
    pub fn start(&mut self) -> bool {
        match self.state {
            SessionState::Negotiating => {
                self.state = SessionState::Syncing;
                let ev = SyncEvent::SessionStarted {
                    peer: self.peer.clone(),
                    files_planned: self.summary.files_planned,
                    bytes_planned: self.summary.bytes_planned,
                };
                self.events.push_back(ev);
                for k in &self.order {
                    if let Some(f) = self.files.get(k) {
                        self.events.push_back(SyncEvent::FileTransferStarted {
                            direction: f.direction,
                            path: f.path.clone(),
                            bytes_total: f.bytes_total,
                        });
                    }
                }
                // Empty files complete at start.
                self.complete_empty_files();
                true
            }
            _ => false,
        }
    }

    fn complete_empty_files(&mut self) {
        let empty: Vec<(Direction, String)> = self
            .order
            .iter()
            .filter(|k| {
                self.files
                    .get(k)
                    .map(|f| f.bytes_total == 0 && !f.done)
                    .unwrap_or(false)
            })
            .cloned()
            .collect();
        for k in empty {
            self.finish_file(&k);
        }
    }

    /// Pauses a syncing session (idempotent).
    pub fn pause(&mut self) -> bool {
        if self.state == SessionState::Syncing {
            self.state = SessionState::Paused;
            true
        } else {
            false
        }
    }

    /// Resumes a paused session (idempotent).
    pub fn resume(&mut self) -> bool {
        if self.state == SessionState::Paused {
            self.state = SessionState::Syncing;
            self.complete_empty_files();
            true
        } else {
            false
        }
    }

    /// Cancels a live session; emits the terminal event with a partial
    /// summary. Terminal: a cancelled session accepts no more progress.
    pub fn cancel(&mut self) -> bool {
        match self.state {
            SessionState::Negotiating | SessionState::Syncing | SessionState::Paused => {
                self.state = SessionState::Cancelled;
                let ev = SyncEvent::SessionCancelled(self.summary);
                self.events.push_back(ev);
                true
            }
            _ => false,
        }
    }

    /// Transport progress push: `bytes_delta` chunk bytes just landed
    /// for `(direction, path)`. Rejected while paused/cancelled/unknown
    /// job — the transport layer must respect the intent states.
    pub fn on_bytes_transferred(
        &mut self,
        direction: Direction,
        path: &str,
        bytes_delta: u64,
    ) -> Result<(), SyncError> {
        if self.state == SessionState::Cancelled {
            return Err(SyncError::UnknownJob);
        }
        if self.state != SessionState::Syncing {
            return Err(SyncError::UnknownJob);
        }
        let key = (direction, path.to_string());
        let f = self.files.get_mut(&key).ok_or(SyncError::UnknownJob)?;
        if f.done {
            return Err(SyncError::UnknownJob);
        }
        f.bytes_done = f.bytes_done.saturating_add(bytes_delta).min(f.bytes_total);
        let ev = SyncEvent::FileChunkCompleted {
            direction,
            path: path.to_string(),
            bytes_done: f.bytes_done,
            bytes_total: f.bytes_total,
        };
        self.events.push_back(ev);
        Ok(())
    }

    /// Marks a job's payload fully delivered (the final chunk callback).
    pub fn on_file_completed(&mut self, direction: Direction, path: &str) -> Result<(), SyncError> {
        if self.state != SessionState::Syncing {
            return Err(SyncError::UnknownJob);
        }
        let key = (direction, path.to_string());
        if !self.files.contains_key(&key) {
            return Err(SyncError::UnknownJob);
        }
        self.finish_file(&key);
        Ok(())
    }

    fn finish_file(&mut self, key: &(Direction, String)) {
        if let Some(f) = self.files.get_mut(key) {
            if f.done {
                return;
            }
            f.bytes_done = f.bytes_total;
            f.done = true;
            match f.direction {
                Direction::Pull => {
                    self.summary.files_pulled += 1;
                    self.summary.bytes_pulled += f.bytes_total;
                }
                Direction::Push => {
                    self.summary.files_pushed += 1;
                    self.summary.bytes_pushed += f.bytes_total;
                }
            }
            let ev = SyncEvent::FileCompleted {
                direction: f.direction,
                path: f.path.clone(),
                bytes_total: f.bytes_total,
            };
            self.events.push_back(ev);
        }
        if self.files.values().all(|f| f.done) && self.state == SessionState::Syncing {
            self.state = SessionState::Completed;
            let ev = SyncEvent::SessionCompleted(self.summary);
            self.events.push_back(ev);
        }
    }

    /// Per-file progress fraction, `None` for unknown jobs.
    pub fn file_progress(&self, path: &str) -> Option<f32> {
        // Pull wins for ambiguous keys: a KeepBoth session never has the
        // same path under both directions with KeepBoth semantics.
        let key_pull = (Direction::Pull, path.to_string());
        if let Some(f) = self.files.get(&key_pull) {
            return Some(f.progress());
        }
        let key_push = (Direction::Push, path.to_string());
        self.files.get(&key_push).map(|f| f.progress())
    }

    /// Session-wide progress fraction over planned bytes.
    pub fn overall_progress(&self) -> f32 {
        let total: u64 = self.files.values().map(|f| f.bytes_total).sum();
        if total == 0 {
            return if self.state == SessionState::Completed {
                1.0
            } else {
                0.0
            };
        }
        let done: u64 = self.files.values().map(|f| f.bytes_done).sum();
        (done as f64 / total as f64).min(1.0) as f32
    }

    pub fn summary(&self) -> SyncSummary {
        self.summary
    }

    /// Drains queued events (the JNI bridge fans these out as callbacks).
    pub fn drain_events(&mut self) -> Vec<SyncEvent> {
        self.events.drain(..).collect()
    }
}

// ───────────────────────────────────────────────────────────── unit tests

#[cfg(test)]
mod tests {
    use super::*;

    fn hash_of(seed: u8) -> ChunkHash {
        [seed; 32]
    }

    fn entry(path: &str, size: u64, mtime: u64, seed: u8) -> CatalogEntry {
        CatalogEntry::new(path, size, mtime, hash_of(seed))
    }

    fn catalog(entries: Vec<CatalogEntry>) -> LibraryCatalog {
        LibraryCatalog::from_entries(entries).unwrap()
    }

    fn sample_local() -> LibraryCatalog {
        catalog(vec![
            entry("Autechre/Amber/9.flac", 1_000, 1_000, 1),
            entry("Boards of Canada/MHTRTC/6.flac", 2_000, 2_000, 2),
            entry("Aphex Twin/SAW II/3.flac", 3_000, 3_000, 3),
        ])
    }

    #[test]
    fn catalog_wire_roundtrip_and_fingerprint_stability() {
        let local = sample_local();
        let wire = local.to_wire();
        assert_eq!(LibraryCatalog::from_wire(&wire).unwrap(), local);
        // Trailing bytes ignored (forward compat).
        let mut padded = wire.clone();
        padded.extend_from_slice(&[0xEE]);
        assert_eq!(LibraryCatalog::from_wire(&padded).unwrap(), local);
        // Fingerprint is order-independent: same entries, any insertion
        // order → same digest (BTreeMap canonicalizes).
        let shuffled = catalog(vec![
            entry("Aphex Twin/SAW II/3.flac", 3_000, 3_000, 3),
            entry("Boards of Canada/MHTRTC/6.flac", 2_000, 2_000, 2),
            entry("Autechre/Amber/9.flac", 1_000, 1_000, 1),
        ]);
        assert_eq!(local.fingerprint(), shuffled.fingerprint());
        // Any content change flips it.
        let mutated = catalog(vec![
            entry("Autechre/Amber/9.flac", 1_000, 1_000, 9),
            entry("Boards of Canada/MHTRTC/6.flac", 2_000, 2_000, 2),
            entry("Aphex Twin/SAW II/3.flac", 3_000, 3_000, 3),
        ]);
        assert_ne!(local.fingerprint(), mutated.fingerprint());
    }

    #[test]
    fn catalog_rejects_path_traversal_and_hostile_wire() {
        for bad in [
            "/abs/path.flac",
            "../escape.flac",
            "a/../../b.flac",
            "a/./b.flac",
            "back\\slash.flac",
            "nul\0byte.flac",
            "",
        ] {
            assert!(!valid_rel_path(bad), "{bad:?} must be invalid");
            assert!(LibraryCatalog::from_entries(vec![entry(bad, 1, 1, 0)]).is_err());
        }
        // Valid forms pass.
        for good in ["a.flac", "Artist/Album/01 Track.flac", "中文/专辑/曲.aac"] {
            assert!(valid_rel_path(good), "{good:?} must be valid");
        }
        // Truncation sweep: every prefix of a valid wire blob errors.
        let wire = sample_local().to_wire();
        for cut in 0..wire.len() {
            assert!(LibraryCatalog::from_wire(&wire[..cut]).is_err());
        }
        // Junk magic.
        let mut junk = wire.clone();
        junk[0] = b'X';
        assert!(LibraryCatalog::from_wire(&junk).is_err());
        // Entry-count lie: declared 3, body holds 2.
        let mut lie = wire.clone();
        lie[4..8].copy_from_slice(&3u32.to_le_bytes());
        let two = catalog(vec![entry("a.flac", 1, 1, 1), entry("b.flac", 1, 1, 1)]).to_wire();
        let mut forged = Vec::new();
        forged.extend_from_slice(&two[..8]);
        forged.extend_from_slice(&two[8..]);
        // body says 2 entries but header says 2 — craft instead: swap header
        let _ = lie;
        let mut header_lie = two.clone();
        header_lie[4..8].copy_from_slice(&9u32.to_le_bytes()); // 9 declared
        assert!(LibraryCatalog::from_wire(&header_lie).is_err());
        let _ = forged;
    }

    #[test]
    fn delta_computes_pull_push_diverged_identical() {
        let local = sample_local();
        let remote = catalog(vec![
            // identical to local
            entry("Autechre/Amber/9.flac", 1_000, 1_000, 1),
            // diverged: same path, different bytes
            entry("Boards of Canada/MHTRTC/6.flac", 2_500, 2_000, 0x2A),
            // remote-only → pull candidate
            entry("Clark/Body Riddle/2.flac", 4_000, 4_000, 4),
        ]);
        // local-only "Aphex Twin" → push candidate.
        let d = CatalogDelta::compute(&local, &remote);
        assert_eq!(d.identical, 1);
        assert_eq!(d.pull.len(), 1);
        assert_eq!(d.pull[0].path, "Clark/Body Riddle/2.flac");
        assert_eq!(d.push.len(), 1);
        assert_eq!(d.push[0].path, "Aphex Twin/SAW II/3.flac");
        assert_eq!(d.diverged.len(), 1);
        assert_eq!(d.diverged[0].0.path, "Boards of Canada/MHTRTC/6.flac");
        assert!(!d.is_empty());
        // Identical catalogs: empty delta.
        assert!(CatalogDelta::compute(&local, &sample_local()).is_empty());
    }

    #[test]
    fn plan_gates_directions_budget_and_conflicts() {
        let local = sample_local();
        let remote = catalog(vec![
            // identical to local → zero bytes move
            entry("Autechre/Amber/9.flac", 1_000, 1_000, 1),
            // diverged: same path, different bytes, OLDER remote mtime
            entry("Boards of Canada/MHTRTC/6.flac", 2_500, 1_500, 0x2A),
            // remote-only → pull candidate
            entry("Clark/Body Riddle/2.flac", 4_000, 4_000, 4),
        ]);

        // NewerWins: local mtime 2000 > remote 1500 → PUSH the diverged
        // file; PULL the remote-only file; local-only Aphex Twin pushes;
        // identical Autechre moves nothing.
        let plan = SyncPlan::from_delta(
            &CatalogDelta::compute(&local, &remote),
            &SyncPolicy::default(),
        );
        assert_eq!(plan.jobs.len(), 3);
        assert!(plan
            .jobs
            .iter()
            .any(|j| j.direction == Direction::Pull && j.path == "Clark/Body Riddle/2.flac"));
        assert!(plan
            .jobs
            .iter()
            .any(|j| j.direction == Direction::Push && j.path == "Boards of Canada/MHTRTC/6.flac"));
        assert!(plan
            .jobs
            .iter()
            .any(|j| j.direction == Direction::Push && j.path == "Aphex Twin/SAW II/3.flac"));

        // Push denied: pull-only policy — every push candidate (Aphex +
        // the diverged Boards push) is skipped, only the pull survives.
        let pull_only = SyncPolicy {
            allow_push: false,
            ..SyncPolicy::default()
        };
        let plan = SyncPlan::from_delta(&CatalogDelta::compute(&local, &remote), &pull_only);
        assert!(plan.jobs.iter().all(|j| j.direction == Direction::Pull));
        assert_eq!(
            plan.skipped
                .iter()
                .filter(|(_, r)| *r == SkipReason::PolicyDenied)
                .count(),
            2
        );

        // Push byte budget 3000: Aphex Twin (3000 B) fits and drains it;
        // the Boards push (2500 B) then hits PushBudgetExhausted.
        let budget = SyncPolicy {
            max_push_bytes: Some(3_000),
            ..SyncPolicy::default()
        };
        let plan = SyncPlan::from_delta(&CatalogDelta::compute(&local, &remote), &budget);
        assert!(plan
            .jobs
            .iter()
            .any(|j| j.direction == Direction::Push && j.path == "Aphex Twin/SAW II/3.flac"));
        assert_eq!(
            plan.skipped
                .iter()
                .filter(|(_, r)| *r == SkipReason::PushBudgetExhausted)
                .count(),
            1
        );

        // Suspicious tie: same mtime, diverged bytes → skipped loudly.
        let tied = catalog(vec![entry(
            "Boards of Canada/MHTRTC/6.flac",
            2_500,
            2_000,
            0x2A,
        )]);
        let plan = SyncPlan::from_delta(
            &CatalogDelta::compute(&sample_local(), &tied),
            &SyncPolicy::default(),
        );
        assert_eq!(
            plan.skipped
                .iter()
                .filter(|(_, r)| *r == SkipReason::SuspiciousTie)
                .count(),
            1
        );

        // Full-mirror variant: every local file exists remotely, only
        // Boards diverges (mtime tie) — isolates the conflict policy.
        let tied_full = catalog(vec![
            entry("Autechre/Amber/9.flac", 1_000, 1_000, 1),
            entry("Boards of Canada/MHTRTC/6.flac", 2_500, 2_000, 0x2A),
            entry("Aphex Twin/SAW II/3.flac", 3_000, 3_000, 3),
        ]);

        // KeepBoth: peer copy pulled to `<path>.peer`, nothing pushed.
        let keep = SyncPolicy {
            conflict: ConflictPolicy::KeepBoth,
            ..SyncPolicy::default()
        };
        let plan = SyncPlan::from_delta(&CatalogDelta::compute(&sample_local(), &tied_full), &keep);
        assert_eq!(plan.jobs.len(), 1);
        assert_eq!(plan.jobs[0].direction, Direction::Pull);
        assert_eq!(plan.jobs[0].path, "Boards of Canada/MHTRTC/6.flac.peer");

        // SkipDiverged: no job for the diverged file at all.
        let skip = SyncPolicy {
            conflict: ConflictPolicy::SkipDiverged,
            ..SyncPolicy::default()
        };
        let plan = SyncPlan::from_delta(&CatalogDelta::compute(&sample_local(), &tied_full), &skip);
        assert!(plan.jobs.is_empty());
        assert_eq!(
            plan.skipped
                .iter()
                .filter(|(_, r)| *r == SkipReason::ConflictSkipped)
                .count(),
            1
        );
    }

    #[test]
    fn session_tracks_progress_pause_resume_cancel() {
        // Empty local library: the ONLY job is pulling the remote file,
        // which keeps the progress math single-dimensional.
        let local = LibraryCatalog::default();
        let remote = catalog(vec![entry("Clark/Body Riddle/2.flac", 1_000, 4_000, 4)]);
        let mut s = SyncSession::new("peer-A", &local, &remote, SyncPolicy::default());
        assert_eq!(s.state(), SessionState::Negotiating);

        // Progress before start is rejected.
        assert!(s
            .on_bytes_transferred(Direction::Pull, "Clark/Body Riddle/2.flac", 100)
            .is_err());

        assert!(s.start());
        assert_eq!(s.state(), SessionState::Syncing);
        assert_eq!(s.file_progress("Clark/Body Riddle/2.flac"), Some(0.0));

        // Chunk progress lands.
        s.on_bytes_transferred(Direction::Pull, "Clark/Body Riddle/2.flac", 400)
            .unwrap();
        assert_eq!(s.file_progress("Clark/Body Riddle/2.flac"), Some(0.4));
        // Clamped: over-delivery saturates at 1.0.
        s.on_bytes_transferred(Direction::Pull, "Clark/Body Riddle/2.flac", 9_999)
            .unwrap();
        assert_eq!(s.file_progress("Clark/Body Riddle/2.flac"), Some(1.0));

        // Pause blocks progress; resume reopens it.
        assert!(s.pause());
        assert!(s
            .on_bytes_transferred(Direction::Pull, "Clark/Body Riddle/2.flac", 1)
            .is_err());
        assert!(s.resume());

        s.on_file_completed(Direction::Pull, "Clark/Body Riddle/2.flac")
            .unwrap();
        assert_eq!(s.state(), SessionState::Completed);
        let sum = s.summary();
        assert_eq!(sum.files_pulled, 1);
        assert_eq!(sum.bytes_pulled, 1_000);
        assert_eq!(s.overall_progress(), 1.0);
        // Terminal: no further progress, restarts are no-ops.
        assert!(!s.start());
        assert!(!s.cancel());
        assert!(s
            .on_bytes_transferred(Direction::Pull, "Clark/Body Riddle/2.flac", 1)
            .is_err());
    }

    #[test]
    fn session_events_stream_the_full_lifecycle() {
        let local = LibraryCatalog::default();
        let remote = catalog(vec![
            entry("Clark/Body Riddle/2.flac", 1_000, 4_000, 4),
            entry("Clark/Empty Tag/0.flac", 0, 4_000, 5),
        ]);
        let mut s = SyncSession::new("peer-B", &local, &remote, SyncPolicy::default());
        assert!(s.start());

        // Empty file completes instantly at start.
        assert_eq!(s.file_progress("Clark/Empty Tag/0.flac"), Some(1.0));

        s.on_bytes_transferred(Direction::Pull, "Clark/Body Riddle/2.flac", 500)
            .unwrap();
        s.on_file_completed(Direction::Pull, "Clark/Body Riddle/2.flac")
            .unwrap();
        assert_eq!(s.state(), SessionState::Completed);

        let events = s.drain_events();
        // CatalogsExchanged → SessionStarted → Started* → (empty file
        // completes instantly) → Chunk → Completed → SessionCompleted.
        assert!(matches!(
            events[0],
            SyncEvent::CatalogsExchanged { identical: 0, .. }
        ));
        assert!(matches!(
            events[1],
            SyncEvent::SessionStarted {
                files_planned: 2,
                ..
            }
        ));
        assert!(events.iter().any(|e| matches!(
            e,
            SyncEvent::FileTransferStarted { path, .. } if path == "Clark/Empty Tag/0.flac"
        )));
        assert!(events.iter().any(|e| matches!(
            e,
            SyncEvent::FileChunkCompleted {
                bytes_done: 500,
                bytes_total: 1_000,
                ..
            }
        )));
        assert_eq!(
            events
                .iter()
                .filter(|e| matches!(e, SyncEvent::FileCompleted { .. }))
                .count(),
            2
        );
        assert!(matches!(
            events.last(),
            Some(SyncEvent::SessionCompleted(_))
        ));
        // Drain is destructive.
        assert!(s.drain_events().is_empty());
    }

    #[test]
    fn cancelled_session_emits_partial_summary() {
        let local = sample_local();
        let remote = catalog(vec![
            entry("Clark/Body Riddle/2.flac", 1_000, 4_000, 4),
            entry("Clark/Second/3.flac", 1_000, 4_000, 6),
        ]);
        let mut s = SyncSession::new("peer-C", &local, &remote, SyncPolicy::default());
        s.start();
        s.on_bytes_transferred(Direction::Pull, "Clark/Body Riddle/2.flac", 250)
            .unwrap();
        assert!(s.cancel());
        assert_eq!(s.state(), SessionState::Cancelled);
        let events = s.drain_events();
        assert!(matches!(
            events.last(),
            Some(SyncEvent::SessionCancelled(_))
        ));
    }

    #[test]
    fn fingerprint_fast_path_skips_exchange_entirely() {
        // The 32-byte fast path: equal fingerprints → the peers skip the
        // catalog body exchange; unequal → full delta pass runs.
        let a = sample_local();
        let b = sample_local();
        assert_eq!(a.fingerprint(), b.fingerprint());
        assert!(CatalogDelta::compute(&a, &b).is_empty());
    }
}
