/**
 * features/streamify-eq/hooks/use-streamify-eq-graph.ts - the EQ DSP graph
 * manager (P4B): builds the Streamify chain downstream of the per-slot gain
 * nodes and keeps it live, with a zero-output watchdog and a clean bypass.
 *
 * THE GRAPH (Web Audio):
 *
 *   <audio> x2 -> MediaElementSource -> gains[0..1] (upstream, untouched)
 *       gains -> preampGain -> 10x peaking BiquadFilter -> limiter
 *       limiter -> context.destination
 *       limiter -> probe AnalyserNode (watchdog only, a sink)
 *
 * The rebuild replaces `webAudio.dsp` wholesale through the app's own
 * WebAudioContext setter, so the upstream EQ settings panel (which mutates
 * whatever dsp is current) and the visualizer tap (which reads
 * dsp.compressor) keep working against our nodes - same shapes, better
 * chain.
 *
 * THE CORS GUARDRAIL (defense in depth):
 *
 *   1. crossOrigin='anonymous' is already set on both media elements
 *      (upstream since the fork base), so a CORS-unclean rung fails LOUDLY
 *      at load and the P2-A source-error ladder rotates - audio never
 *      silently mutes through a tainted MediaElementAudioSourceNode.
 *   2. The watchdog probes the chain output once per second: while playback
 *      is running, unmuted, and advancing, three consecutive all-zero
 *      probes engage the BYPASS - gains rewire straight to
 *      context.destination, the DSP chain leaves the path completely, and
 *      audio keeps flowing.
 *   3. Each track change retries the full graph once (the next rung may be
 *      CORS-clean); a repeat strike within the same track latches the
 *      bypass until the track changes again.
 */

import { useEffect, useRef } from 'react';

import { useWebAudio } from '/@/renderer/features/player/hooks/use-webaudio';
import { eqBandsForEngine } from '/@/renderer/features/streamify-eq/utils/eq-math';
import { usePlayerStoreBase } from '/@/renderer/store/player.store';
import { useSettingsStore } from '/@/renderer/store/settings.store';
import { useStreamifyEqStoreBase } from '/@/renderer/store/streamify-eq.store';
import { useTimestampStoreBase } from '/@/renderer/store/timestamp.store';
import { logger } from '/@/renderer/utils/logger';
import { PlayerStatus } from '/@/shared/types/types';

interface EqChainState {
    bypassed: boolean;
    filters: BiquadFilterNode[];
    limiter: DynamicsCompressorNode;
    preampGain: GainNode;
    probe: AnalyserNode;
    retriesThisTrack: number;
    trackKey: null | string;
}

const WATCHDOG_INTERVAL_MS = 1_000;
const WATCHDOG_STRIKES = 3;
const WATCHDOG_MIN_POSITION_MS = 2_000;

const dbToGain = (db: number): number => Math.pow(10, db / 20);

