#!/usr/bin/env node
/**
 * Standalone CLI for Amazon Catalog Engine (Full Expanded Suite)
 *
 * AMAZON-FIRST POLICY (2026-11): stream resolution never silently bridges
 * to YouTube. `resolve`/`lossless` return Amazon-native artifacts: the REAL
 * DMLS full-song link graph (authenticated) or the REAL guest sample URL
 * (signed CloudFront MP3, anonymous). The YouTube bridge is the explicit
 * `resolve-youtube` command only.
 *
 * Usage:
 *   node cli.mjs probe                 Run live discovery probe (API 12)
 *   node cli.mjs session [territory]   Print current session metadata
 *   node cli.mjs home [territory]      Fetch Home Showcase & Editorial (API 1)
 *   node cli.mjs search <query>        Search catalog (API 2)
 *   node cli.mjs suggest <query>       Instant suggestions (API 3)
 *   node cli.mjs browse <id>           Browse album/playlist/artist/podcast (API 4)
 *   node cli.mjs next <asin>           Fetch autoplay queue (API 5)
 *   node cli.mjs thumb <url> [size]    Scale artwork thumbnail (API 6)
 *   node cli.mjs scrape <url>          Scrape external playlist link (API 7)
 *   node cli.mjs lyrics <song> <band>  Fetch synced lyrics via LRCLIB (API 8)
 *   node cli.mjs resolve <title> [art] Resolve the REAL Amazon stream (native/sample)
 *   node cli.mjs sample <asin|title>   REAL guest sample URL (signed CloudFront MP3)
 *   node cli.mjs flac-links <asin> [flags] Solve REAL Amazon DMLS stream links (FLAC Ultra HD)
 *   node cli.mjs resolve-youtube <t> [a] EXPLICIT YouTube bridge (opt-in legacy path)
 *   node cli.mjs manifest <title>      Generate DASH MPD manifest (API 10)
 *   node cli.mjs prefetch <title>      Prefetch descriptor (API 11)
 *   node cli.mjs podcast <query>       Search and stream podcasts
 *   node cli.mjs station <seed>        Generate algorithmic radio station
 *   node cli.mjs badges <title>        Inspect audiophile quality badges
 *   node cli.mjs credits <title>       View track personnel & liner notes
 *   node cli.mjs videos <artist>       Find official music videos
 */

import fs from 'node:fs';
import { AmazonCatalogClient } from './src/client.mjs';

const args = process.argv.slice(2);
const command = args[0] || 'help';

const client = new AmazonCatalogClient();

