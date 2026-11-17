/**
 * features/streamify-badges/components/quality-badges.tsx — the player-bar
 * honest quality badges (W1-B).
 *
 * One upstream hook point (right-controls.tsx renders
 * `<StreamifyQualityBadges />`, logged in PATCHES.md A14); everything
 * else lives here. Renders null on stock servers.
 *
 * ZERO-EGRESS BY CONSTRUCTION: the component reads the resolver's cache
 * (peekStreamUrl — the resolve playback already performed) and derives
 * the offline state from the negotiated resolution (P4-A's offline serve
 * hands the element a blob: URL; that fact IS the manifest read-out).
 * No getInfo, no supplemental InnerTube requests, no store mutations.
 *
 * The badge model itself is pure (quality-badge-model.ts) and locked by
 * the network-free vitest suite in ../__tests__/.
 */

import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { videoIdOfYtmSong } from '/@/renderer/api/streamify/catalog/ytm-mappers';
import { currentStreamifyLibrary } from '/@/renderer/api/streamify/engine';
import { peekStreamUrl } from '/@/renderer/api/streamify/playback/stream-source';
import {
    computeQualityBadges,
    type QualityBadge,
} from '/@/renderer/features/streamify-badges/quality-badge-model';
import { useAuthStore, usePlayerSong } from '/@/renderer/store';
import { Group } from '/@/shared/components/group/group';
import { Pill } from '/@/shared/components/pill/pill';
import { ServerType } from '/@/shared/types/types';

/** The videoId a song id resolves to (snapshot row, or a ytm: decode). */
function videoIdOfSongId(songId: null | string | undefined): null | string {
    if (songId === undefined || songId === null || songId === '') return null;
    const ytm = videoIdOfYtmSong(songId);
    if (ytm !== null) return ytm;
    const snapshot = currentStreamifyLibrary();
    const row = snapshot?.songById.get(songId);
    return row?.streamUrl ?? null;
}

const BADGE_REFRESH_MS = 1000;

export const StreamifyQualityBadges = () => {
    const { t } = useTranslation();
    const isStreamify = useAuthStore((state) => state.currentServer?.type) === ServerType.STREAMIFY;
    const currentSong = usePlayerSong();
    const [badges, setBadges] = useState<QualityBadge[]>([]);

    const songId = currentSong?.id ?? null;

    useEffect(() => {
        if (!isStreamify) {
            setBadges([]);
            return;
        }
        const recompute = (): void => {
            const videoId = videoIdOfSongId(songId);
            const resolution = videoId === null ? null : peekStreamUrl(videoId);
            setBadges(
                computeQualityBadges({
                    offline: resolution?.url.startsWith('blob:') === true,
                    resolution,
                }),
            );
        };
        recompute();
        // The resolution lands asynchronously after the song flips (and
        // re-resolves on expiry) — a light tick keeps the badges truthful
        // without any IO (peek is a Map read).
        const timer = window.setInterval(recompute, BADGE_REFRESH_MS);
        return () => window.clearInterval(timer);
    }, [isStreamify, songId]);

    if (!isStreamify || badges.length === 0) {
        return null;
    }

    return (
        <Group gap={4} wrap="nowrap">
            {badges.map((badge) => (
                <Pill
                    key={badge.kind}
                    size="xs"
                    style={{ cursor: 'default', userSelect: 'none' }}
                    title={t('streamify.badges.title')}
                >
                    {badge.kind === 'offline' ? t('streamify.badges.offline') : badge.label}
                </Pill>
            ))}
        </Group>
    );
};
