package com.ccic.logan.device;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/**
 * 设备运行信息采集（真值，绝不伪造）：内存 / 存储 / CPU / 运行时 / 电量 / 网络 / 设备与系统。
 *
 * 规则：某项取不到或没权限，就不写这个键，前端按缺省显示 —— 宁缺毋假。
 * 全部只读公开 API，不申请任何新权限（电量走 sticky intent，READ_PHONE_STATE 一律不用）。
 * GPU 在 Android 无稳定公开 API，故不写 gpu 字段（不伪造）。
 */
public final class LoganDeviceInfo {

    private static final String TAG = "LoganDeviceInfo";

    private LoganDeviceInfo() {}

    /** 采集一次快照，返回 JSON 字符串；异常时返回空对象而不是 null，方便上层统一处理。 */
    public static String snapshot(Context ctx) {
        JSONObject root = new JSONObject();
        try {
            putDevice(root, ctx);
            putMemory(root, ctx);
            putStorage(root);
            putCpu(root);
            putBattery(root, ctx);
            putNetwork(root, ctx);
        } catch (Throwable t) {
            Log.w(TAG, "collect failed: " + t.getMessage());
        }
        return root.toString();
    }

    // ---------------- 设备与应用 ----------------

    private static void putDevice(JSONObject root, Context ctx) {
        put(root, "brand", Build.BRAND);
        put(root, "manufacturer", Build.MANUFACTURER);
        put(root, "model", Build.MODEL);
        put(root, "device", Build.DEVICE);
        put(root, "product", Build.PRODUCT);
        put(root, "hardware", Build.HARDWARE);
        put(root, "board", Build.BOARD);
        put(root, "os", "android");
        put(root, "osVersion", Build.VERSION.RELEASE);
        put(root, "sdkInt", Build.VERSION.SDK_INT);
        put(root, "incremental", Build.VERSION.INCREMENTAL);
        put(root, "abi", primaryAbi());
        put(root, "cpuCount", Runtime.getRuntime().availableProcessors());
        put(root, "bootloader", Build.BOOTLOADER);
        put(root, "isEmulator", looksLikeEmulator());
        put(root, "locale", Locale.getDefault().toString());
        put(root, "timezone", java.util.TimeZone.getDefault().getID());
        long uptime = SystemClock.elapsedRealtime();
        if (uptime > 0) {
            put(root, "uptimeSeconds", Long.valueOf(uptime / 1000L));
        }
        if (ctx == null) {
            return;
        }
        try {
            PackageManager pm = ctx.getPackageManager();
            if (pm != null) {
                PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
                if (pi != null) {
                    put(root, "appName", String.valueOf(pm.getApplicationLabel(ctx.getApplicationInfo())));
                    put(root, "packageName", ctx.getPackageName());
                    put(root, "appVersion", pi.versionName);
                    put(root, "buildVersion", Long.valueOf(pi.getLongVersionCode()));
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就不写
        }
        try {
            String aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            put(root, "androidIdHash", aid == null ? null : shortHash(aid));
        } catch (Throwable ignored) {
            // 不上传原始 ANDROID_ID，只留短摘要供人工比对
        }
    }

    // ---------------- 内存 ----------------

    private static void putMemory(JSONObject root, Context ctx) {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        if (max > 0) {
            put(root, "javaMaxHeapBytes", Long.valueOf(max));
            put(root, "javaUsedHeapBytes", Long.valueOf(Math.max(0L, total - free)));
        }
        if (ctx == null) {
            return;
        }
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                put(root, "totalMemBytes", Long.valueOf(mi.totalMem));
                put(root, "availMemBytes", Long.valueOf(mi.availMem));
                put(root, "lowMemory", Boolean.valueOf(mi.lowMemory));
            }
        } catch (Throwable ignored) {
        }
        try {
            Debug.MemoryInfo dm = new Debug.MemoryInfo();
            Debug.getMemoryInfo(dm);
            if (dm.getTotalPss() > 0) {
                put(root, "appPssKb", Integer.valueOf(dm.getTotalPss()));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 存储 ----------------

    private static void putStorage(JSONObject root) {
        File[] targets = new File[]{Environment.getDataDirectory(), Environment.getExternalStorageDirectory()};
        String[] names = new String[]{"data", "external"};
        for (int i = 0; i < targets.length; i++) {
            File f = targets[i];
            if (f == null) {
                continue;
            }
            try {
                StatFs sf = new StatFs(f.getAbsolutePath());
                long total = sf.getTotalBytes();
                long avail = sf.getAvailableBytes();
                if (total <= 0) {
                    continue;
                }
                put(root, names[i] + "TotalBytes", Long.valueOf(total));
                put(root, names[i] + "AvailBytes", Long.valueOf(avail));
                put(root, names[i] + "UsedBytes", Long.valueOf(total - avail));
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- CPU ----------------

    /** 两次采样 /proc/stat 求区间占用率（500ms 窗口，真值），另附各核当前频率与调速器。 */
    private static void putCpu(JSONObject root) {
        long[] first = readProcStat();
        if (first != null) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            long[] second = readProcStat();
            if (second != null) {
                long totalDelta = sum(second) - sum(first);
                long idleDelta = second[3] - first[3];
                if (totalDelta > 0) {
                    double pct = (totalDelta - Math.max(0L, idleDelta)) * 100.0d / (double) totalDelta;
                    put(root, "cpuUsagePercent", Double.valueOf(round1(pct)));
                }
            }
        }
        try {
            int n = Runtime.getRuntime().availableProcessors();
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            for (int i = 0; i < n; i++) {
                String v = readOneLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
                if (sb.length() > 0) {
                    sb.append(",");
                }
                if (v != null && v.trim().length() > 0) {
                    sb.append(v.trim());
                    any = true;
                } else {
                    sb.append("-");
                }
            }
            if (any) {
                put(root, "cpuCurFreqKhz", sb.toString());
            }
        } catch (Throwable ignored) {
        }
        try {
            String gov = readOneLine("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor");
            put(root, "cpuGovernor", gov == null ? null : gov.trim());
        } catch (Throwable ignored) {
        }
        try {
            String stat = readOneLine("/proc/self/stat");
            if (stat != null) {
                int close = stat.lastIndexOf(")");
                String tail = close > 0 ? stat.substring(close + 2) : stat;
                String[] parts = tail.split("\\s+");
                if (parts.length > 13) {
                    long ut = Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
                    put(root, "procCpuJiffies", Long.valueOf(ut));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 电量（sticky intent，无需权限） ----------------

    private static void putBattery(JSONObject root, Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            android.content.Intent bat = ctx.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (bat == null) {
                return;
            }
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                put(root, "batteryPercent", Integer.valueOf((int) Math.round(level * 100.0d / scale)));
            }
            int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (status >= 0) {
                put(root, "charging", Boolean.valueOf(status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL));
            }
            int temp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (temp > 0) {
                put(root, "batteryTempC", Double.valueOf(round1(temp / 10.0d)));
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 网络 ----------------

    private static void putNetwork(JSONObject root, Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) {
                put(root, "network", "none");
                return;
            }
            boolean online = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            String kind = "other";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                kind = "wifi";
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                kind = "cellular";
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                kind = "ethernet";
            } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                kind = "vpn";
            }
            put(root, "network", online ? kind : kind + "_no_internet");
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 工具 ----------------

    private static long sum(long[] a) {
        long s = 0L;
        for (int i = 0; i < a.length; i++) {
            s += a[i];
        }
        return s;
    }

    /** 返回 {user,nice,system,idle(+iowait)}；读不到返回 null。 */
    private static long[] readProcStat() {
        String line = readOneLine("/proc/stat");
        if (line == null || !line.startsWith("cpu ")) {
            return null;
        }
        String[] p = line.trim().split("\\s+");
        if (p.length < 5) {
            return null;
        }
        try {
            long idle = Long.parseLong(p[4]);
            if (p.length > 5) {
                idle += Long.parseLong(p[5]);
            }
            return new long[]{Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]), idle};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readOneLine(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path), 256);
            return br.readLine();
        } catch (Throwable t) {
            return null;
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String primaryAbi() {
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                return Build.SUPPORTED_ABIS[0];
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static boolean looksLikeEmulator() {
        String f = lower(Build.FINGERPRINT);
        String m = lower(Build.MODEL);
        String p = lower(Build.PRODUCT);
        String bn = lower(Build.BOARD);
        return f.contains("generic") || f.contains("unknown") || f.startsWith("/dev/")
                || m.contains("google_sdk") || m.contains("emulator") || m.contains("android sdk built")
                || p.contains("sdk") || bn.contains("goldfish") || bn.contains("ranchu");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    private static String shortHash(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6 && i < d.length; i++) {
                sb.append(String.format(Locale.US, "%02x", d[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0d) / 10.0d;
    }

    private static void put(JSONObject o, String key, Object v) {
        if (o == null || key == null || v == null) {
            return;
        }
        if (v instanceof String && ((String) v).trim().length() == 0) {
            return;
        }
        try {
            o.put(key, v);
        } catch (Throwable ignored) {
        }
    }
}
