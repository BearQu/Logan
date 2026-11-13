# LoganHarmony — Logan 鸿蒙 SDK（HAR）

零第三方依赖：崩溃捕获全部走官方系统通道（API12 起可用，签名均对照本机 DevEco SDK d.ts 核实）：
- JS 未捕获异常：`@ohos.app.ability.errorManager` `on('error', ErrorObserver)`（官方方法是 `onUnhandledException(errMsg)`）→ 当场 type99 落盘 + 裸 HTTPS POST（A 通道）
- native crash / freeze / 漏网崩溃：下次启动扫 `/data/storage/el2/log/faultlog/faultlogger/` 补读（B 通道，seen 名单去重）。本 SDK 的 `@ohos.faultLogger` `FaultLogInfo` 仅含元数据无内容字段，故内容走沙箱文件直读
- 未处理 Promise：`errorManager.on('unhandledRejection')` 记 type4 常规日志

内核 = 共享 clogan C 源码（`../../logan-core/src/main/native`，与 Android/iOS 同一份）经 OHOS NDK 编 `liblogan_napi.so` → 落盘格式与 Server 零改动兼容。

## 存量日志接入（对齐 Android 编译期插件语义）
本 SDK d.ts 的 `@ohos.hilog` **没有** hilog.on 订阅 API（官方全量函数表已核，网上流传的写法不成立）。
目标工程（如 ccic-harmonyos）本就有自写 Log 封装——在其内部把 `hilog.info/warn/error` 换成 `LoganCompat.i/w/e` 一处改动即可：原 hilog 输出在 LoganCompat 内原样保留（双写非替换），49 个业务调用点零改动。`LoganCompat.setEnabled(false)` 即回原样。

## 接入示例（EntryAbility.onCreate 一次）
```ts
import { LoganCore, LoganCrash, CrashConfig } from '@ccic/logan-harmony';

LoganCore.init(this.context.filesDir);
const cfg = new CrashConfig();
cfg.appId = 'com.ccic.bxmx'; cfg.appVersion = '1.3.0'; cfg.buildVersion = '1000030';
cfg.deviceId = /* 设备码 */; cfg.unionId = /* 用户联合 id */; cfg.userId = '';
cfg.authHeader = 'Basic ' + /* base64(user:pass) */;
LoganCrash.init(cfg, this.context.filesDir);
```
崩溃 envelope schema 与 Android logan-crash 完全一致（CrashEnvelope.ets），同一后端 handler 零改动吃两端数据。

## 构建
DevEco Studio 打开本目录（最小工程壳），或命令行：
`hvigorw --mode module -p module=LoganHarmony@default assembleHar`
产物 `LoganHarmony/build/default/outputs/default/LoganHarmony.har`。
