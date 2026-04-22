import AppKit
import Metal
import MetalKit

final class MetalRenderer: NSObject, MTKViewDelegate {
    private let settings: WidgetSettings
    private let engine: RaceEngine
    private var lastFrameTime: CFAbsoluteTime = 0

    // Circuit accent colors (same 23-circuit map as Android)
    private let circuitAccents: [String: UInt32] = [
        "Bahrain": 0xFFE8002D, "Saudi": 0xFF00D2FF,
        "Australia": 0xFF00A550, "Japan": 0xFFFF0000,
        "China": 0xFFFF6B00, "Miami": 0xFF00CED1,
        "Monaco": 0xFFFFD700, "Canada": 0xFFFF0000,
        "Spain": 0xFFAA151B, "Austria": 0xFFED2939,
        "UK": 0xFF003399, "Hungary": 0xFF477050,
        "Belgium": 0xFFFFD700, "Netherlands": 0xFFFF6600,
        "Italy": 0xFF009246, "Azerbaijan": 0xFF0092BC,
        "Singapore": 0xFFEF3340, "USA": 0xFF3C3B6E,
        "Mexico": 0xFF006847, "Brazil": 0xFF009C3B,
        "Las Vegas": 0xFFFFD700, "Qatar": 0xFF8D1B3D,
        "Abu Dhabi": 0xFF00A3D7
    ]

    // Sector colors
    private let sectorColors: [NSColor] = [
        NSColor(red: 0.42, green: 0.18, blue: 0.62, alpha: 1), // purple
        NSColor(red: 0.18, green: 0.35, blue: 0.62, alpha: 1), // blue
        NSColor(red: 0.18, green: 0.62, blue: 0.35, alpha: 1), // green
    ]

    // Tire compound colors (static)
    private static let tireColors: [String: NSColor] = [
        "SOFT": NSColor(red: 1, green: 0.2, blue: 0.2, alpha: 1),
        "MEDIUM": NSColor(red: 1, green: 0.87, blue: 0, alpha: 1),
        "HARD": NSColor.white,
        "INTERMEDIATE": NSColor(red: 0.27, green: 0.73, blue: 0.27, alpha: 1),
        "WET": NSColor(red: 0.27, green: 0.53, blue: 1, alpha: 1),
    ]

