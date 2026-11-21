/**
 * screens/YtOnboardingScreen.tsx — P3a: the onboarding gate.
 *
 * The Android YtOnboardingScreen port: pulsing radial glow, the red
 * logo badge, welcome copy, and the "Sign In with Google" pill — wired
 * to the Supabase Google OAuth lane (the Android AuthManager's web
 * lowering). The guest link is the web adaptation: anonymous search
 * keeps working (P2 behavior). Auto-advances the moment a session
 * exists (including one arriving via OAuth redirect).
 */

import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { useSessionStore } from '../lib/sessionStore';
import { completeOnboarding } from './OnboardingGate';

export function YtOnboardingScreen(): JSX.Element {
  const navigate = useNavigate();
  const session = useSessionStore((s) => s.session);
  const booted = useSessionStore((s) => s.booted);
  const configured = useSessionStore((s) => s.configured);
  const authError = useSessionStore((s) => s.authError);
  const signInWithGoogle = useSessionStore((s) => s.signInWithGoogle);

  // A live session (persisted OR fresh off the OAuth redirect) completes
  // onboarding — the auth StateFlow auto-advance in MainActivity.
  useEffect(() => {
    if (booted && session !== null) {
      completeOnboarding();
      navigate('/', { replace: true });
    }
  }, [booted, session, navigate]);

  const onGuest = () => {
    completeOnboarding();
    navigate('/', { replace: true });
  };

  return (
    <div className="onboarding" role="main">
      <div className="onboarding__glow" aria-hidden="true" />
      <div className="onboarding__column">
        <div className="onboarding__logo" aria-hidden="true">
          <Icon name="playArrow" size={52} />
        </div>
        <h1 className="onboarding__title">Welcome to Streamify</h1>
        <p className="onboarding__subtitle">
          Your offline acoustic AI music sanctuary with 120 FPS high-fidelity sound.
        </p>
        <div className="onboarding__actions">
          {configured ? (
            <button type="button" className="onboarding__google" onClick={() => signInWithGoogle()}>
              <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
                <path
                  fill="#EA4335"
                  d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"
                />
                <path
                  fill="#4285F4"
                  d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"
                />
                <path
                  fill="#FBBC05"
                  d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"
                />
                <path
                  fill="#34A853"
                  d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
                />
              </svg>
              <span>Sign In with Google</span>
            </button>
          ) : (
            <p className="onboarding__unconfigured">
              Google sign-in needs Supabase credentials baked by CI — this build
              runs anonymous.
            </p>
          )}
          <button type="button" className="onboarding__guest" onClick={onGuest}>
            Continue without an account
          </button>
        </div>
        {authError !== null && <p className="onboarding__error">{authError}</p>}
        <p className="onboarding__trust">
          <span aria-hidden="true">🔒</span> Secure Multi-Device Taste Sync &amp; Cloud Storage
        </p>
      </div>
    </div>
  );
}
