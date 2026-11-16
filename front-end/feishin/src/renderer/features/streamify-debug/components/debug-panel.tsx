/**
 * features/streamify-debug/components/debug-panel.tsx — P4-A: one
 * read-only diagnostics section (title + optional header action + rows).
 */

import type { ReactNode } from 'react';

import { Paper } from '/@/shared/components/paper/paper';
import { Stack } from '/@/shared/components/stack/stack';
import { Text } from '/@/shared/components/text/text';

export interface DebugPanelProps {
    action?: ReactNode;
    children: ReactNode;
    title: string;
}

export const DebugPanel = ({ action, children, title }: DebugPanelProps) => (
    <Paper p="md" shadow="none" withBorder>
        <Stack gap="xs">
            {action !== undefined ? (
                <Stack
                    style={{
                        alignItems: 'center',
                        display: 'flex',
                        justifyContent: 'space-between',
                        width: '100%',
                    }}
                >
                    <Text fw={600} size="md">
                        {title}
                    </Text>
                    {action}
                </Stack>
            ) : (
                <Text fw={600} size="md">
                    {title}
                </Text>
            )}
            {children}
        </Stack>
    </Paper>
);
