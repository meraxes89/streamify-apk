/**
 * components/EmptyStateView.tsx — P2 browse port
 * (ui/components/EmptyStateView.kt).
 *
 * Centered headline + body + optional action button. The Kotlin button
 * inverts the theme (TextMain container, BgBase content) — the same
 * contrast pair via the --sf-* tokens.
 */

export interface EmptyStateViewProps {
  title: string;
  subtitle: string;
  actionText?: string | null;
  onActionClick?: (() => void) | null;
  className?: string;
}

export function EmptyStateView(props: EmptyStateViewProps): JSX.Element {
  const { title, subtitle, actionText, onActionClick, className } = props;
  return (
    <div className={`emptystate ${className ?? ''}`} role="status">
      <h2 className="emptystate__title">{title}</h2>
      <p className="emptystate__subtitle">{subtitle}</p>
      {actionText != null && actionText !== '' && onActionClick != null && (
        <button type="button" className="emptystate__action" onClick={onActionClick}>
          {actionText}
        </button>
      )}
    </div>
  );
}
