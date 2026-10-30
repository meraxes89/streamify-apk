import Foundation
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-07 (Phase 5 / MP-CAP03): the Apple CarPlay integration —
// `CPTemplateApplicationSceneDelegate` with a `CPTabBarTemplate` of three
// tabs (Home, Playlists, Now Playing) and the `CPNowPlayingTemplate`.
//
// CONTROL WIRING (the directive's "hardware car knob and steering wheel
// controls wired to EngineGateway"):
//  * Steering-wheel / knob NEXT / PREV / PLAY-PAUSE arrive through
//    MPRemoteCommandCenter — ALREADY bound by RemoteCommandCenterAdapter
//    (SW-ENG3-03) onto the EngineGateway plane with the MP-DEC-07 debounce.
//    CarPlay needs no second command path: the knob IS a remote command.
//  * Template taps (list rows, tab picks) run the CPListItem activation
//    handlers here, routing onto the SAME `RemoteCommandRouting` plane.
//  * Browse content (playlists, shortcuts) comes from the injectable
//    `CarPlayContentProviding` seam — the shell feeds library truth; the
//    empty default renders honest "nothing here yet" rows, never
//    fabricated content.
//
// HOST-APP WIRING (the wrapper target's responsibility — the library
// compiles without it): Info.plist UIApplicationSceneManifest declares a
// CPTemplateApplicationSceneSessionRoleApplication scene whose delegate
// class is this type, the `com.apple.developer.carplay-audio` entitlement
// is present, and `UIBackgroundModes` includes audio.
// ─────────────────────────────────────────────────────────────────────────────

#if canImport(CarPlay)
import CarPlay

// MARK: - Browse content seam

/// One browsable row (playlist, shortcut, track) rendered in the car.
struct CarPlayBrowseItem {
    let text: String
    let detailText: String?
    let artwork: UIImage?
    /// The selection command (nil rows are informational only).
    let command: RemoteCommand?
}

/// The CarPlay browse-content seam (the shell feeds library truth; tests
/// install deterministic providers).
@MainActor
protocol CarPlayContentProviding: AnyObject {
    /// The Home tab's shortcut rows (above Now Playing / Playlists links).
    func homeShortcuts() -> [CarPlayBrowseItem]
    /// The Playlists tab's rows.
    func playlists() -> [CarPlayBrowseItem]
}

/// The honest empty default (pre-library lane): the car renders
/// "nothing here yet" rows instead of fabricated playlists.
@MainActor
final class EmptyCarPlayContentProvider: CarPlayContentProviding {
    func homeShortcuts() -> [CarPlayBrowseItem] { [] }
    func playlists() -> [CarPlayBrowseItem] { [] }
}

// MARK: - Template factory (pure construction, white-box tested)

/// Builds the CarPlay template tree. Every function is pure construction
/// over value inputs — the XCTest lane asserts composition (titles, tab
/// order, row wiring) without a car scene.
enum CarPlayTemplateFactory {

    static let homeTabTitle = "Home"
    static let playlistsTabTitle = "Playlists"
    static let nowPlayingTabTitle = "Now Playing"

    /// The Home tab's launcher rows (Now Playing + a pushed Playlists
    /// browser) — shared by the initial build and `reloadBrowseContent`.
    static func launcherRows(
        onNowPlaying: @escaping () -> Void,
        onPlaylists: @escaping () -> Void
    ) -> [CPListItem] {
        let nowPlayingRow = CPListItem(
            text: nowPlayingTabTitle,
            detailText: "Open the player",
            image: nil,
            accessoryImage: nil,
            accessoryType: .playbackControls
        ) { selected, completion in
            if selected { onNowPlaying() }
            completion()
        }

        let playlistsRow = CPListItem(
            text: playlistsTabTitle,
            detailText: "Browse playlists",
            image: nil,
            accessoryImage: nil,
            accessoryType: .disclosureIndicator
        ) { selected, completion in
            if selected { onPlaylists() }
            completion()
        }
        return [nowPlayingRow, playlistsRow]
    }

    /// The Home tab's shortcut rows (the provider's browse content, each
    /// carrying its selection command) — shared by the initial build and
    /// `reloadBrowseContent`.
    static func shortcutRows(
        shortcuts: [CarPlayBrowseItem],
        onCommand: @escaping (RemoteCommand) -> Void
    ) -> [CPListItem] {
        shortcuts.map { item in
            CPListItem(
                text: item.text,
                detailText: item.detailText,
                image: item.artwork.map(CPListImage.init(image:)),
                accessoryImage: nil,
                accessoryType: .none
            ) { selected, completion in
                if selected, let command = item.command {
                    onCommand(command)
                }
                completion()
            }
        }
    }

    /// The Home tab: the launcher rows + the provider's shortcuts.
    static func homeTemplate(
        shortcuts: [CarPlayBrowseItem],
        onNowPlaying: @escaping () -> Void,
        onPlaylists: @escaping () -> Void,
        onCommand: @escaping (RemoteCommand) -> Void
    ) -> CPListTemplate {
        let section = CPListSection(
            items: launcherRows(
                onNowPlaying: onNowPlaying,
                onPlaylists: onPlaylists
            ) + shortcutRows(shortcuts: shortcuts, onCommand: onCommand)
        )
        return CPListTemplate(
            title: homeTabTitle,
            subtitle: nil,
            sections: [section]
        )
    }

