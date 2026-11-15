/**
 * screens/AdminTerminalScreen.tsx — W-W: the Diagnostic Terminal port
 * (ui/screens/AdminTerminalScreen.kt).
 *
 * Available to EVERY user (the APK's stance): capture is OFF by default
 * and costs nothing until armed; the switch arms the SLog ring which
 * auto-disarms after 2 hours. Copy / Save export the entire buffer.
 *
 * The terminal look is the APK's verbatim: near-black bg (#0A0E0A),
 * terminal green (#4AF626), the V/D/I/W/E/F level chips colored per
 * levelColor(), a grep box, and a 500 ms live tail poll while armed
 * (allocation-light, same as the Kotlin).
 *
 * Web lowering: Android's share-intent/save-to-Downloads collapse into
 * Copy (clipboard) + Save (a .log download) — the browser's honest
 * equivalents. The format-line level index is the SHARED LEVEL_INDEX
 * from lib/slog.ts (the APK's off-by-two between AdminTerminalScreen
 * and SLog.formatLine is fixed in the port — see the PR).
 */

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import {
  LEVEL_INDEX,
  captureState,
  clearBuffer,
  exportAll,
  remainingCaptureMs,
  setCaptureEnabled,
  slogVersion,
  snapshotLines,
  type SLogLevel,
} from '../lib/slog';
import { toast } from '../lib/toastStore';

const LEVELS: SLogLevel[] = ['V', 'D', 'I', 'W', 'E', 'F'];

function levelColor(lvl: string): string {
  switch (lvl) {
    case 'V':
      return '#8a8f8a';
    case 'D':
      return '#58c4dd';
    case 'I':
      return '#4af626';
    case 'W':
      return '#ffb74d';
    case 'E':
      return '#ff5370';
    case 'F':
      return '#ff2e88';
    default:
      return '#ffffff';
  }
}