    // Cached rendering resources (created once, reused across frames)
    private var cachedContext: CGContext?
    private var cachedPixelBuffer: UnsafeMutableRawPointer?
    private var cachedSize = CGSize.zero
    private let colorSpace = CGColorSpaceCreateDeviceRGB()
    private let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "h:mm:ss a"
        return f
    }()

    // Transform state
    private var scaleX: Float = 1
    private var scaleY: Float = 1
    private var offsetX: Float = 0
    private var offsetY: Float = 0

    // Crossfade state
    private var crossfadeAlpha: Float = 1.0
    private var isTransitioning = false
    private var transitionStartTime: CFAbsoluteTime = 0

    init(settings: WidgetSettings, engine: RaceEngine) {
        self.settings = settings
        self.engine = engine
        super.init()
    }

    deinit {
        cachedPixelBuffer?.deallocate()
    }

    // MARK: - MTKViewDelegate

    func draw(in view: MTKView) {
        guard let drawable = view.currentDrawable else { return }

        let now = CFAbsoluteTimeGetCurrent()
        let delta = lastFrameTime > 0 ? Float(now - lastFrameTime) : 0
        lastFrameTime = now

        engine.advanceTime(delta: delta)

        // Get current drawable size
        let width = Float(drawable.texture.width)
        let height = Float(drawable.texture.height)
        let uiScale = min(width, height) / 500.0
        let clampedScale = max(0.6, min(2.0, uiScale))

        // Reuse CGContext across frames (resize only when drawable changes)
        let size = CGSize(width: CGFloat(width), height: CGFloat(height))
        if cachedSize != size {
            cachedPixelBuffer?.deallocate()
            let bytesPerRow = 4 * drawable.texture.width
            let bufferSize = bytesPerRow * drawable.texture.height
            cachedPixelBuffer = UnsafeMutableRawPointer.allocate(byteCount: bufferSize, alignment: 16)
            cachedContext = CGContext(
                data: cachedPixelBuffer,
                width: drawable.texture.width,
                height: drawable.texture.height,
                bitsPerComponent: 8,
                bytesPerRow: bytesPerRow,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue
            )
            cachedSize = size
        }
        guard let cgContext = cachedContext else { return }

        cgContext.clear(CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))

        // Flip for Cocoa coordinate system
        cgContext.saveGState()
        cgContext.translateBy(x: 0, y: CGFloat(height))
        cgContext.scaleBy(x: 1, y: -1)

        // Apply crossfade alpha modulation
        let fadeAlpha = updateCrossfade()

        // Draw the full scene
        guard let race = engine.currentRace else {
            cgContext.restoreGState()
            return
        }
        drawScene(cgContext, race: race, width: width, height: height, uiScale: clampedScale, fadeAlpha: fadeAlpha)

        cgContext.restoreGState()

        // Blit pre-allocated pixel buffer directly to Metal texture (zero allocation)
        guard let buffer = cachedPixelBuffer else { return }
        let bytesPerRow = 4 * drawable.texture.width
        drawable.texture.replace(
            region: MTLRegion(origin: .init(x: 0, y: 0, z: 0),
                size: MTLSize(width: drawable.texture.width, height: drawable.texture.height, depth: 1)),
            mipmapLevel: 0,
            withBytes: buffer,
            bytesPerRow: bytesPerRow
        )

        drawable.present()
    }

    func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
        // Recompute transform when resized
        if let race = engine.currentRace {
            computeTransform(width: Float(size.width), height: Float(size.height), outline: race.trackOutline)
        }
    }

    // MARK: - Coordinate transform

    func computeTransform(width: Float, height: Float, outline: [TrackPoint]) {
        guard !outline.isEmpty else { return }
        var minX = Float.greatestFiniteMagnitude, maxX = -Float.greatestFiniteMagnitude
        var minY = Float.greatestFiniteMagnitude, maxY = -Float.greatestFiniteMagnitude
        for p in outline {
            if p.x < minX { minX = p.x }
            if p.x > maxX { maxX = p.x }
            if p.y < minY { minY = p.y }
            if p.y > maxY { maxY = p.y }
        }
        let rangeX = max(maxX - minX, 1)
        let rangeY = max(maxY - minY, 1)
        let padTop = height * 0.18
        let padSide = width * 0.06
        let padBottom = height * 0.08
        let w = width - padSide * 2
        let h = height - padTop - padBottom
        let scale = min(w / rangeX, h / rangeY)
        scaleX = scale
        scaleY = scale
        offsetX = padSide + (w - rangeX * scale) / 2 - minX * scale
        offsetY = padTop + (h - rangeY * scale) / 2 + maxY * scale
    }

    private func toScreenX(_ x: Float) -> Float { x * scaleX + offsetX }
    private func toScreenY(_ y: Float) -> Float { -y * scaleY + offsetY }

    // MARK: - Crossfade

    func startCrossfade() {
        isTransitioning = true
        transitionStartTime = CFAbsoluteTimeGetCurrent()
    }

    private func updateCrossfade() -> Float {
        guard isTransitioning else { return 1.0 }
        let elapsed = Float(CFAbsoluteTimeGetCurrent() - transitionStartTime)
        if elapsed < 1.0 {
            return 1.0 - elapsed // fade out
        } else if elapsed < 2.0 {
            return elapsed - 1.0 // fade in
        } else {
            isTransitioning = false
            return 1.0
        }
    }

    // MARK: - Scene drawing

    func drawScene(_ ctx: CGContext, race: RaceData, width: Float, height: Float, uiScale: Float, fadeAlpha: Float = 1.0) {
        let t = engine.raceTimeSeconds
        let accentColor = circuitAccentColor(for: race.title)
        let s = CGFloat(uiScale)

        // Apply crossfade: modulate all alpha values
        let alpha = CGFloat(fadeAlpha)

        // 1. Background
        drawBackground(ctx, width: width, height: height, opacity: CGFloat(settings.backgroundOpacity) / 255 * alpha)

        // 2. Track
        drawTrack(ctx, race: race, uiScale: s, accentColor: accentColor)

        // 3. Start/finish line
        drawStartFinishLine(ctx, outline: race.trackOutline, uiScale: s)

        // Compute driver states
        let currentLap = RaceEngine.currentLap(in: race.laps, at: t)
        var driverStates: [DriverState] = []
        for (driverNum, driver) in race.drivers {
            guard let locs = race.locations[driverNum] else { continue }
            guard let pos = RaceEngine.interpolateLocation(in: locs, at: t) else { continue }
            let racePos = RaceEngine.racePosition(in: race.positions[driverNum] ?? [], at: t)
            let retired = RaceEngine.isRetired(locations: locs, at: t, raceDurationS: race.raceDurationS)
            let tire = RaceEngine.tireCompound(stints: race.stints[driverNum], lap: currentLap)
            driverStates.append(DriverState(
                driverNum: driverNum, driver: driver, position: pos,
                racePosition: racePos, retired: retired, tire: tire, locations: locs
            ))
        }
        driverStates.sort { $0.racePosition > $1.racePosition }

        // 4-6. Cars (trails, glow, body)
        for ds in driverStates where !ds.retired {
            drawCar(ctx, ds: ds, t: t, uiScale: s, fadeAlpha: alpha)
        }

        // Fastest lap glow (purple pulse for 3s after FL timestamp)
        if let fl = race.fastestLap, settings.showGlowEffects {
            let flElapsed = t - fl.t
            if flElapsed >= 0 && flElapsed < 3.0 {
                let pulseAlpha = 0.5 + 0.4 * sin(flElapsed * Float.pi * 2)
                if let locs = race.locations[String(fl.driverNumber)],
                   let flPos = RaceEngine.interpolateLocation(in: locs, at: fl.t) {
                    let sx = CGFloat(toScreenX(flPos.x))
                    let sy = CGFloat(toScreenY(flPos.y))
                    let glowR = CGFloat(10) * s
                    ctx.setFillColor(NSColor(red: 0.7, green: 0.3, blue: 1, alpha: CGFloat(pulseAlpha) * alpha).cgColor)
                    ctx.fillEllipse(in: CGRect(x: sx - glowR, y: sy - glowR, width: glowR * 2, height: glowR * 2))
                }
            }
        }

        // 7. Clock
        drawClock(ctx, width: CGFloat(width), uiScale: s, accentColor: accentColor, fadeAlpha: alpha)

        // 8. Race title
        drawRaceTitle(ctx, race: race, width: CGFloat(width), uiScale: s, accentColor: accentColor, fadeAlpha: alpha)

        // 9. Leaderboard
        if settings.showLeaderboard {
            drawLeaderboard(ctx, driverStates: driverStates, race: race, t: t, width: CGFloat(width), height: CGFloat(height), uiScale: s, fadeAlpha: alpha)
        }

        // 10. Lap counter
        if settings.showRaceInfo {
            drawLapCounter(ctx, race: race, currentLap: currentLap, width: CGFloat(width), height: CGFloat(height), uiScale: s, fadeAlpha: alpha)
        }

        // 11. Progress bar
        if settings.showRaceInfo {
            drawProgressBar(ctx, race: race, t: t, width: CGFloat(width), height: CGFloat(height), uiScale: s, accentColor: accentColor, fadeAlpha: alpha)
        }
    }

    // MARK: - 1. Background

    private func drawBackground(_ ctx: CGContext, width: Float, height: Float, opacity: CGFloat) {
        let topH = CGFloat(height * 0.3)
        ctx.setFillColor(NSColor(white: 0.02, alpha: opacity).cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: CGFloat(width), height: topH))
        ctx.setFillColor(NSColor(white: 0.03, alpha: opacity * 0.7).cgColor)
        ctx.fill(CGRect(x: 0, y: topH, width: CGFloat(width), height: CGFloat(height) - topH))
    }

    // MARK: - 2. Track

    private func drawTrack(_ ctx: CGContext, race: RaceData, uiScale: CGFloat, accentColor: NSColor) {
        func strokePath(_ points: [TrackPoint], color: NSColor) {
            guard points.count > 1 else { return }
            ctx.addLines(between: points.map { CGPoint(x: CGFloat(toScreenX($0.x)), y: CGFloat(toScreenY($0.y))) })
            ctx.setStrokeColor(color.cgColor)
            ctx.setLineWidth(5 * uiScale)
            ctx.strokePath()
        }

        let sectors = race.trackSectors
        if settings.showSectorColors, let sectors,
           sectors.sector1.count > 1, sectors.sector2.count > 1, sectors.sector3.count > 1 {
            for i in 0..<3 {
                let pts = [sectors.sector1, sectors.sector2, sectors.sector3][i]
                strokePath(pts, color: sectorColors[i])
            }
        } else {
            let outline = race.trackOutline
            guard outline.count > 1 else { return }
            // Use accent-derived track color
            let r = (accentColor.redComponent * 0.2) + 0.2
            let g = (accentColor.greenComponent * 0.2) + 0.2
            let b = (accentColor.blueComponent * 0.2) + 0.2
            strokePath(outline, color: NSColor(red: r, green: g, blue: b, alpha: 0.8))
        }
    }

    // MARK: - 3. Start/finish

    private func drawStartFinishLine(_ ctx: CGContext, outline: [TrackPoint], uiScale: CGFloat) {
        guard outline.count > 1 else { return }
        let sx = CGFloat(toScreenX(outline[0].x))
        let sy = CGFloat(toScreenY(outline[0].y))
        let dx = CGFloat(toScreenX(outline[1].x)) - sx
        let dy = CGFloat(toScreenY(outline[1].y)) - sy
        let len = max(sqrt(dx*dx + dy*dy), 0.001)
        let px = -dy / len * 14 * uiScale
        let py = dx / len * 14 * uiScale

        ctx.setStrokeColor(NSColor.white.cgColor)
        ctx.setLineWidth(4 * uiScale)
        ctx.move(to: CGPoint(x: sx - px, y: sy - py))
        ctx.addLine(to: CGPoint(x: sx + px, y: sy + py))
        ctx.strokePath()

        ctx.setStrokeColor(NSColor(red: 0.91, green: 0, blue: 0.18, alpha: 1).cgColor)
        ctx.setLineWidth(2 * uiScale)
        let offDx = (dx / len) * 2 * uiScale
        let offDy = (dy / len) * 2 * uiScale
        ctx.move(to: CGPoint(x: sx - px + offDx, y: sy - py + offDy))
        ctx.addLine(to: CGPoint(x: sx + px + offDx, y: sy + py + offDy))
        ctx.strokePath()
    }

    // MARK: - 4-6. Car (trails + glow + body + label)

    private func drawCar(_ ctx: CGContext, ds: DriverState, t: Float, uiScale: CGFloat, fadeAlpha: CGFloat = 1.0) {
        let sx = CGFloat(toScreenX(ds.position.x))
        let sy = CGFloat(toScreenY(ds.position.y))
        let r = 6 * uiScale * CGFloat(settings.carSizeMultiplier)
        let carColor = driverColor(ds.driver)

        // Trails (4 ghost circles, offsets 0.4s-1.6s, alpha 0.2-0.55)
        let offsets: [Float] = [1.6, 1.2, 0.8, 0.4]
        let alphas: [CGFloat] = [0.2, 0.31, 0.43, 0.55]
        let radMul: [CGFloat] = [0.45, 0.55, 0.65, 0.8]
        for i in 0..<4 {
            guard t - offsets[i] > 0 else { continue }
            if let tp = RaceEngine.interpolateLocation(in: ds.locations, at: t - offsets[i]) {
                ctx.setFillColor(carColor.withAlphaComponent(alphas[i] * fadeAlpha).cgColor)
                ctx.fillEllipse(in: CGRect(
                    x: CGFloat(toScreenX(tp.x)) - r * radMul[i],
                    y: CGFloat(toScreenY(tp.y)) - r * radMul[i],
                    width: r * radMul[i] * 2, height: r * radMul[i] * 2
                ))
            }
        }

        // Glow effects
        if settings.showGlowEffects {
            if ds.racePosition == 1 {
                // Gold glow on P1
                ctx.setFillColor(NSColor(red: 1, green: 0.84, blue: 0, alpha: 0.27 * fadeAlpha).cgColor)
                ctx.fillEllipse(in: CGRect(x: sx - r - 6, y: sy - r - 6, width: (r + 6) * 2, height: (r + 6) * 2))
            } else if ds.racePosition <= 3 {
                // Team-color glow on P2-P3
                ctx.setFillColor(carColor.withAlphaComponent(0.2 * fadeAlpha).cgColor)
                ctx.fillEllipse(in: CGRect(x: sx - r - 4, y: sy - r - 4, width: (r + 4) * 2, height: (r + 4) * 2))
            }
        }

        // Car body: team-color filled circle with 1px black stroke
        ctx.setFillColor(carColor.cgColor)
        ctx.fillEllipse(in: CGRect(x: sx - r, y: sy - r, width: r * 2, height: r * 2))
        ctx.setStrokeColor(NSColor.black.cgColor)
        ctx.setLineWidth(1.2 * uiScale)
        ctx.strokeEllipse(in: CGRect(x: sx - r, y: sy - r, width: r * 2, height: r * 2))

        // Driver label (top 3 always, others when enabled)
        if ds.racePosition <= 3 || settings.showDriverLabels {
            let fontSize = 8 * uiScale
            let text = ds.driver.code as NSString
            let attrs: [NSAttributedString.Key: Any] = [
                .font: NSFont.systemFont(ofSize: fontSize, weight: .semibold),
                .foregroundColor: ds.racePosition == 1 ? NSColor(red: 1, green: 0.84, blue: 0, alpha: 1) : carColor
            ]
            let textWidth = text.size(withAttributes: attrs).width
            let lx = sx + r + 3 * uiScale
            let ly = sy - fontSize / 2

            // Pill background for collision avoidance
            let pillRect = CGRect(x: lx - 2, y: ly - 2, width: textWidth + 4, height: fontSize + 4)
            ctx.setFillColor(NSColor(white: 0, alpha: 0.7).cgColor)
            let pillPath = CGPath(roundedRect: pillRect, cornerWidth: 3, cornerHeight: 3, transform: nil)
            ctx.addPath(pillPath)
            ctx.fillPath()

            text.draw(at: CGPoint(x: lx, y: ly), withAttributes: attrs)
        }
    }

    // MARK: - 7. Clock

    private func drawClock(_ ctx: CGContext, width: CGFloat, uiScale: CGFloat, accentColor: NSColor, fadeAlpha: CGFloat = 1.0) {
        let cx = width / 2
        let now = Date()
        let clockText = dateFormatter.string(from: now)

        let fontSize = 18 * uiScale
        let font = NSFont.monospacedSystemFont(ofSize: fontSize, weight: .bold)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: NSColor.white.withAlphaComponent(fadeAlpha)]
        let textWidth = (clockText as NSString).size(withAttributes: attrs).width
        let pp = 10 * uiScale
        let clockY = 28 * uiScale

        // Dark pill background
        let pillRect = CGRect(x: cx - textWidth / 2 - pp, y: clockY - fontSize - 2 * uiScale, width: textWidth + pp * 2, height: fontSize + 8 * uiScale)
        ctx.setFillColor(NSColor(white: 0, alpha: 0.78 * fadeAlpha).cgColor)
        let pillPath = CGPath(roundedRect: pillRect, cornerWidth: 10 * uiScale, cornerHeight: 10 * uiScale, transform: nil)
        ctx.addPath(pillPath)
        ctx.fillPath()

        // Accent underline
        ctx.setFillColor(accentColor.withAlphaComponent(fadeAlpha).cgColor)
        ctx.fill(CGRect(x: pillRect.minX + 8 * uiScale, y: pillRect.maxY - 2.5 * uiScale, width: pillRect.width - 16 * uiScale, height: 2.5 * uiScale))

        // Text
        (clockText as NSString).draw(at: CGPoint(x: cx - textWidth / 2, y: clockY - fontSize), withAttributes: attrs)
    }

    // MARK: - 8. Race title

    private func drawRaceTitle(_ ctx: CGContext, race: RaceData, width: CGFloat, uiScale: CGFloat, accentColor: NSColor, fadeAlpha: CGFloat = 1.0) {
        let cx = width / 2
        let titleY = 58 * uiScale + 18 * uiScale * 1.1

        // Extract year from title (e.g., "2024 Bahrain Grand Prix" already has it)
        var title = race.title
            .replacingOccurrences(of: "Formula 1 ", with: "")
            .replacingOccurrences(of: "Formula One ", with: "")

        let fontSize = 9 * uiScale
        let attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: fontSize, weight: .semibold),
            .foregroundColor: accentColor.withAlphaComponent(fadeAlpha)
        ]
        let maxWidth = width * 0.85
        // Truncate to 85% widget width
        while (title as NSString).size(withAttributes: attrs).width > maxWidth && title.count > 10 {
            title = String(title.dropLast(4)) + "..."
        }
        let textWidth = (title as NSString).size(withAttributes: attrs).width
        (title as NSString).draw(at: CGPoint(x: cx - textWidth / 2, y: titleY), withAttributes: attrs)
    }

    // MARK: - 9. Leaderboard

    private func drawLeaderboard(_ ctx: CGContext, driverStates: [DriverState], race: RaceData, t: Float, width: CGFloat, height: CGFloat, uiScale: CGFloat, fadeAlpha: CGFloat = 1.0) {
        // Responsive: top-5 at <400px
        let maxDrivers = width < 400 ? 5 : 20
        let active = driverStates.filter { !$0.retired }.sorted { $0.racePosition < $1.racePosition }
        let retired = driverStates.filter { $0.retired }.sorted { $0.racePosition < $1.racePosition }
        let shown = Array(active.prefix(maxDrivers))

        let fs = 9 * uiScale
        let lh = 13.5 * uiScale
        let lbX = 6 * uiScale
        let lbY = height * 0.20
        let lbW = 84 * uiScale

        // Background
        let totalRows = shown.count + retired.count + 1
        let bgRect = CGRect(x: lbX, y: lbY, width: lbW, height: lh * CGFloat(totalRows) + 6 * uiScale)
        ctx.setFillColor(NSColor(white: 0, alpha: 0.65 * fadeAlpha).cgColor)
        let bgPath = CGPath(roundedRect: bgRect, cornerWidth: 6 * uiScale, cornerHeight: 6 * uiScale, transform: nil)
        ctx.addPath(bgPath)
        ctx.fillPath()

        // LIVE header (solid red dot, no blink)
        let liveAttrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: fs * 0.85, weight: .bold),
            .foregroundColor: NSColor(red: 1, green: 0.2, blue: 0.2, alpha: fadeAlpha)
        ]
        let liveY = lbY + lh * 0.8
        let dotSize = CGFloat(5)
        ctx.setFillColor(NSColor(red: 1, green: 0.2, blue: 0.2, alpha: fadeAlpha).cgColor)
        ctx.fillEllipse(in: CGRect(x: lbX + lbW / 2 - 14, y: liveY - dotSize / 2, width: dotSize, height: dotSize))
        ("LIVE" as NSString).draw(at: CGPoint(x: lbX + lbW / 2 - 6, y: liveY - fs * 0.4), withAttributes: liveAttrs)

        // Driver rows
        var y = lbY + lh * 2
        let dotR = 3 * uiScale
        let posAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs, weight: .regular), .foregroundColor: NSColor(white: 0.6, alpha: fadeAlpha)]

        for ds in shown {
            // Position number
            ("\(ds.racePosition)" as NSString).draw(at: CGPoint(x: lbX, y: y - fs), withAttributes: posAttrs)
            // Team color dot
            ctx.setFillColor(driverColor(ds.driver).cgColor)
            ctx.fillEllipse(in: CGRect(x: lbX + 18 * uiScale, y: y - fs / 2 - dotR / 2, width: dotR, height: dotR))
            // Driver code
            let codeAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs, weight: .medium), .foregroundColor: NSColor.white.withAlphaComponent(fadeAlpha)]
            (ds.driver.code as NSString).draw(at: CGPoint(x: lbX + 24 * uiScale, y: y - fs), withAttributes: codeAttrs)
            // Tire compound dot
            if let tire = ds.tire, let tireColor = Self.tireColors[tire] {
                let tireR = 2 * uiScale
                ctx.setFillColor(tireColor.cgColor)
                ctx.fillEllipse(in: CGRect(x: lbX + 52 * uiScale, y: y - fs / 2 - tireR / 2, width: tireR, height: tireR))
            }
            y += lh
        }

        for ds in retired {
            let fadedAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs * 0.85, weight: .regular), .foregroundColor: NSColor(white: 0.27, alpha: fadeAlpha)]
            ("-" as NSString).draw(at: CGPoint(x: lbX, y: y - fs), withAttributes: fadedAttrs)
            let fadedColor = driverColor(ds.driver).withAlphaComponent(0.3)
            ctx.setFillColor(fadedColor.cgColor)
            ctx.fillEllipse(in: CGRect(x: lbX + 18 * uiScale, y: y - fs / 2 - dotR * 0.4, width: dotR * 0.8, height: dotR * 0.8))
            (ds.driver.code as NSString).draw(at: CGPoint(x: lbX + 24 * uiScale, y: y - fs), withAttributes: fadedAttrs)

            // DNF badge
            let dnfAttrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fs * 0.6, weight: .bold), .foregroundColor: NSColor.white]
            let dnfX = lbX + 52 * uiScale
            let dnfW = ("DNF" as NSString).size(withAttributes: dnfAttrs).width
            ctx.setFillColor(NSColor(red: 0.7, green: 0, blue: 0, alpha: 0.8 * fadeAlpha).cgColor)
            let dnfRect = CGRect(x: dnfX - 2, y: y - fs, width: dnfW + 4, height: fs)
            let dnfPath = CGPath(roundedRect: dnfRect, cornerWidth: 3, cornerHeight: 3, transform: nil)
            ctx.addPath(dnfPath)
            ctx.fillPath()
            ("DNF" as NSString).draw(at: CGPoint(x: dnfX, y: y - fs), withAttributes: dnfAttrs)
            y += lh * 0.9
        }
    }

    // MARK: - 10. Lap counter

    private func drawLapCounter(_ ctx: CGContext, race: RaceData, currentLap: Int, width: CGFloat, height: CGFloat, uiScale: CGFloat, fadeAlpha: CGFloat = 1.0) {
        guard race.totalLaps > 0 else { return }
        let cx = width / 2
        let lapText = "LAP  \(currentLap) / \(race.totalLaps)"
        let fontSize = 10 * uiScale
        let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: fontSize, weight: .bold), .foregroundColor: NSColor.white.withAlphaComponent(fadeAlpha)]
        let textWidth = (lapText as NSString).size(withAttributes: attrs).width
        let lapY = height - 14 * uiScale

        // Dark pill background
        let pillRect = CGRect(x: cx - textWidth / 2 - 8 * uiScale, y: lapY - 12 * uiScale, width: textWidth + 16 * uiScale, height: 16 * uiScale)
        ctx.setFillColor(NSColor(white: 0, alpha: 0.67 * fadeAlpha).cgColor)
        let pillPath = CGPath(roundedRect: pillRect, cornerWidth: 6 * uiScale, cornerHeight: 6 * uiScale, transform: nil)
        ctx.addPath(pillPath)
        ctx.fillPath()

        (lapText as NSString).draw(at: CGPoint(x: cx - textWidth / 2, y: lapY - fontSize), withAttributes: attrs)
    }

    // MARK: - 11. Progress bar

    private func drawProgressBar(_ ctx: CGContext, race: RaceData, t: Float, width: CGFloat, height: CGFloat, uiScale: CGFloat, accentColor: NSColor, fadeAlpha: CGFloat = 1.0) {
        let pct = min(max(CGFloat(t) / CGFloat(race.raceDurationS), 0), 1)
        let barY = height - 3 * uiScale

        // Background
        ctx.setFillColor(NSColor(white: 0.1, alpha: 1).cgColor)
        ctx.fill(CGRect(x: 0, y: barY, width: width, height: 3 * uiScale))
        // Accent fill
        ctx.setFillColor(accentColor.withAlphaComponent(fadeAlpha).cgColor)
        ctx.fill(CGRect(x: 0, y: barY, width: width * pct, height: 3 * uiScale))
    }

    // MARK: - Helpers

    private func circuitAccentColor(for title: String) -> NSColor {
        for (key, hex) in circuitAccents {
            if title.contains(key) {
                let r = CGFloat((hex >> 16) & 0xFF) / 255
                let g = CGFloat((hex >> 8) & 0xFF) / 255
                let b = CGFloat(hex & 0xFF) / 255
                return NSColor(red: r, green: g, blue: b, alpha: 1)
            }
        }
        return NSColor(red: 0.91, green: 0, blue: 0.18, alpha: 1) // F1 red default
    }

    private func driverColor(_ driver: Driver) -> NSColor {
        let hex = driver.color.replacingOccurrences(of: "#", with: "")
        guard let val = UInt32(hex, radix: 16) else { return .white }
        let r = CGFloat((val >> 16) & 0xFF) / 255
        let g = CGFloat((val >> 8) & 0xFF) / 255
        let b = CGFloat(val & 0xFF) / 255
        return NSColor(red: r, green: g, blue: b, alpha: 1)
    }
}

// MARK: - DriverState

private struct DriverState {
    let driverNum: String
    let driver: Driver
    let position: (x: Float, y: Float)
    let racePosition: Int
    let retired: Bool
    let tire: String?
    let locations: [LocationPoint]
}
