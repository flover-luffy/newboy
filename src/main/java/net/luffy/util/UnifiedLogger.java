package net.luffy.util;

import net.luffy.Newboy;
import net.mamoe.mirai.utils.MiraiLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一日志门面
 * 提供统一的日志接口，替代混用的java.util.logging和Newboy.INSTANCE.getLogger()
 */
public class UnifiedLogger {
    private static final UnifiedLogger INSTANCE = new UnifiedLogger();
    private final MiraiLogger miraiLogger;
    private final Map<String, AtomicLong> logCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> errorCounters = new ConcurrentHashMap<>();
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    // 日志级别枚举
    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }
    
    private UnifiedLogger() {
        this.miraiLogger = Newboy.INSTANCE.getLogger();
    }
    
    public static UnifiedLogger getInstance() {
        return INSTANCE;
    }
    
    /**
     * 记录调试日志
     */
    public void debug(String component, String message) {
        log(Level.DEBUG, component, message, null);
    }
    
    /**
     * 记录信息日志
     */
    public void info(String component, String message) {
        log(Level.INFO, component, message, null);
    }
    
    /**
     * 记录警告日志
     */
    public void warn(String component, String message) {
        log(Level.WARN, component, message, null);
    }
    
    /**
     * 记录错误日志
     */
    public void error(String component, String message) {
        log(Level.ERROR, component, message, null);
    }
    
    /**
     * 记录错误日志（带异常）
     */
    public void error(String component, String message, Throwable throwable) {
        log(Level.ERROR, component, message, throwable);
    }
    
    /**
     * 统一日志记录方法
     */
    private void log(Level level, String component, String message, Throwable throwable) {
        // 更新计数器
        String counterKey = component + "_" + level.name();
        logCounters.computeIfAbsent(counterKey, k -> new AtomicLong(0)).incrementAndGet();
        
        if (level == Level.ERROR) {
            errorCounters.computeIfAbsent(component, k -> new AtomicInteger(0)).incrementAndGet();
        }
        
        // 格式化日志消息
        String timestamp = LocalDateTime.now().format(timeFormatter);
        String formattedMessage = String.format("[%s][%s] %s", timestamp, component, message);
        
        // 根据级别输出日志
        switch (level) {
            case DEBUG:
                // Debug级别日志默认不输出，可通过配置启用
                break;
            case INFO:
                miraiLogger.info(formattedMessage);
                break;
            case WARN:
                miraiLogger.warning(formattedMessage);
                break;
            case ERROR:
                if (throwable != null) {
                    miraiLogger.error(formattedMessage, throwable);
                } else {
                    miraiLogger.error(formattedMessage);
                }
                break;
        }
    }
    
    /**
     * 获取组件的日志统计
     */
    public Map<String, Long> getLogStats(String component) {
        Map<String, Long> stats = new ConcurrentHashMap<>();
        for (Level level : Level.values()) {
            String key = component + "_" + level.name();
            AtomicLong counter = logCounters.get(key);
            stats.put(level.name(), counter != null ? counter.get() : 0L);
        }
        return stats;
    }
    
    /**
     * 获取组件的错误计数
     */
    public int getErrorCount(String component) {
        AtomicInteger counter = errorCounters.get(component);
        return counter != null ? counter.get() : 0;
    }
    
    /**
     * 重置统计计数器
     */
    public void resetStats() {
        logCounters.clear();
        errorCounters.clear();
    }
    
    /**
     * 获取所有组件的日志统计摘要
     */
    public String getLogSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("=== 日志统计摘要 ===\n");
        
        Map<String, Map<String, Long>> componentStats = new ConcurrentHashMap<>();
        
        // 按组件分组统计
        for (Map.Entry<String, AtomicLong> entry : logCounters.entrySet()) {
            String[] parts = entry.getKey().split("_", 2);
            if (parts.length == 2) {
                String component = parts[0];
                String level = parts[1];
                componentStats.computeIfAbsent(component, k -> new ConcurrentHashMap<>())
                    .put(level, entry.getValue().get());
            }
        }
        
        // 输出统计信息
        for (Map.Entry<String, Map<String, Long>> entry : componentStats.entrySet()) {
            String component = entry.getKey();
            Map<String, Long> stats = entry.getValue();
            summary.append(String.format("[%s] INFO:%d, WARN:%d, ERROR:%d\n",
                component,
                stats.getOrDefault("INFO", 0L),
                stats.getOrDefault("WARN", 0L),
                stats.getOrDefault("ERROR", 0L)
            ));
        }
        
        return summary.toString();
    }
}