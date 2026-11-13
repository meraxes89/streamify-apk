/**
 * screens/OnboardingGate.tsx — P3a: first-visit gate for the home route.
 *
 * The Android root gate (authState != Authenticated → YtOnboardingScreen),
 * web-lowered: first visit per browser → onboarding; the guest lane keeps
 * anonymous search working (the P2 behavior).
 */

import { Navigate } from 'react-router-dom';
import { HomeScreen } from './HomeScreen';

export function onboardingDone(): boolean {
  try {
    return localStorage.getItem('streamify.onboarding.v1') === 'done';
  } catch {
    return true; // storage unavailable — never gate the app
  }
}

export function completeOnboarding(): void {
  try {
    localStorage.setItem('streamify.onboarding.v1', 'done');
  } catch {
    /* private mode */
  }
}

export function OnboardingGate(): JSX.Element {
  if (!onboardingDone()) {
    return <Navigate to="/onboarding" replace />;
  }
  return <HomeScreen />;
}
