/**
 * features/streamify-settings/components/account-session-panel.tsx -
 * Account & Session (P4B): signed-in Google profile status, manual session
 * refresh, and sign-out - all through the P1 session seam
 * (streamify-supabase.ts), no upstream auth file touched.
 */

import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
    getStreamifySession,
    getStreamifySupabase,
    signOutStreamify,
    upsertStreamifyServerEntry,
} from '/@/renderer/api/streamify/streamify-supabase';
import { useAuthStore } from '/@/renderer/store';
import { useStreamifySettingsStore } from '/@/renderer/store/streamify-settings.store';
import { Badge } from '/@/shared/components/badge/badge';
import { Button } from '/@/shared/components/button/button';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { Textarea } from '/@/shared/components/textarea/textarea';
import { toast } from '/@/shared/components/toast/toast';
import { ServerType } from '/@/shared/types/types';

export const AccountSessionPanel = () => {
    const { t } = useTranslation();
    const [busy, setBusy] = useState(false);

    const settings = useStreamifySettingsStore((state) => state.settings);
    const setSettings = useStreamifySettingsStore((state) => state.set);
    const [cookieDraft, setCookieDraft] = useState(settings.userCookie || '');

    const hasSapisid = /(?:^|;\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)/.test(
        cookieDraft || settings.userCookie || '',
    );
    const hasCookie = (cookieDraft || settings.userCookie || '').trim().length > 0;

    const handleSaveCookie = () => {
        const clean = cookieDraft.trim();
        setSettings({ userCookie: clean });
        try {
            if (clean) {
                window.localStorage.setItem('streamify.user_cookie', clean);
            } else {
                window.localStorage.removeItem('streamify.user_cookie');
            }
        } catch {
            // localStorage fallback
        }
        toast.success({ message: 'YouTube session cookie saved' });
    };

    const handleClearCookie = () => {
        setCookieDraft('');
        setSettings({ userCookie: '' });
        try {
            window.localStorage.removeItem('streamify.user_cookie');
        } catch {
            // localStorage fallback
        }
        toast.success({ message: 'YouTube session cookie cleared' });
    };

    const server = useAuthStore((state) => state.currentServer);
    const isStreamify = server?.type === ServerType.STREAMIFY;

    const handleRefresh = async () => {
        setBusy(true);
        try {
            const supabase = getStreamifySupabase();
            if (supabase) {
                const { data } = await supabase.auth.refreshSession();
                if (data.session) {
                    upsertStreamifyServerEntry(data.session);
                }
            }
            const session = await getStreamifySession();
            if (session) {
                upsertStreamifyServerEntry(session);
                toast.success({ message: t('streamify.settings.sessionRefreshed') });
            } else {
                toast.error({ message: t('streamify.settings.sessionMissing') });
            }
        } finally {
            setBusy(false);
        }
    };

    const handleSignOut = async () => {
        await signOutStreamify();
        toast.success({ message: t('streamify.settings.signOutSuccess') });
    };

    return (
        <Stack gap="md">
            <Stack gap={4}>
                <TextTitle order={5}>{t('streamify.settings.account')}</TextTitle>
                {isStreamify ? (
                    <Group gap="xs">
                        <Badge color="green" variant="outline">
                            {t('streamify.settings.signedIn')}
                        </Badge>
                        <Text size="sm" span>
                            {server?.username ?? server?.userId}
                        </Text>
                    </Group>
                ) : (
                    <Text isMuted size="sm">
                        {t('streamify.settings.signedOut')}
                    </Text>
                )}
            </Stack>

            <Divider />

            <Group gap="xs">
                <Button
                    disabled={!isStreamify}
                    loading={busy}
                    onClick={handleRefresh}
                    size="sm"
                    variant="outline"
                >
                    {t('streamify.settings.refreshSession')}
                </Button>
                <Button
                    color="red"
                    disabled={!isStreamify}
                    onClick={handleSignOut}
                    size="sm"
                    variant="outline"
                >
                    {t('streamify.settings.signOut')}
                </Button>
            </Group>

            <Divider />

            <Stack gap={4}>
                <Group justify="apart">
                    <TextTitle order={5}>YouTube Music Session (Option 3)</TextTitle>
                    {hasSapisid ? (
                        <Badge color="green" variant="outline">
                            Authenticated (SAPISID active)
                        </Badge>
                    ) : hasCookie ? (
                        <Badge color="yellow" variant="outline">
                            Cookie set (no SAPISID)
                        </Badge>
                    ) : (
                        <Badge color="gray" variant="outline">
                            Guest (tokenless)
                        </Badge>
                    )}
                </Group>
                <Text isMuted size="sm">
                    Optional YouTube session cookie (containing SAPISID) to unlock bot-gated
                    licensed tracks and official masters directly in the browser player without
                    datacenter bot-walls.
                </Text>
                <Textarea
                    autosize
                    maxRows={6}
                    minRows={2}
                    onChange={(e) => setCookieDraft(e.currentTarget.value)}
                    placeholder="Paste YouTube cookie here (e.g. __Secure-3PAPISID=...; SAPISID=...)"
                    value={cookieDraft}
                />
                <Group gap="xs">
                    <Button
                        disabled={cookieDraft.trim() === (settings.userCookie || '')}
                        onClick={handleSaveCookie}
                        size="sm"
                        variant="filled"
                    >
                        Save Cookie
                    </Button>
                    <Button
                        color="red"
                        disabled={!settings.userCookie && !cookieDraft}
                        onClick={handleClearCookie}
                        size="sm"
                        variant="outline"
                    >
                        Clear Cookie
                    </Button>
                </Group>
            </Stack>
        </Stack>
    );
};
