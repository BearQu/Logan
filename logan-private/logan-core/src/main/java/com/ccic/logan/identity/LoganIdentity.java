package com.ccic.logan.identity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Logan 统一机器码（设备标识）的唯一出处。日志通道、崩溃通道、应用画像上报必须同源，
 * 否则平台按 device_id 做的「崩溃 ↔ 业务日志」互证与设备数统计会被打乱。

 * 取值语义（与 iOS Keychain / 鸿蒙 Asset Store 同一档「尽量跨卸载」的承诺）：
 *  1) 首选 Settings.Secure.ANDROID_ID —— Android 8.1+ 按「签名+用户+设备」派生，
 *     卸载重装、覆盖安装都不变，是 Android 端能拿到的最稳机器码。
 *  2) 少数 ROM 上 ANDROID_ID 为空或全网相同坏值 → 回落本地持久化 UUID（SharedPreferences）。
 *     该回落值卸载即失效，是 ROM 缺陷下的次优兜底。

 * 宿主不要再自己生成 deviceId：一律调 {@link #deviceId(Context)}。
 */
public final class LoganIdentity {

    private static final String PREF = "logan_identity";
    private static final String KEY = "logan_device_id";

    /** 已知坏的 ANDROID_ID（部分 2.2 / 4.2 机型全网同一个值，无法区分设备）。 */
    private static final String[] BAD_IDS = {"9774d56d682e549c", "unknown"};

    private static volatile String cached = null;

    private LoganIdentity() {}

    /** 取本机的稳定机器码；进程内缓存，重复调用同值。永不返回空串。 */
    public static String deviceId(Context context) {
        String v = cached;
        if (!TextUtils.isEmpty(v)) {
            return v;
        }
        synchronized (LoganIdentity.class) {
            if (!TextUtils.isEmpty(cached)) {
                return cached;
            }
            String id = systemId(context);
            cached = valid(id) ? id : fallbackUuid(context);
            return cached;
        }
    }

    /** 只读探一次系统值（不落盘），便于宿主/测试断言「本机机器码是什么」。 */
    @SuppressLint("HardwareIds")
    public static String systemId(Context context) {
        try {
            return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean valid(String id) {
        if (TextUtils.isEmpty(id) || id.length() < 8) {
            return false;
        }
        for (String bad : BAD_IDS) {
            if (bad.equalsIgnoreCase(id)) {
                return false;
            }
        }
        return true;
    }

    private static String fallbackUuid(Context context) {
        try {
            SharedPreferences sp = context.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String exist = sp.getString(KEY, null);
            if (!TextUtils.isEmpty(exist)) {
                return exist;
            }
            String fresh = java.util.UUID.randomUUID().toString();
            sp.edit().putString(KEY, fresh).apply();
            return fresh;
        } catch (Throwable t) {
            return "unknown-" + Build.MODEL;
        }
    }
}