export const useStreamifyEqGraph = (): void => {
    const { setWebAudio, webAudio } = useWebAudio();
    const chainRef = useRef<EqChainState | null>(null);
    const strikesRef = useRef(0);
    const lastPositionRef = useRef(0);

    const context = webAudio?.context ?? null;
    const gains = webAudio?.gains ?? null;
    const contextActive = Boolean(context && gains && gains.length > 0);

    // -------------------------------------------------------------------------
    // Build / rebuild the chain when the audio context identity changes
    // -------------------------------------------------------------------------
    useEffect(() => {
        if (!context || !gains || gains.length === 0 || !setWebAudio || !webAudio) {
            return undefined;
        }

        const { playback } = useSettingsStore.getState();
        const eq = playback.equalizer;

        // Tear down whatever chain currently sits downstream of the gains
        // (the upstream builder's, or a previous instance of ours).
        for (const gain of gains) {
            try {
                gain.disconnect();
            } catch {
                // already detached
            }
        }
        if (webAudio.dsp) {
            try {
                webAudio.dsp.preampGain.disconnect();
                for (const filter of webAudio.dsp.eqFilters) filter.disconnect();
                webAudio.dsp.compressor.disconnect();
            } catch {
                // already detached
            }
        }

        // The Streamify chain.
        const preampGain = context.createGain();
        preampGain.gain.value = eq.enabled ? dbToGain(eq.preamp) : 1;

        const bands = eqBandsForEngine(
            eq.bands.length > 0
                ? {
                      bands: eq.bands as Array<{ freq: number; gain: number }>,
                      id: '',
                      name: '',
                      preamp: eq.preamp,
                  }
                : null,
        );
        const filters = bands.map((band) => {
            const filter = context.createBiquadFilter();
            filter.type = 'peaking';
            filter.frequency.value = band.freq;
            filter.Q.value = band.q ?? 1.41;
            filter.gain.value = eq.enabled ? band.gain : 0;
            return filter;
        });

        const limiter = context.createDynamicsCompressor();
        if (eq.enabled) {
            // Safety limiter: catches preamp + boost clipping.
            limiter.threshold.value = -1;
            limiter.knee.value = 0;
            limiter.ratio.value = 20;
            limiter.attack.value = 0.003;
            limiter.release.value = 0.1;
        } else {
            // Mathematically transparent (mirrors the upstream pattern).
            limiter.threshold.value = 0;
            limiter.ratio.value = 1;
            limiter.attack.value = 0;
            limiter.release.value = 0.25;
            limiter.knee.value = 0;
        }

        for (const gain of gains) {
            gain.connect(preampGain);
        }
        if (filters.length > 0) {
            preampGain.connect(filters[0]);
            for (let i = 0; i < filters.length - 1; i += 1) {
                filters[i].connect(filters[i + 1]);
            }
            filters[filters.length - 1].connect(limiter);
        } else {
            preampGain.connect(limiter);
        }
        limiter.connect(context.destination);

        const probe = context.createAnalyser();
        probe.fftSize = 512;
        limiter.connect(probe);

        chainRef.current = {
            bypassed: false,
            filters,
            limiter,
            preampGain,
            probe,
            retriesThisTrack: 0,
            trackKey: null,
        };
        strikesRef.current = 0;
        useStreamifyEqStoreBase.getState().setGraphActive(true);
        useStreamifyEqStoreBase.getState().setBypassed(false);

        setWebAudio({ ...webAudio, dsp: { compressor: limiter, eqFilters: filters, preampGain } });

        return () => {
            useStreamifyEqStoreBase.getState().setGraphActive(false);
        };
        // The context identity (not the wrapper object) is the rebuild key:
        // setWebAudio below swaps the wrapper and must NOT retrigger.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [context, contextActive, gains, setWebAudio]);

    // -------------------------------------------------------------------------
    // Live settings application (both UIs mutate settings.playback.equalizer)
    // -------------------------------------------------------------------------
    useEffect(() => {
        const unsubscribe = useSettingsStore.subscribe(
            (state) => state.playback.equalizer,
            (equalizer) => {
                const chain = chainRef.current;
                if (!chain) return;
                chain.preampGain.gain.value = equalizer.enabled ? dbToGain(equalizer.preamp) : 1;
                const bands = eqBandsForEngine(
                    equalizer.bands.length > 0
                        ? {
                              bands: equalizer.bands as Array<{ freq: number; gain: number }>,
                              id: '',
                              name: '',
                              preamp: equalizer.preamp,
                          }
                        : null,
                );
                // Re-shape the live filter bank when the band count changes
                // (e.g. first switch from the upstream 12-band default).
                for (let i = 0; i < chain.filters.length; i += 1) {
                    const filter = chain.filters[i];
                    const band = bands[i];
                    if (!band) {
                        filter.gain.value = 0;
                        continue;
                    }
                    filter.frequency.value = band.freq;
                    filter.Q.value = band.q ?? 1.41;
                    filter.gain.value = equalizer.enabled ? band.gain : 0;
                }
                if (equalizer.enabled) {
                    chain.limiter.threshold.value = -1;
                    chain.limiter.knee.value = 0;
                    chain.limiter.ratio.value = 20;
                    chain.limiter.attack.value = 0.003;
                    chain.limiter.release.value = 0.1;
                } else {
                    chain.limiter.threshold.value = 0;
                    chain.limiter.ratio.value = 1;
                    chain.limiter.attack.value = 0;
                    chain.limiter.release.value = 0.25;
                    chain.limiter.knee.value = 0;
                }
            },
        );
        return unsubscribe;
    }, []);

    // -------------------------------------------------------------------------
    // Watchdog + track-change retry
    // -------------------------------------------------------------------------
    useEffect(() => {
        if (!context || !gains) {
            return undefined;
        }

        const isAudiblyPlaying = (): { advancing: boolean; playing: boolean } => {
            const player = usePlayerStoreBase.getState().player;
            const positionMs = useTimestampStoreBase.getState().timestamp * 1000;
            const advancing = Math.abs(positionMs - lastPositionRef.current) > 100;
            lastPositionRef.current = positionMs;
            const playing =
                player.status === PlayerStatus.PLAYING &&
                !player.muted &&
                player.volume > 0 &&
                positionMs >= WATCHDOG_MIN_POSITION_MS;
            return { advancing, playing };
        };

        const probeOutputSilent = (): boolean => {
            const chain = chainRef.current;
            if (!chain || chain.bypassed) return false;
            const buffer = new Float32Array(chain.probe.fftSize);
            chain.probe.getFloatTimeDomainData(buffer);
            for (let i = 0; i < buffer.length; i += 1) {
                if (buffer[i] !== 0) {
                    return false;
                }
            }
            return true;
        };

        const engageBypass = (reason: string): void => {
            const chain = chainRef.current;
            if (!chain || chain.bypassed) return;
            for (const gain of gains) {
                try {
                    gain.disconnect();
                } catch {
                    // already detached
                }
                gain.connect(context.destination);
            }
            chain.bypassed = true;
            useStreamifyEqStoreBase.getState().setBypassed(true, reason);
            logger.warn('Streamify EQ engaged hard bypass', { reason });
        };

        const restoreGraph = (): void => {
            const chain = chainRef.current;
            if (!chain || !chain.bypassed) return;
            for (const gain of gains) {
                try {
                    gain.disconnect();
                } catch {
                    // already detached
                }
                gain.connect(chain.preampGain);
            }
            chain.bypassed = false;
            chain.retriesThisTrack += 1;
            strikesRef.current = 0;
            useStreamifyEqStoreBase.getState().setBypassed(false);
        };

        const watchdog = setInterval(() => {
            const chain = chainRef.current;
            if (!chain) return;
            const { advancing, playing } = isAudiblyPlaying();
            if (!playing || !advancing) {
                strikesRef.current = 0;
                return;
            }
            if (!probeOutputSilent()) {
                strikesRef.current = 0;
                return;
            }
            strikesRef.current += 1;
            if (strikesRef.current >= WATCHDOG_STRIKES) {
                engageBypass('silent-output');
            }
        }, WATCHDOG_INTERVAL_MS);

        // One retry per track change: the next track's rung may be CORS-clean.
        const unsubscribeTrack = usePlayerStoreBase.subscribe((state) => {
            const key = state.getQueue().items[state.player.index]?.id ?? null;
            const chain = chainRef.current;
            if (!chain || key === chain.trackKey) return;
            chain.trackKey = key;
            chain.retriesThisTrack = 0;
            if (chain.bypassed) {
                restoreGraph();
            }
        });

        return () => {
            clearInterval(watchdog);
            unsubscribeTrack();
        };
    }, [context, gains]);
};
