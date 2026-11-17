package com.ccic.logan.http;

import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * OkHttp / Retrofit 通用观测装配器（零编译期依赖实现）。
 *
 * 为什么用反射+动态代理：logan-core 的硬约束是零第三方依赖（与崩溃通道同一档要求），
 * 而 OkHttp 是否存在于宿主是运行时才确定的事。用 java.lang.reflect.Proxy 动态实现
 * okhttp3.Interceptor 接口，装了 OkHttp 的宿主直接生效，没装就是干净的空操作，
 * 不需要为观测单独发布一个 okhttp 适配包，也不要求宿主改任何业务代码。
 *
 * 两种装配方式（都提供，按客户端情况选）：
 *   1) 默认自动：编译期桥插件把 okhttp3.OkHttpClient.Builder.build() 前自动插入本类 install(builder)；
 *   2) 显式手接：宿主在自己的 Builder 上调 LoganHttp.install(builder)（一行），供无法走编译期桥的客户端。
 */
public final class LoganOkHttpBridge {

    private static final String TAG = "LoganHttp";

    private static final String OKHttpBuilder = "okhttp3.OkHttpClient$Builder";
    private static final String OKHttpInterceptor = "okhttp3.Interceptor";
    private static final String OKHttpChain = "okhttp3.Interceptor$Chain";

    private static volatile Object cachedInterceptor;
    private static volatile boolean givenUp;

    private LoganOkHttpBridge() {}

