package com.meituan.logan.web.task;

import com.meituan.logan.web.service.LoganAlertService;
import org.apache.log4j.Logger;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.Properties;
import java.io.InputStream;

/**
 * 报警检测 worker：定时扫描启用规则并按通用 webhook 触发通知。
 * alert.enabled=false 时完全不跑（对齐 CrashLlmWorker 的配置开关范式）。
 * 单线程 daemon，扫描异常只记日志不外抛，保证容器存活。
 */
@Component
public class LoganAlertWorker {
    private static final Logger LOGGER = Logger.getLogger(LoganAlertWorker.class);
    private static final long DEFAULT_PERIOD_MS = 60_000L;
    private static final long MIN_PERIOD_MS = 15_000L;

    @Resource
    private LoganAlertService alertService;

    // 报警记录保留：默认 7 天，且总数兜底 2000 条（防止规则冷却被调成 0 之类情况灌爆表）
    private long retentionMs = 7L * 24 * 3600 * 1000;
    private int maxRows = 2000;
    private long lastPurgeAt;
    private static final long PURGE_INTERVAL_MS = 3600_000L;

    private volatile boolean running;
    private Thread worker;
    private long periodMs = DEFAULT_PERIOD_MS;

    @PostConstruct
    public void init() {
        Properties cfg = new Properties();
        try {
            InputStream in = getClass().getClassLoader().getResourceAsStream("alert.properties");
            if (in != null) {
                cfg.load(in);
                in.close();
            }
        } catch (Exception e) {
            LOGGER.warn("alert.properties missing, use defaults");
        }
        if (!"true".equalsIgnoreCase(cfg.getProperty("alert.enabled", "true"))) {
            LOGGER.info("alert worker disabled by config");
            return;
        }
        try {
            long p = Long.parseLong(cfg.getProperty("alert.period.ms", String.valueOf(DEFAULT_PERIOD_MS)).trim());
            periodMs = Math.max(MIN_PERIOD_MS, p);
        } catch (Exception ignore) {
        }
        try {
            long d = Long.parseLong(cfg.getProperty("alert.record.retention.days", "7").trim());
            if (d > 0) {
                retentionMs = d * 24L * 3600 * 1000L;
            }
        } catch (Exception ignore) {
        }
        try {
            int mr = Integer.parseInt(cfg.getProperty("alert.record.max.rows", "2000").trim());
            if (mr >= 100) {
                maxRows = mr;
            }
        } catch (Exception ignore) {
        }
        running = true;
        worker = new Thread(this::loop, "logan-alert-worker");
        worker.setDaemon(true);
        worker.start();
        LOGGER.info("alert worker started, period=" + periodMs);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void loop() {
        // 首轮延后 20s，等 spring 容器与连接池完全就绪，避免启动即报错噪音
        try {
            Thread.sleep(20_000L);
        } catch (InterruptedException e) {
            return;
        }
        while (running) {
            try {
                int fired = alertService.scanOnce();
                if (fired > 0) {
                    LOGGER.info("alert scan fired " + fired + " rule(s)");
                }
            } catch (Exception e) {
                LOGGER.warn("alert scan failed: " + e.getMessage());
            }
            try {
                long now = System.currentTimeMillis();
                if (now - lastPurgeAt >= PURGE_INTERVAL_MS) {
                    lastPurgeAt = now;
                    int removed = alertService.purgeRecords(retentionMs, maxRows);
                    if (removed > 0) {
                        LOGGER.info("alert records purged " + removed + " (retentionMs=" + retentionMs + ", maxRows=" + maxRows + ")");
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("alert purge failed: " + e.getMessage());
            }
            try {
                Thread.sleep(periodMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
