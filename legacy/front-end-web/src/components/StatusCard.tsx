import type { EngineStatus } from '../lib/engine';

export interface StatusCardProps {
  status: EngineStatus | null;
  lastSearchCount: number | null;
}

const NOT_CONFIGURED = 'not configured — set ?relay=URL or VITE_RELAY_BASE_URL';

export function StatusCard(props: StatusCardProps): JSX.Element {
  const { status, lastSearchCount } = props;

  const relayBase = status === null ? null : status.relayBase;
  const relayConfigured = relayBase !== null && relayBase.length > 0;
  const relayText = status === null ? '…' : relayConfigured ? relayBase : NOT_CONFIGURED;
  const pingText = status === null ? '…' : status.ping === null ? '—' : status.ping;
  const loaded = status !== null && status.loaded;
  const failed = status !== null && !status.loaded;

  return (
    <section className="card" aria-label="Engine status">
      <h2>Engine status</h2>
      <dl className="status-grid">
        <dt>Relay base</dt>
        <dd className={relayConfigured ? undefined : 'bad'}>
          {relayConfigured ? <code>{relayText}</code> : relayText}
        </dd>
        <dt>Engine ping</dt>
        <dd className={loaded ? 'ok' : failed ? 'bad' : undefined}>{pingText}</dd>
        <dt>Last search count</dt>
        <dd>{lastSearchCount === null ? '—' : String(lastSearchCount)}</dd>
        {status !== null && status.error !== undefined ? (
          <>
            <dt>Engine error</dt>
            <dd className="bad">{status.error}</dd>
          </>
        ) : null}
      </dl>
    </section>
  );
}