    /**
     * 给 okhttp3.OkHttpClient.Builder 挂上观测拦截器。
     * 返回 true 表示确实挂上了；false 表示宿主没有 OkHttp、参数类型不对或已降级，调用方可忽略。
     * 永不抛出。
     */
    public static boolean install(Object okHttpClientBuilder) {
        try {
            if (!LoganHttp.get().isEnabled() || givenUp || okHttpClientBuilder == null) {
                return false;
            }
            Class<?> builderCls = Class.forName(OKHttpBuilder);
            if (!builderCls.isInstance(okHttpClientBuilder)) {
                return false;
            }
            Object interceptor = ensureInterceptor();
            if (interceptor == null) {
                return false;
            }
            // newBuilder() 会带上已有的观测拦截器，先按实例身份去重，避免同一请求重复上报
            if (alreadyInstalled(okHttpClientBuilder, interceptor)) {
                return false;
            }
            Method add = builderCls.getMethod("addInterceptor", Class.forName(OKHttpInterceptor));
            add.invoke(okHttpClientBuilder, interceptor);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "okhttp hook skipped: " + t);
            return false;
        }
    }

    /**
     * 把业务网络层抛出的 Throwable 归类为平台约定的 errKind：cancel/timeout/ssl/parse/connect/other。
     * 公开给显式上报入口 LoganHttp.record(...,Throwable,...) 复用，保证自动采集与手工接入的口径一致。
     */
    public static String classifyName(Throwable t) {
        if (t == null) {
            return "other";
        }
        String cn = String.valueOf(t.getClass().getName()).toLowerCase();
        String msg = String.valueOf(t.getMessage()).toLowerCase();
        for (Throwable c = t.getCause(); c != null && c != t; c = c.getCause()) {
            cn += " " + String.valueOf(c.getClass().getName()).toLowerCase();
            msg += " " + String.valueOf(c.getMessage()).toLowerCase();
        }
        if (cn.contains("canceled") || cn.contains("cancelled") || msg.contains("canceled") || msg.contains("cancelled") || msg.contains(" canceled")) {
            return "cancel";
        }
        if (cn.contains("timeout") || msg.contains("timeout") || msg.contains("timed out")) {
            return "timeout";
        }
        if (cn.contains("ssl") || cn.contains("cert") || msg.contains("ssl") || msg.contains("certificate")) {
            return "ssl";
        }
        if (cn.contains("malformed") || cn.contains("json") || cn.contains("parse") || msg.contains("json")
                || cn.contains("syntax") || msg.contains("parse")) {
            return "parse";
        }
        if (cn.contains("connect") || cn.contains("unknownhost") || cn.contains("unreachable") || cn.contains("nodename")
                || msg.contains("failed to connect") || msg.contains("unable to resolve host") || msg.contains("connection refused")) {
            return "connect";
        }
        return "other";
    }

    /** builder 现有拦截器里是否已含本 SDK 的共享实例（身份比较，取不到列表时保守按未装处理）。 */
    private static boolean alreadyInstalled(Object builder, Object interceptor) {
        try {
            Object list = builder.getClass().getMethod("interceptors").invoke(builder);
            if (!(list instanceof java.util.List)) {
                return false;
            }
            for (Object o : (java.util.List<?>) list) {
                if (o == interceptor) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
            // 老版本 OkHttp 无 interceptors() 或反射失败：保守放行，宁可多挂不漏挂
        }
        return false;
    }

    /** 只造一次共享拦截器（无状态，可被任意多个 client 复用）。 */
    private static Object ensureInterceptor() {
        if (cachedInterceptor != null) {
            return cachedInterceptor;
        }
        try {
            Class<?> interceptorCls = Class.forName(OKHttpInterceptor);
            Object proxy = Proxy.newProxyInstance(
                    LoganOkHttpBridge.class.getClassLoader(),
                    new Class<?>[] { interceptorCls },
                    new Handler());
            cachedInterceptor = proxy;
            return proxy;
        } catch (Throwable t) {
            givenUp = true;
            Log.d(TAG, "no okhttp on classpath: " + t);
            return null;
        }
    }

    /** 动态代理实现 okhttp3.Interceptor#intercept(Chain)。全程反射，任何异常都退回业务原语义。 */
    private static final class Handler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (!"intercept".equals(name)) {
                // equals/hashCode/toString 等由代理自身处理
                if ("toString".equals(name)) {
                    return "LoganHttpInterceptor";
                }
                if ("equals".equals(name)) {
                    return Boolean.valueOf(proxy == args[0]);
                }
                if ("hashCode".equals(name)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                return null;
            }
            Object chain = args == null || args.length == 0 ? null : args[0];
            if (chain == null) {
                return null;
            }
            LoganHttpEvent ev = new LoganHttpEvent();
            ev.kind = "native";
            long start = System.currentTimeMillis();
            try {
                Object request = chain.getClass().getMethod("request").invoke(chain);
                if (request != null) {
                    ev.method = str(call(request, "method"), "GET");
                    ev.url = str(call(request, "url"), "");
                    Object body = call(request, "body");
                    if (body != null) {
                        Object len = call(body, "contentLength");
                        if (len instanceof Number) {
                            long v = ((Number) len).longValue();
                            if (v >= 0) {
                                ev.reqBytes = Long.valueOf(v);
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
            }

            Object response = null;
            Throwable failure = null;
            try {
                Method proceed = chain.getClass().getMethod("proceed", requestClassOf(chain));
                response = proceed.invoke(chain, new Object[] { findRequest(chain) });
            } catch (InvocationTargetException e) {
                failure = e.getTargetException();
            } catch (Throwable e) {
                failure = e;
            } finally {
                ev.durationMs = System.currentTimeMillis() - start;
                ev.occurredAt = start;
            }

            if (failure != null) {
                ev.status = 0;
                ev.errKind = classify(failure);
                ev.errMsg = safeMessage(failure);
                LoganHttp.get().report(ev);
                throw failure;
            }
            try {
                Object code = call(response, "code");
                ev.status = code instanceof Number ? ((Number) code).intValue() : 0;
                Object body = call(response, "body");
                if (body != null) {
                    Object len = call(body, "contentLength");
                    if (len instanceof Number) {
                        long v = ((Number) len).longValue();
                        if (v >= 0) {
                            ev.respBytes = Long.valueOf(v);
                        }
                    }
                }
                if (ev.status == 0) {
                    ev.errKind = "other";
                    ev.errMsg = "response without status";
                }
            } catch (Throwable ignore) {
            }
            LoganHttp.get().report(ev);
            return response;
        }

        private Class<?> requestClassOf(Object chain) {
            try {
                return Class.forName("okhttp3.Request");
            } catch (Throwable t) {
                return null;
            }
        }

        private Object findRequest(Object chain) {
            try {
                return chain.getClass().getMethod("request").invoke(chain);
            } catch (Throwable t) {
                return null;
            }
        }

        private Object call(Object target, String getter) {
            if (target == null) {
                return null;
            }
            try {
                return target.getClass().getMethod(getter).invoke(target);
            } catch (Throwable t) {
                return null;
            }
        }

        private String classify(Throwable t) {
            return LoganOkHttpBridge.classifyName(t);
        }

        private String safeMessage(Throwable t) {
            try {
                String m = t.getMessage();
                String s = (m == null || m.length() == 0) ? t.getClass().getName() : t.getClass().getSimpleName() + ": " + m;
                return s.length() > 500 ? s.substring(0, 500) : s;
            } catch (Throwable ignore) {
                return "";
            }
        }

        private String str(Object v, String fallback) {
            if (v == null) {
                return fallback;
            }
            String s = String.valueOf(v).trim();
            return s.length() == 0 ? fallback : s;
        }
    }
}