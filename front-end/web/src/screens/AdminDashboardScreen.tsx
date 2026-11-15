/**
 * screens/AdminDashboardScreen.tsx — W-W: the Admin Portal port
 * (ui/screens/AdminDashboardScreen.kt).
 *
 * Seven tabs over the Supabase admin surface (lib/adminStore.ts): Telemetry,
 * Top Songs, Edge Mesh, Users, Jam Rooms, Comments, Broadcasts. Read RPCs
 * ride PostgREST exactly like the APK's SupabaseAdminClient; the mutating
 * actions (role toggle, room terminate, comment delete) dispatch through
 * the authenticated Edge Functions (SEC-02 — JWT-verified, admin-claimed
 * server-side). Auto-refresh every 30 s (silent) so the dashboard can
 * never go permanently stale — the APK's rule.
 *
 * PORT FIX (noted in the PR): the APK's `when (selectedTab)` maps index 1
 * to BOTH the Top-Songs panel and (dead code) the Edge-Mesh panel, so the
 * tab labels shifted one slot from their content from "Edge Mesh" onward
 * and the last label rendered nothing. The port maps every label to its
 * intended panel — seven tabs, seven panels.
 *
 * Honesty: a non-admin signed-in user sees the RPC error banners (the
 * server refuses); a signed-out user sees the sign-in requirement. No
 * mocked numbers anywhere.
 */

import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { Sheet } from '../components/Sheet';
import { useAdminStore } from '../lib/adminStore';
import { useSessionStore } from '../lib/sessionStore';
import { toast } from '../lib/toastStore';
import type { AdminUserProfile } from '../lib/supabase';

const TABS = ['Telemetry', 'Top Songs', 'Edge Mesh', 'Users', 'Jam Rooms', 'Comments', 'Broadcasts'] as const;

function fmtListening(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const hrs = Math.floor(mins / 60);
  return hrs > 0 ? `${hrs}h ${mins % 60}m` : `${mins} mins`;
}

export function AdminDashboardScreen(): JSX.Element {
  const navigate = useNavigate();
  const session = useSessionStore((s) => s.session);

  const phase = useAdminStore((s) => s.phase);
  const error = useAdminStore((s) => s.error);
  const telemetry = useAdminStore((s) => s.telemetry);
  const refreshAll = useAdminStore((s) => s.refreshAll);

  const [tab, setTab] = useState(0);

  useEffect(() => {
    if (session !== null) void refreshAll();
  }, [session, refreshAll]);

  // The 30s silent auto-refresh (the APK's never-stale rule).
  useEffect(() => {
    if (session === null) return;
    const id = window.setInterval(() => void refreshAll(true), 30_000);
    return () => window.clearInterval(id);
  }, [session, refreshAll]);

  const rpcFailed = telemetry !== null && telemetry.serverStatus.startsWith('RPC ERROR');

  return (
    <div className="admin" role="main">
      <header className="screen-topbar">
        <button type="button" className="screen-topbar__back" onClick={() => navigate(-1)} aria-label="Back">
          <Icon name="arrowBack" size={24} />
        </button>
        <h1 className="screen-topbar__title">Admin Portal</h1>
        <span className="admin__prodbadge">PROD ROOT</span>
        <button
          type="button"
          className="admin__refresh"
          onClick={() => void refreshAll()}
          aria-label="Refresh dashboard"
          disabled={phase === 'loading'}
        >
          <Icon name="refresh" size={20} />
        </button>
      </header>

      <div className="admin__sub">
        <p className="admin__email">{session?.user.email ?? 'not signed in'}</p>
        <p className="admin__engine">{telemetry?.engineMode ?? 'PostgreSQL 15 + pgvector'}</p>
      </div>

      <div className="admin__tabs" role="tablist" aria-label="Admin sections">
        {TABS.map((t, i) => (
          <button
            key={t}
            type="button"
            role="tab"
            aria-selected={tab === i}
            className={`admin__tab ${tab === i ? 'admin__tab--on' : ''}`}
            onClick={() => setTab(i)}
          >
            {t}
          </button>
        ))}
      </div>

      <div className="admin__body">
        {session === null ? (
          <AdminSigninRequired />
        ) : (
          <>
            {phase === 'loading' && telemetry === null && (
              <div className="admin__loading" role="status">
                <span className="spinner" aria-hidden="true" />
                <span className="sr-only">Loading admin telemetry…</span>
              </div>
            )}
            {phase === 'error' && telemetry === null && (
              <p className="admin__rpcerror" role="alert">
                ⚠ {error}
              </p>
            )}
            {rpcFailed && (
              <p className="admin__rpcerror" role="alert">
                ⚠ Dashboard RPC failed ({telemetry.serverStatus.replace('RPC ERROR — ', '')}) — apply the
                stats_overhaul migration &amp; verify is_admin.
              </p>
            )}

            {tab === 0 && <TelemetryTab />}
            {tab === 1 && <TopSongsTab />}
            {tab === 2 && <EdgeMeshTab />}
            {tab === 3 && <UsersTab />}
            {tab === 4 && <JamRoomsTab />}
            {tab === 5 && <CommentsTab />}
            {tab === 6 && <BroadcastsTab />}
          </>
        )}
      </div>

      <UserTelemetrySheetHost />
    </div>
  );
}

