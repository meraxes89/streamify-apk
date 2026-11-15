/**
 * screens/SettingsScreen.tsx — P3a: the settings surface.
 *
 * The Android SettingsScreen port: the red uppercase SectionHeaders,
 * 16px-radius cards on #181818, back-arrow top bar, and the section
 * order (Accounts → Cloud → Audio Quality → DSP → Sleep Timer →
 * Storage → Backup → Danger Zone → Updates → About). What is REAL on
 * the web today is wired (Google auth, bitrate + DSP prefs, sleep
 * timer, cache clearing, library export, GitHub release checks, the
 * nuclear purge); the pieces that need engine/OPFS work say so
 * honestly instead of faking.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { useSessionStore } from '../lib/sessionStore';
import { usePlayerStore } from '../lib/playerStore';
import { toast } from '../lib/toastStore';
import { APP_BUILD, APP_EDITION, APP_VERSION, RELEASES_URL, fetchGithubReleases } from '../version';

// -- prefs -------------------------------------------------------------------

interface Prefs {
  bitrate: 128 | 192 | 256 | 320;
  loudness: boolean;
  lufs: number;
  gapless: boolean;
  crossfadeSec: number;
  mono: boolean;
  balance: number;
  truePeak: boolean;
}

const DEFAULT_PREFS: Prefs = {
  bitrate: 320,
  loudness: true,
  lufs: -14,
  gapless: true,
  crossfadeSec: 0,
  mono: false,
  balance: 0,
  truePeak: true,
};

const PREFS_KEY = 'streamify.settings.v1';

function readPrefs(): Prefs {
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    if (raw === null) return DEFAULT_PREFS;
    return { ...DEFAULT_PREFS, ...(JSON.parse(raw) as Partial<Prefs>) };
  } catch {
    return DEFAULT_PREFS;
  }
}

const BITRATES: Array<{ value: 128 | 192 | 256 | 320; label: string }> = [
  { value: 128, label: '128 Low' },
  { value: 192, label: '192 Normal' },
  { value: 256, label: '256 High' },
  { value: 320, label: '320 Extreme (Default)' },
];

export function SettingsScreen(): JSX.Element {
  const navigate = useNavigate();
  const session = useSessionStore((s) => s.session);
  const configured = useSessionStore((s) => s.configured);
  const signInWithGoogle = useSessionStore((s) => s.signInWithGoogle);
  const signOut = useSessionStore((s) => s.signOut);
  const likes = useSessionStore((s) => s.likes);
  const send = usePlayerStore((s) => s.send);
  const snapshot = usePlayerStore((s) => s.snapshot);

  const [prefs, setPrefs] = useState<Prefs>(readPrefs);
  const [cacheText, setCacheText] = useState('—');
  const [sleepMin, setSleepMin] = useState<number | 'end' | null>(null);
  const endOfTrackRef = useRef(false);
  const prevTrackRef = useRef<string | null>(null);
  const [nukeConfirm, setNukeConfirm] = useState(false);
  const [updateState, setUpdateState] = useState<'idle' | 'checking' | 'latest' | 'available' | 'error'>('idle');
  const [latestRelease, setLatestRelease] = useState<string | null>(null);
  const sleepTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const setPref = useCallback(<K extends keyof Prefs>(key: K, value: Prefs[K]) => {
    setPrefs((prev) => {
      const next = { ...prev, [key]: value };
      try {
        localStorage.setItem(PREFS_KEY, JSON.stringify(next));
      } catch {
        /* private mode */
      }
      return next;
    });
  }, []);

  useEffect(() => {
    void (async () => {
      try {
        const est = await navigator.storage?.estimate?.();
        if (est?.usage !== undefined) {
          setCacheText(`${(est.usage / 1024 / 1024).toFixed(1)} MB`);
        } else {
          setCacheText('unavailable');
        }
      } catch {
        setCacheText('unavailable');
      }
    })();
  }, []);

  // Sleep timer: minutes → hard stop; "end of track" → stop on the NEXT
  // auto-advance (the current track leaves the cursor).
  useEffect(() => {
    return () => {
      if (sleepTimer.current !== null) clearTimeout(sleepTimer.current);
    };
  }, []);
  useEffect(() => {
    const id = snapshot?.currentTrack?.id ?? null;
    const prev = prevTrackRef.current;
    prevTrackRef.current = id;
    if (endOfTrackRef.current && prev !== null && id !== null && id !== prev) {
      endOfTrackRef.current = false;
      setSleepMin(null);
      send({ type: 'stop' });
    }
  }, [snapshot?.currentTrack?.id, send]);

  const setSleep = (min: number | 'end' | null) => {
    if (sleepTimer.current !== null) {
      clearTimeout(sleepTimer.current);
      sleepTimer.current = null;
    }
    endOfTrackRef.current = min === 'end';
    setSleepMin(min);
    if (typeof min === 'number') {
      sleepTimer.current = setTimeout(() => {
        send({ type: 'stop' });
        setSleepMin(null);
      }, min * 60_000);
      toast(`Sleep timer: ${min} min`);
    } else if (min === 'end') {
      toast('Sleep timer: end of track');
    } else {
      toast('Sleep timer off');
    }
  };

  const clearCaches = async () => {
    if ('caches' in window) {
      const names = await caches.keys();
      await Promise.all(names.filter((n) => n.startsWith('streamify-')).map((n) => caches.delete(n)));
    }
    setCacheText('0 MB');
    toast('Caches cleared');
  };

  const exportLibrary = () => {
    const payload = {
      app: 'streamify-web',
      version: APP_VERSION,
      exportedAt: new Date().toISOString(),
      prefs,
      likes,
    };
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'streamify-library-backup.json';
    a.click();
    URL.revokeObjectURL(url);
    toast('Library backup exported');
  };

  const nuke = () => {
    try {
      localStorage.clear();
    } catch {
      /* private mode */
    }
    void (async () => {
      if ('caches' in window) {
        const names = await caches.keys();
        await Promise.all(names.map((n) => caches.delete(n)));
      }
      window.location.reload();
    })();
  };

  const checkUpdates = async () => {
    setUpdateState('checking');
    try {
      const releases = await fetchGithubReleases();
      const latest = releases[0];
      setLatestRelease(latest ? `${latest.tagName} — ${latest.name ?? 'release'}` : null);
      setUpdateState('latest');
    } catch {
      setUpdateState('error');
    }
  };

  return (
    <div className="settings" role="main">
      <header className="screen-topbar">
        <button type="button" className="screen-topbar__back" onClick={() => navigate(-1)} aria-label="Back">
          <Icon name="arrowBack" size={24} />
        </button>
        <h1 className="screen-topbar__title">Settings</h1>
      </header>

      <div className="settings__scroll">
        <Section title="Connected Streaming Accounts">
          <p className="settings__card-title">Music Services &amp; Continuum</p>
          <p className="settings__card-caption">Active Mode: {readAppMode()}</p>
          <div className="settings__row">
            <span className="settings__mode-pill" style={{ background: '#1db95433', color: '#1db954' }}>
              SPOTIFY
            </span>
            <span className="settings__mode-pill" style={{ background: '#ff000033', color: '#ff4444' }}>
              YTM
            </span>
          </div>
          <div className="settings__row">
            <button type="button" className="btn" onClick={() => navigate('/profile-selection')}>
              Manage Accounts
            </button>
            <button type="button" className="btn" onClick={() => navigate('/profile-selection')}>
              Switch Mode
            </button>
          </div>
        </Section>

        <Section title="Cloud & Social Hub">
          {session === null ? (
            configured ? (
              <button type="button" className="btn button--primary settings__google" onClick={() => signInWithGoogle()}>
                <Icon name="album" size={18} />
                Continue with Google (1-Tap)
              </button>
            ) : (
              <p className="muted">Supabase credentials not baked — cloud features run anonymous.</p>
            )
          ) : (
            <>
              <div className="settings__account">
                <span className="settings__avatar" aria-hidden="true">
                  {(session.user.email ?? '?').charAt(0).toUpperCase()}
                </span>
                <span className="settings__account-titles">
                  <span className="settings__account-name">{session.user.email ?? session.user.id}</span>
                  <span className="settings__account-hint">Tap to edit profile &amp; view stats</span>
                </span>
                <button
                  type="button"
                  className="settings__signout"
                  onClick={() => {
                    void signOut();
                  }}
                >
                  Sign Out
                </button>
              </div>
              <button type="button" className="btn settings__fullrow" onClick={() => navigate('/profile')}>
                Profile &amp; Wrapped Stats
              </button>
            </>
          )}
          <div className="settings__row">
            <button type="button" className="btn" onClick={() => navigate('/updates')}>
              Updates Hub
            </button>
            <a className="btn settings__terminal" href="canary.html">
              <Icon name="terminal" size={18} />
              Diagnostic Terminal
            </a>
          </div>
        </Section>

        <Section title="Audio Quality">
          <p className="settings__card-title">Streaming &amp; Download Bitrate</p>
          <ul className="settings__radios">
            {BITRATES.map((b) => (
              <li key={b.value}>
                <button
                  type="button"
                  className={`settings__radio ${prefs.bitrate === b.value ? 'settings__radio--on' : ''}`}
                  onClick={() => setPref('bitrate', b.value)}
                  role="radio"
                  aria-checked={prefs.bitrate === b.value}
                >
                  <span className="settings__radio-dot" aria-hidden="true" />
                  <span>{b.label}</span>
                </button>
              </li>
            ))}
          </ul>
        </Section>

        <Section title="Audio Quality & DSP">
          <ToggleRow
            label="Loudness Normalization"
            on={prefs.loudness}
            onToggle={() => setPref('loudness', !prefs.loudness)}
          />
          <SliderRow
            label="Target loudness"
            value={prefs.lufs}
            min={-23}
            max={-11}
            step={1}
            format={(v) => `${v} LUFS`}
            onChange={(v) => setPref('lufs', v)}
          />
          <ToggleRow
            label="Gapless Playback"
            on={prefs.gapless}
            onToggle={() => setPref('gapless', !prefs.gapless)}
          />
          <SliderRow
            label="Crossfade"
            value={prefs.crossfadeSec}
            min={0}
            max={12}
            step={1}
            format={(v) => (v === 0 ? 'Off' : `${v}s`)}
            onChange={(v) => setPref('crossfadeSec', v)}
          />
          <ToggleRow label="Mono Audio" on={prefs.mono} onToggle={() => setPref('mono', !prefs.mono)} />
          <SliderRow
            label="Stereo Balance"
            value={prefs.balance}
            min={-100}
            max={100}
            step={5}
            format={(v) => (v === 0 ? 'Centered' : v < 0 ? `L ${-v}%` : `R ${v}%`)}
            onChange={(v) => setPref('balance', v)}
          />
          <ToggleRow
            label="True-Peak Limiter (−1.0 dBFS)"
            on={prefs.truePeak}
            onToggle={() => setPref('truePeak', !prefs.truePeak)}
          />
          <p className="settings__note">
            Prefs persist per device; the DSP chain wiring rides the engine-quality
            lane (P3b).
          </p>
        </Section>

        <Section title="Sleep Timer">
          {sleepMin !== null && (
            <p className="settings__card-caption">
              Timer active: {sleepMin === 'end' ? 'End of track' : `${sleepMin} min`}
            </p>
          )}
          <div className="settings__row settings__row--wrap">
            {([null, 15, 30, 45, 60, 'end'] as Array<number | 'end' | null>).map((v) => (
              <button
                key={String(v)}
                type="button"
                className="btn btn--small settings__sleep"
                onClick={() => setSleep(v)}
              >
                {v === null ? 'Off' : v === 'end' ? 'End of track' : v >= 60 ? '1 hour' : `${v} min`}
              </button>
            ))}
          </div>
        </Section>

        <Section title="Storage & 5-Tier Cache">
          <div className="settings__statrow">
            <span>Audio Stream Cache</span>
            <span className="settings__statval">{cacheText}</span>
          </div>
          <div className="settings__statrow">
            <span>Cloud Likes</span>
            <span className="settings__statval">{likes.length} rows</span>
          </div>
          <button type="button" className="btn settings__fullrow" onClick={() => void clearCaches()}>
            Clear All Cache ({cacheText})
          </button>
        </Section>

        <Section title="Backup & Data Migration">
          <button type="button" className="settings__linkrow" onClick={exportLibrary}>
            <span>Export Library Backup</span>
            <span className="settings__linkrow-sub">JSON · likes + prefs</span>
            <Icon name="arrowBack" size={18} className="settings__fwd" />
          </button>
          <div className="settings__linkrow settings__linkrow--disabled">
            <span>Restore Library from JSON</span>
            <span className="settings__linkrow-sub">P3b (OPFS + upsert lane)</span>
          </div>
        </Section>

        <Section title="Danger Zone" danger>
          <p className="settings__danger-title">Nuke Database &amp; Start Fresh</p>
          <p className="settings__danger-text">
            Clears every local store on this device: settings, likes cache, play
            tally, onboarding state, service-worker caches. Cloud rows are NOT
            touched.
          </p>
          <button type="button" className="settings__nuke" onClick={() => setNukeConfirm(true)}>
            Nuke Database &amp; Clean Start
          </button>
        </Section>

        <Section title="App Updates & CI/CD Builds">
          <div className="settings__statrow">
            <span>Installed Version</span>
            <span className="settings__statval">
              Build {APP_BUILD} · v{APP_VERSION}
            </span>
          </div>
          <button
            type="button"
            className="btn button--primary settings__fullrow"
            onClick={() => void checkUpdates()}
            disabled={updateState === 'checking'}
          >
            <Icon name="refresh" size={18} />
            {updateState === 'checking' ? 'Checking…' : 'Check Updates'}
          </button>
          {updateState === 'latest' && (
            <p className="settings__update-ok">
              <Icon name="checkCircle" size={16} /> Latest release: {latestRelease ?? 'none published yet'} — you are on web build v{APP_VERSION}.
            </p>
          )}
          {updateState === 'error' && <p className="auth__error">Update check failed (GitHub API).</p>}
          <a className="settings__linkrow" href={RELEASES_URL} target="_blank" rel="noreferrer">
            <span>View GitHub Releases &amp; CI Builds</span>
            <Icon name="arrowBack" size={18} className="settings__fwd" />
          </a>
        </Section>

        <Section title="About">
          <div className="settings__statrow">
            <span>Edition</span>
            <span className="settings__statval">{APP_EDITION}</span>
          </div>
          <div className="settings__statrow">
            <span>Build Number</span>
            <span className="settings__statval">Build {APP_BUILD}</span>
          </div>
        </Section>
      </div>

      {nukeConfirm && (
        <div className="settings__scrim" role="dialog" aria-modal="true" aria-label="Confirm Nuclear Purge">
          <div className="settings__dialog">
            <h2 className="settings__dialog-title">Confirm Nuclear Purge</h2>
            <p className="settings__danger-text">
              This wipes every local store on the device and reloads. Cloud rows
              (likes, stats) are safe on Supabase.
            </p>
            <div className="settings__row settings__row--end">
              <button type="button" className="btn" onClick={() => setNukeConfirm(false)}>
                Cancel
              </button>
              <button type="button" className="settings__nuke" onClick={nuke}>
                Yes, Nuke &amp; Rebirth
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function readAppMode(): string {
  try {
    return localStorage.getItem('streamify.appmode')?.toUpperCase() ?? 'STREAMIFY';
  } catch {
    return 'STREAMIFY';
  }
}

// -- section primitives --------------------------------------------------------

function Section({
  title,
  danger,
  children,
}: {
  title: string;
  danger?: boolean;
  children: React.ReactNode;
}): JSX.Element {
  return (
    <section className={`settings__section ${danger === true ? 'settings__section--danger' : ''}`}>
      <h2 className="settings__header">{title}</h2>
      <div className="settings__card">{children}</div>
    </section>
  );
}

function ToggleRow({
  label,
  on,
  onToggle,
}: {
  label: string;
  on: boolean;
  onToggle: () => void;
}): JSX.Element {
  return (
    <div className="settings__toggle">
      <span className="settings__toggle-label">{label}</span>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-label={label}
        className={`settings__switch ${on ? 'settings__switch--on' : ''}`}
        onClick={onToggle}
      >
        <span className="settings__switch-thumb" />
      </button>
    </div>
  );
}

function SliderRow({
  label,
  value,
  min,
  max,
  step,
  format,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  format: (v: number) => string;
  onChange: (v: number) => void;
}): JSX.Element {
  return (
    <label className="settings__slider">
      <span className="settings__toggle-label">
        {label} <span className="settings__slider-value">{format(value)}</span>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-label={label}
      />
    </label>
  );
}
