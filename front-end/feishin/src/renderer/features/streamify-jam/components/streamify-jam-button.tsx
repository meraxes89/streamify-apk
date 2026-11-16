/**
 * features/streamify-jam/components/streamify-jam-button.tsx - the
 * player-bar trigger for the Streamify suite (P4B).
 *
 * One upstream hook point (right-controls.tsx renders `<StreamifyJamButton
 * />`, two lines, logged in PATCHES.md) and everything else lives here:
 *
 *   - mounts the Jam session controller (singleton, survives drawer close),
 *   - mounts the EQ DSP graph manager (runs whenever web playback runs),
 *   - mounts the brand-theme default hook,
 *   - renders the radio icon (badge dot while a session is live) and the
 *     right-side drawer (Jam room + equalizer tabs).
 *
 * Only rendered for Streamify sessions; stock servers never see it.
 */

import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useStreamifyEqGraph } from '/@/renderer/features/streamify-eq/hooks/use-streamify-eq-graph';
import { JamDrawer } from '/@/renderer/features/streamify-jam/components/jam-drawer';
import { useStreamifyJamController } from '/@/renderer/features/streamify-jam/hooks/use-streamify-jam-controller';
import { useStreamifyBrandTheme } from '/@/renderer/features/streamify-settings/hooks/use-streamify-brand-theme';
import { useAuthStore } from '/@/renderer/store';
import { useStreamifyJamStore } from '/@/renderer/store/streamify-jam.store';
import { ActionIcon } from '/@/shared/components/action-icon/action-icon';
import { Badge } from '/@/shared/components/badge/badge';
import { Drawer } from '/@/shared/components/drawer/drawer';
import { ServerType } from '/@/shared/types/types';

export const StreamifyJamButton = () => {
    const { t } = useTranslation();
    const [opened, setOpened] = useState(false);
    const [tab, setTab] = useState<'eq' | 'jam'>('jam');

    const isStreamify = useAuthStore((state) => state.currentServer?.type) === ServerType.STREAMIFY;

    // Hooks mount whenever a Streamify session is active; each is a no-op
    // for stock servers (the button itself is not rendered then).
    const controller = useStreamifyJamController();
    useStreamifyEqGraph();
    useStreamifyBrandTheme();

    const status = useStreamifyJamStore((state) => state.status);
    const eqQuickLinkCounter = useStreamifyJamStore((state) => state.eqQuickLinkCounter);
    const eqQuickLinkRef = useRef(eqQuickLinkCounter);

    // The settings page's EQ quick-link opens the drawer on the EQ tab.
    useEffect(() => {
        if (eqQuickLinkCounter !== eqQuickLinkRef.current) {
            eqQuickLinkRef.current = eqQuickLinkCounter;
            setTab('eq');
            setOpened(true);
        }
    }, [eqQuickLinkCounter]);

    if (!isStreamify) {
        return null;
    }

    return (
        <>
            <div style={{ position: 'relative' }}>
                <ActionIcon
                    icon="radio"
                    onClick={(event) => {
                        event.stopPropagation();
                        setOpened(true);
                    }}
                    size="sm"
                    tooltip={{ label: t('streamify.jam.buttonTooltip'), openDelay: 0 }}
                    variant="subtle"
                />
                {status === 'live' || status === 'connecting' ? (
                    <Badge
                        circle
                        color="primary"
                        p={0}
                        size="6px"
                        style={{ position: 'absolute', right: 3, top: 3 }}
                        variant="filled"
                    />
                ) : null}
            </div>
            <Drawer
                onClose={() => setOpened(false)}
                opened={opened}
                position="right"
                size="lg"
                title={t('streamify.jam.drawerTitle')}
                withinPortal
            >
                <JamDrawer controller={controller} onTabChange={setTab} tab={tab} />
            </Drawer>
        </>
    );
};
