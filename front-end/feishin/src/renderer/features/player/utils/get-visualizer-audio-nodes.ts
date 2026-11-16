import type { WebAudio } from '/@/shared/types/types';

import { PlayerType } from '/@/shared/types/types';

export function getVisualizerAudioNodes(
    webAudio: undefined | WebAudio,
    playbackType: PlayerType,
): AudioNode[] {
    if (!webAudio) return [];
    if (playbackType === PlayerType.LOCAL) {
        return webAudio.visualizerInputs ?? [];
    }
    // P4B (Streamify, thin seam): tap POST-DSP so the analysers see the
    // equalized/limited signal (both slots merge into the DSP chain before
    // this node, so crossfades are covered by the single tap). Falls back
    // to the pre-EQ gains whenever no DSP chain is attached.
    if (webAudio.dsp) {
        return [webAudio.dsp.compressor];
    }
    return webAudio.gains;
}
