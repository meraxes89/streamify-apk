import { createRoot } from 'react-dom/client';
import { App } from './App';
import { consumeOAuthRedirect } from './lib/supabase';
import './theme/tokens.css';
import './styles.css';

// OAuth redirects land with EITHER a PKCE authorization code in the query
// string (?code=…, the flow signInWithGoogle initiates) OR implicit tokens
// in the URL fragment (#access_token=…, the fallback lane). Consume them
// SYNCHRONOUSLY, before the hash router ever mounts (it would treat the
// fragment as a route): the payload is captured and the URL scrubbed via
// replaceState first thing. The PKCE code exchange then runs in the
// background — sessionStore.init() awaits it (awaitOAuthExchange) before
// the UI reads the session, so the store is settled, never racing.
consumeOAuthRedirect();

const container = document.getElementById('root');
if (container === null) {
  throw new Error('#root container missing from index.html');
}

createRoot(container).render(<App />);
