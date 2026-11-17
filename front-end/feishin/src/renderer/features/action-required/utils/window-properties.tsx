export const isLegacyAuth = () =>
    window.LEGACY_AUTHENTICATION === true || window.LEGACY_AUTHENTICATION === 'true';

export const isServerLock = () => window.SERVER_LOCK === true || window.SERVER_LOCK === 'true';

/**
 * #202: is this deployment locked to the Streamify server type? The
 * splash gate branch reads this — a locked STREAMIFY deployment renders
 * the wave splash + Google OAuth gate (local-mode entry when Supabase is
 * unconfigured), every other locked type keeps the stock login form.
 */
export const isStreamifyLockedDeployment = () =>
    window.SERVER_TYPE === 'streamify' || window.SERVER_TYPE === 'Streamify';
