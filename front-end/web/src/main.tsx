import { createRoot } from 'react-dom/client';
import { App } from './App';
import { consumeOAuthRedirect } from './lib/supabase';
import './theme/tokens.css';
import './styles.css';

// OAuth redirects land with tokens in the URL fragment — consume them
// SYNCHRONOUSLY, before the hash router ever mounts (it would treat the
// fragment as a route). Parses, persists, and scrubs the URL clean.
consumeOAuthRedirect();

const container = document.getElementById('root');
if (container === null) {
  throw new Error('#root container missing from index.html');
}

createRoot(container).render(<App />);