function AdminSigninRequired(): JSX.Element {
  const configured = useSessionStore((s) => s.configured);
  const signInWithGoogle = useSessionStore((s) => s.signInWithGoogle);
  return (
    <div className="admin__signin">
      <Icon name="adminPanel" size={44} />
      <p className="admin__signin-title">Admin telemetry requires a signed-in session</p>
      <p className="admin__signin-sub">
        The admin RPCs are RLS-fenced: the server verifies the caller&apos;s admin claim. Sign in with the
        admin account to view this portal.
      </p>
      {configured ? (
        <button type="button" className="btn button--primary" onClick={() => signInWithGoogle()}>
          Sign In with Google
        </button>
      ) : (
        <p className="muted">Supabase credentials not baked — this build runs anonymous (dev).</p>
      )}
    </div>
  );
}

// ---- Tab 0: Telemetry -------------------------------------------------------

function MetricCard({
  title,
  value,
  subtitle,
  icon,
}: {
  title: string;
  value: string;
  subtitle: string;
  icon: Parameters<typeof Icon>[0]['name'];
}): JSX.Element {
  return (
    <div className="admin__metric" role="group" aria-label={title}>
      <div className="admin__metric-head">
        <span className="admin__metric-title">{title}</span>
        <Icon name={icon} size={20} className="admin__metric-icon" />
      </div>
      <p className="admin__metric-value">{value}</p>
      <p className="admin__metric-sub">{subtitle}</p>
    </div>
  );
}

function TelemetryTab(): JSX.Element {
  const telemetry = useAdminStore((s) => s.telemetry);
  if (telemetry === null) return <></>;
  return (
    <div className="admin__stack">
      <div className="admin__healthcard">
        <span className="admin__dot" aria-hidden="true" />
        <div>
          <p className="admin__health-title">Database &amp; Vector Engine</p>
          <p className="admin__health-sub">{telemetry.engineMode}</p>
        </div>
        <span className="admin__health-latency">{telemetry.latencyMs} ms</span>
      </div>
      <div className="admin__metricgrid">
        <MetricCard title="Users" value={String(telemetry.totalUsers)} subtitle={`${telemetry.dau24h} Active 24h`} icon="people" />
        <MetricCard title="Jam Rooms" value={String(telemetry.activeJamSessions)} subtitle="Live Rooms" icon="radio" />
        <MetricCard title="Cloud Songs" value={String(telemetry.totalTracks)} subtitle="Indexed Vectors" icon="musicNote" />
        <MetricCard title="Playlists" value={String(telemetry.totalPlaylists)} subtitle="Public & Collab" icon="queueMusic" />
        <MetricCard title="Comments" value={String(telemetry.totalComments)} subtitle="Reactions" icon="chat" />
        <MetricCard title="Total Plays" value={String(telemetry.totalPlays)} subtitle="Streams" icon="playArrow" />
      </div>
    </div>
  );
}

// ---- Tab 1: Top Songs --------------------------------------------------------

