package com.ccic.logan.http;

/**
 * 单条 HTTP 观测事件（纯数据，宿主/桥只填字段，落库归一化与指纹全部在平台侧算，客户端零重复逻辑）。
 * 时间一律毫秒；未采集到的耗时字段保持 null（平台侧列可空），不要伪造 0。
 */
public final class LoganHttpEvent {

    /** native = 原生网络栈观测；h5 = 容器内页面脚本观测。只有这两个取值，非法值平台会拒。 */
    public String kind = "native";
    public String method = "GET";
    /** 原始完整 URL；脱敏与归一化由平台 HttpNorm 统一做。 */
    public String url;
    /** HTTP 状态码；传输层失败（没拿到响应）填 0 并写 errKind。 */
    public int status;
    /** timeout/connect/ssl/parse/cancel/other，服务端还会按 status 与消息兜底归类。 */
    public String errKind;
    public String errMsg;
    /** 请求总耗时（发出到收到响应/失败），必填，平台按它判慢。 */
    public long durationMs;
    public Long connectMs;
    public Long dnsMs;
    /** 收到响应头的耗时近似值；没有就留 null。 */
    public Long ttfbMs;
    public Long reqBytes;
    public Long respBytes;
    /** null = 由 SDK 用全局慢阈值自动判定（默认 3000ms）；显式传值以宿主为准。 */
    public Boolean slow;
    /** h5 事件的宿主页面地址。 */
    public String pageUrl;
    public String traceId;
    /** 事件发生毫秒；0=SDK 填当前时间。 */
    public long occurredAt;
    public String networkType;
    public String extra;

    public LoganHttpEvent() {}
}
