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
 * 默认只改 PROJECT 作用域（工程自身代码）；instrumentDependencies=true 时改用官方 Scope.ALL，
 */
public class LoganLogBridgePlugin implements Plugin<Project> {

    /** 永不改写（防递归 + 防改到桥自身/崩溃模块/内核）。 */
    private static final String[] HARD_EXCLUDE = {
            "android/util/Log",
            "com/ccic/logan/bridge/",   // 桥自身三个跳板类都不能改写，否则递归
            "com/ccic/logan/error/",   // 错误采集层自身：catch 注入会自噬（LoganError 内部大量 try/catch）
            "com/dianping/logan/",
            "com/ccic/logan/crash/",
            "com/ccic/logan/http/",
            "com/ccic/logan/identity/",
            "com/ccic/logan/device/",
            "com/ccic/logan/command/",
            "androidx/",
            "kotlin/",
            "kotlinx/",
            "com/google/",
            // 网络库自身不能改写：okhttp/retrofit 内部也会调 Builder#build()，改到会自递归装配
            "okhttp3/",
            "retrofit2/",
            "com/squareup/",
            "android/taobao/",
            "com/tencent/",
            "com/alibaba/",
            "io/reactivex/",
            "com/bumptech/",
            "org/apache/",
            "javax/",
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
                        t.getNetworkEnabled().set(ext.getNetworkEnabled());
                        t.getCatchEnabled().set(ext.getCatchEnabled());
                        t.getIncludePrefixes().set(ext.getIncludePrefixes());
                        t.getExcludePrefixes().set(ext.getExcludePrefixes());
                        t.getHardExcludePrefixes().set(java.util.Arrays.asList(HARD_EXCLUDE));
                    });
            // 默认只插桩本模块（已验证路径）；宿主网络栈若在其他 Gradle 模块，在那些模块各 apply 一次，
            // 或打开 instrumentDependencies=true 让依赖 jar 一并进入变换（AGP 官方 Scope.ALL）。
            boolean all = Boolean.TRUE.equals(ext.getInstrumentDependencies().getOrElse(Boolean.FALSE));
            ScopedArtifacts.Scope scope = all ? ScopedArtifacts.Scope.ALL : ScopedArtifacts.Scope.PROJECT;
            variant.getArtifacts()
                    .forScope(scope)
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
