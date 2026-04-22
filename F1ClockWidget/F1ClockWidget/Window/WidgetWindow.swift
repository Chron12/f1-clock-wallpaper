import AppKit

final class WidgetWindow: NSPanel {
    private let settings: WidgetSettings

    convenience init() {
        self.init(settings: WidgetSettings())
    }

    init(settings: WidgetSettings) {
        self.settings = settings
        super.init(
            contentRect: settings.windowFrame,
            styleMask: [.titled, .closable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.desktopWindow)))
        isFloatingPanel = false
        becomesKeyOnlyIfNeeded = true
        titlebarAppearsTransparent = true
        titleVisibility = .hidden
        isMovableByWindowBackground = true
        backgroundColor = .clear
        isOpaque = false
        hasShadow = false
    }
}
