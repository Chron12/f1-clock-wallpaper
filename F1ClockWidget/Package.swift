// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "F1ClockWidget",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "F1ClockWidget",
            dependencies: [],
            path: "F1ClockWidget",
            exclude: ["Info.plist"],
            resources: [
                .copy("Resources/Races")
            ]
        ),
        .testTarget(
            name: "F1ClockWidgetTests",
            dependencies: ["F1ClockWidget"],
            path: "F1ClockWidgetTests"
        ),
    ]
)
