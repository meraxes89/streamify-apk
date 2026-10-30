import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-10 — tactile micro-motion: card press effects + the heart burst.
//
// Ports of ui/animations/CardPressEffect.kt (pressed scale 0.95, alpha 0.85)
// and HeartBurstEffect.kt (1.2× pop, HighBouncy) — expressed the native way:
// a ButtonStyle for real buttons, a modifier for gesture-driven cards, and
// a 120 Hz TimelineView + Canvas particle burst (allocation-free draw loop,
// paused when idle — the ProMotion discipline).
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Card press (Android Modifier.cardPressEffect)

/// Pressed: scale 0.95 (low-bouncy rebound) + alpha 0.85 (near-instant).
/// The default style for every tappable card, chip rail item and hero tile.
public struct PressableCardButtonStyle: ButtonStyle {
    public init() {}

    public func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.95 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(Motion.cardPress, value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == PressableCardButtonStyle {
    /// The Streamify card press feel (scale 0.95 / alpha 0.85).
    public static var pressableCard: PressableCardButtonStyle { .init() }
}

/// Manual press tracking for non-`Button` cards (long-press contexts,
/// gesture-composited tiles): drive `isPressed` from the gesture state.
public struct PressEffectModifier: ViewModifier {
    public var isPressed: Bool

    public func body(content: Content) -> some View {
        content
            .scaleEffect(isPressed ? 0.95 : 1)
            .opacity(isPressed ? 0.85 : 1)
            .animation(Motion.cardPress, value: isPressed)
    }
}

extension View {
    public func pressEffect(_ isPressed: Bool) -> some View {
        modifier(PressEffectModifier(isPressed: isPressed))
    }
}

// MARK: - Heart burst (Android HeartBurstEffect + jam upvote particles)

/// A 120 Hz Canvas particle burst: 12 hearts eject radially with decaying
/// scale/opacity over ~0.9 s. Fire by incrementing `trigger` (one burst per
/// like / blend moment / jam upvote). Ticking pauses the moment the burst
/// expires — idle surfaces cost zero frames.
public struct HeartBurstView: View {

    /// Increment to fire one burst.
    public var trigger: Int
    public var color: Color

    private struct Burst: Identifiable {
        let id: Int
        let startedAt: Date
    }

    @State private var burst: Burst?

    public init(trigger: Int, color: Color = StreamifyColors.primary) {
        self.trigger = trigger
        self.color = color
    }

    public var body: some View {
        TimelineView(
            .animation(minimumInterval: 1.0 / 120.0, paused: burst == nil)
        ) { timeline in
            Canvas { context, size in
                guard let burst else { return }
                let t = timeline.date.timeIntervalSince(burst.startedAt)
                guard t > 0, t < 0.9 else { return }

                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                let radius = min(size.width, size.height) * 0.5
                let heart = context.resolve(
                    Text(Image(systemName: StreamifyIcon.heartFill))
                        .font(.system(size: 11))
                        .foregroundColor(color)
                )

                for i in 0..<12 {
                    // Explicit type boundaries (Double math, CGFloat layout):
                    // the mixed-literal form exceeded the type-checker budget.
                    let angle = Double(i) / 12 * 2 * .pi + Double(burst.id % 7) * 0.13
                    let eased: Double = 1 - pow(1 - t / 0.9, 3)
                    let travel: CGFloat = radius * CGFloat(eased)
                    let lift: CGFloat = CGFloat(t) * 14
                    let point = CGPoint(
                        x: center.x + CGFloat(cos(angle)) * travel,
                        y: center.y + CGFloat(sin(angle)) * travel - lift
                    )
                    let decay = max(0, 1 - t / 0.9)
                    var particle = heart
                    particle.opacity = decay
                    particle.transform = .scale(x: decay, y: decay)
                    context.draw(particle, at: point)
                }
            }
        }
        .allowsHitTesting(false)
        .onChange(of: trigger) { _, newValue in
            guard newValue > 0 else { return }
            burst = Burst(id: newValue, startedAt: Date())
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1))
                if burst?.id == newValue { burst = nil }
            }
        }
    }
}

// MARK: - Heart pop scale (HeartButton's own 1.2× beat)

/// The 1.2× heart pop on the like transition (Android heartBurstScale):
/// apply to the symbol inside a like button.
public struct HeartPopModifier: ViewModifier {
    public var isLiked: Bool

    @State private var pop = false

    public func body(content: Content) -> some View {
        content
            .scaleEffect(pop ? 1.2 : 1)
            .onChange(of: isLiked) { _, liked in
                guard liked else { return }
                pop = true
                withAnimation(Motion.heartBurst) { pop = false }
            }
    }
}

extension View {
    public func heartPop(isLiked: Bool) -> some View {
        modifier(HeartPopModifier(isLiked: isLiked))
    }
}

// MARK: - Previews

#Preview("Card press") {
    VStack(spacing: 24) {
        Button {
            // tap — feel the 0.95 / 0.85 press
        } label: {
            VStack(alignment: .leading) {
                Text("Obsidian Sunrise").font(StreamifyType.songTitle)
                Text("Sireen · 4:11").font(StreamifyType.songArtist)
            }
            .frame(width: 260, alignment: .leading)
            .padding(16)
            .background(StreamifyColors.bgCard, in: StreamifyShapes.card)
        }
        .buttonStyle(.pressableCard)
    }
    .padding(40)
    .background(StreamifyColors.bgBase)
}

#Preview("Heart burst") {
    HeartBurstDemo()
        .padding(60)
        .background(StreamifyColors.bgBase)
}

private struct HeartBurstDemo: View {
    @State private var trigger = 0

    var body: some View {
        Button {
            trigger += 1
            Haptics.play(.likeHeartbeat)
        } label: {
            Image(systemName: StreamifyIcon.heartFill)
                .font(.system(size: 44))
                .foregroundStyle(StreamifyColors.primary)
                .heartPop(isLiked: true)
                .overlay(HeartBurstView(trigger: trigger))
        }
        .buttonStyle(.pressableCard)
    }
}
