package com.ccic.logan.http;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * 编译期桥的静态跳板（唯一被改写出来的调用目标，不写死任何第三方类型）。
 *
 * 改写规则把宿主里对 okhttp3.OkHttpClient$Builder#build() 的调用改成本方法：
 *   INVOKEVIRTUAL okhttp3/OkHttpClient$Builder.build()Lokhttp3/OkHttpClient;
 *   -> INVOKESTATIC com/ccic/logan/http/LoganOkHttpInstaller.build(Ljava/lang/Object;)Ljava/lang/Object;
 * 栈形状完全一致（消费一个 builder、产出一个 client），所以不新增指令、不动 maxStack、不动 StackMapTable，
 * 与现有 android.util.Log 改写同一档安全性。本类不 import okhttp3，未装 OkHttp 的客户端也照样能编过、跑得通。
 */
public final class LoganOkHttpInstaller {

    private static final String TAG = "LoganHttp";
    /** 跳板对外只接受 Object：无需写死 okhttp3 类名，未装 OkHttp 的客户端也能正常运行。 */

    private LoganOkHttpInstaller() {}

    /** 等价于 builder.build()，只是建之前先把观测拦截器挂上。任何异常退回原始语义。 */
    public static Object build(Object builder) throws Exception {
        if (builder == null) {
            throw new NullPointerException("okhttp builder is null");
        }
        try {
            if (LoganHttp.get().isEnabled()) {
                LoganOkHttpBridge.install(builder);
            }
        } catch (Throwable t) {
            Log.d(TAG, "install skipped: " + t);
        }
        Method m = builder.getClass().getMethod("build");
        m.setAccessible(true);
        return m.invoke(builder);
    }
}