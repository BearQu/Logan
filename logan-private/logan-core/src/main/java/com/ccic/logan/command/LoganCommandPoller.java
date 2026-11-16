package com.ccic.logan.command;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ccic.logan.device.LoganDeviceInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 平台侧主动拉日志的客户端轮询器（"轮询命令通道"，与平台零代码耦合）。

 * 语义：平台「设备清单」页点「拉取日志」写入一条待下发命令；本类按固定间隔带机器码来领任务，
 * 领到 upload_logs 就回调宿主上传，完成后回执 ack，平台侧状态从 pending→delivered→acked 闭环。
 * 同时本类每次轮询都上报版本/机型，平台据此判断设备在线（last_poll_time）。

 * 零第三方依赖（HttpURLConnection + org.json）：与崩溃通道同一档稳定性要求。
 */
public final class LoganCommandPoller {

    private static final String TAG = "LoganCommand";

    /** 领到任务后的动作，由宿主实现（Android SDK 不碰上传细节，上传在宿主 LoganKit）。 */
    public interface Handler2 {
        /**
         * 返回 true 表示任务确实完成（SDK 会向平台 ack ok=true）；返回 false 表示失败（ack ok=false）。
         * 在 SDK 的轮询线程上回调，宿主自己决定是否切线程。
         */
        boolean onCommand(String action, long commandId, String param);
    }

    private static volatile LoganCommandPoller sInstance;

    private String baseUrl = "";
    private String appId = "";
    private String deviceId = "";
    private String appVersion = "";
    private String buildVersion = "";
    private String sdkVersion = "0.1.0";
    private final Map<String, String> headers = new HashMap<String, String>();
    private Handler2 handler;
    private Thread worker;
    private volatile boolean running;
    private volatile long intervalMs = 60000L;

    /** 采集设备信息用的 ApplicationContext（由宿主 LoganKit 注入）。 */
    private volatile Context appContext;
    private volatile boolean infoReported;

    private LoganCommandPoller() {}

    public static LoganCommandPoller get() {
        if (sInstance == null) {
            synchronized (LoganCommandPoller.class) {
                if (sInstance == null) {
                    sInstance = new LoganCommandPoller();
                }
            }
        }
        return sInstance;
    }

    /** 配置连接信息，必须在 {@link #start()} 之前调用。 */
    public LoganCommandPoller configure(String baseUrl, String appId, String deviceId, String appVersion,
                                       String buildVersion, Map<String, String> extraHeaders) {
        this.baseUrl = baseUrl == null ? "" : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.appId = appId == null ? "" : appId;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.appVersion = appVersion == null ? "" : appVersion;
        this.buildVersion = buildVersion == null ? "" : buildVersion;
        this.headers.clear();
        if (extraHeaders != null) {
            this.headers.putAll(extraHeaders);
        }
        return this;
    }

    /** 注入 ApplicationContext，供 collect_info 采集设备信息（内存/存储/CPU/电量/网络）。 */
    public LoganCommandPoller attachContext(Context ctx) {
        this.appContext = ctx == null ? null : ctx.getApplicationContext();
        return this;
    }

    public LoganCommandPoller onCommand(Handler2 h) {
        this.handler = h;
        return this;
    }

    /** 轮询间隔，最小 15 秒（别把平台打爆）。 */
    public LoganCommandPoller setIntervalMs(long ms) {
        this.intervalMs = Math.max(15000L, ms);
        return this;
    }

