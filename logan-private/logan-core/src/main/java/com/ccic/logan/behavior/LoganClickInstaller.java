package com.ccic.logan.behavior;

import android.view.View;

import java.lang.reflect.Method;

/**
 * 编译期桥改写 View#setOnClickListener / setOnLongClickListener 调用点的静态跳板。
 *
 * 参数类型刻意用 Object：除 android.view.View 之外，各内核与自绘控件（TBS/UC 的 WebView、
 * 自定义 Button 等）也各自声明同名 setter，用 Object 才能一条规则通吃，并保证栈形状与原
 * INVOKEVIRTUAL 完全等价（两个引用操作数 + void，零新增指令、不动 maxStack）。
 *
 * 定位来源：点击归属取监听器自身的类名（形如 com.foo.MainActivity$3 或 lambda 类），
 * 那正是「谁挂的这个点击」，比宿主类名更准；控件文本等内容在 LoganBehavior 里于点击瞬间取。
 *
 * 稳定性红线：包装失败一律退回反射原样转发，业务点击行为不受影响；本类整体在插件
 * HARD_EXCLUDE（com/ccic/logan/behavior/）内，跳板内部对 setOnClickListener 的调用不会再被改写。
 */
public final class LoganClickInstaller {

    private LoganClickInstaller() {}

    /** 重入闸：包装类里再调 setOnClickListener 时直接原生转发，防递归。 */
    private static final ThreadLocal<Boolean> IN_HOOK = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    public static void setOnClickListener(Object view, Object listener) {
        hook(view, listener, "setOnClickListener");
    }

    public static void setOnLongClickListener(Object view, Object listener) {
        hook(view, listener, "setOnLongClickListener");
    }

    private static void hook(Object view, Object listener, String methodName) {
        if (view == null) {
            return;
        }
        if (Boolean.TRUE.equals(IN_HOOK.get())) {
            callOriginal(view, listener, methodName);
            return;
        }
        boolean on;
        try {
            on = LoganBehavior.get().isEnabled();
        } catch (Throwable t) {
            on = false;
        }
        if (!on || listener == null) {
            // 通道关闭或未传监听器（业务用它清空点击）：原样交回，绝不干预
            callOriginal(view, listener, methodName);
            return;
        }
        String owner = ownerOf(listener);
        IN_HOOK.set(Boolean.TRUE);
        try {
            if (view instanceof View && listener instanceof View.OnClickListener
                    && "setOnClickListener".equals(methodName)) {
                View.OnClickListener wrapped = LoganBehavior.wrapClick((View.OnClickListener) listener, owner);
                if (wrapped != null) {
                    ((View) view).setOnClickListener(wrapped);
                    return;
                }
            }
            if (view instanceof View && listener instanceof View.OnLongClickListener
                    && "setOnLongClickListener".equals(methodName)) {
                View.OnLongClickListener wrapped = LoganBehavior.wrapLongClick((View.OnLongClickListener) listener, owner);
                if (wrapped != null) {
                    ((View) view).setOnLongClickListener(wrapped);
                    return;
                }
            }
        } catch (Throwable t) {
            // 包装不成只是少一个轨迹点
        } finally {
            IN_HOOK.set(Boolean.FALSE);
        }
        callOriginal(view, listener, methodName);
    }

    /** 点击归属：监听器自身类名（含匿名内部类 / lambda 合成类），取不到则空由平台侧按 label 兜。 */
    private static String ownerOf(Object listener) {
        try {
            String n = listener.getClass().getName();
            if (n == null) {
                return "";
            }
            int d = n.lastIndexOf('.');
            String tail = d >= 0 ? n.substring(d + 1) : n;
            // 我们自己的包装类被再次挂载时，取它包着的真实监听器，归属才有意义
            if ("Wrapped".equals(tail) || "WrappedLong".equals(tail)) {
                java.lang.reflect.Field f = listener.getClass().getDeclaredField("real");
                f.setAccessible(true);
                Object real = f.get(listener);
                if (real != null) {
                    return ownerOf(real);
                }
            }
            return n;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 反射调宿主对象自身的 setter（自绘控件/各内核的监听器类型只能靠反射匹配）。 */
    private static void callOriginal(Object view, Object listener, String methodName) {
        try {
            Class<?> c = view.getClass();
            Method[] ms = c.getMethods();
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                if (!methodName.equals(m.getName()) || m.getParameterTypes().length != 1) {
                    continue;
                }
                Class<?> pt = m.getParameterTypes()[0];
                if (!pt.isInterface()) {
                    continue;
                }
                if (listener == null || pt.isInstance(listener)) {
                    m.invoke(view, listener);
                    return;
                }
            }
        } catch (Throwable ignore) {
            // 转发失败也不能影响业务：业务此时已认为挂上了监听器
        }
    }
}
