// swift-tools-version: 5.9
// LoganPrivate iOS SPM 包。ThirdParty/ 是官方 Logan 1.2.10 源码的 vendored 拷贝
// （源: fork upstream Meituan-Dianping/Logan master，MIT；升级=从 upstream 重新拷贝+跑一致性测试）。
// 崩溃层(KSCrash vendored)下一迭代加入。
import PackageDescription

let package = Package(
    name: "LoganPrivate",
    platforms: [.iOS(.v12), .macOS(.v10_15)],
    products: [
        .library(name: "Logan", targets: ["Logan"]),
        .library(name: "LoganCompat", targets: ["LoganCompat"]),
    ],
    targets: [
        .target(
            name: "mbedtls",
            path: "ThirdParty/mbedtls",
            exclude: [
                "include/CMakeLists.txt",
                "library/CMakeLists.txt",
                "library/Makefile",
                "library/bn_asm.c", // 2.6 手写汇编路径，ARM64 上不启用（MBEDTLS_HAVE_ASM 关闭后未引用）
            ]
        ),
        .target(
            name: "CLogan",
            dependencies: ["mbedtls"],
            path: "ThirdParty/Clogan",
            exclude: ["main.c", "CMakeLists.txt", "Info.plist", "main.dSYM/Contents/Info.plist", "main.dSYM/Contents/Resources/DWARF/main"],
            publicHeadersPath: "./",
            cSettings: [
                .headerSearchPath("../../ThirdParty/mbedtls/include"),
            ]
        ),
        .target(
            name: "Logan",
            dependencies: ["CLogan"],
            path: "ThirdParty/Logan",
            exclude: ["README.md", "README-zh.md"],
            publicHeadersPath: "./",
            cSettings: [
                .headerSearchPath("../Clogan"),
            ],
            linkerSettings: [
                .linkedLibrary("z"),
            ]
        ),
        .target(
            name: "LoganCompat",
            dependencies: ["Logan"],
            path: "Sources/LoganCompat",
            publicHeadersPath: "./",
            cSettings: [
                .headerSearchPath("../../ThirdParty/Logan"),
                .headerSearchPath("../../ThirdParty/Clogan"),
            ],
            linkerSettings: [
                .linkedFramework("Security"),
                .linkedFramework("UIKit", .when(platforms: [.iOS]))
            ]
        ),
    ]
)
