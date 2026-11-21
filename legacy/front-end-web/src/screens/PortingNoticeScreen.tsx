/**
 * screens/PortingNoticeScreen.tsx — P3a: Library / Downloads placeholders.
 *
 * The 23-screen port continues in P3b; these two tabs exist now so the
 * NavRail is truthful — each shows the Android UpdatesHub empty-state
 * pattern plus whatever honest data already lives on the web (cloud
 * likes on Library; storage estimate on Downloads).
 */

import { useEffect, useState } from 'react';
import { Icon, type IconName } from '../components/icons';
import { useSessionStore } from '../lib/sessionStore';

function Notice({
  icon,
  title,
  subtitle,
  children,
}: {
  icon: IconName;
  title: string;
  subtitle: string;
  children?: React.ReactNode;
}): JSX.Element {
  return (
    <div className="notice" role="main">
      <div className="notice__inner">
        <Icon name={icon} size={48} className="notice__icon" />
        <h1 className="notice__title">{title}</h1>
        <p className="notice__subtitle">{subtitle}</p>
        {children}
      </div>
    </div>
  );
}

export function LibraryNoticeScreen(): JSX.Element {
  const session = useSessionStore((s) => s.session);
  const likes = useSessionStore((s) => s.likes);
  const likesBusy = useSessionStore((s) => s.likesBusy);
  return (
    <Notice
      icon="libraryMusic"
      title="Library"
      subtitle="The full library port (playlists, albums, artists, taste graph) lands in P3b. Your cloud likes are already here:"
    >
      {session === null ? (
        <p className="notice__data muted">Sign in (Settings or the account card on Home) to sync your likes.</p>
      ) : likesBusy ? (
        <p className="notice__data muted">loading…</p>
      ) : likes.length === 0 ? (
        <p className="notice__data muted">No liked tracks yet — ♡ any search result to start the collection.</p>
      ) : (
        <ul className="notice__likes">
          {likes.slice(0, 12).map((t) => (
            <li key={t.id}>
              <span className="notice__like-title">{t.title}</span>
              <span className="notice__like-artist">{t.artist}</span>
            </li>
          ))}
        </ul>
      )}
    </Notice>
  );
}

export function DownloadsNoticeScreen(): JSX.Element {
  const [usage, setUsage] = useState<string>('—');
  useEffect(() => {
    void (async () => {
      try {
        const est = await navigator.storage?.estimate?.();
        setUsage(est?.usage !== undefined ? `${(est.usage / 1024 / 1024).toFixed(1)} MB in use` : 'unavailable');
      } catch {
        setUsage('unavailable');
      }
    })();
  }, []);
  return (
    <Notice
      icon="download"
      title="Downloads"
      subtitle="Offline downloads port with the OPFS cache in P3b (the same chunk-seal-crypt pipeline as the APK, in wasm)."
    >
      <p className="notice__data muted">Device storage: {usage}</p>
    </Notice>
  );
}