function TopSongsTab(): JSX.Element {
  const topTracks = useAdminStore((s) => s.topTracks);
  const topTracksError = useAdminStore((s) => s.topTracksError);
  const loadTopTracks = useAdminStore((s) => s.loadTopTracks);

  useEffect(() => {
    void loadTopTracks();
  }, [loadTopTracks]);

  if (topTracksError !== null) {
    return (
      <p className="admin__rpcerror" role="alert">
        ⚠ {topTracksError}
        <br />
        Apply the stats_overhaul migration to enable this panel.
      </p>
    );
  }
  if (topTracks === null) {
    return (
      <div className="admin__loading" role="status">
        <span className="spinner" aria-hidden="true" />
        <span className="sr-only">Loading top songs…</span>
      </div>
    );
  }
  if (topTracks.length === 0) {
    return (
      <p className="admin__empty">
        No plays recorded yet. Counts stream in from updated clients as they play.
      </p>
    );
  }
  return (
    <div className="admin__stack">
      <p className="admin__sectiontitle">GLOBAL TOP SONGS · ALL LISTENERS</p>
      <ol className="admin__toplist">
        {topTracks.map((t, i) => (
          <li className={`admin__toprow ${i < 3 ? 'admin__toprow--lead' : ''}`} key={`${t.trackSig}_${i}`}>
            <span className="admin__toprank">#{i + 1}</span>
            {t.coverUrl !== null ? (
              <img className="admin__topart" src={t.coverUrl} alt="" loading="lazy" />
            ) : (
              <span className="admin__topart admin__topart--empty" aria-hidden="true">
                ♪
              </span>
            )}
            <span className="admin__toptitles">
              <span className="admin__toptitle">{t.title}</span>
              <span className="admin__topsub">
                {t.artist} · {t.listeners} listener{t.listeners === 1 ? '' : 's'}
              </span>
            </span>
            <span className="admin__topstats">
              <span className="admin__topplays">{t.plays}</span>
              <span className="admin__topmins">{t.minutes} min</span>
            </span>
          </li>
        ))}
      </ol>
    </div>
  );
}

// ---- Tab 2: Edge Mesh --------------------------------------------------------

