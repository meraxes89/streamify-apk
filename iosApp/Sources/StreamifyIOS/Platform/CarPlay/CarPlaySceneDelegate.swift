#if canImport(CarPlay) && canImport(UIKit)
import Foundation
import UIKit
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
    ///
    /// `CPListItem`'s activation is the `handler` PROPERTY (the designated
    /// initializer takes no trailing closure): the system hands the row + a
    /// completion that MUST be called to clear the row's loading spinner.
    static func launcherRows(
        onNowPlaying: @escaping () -> Void,
        onPlaylists: @escaping () -> Void
    ) -> [CPListItem] {
        let nowPlayingRow = CPListItem(
            text: nowPlayingTabTitle,
            detailText: "Open the player",
            image: nil,
            accessoryImage: nil,
            accessoryType: .none
        )
        nowPlayingRow.handler = { _, completion in
            onNowPlaying()
            completion()
        }

        let playlistsRow = CPListItem(
            text: playlistsTabTitle,
            detailText: "Browse playlists",
            image: nil,
            accessoryImage: nil,
            accessoryType: .disclosureIndicator
        )
        playlistsRow.handler = { _, completion in
            onPlaylists()
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
            let row = CPListItem(
                text: item.text,
                detailText: item.detailText,
                image: item.artwork,
                accessoryImage: nil,
                accessoryType: .none
            )
            row.handler = { _, completion in
                if let command = item.command {
                    onCommand(command)
                }
                completion()
            }
            return row
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
            )
            placeholder.handler = { _, completion in completion() }
            placeholder.isEnabled = false
            rows = [placeholder]
        } else {
            rows = playlists.map { item in
                let row = CPListItem(
                    text: item.text,
                    detailText: item.detailText,
                    image: item.artwork,
                    accessoryImage: nil,
                    accessoryType: .disclosureIndicator
                )
                row.handler = { _, completion in
                    if let command = item.command {
                        onCommand(command)
                    }
                    completion()
                }
                return row
            }
        }
        return CPListTemplate(
            title: playlistsTabTitle,
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

// MARK: - The live queue content provider (WS-B B6)

/// Browse content from the REAL library lane (WS-B B6: "CarPay renders
/// browse/playback from real library"): the shortcuts are the live queue's
/// rows — now playing first, then up next — so the car mirrors exactly
/// what the phone's queue screen shows. The Playlists tab stays the honest
/// empty placeholder until a playlist surface crosses the FFI contract
/// (the placeholder row is built by the template factory for exactly
/// this state).
///
/// The provider reads the shell's QueueViewModel synchronously (its
/// rev-guarded projections are always consistent); a gone view model (the
/// shell is app-lifetime, so this is belt-and-braces) degrades to the
/// honest empty provider.
@MainActor
final class QueueCarPlayContentProvider: CarPlayContentProviding {
    private weak var queueModel: QueueViewModel?

    init(queueModel: QueueViewModel? = nil) {
        self.queueModel = queueModel
    }

    func homeShortcuts() -> [CarPlayBrowseItem] {
        guard let queueModel, !queueModel.tracks.isEmpty else { return [] }
        var rows: [CarPlayBrowseItem] = []
        if let current = queueModel.current {
            rows.append(CarPlayBrowseItem(
                text: current.title,
                detailText: "\(current.artist) — now playing",
                artwork: nil,
                command: nil  // informational row; transport rides Now Playing
            ))
        }
        for entry in queueModel.upNext.prefix(5) {
            rows.append(CarPlayBrowseItem(
                text: entry.track.title,
                detailText: "\(entry.track.artist) — up next",
                artwork: nil,
                command: nil
            ))
        }
        return rows
    }

    func playlists() -> [CarPlayBrowseItem] {
        []  // no playlist surface on the FFI contract yet — the honest empty
    }
}

// MARK: - The scene delegate

/// The CarPay scene delegate: connects the template tree on scene connect,
/// routes template taps onto the shared command plane, tears everything
/// down on disconnect.
///
/// WIRING (WS-B B6, the "router unassigned" fix): the scene delegate is
/// instantiated BY THE SYSTEM from the host's scene manifest — the shell
/// cannot hand it a constructor dependency. So the app-lifetime shell
/// installs its shared surfaces here (`sharedRouter` /
/// `sharedContentProvider`), and `connect` binds them into the instance
/// before the template tree builds. Weak by design: the scene can never
/// keep a torn-down shell alive.
@available(iOS 14.0, *)
@MainActor
final class CarPlaySceneDelegate: NSObject, CPTemplateApplicationSceneDelegate {

    /// The shell's shared command plane (the transport VM), installed at
    /// app start; bound into the delegate on scene connect.
    @MainActor static weak var sharedRouter: RemoteCommandRouting?

    /// The shell's shared browse content (the live queue provider),
    /// installed at app start; bound into the delegate on scene connect.
    @MainActor static var sharedContentProvider: CarPlayContentProviding?

    /// The shared command plane (weak: the scene outlives nothing).
    weak var router: RemoteCommandRouting?

    /// Browse content (library truth).
    var contentProvider: CarPlayContentProviding = EmptyCarPlayContentProvider()

    private weak var interfaceController: CPInterfaceController?

    private var homeTemplate: CPListTemplate?
    private var playlistsTemplate: CPListTemplate?

    // MARK: CPTemplateApplicationSceneDelegate (audio app variant)
    //
    // NONISOLATED witnesses: the protocol's requirements are nonisolated,
    // and a @MainActor-isolated witness would only warn today (and harden
    // into an error under Swift 6). CarPlay delivers these callbacks on
    // the main thread; the bodies hop to the main actor explicitly.

    nonisolated func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController,
        to window: CPWindow
    ) {
        Task { @MainActor in
            connect(interfaceController: interfaceController)
        }
    }

    nonisolated func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnect interfaceController: CPInterfaceController,
        from window: CPWindow
    ) {
        Task { @MainActor in
            disconnect()
        }
    }

    private func connect(interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController

        // WS-B B6: bind the shell's shared surfaces (installed at app
        // start) before the template tree builds — `reloadBrowseContent`
        // re-reads the provider, so the car mirrors the live queue.
        if router == nil {
            router = CarPlaySceneDelegate.sharedRouter
        }
        if CarPlaySceneDelegate.sharedContentProvider != nil, contentProvider is EmptyCarPlayContentProvider {
            contentProvider = CarPlaySceneDelegate.sharedContentProvider ?? contentProvider
        }

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

        interfaceController.setRootTemplate(tabBar, animated: false) { _, _ in }
    }

    private func disconnect() {
        // The scene is going away and the system discards the template
        // hierarchy with it. setRootTemplate's Swift surface takes a
        // NON-optional CPTemplate (nil is not expressible), and the
        // documented didDisconnect contract is state cleanup — so: drop
        // our references and let the teardown happen with the scene.
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

#endif  // canImport(CarPlay) && canImport(UIKit)
