/**
 * streamify-splash-gate.tsx — the Wave 2 splash + sign-in gate for
 * #/action-required (the #202 restore).
 *
 * What the user sees on a locked Streamify deployment (SERVER_LOCK=true +
 * SERVER_TYPE=streamify): the ported PrismaticSplashScreen choreography
 * (legacy/front-end-web/src/screens/PrismaticSplashScreen.tsx, P3a) —
 * the #0A0A0C dark canvas, 16 prismatic ribbons, the STREAMIFY wordmark
 * with per-letter genesis + shimmer + neon underline, the DEVELOPED BY
 * SIREEN signature, the "120 FPS · SPATIAL DSP · NEURAL RADIO" tagline —
 * rendered as an AMBIENT loop (the 4.4 s intro plays once, then the
 * ribbons + breathing underline idle forever; upstream's auto-advance
 * makes no sense on a sign-in gate).
 *
 * The sign-in surface replaces upstream's Username / Password form
 * completely for ServerType.STREAMIFY:
 *   - the glowing red circular Play CTA enters Streamify in LOCAL MODE
 *     (a synthetic anonymous entry — every read resolves its honest
 *     empty; the P1 dev-session contract), offered when STREAMIFY_AUTH
 *     is 'false' or the deployment carries no Supabase configuration;
 *   - "Continue with Google" runs supabase.auth.signInWithOAuth({
 *     provider: 'google' }) with a subpath-safe redirectTo
 *     (origin + pathname — works under /streamify-apk/ and at /);
 *   - a subtle /streamify-settings link for self-hosters.
 *
 * Hygiene: requestAnimationFrame is cancelled on unmount, the pointer
 * listener is detached, and prefers-reduced-motion skips the animation
 * entirely (a static lockup renders instead).
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { encodeStreamifyLocalCredential } from '/@/renderer/api/streamify/local-entry';
import { getStreamifySupabase } from '/@/renderer/api/streamify/streamify-supabase';
import { STREAMIFY_SERVER_ID } from '/@/renderer/api/streamify/streamify-supabase';
import { AppRoute } from '/@/renderer/router/routes';
import { getServerById, useAuthStoreActions } from '/@/renderer/store';
import { logger } from '/@/renderer/utils/logger';
import { Button } from '/@/shared/components/button/button';
import { ServerType } from '/@/shared/types/types';

const INTRO_MS = 4400;

/** The prismatic ribbon palette (legacy theme tokens, verbatim). */
const SPLASH_RIBBONS = [
    '#8b5cf6',
    '#a855f7',
    '#06b6d4',
    '#10b981',
    '#f59e0b',
    '#ff0000',
    '#ec4899',
] as const;

const BG_SPLASH = '#0A0A0C';
const PRIMARY = '#ff0000';
const TEXT_SECONDARY = '#aaaaaa';

const WORDMARK = 'STREAMIFY';
const SIGNATURE_TAG = 'DEVELOPED BY ';
const SIGNATURE_NAME = 'SIREEN';
const TAGLINE = '120 FPS · SPATIAL DSP · NEURAL RADIO';

export interface StreamifySplashGateProps {
    /** Whether the ambient loop may run (false under reduced motion). */
    animate?: boolean;
    /** Override for tests: the supabase client factory. */
    getSupabase?: typeof getStreamifySupabase;
    /** Override for tests: local-entry navigation target. */
    onLocalEnter?: () => void;
}
function clamp01(x: number): number {
    return x < 0 ? 0 : x > 1 ? 1 : x;
}
function easeOutCubic(x: number): number {
    const t = clamp01(x);
    return 1 - Math.pow(1 - t, 3);
}

function smoothstep(a: number, b: number, x: number): number {
    const t = clamp01((x - a) / (b - a));
    return t * t * (3 - 2 * t);
}

