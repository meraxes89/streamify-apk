import { createRoot } from 'react-dom/client';
// W0.1b: the real canary console lives in the front-end/canary workspace
// (plain TS + a React component, no build of its own) — Vite follows the
// relative import out of web/ into the workspace package.
import CanaryConsole from '../../../canary/src/ui/CanaryConsole';
import '../styles.css';

const container = document.getElementById('root');
if (container === null) {
  throw new Error('#root container missing from canary.html');
}

createRoot(container).render(<CanaryConsole />);
