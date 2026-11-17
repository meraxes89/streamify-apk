/**
 * features/streamify/connect/components/streamify-connect-button.tsx —
 * the W3-A player-bar seam: the Connect Devices popover.
 *
 * One upstream hook point (right-controls.tsx renders
 * `<StreamifyConnectButton />`, logged in PATCHES.md A14) and everything
 * else lives in features/streamify/connect/:
 *
 *   - mounts the Connect controller (presence + command lane),
 *   - the device list popover: per remote device — the now-playing line
 *     (interpolated position), the remote transport buttons (play/pause,
 *     prev, next, seek-to-live) and "Transfer playback here",
 *   - renders null when the session is not Streamify or Supabase is
 *     unconfigured (the graceful-degradation contract).
 */

import { type CSSProperties, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { LuMonitorSmartphone, LuPause, LuPlay, LuSkipBack, LuSkipForward } from 'react-icons/lu';

import {
    useIsConnectAvailable,
    useStreamifyConnect,
} from '/@/renderer/features/streamify/connect/hooks/use-streamify-connect';
import { formatChapterTime } from '/@/renderer/features/streamify/video/chapter-utils';
import { Badge } from '/@/shared/components/badge/badge';
import { Group } from '/@/shared/components/group/group';
import { Popover } from '/@/shared/components/popover/popover';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';
import { Tooltip } from '/@/shared/components/tooltip/tooltip';

const remoteButtonStyle: CSSProperties = {
    alignItems: 'center',
    background: 'rgb(var(--theme-colors-background) / 0.6)',
    border: '1px solid rgb(var(--theme-colors-text) / 0.2)',
    borderRadius: '6px',
    color: 'inherit',
    cursor: 'pointer',
    display: 'inline-flex',
    height: '28px',
    justifyContent: 'center',
    minWidth: '28px',
    padding: '0 6px',
};

export const StreamifyConnectButton = () => {
    const { t } = useTranslation();
    const [opened, setOpened] = useState(false);
    const available = useIsConnectAvailable();
    const { devices, requestHandoff, sendTransportCommand, status } = useStreamifyConnect();

    if (!available) {
        return null;
    }

    return (
        <Popover
            onClose={() => setOpened(false)}
            opened={opened}
            position="top"
            width={340}
            withinPortal
        >
            <Popover.Target>
                <Tooltip label={t('streamify.connect.buttonTooltip')} openDelay={0}>
                    <button
                        aria-label={t('streamify.connect.buttonTooltip')}
                        onClick={(event) => {
                            event.stopPropagation();
                            setOpened((o) => !o);
                        }}
                        style={{
                            alignItems: 'center',
                            background: 'transparent',
                            border: 'none',
                            color:
                                status === 'connected' ? 'var(--theme-colors-primary)' : 'inherit',
                            cursor: 'pointer',
                            display: 'inline-flex',
                            height: '32px',
                            justifyContent: 'center',
                            width: '32px',
                        }}
                        type="button"
                    >
                        <LuMonitorSmartphone size={18} />
                    </button>
                </Tooltip>
            </Popover.Target>
            <Popover.Dropdown onClick={(e) => e.stopPropagation()} p="sm">
                <Stack gap="sm">
                    <Group gap="xs" justify="apart" wrap="nowrap">
                        <Text size="sm" weight={600}>
                            {t('streamify.connect.popoverTitle')}
                        </Text>
                        {status === 'connected' ? (
                            <Badge color="primary" size="sm" variant="filled">
                                {t('streamify.connect.statusOnline')}
                            </Badge>
                        ) : status === 'connecting' ? (
                            <Badge color="yellow" size="sm" variant="filled">
                                {t('streamify.connect.statusConnecting')}
                            </Badge>
                        ) : (
                            <Badge color="gray" size="sm" variant="filled">
                                {t('streamify.connect.statusOffline')}
                            </Badge>
                        )}
                    </Group>
                    {devices.length === 0 ? (
                        <Text isMuted size="xs">
                            {t('streamify.connect.noDevices')}
                        </Text>
                    ) : (
                        devices.map((device) => (
                            <Stack
                                gap="xs"
                                key={device.presence.deviceId}
                                style={{
                                    border: '1px solid rgb(var(--theme-colors-text) / 0.15)',
                                    borderRadius: '6px',
                                    padding: '6px 8px',
                                }}
                            >
                                <Text size="xs" weight={600}>
                                    {device.deviceName} ·{' '}
                                    {device.isPlaying
                                        ? t('streamify.connect.playingBadge')
                                        : t('streamify.connect.pausedBadge')}
                                    {device.mode === 'video'
                                        ? ` · ${t('streamify.connect.videoBadge')}`
                                        : ''}
                                </Text>
                                <Text isMuted size="xs">
                                    {device.presence.currentTrack
                                        ? `${device.presence.currentTrack.title} — ${formatChapterTime(
                                              device.interpolatedPositionSec,
                                          )}`
                                        : t('streamify.connect.idleDevice')}
                                </Text>
                                <Group gap="xs" wrap="nowrap">
                                    <Tooltip label={t('streamify.connect.commandPlay')}>
                                        <button
                                            aria-label={t('streamify.connect.commandPlay')}
                                            onClick={() =>
                                                sendTransportCommand(
                                                    'play',
                                                    device.presence.deviceId,
                                                )
                                            }
                                            style={remoteButtonStyle}
                                            type="button"
                                        >
                                            <LuPlay size={14} />
                                        </button>
                                    </Tooltip>
                                    <Tooltip label={t('streamify.connect.commandPause')}>
                                        <button
                                            aria-label={t('streamify.connect.commandPause')}
                                            onClick={() =>
                                                sendTransportCommand(
                                                    'pause',
                                                    device.presence.deviceId,
                                                )
                                            }
                                            style={remoteButtonStyle}
                                            type="button"
                                        >
                                            <LuPause size={14} />
                                        </button>
                                    </Tooltip>
                                    <Tooltip label={t('streamify.connect.commandPrev')}>
                                        <button
                                            aria-label={t('streamify.connect.commandPrev')}
                                            onClick={() =>
                                                sendTransportCommand(
                                                    'prev',
                                                    device.presence.deviceId,
                                                )
                                            }
                                            style={remoteButtonStyle}
                                            type="button"
                                        >
                                            <LuSkipBack size={14} />
                                        </button>
                                    </Tooltip>
                                    <Tooltip label={t('streamify.connect.commandNext')}>
                                        <button
                                            aria-label={t('streamify.connect.commandNext')}
                                            onClick={() =>
                                                sendTransportCommand(
                                                    'next',
                                                    device.presence.deviceId,
                                                )
                                            }
                                            style={remoteButtonStyle}
                                            type="button"
                                        >
                                            <LuSkipForward size={14} />
                                        </button>
                                    </Tooltip>
                                    <Tooltip label={t('streamify.connect.commandSeekLive')}>
                                        <button
                                            aria-label={t('streamify.connect.commandSeekLive')}
                                            onClick={() =>
                                                sendTransportCommand(
                                                    'seek',
                                                    device.presence.deviceId,
                                                    Math.floor(device.interpolatedPositionSec),
                                                )
                                            }
                                            style={remoteButtonStyle}
                                            type="button"
                                        >
                                            <Text size="xs">
                                                {formatChapterTime(device.interpolatedPositionSec)}
                                            </Text>
                                        </button>
                                    </Tooltip>
                                    <Tooltip label={t('streamify.connect.transferHere')}>
                                        <button
                                            aria-label={t('streamify.connect.transferHere')}
                                            onClick={() => {
                                                requestHandoff(device.presence.deviceId);
                                                setOpened(false);
                                            }}
                                            style={{
                                                ...remoteButtonStyle,
                                                borderColor: 'var(--theme-colors-primary)',
                                                color: 'var(--theme-colors-primary)',
                                            }}
                                            type="button"
                                        >
                                            <Text size="xs" weight={600}>
                                                {t('streamify.connect.transferHereShort')}
                                            </Text>
                                        </button>
                                    </Tooltip>
                                </Group>
                            </Stack>
                        ))
                    )}
                    <Text isMuted size="xs">
                        {t('streamify.connect.footnote')}
                    </Text>
                </Stack>
            </Popover.Dropdown>
        </Popover>
    );
};
