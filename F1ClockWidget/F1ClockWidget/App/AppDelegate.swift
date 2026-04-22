import AppKit
import MetalKit
import IOKit.ps

final class AppDelegate: NSObject, NSApplicationDelegate {
    var window: WidgetWindow?
    var renderer: MetalRenderer?
    var engine: RaceEngine?
    var displayLink: CVDisplayLink?
    private var lastFrameTime: CFTimeInterval = 0
    private var lastHourIndex = -1
    private let settings = WidgetSettings() // single shared instance

    func applicationDidFinishLaunching(_ notification: Notification) {
        let engine = RaceEngine()
        self.engine = engine

        // Load initial race
        let index = RaceEngine.currentRaceIndex()
        lastHourIndex = index
        if let race = try? engine.loadRace(index: index) {
            engine.setRace(race)
        }

        let renderer = MetalRenderer(settings: settings, engine: engine)
        self.renderer = renderer

        let window = WidgetWindow(settings: settings)
        window.contentView = createMetalView(renderer: renderer)
        window.makeKeyAndOrderFront(nil)
        self.window = window

        startDisplayLink()
    }

    private func createMetalView(renderer: MetalRenderer) -> MTKView {
        let device = MTLCreateSystemDefaultDevice()!
        let mtkView = MTKView(frame: .zero, device: device)
        mtkView.delegate = renderer
        mtkView.isPaused = true // We drive frames manually via display link
        mtkView.enableSetNeedsDisplay = false
        mtkView.autoresizingMask = [.width, .height]
        mtkView.layer?.isOpaque = false
        mtkView.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 0)
        return mtkView
    }

    private func startDisplayLink() {
        var link: CVDisplayLink?
        CVDisplayLinkCreateWithActiveCGDisplays(&link)
        guard let displayLink else { return }
        self.displayLink = displayLink

        CVDisplayLinkSetOutputCallback(displayLink, { _, _, _, _, _, userInfo in
            let appDelegate = Unmanaged<AppDelegate>.fromOpaque(userInfo!).takeUnretainedValue()
            DispatchQueue.main.async {
                appDelegate.tickFrame()
            }
            return kCVReturnSuccess
        }, Unmanaged.passUnretained(self).toOpaque())

        CVDisplayLinkStart(displayLink)
    }

    @objc func tickFrame() {
        // Frame pacing -- skip if too soon
        let now = CACurrentMediaTime()
        let interval = 1.0 / Double(effectiveFPS)
        guard now - lastFrameTime >= interval else { return }
        lastFrameTime = now

        // Check for hourly race rotation
        let hourIndex = RaceEngine.currentRaceIndex()
        if hourIndex != lastHourIndex {
            lastHourIndex = hourIndex
            if let newRace = try? engine?.loadRace(index: hourIndex) {
                renderer?.startCrossfade()
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    self.engine?.setRace(newRace)
                }
            }
        }

        window?.contentView?.displayIfNeeded()
    }

    private var effectiveFPS: Int {
        let settingsFPS = settings.targetFPS
        if settingsFPS > 0 { return settingsFPS }

        // Battery check -- guard against nil on desktop Macs without battery
        guard let ps = IOPSCopyPowerSourcesInfo()?.takeRetainedValue() else { return 30 }
        guard let cfSources = IOPSCopyPowerSourcesList(ps)?.takeRetainedValue() else { return 30 }
        let count = CFArrayGetCount(cfSources)
        for i in 0..<count {
            let sourcePtr = CFArrayGetValueAtIndex(cfSources, i)
            let source = Unmanaged<AnyObject>.fromOpaque(sourcePtr!).takeUnretainedValue()
            guard let info = IOPSGetPowerSourceDescription(ps, source)?.takeUnretainedValue() as? [String: Any] else { continue }
            if let type = info[kIOPSTransportTypeKey] as? String, type == "InternalBattery" {
                let isCharging = info[kIOPSIsChargingKey] as? Bool ?? true
                if !isCharging { return 15 }
            }
        }
        return 30
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return true
    }

    func applicationWillTerminate(_ notification: Notification) {
        if let displayLink {
            CVDisplayLinkStop(displayLink)
        }
    }
}
