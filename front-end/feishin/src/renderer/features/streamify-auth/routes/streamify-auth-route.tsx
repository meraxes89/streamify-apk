/**
 * streamify-auth-route.tsx — the Streamify sign-in page (P1, our module).
 *
 * Google OAuth through the Supabase JS SDK against the Streamify Supabase
 * project. On success the live session is mirrored into feishin's own server
 * store as the synthetic `ServerType.STREAMIFY` entry (persisted across
 * reloads, listed like any Navidrome/Jellyfin entry), the P0 stub controller
 * backs it, and the user goes straight into the app.
 *
 * The stock Navidrome/Jellyfin/OpenSubsonic login UI stays untouched upstream
 * code — "use your own server" below walks into that exact flow.
 */
import { useEffect, useState } from 'react';
import { Navigate, useNavigate } from 'react-router';

import {
    getStreamifySession,
    getStreamifySupabase,
    isStreamifyAuthEnabled,
    signOutStreamify,
    STREAMIFY_SERVER_ID,
    upsertStreamifyServerEntry,
} from '/@/renderer/api/streamify/streamify-supabase';
import { PageHeader } from '/@/renderer/components/page-header/page-header';
import StreamifyLogo from '/@/renderer/features/servers/assets/streamify.png';
import { AnimatedPage } from '/@/renderer/features/shared/components/animated-page';
import { PageErrorBoundary } from '/@/renderer/features/shared/components/page-error-boundary';
import { AppRoute } from '/@/renderer/router/routes';
import { getServerById, useAuthStore, useAuthStoreActions } from '/@/renderer/store';
import { logger } from '/@/renderer/utils/logger';
import { Button } from '/@/shared/components/button/button';
import { Center } from '/@/shared/components/center/center';
import { Divider } from '/@/shared/components/divider/divider';
import { Paper } from '/@/shared/components/paper/paper';
import { Spinner } from '/@/shared/components/spinner/spinner';
import { Stack } from '/@/shared/components/stack/stack';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';
import { ServerType } from '/@/shared/types/types';

type StreamifyAuthStatus = 'checking' | 'error' | 'ready' | 'signed-in' | 'unconfigured';

const StreamifyAuthRoute = () => {
    const navigate = useNavigate();
    const { setCurrentServer } = useAuthStoreActions();
    const [status, setStatus] = useState<StreamifyAuthStatus>(() =>
        isStreamifyAuthEnabled() ? 'checking' : 'unconfigured',
    );
    const [sessionEmail, setSessionEmail] = useState<null | string>(null);
    const [isRedirecting, setIsRedirecting] = useState(false);

    const currentServerType = useAuthStore((s) => s.currentServer?.type ?? null);

    useEffect(() => {
        let cancelled = false;

        const restore = async () => {
            if (!isStreamifyAuthEnabled()) {
                setStatus('unconfigured');
                return;
            }

            try {
                const session = await getStreamifySession();

                if (cancelled) return;

                if (session) {
                    // Consumes OAuth redirects that land here and keeps the
                    // synthetic entry in sync with the live session.
                    upsertStreamifyServerEntry(session);
                    setSessionEmail(session.user.email ?? null);
                    setStatus('signed-in');
                } else {
                    setStatus('ready');
                }
            } catch (error) {
                logger.error('Streamify auth route session restore failed', {
                    action: 'streamify_auth_restore_failed',
                    error: error instanceof Error ? error.message : String(error),
                });

                if (!cancelled) setStatus('error');
            }
        };

        restore();

        return () => {
            cancelled = true;
        };
    }, []);

    // Already signed in with the Streamify entry selected → straight home.
    if (status === 'signed-in' && currentServerType === ServerType.STREAMIFY) {
        return <Navigate replace to={AppRoute.HOME} />;
    }

    const handleGoogleSignIn = async () => {
        const supabase = getStreamifySupabase();

        if (!supabase) {
            toast.error({ message: 'Streamify is not configured for this deployment' });
            return;
        }

        setIsRedirecting(true);

        const { error } = await supabase.auth.signInWithOAuth({
            options: {
                redirectTo: `${window.location.origin}${window.location.pathname}`,
            },
            provider: 'google',
        });

        // A successful call navigates away; only a failed start returns.
        setIsRedirecting(false);

        if (error) {
            logger.error('Streamify Google sign-in failed to start', {
                action: 'streamify_google_signin_failed',
                error: error.message,
            });

            toast.error({ message: error.message });
        }
    };

    const handleOpenStreamify = () => {
        const server = getServerById(STREAMIFY_SERVER_ID);

        if (server) {
            setCurrentServer(server);
            navigate(AppRoute.HOME);
        }
    };

    const handleSignOut = async () => {
        await signOutStreamify();
        setSessionEmail(null);
        setStatus('ready');
    };

    return (
        <AnimatedPage>
            <PageHeader />
            <Center style={{ height: '100%', width: '100vw' }}>
                <Paper p="xl" style={{ maxWidth: '420px', width: '100%' }}>
                    <PageErrorBoundary>
                        <Stack align="center" gap="xl">
                            <Stack align="center" gap="md">
                                <img alt="Streamify" height="80" src={StreamifyLogo} width="80" />
                                <TextTitle fw={700} order={2}>
                                    Streamify
                                </TextTitle>
                                {status === 'unconfigured' ? (
                                    <Text c="dimmed" size="sm" ta="center">
                                        This deployment has no Supabase configuration. Set
                                        STREAMIFY_SUPABASE_URL and STREAMIFY_SUPABASE_ANON_KEY in
                                        settings.js to enable Streamify sign-in.
                                    </Text>
                                ) : (
                                    <Text c="dimmed" size="sm" ta="center">
                                        Sign in with your Google account to access your Streamify
                                        library.
                                    </Text>
                                )}
                            </Stack>

                            {status === 'checking' && (
                                <Center h={64} w="100%">
                                    <Spinner container />
                                </Center>
                            )}

                            {status === 'ready' && (
                                <Button
                                    fullWidth
                                    loading={isRedirecting}
                                    onClick={handleGoogleSignIn}
                                    size="md"
                                >
                                    Continue with Google
                                </Button>
                            )}

                            {status === 'signed-in' && (
                                <Stack gap="md" w="100%">
                                    <Text size="sm" ta="center">
                                        Signed in as{' '}
                                        <Text fw={600} span>
                                            {sessionEmail ?? 'Streamify user'}
                                        </Text>
                                    </Text>
                                    <Button fullWidth onClick={handleOpenStreamify} size="md">
                                        Open Streamify
                                    </Button>
                                    <Button
                                        color="gray"
                                        fullWidth
                                        onClick={handleSignOut}
                                        size="md"
                                        variant="subtle"
                                    >
                                        Sign out
                                    </Button>
                                </Stack>
                            )}

                            {status === 'error' && (
                                <Text c="red" size="sm" ta="center">
                                    Could not restore your Streamify session. Reload the page to try
                                    again.
                                </Text>
                            )}

                            <Divider
                                label="Have your own music server?"
                                labelPosition="center"
                                w="100%"
                            />

                            <Button
                                color="gray"
                                fullWidth
                                onClick={() => navigate(AppRoute.ACTION_REQUIRED)}
                                size="md"
                                variant="light"
                            >
                                Use your own Navidrome, Jellyfin, or OpenSubsonic server
                            </Button>
                        </Stack>
                    </PageErrorBoundary>
                </Paper>
            </Center>
        </AnimatedPage>
    );
};

export default StreamifyAuthRoute;
