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
import { Badge } from '/@/shared/components/badge/badge';
import { Button } from '/@/shared/components/button/button';
import { Divider } from '/@/shared/components/divider/divider';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { TextTitle } from '/@/shared/components/text-title/text-title';
import { Text } from '/@/shared/components/text/text';
import { toast } from '/@/shared/components/toast/toast';
import { ServerType } from '/@/shared/types/types';

export const AccountSessionPanel = () => {
    const { t } = useTranslation();
    const [busy, setBusy] = useState(false);

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
        </Stack>
    );
};
