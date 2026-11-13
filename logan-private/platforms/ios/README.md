# LoganPrivate iOS

- 官方 Logan 1.2.10（Logan/iOS + Logan/Clogan + Logan/mbedtls）经 SPM 原样复用（ThirdParty/ 为 vendored 拷贝，升级=从 upstream 重拷+跑一致性测试），格式与 Server 零改动兼容。
- LoganCompat：NSLog 宏桥双写（PCH / GCC_PREFIX_HEADER -include 一行接入）。os_log 原样保留 + clogan mmap 落盘，同文案 1s 限频，LoganCompatSetEnabled(NO) 即回原样。
- 崩溃层（KSCrash vendored + CrashEnvelope 双通道，对齐 Android logan-crash）下一迭代。
- ccic-ios 接入方式：xcconfig 加 GCC_PREFIX_HEADER/-include；项目现用 CocoaPods，也可做私有 podspec source_files 指到本目录，二选一。
