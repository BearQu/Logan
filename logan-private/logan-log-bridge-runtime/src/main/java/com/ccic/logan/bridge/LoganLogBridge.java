package com.ccic.logan.bridge;

import android.util.Log;

import com.dianping.logan.Logan;

/**
 * 字节码改写后的落点。调用点被 Gradle 插件从 android.util.Log.x 替换为 LoganLogBridge.x，
 * 签名逐一相同；本类先原样调 Log.x（logcat 行为逐字节不变 = 双写非替换），再进 Logan mmap。
 * 本模块绝不能被套用改写插件（包名前缀 com/ccic/logan/bridge 在插件里硬排除），否则递归。
 */
public final class LoganLogBridge {

    /** 日志类型映射：对齐服务端 LoganTypeEnum（UNKNOWN0/ONE1/TWO2/THREE3/CRASH99）。
     *  1 为 Logan 内部占用；4 不在枚举内会被落为 UNKNOWN(0)，故 w/e 统一 3，等级靠 V/D/I/W/E 前缀区分（与鸿蒙 LoganCompat 同规则）。 */
    public static final int TYPE_VERBOSE = 2;
    public static final int TYPE_DEBUG = 2;
    public static final int TYPE_INFO = 2;
    public static final int TYPE_WARN = 3;
    public static final int TYPE_ERROR = 3;

    private static volatile boolean loganEnabled = true;   // 关 = 纯直通，logcat 零变化
    private static volatile boolean ready = false;          // Logan.init 成功后由宿主调 markReady()
    private static long lastFp;                             // 同文案 1s 限频（写放大保护）
    private static String lastMsg;

    private LoganLogBridge() {}

    public static void setLoganEnabled(boolean enabled) { loganEnabled = enabled; }
    public static void markReady() { ready = true; }
    public static boolean isReady() { return ready; }

    public static int v(String tag, String msg) { Log.v(tag, msg); toLogan('V', tag, msg, null); return 0; }
    public static int v(String tag, String msg, Throwable tr) { Log.v(tag, msg, tr); toLogan('V', tag, msg, tr); return 0; }
    public static int v(String tag, Throwable tr) { Log.v(tag, "", tr); toLogan('V', tag, null, tr); return 0; }
    public static int d(String tag, String msg) { Log.d(tag, msg); toLogan('D', tag, msg, null); return 0; }
    public static int d(String tag, String msg, Throwable tr) { Log.d(tag, msg, tr); toLogan('D', tag, msg, tr); return 0; }
    public static int i(String tag, String msg) { Log.i(tag, msg); toLogan('I', tag, msg, null); return 0; }
    public static int i(String tag, String msg, Throwable tr) { Log.i(tag, msg, tr); toLogan('I', tag, msg, tr); return 0; }
    public static int w(String tag, String msg) { Log.w(tag, msg); toLogan('W', tag, msg, null); return 0; }
    public static int w(String tag, String msg, Throwable tr) { Log.w(tag, msg, tr); toLogan('W', tag, msg, tr); return 0; }
    public static int w(String tag, Throwable tr) { Log.w(tag, tr); toLogan('W', tag, null, tr); return 0; }
    public static int e(String tag, String msg) { Log.e(tag, msg); toLogan('E', tag, msg, null); return 0; }
    public static int e(String tag, String msg, Throwable tr) { Log.e(tag, msg, tr); toLogan('E', tag, msg, tr); return 0; }
    public static int println(int priority, String tag, String msg) { Log.println(priority, tag, msg); toLogan((char) "VVDDIIEEE".charAt(priority >= 2 && priority <= 8 ? priority : 4), tag, msg, null); return 0; }

    private static void toLogan(char level, String tag, String msg, Throwable tr) {
        if (!loganEnabled || !ready) return;
        try {
            String body = format(level, tag, msg, tr);
            long now = System.currentTimeMillis();
            if (body.equals(lastMsg) && now - lastFp < 1000) return; // 同文案每秒至多一条
            lastMsg = body;
            lastFp = now;
            int type = level == 'E' ? TYPE_ERROR : level == 'W' ? TYPE_WARN : TYPE_DEBUG;
            Logan.w(body, type);
        } catch (Throwable ignore) {
            // 采集层任何异常绝不影响业务与原输出
        }
    }

    private static String format(char level, String tag, String msg, Throwable tr) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(level).append('/').append(tag == null ? "?" : tag).append(": ").append(msg == null ? "" : msg);
        if (tr != null) {
            sb.append("\n").append(Log.getStackTraceString(tr));
        }
        return sb.toString();
    }
}