export function AdminTerminalScreen(): JSX.Element {
  const navigate = useNavigate();
  const [enabled, setEnabled] = useState(captureState);
  const [lines, setLines] = useState<string[]>(() => snapshotLines(1000));
  const [remainingMin, setRemainingMin] = useState(() => Math.floor(remainingCaptureMs() / 60000));
  const [filters, setFilters] = useState<Set<SLogLevel>>(() => new Set(LEVELS));
  const [query, setQuery] = useState('');
  const [paused, setPaused] = useState(false);
  const versionRef = useRef(0);
  const listRef = useRef<HTMLDivElement | null>(null);

  // Live tail only while armed; the 500ms poll mirrors the Kotlin loop.
  useEffect(() => {
    if (!enabled) return;
    const id = window.setInterval(() => {
      const v = slogVersion();
      if (v !== versionRef.current) {
        versionRef.current = v;
        setLines(snapshotLines(1000));
      }
      setRemainingMin(Math.floor(remainingCaptureMs() / 60000));
    }, 500);
    return () => window.clearInterval(id);
  }, [enabled]);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return lines.filter((l) => {
      const lvl = l.length > LEVEL_INDEX ? l[LEVEL_INDEX] : 'I';
      return filters.has(lvl as SLogLevel) && (q === '' || l.toLowerCase().includes(q));
    });
  }, [lines, filters, query]);

  // Auto-scroll to the tail unless the user scrolled up (the Kotlin's
  // paused-flag semantics).
  useEffect(() => {
    if (paused || !enabled) return;
    const el = listRef.current;
    if (el !== null) el.scrollTop = el.scrollHeight;
  }, [visible.length, paused, enabled]);

  const onScroll = useCallback(() => {
    const el = listRef.current;
    if (el === null) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 24;
    if (!atBottom && !paused) setPaused(true);
    if (atBottom && paused) setPaused(false);
  }, [paused]);

  const copyAll = useCallback(() => {
    void navigator.clipboard?.writeText(exportAll()).then(() => {
      toast(`Copied ${exportAll().length} chars`);
    });
  }, []);

  const saveLog = useCallback(() => {
    const blob = new Blob([exportAll()], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `Streamify-log-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.log`;
    a.click();
    URL.revokeObjectURL(url);
    toast('Log saved to your downloads');
  }, []);

  return (
    <div className="term" role="main">
      <header className="term__topbar">
        <button
          type="button"
          className="term__back"
          onClick={() => navigate(-1)}
          aria-label="Back"
        >
          &lt; back
        </button>
        <div className="term__titles">
          <p className="term__url">streamify://terminal</p>
          <p className="term__status">
            {enabled
              ? `${lines.length} lines · auto-off ${remainingMin}m${paused ? ' · paused' : ''}`
              : 'capture off — flip the switch to start'}
          </p>
        </div>
        <label className="term__switch">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => {
              setCaptureEnabled(e.target.checked);
              setEnabled(e.target.checked);
              if (!e.target.checked) setLines([]);
              else setLines(snapshotLines(1000));
              toast(e.target.checked ? 'Logging ON — auto-stops in 2h' : 'Logging OFF');
            }}
            aria-label="Toggle diagnostic capture"
          />
          <span className="term__switch-track" aria-hidden="true" />
        </label>
      </header>

      {!enabled ? (
        <div className="term__off">
          <span className="term__off-glyph" aria-hidden="true">
            ⏻
          </span>
          <p className="term__off-text">
            Diagnostic capture is off.
            <br />
            Flip the switch above to record app activity
            <br />
            (search → stream resolution, errors, telemetry).
            <br />
            Auto-stops after 2 hours.
          </p>
        </div>
      ) : (
        <div className="term__body">
          <div className="term__filters">
            {LEVELS.map((lvl) => {
              const active = filters.has(lvl);
              return (
                <button
                  key={lvl}
                  type="button"
                  className={`term__lvl ${active ? 'term__lvl--on' : ''}`}
                  style={active ? { borderColor: levelColor(lvl), color: levelColor(lvl) } : undefined}
                  aria-pressed={active}
                  onClick={() => {
                    const next = new Set(filters);
                    if (active) next.delete(lvl);
                    else next.add(lvl);
                    setFilters(next);
                  }}
                >
                  {lvl}
                </button>
              );
            })}
            <input
              className="term__grep"
              type="search"
              placeholder="grep…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              aria-label="Filter log lines"
            />
            <div className="term__actions">
              <button type="button" className="term__act" onClick={copyAll} title="Copy the whole buffer">
                <Icon name="contentCopy" size={16} />
              </button>
              <button type="button" className="term__act" onClick={saveLog} title="Save the buffer as a .log file">
                <Icon name="download" size={16} />
              </button>
              <button
                type="button"
                className="term__act term__act--dim"
                onClick={() => {
                  clearBuffer();
                  setLines([]);
                  toast('Buffer cleared');
                }}
                title="Clear the buffer"
              >
                <Icon name="delete" size={16} />
              </button>
            </div>
          </div>

          <div
            className="term__lines"
            role="log"
            aria-label="Diagnostic log"
            ref={listRef}
            onScroll={onScroll}
          >
            {visible.length === 0 ? (
              <p className="term__nomatch">— no lines match —</p>
            ) : (
              visible.map((line, i) => {
                const lvl = line.length > LEVEL_INDEX ? line[LEVEL_INDEX] : 'I';
                return (
                  <p className="term__line" key={i}>
                    <span className="term__line-ts">{line.slice(0, LEVEL_INDEX)}</span>
                    <span style={{ color: levelColor(lvl), fontWeight: 700 }}>{line.slice(LEVEL_INDEX, LEVEL_INDEX + 1)}</span>
                    <span className="term__line-tag">{line.slice(LEVEL_INDEX + 1)}</span>
                  </p>
                );
              })
            )}
          </div>
        </div>
      )}
    </div>
  );
}
