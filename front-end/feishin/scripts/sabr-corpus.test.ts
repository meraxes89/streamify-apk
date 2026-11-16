/**
 * scripts/sabr-corpus.test.ts — P5-A: the daily SABR corpus run, re-pointed
 * at the CANONICAL resolver tree.
 *
 * CI only (resolver-canary.yml → `pnpm run test:corpus`, never `pnpm test`
 * — vitest.config.ts includes src/** only); live network by design. Until
 * the P5-A cutover this runner lived in front-end/web and imported the
 * resolver from there; front-end/web is archived (legacy/front-end-web)
 * and this fork's tree is the single source of truth for resolution
 * behavior, so the corpus exercises exactly the code production ships:
 *
 *   front-end/feishin/src/renderer/api/streamify/playback/resolver/
 *
 * Corpus: the de-facto W1.2 corpus — the canary/g3b canonical IDs. For
 * every track: getStreamUrl → one instrumented record (protocol, rung,
 * itag, codec, rangeSupport, expiry). Report: per-track detail + the SABR
 * hit rate (sabr resolves / total resolves) — the number that decides the
 * custom engine checkpoint ("a week of data, then a joint call", per the
 * P2-A kickoff). A SABR-heavy day is DATA, not a failure — the only fatal
 * case is zero resolves at all (infra).
 *
 * Output: sabr-report.json (artifact) + $GITHUB_STEP_SUMMARY markdown.
 */

import { appendFileSync, writeFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import {
    getStreamUrl,
    recentResolveLog,
    type ResolveLogRecord,
} from '../src/renderer/api/streamify/playback/resolver/audio/get-stream-url';

const CORPUS: ReadonlyArray<{ id: string; label: string }> = [
    { id: 'dQw4w9WgXcQ', label: 'standard content' },
    { id: '4NRXx6U8ABQ', label: 'licensed (the G3b target)' },
    { id: 'tixIb21MO5U', label: 'licensed (the phone-failure case)' },
    { id: 'DOGP2dYUGE0', label: 'licensed (the G4 phone-failure case)' },
];

/** Same relay the production app rides (resolver-canary.yml RELAY_BASE_URL). */
const RELAY =
    process.env.RELAY_BASE_URL ?? 'https://streamify-relay.zephyr4289.workers.dev';

interface TrackOutcome {
    id: string;
    label: string;
    ok: boolean;
    error?: string;
    record?: ResolveLogRecord;
}

describe('SABR corpus (live — feishin canonical resolver)', () => {
    it('resolves the corpus and reports the SABR hit rate', async () => {
        const outcomes: TrackOutcome[] = [];
        for (const track of CORPUS) {
            try {
                const resolution = await getStreamUrl(track.id, { relayBase: RELAY });
                outcomes.push({
                    id: track.id,
                    label: track.label,
                    ok: true,
                    record: recentResolveLog().at(-1),
                });
                // Touch the fields the engine consumes so the report is complete.
                console.info(
                    `[corpus] ${track.id} (${track.label}): protocol=${resolution.protocol} ` +
                        `rung=${resolution.rung} itags=${resolution.ladder.map((r) => r.itag).join('/')} ` +
                        `range=${String(resolution.rangeSupport)} duration=${String(resolution.durationMs)}ms`,
                );
            } catch (err) {
                // An honest per-track failure is data, not a test failure.
                const message = err instanceof Error ? err.message : String(err);
                outcomes.push({ id: track.id, label: track.label, ok: false, error: message });
                console.warn(`[corpus] ${track.id} (${track.label}): FAILED — ${message}`);
            }
        }

        const resolved = outcomes.filter((o) => o.ok);
        const records = resolved
            .map((o) => o.record)
            .filter((r): r is ResolveLogRecord => r !== undefined);
        const progressive = records.filter((r) => r.protocol === 'progressive').length;
        const hls = records.filter((r) => r.protocol === 'hls').length;
        const sabr = records.filter((r) => r.protocol === 'sabr').length;
        const sabrRate = resolved.length > 0 ? sabr / resolved.length : 0;

        const report = {
            at: new Date().toISOString(),
            relay: RELAY,
            resolver: 'front-end/feishin (canonical, P5-A)',
            corpus: CORPUS,
            outcomes,
            summary: {
                total: outcomes.length,
                resolved: resolved.length,
                failed: outcomes.length - resolved.length,
                progressive,
                hls,
                sabr,
                sabrRate: Number(sabrRate.toFixed(4)),
            },
        };
        const reportPath = process.env.SABR_REPORT_PATH ?? 'sabr-report.json';
        writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
        console.info(`[corpus] report written: ${reportPath}`);

        const summaryMd = [
            '## P2-A SABR corpus — daily report (feishin canonical resolver)',
            '',
            `| metric | value |`,
            `| --- | --- |`,
            `| relay | \`${RELAY}\` |`,
            `| resolved | ${resolved.length}/${outcomes.length} |`,
            `| progressive | ${progressive} |`,
            `| hls | ${hls} |`,
            `| **sabr (hits)** | **${sabr}** |`,
            `| **sabr hit rate** | **${(sabrRate * 100).toFixed(1)}%** |`,
            '',
            'Per track:',
            '',
            ...outcomes.map(
                (o) =>
                    `- \`${o.id}\` (${o.label}): ${
                        o.ok
                            ? `${o.record?.protocol} via ${o.record?.rung}, itag ${o.record?.itag} (${o.record?.codec}), ladder depth ${o.record?.ladderDepth}, range=${String(o.record?.rangeSupport)}`
                            : `FAILED — ${o.error}`
                    }`,
            ),
            '',
        ].join('\n');
        console.info(summaryMd);
        if (process.env.GITHUB_STEP_SUMMARY !== undefined) {
            appendFileSync(process.env.GITHUB_STEP_SUMMARY, `${summaryMd}\n`);
        }

        // The only fatal case: nothing resolved at all (infra, not data).
        expect(
            resolved.length,
            'zero corpus resolves — infra failure (relay/egress), not a SABR result',
        ).toBeGreaterThan(0);
    });
});
