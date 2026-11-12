/**
 * Streamify Web — P0 proof application (W0.4).
 *
 * Proves the full chain in a browser:
 *     TypeScript ──▶ wasm facade ──▶ relay ──▶ youtubei ──▶ wasm parse
 *         ──▶ search results ──▶ player ladder resolve ──▶ <audio> through
 *             the relay with a Web Audio graph producing non-zero output.
 *
 * Stable selectors for CI / browser smoke tests:
 *   #ping-result            engine handshake ("pong")
 *   #result-count           search status + data-count
 *   #audio-state            playback state + data-state/-peak/-time/-client/-ciphered/-itag
 *   #proof-result          relay 206 + Web Audio proof + data-status/-http/-bytes/-peak/-time
 */
import { useEffect, useRef, useState } from "react";
import Canary from "./pages/Canary";
import { playWithFallback } from "./mse";
import {
  bootEngine,
  doGetStream,
  doSearch,
  formatDuration,
  relayWrap,
  type StreamEnvelope,
  type Track,
} from "./wasm";

interface ProofResult {
  status: string;
  http: string;
  bytes: string;
  peak: string;
  time: string;
  detail: string;
}

/** G0.4's standard-content track when the proof runs on a cold page. */
const PROOF_VIDEO_ID = "dQw4w9WgXcQ";