    /// The Playlists tab (empty provider → the honest placeholder row).
    static func playlistsTemplate(
        playlists: [CarPlayBrowseItem],
        onCommand: @escaping (RemoteCommand) -> Void
    ) -> CPListTemplate {
        let rows: [CPListItem]
        if playlists.isEmpty {
            let placeholder = CPListItem(
                text: "No playlists yet",
                detailText: "Create them in the app",
                image: nil,
                accessoryImage: nil,
                accessoryType: .none
            ) { _, completion in completion() }
            placeholder.isEnabled = false
            rows = [placeholder]
        } else {
            rows = playlists.map { item in
                CPListItem(
                    text: item.text,
                    detailText: item.detailText,
                    image: item.artwork.map(CPListImage.init(image:)),
                    accessoryImage: nil,
                    accessoryType: .disclosureIndicator
                ) { selected, completion in
                    if selected, let command = item.command {
                        onCommand(command)
                    }
                    completion()
                }
            }
        }
        return CPListTemplate(
            title: playlistsTabTitle,
            subtitle: nil,
            sections: [CPListSection(items: rows)]
        )
    }

    /// The three-tab bar: Home, Playlists, Now Playing (the shared
    /// CPNowPlayingTemplate — its transport controls route through
    /// MPRemoteCommandCenter, i.e. the EngineGateway plane).
    static func tabBarTemplate(
        home: CPListTemplate,
        playlists: CPListTemplate,
        nowPlaying: CPNowPlayingTemplate
    ) -> CPTabBarTemplate {
        CPTabBarTemplate(templates: [home, playlists, nowPlaying])
    }

    /// The tab order the acceptance checklist pins.
    static func tabTitles(of tabBar: CPTabBarTemplate) -> [String] {
        tabBar.templates.compactMap { ($0 as? CPListTemplate)?.title }
            + (tabBar.templates.contains { $0 is CPNowPlayingTemplate }
               ? [nowPlayingTabTitle] : [])
    }
}

// MARK: - The scene delegate

/// The CarPlay scene delegate: connects the template tree on scene connect,
/// routes template taps onto the shared command plane, tears everything
/// down on disconnect. The shell assigns `router` (the PlaybackViewModel)
/// before the scene connects.
@available(iOS 14.0, *)
@MainActor
final class CarPlaySceneDelegate: NSObject, CPTemplateApplicationSceneDelegate {

    /// The shared command plane (weak: the scene outlives nothing).
    weak var router: RemoteCommandRouting?

    /// Browse content (library truth).
    var contentProvider: CarPlayContentProviding = EmptyCarPlayContentProvider()

    private weak var interfaceController: CPInterfaceController?

    private var homeTemplate: CPListTemplate?
    private var playlistsTemplate: CPListTemplate?

    // MARK: CPTemplateApplicationSceneDelegate (audio app variant)

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController,
        to window: CPWindow
    ) {
        self.interfaceController = interfaceController

        let home = CarPlayTemplateFactory.homeTemplate(
            shortcuts: contentProvider.homeShortcuts(),
            onNowPlaying: { [weak self] in
                self?.presentNowPlaying()
            },
            onPlaylists: { [weak self] in
                self?.pushPlaylistsBrowser()
            },
            onCommand: { [weak self] command in
                self?.router?.remoteCommandReceived(command)
            }
        )
        let playlists = CarPlayTemplateFactory.playlistsTemplate(
            playlists: contentProvider.playlists(),
            onCommand: { [weak self] command in
                self?.router?.remoteCommandReceived(command)
            }
        )
        let tabBar = CarPlayTemplateFactory.tabBarTemplate(
            home: home,
            playlists: playlists,
            nowPlaying: CPNowPlayingTemplate.shared
        )

        homeTemplate = home
        playlistsTemplate = playlists

        interfaceController.setRootTemplates([tabBar], animated: false) { _, _ in }
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnect interfaceController: CPInterfaceController,
        from window: CPWindow
    ) {
        interfaceController.setRootTemplates([], animated: false) { _, _ in }
        self.interfaceController = nil
        homeTemplate = nil
        playlistsTemplate = nil
    }

    // MARK: - Navigation helpers

    /// Presents the shared Now Playing template (the Home tab's launcher
    /// row and any shortcut that wants the player).
    func presentNowPlaying() {
        interfaceController?.presentTemplate(
            CPNowPlayingTemplate.shared,
            animated: true
        ) { _, _ in }
    }

    /// Pushes a FRESH Playlists browser page (a template instance can
    /// appear in only one hierarchy location — the tab keeps its own).
    func pushPlaylistsBrowser() {
        let playlists = CarPlayTemplateFactory.playlistsTemplate(
            playlists: contentProvider.playlists(),
            onCommand: { [weak self] command in
                self?.router?.remoteCommandReceived(command)
            }
        )
        interfaceController?.pushTemplate(playlists, animated: true) { _, _ in }
    }

    /// Rebuilds the browse tabs' row content (library truth changed).
    func reloadBrowseContent() {
        guard let home = homeTemplate, let playlists = playlistsTemplate else { return }
        home.updateSections([
            CPListSection(
                items: CarPlayTemplateFactory.launcherRows(
                    onNowPlaying: { [weak self] in self?.presentNowPlaying() },
                    onPlaylists: { [weak self] in self?.pushPlaylistsBrowser() }
                ) + CarPlayTemplateFactory.shortcutRows(
                    shortcuts: contentProvider.homeShortcuts()
                ) { [weak self] command in
                    self?.router?.remoteCommandReceived(command)
                }
            )
        ])
        playlists.updateSections([
            CPListSection(
                items: CarPlayTemplateFactory.shortcutRows(
                    shortcuts: contentProvider.playlists()
                ) { [weak self] command in
                    self?.router?.remoteCommandReceived(command)
                }
            )
        ])
    }
}

#endif  // canImport(CarPlay)
