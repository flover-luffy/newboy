package net.luffy.util;

import net.luffy.util.NetworkQualityMonitor.NetworkQuality;
import net.luffy.util.NetworkQualityMonitor.NetworkQualityResult;
import net.luffy.util.delay.DelayConfig;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.luffy.util.UnifiedSchedulerManager;

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
    
    // 重试统计和优化参数
    private final AtomicInteger totalRetries = new AtomicInteger(0);
    private final AtomicInteger successfulRetries = new AtomicInteger(0);
    private final AtomicLong totalRetryTime = new AtomicLong(0);
    private final Map<String, RetryStats> endpointStats = new ConcurrentHashMap<>();
    
    // 智能重试参数 - 优化为更快的失败策略
    private static final double MIN_SUCCESS_RATE = 0.4; // 提高最低成功率阈值
    private static final int STATS_WINDOW_SIZE = 50; // 减少统计窗口大小以更快响应
    private static final double EXPONENTIAL_BASE = 1.5; // 使用更平滑的指数退避基数
    private static final long MAX_RETRY_DELAY = 2000; // 大幅减少最大重试延迟(ms)
    private static final long MIN_RETRY_DELAY = 100; // 减少最小重试延迟(ms)
    
    // 超时调整配置已移至DelayConfig统一管理
    
    private DynamicTimeoutManager() {
        this.networkMonitor = new NetworkQualityMonitor();
        this.config = MonitorConfig.getInstance();
        this.scheduler = UnifiedSchedulerManager.getInstance().getScheduledExecutor();
        
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
     * 启动网络质量监控 - 已禁用
     */
    private void startQualityMonitoring() {
        // 禁用网络质量监控，不再启动定期检查以减少系统开销
        // scheduler.scheduleWithFixedDelay(this::checkNetworkQuality, 
        //         0, QUALITY_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 检查网络质量 - 已禁用
     */
    private void checkNetworkQuality() {
        // 禁用网络质量检查，不再进行实际检测以减少系统开销
        // 保持方法存在以避免编译错误，但不执行任何操作
    }
    
    /**
     * 安排快速检查（网络质量差时）- 已禁用
     */
    private void scheduleQuickCheck() {
        // 禁用快速检查，不再安排额外的网络质量检测
    }
    
    /**
     * 获取当前网络质量 - 已禁用，始终返回优秀网络质量
     */
    public NetworkQuality getCurrentQuality() {
        // 禁用网络质量检测，始终返回优秀网络质量以减少系统开销
        return NetworkQuality.EXCELLENT;
    }
    
    /**
     * 获取动态调整后的连接超时时间 - 已禁用网络质量调整
     * @param baseTimeout 基础超时时间
     * @return 调整后的超时时间
     */
    public int getDynamicConnectTimeout(int baseTimeout) {
        // 禁用网络质量调整，直接返回基础超时时间
        return Math.max(500, Math.min(10000, baseTimeout));
    }
    
    /**
     * 获取动态调整后的读取超时时间 - 已禁用网络质量调整
     * @param baseTimeout 基础超时时间
     * @return 调整后的超时时间
     */
    public int getDynamicReadTimeout(int baseTimeout) {
        // 禁用网络质量调整，直接返回基础超时时间
        return Math.max(1000, Math.min(15000, baseTimeout));
    }
    
    /**
     * 获取动态调整后的重试次数 - 已禁用网络质量调整
     * @param baseRetries 基础重试次数
     * @param endpoint 端点标识（用于统计）
     * @return 调整后的重试次数
     */
    public int getDynamicRetries(int baseRetries, String endpoint) {
        // 禁用网络质量调整，直接返回基础重试次数
        return Math.max(1, baseRetries);
    }
    
    /**
     * 兼容性方法：获取动态调整后的重试次数
     */
    public int getDynamicRetries(int baseRetries) {
        return getDynamicRetries(baseRetries, "default");
    }
    
    /**
     * 根据网络质量和端点统计动态调整重试次数
     */
    private int getQualityAdjustedRetries(int baseRetries, NetworkQuality quality) {
        // 基于网络质量的基础调整
        int adjustedRetries = baseRetries;
        
        switch (quality) {
            case EXCELLENT:
                adjustedRetries = Math.max(1, baseRetries - 1); // 网络好时减少重试
                break;
            case GOOD:
                adjustedRetries = baseRetries; // 保持默认
                break;
            case FAIR:
                adjustedRetries = baseRetries + 1; // 网络一般时增加1次重试
                break;
            case POOR:
                adjustedRetries = baseRetries + 2; // 网络差时增加2次重试
                break;
            case VERY_POOR:
                adjustedRetries = baseRetries + 3; // 网络很差时增加3次重试
                break;
            default:
                adjustedRetries = baseRetries;
        }
        
        // 确保重试次数在合理范围内 (1-8次)
        return Math.max(1, Math.min(8, adjustedRetries));
    }
    
    /**
     * 获取动态调整后的重试延迟 - 优化版本
     * @param baseDelay 基础延迟时间
     * @param retryAttempt 当前重试次数（从1开始）
     * @param endpoint 端点标识
     * @return 调整后的延迟时间
     */
    public long getDynamicRetryDelay(long baseDelay, int retryAttempt, String endpoint) {
        // 使用指数退避策略，但限制最大延迟
        long delay = Math.min((long)(MIN_RETRY_DELAY * Math.pow(1.5, retryAttempt)), MAX_RETRY_DELAY);
        
        // 根据网络质量调整延迟
        NetworkQuality quality = getCurrentQuality();
        switch (quality) {
            case EXCELLENT:
                return (long)(delay * 0.5); // 网络优秀时减少延迟
            case GOOD:
                return delay;
            case FAIR:
                return (long)(delay * 1.2);
            case POOR:
                return (long)(delay * 1.5);
            case VERY_POOR:
                return MAX_RETRY_DELAY; // 网络很差时使用最大延迟
            default:
                return delay;
        }
    }
    
    /**
     * 兼容性方法：获取动态调整后的重试延迟
     */
    public long getDynamicRetryDelay(long baseDelay) {
        return getDynamicRetryDelay(baseDelay, 1, "default");
    }
    
    /**
     * 根据网络质量调整延迟 - 优化版本
     */
    private long getQualityAdjustedDelay(long baseDelay, NetworkQuality quality, DelayConfig delayConfig) {
        switch (quality) {
            case EXCELLENT:
                return (long) (baseDelay * delayConfig.getExcellentNetworkMultiplier());
            case GOOD:
                return (long) (baseDelay * delayConfig.getGoodNetworkMultiplier());
            case FAIR:
                return (long) (baseDelay * delayConfig.getFairNetworkMultiplier());
            case POOR:
                return (long) (baseDelay * delayConfig.getPoorNetworkMultiplier());
            case VERY_POOR:
                return (long) (baseDelay * delayConfig.getVeryPoorNetworkMultiplier());
            default:
                return baseDelay;
        }
    }
    
    /**
     * 优化的网络质量延迟调整 - 在网络质量差时更激进
     */
    private long getOptimizedQualityAdjustedDelay(long baseDelay, NetworkQuality quality, DelayConfig delayConfig) {
        switch (quality) {
            case EXCELLENT:
                // 网络优秀时，使用更小的延迟
                return (long) (baseDelay * Math.min(delayConfig.getExcellentNetworkMultiplier(), 0.5));
            case GOOD:
                return (long) (baseDelay * Math.min(delayConfig.getGoodNetworkMultiplier(), 0.7));
            case FAIR:
                return (long) (baseDelay * Math.min(delayConfig.getFairNetworkMultiplier(), 1.0));
            case POOR:
                // 网络较差时，不增加太多延迟，准备快速失败
                return (long) (baseDelay * Math.min(delayConfig.getPoorNetworkMultiplier(), 1.2));
            case VERY_POOR:
                // 网络很差时，使用最小延迟，快速失败
                return Math.max(MIN_RETRY_DELAY, (long) (baseDelay * 0.8));
            default:
                return baseDelay;
        }
    }
    
    /**
     * 获取口袋48 API的动态超时配置
     */
    public Pocket48TimeoutConfig getPocket48DynamicConfig() {
        NetworkQuality quality = getCurrentQuality();
        String endpoint = "pocket48";
        
        // 如果启用了快速失败模式
        if (config.isPocket48FastFailEnabled()) {
            int connectTimeout = getDynamicConnectTimeout(config.getPocket48ConnectTimeout());
            int readTimeout = getDynamicReadTimeout(config.getPocket48ReadTimeout());
            int maxRetries = getDynamicRetries(config.getPocket48MaxRetries(), endpoint);
            long retryDelay = getDynamicRetryDelay(config.getPocket48RetryBaseDelay(), 1, endpoint);
            
            return new Pocket48TimeoutConfig(connectTimeout, readTimeout, maxRetries, retryDelay, true);
        } else {
            // 使用通用配置
            int connectTimeout = getDynamicConnectTimeout(config.getConnectTimeout());
            int readTimeout = getDynamicReadTimeout(config.getReadTimeout());
            int maxRetries = getDynamicRetries(config.getMaxRetries(), endpoint);
            long retryDelay = getDynamicRetryDelay(config.getRetryBaseDelay(), 1, endpoint);
            
            return new Pocket48TimeoutConfig(connectTimeout, readTimeout, maxRetries, retryDelay, false);
        }
    }
    
    /**
     * 根据网络质量获取超时倍数 - 原版本保留兼容性
     */
    private double getTimeoutMultiplier(NetworkQuality quality) {
        DelayConfig delayConfig = DelayConfig.getInstance();
        
        switch (quality) {
            case EXCELLENT:
                return delayConfig.getExcellentNetworkMultiplier();
            case GOOD:
                return delayConfig.getGoodNetworkMultiplier();
            case FAIR:
                return delayConfig.getFairNetworkMultiplier();
            case POOR:
                return delayConfig.getPoorNetworkMultiplier();
            case VERY_POOR:
                return delayConfig.getVeryPoorNetworkMultiplier();
            default:
                return delayConfig.getFairNetworkMultiplier();
        }
    }
    
    /**
     * 优化的超时倍数计算 - 更激进的超时策略
     */
    private double getOptimizedTimeoutMultiplier(NetworkQuality quality) {
        switch (quality) {
            case EXCELLENT:
                return 0.6; // 网络优秀时，使用更短的超时
            case GOOD:
                return 0.8; // 网络良好时，适度减少超时
            case FAIR:
                return 1.0; // 网络一般时，使用基础超时
            case POOR:
                return 1.1; // 网络差时，略微增加超时，但不要太多
            case VERY_POOR:
                return 0.8; // 网络很差时，使用较短超时，快速失败
            default:
                return 1.0;
        }
    }
    
    /**
     * 强制刷新网络质量检查 - 已禁用
     */
    public void forceQualityCheck() {
        // 禁用强制网络质量检查，不再执行检测以减少系统开销
    }
    
    /**
     * 记录重试统计
     * @param endpoint 端点标识
     * @param success 是否成功
     * @param retryCount 重试次数
     * @param totalTime 总耗时
     */
    public void recordRetryStats(String endpoint, boolean success, int retryCount, long totalTime) {
        totalRetries.addAndGet(retryCount);
        totalRetryTime.addAndGet(totalTime);
        
        if (success) {
            successfulRetries.incrementAndGet();
        }
        
        endpointStats.computeIfAbsent(endpoint, k -> new RetryStats()).recordAttempt(success, retryCount, totalTime);
    }
    
    /**
     * 获取重试统计信息
     */
    public RetryStatsSnapshot getRetryStats() {
        int total = totalRetries.get();
        int successful = successfulRetries.get();
        long avgTime = total > 0 ? totalRetryTime.get() / total : 0;
        double globalSuccessRate = total > 0 ? (double) successful / total : 1.0;
        
        return new RetryStatsSnapshot(total, successful, globalSuccessRate, avgTime, endpointStats.size());
    }
    
    /**
     * 获取端点特定的统计信息
     */
    public RetryStats getEndpointStats(String endpoint) {
        return endpointStats.get(endpoint);
    }
    
    /**
     * 清理过期的统计数据
     */
    public void cleanupStats() {
        long now = System.currentTimeMillis();
        endpointStats.entrySet().removeIf(entry -> {
            RetryStats stats = entry.getValue();
            return now - stats.getLastUpdateTime() > 300000; // 5分钟未更新则清理
        });
    }
    
    /**
     * 检查是否应该快速失败 - 优化版本
     * @return 如果网络质量很差且启用了快速失败，返回true
     */
    public boolean shouldFastFail() {
        NetworkQuality quality = getCurrentQuality();
        return config.isPocket48FastFailEnabled() && 
               (quality == NetworkQuality.VERY_POOR || quality == NetworkQuality.POOR);
    }
    
    /**
     * 智能判断是否应该快速失败（基于统计数据）- 优化版本
     */
    public boolean shouldFastFail(String endpoint) {
        NetworkQuality quality = getCurrentQuality();
        
        // 网络质量极差时，无论配置如何都建议快速失败
        if (quality == NetworkQuality.VERY_POOR) {
            return true;
        }
        
        // 检查配置的快速失败设置
        if (!shouldFastFail()) {
            // 即使配置未启用，但如果统计数据显示成功率极低，仍建议快速失败
            RetryStats stats = endpointStats.get(endpoint);
            if (stats != null && stats.getTotalAttempts() >= 3) {
                return stats.getSuccessRate() < 0.1; // 成功率低于10%时快速失败
            }
            return false;
        }
        
        RetryStats stats = endpointStats.get(endpoint);
        if (stats != null && stats.getTotalAttempts() >= 3) { // 降低统计阈值
            double successRate = stats.getSuccessRate();
            // 更激进的快速失败策略
            if (successRate < 0.15) { // 提高快速失败阈值
                return true;
            }
            // 如果平均重试时间过长，也建议快速失败
            if (stats.getAverageRetryTime() > 5000) { // 平均重试时间超过5秒
                return true;
            }
        }
        
        return true;
    }
    
    /**
     * 预测是否值得重试 - 新增智能预测功能
     * @param endpoint 端点标识
     * @param currentAttempt 当前重试次数
     * @return 是否值得继续重试
     */
    public boolean isRetryWorthwhile(String endpoint, int currentAttempt) {
        // 超过4次重试通常不值得继续
        if (currentAttempt > 4) {
            return false;
        }
        
        NetworkQuality quality = getCurrentQuality();
        
        // 网络质量极差时，超过2次重试就不值得了
        if (quality == NetworkQuality.VERY_POOR && currentAttempt > 2) {
            return false;
        }
        
        // 网络质量差时，超过3次重试就不值得了
        if (quality == NetworkQuality.POOR && currentAttempt > 3) {
            return false;
        }
        
        RetryStats stats = endpointStats.get(endpoint);
        if (stats != null && stats.getTotalAttempts() >= 3) {
            double successRate = stats.getSuccessRate();
            
            // 基于成功率和当前重试次数的智能判断
            if (successRate < 0.1 && currentAttempt > 1) {
                return false; // 成功率极低时，第一次重试后就放弃
            }
            
            if (successRate < 0.3 && currentAttempt > 2) {
                return false; // 成功率低时，第二次重试后就放弃
            }
            
            if (successRate < 0.5 && currentAttempt > 3) {
                return false; // 成功率一般时，第三次重试后就放弃
            }
        }
        
        return true;
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
        // scheduler现在由UnifiedSchedulerManager管理，不需要显式关闭
        
        if (networkMonitor != null) {
            networkMonitor.shutdown();
        }
        
        System.out.println("DynamicTimeoutManager已关闭，调度器由UnifiedSchedulerManager统一管理");
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
    
    /**
     * 重试统计类
     */
    public static class RetryStats {
        private final AtomicInteger totalAttempts = new AtomicInteger(0);
        private final AtomicInteger successfulAttempts = new AtomicInteger(0);
        private final AtomicLong totalRetryTime = new AtomicLong(0);
        private final AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());
        
        public void recordAttempt(boolean success, int retryCount, long totalTime) {
            totalAttempts.incrementAndGet();
            if (success) {
                successfulAttempts.incrementAndGet();
            }
            totalRetryTime.addAndGet(totalTime);
            lastUpdateTime.set(System.currentTimeMillis());
        }
        
        public double getSuccessRate() {
            int total = totalAttempts.get();
            return total > 0 ? (double) successfulAttempts.get() / total : 1.0;
        }
        
        public long getAverageRetryTime() {
            int total = totalAttempts.get();
            return total > 0 ? totalRetryTime.get() / total : 0;
        }
        
        public int getTotalAttempts() {
            return totalAttempts.get();
        }
        
        public int getSuccessfulAttempts() {
            return successfulAttempts.get();
        }
        
        public long getLastUpdateTime() {
            return lastUpdateTime.get();
        }
        
        @Override
        public String toString() {
            return String.format("RetryStats{attempts=%d, success=%d, rate=%.2f%%, avgTime=%dms}",
                    getTotalAttempts(), getSuccessfulAttempts(), getSuccessRate() * 100, getAverageRetryTime());
        }
    }
    
    /**
     * 重试统计快照
     */
    public static class RetryStatsSnapshot {
        private final int totalRetries;
        private final int successfulRetries;
        private final double globalSuccessRate;
        private final long averageRetryTime;
        private final int activeEndpoints;
        
        public RetryStatsSnapshot(int totalRetries, int successfulRetries, double globalSuccessRate, 
                                long averageRetryTime, int activeEndpoints) {
            this.totalRetries = totalRetries;
            this.successfulRetries = successfulRetries;
            this.globalSuccessRate = globalSuccessRate;
            this.averageRetryTime = averageRetryTime;
            this.activeEndpoints = activeEndpoints;
        }
        
        public int getTotalRetries() { return totalRetries; }
        public int getSuccessfulRetries() { return successfulRetries; }
        public double getGlobalSuccessRate() { return globalSuccessRate; }
        public long getAverageRetryTime() { return averageRetryTime; }
        public int getActiveEndpoints() { return activeEndpoints; }
        
        @Override
        public String toString() {
            return String.format("GlobalRetryStats{total=%d, success=%d, rate=%.2f%%, avgTime=%dms, endpoints=%d}",
                    totalRetries, successfulRetries, globalSuccessRate * 100, averageRetryTime, activeEndpoints);
        }
    }
}