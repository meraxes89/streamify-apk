/**
 * features/streamify-eq/components/eq-curve.tsx - the interactive
 * frequency-response curve (P4B).
 *
 * Pure presentation: a log-frequency SVG rendering of eqFrequencyResponse
 * (the same math the DSP chain implements), grid lines at the ISO band
 * centers, and a clamped +/-15 dB viewport.
 */

import { useMemo } from 'react';

import { type EqBand, eqFrequencyResponse } from '/@/renderer/features/streamify-eq/utils/eq-math';

const VIEW_HEIGHT = 100;
const VIEW_WIDTH = 100;
const DB_VIEW = 15;
const F_MIN = 20;
const F_MAX = 20_000;

const GRID_FREQS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];

const labelFor = (freq: number): string => (freq >= 1000 ? `${freq / 1000}k` : `${freq}`);

export const EqCurve = ({
    bands,
    preamp,
    sampleRate,
}: {
    bands: EqBand[];
    preamp: number;
    sampleRate?: number;
}) => {
    const { areaPath, linePath } = useMemo(() => {
        const response = eqFrequencyResponse(bands, preamp, {
            fMax: F_MAX,
            fMin: F_MIN,
            points: 96,
            sampleRate,
        });
        const points = response.map(({ db, freq }) => {
            const x = (Math.log10(freq / F_MIN) / Math.log10(F_MAX / F_MIN)) * VIEW_WIDTH;
            const clamped = Math.max(-DB_VIEW, Math.min(DB_VIEW, db));
            const y = VIEW_HEIGHT / 2 - (clamped / DB_VIEW) * (VIEW_HEIGHT / 2 - 4);
            return `${x.toFixed(2)},${y.toFixed(2)}`;
        });
        return {
            areaPath: `M0,${VIEW_HEIGHT / 2} L${points.join(' L')} L${VIEW_WIDTH},${VIEW_HEIGHT / 2} Z`,
            linePath: `M${points.join(' L')}`,
        };
    }, [bands, preamp, sampleRate]);

    const zeroY = VIEW_HEIGHT / 2;

    return (
        <svg
            aria-label="EQ frequency response"
            preserveAspectRatio="none"
            role="img"
            viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
        >
            {/* dB grid */}
            {[-12, -6, 6, 12].map((db) => {
                const y = zeroY - (db / DB_VIEW) * (VIEW_HEIGHT / 2 - 4);
                return (
                    <line
                        key={db}
                        style={{ stroke: 'var(--theme-colors-surface)', strokeWidth: 0.4 }}
                        x1={0}
                        x2={VIEW_WIDTH}
                        y1={y}
                        y2={y}
                    />
                );
            })}
            {/* ISO band centers */}
            {GRID_FREQS.map((freq) => {
                const x = (Math.log10(freq / F_MIN) / Math.log10(F_MAX / F_MIN)) * VIEW_WIDTH;
                return (
                    <g key={freq}>
                        <line
                            style={{ stroke: 'var(--theme-colors-surface)', strokeWidth: 0.25 }}
                            x1={x}
                            x2={x}
                            y1={0}
                            y2={VIEW_HEIGHT}
                        />
                        <text
                            fill="var(--theme-colors-foreground-muted)"
                            fontSize={3.4}
                            textAnchor="middle"
                            x={x}
                            y={VIEW_HEIGHT - 1.5}
                        >
                            {labelFor(freq)}
                        </text>
                    </g>
                );
            })}
            {/* 0 dB line */}
            <line
                style={{ stroke: 'var(--theme-colors-foreground-muted)', strokeWidth: 0.5 }}
                x1={0}
                x2={VIEW_WIDTH}
                y1={zeroY}
                y2={zeroY}
            />
            {/* response */}
            <path d={areaPath} fill="var(--theme-colors-primary)" opacity={0.15} />
            <path d={linePath} fill="none" stroke="var(--theme-colors-primary)" strokeWidth={0.9} />
        </svg>
    );
};
