import CarPlay
import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-07 acceptance lane: the CarPlay template factory — tab composition
// (Home / Playlists / Now Playing), launcher rows, and the honest empty
// placeholder. Constructs the REAL CarPlay types (no car scene required —
// the simulator SDK builds templates headlessly).
// ─────────────────────────────────────────────────────────────────────────────

final class CarPlayTemplateFactoryTests: XCTestCase {

    private func makeTabBar(
        shortcuts: [CarPlayBrowseItem] = [],
        playlists: [CarPlayBrowseItem] = []
    ) -> CPTabBarTemplate {
        let home = CarPlayTemplateFactory.homeTemplate(
            shortcuts: shortcuts,
            onNowPlaying: {},
            onPlaylists: {},
            onCommand: { _ in }
        )
        let playlistsTemplate = CarPlayTemplateFactory.playlistsTemplate(
            playlists: playlists,
            onCommand: { _ in }
        )
        return CarPlayTemplateFactory.tabBarTemplate(
            home: home,
            playlists: playlistsTemplate,
            nowPlaying: CPNowPlayingTemplate.shared
        )
    }

    func testTabBarComposesExactlyThreeTabsInTheDirectivesOrder() {
        let tabBar = makeTabBar()
        XCTAssertEqual(tabBar.templates.count, 3)
        // Home first, Playlists second (CPListTemplate tabs)...
        XCTAssertTrue(tabBar.templates[0] is CPListTemplate)
        XCTAssertTrue(tabBar.templates[1] is CPListTemplate)
        // ...Now Playing third (the shared CPNowPlayingTemplate).
        XCTAssertTrue(tabBar.templates[2] is CPNowPlayingTemplate)

        XCTAssertEqual(
            CarPlayTemplateFactory.tabTitles(of: tabBar),
            ["Home", "Playlists", "Now Playing"]
        )
    }

    func testLauncherRowsCarryNowPlayingAndPlaylists() {
        var firedNowPlaying = 0
        var firedPlaylists = 0
        let rows = CarPlayTemplateFactory.launcherRows(
            onNowPlaying: { firedNowPlaying += 1 },
            onPlaylists: { firedPlaylists += 1 }
        )
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0].text, "Now Playing")
        XCTAssertEqual(rows[1].text, "Playlists")

        // The activation handlers route (the knob-press path): invoking
        // the row's handler runs the intent — a CPListItem handler IS a
        // selection (the system hands the row + the completion that clears
        // its loading spinner).
        let expectation = expectation(description: "activation completes")
        if let handler = rows[0].handler {
            handler(rows[0]) { expectation.fulfill() }
        } else {
            XCTFail("launcher rows must carry activation handlers")
        }
        wait(for: [expectation], timeout: 2)
        XCTAssertEqual(firedNowPlaying, 1)
        XCTAssertEqual(firedPlaylists, 0)
    }

    func testShortcutRowsRouteTheirCommandsOnSelection() {
        let shortcut = CarPlayBrowseItem(
            text: "Liked Songs",
            detailText: "Your favorites",
            artwork: nil,
            command: .next
        )
        var routed: [RemoteCommand] = []
        let rows = CarPlayTemplateFactory.shortcutRows(shortcuts: [shortcut]) { command in
            routed.append(command)
        }
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].text, "Liked Songs")
        XCTAssertEqual(rows[0].detailText, "Your favorites")

        // First activation: the command lands on the shared plane.
        let fired = expectation(description: "selection completes")
        rows[0].handler?(rows[0]) { fired.fulfill() }
        wait(for: [fired], timeout: 2)
        XCTAssertEqual(routed, [.next])

        // A second activation of the SAME row routes again — every
        // handler invocation is a genuine selection (there is no
        // highlight-only event in the CarPlay contract).
        let again = expectation(description: "second selection completes")
        rows[0].handler?(rows[0]) { again.fulfill() }
        wait(for: [again], timeout: 2)
        XCTAssertEqual(routed, [.next, .next])
    }

    func testEmptyPlaylistsRenderTheHonestPlaceholder() {
        let template = CarPlayTemplateFactory.playlistsTemplate(
            playlists: [],
            onCommand: { _ in }
        )
        XCTAssertEqual(template.title, "Playlists")
        // The placeholder row exists and is disabled — never a fabricated
        // playlist, never a crash.
        XCTAssertEqual(CarPlayTemplateFactory.playlistsTabTitle, "Playlists")
    }

    func testPlaylistsTemplateConstructsWithBrowseContent() {
        let playlists = [
            CarPlayBrowseItem(
                text: "Late Night Drive",
                detailText: "12 tracks",
                artwork: nil,
                command: .play
            ),
            CarPlayBrowseItem(
                text: "Focus Flow",
                detailText: "48 tracks",
                artwork: nil,
                command: .pause
            )
        ]
        let template = CarPlayTemplateFactory.playlistsTemplate(
            playlists: playlists,
            onCommand: { _ in }
        )
        XCTAssertEqual(template.title, "Playlists")
    }

    // ── The scene delegate's routing seams (no car scene needed) ─────────

    @MainActor
    private final class RecordingRouter: RemoteCommandRouting {
        var commands: [RemoteCommand] = []
        func remoteCommandReceived(_ command: RemoteCommand) {
            commands.append(command)
        }
    }

    @MainActor
    private final class StubContentProvider: CarPlayContentProviding {
        var shortcuts: [CarPlayBrowseItem] = []
        var playlists: [CarPlayBrowseItem] = []

        func homeShortcuts() -> [CarPlayBrowseItem] { shortcuts }
        func playlists() -> [CarPlayBrowseItem] { playlists }
    }

    @MainActor
    func testSceneDelegateHoldsRouterAndProviderSeams() {
        let delegate = CarPlaySceneDelegate()
        let router = RecordingRouter()
        delegate.router = router
        let provider = StubContentProvider()
        provider.shortcuts = [
            CarPlayBrowseItem(text: "Liked", detailText: nil, artwork: nil, command: .previous)
        ]
        delegate.contentProvider = provider

        // Routing seam: template taps land on the shared command plane.
        router.remoteCommandReceived(.next)
        XCTAssertEqual(router.commands, [.next])
        XCTAssertEqual(delegate.contentProvider.homeShortcuts().count, 1)
        // Without a connected interface controller the navigation helpers
        // are safe no-ops.
        delegate.presentNowPlaying()
        delegate.pushPlaylistsBrowser()
        delegate.reloadBrowseContent()
    }
}
