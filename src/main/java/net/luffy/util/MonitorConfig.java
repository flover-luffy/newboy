package net.luffy.util;

import net.luffy.Newboy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 监控配置管理类
 * 负责加载和管理monitor-config.properties中的配置项
 */
public class MonitorConfig {
    private static MonitorConfig instance;
    private final Properties properties;
    
    // 网络配置
    private final int connectTimeout;
    private final int readTimeout;
    private final int maxRetries;
    private final long retryBaseDelay;
    private final long retryMaxDelay;
    
    // 健康检查配置
    private final int maxConsecutiveFailures;
    private final long healthCheckInterval;
    private final double failureRateThreshold;
    private final int failureCooldownBase;
    private final int failureCooldownMax;
    private final long failureCooldown;
    
    // 缓存配置
    private final long cacheExpireTime;
    private final long cacheCleanupInterval;
    private final long healthStatsRetention;
    
    // 监控配置
    private final long statusCheckInterval;
    private final boolean verboseLogging;
    private final boolean performanceEnabled;
    private final boolean healthWarningEnabled;
    
    // 通知配置
    private final boolean showHealthInNotification;
    private final boolean systemHealthWarning;
    private final long healthWarningInterval;
    
    // 高级配置
    private final boolean adaptiveIntervalEnabled;
    private final long adaptiveIntervalMin;
    private final long adaptiveIntervalMax;
    private final boolean batchQueryEnabled;
    private final int batchQuerySize;
    private final boolean asyncProcessingEnabled;
    private final int asyncThreadPoolSize;
    
    // 批量查询配置
    private long batchQueryInterval;
    private final long batchQueryTimeout;
    private final int batchQueryMaxConcurrent;
    
    // 可变配置字段（用于运行时更新）
    private int dynamicBatchQuerySize;
    private long dynamicCacheExpireTime;
    
    // 消息延迟优化配置已迁移到config.setting中
    
    private MonitorConfig() {
        properties = new Properties();
        loadConfiguration();
        
        // 初始化网络配置 - 优化为3秒内响应
        connectTimeout = getIntProperty("monitor.network.connect.timeout", 2000);
        readTimeout = getIntProperty("monitor.network.read.timeout", 3000);
        maxRetries = getIntProperty("monitor.network.max.retries", 3);
        retryBaseDelay = getLongProperty("monitor.network.retry.base.delay", 1000L);
        retryMaxDelay = getLongProperty("monitor.network.retry.max.delay", 10000L);
        
        // 初始化健康检查配置 - 优化为实时监控
        maxConsecutiveFailures = getIntProperty("monitor.health.max.consecutive.failures", 3);
        healthCheckInterval = getLongProperty("monitor.health.check.interval", 120000L);
        failureRateThreshold = getDoubleProperty("monitor.health.failure.rate.threshold", 0.5);
        failureCooldownBase = getIntProperty("monitor.health.failure.cooldown.base", 5);
        failureCooldownMax = getIntProperty("monitor.health.failure.cooldown.max", 60);
        failureCooldown = getLongProperty("monitor.health.failure.cooldown", 120000L);
        
        // 初始化缓存配置 - 优化为实时性
        cacheExpireTime = getLongProperty("monitor.cache.expire.time", 15000L);
        cacheCleanupInterval = getLongProperty("monitor.cache.cleanup.interval", 600000L);
        healthStatsRetention = getLongProperty("monitor.health.stats.retention", 86400000L);
        
        // 初始化监控配置 - 设置为5秒实时检查
        statusCheckInterval = getLongProperty("monitor.status.check.interval", 5000L);
        verboseLogging = getBooleanProperty("monitor.logging.verbose", true);
        performanceEnabled = getBooleanProperty("monitor.performance.enabled", true);
        healthWarningEnabled = getBooleanProperty("monitor.health.warning.enabled", true);
        
        // 初始化通知配置
        showHealthInNotification = getBooleanProperty("monitor.notification.show.health", true);
        systemHealthWarning = getBooleanProperty("monitor.notification.system.health.warning", true);
        healthWarningInterval = getLongProperty("monitor.notification.health.warning.interval", 1800000L);
        
        // 初始化高级配置 - 优化自适应间隔以配合实时监控
        adaptiveIntervalEnabled = getBooleanProperty("monitor.adaptive.interval.enabled", true);
        adaptiveIntervalMin = getLongProperty("monitor.adaptive.interval.min", 10000L);
        adaptiveIntervalMax = getLongProperty("monitor.adaptive.interval.max", 120000L);
        batchQueryEnabled = getBooleanProperty("monitor.batch.query.enabled", true);
        batchQuerySize = getIntProperty("monitor.batch.query.size", 5);
        asyncProcessingEnabled = getBooleanProperty("monitor.async.processing.enabled", true);
        asyncThreadPoolSize = getIntProperty("monitor.async.thread.pool.size", 6); // 从3增加到6以适应低CPU占用率
        
        // 初始化批量查询配置 - 优化响应速度
        batchQueryInterval = getLongProperty("monitor.batch.query.interval", 1000L);
        batchQueryTimeout = getLongProperty("monitor.batch.query.timeout", 3000L);
        batchQueryMaxConcurrent = getIntProperty("monitor.batch.query.max.concurrent", 3);
        
        // 初始化动态配置字段
        dynamicBatchQuerySize = batchQuerySize;
        dynamicCacheExpireTime = cacheExpireTime;
        
        // 消息延迟优化配置已迁移到config.setting中
        
        logConfigurationSummary();
    }
    