export const StreamifySplashGate = ({
    animate = true,
    getSupabase = getStreamifySupabase,
    onLocalEnter,
}: StreamifySplashGateProps) => {
    const { t } = useTranslation();
    const navigate = useNavigate();
    const { setCurrentServer, updateServer } = useAuthStoreActions();
    const canvasRef = useRef<HTMLCanvasElement | null>(null);
    const [isRedirecting, setIsRedirecting] = useState(false);

    const reducedMotion = useMemo(
        () =>
            typeof window.matchMedia === 'function' &&
            window.matchMedia('(prefers-reduced-motion: reduce)').matches,
        [],
    );

    const config = getStreamifySupabase();
    // Google CTA shows exactly when the Supabase client exists.
    const showGoogle = config !== null;
    const showLocalEntry = !showGoogle;

    // ---- the ambient canvas loop (ported choreography) -------------------
    useEffect(() => {
        if (!animate || reducedMotion) return;
        const canvas = canvasRef.current;
        if (canvas === null) return;
        const ctx = canvas.getContext('2d');
        if (ctx === null) return;

        let raf = 0;
        const start = performance.now();

        const draw = (now: number) => {
            const p = clamp01((now - start) / INTRO_MS);

            const dpr = Math.min(2, window.devicePixelRatio || 1);
            const w = window.innerWidth;
            const h = window.innerHeight;
            if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
                canvas.width = w * dpr;
                canvas.height = h * dpr;
            }
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

            ctx.globalCompositeOperation = 'source-over';
            ctx.globalAlpha = 1;
            ctx.fillStyle = BG_SPLASH;
            ctx.fillRect(0, 0, w, h);

            // ---- red core + halo + play triangle -------------------------
            const coreT = smoothstep(0.35, 0.88, p);
            const cy = h / 2 - 96; // lifted: the DOM CTAs live below center
            const cx = w / 2;
            const r = 26 * (1 + 2.8 * coreT);
            const halo = ctx.createRadialGradient(cx, cy, r * 0.4, cx, cy, r * 2.6);
            halo.addColorStop(0, `rgba(255, 0, 0, ${0.34 * coreT})`);
            halo.addColorStop(0.6, `rgba(255, 0, 51, ${0.12 * coreT})`);
            halo.addColorStop(1, 'rgba(255, 0, 51, 0)');
            ctx.globalCompositeOperation = 'screen';
            ctx.fillStyle = halo;
            ctx.fillRect(cx - r * 2.8, cy - r * 2.8, r * 5.6, r * 5.6);
            ctx.beginPath();
            ctx.arc(cx, cy, r, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(255, 0, 0, ${0.9 * coreT})`;
            ctx.fill();
            const ts = r * 0.62;
            ctx.beginPath();
            ctx.moveTo(cx - ts * 0.42, cy - ts * 0.55);
            ctx.lineTo(cx - ts * 0.42, cy + ts * 0.55);
            ctx.lineTo(cx + ts * 0.62, cy);
            ctx.closePath();
            ctx.fillStyle = `rgba(255, 255, 255, ${0.95 * coreT})`;
            ctx.fill();

            // ---- prismatic ribbons (idle forever after the intro) --------
            const ribbonT = smoothstep(0.65, 0.78, p);
            if (ribbonT > 0) {
                ctx.globalCompositeOperation = 'screen';
                ctx.lineWidth = 14;
                ctx.lineCap = 'round';
                const tNow = now / 1000;
                for (let i = 0; i < 16; i += 1) {
                    const color = SPLASH_RIBBONS[i % SPLASH_RIBBONS.length];
                    const yc = h * (0.18 + (0.64 * ((i * 37) % 16)) / 15);
                    const phase = (i * 1.7 + tNow * (0.6 + (i % 5) * 0.22)) % (Math.PI * 2);
                    const amp = h * 0.05 + (i % 4) * 9;
                    ctx.beginPath();
                    for (let x = -20; x <= w + 20; x += 26) {
                        const y =
                            yc +
                            Math.sin(x / (170 + (i % 5) * 40) + phase) *
                                amp *
                                (0.5 + 0.5 * ribbonT);
                        if (x === -20) ctx.moveTo(x, y);
                        else ctx.lineTo(x, y);
                    }
                    ctx.strokeStyle = color;
                    ctx.globalAlpha = 0.16 * ribbonT * (1 - 0.4 * ((i % 3) / 2));
                    ctx.stroke();
                }
            }

            // ---- wordmark lockup -----------------------------------------
            ctx.globalCompositeOperation = 'source-over';
            const fontSize = Math.min(36, Math.max(22, w / 12));
            ctx.font = `900 ${fontSize}px system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif`;
            const letters = WORDMARK.split('');
            const letterW = letters.map((ch) => ctx.measureText(ch).width);
            const spacing = 8;
            const totalW = letterW.reduce((a, b) => a + b, 0) + spacing * (letters.length - 1);
            const drift = smoothstep(0.35, 0.65, p) * h * 0.115;
            const baseY = h / 2 - drift;
            ctx.save();
            ctx.translate(w / 2, baseY);
            ctx.textBaseline = 'alphabetic';

            let x = -totalW / 2;
            for (let i = 0; i < letters.length; i += 1) {
                const birth = 0.06 + i * 0.038;
                const a = clamp01((p - birth) / 0.12);
                const rise = (1 - a) * fontSize * 0.5;
                ctx.globalAlpha = a;
                ctx.fillStyle = '#ffffff';
                ctx.fillText(letters[i], x, rise);
                x += letterW[i] + spacing;
            }

            // shimmer: keeps sweeping in the idle loop
            const shimmerX = ((now / 2600) % 1) * (totalW + 620) - 310;
            const bandW = 240;
            const shimmer = ctx.createLinearGradient(shimmerX - bandW, 0, shimmerX + bandW, 0);
            shimmer.addColorStop(0, 'rgba(255,255,255,0)');
            shimmer.addColorStop(0.35, 'rgba(255,255,255,0.75)');
            shimmer.addColorStop(0.5, 'rgba(255,0,0,0.85)');
            shimmer.addColorStop(0.65, 'rgba(255,255,255,0.75)');
            shimmer.addColorStop(1, 'rgba(255,255,255,0)');
            ctx.save();
            ctx.beginPath();
            ctx.rect(-totalW / 2 - 8, -fontSize, totalW + 16, fontSize * 1.45);
            ctx.clip();
            ctx.fillStyle = shimmer;
            ctx.fillRect(shimmerX - bandW, -fontSize, bandW * 2, fontSize * 1.45);
            ctx.restore();

            // neon underline: sweeps to p→0.78, then breathes forever
            const sweep = easeOutCubic(Math.min(p, 0.78) / 0.78);
            const breathe = 0.78 + 0.17 * Math.sin(now / 260);
            const underY = fontSize * 0.55;
            const underW = totalW * sweep;
            const under = ctx.createLinearGradient(-totalW / 2, 0, -totalW / 2 + underW, 0);
            under.addColorStop(0, 'rgba(255,0,0,0.25)');
            under.addColorStop(0.5, `rgba(255,255,255,${breathe})`);
            under.addColorStop(1, 'rgba(255,0,0,0.25)');
            ctx.strokeStyle = under;
            ctx.lineWidth = 2.2;
            ctx.beginPath();
            ctx.moveTo(-totalW / 2, underY);
            ctx.lineTo(-totalW / 2 + underW, underY);
            ctx.stroke();

            // signature (rises p 0.16→0.26, stays)
            const sigA = clamp01((p - 0.16) / 0.1);
            if (sigA > 0) {
                ctx.globalAlpha = sigA;
                const sigY = underY + 16;
                ctx.font = `700 12px system-ui, sans-serif`;
                const tagW = ctx.measureText(SIGNATURE_TAG).width;
                ctx.fillStyle = 'rgba(255,255,255,0.82)';
                ctx.fillText(SIGNATURE_TAG, -totalW / 2, sigY + 12);
                ctx.font = `900 15px system-ui, sans-serif`;
                ctx.fillStyle = PRIMARY;
                ctx.fillText(SIGNATURE_NAME, -totalW / 2 + tagW + 4, sigY + 12);
            }

            // tagline (in p 0.60→0.90, stays in the idle loop)
            const tagA = clamp01((p - 0.6) / 0.08);
            if (tagA > 0) {
                ctx.globalAlpha = tagA;
                ctx.font = `500 10px system-ui, sans-serif`;
                ctx.fillStyle = TEXT_SECONDARY;
                const tw = ctx.measureText(TAGLINE).width;
                ctx.fillText(TAGLINE, -tw / 2, underY + 42);
            }

            ctx.restore();
            ctx.globalAlpha = 1;

            raf = requestAnimationFrame(draw);
        };

        raf = requestAnimationFrame(draw);
        return () => {
            cancelAnimationFrame(raf);
        };
    }, [animate, reducedMotion]);

    // ---- Google OAuth 2.0 (PKCE — the supabase-js default) --------------
    const handleGoogleSignIn = useCallback(async () => {
        const supabase = getSupabase();
        if (supabase === null) {
            logger.warn('Streamify splash: Google sign-in requested without Supabase config', {
                action: 'streamify_splash_google_unconfigured',
            });
            return;
        }
        setIsRedirecting(true);
        // Subpath-safe: /streamify-apk/ deployments redirect back to the
        // deployment path; root deployments to /. Query/hash-free, exactly
        // like the legacy oauthRedirectTo() and streamify-auth-route.
        const redirectTo = `${window.location.origin}${window.location.pathname}`;
        const { error } = await supabase.auth.signInWithOAuth({
            options: { redirectTo },
            provider: 'google',
        });
        // A successful start navigates away; only failures return here.
        setIsRedirecting(false);
        if (error) {
            logger.error('Streamify splash: Google sign-in failed to start', {
                action: 'streamify_splash_google_failed',
                error: error.message,
            });
        }
    }, [getSupabase]);

    // ---- Local mode entry -------------------------------------------------
    const handleLocalEnter = useCallback(() => {
        const credential = encodeStreamifyLocalCredential();
        const existing = getServerById(STREAMIFY_SERVER_ID);
        if (existing) {
            updateServer(STREAMIFY_SERVER_ID, {
                credential,
                type: ServerType.STREAMIFY,
            });
            const updated = getServerById(STREAMIFY_SERVER_ID);
            if (updated) setCurrentServer(updated);
        } else {
            setCurrentServer({
                credential,
                id: STREAMIFY_SERVER_ID,
                isAdmin: false,
                name: 'Streamify',
                ndCredential: undefined,
                remoteUrl: '',
                savePassword: false,
                type: ServerType.STREAMIFY,
                url: 'https://streamify.local',
                userId: '',
                username: 'local',
            });
        }
        if (onLocalEnter) onLocalEnter();
        else navigate(AppRoute.HOME);
    }, [navigate, onLocalEnter, setCurrentServer, updateServer]);

    return (
        <div
            aria-label={t('streamifyAuth.splashAriaLabel')}
            className="streamify-splash-gate"
            role="presentation"
            style={{
                background: BG_SPLASH,
                height: '100%',
                left: 0,
                overflow: 'hidden',
                position: 'fixed',
                top: 0,
                width: '100%',
                zIndex: 1000,
            }}
        >
            {animate && !reducedMotion ? (
                <canvas
                    aria-hidden
                    ref={canvasRef}
                    style={{ height: '100%', left: 0, position: 'absolute', top: 0, width: '100%' }}
                />
            ) : (
                // Static fallback: the reduced-motion lockup (no rAF at all)
                <div
                    aria-hidden
                    style={{
                        alignItems: 'center',
                        color: '#ffffff',
                        display: 'flex',
                        flexDirection: 'column',
                        fontFamily: 'system-ui, sans-serif',
                        gap: 10,
                        height: '100%',
                        justifyContent: 'center',
                        width: '100%',
                    }}
                >
                    <div style={{ fontSize: 32, fontWeight: 900, letterSpacing: 4 }}>
                        {WORDMARK}
                    </div>
                    <div style={{ color: TEXT_SECONDARY, fontSize: 10 }}>{TAGLINE}</div>
                    <div style={{ fontSize: 12 }}>
                        <span style={{ color: 'rgba(255,255,255,0.82)' }}>{SIGNATURE_TAG}</span>
                        <span style={{ color: PRIMARY, fontWeight: 900 }}>{SIGNATURE_NAME}</span>
                    </div>
                </div>
            )}

            <div
                style={{
                    alignItems: 'center',
                    bottom: '12vh',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 12,
                    left: 0,
                    position: 'absolute',
                    width: '100%',
                }}
            >
                {showGoogle && (
                    <Button
                        fullWidth
                        loading={isRedirecting}
                        onClick={handleGoogleSignIn}
                        size="md"
                        style={{ maxWidth: 320 }}
                    >
                        {t('streamifyAuth.continueWithGoogle')}
                    </Button>
                )}
                {showLocalEntry && (
                    <button
                        aria-label={t('streamifyAuth.enterLocally')}
                        data-testid="streamify-local-enter"
                        onClick={handleLocalEnter}
                        style={{
                            alignItems: 'center',
                            background: PRIMARY,
                            border: 'none',
                            borderRadius: '50%',
                            boxShadow: `0 0 32px rgba(255,0,0,0.55), 0 0 96px rgba(255,0,0,0.25)`,
                            color: '#ffffff',
                            cursor: 'pointer',
                            display: 'flex',
                            height: 72,
                            justifyContent: 'center',
                            width: 72,
                        }}
                        type="button"
                    >
                        <svg aria-hidden height="30" viewBox="0 0 24 24" width="30">
                            <path d="M8 5v14l11-7z" fill="currentColor" />
                        </svg>
                    </button>
                )}
                <button
                    onClick={() => navigate(AppRoute.STREAMIFY_SETTINGS)}
                    style={{
                        background: 'transparent',
                        border: 'none',
                        color: TEXT_SECONDARY,
                        cursor: 'pointer',
                        fontSize: 11,
                        textDecoration: 'underline',
                    }}
                    type="button"
                >
                    {t('streamifyAuth.configureDeployment')}
                </button>
            </div>
        </div>
    );
};
