/**
 * features/streamify/jam/components/jam-reaction-bar.tsx — W3-B: the Jam
 * session reaction surface.
 *
 * Five whitelisted emoji triggers (flood-controlled inside the session
 * controller — one emit per 500 ms) plus the floating-badge overlay that
 * renders the ephemeral reaction feed. Everything degrades silently when
 * the room is not live or the controller is absent (offline / local-only
 * playlists): the buttons disable, the stage stays empty — zero errors.
 */

import type { StreamifyJamSessionController } from '/@/renderer/api/streamify/jam-live/jam-session-controller';

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import styles from './jam-reactions.module.css';

import { JAM_REACTION_EMOJIS } from '/@/renderer/features/streamify/jam/jam-voting';
import { useStreamifyJamStore } from '/@/renderer/store/streamify-jam.store';
import { Button } from '/@/shared/components/button/button';
import { Group } from '/@/shared/components/group/group';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';

/** Badges older than this stop rendering (the float animation is 2.4 s). */
const REACTION_VISIBLE_MS = 2_600;

export const JamReactionBar = ({
    controller,
}: {
    controller: null | StreamifyJamSessionController;
}) => {
    const { t } = useTranslation();
    const status = useStreamifyJamStore((state) => state.status);
    const reactions = useStreamifyJamStore((state) => state.reactions);

    // Age-out tick: stale badges leave the DOM even without new pushes.
    // The clock lives in state (set from effects only) so the render stays
    // pure — react-hooks/purity forbids Date.now() during render.
    const [nowTick, setNowTick] = useState<null | number>(null);
    useEffect(() => {
        if (reactions.length === 0) return;
        setNowTick(Date.now());
        const timer = window.setInterval(() => setNowTick(Date.now()), 1_000);
        return () => window.clearInterval(timer);
    }, [reactions.length]);

    const visible =
        nowTick === null ? [] : reactions.filter((r) => nowTick - r.atMs < REACTION_VISIBLE_MS);
    const live = status === 'live' && controller !== null;

    return (
        <Stack gap={4}>
            <Text isMuted size="sm">
                {t('streamify.jam.reactionsTitle')}
            </Text>
            <div className={styles.stage}>
                {visible.map((reaction, i) => (
                    <span
                        className={styles.float}
                        key={reaction.key}
                        style={{ left: `${8 + (i % 8) * 34}px` }}
                    >
                        {reaction.emoji}
                    </span>
                ))}
            </div>
            <Group gap="xs">
                {JAM_REACTION_EMOJIS.map((emoji) => (
                    <Button
                        disabled={!live}
                        key={emoji}
                        onClick={() => controller?.sendReaction(emoji)}
                        size="compact-sm"
                        variant="subtle"
                    >
                        {emoji}
                    </Button>
                ))}
            </Group>
        </Stack>
    );
};
