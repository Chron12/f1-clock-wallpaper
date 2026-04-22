import AppKit

final class WidgetWindow: NSPanel {
    private let settings: WidgetSettings

    convenience init() {
        self.init(settings: WidgetSettings())
    }

    init(settings: WidgetSettings) {
        self.settings = settings
        let frame = settings.windowFrame
        super.init(
            contentRect: frame,
            styleMask: [.titled, .closable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )

        // Desktop level — always behind normal windows
        level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.desktopWindow)))
        isFloatingPanel = false
        becomesKeyOnlyIfNeeded = true

        // No titlebar chrome
        titlebarAppearsTransparent = true
        titleVisibility = .hidden
        isMovableByWindowBackground = true

        // Transparent background
        backgroundColor = .clear
        isOpaque = false
        hasShadow = false

        // 12px corner radius + subtle border on content view
        contentView?.wantsLayer = true
        contentView?.layer?.cornerRadius = 12
        contentView?.layer?.masksToBounds = true
        contentView?.layer?.borderWidth = 1
        contentView?.layer?.borderColor = NSColor(white: 1, alpha: 0.05).cgColor

        // Minimum size
        minSize = NSSize(width: 300, height: 200)

        // Ensure window is on-screen (recover from saved off-screen position)
        ensureOnScreen()

        // Context menu for settings
        setupContextMenu()
    }

    override func setFrame(_ frameRect: NSRect, display flag: Bool) {
        super.setFrame(frameRect, display: flag)
        settings.windowFrame = frameRect
    }

    override func setFrame(_ frameRect: NSRect, display flag: Bool, animate animateFlag: Bool) {
        super.setFrame(frameRect, display: flag, animate: animateFlag)
        settings.windowFrame = frameRect
    }

    private func ensureOnScreen() {
        guard let screen = NSScreen.main else { return }
        let visible = screen.visibleFrame
        let f = frame
        // If saved position is off-screen, center on main screen
        if f.origin.x + f.size.width < visible.origin.x ||
           f.origin.x > visible.origin.x + visible.size.width ||
           f.origin.y + f.size.height < visible.origin.y ||
           f.origin.y > visible.origin.y + visible.size.height {
            setFrameOrigin(NSPoint(
                x: visible.origin.x + (visible.size.width - f.size.width) / 2,
                y: visible.origin.y + (visible.size.height - f.size.height) / 2
            ))
        }
    }

    private func setupContextMenu() {
        let menu = NSMenu()

        menu.addItem(withTitle: "Toggle Leaderboard", action: #selector(toggleLeaderboard), keyEquivalent: "")
        menu.addItem(withTitle: "Toggle Driver Labels", action: #selector(toggleDriverLabels), keyEquivalent: "")
        menu.addItem(withTitle: "Toggle Sector Colors", action: #selector(toggleSectorColors), keyEquivalent: "")
        menu.addItem(withTitle: "Toggle Glow Effects", action: #selector(toggleGlowEffects), keyEquivalent: "")
        menu.addItem(withTitle: "Toggle Tire Colors", action: #selector(toggleTireColors), keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")

        self.menu = menu
    }

    @objc private func toggleLeaderboard() { settings.showLeaderboard.toggle() }
    @objc private func toggleDriverLabels() { settings.showDriverLabels.toggle() }
    @objc private func toggleSectorColors() { settings.showSectorColors.toggle() }
    @objc private func toggleGlowEffects() { settings.showGlowEffects.toggle() }
    @objc private func toggleTireColors() { settings.showTireColors.toggle() }
}
