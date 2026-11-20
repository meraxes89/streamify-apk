import { Readable } from 'node:stream';
import { SabrStream } from 'googlevideo/sabr-stream';
import { getDefaultBufferPool } from '../../lib/buffer-pool.js';

/**
 * L4 SABR Streaming Engine (comm10.1.md Frontier 4)
 * 
 * - Handles YouTube Universal Media Pipe (UMP) / SABR binary protocol
 * - Demuxes fragmented MP4 (fMP4) and WebM audio segments without re-encoding
 * - Generates seekable progressive streams and DASH MPD manifests
 */
export class SabrEngine {
  constructor({ bufferPool = getDefaultBufferPool() } = {}) {
    this.bufferPool = bufferPool;
    this.activeStreams = new Set();
    this.metrics = {
      totalSabrStreams: 0,
      totalSegmentsRemuxed: 0,
    };
  }

  /**
   * Determine if player response contains or requires SABR streaming.
   * @param {Object} data - Player response data
   * @returns {boolean}
   */
  isSabrRequired(data) {
    if (!data) return false;
    if (data.streamingData?.serverAbrStreamingUrl) return true;

    const formats = [
      ...(data.streamingData?.adaptiveFormats || []),
      ...(data.streamingData?.formats || []),
    ];

    // If no direct progressive URLs exist across all audio formats
    const hasProgressive = formats.some(
      (f) => (f.mimeType || '').startsWith('audio/') && (f.url || f.signatureCipher)
    );
    return !hasProgressive && formats.length > 0;
  }

  /**
   * Create a continuous Node.js Readable stream from a SABR upstream session.
   * 
   * @param {Object} options
   * @param {string} options.videoId
   * @param {string} options.serverAbrStreamingUrl
   * @param {string} [options.videoPlaybackUstreamerConfig]
   * @param {Array} options.formats
   * @param {string} [options.poToken]
   * @param {Function} [options.onMintPoToken]
   * @returns {{ stream: Readable, itag: number, mimeType: string }}
   */
  createStream({
    videoId,
    serverAbrStreamingUrl,
    videoPlaybackUstreamerConfig = '',
    formats = [],
    poToken = null,
    onMintPoToken = null,
  }) {
    // Select best audio format: Opus (251) preferred over AAC (140)
    let selectedAudio = formats.find((f) => f.itag === 251);
    if (!selectedAudio) selectedAudio = formats.find((f) => f.itag === 140);
    if (!selectedAudio) selectedAudio = formats.find((f) => (f.mimeType || '').startsWith('audio/')) || formats[0];

    const config = {
      videoId,
      serverAbrStreamingUrl,
      videoPlaybackUstreamerConfig,
      clientInfo: {
        clientName: 67, // WEB_REMIX
        clientVersion: '1.20240101.01.00',
      },
      formats: [selectedAudio],
      poToken: poToken || undefined,
      callbacks: {},
    };

    if (onMintPoToken) {
      config.callbacks.onMintPoToken = async () => {
        const token = await onMintPoToken();
        return Buffer.from(token, 'utf8');
      };
    }

    const sabr = new SabrStream(config);
    this.activeStreams.add(sabr);
    this.metrics.totalSabrStreams++;

    const startResult = sabr.start({
      audioFormat: selectedAudio,
    });

    // Convert Web ReadableStream to Node.js Readable
    const nodeStream = Readable.fromWeb(startResult.audioStream);

    nodeStream.on('close', () => {
      this.activeStreams.delete(sabr);
      sabr.abort().catch(() => {});
    });

    nodeStream.on('data', () => {
      this.metrics.totalSegmentsRemuxed++;
    });

    const isOpus = (selectedAudio.mimeType || '').includes('opus');
    const mimeType = isOpus ? 'audio/webm; codecs="opus"' : 'audio/mp4; codecs="mp4a.40.2"';

    return {
      stream: nodeStream,
      itag: selectedAudio.itag || 251,
      mimeType,
      sabrSession: sabr,
    };
  }

  /**
   * Generate dynamic DASH MPD XML manifest for media3 / Shaka Player clients.
   * 
   * @param {Object} options
   * @param {string} options.videoId
   * @param {number} [options.durationSec=0]
   * @param {Array} options.formats
   * @param {string} [options.baseUrl='']
   * @returns {string} XML string
   */
  generateDashMpd({ videoId, durationSec = 180, formats = [], baseUrl = '' }) {
    const audioFormats = formats.filter((f) => (f.mimeType || '').startsWith('audio/'));
    const durationIso = `PT${Math.floor(durationSec)}S`;

    let representationsXml = '';
    for (const f of audioFormats) {
      const isOpus = (f.mimeType || '').includes('opus');
      const codecs = isOpus ? 'opus' : 'mp4a.40.2';
      const mime = isOpus ? 'audio/webm' : 'audio/mp4';
      const bitrate = f.bitrate || (isOpus ? 160000 : 128000);
      const sampleRate = f.audioSampleRate || '48000';

      representationsXml += `
      <Representation id="${f.itag}" mimeType="${mime}" codecs="${codecs}" bandwidth="${bitrate}" audioSamplingRate="${sampleRate}">
        <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>
        <BaseURL>${baseUrl}/api/stream/${videoId}?itag=${f.itag}</BaseURL>
      </Representation>`;
    }

    return `<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
     profiles="urn:mpeg:dash:profile:isoff-on-demand:2011"
     type="static"
     mediaPresentationDuration="${durationIso}"
     minBufferTime="PT1.5S">
  <Period id="0" start="PT0S">
    <AdaptationSet id="0" contentType="audio" subsegmentAlignment="true">${representationsXml}
    </AdaptationSet>
  </Period>
</MPD>`.trim();
  }

  getStatus() {
    return {
      activeStreams: this.activeStreams.size,
      totalSabrStreams: this.metrics.totalSabrStreams,
      totalSegmentsRemuxed: this.metrics.totalSegmentsRemuxed,
    };
  }

  close() {
    for (const s of this.activeStreams) {
      try {
        s.abort().catch(() => {});
      } catch {}
    }
    this.activeStreams.clear();
  }
}
