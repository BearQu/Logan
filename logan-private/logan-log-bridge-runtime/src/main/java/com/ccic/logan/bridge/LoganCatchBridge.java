package com.ccic.logan.bridge;

import com.ccic.logan.error.LoganError;

/**
 * 编译期 catch 采集的静态跳板（字节码在每个 catch handler 入口注入本方法调用）。
 *
 * 为什么不直接改调用 LoganError：跳板包 com.ccic.logan.bridge 在插件 HARD_EXCLUDE 里，
 * 且本类把一切都吞掉 —— 采集层任何异常都不会从业务 catch 块里再抛出去（那会把「本来不崩」
 * 变成「崩」，是本通道最大的稳定性红线）。
 *
 * 通用性（硬约束）：业务零改动、零依赖具体框架；只要求宿主依赖本运行时模块
 * （与日志双写同一份依赖，没有新增接入成本）。
 */
public final class LoganCatchBridge {

    private LoganCatchBridge() {}

    /**
     * @param throwable catch 到的异常对象（字节码在 handler 入口 DUP 出来的那一份，原样留给业务）
     * @param where     定位串，形如 com.foo.Bar#doThing，由编译期写入常量
     */
    public static void report(Throwable throwable, String where) {
        try {
            if (throwable == null) {
                return;
            }
            LoganError.reportCaught(throwable, where);
        } catch (Throwable ignore) {
            // 观测通道绝不影响业务流程
        }
    }
}
