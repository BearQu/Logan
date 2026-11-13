package com.ccic.logan.bridge.plugin;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import java.util.Arrays;

/**
 * DSL：
 *   loganLogBridge {
 *       enabled = true
 *       include 'com.ccic.baodai.'   // 业务包白名单，可多次调用；不调用=全工程
 *       exclude 'com.ccic.baodai.legacy.'
 *   }
 * 使用 Gradle managed Property：懒求值，DSL 块在插件 wire 之后执行也安全。
 */
public abstract class LoganLogBridgeExtension {
    public abstract Property<Boolean> getEnabled();
    public abstract ListProperty<String> getIncludePrefixes();
    public abstract ListProperty<String> getExcludePrefixes();

    public LoganLogBridgeExtension() {
        getEnabled().convention(true);
        getIncludePrefixes().convention(java.util.Collections.emptyList());
        getExcludePrefixes().convention(java.util.Collections.emptyList());
    }

    public void include(String... p) { getIncludePrefixes().addAll(Arrays.asList(p)); }
    public void exclude(String... p) { getExcludePrefixes().addAll(Arrays.asList(p)); }
}