function EdgeMeshTab(): JSX.Element {
  const edgeStats = useAdminStore((s) => s.edgeStats);
  if (edgeStats === null) {
    return <p className="admin__empty">Edge mesh stats unavailable (RPC not deployed or not admin).</p>;
  }
  return (
    <div className="admin__stack">
      <div className="admin__metricgrid">
        <MetricCard title="Active Nodes" value={String(edgeStats.activeNodesCount)} subtitle="Computing Right Now" icon="memory" />
        <MetricCard
          title="Mesh Pipeline"
          value={String(edgeStats.completedTasksCount)}
          subtitle={`of ${edgeStats.totalTasksCount} Verified`}
          icon="checkCircle"
        />
        <MetricCard
          title="Bandwidth Inverted"
          value={`${edgeStats.totalBandwidthSavedMb.toFixed(1)} MB`}
          subtitle="Saved via Local-First Edge Caches ($0 Cloud Bill)"
          icon="speed"
        />
      </div>
      <p className="admin__sectiontitle">Live Edge Nodes ({edgeStats.activeNodes.length})</p>
      {edgeStats.activeNodes.length === 0 ? (
        <p className="admin__empty">No edge nodes computing right now.</p>
      ) : (
        <ul className="admin__nodelist">
          {edgeStats.activeNodes.map((n) => (
            <li className="admin__node" key={n.deviceId}>
              <span className="admin__node-head">
                <span className="admin__node-name">{n.displayName}</span>
                <span className={`admin__node-status admin__node-status--${n.status.toLowerCase()}`}>
                  {n.status}
                </span>
              </span>
              <span className="admin__node-sub">
                {n.currentTrackTitle !== '' ? `🎵 ${n.currentTrackTitle} · ` : ''}
                {n.totalContributions} contributions · {n.bandwidthSavedMb.toFixed(1)} MB saved
              </span>
            </li>
          ))}
        </ul>
      )}
      {edgeStats.topContributors.length > 0 && (
        <>
          <p className="admin__sectiontitle">Top Contributors</p>
          <ul className="admin__nodelist">
            {edgeStats.topContributors.map((c) => (
              <li className="admin__node" key={c.userId}>
                <span className="admin__node-head">
                  <span className="admin__node-name">{c.displayName}</span>
                  <span className="admin__node-sub">{c.totalContributions} contributions</span>
                </span>
                <span className="admin__node-sub">{c.bandwidthSavedMb.toFixed(1)} MB saved</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}

// ---- Tab 3: Users -------------------------------------------------------------

function UsersTab(): JSX.Element {
  const telemetry = useAdminStore((s) => s.telemetry);
  const selectUser = useAdminStore((s) => s.selectUser);
  const session = useSessionStore((s) => s.session);
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    const list: AdminUserProfile[] = telemetry?.userList ?? [];
    if (query.trim() === '') return list;
    const q = query.toLowerCase();
    return list.filter(
      (u: AdminUserProfile) => u.displayName.toLowerCase().includes(q) || u.email.toLowerCase().includes(q),
    );
  }, [telemetry?.userList, query]);

  if (telemetry === null) return <></>;
  return (
    <div className="admin__stack">
      <input
        className="admin__search"
        type="search"
        placeholder="Search users by name or email…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        aria-label="Search users"
      />
      <p className="admin__sectiontitle">Registered Profiles ({filtered.length})</p>
      {filtered.length === 0 ? (
        <p className="admin__empty">No profiles match.</p>
      ) : (
        <ul className="admin__userlist">
          {filtered.map((u) => (
            <li key={u.id}>
              <button type="button" className="admin__user" onClick={() => selectUser(u)}>
                {u.avatarUrl !== '' ? (
                  <img className="admin__avatar" src={u.avatarUrl} alt="" loading="lazy" />
                ) : (
                  <span className="admin__avatar admin__avatar--empty" aria-hidden="true">
                    {u.displayName.charAt(0).toUpperCase()}
                  </span>
                )}
                <span className="admin__usertitles">
                  <span className="admin__username">
                    {u.displayName}
                    {u.isAdmin && <span className="admin__badge">ADMIN</span>}
                  </span>
                  <span className="admin__usersub">{u.email}</span>
                  <span className="admin__usersub2">
                    {u.totalPlays} plays · {fmtListening(u.listeningSeconds)}
                    {u.favoriteGenre !== '' && u.favoriteGenre !== 'All' ? ` · ${u.favoriteGenre}` : ''}
                  </span>
                  <span className="admin__usertop">
                    🎧 {u.topTrack !== '' ? u.topTrack : 'No tracks played yet'}
                  </span>
                </span>
                {/* SEC-09 carve-out: never toggle your own role. */}
                {u.id !== session?.user.id && <span className="admin__userarrow">›</span>}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function UserTelemetrySheetHost(): JSX.Element | null {
  const selectedUser = useAdminStore((s) => s.selectedUser);
  const selectUser = useAdminStore((s) => s.selectUser);
  const toggleUserRole = useAdminStore((s) => s.toggleUserRole);
  const session = useSessionStore((s) => s.session);

  if (selectedUser === null) return null;
  const u = selectedUser;
  const isSelf = u.id === session?.user.id;

  return (
    <Sheet open onDismiss={() => selectUser(null)} title={`User telemetry — ${u.displayName}`} heightFraction={0.8}>
      <div className="admin__sheet">
        {u.avatarUrl !== '' ? (
          <img className="admin__sheetavatar" src={u.avatarUrl} alt="" />
        ) : (
          <span className="admin__sheetavatar admin__sheetavatar--empty" aria-hidden="true">
            {u.displayName.charAt(0).toUpperCase()}
          </span>
        )}
        <p className="admin__sheetname">
          {u.displayName} <span className={`admin__badge ${u.isAdmin ? 'admin__badge--big' : ''}`}>{u.isAdmin ? 'ADMIN 🛡️' : 'USER'}</span>
        </p>
        <p className="admin__sheetemail">{u.email}</p>
        <div className="admin__metricgrid">
          <MetricCard title="Listening Time" value={fmtListening(u.listeningSeconds)} subtitle="This listener" icon="schedule" />
          <MetricCard title="Total Streams" value={`${u.totalPlays} plays`} subtitle="All-time" icon="playArrow" />
        </div>
        <div className="admin__dna">
          <p className="admin__dna-title">
            AUDIO DNA &amp; AI PERSONA <span className="admin__dna-chip">⚡ GLM-4</span>
          </p>
          <div className="admin__dnarow">
            <span>Persona Archetype</span>
            <span className="admin__dnavalue">{u.bio !== '' ? u.bio : 'Sonic Explorer 🎧'}</span>
          </div>
          <div className="admin__dnarow">
            <span>Top Genre Signature</span>
            <span className="admin__dnavalue">{u.favoriteGenre !== '' ? u.favoriteGenre : 'All Genres'}</span>
          </div>
          {u.topTrack !== '' && (
            <div className="admin__dnarow">
              <span>Top Listened Song</span>
              <span className="admin__dnavalue">{u.topTrack}</span>
            </div>
          )}
        </div>
        {!isSelf && (
          <button
            type="button"
            className={`admin__roletoggle ${u.isAdmin ? 'admin__roletoggle--revoke' : ''}`}
            onClick={() => {
              void toggleUserRole(u).then((ok) => {
                toast(ok ? `Updated role for ${u.displayName}` : 'Failed to update role', ok ? 'info' : 'error');
              });
            }}
          >
            {u.isAdmin ? 'Revoke Admin Privileges' : 'Promote to Admin'}
          </button>
        )}
      </div>
    </Sheet>
  );
}

// ---- Tab 4: Jam Rooms ---------------------------------------------------------

function JamRoomsTab(): JSX.Element {
  const jamSessions = useAdminStore((s) => s.jamSessions);
  const terminateJam = useAdminStore((s) => s.terminateJam);
  return (
    <div className="admin__stack">
      <p className="admin__sectiontitle">Active Listening Rooms ({jamSessions.length})</p>
      {jamSessions.length === 0 ? (
        <div className="admin__empty--block">
          <Icon name="radio" size={44} className="admin__emptyicon" />
          <p>No active Jam rooms currently open</p>
        </div>
      ) : (
        <ul className="admin__jamlist">
          {jamSessions.map((s) => (
            <li className="admin__jam" key={s.id}>
              <div className="admin__jam-head">
                <button
                  type="button"
                  className="admin__pin"
                  onClick={() => {
                    void navigator.clipboard?.writeText(s.sessionCode);
                    toast(`PIN ${s.sessionCode} copied!`);
                  }}
                  title="Copy room PIN"
                >
                  {s.sessionCode}
                </button>
                <span className="admin__jam-host">{s.hostName}</span>
                <button
                  type="button"
                  className="admin__jam-terminate"
                  onClick={() => {
                    void terminateJam(s.id).then((ok) => {
                      toast(ok ? 'Jam room closed' : 'Failed to terminate room', ok ? 'info' : 'error');
                    });
                  }}
                  aria-label={`Terminate room ${s.sessionCode}`}
                >
                  <Icon name="delete" size={18} />
                </button>
              </div>
              <p className="admin__jam-track">
                🎵 {s.currentTrackTitle} {s.currentTrackArtist !== '' ? `• ${s.currentTrackArtist}` : ''}
              </p>
              <p className="admin__jam-sub">
                👥 {s.participantCount} connected listener{s.participantCount === 1 ? '' : 's'} ·{' '}
                {s.isPlaying ? '▶ Playing' : '⏸ Paused'}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ---- Tab 5: Comments ----------------------------------------------------------

function CommentsTab(): JSX.Element {
  const recentComments = useAdminStore((s) => s.recentComments);
  const deleteComment = useAdminStore((s) => s.deleteComment);
  return (
    <div className="admin__stack">
      <p className="admin__sectiontitle">Live Song Comments ({recentComments.length})</p>
      {recentComments.length === 0 ? (
        <p className="admin__empty">No recent comments to moderate.</p>
      ) : (
        <ul className="admin__commentlist">
          {recentComments.map((c) => (
            <li className="admin__comment" key={c.id}>
              <div className="admin__comment-body">
                <p className="admin__comment-head">
                  <span className="admin__comment-user">{c.userName}</span>{' '}
                  <span className="admin__comment-on">on {c.trackTitle}</span>{' '}
                  <span className="admin__comment-at">@ {Math.floor(c.timestampMs / 1000)}s</span>
                </p>
                <p className="admin__comment-text">{c.commentText}</p>
              </div>
              <button
                type="button"
                className="admin__comment-delete"
                onClick={() => {
                  void deleteComment(c.id).then((ok) => {
                    toast(ok ? 'Comment removed' : 'Failed to delete comment', ok ? 'info' : 'error');
                  });
                }}
                aria-label="Delete comment"
              >
                <Icon name="delete" size={18} />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// ---- Tab 6: Broadcasts --------------------------------------------------------

function BroadcastsTab(): JSX.Element {
  const broadcast = useAdminStore((s) => s.broadcast);
  const [message, setMessage] = useState('');
  const [sending, setSending] = useState(false);
  return (
    <div className="admin__stack">
      <div className="admin__broadcastcard">
        <p className="admin__sectiontitle">New Global Announcement</p>
        <p className="admin__broadcast-sub">
          Broadcast an instant announcement banner to all connected Streamify apps worldwide.
        </p>
        <textarea
          className="admin__broadcast-input"
          placeholder="Enter announcement message…"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          rows={3}
          aria-label="Announcement message"
        />
        <button
          type="button"
          className="btn button--primary admin__broadcast-send"
          disabled={message.trim() === '' || sending}
          onClick={() => {
            setSending(true);
            void broadcast(message.trim()).then((ok) => {
              setSending(false);
              if (ok) {
                setMessage('');
                toast('Broadcast sent successfully!');
              } else {
                toast('Failed to post broadcast', 'error');
              }
            });
          }}
        >
          <Icon name="campaign" size={18} />
          {sending ? 'Publishing…' : 'Publish Global Banner'}
        </button>
      </div>
    </div>
  );
}
