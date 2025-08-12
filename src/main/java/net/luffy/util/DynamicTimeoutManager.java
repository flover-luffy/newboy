package net.luffy.util;

import net.luffy.util.NetworkQualityMonitor.NetworkQuality;
import net.luffy.util.NetworkQualityMonitor.NetworkQualityResult;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 动态超时管理器
 * 根据网络质量自动调整超时时间和重试策略
 */
public class DynamicTimeoutManager {
    private static DynamicTimeoutManager instance;
    
    private final NetworkQualityMonitor networkMonitor;
    private final MonitorConfig config;
    private final ScheduledExecutorService scheduler;
    
    // 当前网络质量状态
    private final AtomicReference<NetworkQuality> currentQuality = new AtomicReference<>(NetworkQuality.GOOD);
    private final AtomicLong lastQualityCheck = new AtomicLong(0);
    
    // 质量检查间隔（毫秒）
    private static final long QUALITY_CHECK_INTERVAL = 30000; // 30秒检查一次
    private static final long FAST_CHECK_INTERVAL = 5000; // 快速检查间隔（网络质量差时）
    
    // 超时调整配置
    private static final double EXCELLENT_MULTIPLIER = 0.5; // 优秀网络：减少50%超时
    private static final double GOOD_MULTIPLIER = 0.8;      // 良好网络：减少20%超时
    private static final double FAIR_MULTIPLIER = 1.0;      // 一般网络：保持原超时
    private static final double POOR_MULTIPLIER = 1.5;      // 较差网络：增加50%超时
    private static final double VERY_POOR_MULTIPLIER = 2.0; // 很差网络：增加100%超时
    
