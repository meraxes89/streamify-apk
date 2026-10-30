import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — AdminDashboardView: the command center.
//
// SwiftUI port of ui/screens/AdminDashboardScreen.kt (the metrics grid +
// fleet health cards, on PreviewCatalog.adminMetrics fixtures): a 2-column
// LazyVGrid of KPI tiles with signed trend chips, the Edge mesh panel and
// the Engine health panel. The crown ADMIN badge marks the restricted
// surface (Android "Open Admin Command Center").
// ─────────────────────────────────────────────────────────────────────────────

/// Uppercase, letterspaced section label (Android SectionHeader, 1:1).
private struct AdminSectionLabel: View {
    let title: String

    var body: some View {
        Text(title.uppercased())
            .font(StreamifyType.caption)
            .tracking(1.2)
            .foregroundStyle(StreamifyColors.primary)
            .padding(.bottom, StreamifyDimens.spaceSM)
    }
}

/// One KPI tile: symbol medallion, caption label, value and signed trend chip
/// (Android AdminDashboardScreen MetricCard).
private struct MetricCard: View {
    let tile: MetricTile

    private var trendText: String? {
        guard let trend = tile.trendPercent else { return nil }
        return String(format: "%+.1f %%", trend)
    }

    private var trendIsDown: Bool {
        (tile.trendPercent ?? 0) < 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
            HStack(spacing: StreamifyDimens.spaceSM) {
                Image(systemName: tile.symbol)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(StreamifyColors.primary)
                    .frame(width: 30, height: 30)
                    .background(Capsule().fill(StreamifyColors.primary.opacity(0.12)))
                Text(tile.label.uppercased())
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            Text(tile.value)
                .font(StreamifyType.titleMedium)
                .foregroundStyle(StreamifyColors.textMain)
            if let trendText {
                HStack(spacing: 2) {
                    if trendIsDown {
                        Image(systemName: "arrow.down")
                            .font(.system(size: 9, weight: .bold))
                    }
                    Text(trendText)
                }
                .font(StreamifyType.caption)
                .monospacedDigit()
                .foregroundStyle(
                    trendIsDown ? StreamifyColors.errorRed : StreamifyColors.successGreen
                )
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(
                    Capsule().fill(
                        trendIsDown
                            ? StreamifyColors.errorRed.opacity(0.12)
                            : StreamifyColors.successGreen.opacity(0.12)
                    )
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(StreamifyDimens.spaceMD)
        .cardSurface(hoverable: false)
    }
}

/// One icon + copy row inside the Edge mesh / Engine health panels, with an
/// optional trailing badge tinted like the row.
private struct HealthRow: View {
    let icon: String
    let title: String
    var tint: Color = StreamifyColors.textMain
    var badge: String?

    var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Image(systemName: icon)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 26, height: 26)
                .background(Capsule().fill(tint.opacity(0.12)))
            Text(title)
                .font(StreamifyType.bodyMedium)
                .foregroundStyle(StreamifyColors.textMain)
            Spacer(minLength: 0)
            if let badge {
                Text(badge)
                    .font(StreamifyType.caption)
                    .tracking(0.6)
                    .foregroundStyle(tint)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(tint.opacity(0.14)))
            }
        }
    }
}

public struct AdminDashboardView: View {
    public var metrics: [MetricTile]

    public init(metrics: [MetricTile] = PreviewCatalog.adminMetrics) {
        self.metrics = metrics
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                header
                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: StreamifyDimens.spaceMD),
                        GridItem(.flexible(), spacing: StreamifyDimens.spaceMD),
                    ],
                    spacing: StreamifyDimens.spaceMD
                ) {
                    ForEach(metrics) { tile in
                        MetricCard(tile: tile)
                    }
                }
                adminSection("Edge mesh") {
                    HealthRow(icon: StreamifyIcon.cpu, title: "57 tasks queued")
                    HealthRow(icon: StreamifyIcon.network, title: "3 regions")
                    HealthRow(icon: StreamifyIcon.clock, title: "p95 sync 214 ms")
                }
                adminSection("Engine health") {
                    HealthRow(icon: StreamifyIcon.wave, title: "DAC drift median 3.1 ppm")
                    HealthRow(
                        icon: StreamifyIcon.checkmarkCircle,
                        title: "Pipeline live",
                        tint: StreamifyColors.successGreen,
                        badge: "LIVE"
                    )
                    HealthRow(
                        icon: StreamifyIcon.warning,
                        title: "1 legacy fallback active",
                        tint: StreamifyColors.warningAmber
                    )
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
    }

    private var header: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Text("Command center")
                .font(StreamifyType.headlineLarge)
                .foregroundStyle(StreamifyColors.textMain)
            Spacer(minLength: 0)
            HStack(spacing: 4) {
                Image(systemName: StreamifyIcon.crown)
                    .font(.system(size: 10, weight: .bold))
                Text("ADMIN")
                    .font(StreamifyType.caption)
                    .tracking(1.0)
            }
            .foregroundStyle(StreamifyColors.textOnActiveChip)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Capsule().fill(StreamifyColors.primary))
        }
    }

    private func adminSection(
        _ title: String,
        @ViewBuilder rows: () -> some View
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            AdminSectionLabel(title: title)
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
                rows()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(StreamifyDimens.spaceLG)
            .cardSurface(hoverable: false)
        }
    }
}

#Preview("Admin dashboard") {
    AdminDashboardView()
}
