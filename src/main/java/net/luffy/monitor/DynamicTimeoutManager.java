package net.luffy.monitor;

import net.luffy.util.MonitorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 动态超时管理器
 * 根据网络质量动态调整超时时间和重试策略
 */
public class DynamicTimeoutManager {
    private static final Logger logger = LoggerFactory.getLogger(DynamicTimeoutManager.class);
    
    private final NetworkQualityMonitor networkMonitor;
    private final MonitorConfig config;
    private final ScheduledExecutorService scheduler;
    
    // 当前网络质量状态
    private volatile NetworkQualityMonitor.NetworkQuality currentQuality;
    
    public DynamicTimeoutManager(NetworkQualityMonitor networkMonitor, MonitorConfig config) {
        this.networkMonitor = networkMonitor;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DynamicTimeoutManager-Monitor");
            t.setDaemon(true);
            return t;
        });
        this.currentQuality = NetworkQualityMonitor.NetworkQuality.GOOD; // 默认良好
        
        // 启动定期网络质量检查
        startNetworkQualityMonitoring();
    }
    
    /**
     * 启动网络质量监控
     */
    private void startNetworkQualityMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                NetworkQualityMonitor.NetworkQuality quality = networkMonitor.checkNetworkQuality();
                if (quality != currentQuality) {
                    logger.info("网络质量状态变化: {} -> {}", currentQuality, quality);
                    currentQuality = quality;
                }
            } catch (Exception e) {
                logger.warn("网络质量检查失败", e);
            }
        }, 0, 30, TimeUnit.SECONDS); // 每30秒检查一次
    }
    
    /**
     * 获取当前网络质量
     */
    public NetworkQualityMonitor.NetworkQuality getCurrentQuality() {
        return currentQuality;
    }
    
    /**
     * 根据网络质量动态计算超时时间
     */
    public int getDynamicTimeout(int baseTimeout) {
        switch (currentQuality) {
            case EXCELLENT:
                return (int) (baseTimeout * 0.8); // 优秀网络，减少20%超时时间
            case GOOD:
                return baseTimeout; // 良好网络，使用基础超时时间
            case FAIR:
                return (int) (baseTimeout * 1.5); // 一般网络，增加50%超时时间
            case POOR:
                return baseTimeout * 2; // 差网络，增加100%超时时间
            case VERY_POOR:
                return baseTimeout * 3; // 很差网络，增加200%超时时间
            default:
                return baseTimeout;
        }
    }
    
    /**
     * 根据网络质量动态计算重试次数
     */
    public int getDynamicRetries(int baseRetries) {
        switch (currentQuality) {
            case EXCELLENT:
            case GOOD:
                return baseRetries; // 好网络，使用基础重试次数
            case FAIR:
                return baseRetries + 1; // 一般网络，增加1次重试
            case POOR:
                return baseRetries + 2; // 差网络，增加2次重试
            case VERY_POOR:
                return baseRetries + 3; // 很差网络，增加3次重试
            default:
                return baseRetries;
        }
    }
    
    /**
     * 根据网络质量动态计算重试延迟
     */
    public long getDynamicDelay(long baseDelay) {
        switch (currentQuality) {
            case EXCELLENT:
                return (long) (baseDelay * 0.5); // 优秀网络，减少50%延迟
            case GOOD:
                return baseDelay; // 良好网络，使用基础延迟
            case FAIR:
                return (long) (baseDelay * 1.5); // 一般网络，增加50%延迟
            case POOR:
                return baseDelay * 2; // 差网络，增加100%延迟
            case VERY_POOR:
                return baseDelay * 3; // 很差网络，增加200%延迟
            default:
                return baseDelay;
        }
    }
    
    /**
     * 获取口袋48 API的动态超时配置
     */
    public TimeoutConfig getPocket48TimeoutConfig() {
        // 获取口袋48专用的基础配置
        int baseConnectTimeout = config.getPocket48ConnectTimeout();
        int baseReadTimeout = config.getPocket48ReadTimeout();
        int baseRetries = config.getPocket48MaxRetries();
        long baseDelay = config.getPocket48RetryBaseDelay();
        
        // 根据当前网络质量动态调整
        int dynamicConnectTimeout = getDynamicTimeout(baseConnectTimeout);
        int dynamicReadTimeout = getDynamicTimeout(baseReadTimeout);
        int dynamicRetries = getDynamicRetries(baseRetries);
        long dynamicDelay = getDynamicDelay(baseDelay);
        
        return new TimeoutConfig(dynamicConnectTimeout, dynamicReadTimeout, dynamicRetries, dynamicDelay);
    }
    
    /**
     * 超时配置类
     */
    public static class TimeoutConfig {
        private final int connectTimeout;
        private final int readTimeout;
        private final int maxRetries;
        private final long baseDelay;
        
        public TimeoutConfig(int connectTimeout, int readTimeout, int maxRetries, long baseDelay) {
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.maxRetries = maxRetries;
            this.baseDelay = baseDelay;
        }
        
        public int getConnectTimeout() {
            return connectTimeout;
        }
        
        public int getReadTimeout() {
            return readTimeout;
        }
        
        public int getMaxRetries() {
            return maxRetries;
        }
        
        public long getBaseDelay() {
            return baseDelay;
        }
        
        @Override
        public String toString() {
            return String.format("TimeoutConfig{connectTimeout=%d, readTimeout=%d, maxRetries=%d, baseDelay=%d}",
                    connectTimeout, readTimeout, maxRetries, baseDelay);
        }
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}