    /**
     * 获取单例实例
     */
    public static MonitorConfig getInstance() {
        if (instance == null) {
            synchronized (MonitorConfig.class) {
                if (instance == null) {
                    instance = new MonitorConfig();
                }
            }
        }
        return instance;
    }
    
    /**
     * 加载配置文件
     */
    private void loadConfiguration() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("monitor-config.properties")) {
            if (input != null) {
                properties.load(input);
                // 监控配置文件加载成功
            } else {
                // 未找到monitor-config.properties文件，使用默认配置
            }
        } catch (IOException e) {
            Newboy.INSTANCE.getLogger().error("加载监控配置文件失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 记录配置摘要
     */
    private void logConfigurationSummary() {
        // 监控配置加载完成，静默处理
    }
    
    // 配置获取辅助方法
    private int getIntProperty(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                Newboy.INSTANCE.getLogger().error("配置项 " + key + " 格式错误，使用默认值: " + defaultValue);
            }
        }
        return defaultValue;
    }
    
    private long getLongProperty(String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                Newboy.INSTANCE.getLogger().error("配置项 " + key + " 格式错误，使用默认值: " + defaultValue);
            }
        }
        return defaultValue;
    }
    
    private double getDoubleProperty(String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            try {
                return Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                Newboy.INSTANCE.getLogger().error("配置项 " + key + " 格式错误，使用默认值: " + defaultValue);
            }
        }
        return defaultValue;
    }
    
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value.trim());
        }
        return defaultValue;
    }
    
    // Getter方法
    public int getConnectTimeout() { return connectTimeout; }
    public int getReadTimeout() { return readTimeout; }
    public int getMaxRetries() { return maxRetries; }
    public long getRetryBaseDelay() { return retryBaseDelay; }
    public long getRetryMaxDelay() { return retryMaxDelay; }
    
    public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
    public long getHealthCheckInterval() { return healthCheckInterval; }
    public double getFailureRateThreshold() { return failureRateThreshold; }
    public int getFailureCooldownBase() { return failureCooldownBase; }
    public int getFailureCooldownMax() { return failureCooldownMax; }
    public long getFailureCooldown() { return failureCooldown; }
    
    public long getCacheCleanupInterval() { return cacheCleanupInterval; }
    public long getHealthStatsRetention() { return healthStatsRetention; }
    
    public long getStatusCheckInterval() { return statusCheckInterval; }
    public boolean isVerboseLogging() { return verboseLogging; }
    public boolean isPerformanceEnabled() { return performanceEnabled; }
    public boolean isHealthWarningEnabled() { return healthWarningEnabled; }
    
    public boolean isShowHealthInNotification() { return showHealthInNotification; }
    public boolean isSystemHealthWarning() { return systemHealthWarning; }
    public long getHealthWarningInterval() { return healthWarningInterval; }
    
    public boolean isAdaptiveIntervalEnabled() { return adaptiveIntervalEnabled; }
    public long getAdaptiveIntervalMin() { return adaptiveIntervalMin; }
    public long getAdaptiveIntervalMax() { return adaptiveIntervalMax; }
    public boolean isBatchQueryEnabled() { return batchQueryEnabled; }
    public boolean isAsyncProcessingEnabled() { return asyncProcessingEnabled; }
    public int getAsyncThreadPoolSize() { return asyncThreadPoolSize; }
    
    public long getBatchQueryInterval() { return batchQueryInterval; }
    public long getBatchQueryTimeout() { return batchQueryTimeout; }
    public int getBatchQueryMaxConcurrent() { return batchQueryMaxConcurrent; }
    
    // 动态配置的getter方法
    public int getBatchQuerySize() { return dynamicBatchQuerySize; }
    public long getCacheExpireTime() { return dynamicCacheExpireTime; }
    
    // 动态配置的setter方法
    public void setBatchQueryInterval(long batchQueryInterval) {
        this.batchQueryInterval = batchQueryInterval;
    }
    
    public void setBatchQuerySize(int batchQuerySize) {
        this.dynamicBatchQuerySize = batchQuerySize;
    }
    
    public void setCacheExpireTime(long cacheExpireTime) {
        this.dynamicCacheExpireTime = cacheExpireTime;
    }
    
    // 消息延迟优化配置 Getter 方法已移除 - 配置已迁移到config.setting中
    
    // 热重载功能已移除 - 该功能无法正常工作
}