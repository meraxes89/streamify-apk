import Foundation

#if canImport(AppKit)
import AppKit
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-06 (Phase 5 / SW-DEC-04): the macOS Menu Bar extra — a lightweight
// NSStatusItem mini-player with quick transport controls and a volume
// slider, straight from the Mac menu bar.
//
// This file is macOS-only (`#if canImport(AppKit)`) and DORMANT today: the
// package currently builds for iOS only; the moment Engineer 1's universal
// packaging (SW-ENG1-01: `.macOS(.v14)`) lands, this compiles in with zero
// edits. Two integration paths:
//
//  * SWIFTUI SCENE — add `MenuBarExtra("Streamify", systemImage: "music.note")`
//    to the host app's `Scene` body with `StreamifyMenuBarMiniPlayer` as the
//    content (macOS 13+ `.menuBarExtraStyle(.window)`).
//  * CLASSIC NSStatusItem — install `MenuBarStatusItemController` for shells
//    that want pixel-level control; it builds the menu programmatically
//    (transport items + a live volume slider view) and posts onto the SAME
//    cross-process command plane as the widgets (WidgetCommandDispatcher /
//    the App Group mailbox — the menu bar extra may run in the app process
//    OR a companion, the plane does not care).
//
// CLOCK DISCIPLINE (MP-DEC-03): the mini-player's progress renders from the
// snapshot's hardware-anchored position + the monotonic delta since
// `snapshotAt` (WidgetPlaybackMath) — never a wall-clock estimate.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - The SwiftUI mini-player (MenuBarExtra content)

/// The menu-bar mini-player content: transport row, track identity, live
/// scrubber, and the volume slider. Commands post through the shared
/// dispatcher; state renders from the App Group snapshot store.
@available(macOS 13.0, *)
struct StreamifyMenuBarMiniPlayer: View {

    @State private var snapshot: WidgetPlaybackSnapshot?

    private let store: WidgetPlaybackStore
    private let pollInterval: TimeInterval

    init(
        store: WidgetPlaybackStore = WidgetPlaybackStore(),
        pollInterval: TimeInterval = 2
    ) {
        self.store = store
        self.pollInterval = pollInterval
    }

    var body: some View {
        VStack(spacing: 10) {
            if let snapshot, !snapshot.trackId.isEmpty {
                HStack(spacing: 10) {
                    MenuBarArtworkView(artworkData: snapshot.artwork)
                        .frame(width: 44, height: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(snapshot.title)
                            .font(.headline)
                            .lineLimit(1)
                        Text(snapshot.artist)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        MenuBarScrubberView(snapshot: snapshot)
                    }
                }
                MenuBarTransportRow(isPlaying: snapshot.isPlaying)
                MenuBarVolumeSlider(volume: snapshot.volume)
            } else {
                Text("Streamify is idle")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                MenuBarTransportRow(isPlaying: false)
            }
        }
        .padding(12)
        .frame(width: 280)
        .onAppear { snapshot = store.snapshot() }
        .task {
            // Duty-cycle refresh (the snapshot store is latest-wins: 0.5 Hz
            // keeps the scrubber honest at menu-bar-acceptable cost).
            while !Task.isCancelled {
                snapshot = store.snapshot()
                try? await Task.sleep(nanoseconds: UInt64(pollInterval * 1_000_000_000))
            }
        }
    }
}

/// Transport row for the mini-player: classic AppKit-styled buttons posting
/// the shared intents (the SAME commands the iOS widgets post).
@available(macOS 13.0, *)
struct MenuBarTransportRow: View {
    let isPlaying: Bool

    var body: some View {
        HStack(spacing: 18) {
            Button {
                WidgetCommandDispatcher.post(.previous)
            } label: {
                Image(systemName: "backward.fill")
            }
            Button {
                WidgetCommandDispatcher.post(.togglePlayPause)
            } label: {
                Image(systemName: isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.title)
            }
            .buttonStyle(.borderless)
            Button {
                WidgetCommandDispatcher.post(.next)
            } label: {
                Image(systemName: "forward.fill")
            }
        }
        .buttonStyle(.borderless)
        .frame(maxWidth: .infinity)
    }
}

/// The volume slider: local editing state while dragging (no IPC churn),
/// one absolute post on edit end.
@available(macOS 13.0, *)
struct MenuBarVolumeSlider: View {
    @State private var editingVolume: Double?
    private let reported: Double

    init(volume: Double) {
        reported = volume
    }

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "speaker.wave.1")
                .foregroundStyle(.secondary)
            Slider(
                value: Binding(
                    get: { editingVolume ?? reported },
                    set: { editingVolume = $0 }
                ),
                in: 0...1
            ) { editing in
                if !editing, let volume = editingVolume {
                    WidgetCommandDispatcher.postVolume(volume)
                    editingVolume = nil
                }
            }
            Image(systemName: "speaker.wave.3")
                .foregroundStyle(.secondary)
        }
    }
}

/// Menu-bar scrubber: hardware-anchored progress (WidgetPlaybackMath).
@available(macOS 13.0, *)
struct MenuBarScrubberView: View {
    let snapshot: WidgetPlaybackSnapshot

    var body: some View {
        let fraction = WidgetPlaybackMath.progress(snapshot: snapshot, at: .now)
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(.quaternary)
                Capsule()
                    .fill(.accentColor)
                    .frame(width: (fraction ?? 0) * proxy.size.width)
            }
        }
        .frame(height: 4)
        .opacity(fraction == nil ? 0.4 : 1)
    }
}

