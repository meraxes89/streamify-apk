/**
 * components/CategoryCard.tsx — P2 browse port (ui/components/CategoryCard.kt).
 *
 * The moods & genres tile: vertical gradient (accent at 0.8 → full), the
 * title pinned top-start, and the Spotify-style tilted art placeholder in
 * the bottom-end corner (rotationZ 25° → CSS rotate).
 */

export interface CategoryCardProps {
  title: string;
  /** Any CSS color (the Kotlin Color) driving the vertical gradient. */
  accentColor: string;
  onClick: () => void;
  className?: string;
}

export function CategoryCard(props: CategoryCardProps): JSX.Element {
  const { title, accentColor, onClick, className } = props;
  return (
    <button
      type="button"
      className={`categorycard ${className ?? ''}`}
      onClick={onClick}
      aria-label={`Search ${title}`}
      style={{
        backgroundImage: `linear-gradient(180deg, ${accentColor}cc, ${accentColor})`,
      }}
    >
      <span className="categorycard__title">{title}</span>
      <span className="categorycard__art" aria-hidden="true" />
    </button>
  );
}
