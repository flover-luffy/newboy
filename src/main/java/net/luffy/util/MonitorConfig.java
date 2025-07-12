package net.luffy.util;

import net.luffy.Newboy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 监控系统配置管理类
 * 负责加载和管理监控系统的各种配置参数
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
    
    private MonitorConfig() {
        properties = new Properties();
        loadConfiguration();
        
        // 初始化网络配置
        connectTimeout = getIntProperty("monitor.network.connect.timeout", 10000);
        readTimeout = getIntProperty("monitor.network.read.timeout", 30000);
        maxRetries = getIntProperty("monitor.network.max.retries", 3);
        retryBaseDelay = getLongProperty("monitor.network.retry.base.delay", 1000L);
        retryMaxDelay = getLongProperty("monitor.network.retry.max.delay", 10000L);
        
        // 初始化健康检查配置
        maxConsecutiveFailures = getIntProperty("monitor.health.max.consecutive.failures", 3);
        healthCheckInterval = getLongProperty("monitor.health.check.interval", 300000L);
        failureRateThreshold = getDoubleProperty("monitor.health.failure.rate.threshold", 0.5);
        failureCooldownBase = getIntProperty("monitor.health.failure.cooldown.base", 5);
        failureCooldownMax = getIntProperty("monitor.health.failure.cooldown.max", 60);
        failureCooldown = getLongProperty("monitor.health.failure.cooldown", 300000L);
        
        // 初始化缓存配置
        cacheExpireTime = getLongProperty("monitor.cache.expire.time", 30000L);
        cacheCleanupInterval = getLongProperty("monitor.cache.cleanup.interval", 300000L);
        healthStatsRetention = getLongProperty("monitor.health.stats.retention", 86400000L);
        
        // 初始化监控配置
        statusCheckInterval = getLongProperty("monitor.status.check.interval", 60000L);
        verboseLogging = getBooleanProperty("monitor.logging.verbose", true);
        performanceEnabled = getBooleanProperty("monitor.performance.enabled", true);
        healthWarningEnabled = getBooleanProperty("monitor.health.warning.enabled", true);
        
        // 初始化通知配置
        showHealthInNotification = getBooleanProperty("monitor.notification.show.health", true);
        systemHealthWarning = getBooleanProperty("monitor.notification.system.health.warning", true);
        healthWarningInterval = getLongProperty("monitor.notification.health.warning.interval", 1800000L);
        
        // 初始化高级配置
        adaptiveIntervalEnabled = getBooleanProperty("monitor.adaptive.interval.enabled", false);
        adaptiveIntervalMin = getLongProperty("monitor.adaptive.interval.min", 30000L);
        adaptiveIntervalMax = getLongProperty("monitor.adaptive.interval.max", 300000L);
        batchQueryEnabled = getBooleanProperty("monitor.batch.query.enabled", false);
        batchQuerySize = getIntProperty("monitor.batch.query.size", 5);
        asyncProcessingEnabled = getBooleanProperty("monitor.async.processing.enabled", false);
        asyncThreadPoolSize = getIntProperty("monitor.async.thread.pool.size", 3);
        
        logConfigurationSummary();
    }
    
    /**
     * 获取配置实例（单例模式）
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
                Newboy.INSTANCE.getLogger().info("监控配置文件加载成功");
            } else {
                Newboy.INSTANCE.getLogger().warning("监控配置文件未找到，使用默认配置");
            }
        } catch (IOException e) {
            Newboy.INSTANCE.getLogger().warning("加载监控配置文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 记录配置摘要
     */
    private void logConfigurationSummary() {
        if (verboseLogging) {
            Newboy.INSTANCE.getLogger().info(
                String.format("监控配置加载完成 - 网络超时: %d/%dms, 重试: %d次, 健康检查: %dmin, 缓存: %ds",
                    connectTimeout, readTimeout, maxRetries, 
                    healthCheckInterval / 60000, cacheExpireTime / 1000));
        }
    }
    
    // 配置获取辅助方法
    private int getIntProperty(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            Newboy.INSTANCE.getLogger().warning(
                String.format("配置项 %s 格式错误，使用默认值: %d", key, defaultValue));
            return defaultValue;
        }
    }
    
    private long getLongProperty(String key, long defaultValue) {
        try {
            return Long.parseLong(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            Newboy.INSTANCE.getLogger().warning(
                String.format("配置项 %s 格式错误，使用默认值: %d", key, defaultValue));
            return defaultValue;
        }
    }
    
    private double getDoubleProperty(String key, double defaultValue) {
        try {
            return Double.parseDouble(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            Newboy.INSTANCE.getLogger().warning(
                String.format("配置项 %s 格式错误，使用默认值: %.2f", key, defaultValue));
            return defaultValue;
        }
    }
    
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key, String.valueOf(defaultValue));
        return Boolean.parseBoolean(value);
    }
    
    // Getter 方法
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
    
    public long getCacheExpireTime() { return cacheExpireTime; }
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
    public int getBatchQuerySize() { return batchQuerySize; }
    public boolean isAsyncProcessingEnabled() { return asyncProcessingEnabled; }
    public int getAsyncThreadPoolSize() { return asyncThreadPoolSize; }
    
    /**
     * 重新加载配置
     */
    public void reload() {
        loadConfiguration();
        Newboy.INSTANCE.getLogger().info("监控配置已重新加载");
    }
    
    /**
     * 获取配置摘要
     */
    public String getConfigSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("⚙️ 监控系统配置摘要\n");
        summary.append("━━━━━━━━━━━━━━━━━━━━\n");
        summary.append(String.format("🌐 网络超时: %d/%d ms\n", connectTimeout, readTimeout));
        summary.append(String.format("🔄 重试配置: %d次, %d-%dms\n", maxRetries, retryBaseDelay, retryMaxDelay));
        summary.append(String.format("🏥 健康检查: %d分钟间隔\n", healthCheckInterval / 60000));
        summary.append(String.format("💾 缓存时间: %d秒\n", cacheExpireTime / 1000));
        summary.append(String.format("📊 状态检查: %d秒间隔\n", statusCheckInterval / 1000));
        summary.append(String.format("🔧 高级功能: 自适应(%s), 批量(%s), 异步(%s)\n", 
            adaptiveIntervalEnabled ? "开" : "关",
            batchQueryEnabled ? "开" : "关",
            asyncProcessingEnabled ? "开" : "关"));
        summary.append("━━━━━━━━━━━━━━━━━━━━");
        return summary.toString();
    }
}