    /** 启动后台轮询（幂等）。冷启动调用一次即可。 */
    public synchronized void start() {
        if (running || baseUrl.length() == 0 || appId.length() == 0 || deviceId.length() == 0) {
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                // 首轮稍等，避开启动高峰；之后按固定间隔
                for (long wait = 8000L; running; wait = intervalMs) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!running) {
                        return;
                    }
                    try {
                        if (!infoReported) {
                    infoReported = reportDeviceInfoOnce("boot");
                }
                pollOnce();
                    } catch (Throwable t) {
                        Log.w(TAG, "poll failed: " + t.getMessage());
                    }
                }
            }
        }, "logan-command-poller");
        worker.setDaemon(true);
        worker.start();
        Log.i(TAG, "command poller started, interval=" + intervalMs + "ms");
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) {
            try {
                worker.interrupt();
            } catch (Throwable ignore) {
            }
            worker = null;
        }
    }

    /** 手动领一次任务（宿主调试入口/前台刷新时可用）。 */
    public void pollOnce() {
        JSONArray cmds = null;
        try {
            JSONObject req = new JSONObject();
            req.put("appId", appId);
            req.put("deviceId", deviceId);
            req.put("platform", 1);
            req.put("appVersion", appVersion);
            req.put("buildVersion", buildVersion);
            req.put("sdkVersion", sdkVersion);
            String resp = post("/logan/device/poll.json", req.toString());
            JSONObject obj = new JSONObject(resp);
            if (obj.optInt("code") == 200) {
                cmds = obj.optJSONArray("data");
            }
        } catch (Throwable t) {
            Log.w(TAG, "poll request failed: " + t.getMessage());
            return;
        }
        if (cmds == null || cmds.length() == 0) {
            return;
        }
        for (int i = 0; i < cmds.length(); i++) {
            JSONObject c = cmds.optJSONObject(i);
            if (c == null) {
                continue;
            }
            final long id = c.optLong("id");
            final String action = c.optString("action");
            final String param = c.optString("param", null);
            Log.i(TAG, "command received id=" + id + " action=" + action);
            if ("collect_info".equals(action)) {
                // 设备信息属于 SDK 职责（与机器码同源），不下放宿主，避免各端各写一套
                boolean done = reportDeviceInfoOnce("command");
                ack(id, done, done ? null : "device info collect failed");
                continue;
            }
            boolean ok = false;
            try {
                Handler2 h = handler;
                if (h != null) {
                    ok = h.onCommand(action, id, param);
                }
            } catch (Throwable t) {
                Log.w(TAG, "command handler failed: " + t.getMessage());
                ok = false;
            }
            ack(id, ok, ok ? null : "client handler returned false");
        }
    }

    /**
     * 采集并上报设备信息快照（真值，取不到的字段由采集器省略）。
     * why=boot 表示启动顺带上报，why=command 表示平台下发 collect_info。
     */
    private boolean reportDeviceInfoOnce(String why) {
        Context ctx = appContext;
        if (ctx == null || appId.length() == 0 || deviceId.length() == 0) {
            Log.w(TAG, "device info skipped, no context or ids, trigger=" + why);
            return false;
        }
        try {
            String info = LoganDeviceInfo.snapshot(ctx);
            if (info == null || info.length() < 3) {
                return false;
            }
            JSONObject req = new JSONObject();
            req.put("appId", appId);
            req.put("deviceId", deviceId);
            req.put("platform", 1);
            req.put("appVersion", appVersion);
            req.put("buildVersion", buildVersion);
            req.put("trigger", why);
            req.put("info", new JSONObject(info));
            String resp = post("/logan/device/info/report.json", req.toString());
            boolean ok = new JSONObject(resp).optInt("code") == 200;
            Log.i(TAG, "device info reported, trigger=" + why + ", ok=" + ok + ", bytes=" + info.length());
            return ok;
        } catch (Throwable t2) {
            Log.w(TAG, "device info report failed: " + t2.getMessage());
            return false;
        }
    }

    private void ack(long commandId, boolean ok, String resultNote) {
        try {
            JSONObject req = new JSONObject();
            req.put("id", commandId);
            req.put("ok", ok);
            if (resultNote != null) {
                req.put("resultNote", resultNote);
            }
            post("/logan/device/command/ack.json", req.toString());
        } catch (Throwable t) {
            Log.w(TAG, "ack failed: " + t.getMessage());
        }
    }

    /** 纯 JSON POST（零依赖）。返回响应体，非 2xx 返回 {}。 */
    private String post(String path, String json) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("appId", appId);
            conn.setRequestProperty("deviceId", deviceId);
            conn.setRequestProperty("platform", "1");
            conn.setRequestProperty("appVersion", appVersion);
            conn.setRequestProperty("buildVersion", buildVersion);
            conn.setRequestProperty("sdkVersion", sdkVersion);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            byte[] body = json.getBytes("UTF-8");
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            out.write(body);
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            if (is != null) {
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                is.close();
            }
            if (code != 200) {
                Log.w(TAG, "poll http " + code + " " + bos.toString("UTF-8"));
                return "{}";
            }
            return bos.toString("UTF-8");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}