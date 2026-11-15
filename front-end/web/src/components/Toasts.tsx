/**
 * components/Toasts.tsx — P3a: the toast surface (Android Toast parity).
 */

import { useToastStore } from '../lib/toastStore';
import { Icon } from './icons';

export function Toasts(): JSX.Element | null {
  const toasts = useToastStore((s) => s.toasts);
  if (toasts.length === 0) return null;
  return (
    <div className="toasts" role="status" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast--${t.kind}`}>
          {t.kind === 'error' ? <Icon name="warning" size={16} /> : <Icon name="checkCircle" size={16} />}
          <span>{t.message}</span>
        </div>
      ))}
    </div>
  );
}