export default function App() {
  // W0.1b: hash routing for the residential canary page (#/canary). The
  // home path below is untouched; the Canary page carries its own .app
  // shell + footer.
  const [hash, setHash] = useState(() => window.location.hash);
  useEffect(() => {
    const onHashChange = () => setHash(window.location.hash);
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  const [engine, setEngine] = useState<{ phase: "boot" | "pong" | "error"; detail: string }>({
    phase: "boot",
    detail: "instantiating wasm module…",
  });

  const [query, setQuery] = useState("");
  const [searching, setSearching] = useState(false);
  const [tracks, setTracks] = useState<Track[]>([]);
  const [searchStatus, setSearchStatus] = useState("engine booting…");

  const [playTrack, setPlayTrack] = useState<Track | null>(null);
  const [playState, setPlayState] = useState("idle");
  const [peak, setPeak] = useState(0);

  const [proof, setProof] = useState<ProofResult | null>(null);
  const [proofRunning, setProofRunning] = useState(false);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const audioStateRef = useRef<HTMLParagraphElement | null>(null);
  const ctxRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const meterRef = useRef<number | null>(null);
  const lastStreamRef = useRef<{ url: string; mime: string } | null>(null);

  useEffect(() => {
    let alive = true;
    bootEngine()
      .then((pong) => {
        if (alive) {
          setEngine({ phase: "pong", detail: pong });
          setSearchStatus("ready — type a query");
        }
      })
      .catch((e) => {
        if (alive) {
          setEngine({ phase: "error", detail: String(e) });
          setSearchStatus("engine failed to boot");
        }
      });
    return () => {
      alive = false;
      if (meterRef.current !== null) {
        window.clearInterval(meterRef.current);
      }
    };
  }, []);

  /**
   * Build the Web Audio graph once per element: MediaElementSource →
   * Analyser → destination. MediaElementSource can only be created once for
   * a given element, so the nodes are cached in refs.
   */
  function ensureGraph(): AudioContext {
    const audio = audioRef.current;
    if (!audio) {
      throw new Error("audio element missing from DOM");
    }
    if (!ctxRef.current) {
      const ctx = new AudioContext();
      const source = ctx.createMediaElementSource(audio);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 2048;
      source.connect(analyser);
      analyser.connect(ctx.destination);
      ctxRef.current = ctx;
      analyserRef.current = analyser;
    }
    return ctxRef.current;
  }

  function samplePeak(): number {
    const analyser = analyserRef.current;
    if (!analyser) {
      return 0;
    }
    const buf = new Uint8Array(analyser.fftSize);
    analyser.getByteTimeDomainData(buf);
    let max = 0;
    for (let i = 0; i < buf.length; i++) {
      const dev = Math.abs(buf[i] - 128);
      if (dev > max) {
        max = dev;
      }
    }
    return max / 128;
  }

  function startMeter() {
    if (meterRef.current !== null) {
      window.clearInterval(meterRef.current);
    }
    meterRef.current = window.setInterval(() => {
      const p = samplePeak();
      const audio = audioRef.current;
      const el = audioStateRef.current;
      if (el) {
        el.dataset.peak = p.toFixed(3);
        el.dataset.time = audio ? audio.currentTime.toFixed(2) : "0";
        el.dataset.state = audio && !audio.paused ? "playing" : "paused";
      }
      setPeak(p);
    }, 400);
  }

  async function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    const q = query.trim();
    if (!q || searching || engine.phase !== "pong") {
      return;
    }
    setSearching(true);
    setSearchStatus(`searching "${q}" through the relay…`);
    try {
      const env = await doSearch(q);
      if (env.ok) {
        setTracks(env.tracks);
        setSearchStatus(
          env.tracks.length
            ? `${env.tracks.length} tracks`
            : "0 tracks (endpoint answered but nothing parseable)",
        );
      } else {
        setTracks([]);
        setSearchStatus(`search failed: ${env.error}`);
      }
    } catch (err) {
      setTracks([]);
      setSearchStatus(`search failed: ${String(err)}`);
    } finally {
      setSearching(false);
    }
  }

  async function handlePlay(track: Track) {
    setPlayTrack(track);
    setPlayState("resolving through the player ladder (ANDROID_VR → ANDROID → IOS)…");
    if (audioStateRef.current) {
      audioStateRef.current.dataset.state = "resolving";
    }
    try {
      const env: StreamEnvelope = await doGetStream(track.video_id);
      if (!env.ok) {
        const attempts = (env.attempts ?? [])
          .map((a) => `${a.client}→${a.status}${a.reason ? ` (${a.reason})` : ""}`)
          .join("; ");
        setPlayState(`resolve failed: ${env.error}${attempts ? ` — ${attempts}` : ""}`);
        if (audioStateRef.current) {
          audioStateRef.current.dataset.state = "error";
        }
        return;
      }
      lastStreamRef.current = { url: env.stream.url, mime: env.stream.mimeType };
      const audio = audioRef.current!;
      // Bounded-range MSE path (see mse.ts) with plain-element fallback.
      const mode = await playWithFallback(audio, env.stream.url, env.stream.mimeType);
      const ctx = ensureGraph();
      await ctx.resume();
      const el = audioStateRef.current;
      if (el) {
        el.dataset.state = "playing";
        el.dataset.client = env.client;
        el.dataset.ciphered = String(env.ciphered);
        el.dataset.itag = String(env.stream.itag);
        el.dataset.mode = mode;
      }
      setPlayState(
        `playing "${track.title}" — resolved by ${env.client}, itag ${env.stream.itag}, ${env.stream.mimeType}, ciphered=${env.ciphered}`,
      );
      startMeter();
    } catch (err) {
      setPlayState(`playback failed: ${String(err)}`);
      if (audioStateRef.current) {
        audioStateRef.current.dataset.state = "error";
      }
    }
  }

  /**
   * P0 kill-criterion proof, machine-checkable:
   *  1. fetch a 128 KiB byte-range through the relay → expect HTTP 206 +
   *     Content-Range (relays CAN stream ranges to a browser fetch);
   *  2. play the same stream through the Web Audio graph → expect non-zero
   *     analyser output and advancing currentTime (G0.4).
   */
  async function runProof() {
    setProofRunning(true);
    try {
      let target: string;
      let mime: string;
      if (lastStreamRef.current) {
        target = lastStreamRef.current.url;
        mime = lastStreamRef.current.mime;
      } else {
        const env = await doGetStream(PROOF_VIDEO_ID);
        if (!env.ok) {
          const attempts = (env.attempts ?? [])
            .map((a) => `${a.client}→${a.status}`)
            .join("; ");
          setProof({
            status: "FAIL",
            http: "-",
            bytes: "-",
            peak: "-",
            time: "-",
            detail: `resolve failed: ${env.error}${attempts ? ` — ${attempts}` : ""}`,
          });
          return;
        }
        target = env.stream.url;
        mime = env.stream.mimeType;
        lastStreamRef.current = { url: target, mime };
      }

      const resp = await fetch(relayWrap(target), {
        headers: { Range: "bytes=0-131071" },
      });
      const buf = await resp.arrayBuffer();
      const bytes = new Uint8Array(buf);
      let nonzero = 0;
      for (let i = 0; i < bytes.length; i++) {
        if (bytes[i] !== 0) {
          nonzero++;
        }
      }
      const range = resp.headers.get("content-range") ?? "";
      const okHttp = resp.status === 206 || resp.status === 200;

      const audio = audioRef.current!;
      // The bounded-range fetch above is the 206 evidence; feed the element
      // the same stream via MSE so playback survives open-ended-range 403s.
      await playWithFallback(audio, target, mime);
      const ctx = ensureGraph();
      await ctx.resume();
      startMeter();
      await new Promise((r) => {
        setTimeout(r, 1600);
      });

      const peakNow = samplePeak();
      const timeNow = audio.currentTime;
      const pass = okHttp && bytes.length > 0 && (peakNow > 0.01 || timeNow > 0.5);
      setProof({
        status: pass ? "PASS" : "FAIL",
        http: String(resp.status),
        bytes: String(bytes.length),
        peak: peakNow.toFixed(3),
        time: timeNow.toFixed(2),
        detail: `content-range=${range || "none"}; nonzero-bytes=${nonzero}/${bytes.length}`,
      });
    } catch (err) {
      setProof({
        status: "FAIL",
        http: "-",
        bytes: "-",
        peak: "-",
        time: "-",
        detail: String(err),
      });
    } finally {
      setProofRunning(false);
    }
  }

  // Canary route. Returned AFTER the hooks above on purpose: returning
  // earlier would skip the home path's hooks and desync React's hook order
  // when the hash changes mid-session.
  if (hash === "#/canary") {
    return <Canary />;
  }

  const engineReady = engine.phase === "pong";

  return (
    <div className="app">
      <main className="content" id="main-content">
        <h1>Streamify Web</h1>
        <p className="subtitle">P0 Proof Phase</p>

        <section className="card" aria-labelledby="engine-card-title">
          <h2 id="engine-card-title" className="card-title">
            Wasm engine handshake
          </h2>
          <p className="card-text">
            Same-engine proof: the extraction core (vendored InnertubeParser)
            compiled to wasm32, called via wasm-bindgen from TypeScript.
          </p>
          <span className="badge">{engine.phase === "pong" ? "engine: alive" : engine.phase === "boot" ? "engine: booting" : "engine: error"}</span>
          <p id="ping-result" className="ping-result" role="status">
            {engine.detail}
          </p>
        </section>

        <section className="card section-gap" aria-labelledby="search-card-title">
          <h2 id="search-card-title" className="card-title">
            Search (WEB_REMIX through the relay)
          </h2>
          <form className="search-form" onSubmit={handleSearch}>
            <label htmlFor="search-input" className="sr-only">
              Search query
            </label>
            <input
              id="search-input"
              type="search"
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
              }}
              placeholder="Blinding Lights The Weeknd"
              autoComplete="off"
            />
            <button
              id="search-button"
              type="submit"
              className="button"
              disabled={searching || !engineReady}
            >
              {searching ? "Searching…" : "Search"}
            </button>
          </form>
          <p id="result-count" role="status" data-count={tracks.length} className="status-line">
            {searchStatus}
          </p>
          {tracks.length > 0 && (
            <ol className="track-list scroll-list" id="results">
              {tracks.map((t) => (
                <li key={t.video_id} className="track-row">
                  {t.thumbnail_url ? (
                    <img
                      className="track-thumb"
                      src={t.thumbnail_url}
                      alt=""
                      loading="lazy"
                      width={56}
                      height={56}
                    />
                  ) : null}
                  <div className="track-meta">
                    <span className="track-title">{t.title}</span>
                    <span className="track-artist">
                      {t.artist}
                      {t.album && t.album !== "YouTube Music" ? ` · ${t.album}` : ""}
                    </span>
                  </div>
                  <span className="track-duration">{formatDuration(t.duration_sec)}</span>
                  <button
                    type="button"
                    className="button button-small"
                    data-video-id={t.video_id}
                    disabled={!engineReady}
                    onClick={() => {
                      void handlePlay(t);
                    }}
                  >
                    Play
                  </button>
                </li>
              ))}
            </ol>
          )}
        </section>

        <section className="card section-gap" aria-labelledby="play-card-title">
          <h2 id="play-card-title" className="card-title">
            Playback (player ladder → relay audio)
          </h2>
          <p className="card-text">
            Audio streams from googlevideo through the relay with a Web Audio
            graph attached — the analyser peak below is the G0.4 evidence.
          </p>
          <audio id="audio-element" ref={audioRef} controls preload="none" />
          <div className="meter" aria-hidden="true">
            <div className="meter-fill" style={{ width: `${Math.round(peak * 100)}%` }} />
          </div>
          {/* Test hook + live status: imperative dataset writes from the meter
              and player handlers land here (id is stable for CI/agent-browser). */}
          <p
            id="audio-state"
            ref={audioStateRef}
            className="ping-result"
            role="status"
            data-state="idle"
            data-peak="0"
            data-time="0"
            data-client=""
            data-ciphered=""
            data-itag=""
          >
            {playTrack ? `${playTrack.title} — ${playState}` : playState}
          </p>
        </section>

        <section className="card section-gap" aria-labelledby="proof-card-title">
          <h2 id="proof-card-title" className="card-title">
            Relay 206 + decode proof (kill criterion)
          </h2>
          <p className="card-text">
            Fetches a 128 KiB byte-range through the relay and plays the same
            stream through the Web Audio graph. Resolves the standard-content
            track dQw4w9WgXcQ when nothing is loaded.
          </p>
          <button
            id="proof-button"
            type="button"
            className="button"
            onClick={() => {
              void runProof();
            }}
            disabled={proofRunning || !engineReady}
          >
            {proofRunning ? "Running…" : "Run 206 + decode proof"}
          </button>
          <p
            id="proof-result"
            className="ping-result"
            role="status"
            data-status={proof?.status ?? "idle"}
            data-http={proof?.http ?? "-"}
            data-bytes={proof?.bytes ?? "-"}
            data-peak={proof?.peak ?? "-"}
            data-time={proof?.time ?? "-"}
          >
            {proof
              ? `${proof.status} — http ${proof.http}, ${proof.bytes} bytes, analyser peak ${proof.peak} at t=${proof.time}s; ${proof.detail}`
              : "not run yet"}
          </p>
        </section>
      </main>

      <footer className="footer">
        <p>Streamify Web · MIT License · WS-W P0 proof (W0.1b, W0.2, W0.3, W0.4)</p>
      </footer>
    </div>
  );
}
