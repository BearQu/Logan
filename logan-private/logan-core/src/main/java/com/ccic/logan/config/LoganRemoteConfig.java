package com.ccic.logan.config;

import android.content.Context;
import android.content.SharedPreferences;

import com.ccic.logan.http.LoganHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 远程配置（对标 Bugly 收费版，PoC 净新增）。
 * 增量语义与服务端 LoganConfigService 对齐：本地存 version，拉取带 sinceVersion，
 * 服务端只回更大的行；removed 数组里的键本地直接删（墓碑下发）。
 * 存储 = SharedPreferences（logan_remote_config），启动读缓存即时可用，后台线程刷新。
 * 鉴权复用 LoganHttp 的同一份附加头（errorHeaders()），否则会被平台当未授权拒收。
 */
public final class LoganRemoteConfig {
    private static final String PREF = "logan_remote_config";
    private static final String KEY_VERSION = "logan_cfg_version";
    private static final long FETCH_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long MIN_INTERVAL_MS = 30 * 1000L;   // 手动刷新节流，防宿主滥用

    public interface OnChangeListener {
        /** 任意键变化后在后台线程回调一次；宿主据此即时生效（如开关、阈值）。 */
        void onConfigChanged(Map<String, String> configs);
    }

    private static final LoganRemoteConfig S_INSTANCE = new LoganRemoteConfig();

    public static LoganRemoteConfig get() {
        return S_INSTANCE;
    }

    private volatile SharedPreferences pref;
    private volatile long lastFetchAt = 0L;
    private volatile boolean fetching = false;
    private final CopyOnWriteArrayList<OnChangeListener> listeners = new CopyOnWriteArrayList<OnChangeListener>();

    private LoganRemoteConfig() {
    }

    /** 宿主初始化时调用一次（与 LoganHttp.configure 同阶段，需 ApplicationContext）。 */
    public LoganRemoteConfig attachContext(Context ctx) {
        if (ctx != null && pref == null) {
            pref = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }
        return this;
    }

    public LoganRemoteConfig registerListener(OnChangeListener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
        return this;
    }

    /** 本地缓存读值（不触发网络）。未命中返回 def。 */
    public String getString(String key, String def) {
        SharedPreferences p = pref;
        if (p == null) {
            return def;
        }
        String v = p.getString(key, null);
        return v == null ? def : v;
    }

    public boolean getBoolean(String key, boolean def) {
        String v = getString(key, null);
        if (v == null) {
            return def;
        }
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    public long getLong(String key, long def) {
        String v = getString(key, null);
        if (v == null) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public Map<String, String> getAll() {
        SharedPreferences p = pref;
        Map<String, String> out = new HashMap<String, String>();
        if (p == null) {
            return out;
        }
        for (Map.Entry<String, ?> en : p.getAll().entrySet()) {
            if (!KEY_VERSION.equals(en.getKey()) && en.getValue() instanceof String) {
                out.put(en.getKey(), (String) en.getValue());
            }
        }
        return out;
    }

    public long localVersion() {
        SharedPreferences p = pref;
        return p == null ? 0L : p.getLong(KEY_VERSION, 0L);
    }

    /** 节流刷新：距上次不足 MIN_INTERVAL_MS 直接跳过。后台线程执行，绝不在调用线程联网。 */
    public void refreshAsync() {
        refreshAsync(false);
    }

    /** force=true 忽略节流（宿主冷启动第一条推荐传 true，保证装包后立刻拿到首轮配置）。 */
    public void refreshAsync(boolean force) {
        if (!force && System.currentTimeMillis() - lastFetchAt < MIN_INTERVAL_MS) {
            return;
        }
        if (fetching || pref == null || !LoganHttp.get().isConfiguredForError()) {
            return;
        }
        fetching = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    lastFetchAt = System.currentTimeMillis();
                    fetchOnce();
                } catch (Throwable ignore) {
                    // 拉取失败保持本地缓存，下一轮再试
                } finally {
                    fetching = false;
                }
            }
        }, "logan-remote-config").start();
    }

    /** 供宿主在自有定时器里按 FETCH_INTERVAL_MS 周期调用（SDK 不自己起常驻定时器，省流量）。 */
    public long recommendedFetchIntervalMs() {
        return FETCH_INTERVAL_MS;
    }

    private void fetchOnce() throws Exception {
        String baseUrl = LoganHttp.get().errorBaseUrl();
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/logan/config/get.json")
                .openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            for (Map.Entry<String, String> en : LoganHttp.get().errorHeaders().entrySet()) {
                conn.setRequestProperty(en.getKey(), en.getValue());
            }
            // 与崩溃/行为通道同一套传输约定：gzip + Content-Encoding，平台 readBody 已按 gzip 解
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Encoding", "gzip");
            JSONObject body = new JSONObject();
            body.put("appId", LoganHttp.get().errorAppId());
            body.put("sinceVersion", localVersion());
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos);
            gz.write(body.toString().getBytes("UTF-8"));
            gz.finish();
            gz.close();
            byte[] payload = bos.toByteArray();
            conn.setFixedLengthStreamingMode(payload.length);
            conn.getOutputStream().write(payload);
            conn.getOutputStream().close();
            if (conn.getResponseCode() != 200) {
                return;
            }
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            reader.close();
            JSONObject resp = new JSONObject(sb.toString());
            if (resp.optInt("code", 0) != 200) {
                return;
            }
            JSONObject data = resp.optJSONObject("data");
            if (data == null) {
                return;
            }
            SharedPreferences.Editor editor = pref.edit();
            boolean changed = false;
            JSONObject configs = data.optJSONObject("configs");
            if (configs != null) {
                for (java.util.Iterator<String> it = configs.keys(); it.hasNext(); ) {
                    String k = it.next();
                    String v = configs.optString(k, "");
                    if (!v.equals(pref.getString(k, null))) {
                        editor.putString(k, v);
                        changed = true;
                    }
                }
            }
            JSONArray removed = data.optJSONArray("removed");
            if (removed != null) {
                for (int i = 0; i < removed.length(); i++) {
                    String k = removed.optString(i, "");
                    if (k.length() > 0 && pref.contains(k)) {
                        editor.remove(k);
                        changed = true;
                    }
                }
            }
            long serverVersion = data.optLong("version", localVersion());
            if (serverVersion != localVersion()) {
                editor.putLong(KEY_VERSION, serverVersion);
                changed = true;
            }
            editor.apply();
            if (changed) {
                Map<String, String> snapshot = getAll();
                for (OnChangeListener l : listeners) {
                    try {
                        l.onConfigChanged(snapshot);
                    } catch (Throwable ignore) {
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }
}
