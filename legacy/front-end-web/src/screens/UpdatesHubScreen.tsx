/**
 * screens/UpdatesHubScreen.tsx — P3a: the updates feed.
 *
 * The Android UpdatesHubScreen port on its own near-black (#0A0A0F):
 * the green bell header with the release count, loading / empty
 * states, and the 16px-radius release rows with cover plates and the
 * OUT NOW chip. Web data source (§9.4 bucket B — "site version + APK
 * link"): THIS repo's GitHub releases — the CI/CD builds the Android
 * settings screen links to, surfaced as the web's release feed. The
 * followed-artists watcher (FollowGraphStore + ReleaseWatcherApi)
 * lands with the Supabase follow-graph port in P3b.
 */

import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { APP_VERSION, RELEASES_URL, fetchGithubReleases, type GithubRelease } from '../version';

function fmtDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'just now';
  const diffH = (Date.now() - d.getTime()) / 3_600_000;
  if (diffH < 1) return 'just now';
  if (diffH < 24) return `${Math.floor(diffH)}h ago`;
  if (diffH < 24 * 30) return `${Math.floor(diffH / 24)}d ago`;
  return d.toLocaleDateString();
}

export function UpdatesHubScreen(): JSX.Element {
  const navigate = useNavigate();
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading');
  const [releases, setReleases] = useState<GithubRelease[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void fetchGithubReleases()
      .then((rows) => {
        if (cancelled) return;
        setReleases(rows);
        setPhase('ready');
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : String(err));
        setPhase('error');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="updates" role="main">
      <header className="updates__header">
        <button
          type="button"
          className="screen-topbar__back"
          onClick={() => navigate(-1)}
          aria-label="Back"
        >
          <Icon name="arrowBack" size={24} />
        </button>
        <Icon name="notifications" size={20} className="updates__bell" />
        <h1 className="updates__title">Your Updates</h1>
        <span className="updates__count">
          {phase === 'ready' ? `${releases.length} releases` : '…'}
        </span>
      </header>

      <div className="updates__body">
        {phase === 'loading' && (
          <div className="updates__loading" role="status">
            <span className="updates__spinner" aria-label="loading" />
          </div>
        )}

        {phase === 'error' && (
          <div className="updates__empty">
            <Icon name="album" size={48} className="updates__empty-icon" />
            <p className="updates__empty-title">Couldn&apos;t reach the release feed</p>
            <p className="updates__empty-sub">{error ?? 'GitHub API unavailable'} — retry in a moment.</p>
          </div>
        )}

        {phase === 'ready' && releases.length === 0 && (
          <div className="updates__empty">
            <Icon name="album" size={48} className="updates__empty-icon" />
            <p className="updates__empty-title">No updates yet</p>
            <p className="updates__empty-sub">
              CI/CD release builds land here as they publish. You are on web build v{APP_VERSION}.
            </p>
          </div>
        )}

        {phase === 'ready' && releases.length > 0 && (
          <ul className="updates__list">
            {releases.map((r) => (
              <li key={r.tagName}>
                <a className="updates__row" href={r.htmlUrl} target="_blank" rel="noreferrer">
                  <span className="updates__cover" aria-hidden="true">
                    <Icon name="album" size={24} />
                  </span>
                  <span className="updates__titles">
                    <span className="updates__row-title">{r.name ?? r.tagName}</span>
                    <span className="updates__row-artist">
                      {r.tagName} · published {fmtDate(r.publishedAt)} · {r.author}
                      {r.prerelease ? ' · pre-release' : ''}
                    </span>
                    <span className="updates__chip">OUT NOW</span>
                  </span>
                  <span className="updates__open">Open</span>
                </a>
              </li>
            ))}
          </ul>
        )}

        <a className="updates__apk" href={RELEASES_URL} target="_blank" rel="noreferrer">
          <Icon name="download" size={18} />
          <span>All releases &amp; APK builds</span>
        </a>
      </div>
    </div>
  );
}