/// Artwork or the placeholder glyph.
@available(macOS 13.0, *)
struct MenuBarArtworkView: View {
    let artworkData: Data?

    var body: some View {
        if let image = artworkData.flatMap(NSImage.init(data:)) {
            Image(nsImage: image)
                .resizable()
                .scaledToFill()
                .clipShape(RoundedRectangle(cornerRadius: 8))
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 8).fill(.quaternary)
                Image(systemName: "music.note")
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - The classic NSStatusItem controller

/// Programmatic menu-bar integration for shells without a SwiftUI scene
/// hook: owns an `NSStatusItem` whose menu carries the transport items, a
/// live volume slider (NSMenuItem + custom view), and opens the mini
/// player. Install with `install()`; remove with `remove()` (idempotent).
@available(macOS 13.0, *)
@MainActor
final class MenuBarStatusItemController: ObservableObject {

    private var statusItem: NSStatusItem?

    private let store: WidgetPlaybackStore
    private var snapshot: WidgetPlaybackSnapshot?
    private var pollTimer: Timer?

    init(store: WidgetPlaybackStore = WidgetPlaybackStore()) {
        self.store = store
    }

    var isInstalled: Bool { statusItem != nil }

    /// Installs (or reinstalls) the status item + menu.
    func install() {
        remove()
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = NSImage(systemSymbolName: "music.note", accessibilityDescription: "Streamify")
        item.menu = buildMenu()
        statusItem = item
        refreshSnapshot()
        startPolling()
    }

    /// Removes the status item and stops the duty-cycle refresh.
    func remove() {
        pollTimer?.invalidate()
        pollTimer = nil
        if let item = statusItem {
            NSStatusBar.system.removeStatusItem(item)
        }
        statusItem = nil
    }

    // MARK: - Menu construction

    private func buildMenu() -> NSMenu {
        let menu = NSMenu()
        menu.autoenablesItems = false

        let nowPlaying = NSMenuItem(
            title: "Streamify — Now Playing",
            action: nil,
            keyEquivalent: ""
        )
        nowPlaying.isEnabled = false
        menu.addItem(nowPlaying)

        menu.addItem(.separator())

        menu.addItem(transportItem(
            title: "Play / Pause",
            action: #selector(postToggle),
            key: " "
        ))
        menu.addItem(transportItem(
            title: "Next Track",
            action: #selector(postNext),
            key: "\(String(format: "%C", UInt16(0x2192)))"  // right arrow
        ))
        menu.addItem(transportItem(
            title: "Previous Track",
            action: #selector(postPrevious),
            key: "\(String(format: "%C", UInt16(0x2190)))"  // left arrow
        ))

        menu.addItem(.separator())

        // The volume slider as a menu item with a custom view.
        let volumeItem = NSMenuItem(title: "Volume", action: nil, keyEquivalent: "")
        volumeItem.view = MenuBarVolumeSliderView(store: store)
        menu.addItem(volumeItem)

        return menu
    }

    private func transportItem(
        title: String,
        action selector: Selector,
        key: String
    ) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: selector, keyEquivalent: key)
        item.target = self
        item.isEnabled = true
        return item
    }

    // MARK: - Command posting (the shared cross-process plane)

    @objc private func postToggle() {
        WidgetCommandDispatcher.post(.togglePlayPause)
    }

    @objc private func postNext() {
        WidgetCommandDispatcher.post(.next)
    }

    @objc private func postPrevious() {
        WidgetCommandDispatcher.post(.previous)
    }

    // MARK: - Duty-cycle refresh

    private func startPolling() {
        pollTimer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.refreshSnapshot()
            }
        }
    }

    private func refreshSnapshot() {
        snapshot = store.snapshot()
        if let snapshot, !snapshot.trackId.isEmpty,
           let item = statusItem?.menu?.items.first {
            item.title = "♪ \(snapshot.title) — \(snapshot.artist)"
        }
    }
}

/// The AppKit volume slider embedded in the status menu: local dragging
/// state, one absolute post on release (no IPC churn while scrubbing).
@available(macOS 13.0, *)
@MainActor
private final class MenuBarVolumeSliderView: NSView {

    private let slider = NSSlider(value: 0.5, minValue: 0, maxValue: 1, target: nil, action: nil)
    private let store: WidgetPlaybackStore

    init(store: WidgetPlaybackStore) {
        self.store = store
        super.init(frame: NSRect(x: 0, y: 0, width: 200, height: 24))
        slider.target = self
        slider.action = #selector(sliderChanged(_:))
        addSubview(slider)
        slider.frame = bounds.insetBy(dx: 8, dy: 2)
        // Seed from the latest snapshot's volume.
        if let snapshot = store.snapshot() {
            slider.doubleValue = snapshot.volume
        }
    }

    required init?(coder: NSCoder) {
        nil
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: 200, height: 24)
    }

    @objc private func sliderChanged(_ sender: NSSlider) {
        // Post on mouse-up only (continuous would flood the mailbox).
        if let event = NSApp.currentEvent, event.type == .leftMouseUp {
            var store = store
            store.postVolume(sender.doubleValue)
        }
    }
}

#endif  // canImport(AppKit)
