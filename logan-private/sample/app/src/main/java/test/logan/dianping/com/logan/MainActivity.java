package test.logan.dianping.com.logan;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.ccic.logan.crash.LoganCrash;
import com.dianping.logan.Logan;
import com.dianping.logan.SendLogCallback;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 验收台：
 *  1) 写业务日志 —— Log.d/e 调用点被编译期插件改写进 Logan（logcat 照常输出）
 *  2) Java崩溃 / Native崩溃 —— LoganCrash 当场直传 + 冷启动补传
 *  3) 上传日志包 —— Logan.s 打公网 PoC 服务端，然后去 LoganSite 查询页验收
 */
public class MainActivity extends Activity {

    private TextView info;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(32, 32, 32, 32);
        info = new TextView(this);
        Button bLog = button("1. 写业务日志(Log.d/e 被插件改写)");
        Button bJava = button("2. 触发 Java 崩溃");
        Button bNative = button("3. 触发 Native 崩溃");
        Button bSend = button("4. 上传日志到 PoC 服务端");
        bLog.setOnClickListener(v -> businessLog());
        bJava.setOnClickListener(v -> xcrash.XCrash.testJavaCrash(false));
        bNative.setOnClickListener(v -> xcrash.XCrash.testNativeCrash(false));
        bSend.setOnClickListener(v -> upload());
        root.addView(bLog);
        root.addView(bJava);
        root.addView(bNative);
        root.addView(bSend);
        root.addView(info);
        ScrollView sc = new ScrollView(this);
        sc.addView(root);
        setContentView(sc);
        append("就绪：deviceId=" + MyApplication.deviceId);
    }


    private void businessLog() {
        // 这些 Log 调用点都会被编译期插件改写为 LoganLogBridge
        Log.d("PolicyFlow", "进入保单确认页 产品=年金D 金额=88000");
        Log.i("PolicyFlow", "点击下一步");
        Log.e("PolicyFlow", "模拟业务异常", new IllegalStateException("保单号重复"));
        append("已写 3 条业务日志(经插件改写)");
    }

    private void upload() {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        Logan.s(new String[]{date}, new com.dianping.logan.SendLogRunnable() {
            @Override
            public void sendLog(java.io.File logFile) {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL(MyApplication.SERVER + "/logan/upload.json").openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/octet-stream");
                    conn.setRequestProperty("appId", MyApplication.APP_ID);
                    conn.setRequestProperty("unionId", "poc-union-sample");
                    conn.setRequestProperty("deviceId", MyApplication.deviceId);
                    conn.setRequestProperty("platform", "1");
                    conn.setRequestProperty("fileDate", date);
                    conn.setRequestProperty("appVersion", "0.1.0");
                    conn.setRequestProperty("buildVersion", "1");
                    String creds = "loganviewer:Logan#PoC2026";
                    conn.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString(creds.getBytes(), android.util.Base64.NO_WRAP));
                    java.io.DataOutputStream out = new java.io.DataOutputStream(conn.getOutputStream());
                    java.io.FileInputStream in = new java.io.FileInputStream(logFile);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.flush();
                    out.close();
                    final int code = conn.getResponseCode();
                    runOnUiThread(() -> append("上传 http=" + code + "，去查询页看明文"));
                } catch (Exception e) {
                    runOnUiThread(() -> append("上传失败: " + e));
                }
            }
        });
        append("上传任务已提交");
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private void append(String s) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        info.append("\n[" + ts + "] " + s);
    }
}
