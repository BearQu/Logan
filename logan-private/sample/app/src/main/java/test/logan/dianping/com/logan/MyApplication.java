package test.logan.dianping.com.logan;

import android.app.Application;
import android.util.Log;

import com.ccic.logan.bridge.LoganLogBridge;
import com.ccic.logan.crash.CrashConfig;
import com.ccic.logan.crash.LoganCrash;
import com.dianping.logan.Logan;
import com.dianping.logan.LoganConfig;

import java.io.File;

/**
 * logan-private PoC 验收应用：
 *  - 业务代码里的 Log.d/e/w 由编译期插件改写进 LoganLogBridge（本类不在白名单，Log.d 直出）
 *  - 崩溃按钮走 LoganCrash 双通道
 *  - 上传打公网 PoC 服务端 https://ceshixiaozhan.xyz:18443/logan-web
 */
public class MyApplication extends Application {

    public static final String SERVER = "https://ceshixiaozhan.xyz:18443/logan-web";
    public static final String APP_ID = "com.ccic.logan.sample";
    public static String deviceId = "sample-001";

    @Override
    public void onCreate() {
        super.onCreate();
        initLogan();
        LoganLogBridge.markReady(); // Logan 就绪后打开桥的写入库
        initCrash();
        Log.d("logan-sample", "MyApplication onCreate");
    }

    private void initLogan() {
        LoganConfig config = new LoganConfig.Builder()
                .setCachePath(getApplicationContext().getFilesDir().getAbsolutePath())
                .setPath(getApplicationContext().getExternalFilesDir(null).getAbsolutePath()
                        + File.separator + "logan_poc")
                .setEncryptKey16("0123456789012345".getBytes())
                .setEncryptIV16("0123456789012345".getBytes())
                .build();
        Logan.init(config);
        Logan.setDebug(true);
    }

    private void initCrash() {
        CrashConfig cc = new CrashConfig();
        cc.baseUrl = SERVER;
        cc.appId = APP_ID;
        cc.appVersion = "0.1.0";
        cc.buildVersion = "1";
        cc.deviceId = deviceId;
        cc.unionId = "poc-union-sample";
        cc.userId = "验收用户";
        // basic auth（loganviewer 账号）
        String creds = "loganviewer:Logan#PoC2026";
        cc.headers.put("Authorization", "Basic " + android.util.Base64.encodeToString(creds.getBytes(), android.util.Base64.NO_WRAP));
        LoganCrash.init(this, cc);
    }
}
