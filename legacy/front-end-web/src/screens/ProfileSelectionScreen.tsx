/**
 * screens/ProfileSelectionScreen.tsx — P3a: profile provider selection.
 *
 * The Android ProfileSelectionScreen port: entrance slide-up, the
 * ONBOARDING CONTINUUM pill, and the three BrandedProfileCards (Spotify
 * green / YouTube red / Streamify). The Spotify/YTM cards open the
 * native WebView login dialogs on Android — the web port surfaces an
 * honest "lands with the P3b library port" sheet (those flows need
 * native-only webviews); the Streamify Continuum card works today
 * (persists the app mode + advances).
 */

import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon, type IconName } from '../components/icons';
import { completeOnboarding } from './OnboardingGate';

type Provider = 'spotify' | 'ytm' | 'streamify';

const APP_MODE_KEY = 'streamify.appmode';

interface CardSpec {
  id: Provider;
  title: string;
  subtitle: string;
  color: string;
  badge: string;
  icon: IconName;
}

const CARDS: CardSpec[] = [
  {
    id: 'spotify',
    title: 'Spotify Profile',
    subtitle: 'Daily Mixes 1–6, Blends, Saved Library & Taste Graph',
    color: '#1db954',
    badge: 'VIBE ENGINE',
    icon: 'playArrow',
  },
  {
    id: 'ytm',
    title: 'YouTube Music Profile',
    subtitle: 'Supermix, Quick Picks, Unreleased Live & Remix Shelves',
    color: '#ff0000',
    badge: 'DEEP CATALOG',
    icon: 'radio',
  },
  {
    id: 'streamify',
    title: 'Streamify Continuum',
    subtitle: '50/50 Dual-Core Ensemble, Local Vectors & Edge Mesh',
    color: '#ff0000',
    badge: 'AUTONOMOUS',
    icon: 'autoAwesome',
  },
];

export function ProfileSelectionScreen(): JSX.Element {
  const navigate = useNavigate();
  const [visible, setVisible] = useState(false);
  const [sheet, setSheet] = useState<Provider | null>(null);

  useEffect(() => {
    const t = setTimeout(() => setVisible(true), 40);
    return () => clearTimeout(t);
  }, []);

  const choose = (id: Provider) => {
    if (id === 'streamify') {
      try {
        localStorage.setItem(APP_MODE_KEY, 'streamify');
      } catch {
        /* private mode */
      }
      completeOnboarding();
      navigate('/', { replace: true });
      return;
    }
    setSheet(id);
  };

  const sheetCard = sheet === null ? null : (CARDS.find((c) => c.id === sheet) ?? null);

  return (
    <div className="profilesel" role="main">
      <div className={`profilesel__inner ${visible ? 'profilesel__inner--in' : ''}`}>
        <span className="profilesel__pill">ONBOARDING CONTINUUM</span>
        <h1 className="profilesel__title">Choose Your Experience</h1>
        <p className="profilesel__subtitle">
          Select your profile provider to initialize your personalized real-time
          recommendation feed and acoustic continuum.
        </p>
        <div className="profilesel__cards">
          {CARDS.map((card) => (
            <button
              key={card.id}
              type="button"
              className="profilesel__card"
              style={{
                borderColor: `${card.color}40`,
                background: '#141418',
              }}
              onClick={() => choose(card.id)}
            >
              <span
                className="profilesel__card-icon"
                style={{ background: `${card.color}26`, color: card.color }}
              >
                <Icon name={card.icon} size={28} />
              </span>
              <span className="profilesel__card-body">
                <span className="profilesel__card-head">
                  <span className="profilesel__card-title">{card.title}</span>
                  <span
                    className="profilesel__card-badge"
                    style={{ background: `${card.color}1f`, color: card.color }}
                  >
                    {card.badge}
                  </span>
                </span>
                <span className="profilesel__card-sub">{card.subtitle}</span>
              </span>
            </button>
          ))}
        </div>
      </div>

      {sheetCard !== null && (
        <div className="profilesel__sheet" role="dialog" aria-modal="true" aria-label={sheetCard.title}>
          <div className="profilesel__sheet-card">
            <h2 className="profilesel__sheet-title">{sheetCard.title}</h2>
            <p className="profilesel__sheet-text">
              {sheetCard.id === 'spotify'
                ? 'Spotify profile linking runs through the native in-app WebView on Android. The web linking flow lands with the P3b library port — your Spotify continuum is one phase away.'
                : 'YouTube Music profile linking runs through the native in-app WebView on Android. The web linking flow lands with the P3b library port — your YTM continuum is one phase away.'}
            </p>
            <div className="profilesel__sheet-actions">
              <button
                type="button"
                className="btn button--primary"
                onClick={() => setSheet(null)}
              >
                Got it
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
