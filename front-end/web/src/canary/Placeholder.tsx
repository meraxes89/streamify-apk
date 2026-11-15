export function CanaryPlaceholder(): JSX.Element {
  return (
    <>
      <main className="canary-main">
        <div className="card canary-card">
          <h1>Streamify Canary</h1>
          <p className="muted">
            Canary console — wiring lands with W0.1b (front-end/canary workspace).
          </p>
          <a className="link-button" href="/streamify-apk/">
            Back to the P0 proof console
          </a>
        </div>
      </main>
      <footer>
        <p>WS-W P0 Proof · residential canary probe · one branch, one PR</p>
      </footer>
    </>
  );
}
