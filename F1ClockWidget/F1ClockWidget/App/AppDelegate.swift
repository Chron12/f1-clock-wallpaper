import AppKit

final class AppDelegate: NSObject, NSApplicationDelegate {
    var window: WidgetWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let window = WidgetWindow()
        window.makeKeyAndOrderFront(nil)
        self.window = window
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }
}
