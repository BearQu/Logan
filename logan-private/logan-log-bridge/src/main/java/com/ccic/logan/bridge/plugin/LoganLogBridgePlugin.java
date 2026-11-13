package com.ccic.logan.bridge.plugin;

import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.ScopedArtifacts;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * 编译期把白名单业务包内的 android.util.Log.* 调用点改写为
 * com.ccic.logan.bridge.LoganLogBridge.*（桥内双写：先原样 Log 再进 Logan）。
 *
 * 依据先例：OpenTelemetry Android 官方 android-log instrumentation 同一手法。
 * AGP 8 ScopedArtifacts 新 API（签名已 javap 核实）：
 *   variant.getArtifacts().forScope(Scope.PROJECT).use(task)
 *     .toTransform(ScopedArtifact.CLASSES.INSTANCE, getAllJars, getAllDirectories, getOutput)
 * 只改 PROJECT 作用域（工程自身代码），三方库/框架不动；logcat 最终输出逐字节不变。
 */
public class LoganLogBridgePlugin implements Plugin<Project> {

    /** 永不改写（防递归 + 防改到桥自身/崩溃模块/内核）。 */
    private static final String[] HARD_EXCLUDE = {
            "android/util/Log",
            "com/ccic/logan/bridge/LoganLogBridge",
            "com/dianping/logan/",
            "com/ccic/logan/crash/",
            "androidx/",
            "kotlin/",
            "kotlinx/",
            "com/google/",
    };

    @Override
    public void apply(Project project) {
        LoganLogBridgeExtension ext = project.getExtensions().create("loganLogBridge", LoganLogBridgeExtension.class);

        @SuppressWarnings("unchecked")
        AndroidComponentsExtension<?, ?, ?> ace =
                project.getExtensions().findByType(AndroidComponentsExtension.class);
        if (ace == null) {
            throw new IllegalStateException("logan-log-bridge 必须应用在 com.android.application / com.android.library 模块上");
        }
        ace.onVariants(ace.selector().all(), variant -> {
            String taskName = "loganLogBridge" + capitalize(variant.getName());
            TaskProvider<LoganLogBridgeTask> task = project.getTasks()
                    .register(taskName, LoganLogBridgeTask.class, t -> {
                        t.getBridgeEnabled().set(ext.getEnabled());
                        t.getIncludePrefixes().set(ext.getIncludePrefixes());
                        t.getExcludePrefixes().set(ext.getExcludePrefixes());
                        t.getHardExcludePrefixes().set(java.util.Arrays.asList(HARD_EXCLUDE));
                    });
            variant.getArtifacts()
                    .forScope(ScopedArtifacts.Scope.PROJECT)
                    .use(task)
                    .toTransform(
                            ScopedArtifact.CLASSES.INSTANCE,
                            LoganLogBridgeTask::getAllJars,
                            LoganLogBridgeTask::getAllDirectories,
                            LoganLogBridgeTask::getOutput);
        });
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