async function main() {
  switch (command) {
    case 'probe': {
      await import('./probe.mjs');
      break;
    }

    case 'session': {
      const terr = (args[1] || 'US').toUpperCase();
      console.log(`Bootstrapping anonymous guest session for [${terr}]...`);
      const session = await client.session.getSession(terr);
      console.log(JSON.stringify({
        territory: session.territory,
        host: session.host,
        region: session.region,
        sessionId: session.sessionId,
        deviceId: session.deviceId,
        deviceType: session.deviceType,
        marketplaceId: session.marketplaceId,
        hasCsrf: !!session.csrf?.token,
        cookieCount: session.cookies ? session.cookies.split(';').length : 0,
        createdAt: new Date(session.createdAt).toISOString()
      }, null, 2));
      break;
    }

    case 'home': {
      const terr = (args[1] || 'US').toUpperCase();
      console.log(`Fetching Home Showcase for [${terr}]...`);
      const home = await client.getHome({ territory: terr });
      console.log(JSON.stringify(home, null, 2));
      break;
    }

    case 'search': {
      const query = args.slice(1).filter(a => !a.startsWith('--')).join(' ');
      if (!query) {
        console.error('Error: Please provide a search query.');
        process.exit(1);
      }
      console.log(`Searching for "${query}" on Amazon Music...`);
      const results = await client.search(query, { territory: 'US', limit: 5 });
      console.log(JSON.stringify(results, null, 2));
      break;
    }

    case 'suggest': {
      const query = args.slice(1).join(' ');
      if (!query) {
        console.error('Error: Please provide a suggest query.');
        process.exit(1);
      }
      const suggestions = await client.instantSearch(query);
      console.log(JSON.stringify(suggestions, null, 2));
      break;
    }

    case 'browse': {
      const id = args[1];
      if (!id) {
        console.error('Error: Please provide a browseId or ASIN.');
        process.exit(1);
      }
      const result = await client.getBrowse(id);
      console.log(JSON.stringify(result, null, 2));
      break;
    }

    case 'next': {
      const asin = args[1] || 'B01M11SBC8';
      console.log(`Resolving autoplay next queue for ASIN: ${asin}...`);
      const nextQueue = await client.getNext(asin);
      console.log(JSON.stringify(nextQueue, null, 2));
      break;
    }

    case 'thumb': {
      const url = args[1];
      const size = parseInt(args[2] || '500', 10);
      if (!url) {
        console.error('Error: Please provide an image URL.');
        process.exit(1);
      }
      console.log('Scaled Thumbnail URL:', client.getThumb(url, size));
      break;
    }

    case 'scrape': {
      const url = args[1];
      if (!url) {
        console.error('Error: Please provide a playlist URL.');
        process.exit(1);
      }
      console.log(`Scraping playlist: ${url}...`);
      const res = await client.scrapePlaylist(url);
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case 'lyrics': {
      const track = args[1];
      const artist = args[2] || '';
      if (!track) {
        console.error('Error: Please provide track name and artist.');
        process.exit(1);
      }
      console.log(`Fetching synced lyrics for "${track}" by "${artist}"...`);
      const lyrics = await client.getLyrics(track, artist);
      console.log(JSON.stringify(lyrics, null, 2));
      break;
    }

    case 'resolve': {
      // AMAZON-FIRST: real DMLS links (authenticated) or the real guest
      // sample URL. --bridge opts into the explicit YouTube path.
      const trackName = args[1];
      const artistName = args[2] || '';
      if (!trackName) {
        console.error('Error: Please provide track title.');
        process.exit(1);
      }
      const allowBridge = args.includes('--bridge');
      console.log(`Resolving REAL Amazon audio stream for "${trackName}" by "${artistName}"...`);
      const resolved = await client.resolveAudio({ title: trackName, artist: artistName }, { allowYoutubeBridge: allowBridge });

      console.log('\n=================== AMAZON STREAM RESOLUTION ===================');
      console.log(` Source        : ${resolved.source}${resolved.explicitBridge ? ' (EXPLICIT opt-in)' : ''}`);
      console.log(` ASIN          : ${resolved.asin || '(none)'}`);
      console.log(` Stream URL    : ${resolved.streamUrl || '(none)'}`);
      if (resolved.previewOnly) {
        console.log(` Preview scope : ~30s @ 64 kbps MP3 (expires ${new Date((resolved.expiresAt || 0) * 1000).toISOString()})`);
        console.log(` Full song     : AUTH_REQUIRED - supply Amazon Music cookies to unlock (see recipe below)`);
      }
      if (resolved.representation) {
        console.log(` Rendition     : ${resolved.specLabel} (tier=${resolved.tier}, codec=${resolved.codec})`);
      }
      if (resolved.drm?.protected) {
        console.log(` DRM           : ${resolved.drm.envelope} - bytes are CENC-encrypted; engine never decrypts`);
      }
      if (resolved.native?.status) {
        console.log(` Native verdict: ${resolved.native.status}`);
        if (resolved.native.reason) console.log(`   reason      : ${String(resolved.native.reason).slice(0, 140)}`);
        if (resolved.native.howToAuthenticate?.recipe) {
          console.log(`   unlock     :`);
          for (const step of resolved.native.howToAuthenticate.recipe) console.log(`     - ${step}`);
        }
      }
      console.log('==================================================================\n');
      console.log(JSON.stringify(resolved, null, 2));
      break;
    }

    case 'sample': {
      // The REAL guest vector: getStreamingURLsForSamples with the registered
      // 'WebCP' client -> signed CloudFront MP3 URLs, anonymously.
      //   node cli.mjs sample <asin|title> [artist] [--territory=US]
      //        [--out=file.mp3] [--verify]
      const target = args[1];
      if (!target || target.startsWith('--')) {
        console.error('Usage: node cli.mjs sample <asin|title> [artist] [--territory=US] [--out=file.mp3] [--verify]');
        process.exit(1);
      }
      const getFlag = (name) => {
        const f = args.find(a => a.startsWith(`--${name}=`));
        return f ? f.split('=').slice(1).join('=') : null;
      };
      const hasFlag = (name) => args.includes(`--${name}`);
      const territory = (getFlag('territory') || 'US').toUpperCase();
      const looksLikeAsin = /^[A-Z0-9]{10}$/.test(target);

      console.log(`Solving REAL Amazon sample URL (guest vector) for ${looksLikeAsin ? 'ASIN ' + target : '"' + target + '"'} [${territory}]...`);
      const sample = looksLikeAsin
        ? await client.dmlsSolver.solveSampleUrl({ asin: target, territory })
        : await client.getSampleStreamUrl({ title: target, artist: args[2] || '' }, { territory });

      console.log(`\n=================== AMAZON SAMPLE URL (guest) ===================`);
      console.log(` Status   : ${sample.status}`);
      console.log(` URL      : ${sample.url || '(none)'}`);
      if (sample.reason) console.log(` Reason   : ${sample.reason}`);
      if (sample.expiresAt) console.log(` Expires  : ${new Date(sample.expiresAt * 1000).toISOString()}`);
      console.log('==================================================================\n');

      if (sample.url && hasFlag('verify')) {
        const v = await client.dmlsSolver.verifyLinkReachability(sample.url);
        console.log(` Reachability : ${v.reachable ? 'LIVE (HTTP ' + v.httpStatus + ')' : 'DOWN'}${v.contentType ? ' | ' + v.contentType : ''}${v.totalBytes ? ' | ' + v.totalBytes + ' bytes' : ''}`);
        if (v.firstBytesHex) console.log(` First bytes  : ${v.firstBytesHex} (ID3 detected: ${v.firstBytesHex.startsWith('494433') ? 'yes - MP3 tag' : 'no'})`);
      }

      const out = getFlag('out');
      if (out && sample.url) {
        const { HttpAudioSource } = await import('./src/audio-source.mjs');
        const audio = await new HttpAudioSource().load({ streamUrl: sample.url });
        fs.writeFileSync(out, audio.bytes);
        console.log(`\nWrote ${audio.bytes.length} bytes -> ${out}`);
        console.log(` Honest sniff : codec=${audio.codec.codec} lossless=${audio.codec.lossless} (${audio.codec.detail})`);
        console.log(` Content-Type : ${audio.contentType}`);
      }

      console.log(JSON.stringify({ ...sample, track: undefined }, null, 2));
      break;
    }

    case 'resolve-youtube': {
      // The EXPLICIT YouTube bridge (legacy hybrid path). Opt-in only - the
      // default `resolve` never routes here.
      const trackName = args[1];
      const artistName = args[2] || '';
      if (!trackName) {
        console.error('Error: Please provide track title.');
        process.exit(1);
      }
      console.log(`Resolving via the EXPLICIT YouTube bridge (opt-in) for "${trackName}" by "${artistName}"...`);
      const resolved = await client.resolveAudioViaYoutubeBridge({ title: trackName, artist: artistName });
      console.log(JSON.stringify(resolved, null, 2));
      break;
    }

    case 'flac-links': {
      // THE REAL Amazon stream-link solver: DMLS getDashManifestsV2 (FLAC/Opus
      // path, SIREN_KATANA + TRACK_PSSH) -> inline MPD -> representation cards
      // -> init/segment CDN URLs (+ optional first-chunk URL, DRM pssh card).
      // tier=lossy switches to the V2 bitrateTypeList manifest path (the
      // account's lossy floor - no content protection requested).
      //
      //   node cli.mjs flac-links <asin> [--territory=US] [--tier=ultra|hd|lossy|best]
      //        [--cookies-file=path] [--with-first-chunk] [--force-wire]
      //        [--no-clear-lead] [--verify] [--save-mpd=file]
      //
      // Credentials resolve from (priority): --cookies-file, env
      // AMAZON_MUSIC_COOKIES / AMAZON_MUSIC_OAUTH_TOKEN /
      // AMAZON_MUSIC_COOKIES_FILE, ~/.streamify/amazon-music.cookies.
      const asin = args[1];
      if (!asin || asin.startsWith('--')) {
        console.error('Usage: node cli.mjs flac-links <asin> [--territory=US] [--tier=ultra|hd|lossy|best] [--cookies-file=path] [--with-first-chunk] [--force-wire] [--no-clear-lead] [--verify] [--save-mpd=file]');
        process.exit(1);
      }
      const getFlag = (name) => {
        const f = args.find(a => a.startsWith(`--${name}=`));
        return f ? f.split('=').slice(1).join('=') : null;
      };
      const hasFlag = (name) => args.includes(`--${name}`);
      const territory = (getFlag('territory') || 'US').toUpperCase();
      const tier = getFlag('tier') || 'ultra';
      const includeFirstChunk = hasFlag('with-first-chunk');
      const forceWire = hasFlag('force-wire');
      const noClearLead = hasFlag('no-clear-lead');
      const verify = hasFlag('verify');

      const { resolveAmazonMusicCredential } = await import('./src/credentials.mjs');
      const credential = resolveAmazonMusicCredential({ cookiesFile: getFlag('cookies-file') });

      console.log(`Solving Amazon DMLS stream links for ASIN ${asin} [${territory}] tier=${tier}${tier === 'lossy' ? ' (V2 bitrateTypeList path)' : ''} ...`);
      console.log(`Credential: ${credential.authenticated ? credential.toJSON().mode + ' (' + credential.source + ')' : 'anonymous guest'}`);

      const solved = await client.solveStreamLinks(asin, {
        territory, tier, credential, includeFirstChunk, forceWire, noClearLead
      });

      console.log('\n=================== AMAZON DMLS SOLVE ===================');
      console.log(` Status        : ${solved.status}`);
      console.log(` Service       : ${solved.operation}`);
      if (solved.session) {
        console.log(` Session       : ${solved.session.authenticated ? 'AUTHENTICATED' : 'guest'}${solved.session.musicTier ? ' tier=' + solved.session.musicTier + (solved.session.tierInferred ? ' (inferred)' : '') : ''}`);
        if (solved.session.customerId) console.log(` CustomerId    : ${solved.session.customerId}`);
        if (solved.session.entitlementList?.length) console.log(` Entitlements  : ${solved.session.entitlementList.join(', ')}`);
      }
      if (solved.reason) console.log(` Reason        : ${solved.reason}`);
      if (solved.wall) {
        console.log(` Live wall     : ${solved.wall.__type} - "${solved.wall.message}"`);
        if (solved.howToAuthenticate) {
          console.log(`\n Unlock recipe :`);
          for (const step of solved.howToAuthenticate.recipe) console.log(`   - ${step}`);
        }
      }
      if (solved.detail) console.log(` Detail        : ${String(solved.detail).slice(0, 200)}`);

      if (solved.manifest) {
        console.log(`\n Manifest      : inline MPD, ${solved.manifest.representationCount} representations, cdn=${solved.manifest.cdnType}, substituteAsin=${solved.manifest.substituteAsin}`);
        console.log(' Representations:');
        for (const rep of (solved.manifest.representations || [])) {
          console.log(`   - ${String(rep.id).padEnd(24)} ${rep.specLabel} | trackType=${rep.trackType} codec=${rep.codec} bw=${rep.bandwidth} drm=${rep.drmProtected ? 'CENC(pssh)' : 'clear'}`);
        }
      }

      if (solved.selected) {
        console.log(`\n Selected      : ${solved.selected.specLabel} (tier=${solved.selected.tierLabel}, codec=${solved.selected.codecFamily}, ${solved.selected.bitDepth}-bit @ ${solved.selected.audioSamplingRate} Hz)`);
      }

      if (solved.links) {
        console.log(`\n LINKS (the actual Amazon stream links):`);
        console.log(`   init segment : ${solved.links.initUrl}`);
        console.log(`   media template: ${solved.links.mediaTemplate}`);
        for (const [i, u] of (solved.links.firstSegmentUrls || []).entries()) {
          console.log(`   segment #${i + 1}    : ${u}`);
        }
        if (solved.links.segmentCount != null) console.log(`   segments     : ~${solved.links.segmentCount} @ ${solved.links.segmentDurationSec?.toFixed(2) || '?'}s`);
      }

      if (solved.drm?.protected) {
        console.log(`\n DRM           : ${solved.drm.envelope} keySystems=[${solved.drm.keySystems}] pssh=${solved.drm.psshList.length} box(es)`);
        console.log(`   license op  : ${solved.drm.licenseOperation}`);
        console.log(`   note        : ${solved.drm.note}`);
      }

      if (solved.firstChunk) {
        console.log(`\n First chunk   : ${solved.firstChunk.url || '(not solved: ' + solved.firstChunk.reason + ')'}`);
        if (solved.firstChunk.cdnType) console.log(`   cdn         : ${solved.firstChunk.cdnType}`);
      }

      if (verify) {
        const urls = [];
        if (solved.links?.initUrl) urls.push(['init segment', solved.links.initUrl]);
        for (const [i, u] of (solved.links?.firstSegmentUrls || []).slice(0, 2).entries()) urls.push([`segment #${i + 1}`, u]);
        if (solved.firstChunk?.url) urls.push(['first chunk', solved.firstChunk.url]);
        if (urls.length) {
          console.log('\n LINK REACHABILITY (live Range probes):');
          for (const [label, u] of urls) {
            const v = await client.dmlsSolver.verifyLinkReachability(u);
            console.log(`   ${label.padEnd(14)}: ${v.reachable ? 'LIVE (HTTP ' + v.httpStatus + ')' : 'DOWN (' + (v.reason || 'HTTP ' + v.httpStatus) + ')'}${v.totalBytes ? ' | ' + v.totalBytes + ' bytes total' : ''}${v.contentType ? ' | ' + v.contentType : ''}`);
          }
        }
      }

      console.log('==========================================================\n');

      const saveMpd = getFlag('save-mpd');
      if (saveMpd && solved.manifest?.inline) {
        fs.writeFileSync(saveMpd, solved.manifest.inline);
        console.log(`Inline MPD saved to ${saveMpd}`);
      }

      // Machine-readable copy
      console.log(JSON.stringify({
        status: solved.status,
        asin: solved.asin,
        territory: solved.territory,
        tier: solved.requestedTier,
        session: solved.session,
        reason: solved.reason || null,
        manifest: solved.manifest ? {
          mpd: solved.manifest.mpd,
          cdnType: solved.manifest.cdnType,
          substituteAsin: solved.manifest.substituteAsin,
          representationCount: solved.manifest.representationCount,
          representations: solved.manifest.representations
        } : null,
        selected: solved.selected,
        links: solved.links,
        drm: solved.drm,
        firstChunk: solved.firstChunk || null
      }, null, 2));
      break;
    }

    case 'lossless': {
      const trackName = args[1];
      const artistName = args[2] || '';
      if (!trackName) {
        console.error('Error: Please provide track title.');
        process.exit(1);
      }
      const tierArg = args.find(a => a.startsWith('--tier='));
      const tier = (tierArg ? tierArg.split('=').slice(1).join('=') : 'best');
      console.log(`Resolving lossless top-tier audio (Amazon-first ladder) for "${trackName}" by "${artistName}"...`);
      const resolved = await client.resolveLosslessAudio({ title: trackName, artist: artistName }, { tier });

      // Native DMLS capability card (honest verdict on Amazon-side FLAC delivery)
      let native = null;
      const asinArg = args.find(a => a.startsWith('--asin='));
      if (asinArg) {
        native = await client.probeNativeFlacCapability(asinArg.split('=')[1]);
      }

      console.log('\n================ NATIVE FLAC CAPABILITY (DMLS) ================');
      if (native) {
        console.log(` Status : ${native.status}`);
        console.log(` Reason : ${native.reason}`);
      } else {
        console.log(" Not probed (pass --asin=<ASIN> to probe Amazon's native delivery surface).");
      }
      console.log('==================================================================\n');

      console.log(JSON.stringify({
        asin: resolved.asin,
        videoId: resolved.videoId,
        title: resolved.title,
        artist: resolved.artist,
        requestedTier: resolved.requestedTier,
        tier: resolved.tier,
        specLabel: resolved.specLabel,
        streamUrl: resolved.streamUrl,
        sampleUrl: resolved.sampleUrl || null,
        previewOnly: resolved.previewOnly || false,
        expiresAt: resolved.expiresAt || null,
        nativeLadder: resolved.nativeLadder || null,
        matchConfidence: resolved.matchConfidence,
        source: resolved.source,
        delivery: resolved.delivery,
        native,
      }, null, 2));
      break;
    }

    case 'lossless-full': {
      // Whole-song lossless acquisition end-to-end. Sources (in priority):
      //   1. STREAMIFY_FLAC_SOURCE_URL  - direct full-song FLAC/URL (e.g. Ultra HD master)
      //   2. --relay=<base> or STREAMIFY_RELAY_BASE + descriptor.streamUrl
      // Bytes are downloaded, magic-sniffed, introspected, and labeled with the
      // TRUE codec - never decorated as FLAC when they are Opus/AAC.
      const trackName = args[1];
      const artistName = args[2] || '';
      const sourceUrl = process.env.STREAMIFY_FLAC_SOURCE_URL;
      const relayArg = args.find(a => a.startsWith('--relay='));
      const relayBaseUrl = (relayArg && relayArg.split('=')[1]) || process.env.STREAMIFY_RELAY_BASE || null;
      if (!trackName || (!sourceUrl && !relayBaseUrl)) {
        console.error('Error: provide track title plus either STREAMIFY_FLAC_SOURCE_URL=<url> or --relay=<relay-base-url>.');
        process.exit(1);
      }

      const descriptor = await client.resolveLosslessAudio({ title: trackName, artist: artistName });
      const { HttpAudioSource } = await import('./src/audio-source.mjs');
      const downloader = new HttpAudioSource({ baseUrl: relayBaseUrl });
      const fetchUrl = sourceUrl || descriptor.streamUrl;

      console.log(`Acquiring whole raw full-song audio from: ${fetchUrl}`);
      const audio = await downloader.load({ streamUrl: fetchUrl });

      const { LosslessFullSongService } = await import('./src/lossless-fullsong.mjs');
      const service = new LosslessFullSongService({
        audioSource: async () => ({
          bytes: audio.bytes,
          codec: audio.codec.codec,
          contentType: audio.contentType,
          tier: descriptor.tier,
        }),
      });
      const artifact = await service.acquireFullSong({
        streamUrl: fetchUrl,
        tier: descriptor.tier,
        codec: audio.codec.codec,
      });

      const outputFile = args[3] ||
        `${trackName.toLowerCase().replace(/[^a-z0-9]+/g, '_')}_fullsong.${artifact.delivery.codec === 'flac' ? 'flac' : 'bin'}`;
      fs.writeFileSync(outputFile, artifact.flac || artifact.bytes);

      console.log(JSON.stringify({
        outputFile,
        requestedTier: descriptor.tier,
        specLabel: artifact.specLabel,
        requestedCodec: descriptor.codec,
        totalBytes: (artifact.flac || artifact.bytes).length,
        contentType: audio.contentType,
        remuxed: artifact.remuxed,
        remuxBytesSaved: artifact.streamInfo?.remuxBytesSaved || 0,
        frameCount: artifact.frameIndex?.length || 0,
        sampleRate: artifact.streamInfo?.streamInfo?.sampleRate || null,
        bitDepth: artifact.streamInfo?.streamInfo?.bitsPerSample || null,
        channels: artifact.streamInfo?.streamInfo?.channels || null,
        durationSec: artifact.streamInfo?.streamInfo?.durationSec || null,
        md5: artifact.streamInfo?.streamInfo?.md5 || null,
        // THE honest verdict: what the bytes actually are
        delivery: artifact.delivery,
      }, null, 2));
      break;
    }

    case 'inspect': {
      // Deep FLAC introspection of a local file or URL:
      // STREAMINFO, metadata blocks, REAL frame index (sample numbers decoded
      // from UTF-8-coded frame headers), metadata-first remux savings,
      // frame-aligned seek geometry, and two-phase pacing configuration.
      const target = args[1];
      if (!target) {
        console.error('Error: Please provide a FLAC file path or URL.');
        process.exit(1);
      }
      let buffer;
      if (/^https?:\/\//.test(target)) {
        console.log(`Downloading FLAC from: ${target}`);
        const { HttpAudioSource } = await import('./src/audio-source.mjs');
        const dl = new HttpAudioSource();
        const audio = await dl.load({ streamUrl: target });
        buffer = audio.bytes;
        console.log(`Downloaded ${buffer.length} bytes | sniffed codec: ${audio.codec.codec} (${audio.codec.detail})`);
      } else {
        buffer = fs.readFileSync(target);
        console.log(`Loaded local file: ${target} (${buffer.length} bytes)`);
      }

      const { FlacIntrospector } = await import('./src/flac-introspector.mjs');
      const { FrameAlignedGateway } = await import('./src/frame-gateway.mjs');

      const info = FlacIntrospector.inspect(buffer);
      console.log('\n================ FLAC STREAMINFO ================');
      console.log(JSON.stringify(info.streamInfo, null, 2));
      console.log(`\nMetadata blocks: ${info.blocks.length} | picture: ${info.pictureBlock ? info.pictureBlock.totalBytes + ' bytes' : 'none'} | seektable points: ${info.seekPoints.length}`);

      const remux = FlacIntrospector.createMetadataFirstStream(buffer);
      const remuxInfo = FlacIntrospector.inspect(remux.remuxedBuffer);
      const audioPayload = remux.remuxedBuffer.subarray(remuxInfo.firstAudioFrameOffset);
      const scan = FlacIntrospector.scanFramesDetailed(audioPayload, remuxInfo.firstAudioFrameOffset, 100000, remuxInfo.streamInfo);

      console.log(`\n================ FRAME INDEX (REAL sample numbers) ================`);
      console.log(`Sync markers    : ${scan.frameCount}`);
      console.log(`CRC-8 validated : ${scan.validatedFrameCount} (headers decoded + CRC-8 verified)`);
      console.log(`Contiguous      : ${scan.contiguousFrames}/${Math.max(0, scan.validatedFrameCount - 1)} (${(scan.integrity * 100).toFixed(1)}% frame-tile integrity)`);
      if (scan.validatedFrameCount > 0) {
        console.log(`First frame   : sample ${scan.firstSampleNumber} @ byte ${scan.frames.find(f => f.headerValidated).byteOffset}`);
        console.log(`Last frame    : sample ${scan.lastSampleNumber} @ byte ${scan.frames.filter(f => f.headerValidated).slice(-1)[0].byteOffset}`);
        console.log(`Sample numbers strictly ascending: ${scan.sampleNumbersAscending}`);
      }
      console.log(`Remux         : ${remux.bytesSavedFromFront} bytes saved from front (PICTURE deferred)`);

      const validatedFrames = scan.frames.filter(f => f.headerValidated);
      const gateway = new FrameAlignedGateway({
        frameIndex: validatedFrames,
        sampleRate: remuxInfo.streamInfo?.sampleRate || 48000,
        totalBytes: remux.remuxedBuffer.length,
        metadataBytes: remuxInfo.firstAudioFrameOffset,
        bitrate: remuxInfo.estimatedBitrate || 4608000,
      });

      const duration = remuxInfo.streamInfo?.durationSec || 0;
      const seekTarget = Math.max(1, Math.floor(duration / 3));
      const seek = gateway.resolveSeekRange(seekTarget);
      console.log(`\n================ FRAME-ALIGNED SEEK (t=${seekTarget}s of ${duration.toFixed(1)}s) ================`);
      console.log(JSON.stringify(seek, null, 2));

      console.log(`\n================ TWO-PHASE PACING ================`);
      console.log(JSON.stringify(gateway.computePacingConfig(), null, 2));
      break;
    }

    case 'manifest': {
      const trackName = args[1];
      const manifest = await client.getDashManifest({ title: trackName, artist: args[2] || '' });
      console.log(JSON.stringify(manifest, null, 2));
      break;
    }

    case 'prefetch': {
      const trackName = args[1];
      const prefetched = await client.prefetchDescriptor({ title: trackName, artist: args[2] || '' });
      console.log(JSON.stringify(prefetched, null, 2));
      break;
    }

    case 'podcast': {
      const query = args.slice(1).join(' ') || 'Huberman';
      console.log(`Searching podcasts for: "${query}"...`);
      const podcasts = await client.searchPodcasts(query);
      console.log(JSON.stringify(podcasts, null, 2));
      break;
    }

    case 'station': {
      const seed = args.slice(1).join(' ') || 'The Weeknd';
      console.log(`Generating radio station for seed: "${seed}"...`);
      const station = await client.createStation(seed);
      console.log(JSON.stringify(station, null, 2));
      break;
    }

    case 'badges': {
      const title = args[1] || 'Blinding Lights';
      console.log(`Inspecting audio quality badges for "${title}"...`);
      const badges = client.getAudioBadges({ title, isUltraHd: true, isAtmos: true });
      console.log(JSON.stringify(badges, null, 2));
      break;
    }

    case 'credits': {
      const title = args[1] || 'Blinding Lights';
      console.log(`Extracting track credits for "${title}"...`);
      const credits = client.getTrackCredits({
        title,
        artist: 'The Weeknd',
        album: 'After Hours',
        copyright: '℗ 2020 The Weeknd XO, Inc., manufactured and marketed by Republic Records, a division of UMG Recordings, Inc.',
        isUltraHd: true,
        isAtmos: true,
        isExplicit: false
      });
      console.log(JSON.stringify(credits, null, 2));
      break;
    }

    case 'videos': {
      const artist = args.slice(1).join(' ') || 'The Weeknd';
      console.log(`Finding official music videos for "${artist}"...`);
      const videos = await client.getMusicVideos(artist);
      console.log(JSON.stringify(videos, null, 2));
      break;
    }

    case 'help':
    default: {
      console.log('Streamify Amazon Catalog Engine CLI (Complete Suite)');
      console.log('');
      console.log('AMAZON-FIRST POLICY: stream resolution returns real Amazon artifacts only');
      console.log('(DMLS full-song links when authenticated; the real guest sample URL otherwise).');
      console.log('The YouTube bridge is EXPLICIT (resolve-youtube) - never a silent fallback.');
      console.log('');
      console.log('Available Commands:');
      console.log('  node cli.mjs home [territory]      Editorial Home & Showcase');
      console.log('  node cli.mjs search <query>        Catalog Search');
      console.log('  node cli.mjs suggest <query>       Instant Suggestions');
      console.log('  node cli.mjs browse <id>           Browse Album/Artist/Playlist/Podcast');
      console.log('  node cli.mjs next <asin>           Next Autoplay Queue');
      console.log('  node cli.mjs thumb <url> [size]    Thumbnail URL Formatter');
      console.log('  node cli.mjs scrape <url>          Scrape External Playlist');
      console.log('  node cli.mjs lyrics <song> <band>  Synchronized Lyrics');
      console.log('  node cli.mjs resolve <song> <band> [--bridge] REAL Amazon stream (native/sample)');
      console.log('  node cli.mjs sample <asin|song> [artist] [--out=f.mp3] [--verify] REAL guest sample URL');
      console.log('  node cli.mjs lossless <song> <band> [--asin=ASIN] [--tier=best] Amazon-first lossless ladder');
      console.log('  node cli.mjs lossless-full <song> <band> [--relay=base] Whole raw full-song bytes (honest codec sniff)');
      console.log('  node cli.mjs flac-links <asin> [--tier=ultra|hd|lossy|best] [--verify] REAL DMLS stream links');
      console.log('  node cli.mjs resolve-youtube <song> <band>   EXPLICIT YouTube bridge (opt-in)');
      console.log('  node cli.mjs inspect <file.flac|url>       Deep FLAC introspection: frames, seek, pacing');
      console.log('  node cli.mjs manifest <song>       DASH MPD Manifest');
      console.log('  node cli.mjs prefetch <song>       Stream Prefetch Descriptor');
      console.log('  node cli.mjs probe                 Live Discovery & Health');
      console.log('  node cli.mjs podcast <query>       Podcast Discovery & Streaming');
      console.log('  node cli.mjs station <seed>        Algorithmic Radio Station Generator');
      console.log('  node cli.mjs badges <title>        Audiophile Quality Badges');
      console.log('  node cli.mjs credits <title>       Personnel & Liner Notes');
      console.log('  node cli.mjs videos <artist>       Official Music Videos');
      break;
    }
  }
}

main().catch(err => {
  console.error('CLI Execution Error:', err.message);
  process.exit(1);
});
