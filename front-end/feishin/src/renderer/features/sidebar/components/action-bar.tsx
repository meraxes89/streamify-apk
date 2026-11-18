import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import styles from './action-bar.module.css';

import { useScanStatus } from '/@/renderer/features/shared/hooks/use-scan-status';
import { AppMenu } from '/@/renderer/features/titlebar/components/app-menu';
import { AppRoute } from '/@/renderer/router/routes';
import { useCommandPalette } from '/@/renderer/store';
import { Button } from '/@/shared/components/button/button';
import { DropdownMenu } from '/@/shared/components/dropdown-menu/dropdown-menu';
import { Grid } from '/@/shared/components/grid/grid';
import { Group } from '/@/shared/components/group/group';
import { Icon } from '/@/shared/components/icon/icon';
import { TextInput } from '/@/shared/components/text-input/text-input';

export const ActionBar = () => {
    const { t } = useTranslation();
    const { open } = useCommandPalette();
    const { isScanning } = useScanStatus();
    const navigate = useNavigate();

    return (
        <div className={styles.container}>
            <Grid
                display="flex"
                gap="xs"
                styles={{
                    inner: {
                        width: '100%',
                    },
                    root: {
                        padding: '0 var(--theme-spacing-md)',
                        width: '100%',
                    },
                }}
            >
                <Grid.Col span={6}>
                    <TextInput
                        leftSection={<Icon icon="search" />}
                        onClick={open}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                                open();
                            }
                        }}
                        placeholder={t('common.search')}
                        readOnly
                    />
                </Grid.Col>
                <Grid.Col span={6}>
                    <Group gap="xs" grow wrap="nowrap">
                        <Button
                            onClick={() => navigate(AppRoute.STREAMIFY_SETTINGS)}
                            p="0"
                            tooltip={{ label: 'Streamify Settings', position: 'bottom' }}
                        >
                            <Icon icon="settings" size="lg" />
                        </Button>
                        <DropdownMenu position="bottom-start">
                            <DropdownMenu.Target>
                                <Button p="0">
                                    <Icon
                                        animate={isScanning ? 'spin' : undefined}
                                        icon={isScanning ? 'spinner' : 'menu'}
                                        size="lg"
                                    />
                                </Button>
                            </DropdownMenu.Target>
                            <DropdownMenu.Dropdown>
                                <AppMenu />
                            </DropdownMenu.Dropdown>
                        </DropdownMenu>
                        <NavigateButtons />
                    </Group>
                </Grid.Col>
            </Grid>
        </div>
    );
};

const NavigateButtons = () => {
    const navigate = useNavigate();

    return (
        <>
            <Button onClick={() => navigate(-1)} p="0">
                <Icon icon="arrowLeftS" size="lg" />
            </Button>
            <Button onClick={() => navigate(1)} p="0">
                <Icon icon="arrowRightS" size="lg" />
            </Button>
        </>
    );
};