    private DynamicTimeoutManager() {
        this.networkMonitor = new NetworkQualityMonitor();
        this.config = MonitorConfig.getInstance();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DynamicTimeoutManager-Monitor");
            t.setDaemon(true);
            return t;
        });
        
        // 启动定期网络质量检查
        startQualityMonitoring();
    }
    
    /**
     * 获取单例实例
     */
    public static DynamicTimeoutManager getInstance() {
        if (instance == null) {
            synchronized (DynamicTimeoutManager.class) {
                if (instance == null) {
                    instance = new DynamicTimeoutManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 启动网络质量监控
     */
    private void startQualityMonitoring() {
        scheduler.scheduleWithFixedDelay(this::checkNetworkQuality, 
                0, QUALITY_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 检查网络质量
     */
    private void checkNetworkQuality() {
        try {
            NetworkQualityResult result = networkMonitor.checkNetworkQuality();
            NetworkQuality newQuality = result.getQuality();
            NetworkQuality oldQuality = currentQuality.get();
            
            if (newQuality != oldQuality) {
                currentQuality.set(newQuality);
                lastQualityCheck.set(System.currentTimeMillis());
                
                // 如果网络质量变差，增加检查频率
                if (newQuality.ordinal() > NetworkQuality.FAIR.ordinal()) {
                    scheduleQuickCheck();
                }
            }
        } catch (Exception e) {
            // 检查失败，假设网络质量较差
            currentQuality.set(NetworkQuality.POOR);
        }
    }
    
    /**
     * 安排快速检查（网络质量差时）
     */
    private void scheduleQuickCheck() {
        scheduler.schedule(this::checkNetworkQuality, FAST_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 获取当前网络质量
     */
    public NetworkQuality getCurrentQuality() {
        // 如果超过检查间隔时间，触发一次检查
        long now = System.currentTimeMillis();
        if (now - lastQualityCheck.get() > QUALITY_CHECK_INTERVAL * 2) {
            scheduler.execute(this::checkNetworkQuality);
        }
        return currentQuality.get();
    }
    
    /**
     * 获取动态调整后的连接超时时间
     * @param baseTimeout 基础超时时间
     * @return 调整后的超时时间
     */
    public int getDynamicConnectTimeout(int baseTimeout) {
        return (int) (baseTimeout * getTimeoutMultiplier(getCurrentQuality()));
    }
    
    /**
     * 获取动态调整后的读取超时时间
     * @param baseTimeout 基础超时时间
     * @return 调整后的超时时间
     */
    public int getDynamicReadTimeout(int baseTimeout) {
        return (int) (baseTimeout * getTimeoutMultiplier(getCurrentQuality()));
    }
    
    /**
     * 获取动态调整后的重试次数
     * @param baseRetries 基础重试次数
     * @return 调整后的重试次数
     */
    public int getDynamicRetries(int baseRetries) {
        NetworkQuality quality = getCurrentQuality();
        switch (quality) {
            case EXCELLENT:
                return Math.max(1, baseRetries - 1); // 减少1次重试
            case GOOD:
                return baseRetries;
            case FAIR:
                return baseRetries;
            case POOR:
                return baseRetries + 1; // 增加1次重试
            case VERY_POOR:
                return baseRetries + 2; // 增加2次重试
            default:
                return baseRetries;
        }
    }
    
    /**
     * 获取动态调整后的重试延迟
     * @param baseDelay 基础延迟时间
     * @return 调整后的延迟时间
     */
    public long getDynamicRetryDelay(long baseDelay) {
        NetworkQuality quality = getCurrentQuality();
        switch (quality) {
            case EXCELLENT:
                return (long) (baseDelay * 0.5); // 减少50%延迟
            case GOOD:
                return (long) (baseDelay * 0.8); // 减少20%延迟
            case FAIR:
                return baseDelay;
            case POOR:
                return (long) (baseDelay * 1.5); // 增加50%延迟
            case VERY_POOR:
                return baseDelay * 2; // 增加100%延迟
            default:
                return baseDelay;
        }
    }
    
    /**
     * 获取口袋48 API的动态超时配置
     */
    public Pocket48TimeoutConfig getPocket48DynamicConfig() {
        NetworkQuality quality = getCurrentQuality();
        
        // 如果启用了快速失败模式
        if (config.isPocket48FastFailEnabled()) {
            int connectTimeout = getDynamicConnectTimeout(config.getPocket48ConnectTimeout());
            int readTimeout = getDynamicReadTimeout(config.getPocket48ReadTimeout());
            int maxRetries = getDynamicRetries(config.getPocket48MaxRetries());
            long retryDelay = getDynamicRetryDelay(config.getPocket48RetryBaseDelay());
            
            return new Pocket48TimeoutConfig(connectTimeout, readTimeout, maxRetries, retryDelay, true);
        } else {
            // 使用通用配置
            int connectTimeout = getDynamicConnectTimeout(config.getConnectTimeout());
            int readTimeout = getDynamicReadTimeout(config.getReadTimeout());
            int maxRetries = getDynamicRetries(config.getMaxRetries());
            long retryDelay = getDynamicRetryDelay(config.getRetryBaseDelay());
            
            return new Pocket48TimeoutConfig(connectTimeout, readTimeout, maxRetries, retryDelay, false);
        }
    }
    
    /**
     * 根据网络质量获取超时倍数
     */
    private double getTimeoutMultiplier(NetworkQuality quality) {
        switch (quality) {
            case EXCELLENT:
                return EXCELLENT_MULTIPLIER;
            case GOOD:
                return GOOD_MULTIPLIER;
            case FAIR:
                return FAIR_MULTIPLIER;
            case POOR:
                return POOR_MULTIPLIER;
            case VERY_POOR:
                return VERY_POOR_MULTIPLIER;
            default:
                return FAIR_MULTIPLIER;
        }
    }
    
    /**
     * 强制刷新网络质量检查
     */
    public void forceQualityCheck() {
        scheduler.execute(this::checkNetworkQuality);
    }
    
    /**
     * 检查是否应该快速失败
     * @return 如果网络质量很差且启用了快速失败，返回true
     */
    public boolean shouldFastFail() {
        NetworkQuality quality = getCurrentQuality();
        return config.isPocket48FastFailEnabled() && 
               (quality == NetworkQuality.VERY_POOR || quality == NetworkQuality.POOR);
    }
    
    /**
     * 获取网络质量描述
     */
    public String getQualityDescription() {
        NetworkQuality quality = getCurrentQuality();
        switch (quality) {
            case EXCELLENT:
                return "优秀";
            case GOOD:
                return "良好";
            case FAIR:
                return "一般";
            case POOR:
                return "较差";
            case VERY_POOR:
                return "很差";
            default:
                return "未知";
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
        
        if (networkMonitor != null) {
            networkMonitor.shutdown();
        }
    }
    
    /**
     * 口袋48超时配置类
     */
    public static class Pocket48TimeoutConfig {
        private final int connectTimeout;
        private final int readTimeout;
        private final int maxRetries;
        private final long retryDelay;
        private final boolean fastFailMode;
        
        public Pocket48TimeoutConfig(int connectTimeout, int readTimeout, 
                                   int maxRetries, long retryDelay, boolean fastFailMode) {
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.maxRetries = maxRetries;
            this.retryDelay = retryDelay;
            this.fastFailMode = fastFailMode;
        }
        
        public int getConnectTimeout() { return connectTimeout; }
        public int getReadTimeout() { return readTimeout; }
        public int getMaxRetries() { return maxRetries; }
        public long getRetryDelay() { return retryDelay; }
        public boolean isFastFailMode() { return fastFailMode; }
        
        @Override
        public String toString() {
            return String.format("Pocket48Config{connect=%dms, read=%dms, retries=%d, delay=%dms, fastFail=%s}",
                    connectTimeout, readTimeout, maxRetries, retryDelay, fastFailMode);
        }
    }
}