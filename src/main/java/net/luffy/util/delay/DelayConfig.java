package net.luffy.util.delay;

import net.luffy.util.UnifiedLogger;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 延迟配置管理器
 * 集中管理所有延迟与重试参数，支持自适应动态调整
 * 基于系统负载、网络质量和消息频率进行智能优化
 */
public class DelayConfig {
    private static final UnifiedLogger logger = UnifiedLogger.getInstance();
    private static final AtomicReference<DelayConfig> INSTANCE = new AtomicReference<>();
    
    // 系统监控相关
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ScheduledExecutorService adaptiveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DelayConfig-Adaptive");
        t.setDaemon(true);
        return t;
    });
    
    // 消息频率监控
    private final Map<String, AtomicLong> messageFrequency = new ConcurrentHashMap<>();
    private final AtomicLong lastFrequencyCheck = new AtomicLong(System.currentTimeMillis());
    private volatile double currentSystemLoad = 0.0;
    private volatile double currentMemoryUsage = 0.0;
    
    // 基础延迟配置 - 全部设置为0，实现立即执行
    private volatile long baseTextMessageInterval = 0; // 设置为0，立即执行
    private volatile long baseMediaMessageInterval = 0; // 设置为0，立即执行
    private volatile long messageGroupDelay = 0; // 设置为0，立即执行
    
    // 当前动态调整后的实际间隔 - 全部设置为0
    private volatile long currentTextInterval = 0;
    private volatile long currentMediaInterval = 0;
    
    // 活跃度调整因子 - 已废弃，不再使用
    private volatile double activeMultiplier = 1.0; // 已废弃：设置为1.0，不进行调整
    private volatile double inactiveMultiplier = 1.0; // 已废弃：设置为1.0，不进行调整
    
    // 延迟范围限制 - 全部设置为0
    private volatile long minInterval = 0; // 设置为0，允许无延迟
    private volatile long maxInterval = 0; // 设置为0，无最大延迟限制
    
    // 重试配置 - 设置为0延迟，立即重试
    private volatile int maxRetries = 1;                   // 最大重试次数设置为1
    private volatile long retryBaseDelay = 0;              // 重试基础延迟设置为0ms，立即重试
    private volatile long retryMaxDelay = 0;               // 重试最大延迟设置为0ms，立即重试
    private volatile double retryBackoffMultiplier = 1.0;  // 重试退避倍率设置为1.0，无退避
    
    // 自适应调整参数 - 完全禁用自适应调整
    private volatile boolean enableAdaptiveAdjustment = false; // 禁用自适应调整
    private volatile double cpuThresholdLow = 0.15;         // CPU低负载阈值（更激进）
    private volatile double cpuThresholdHigh = 0.8;          // CPU高负载阈值（更宽松）
    private volatile double memoryThresholdHigh = 0.85;      // 内存高使用率阈值（更宽松）
    private volatile double highLoadMultiplier = 1.0;       // 高负载时延迟倍率设置为1.0，无影响
    private volatile double lowLoadMultiplier = 1.0;        // 低负载时延迟倍率设置为1.0，无影响
    
    // 网络延迟监控
    private volatile long lastNetworkCheckTime = 0;
    private volatile double averageNetworkLatency = 0.0;
    
    // 智能调整历史记录
    private final Map<String, Double> adjustmentHistory = new ConcurrentHashMap<>();
    private volatile long lastAdjustmentTime = 0;
    private static final long MIN_ADJUSTMENT_INTERVAL = 5000; // 最小调整间隔5秒
    
    // 日志输出频率控制 - 更严格的控制以减少控制台噪音
    private volatile long lastLogOutputTime = 0;
    private volatile double lastLoggedAdjustmentFactor = 1.0;
    private volatile long lastLoggedTextInterval = 300;
    private volatile long lastLoggedMediaInterval = 800;
    private static final long LOG_OUTPUT_INTERVAL = 900000; // 15分钟（更长间隔）
    private static final double FACTOR_CHANGE_THRESHOLD = 0.5; // 调整因子变化阈值（更大变化才输出）
    private static final double INTERVAL_CHANGE_THRESHOLD = 0.3; // 延迟变化阈值（30%）
    
    // 详细日志开关 - 默认关闭以减少噪音
    private volatile boolean enableVerboseLogging = false; // 详细日志开关，默认关闭
    
    // 消息频率控制
    private volatile long frequencyCheckInterval = 10000;   // 频率检查间隔（毫秒）
    private volatile int highFrequencyThreshold = 50;       // 高频消息阈值（每10秒）
    private volatile double highFrequencyMultiplier = 2.0;  // 高频时延迟倍率
    
    // 特性开关 - 简化配置
    private volatile boolean enableMetricsCollection = true; // 启用指标收集
    
    private DelayConfig() {
        initializeAdaptiveMonitoring();
        logger.info("DelayConfig", "延迟配置初始化完成，启用自适应调整");
    }
    
    public static DelayConfig getInstance() {
        DelayConfig instance = INSTANCE.get();
        if (instance == null) {
            synchronized (DelayConfig.class) {
                instance = INSTANCE.get();
                if (instance == null) {
                    instance = new DelayConfig();
                    INSTANCE.set(instance);
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化自适应监控
     */
    private void initializeAdaptiveMonitoring() {
        if (enableAdaptiveAdjustment) {
            // 启动系统负载监控任务
            adaptiveScheduler.scheduleAtFixedRate(this::updateSystemMetrics, 0, 5, TimeUnit.SECONDS);
            // 启动延迟调整任务
            adaptiveScheduler.scheduleAtFixedRate(this::adjustDelayBasedOnLoad, 10, 10, TimeUnit.SECONDS);
            // 启动消息频率检查任务
            adaptiveScheduler.scheduleAtFixedRate(this::checkMessageFrequency, 0, frequencyCheckInterval, TimeUnit.MILLISECONDS);
            logger.info("DelayConfig", "自适应监控已启动");
        }
    }
    
    /**
     * 更新系统指标
     */
    private void updateSystemMetrics() {
        try {
            // 获取CPU使用率
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
                currentSystemLoad = sunOsBean.getProcessCpuLoad();
                if (currentSystemLoad < 0) {
                    currentSystemLoad = sunOsBean.getSystemCpuLoad();
                }
            } else {
                currentSystemLoad = osBean.getSystemLoadAverage() / osBean.getAvailableProcessors();
            }
            
            // 获取内存使用率
            long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
            long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
            currentMemoryUsage = maxMemory > 0 ? (double) usedMemory / maxMemory : 0.0;
            
        } catch (Exception e) {
            logger.warn("DelayConfig", "获取系统指标失败: " + e.getMessage());
        }
    }
    
    /**
     * 基于系统负载调整延迟 - 增强版智能调整
     */
    private void adjustDelayBasedOnLoad() {
        try {
            long currentTime = System.currentTimeMillis();
            
            // 防止过于频繁的调整
            if (currentTime - lastAdjustmentTime < MIN_ADJUSTMENT_INTERVAL) {
                return;
            }
            
            // 计算综合调整因子
            double adjustmentFactor = calculateEnhancedAdjustmentFactor();
            
            // 智能调整文本消息间隔
            long newTextInterval = Math.round(baseTextMessageInterval * adjustmentFactor);
            currentTextInterval = Math.max(minInterval, Math.min(maxInterval, newTextInterval));
            
            // 智能调整媒体消息间隔
            long newMediaInterval = Math.round(baseMediaMessageInterval * adjustmentFactor);
            currentMediaInterval = Math.max(minInterval, Math.min(maxInterval, newMediaInterval));
            
            // 动态调整重试参数
            adjustRetryParameters(currentSystemLoad);
            
            // 记录调整历史
            adjustmentHistory.put("factor_" + currentTime, adjustmentFactor);
            adjustmentHistory.put("cpu_" + currentTime, currentSystemLoad);
            adjustmentHistory.put("memory_" + currentTime, currentMemoryUsage);
            
            // 清理过期历史记录（保留最近10分钟）
            cleanupAdjustmentHistory(currentTime);
            
            lastAdjustmentTime = currentTime;
            
            // 智能日志输出控制 - 只有在启用详细日志时才输出
            if (enableVerboseLogging && enableMetricsCollection && shouldOutputLog(adjustmentFactor, currentTime)) {
                logger.info("DelayConfig", String.format(
                    "智能延迟调整: 因子=%.2f, 文本=%dms, 媒体=%dms, CPU=%.2f, 内存=%.2f, 延迟=%.1fms", 
                    adjustmentFactor, currentTextInterval, currentMediaInterval, 
                    currentSystemLoad, currentMemoryUsage, averageNetworkLatency));
                
                // 更新日志输出记录
                lastLogOutputTime = currentTime;
                lastLoggedAdjustmentFactor = adjustmentFactor;
                lastLoggedTextInterval = currentTextInterval;
                lastLoggedMediaInterval = currentMediaInterval;
            }
        } catch (Exception e) {
            logger.warn("DelayConfig", "智能延迟调整失败: " + e.getMessage());
        }
    }
    
    /**
     * 计算增强版调整因子 - 多维度智能评估
     */
    private double calculateEnhancedAdjustmentFactor() {
        double factor = 1.0;
        
        // 1. 基于CPU负载的渐进式调整
        if (currentSystemLoad > cpuThresholdHigh) {
            // 高负载时渐进增加延迟
            double cpuOverload = (currentSystemLoad - cpuThresholdHigh) / (1.0 - cpuThresholdHigh);
            factor *= (1.0 + cpuOverload * (highLoadMultiplier - 1.0));
        } else if (currentSystemLoad < cpuThresholdLow) {
            // 低负载时渐进减少延迟
            double cpuUnderload = (cpuThresholdLow - currentSystemLoad) / cpuThresholdLow;
            factor *= (1.0 - cpuUnderload * (1.0 - lowLoadMultiplier));
        }
        
        // 2. 基于内存使用率的智能调整
        if (currentMemoryUsage > memoryThresholdHigh) {
            double memoryPressure = (currentMemoryUsage - memoryThresholdHigh) / (1.0 - memoryThresholdHigh);
            factor *= (1.0 + memoryPressure * 0.8); // 内存压力影响相对较小
        }
        
        // 3. 基于历史调整效果的学习调整
        double historyFactor = calculateHistoryBasedFactor();
        factor *= historyFactor;
        
        // 4. 限制调整范围，避免极端值
        factor = Math.max(0.2, Math.min(1.5, factor));
        
        return factor;
    }
    
    /**
     * 动态调整重试参数
     * @param systemLoad 系统负载
     */
    public void adjustRetryParameters(double systemLoad) {
        double systemMultiplier = 1.0;
        
        // 基于系统负载调整
        if (systemLoad > 0.8) {
            systemMultiplier = 1.5;
        } else if (systemLoad > 0.6) {
            systemMultiplier = 1.2;
        }
        
        // 调整重试参数
        this.maxRetries = Math.max(1, Math.min(5, (int)(1 * systemMultiplier)));
        this.retryBaseDelay = Math.max(0, Math.min(1000, (long)(0 * systemMultiplier)));
        this.retryMaxDelay = Math.max(0, Math.min(5000, (long)(0 * systemMultiplier)));
    }
    
    /**
     * 基于历史调整效果计算调整因子
     */
    private double calculateHistoryBasedFactor() {
        try {
            if (adjustmentHistory.isEmpty()) {
                return 1.0;
            }
            
            // 分析最近的调整趋势
            long currentTime = System.currentTimeMillis();
            double recentFactorSum = 0.0;
            int recentCount = 0;
            
            for (Map.Entry<String, Double> entry : adjustmentHistory.entrySet()) {
                if (entry.getKey().startsWith("factor_")) {
                    long timestamp = Long.parseLong(entry.getKey().substring(7));
                    if (currentTime - timestamp < 300000) { // 最近5分钟
                        recentFactorSum += entry.getValue();
                        recentCount++;
                    }
                }
            }
            
            if (recentCount > 0) {
                double averageFactor = recentFactorSum / recentCount;
                // 如果最近调整因子偏高，适当降低；如果偏低，适当提高
                if (averageFactor > 1.5) {
                    return 0.9; // 降低调整幅度
                } else if (averageFactor < 0.7) {
                    return 1.1; // 增加调整幅度
                }
            }
            
            return 1.0;
        } catch (Exception e) {
            logger.warn("DelayConfig", "历史因子计算失败: " + e.getMessage());
            return 1.0;
        }
    }
    
    /**
     * 判断是否应该输出日志 - 更严格的控制条件
     */
    private boolean shouldOutputLog(double adjustmentFactor, long currentTime) {
        // 时间间隔检查 - 必须满足15分钟间隔
        if (currentTime - lastLogOutputTime < LOG_OUTPUT_INTERVAL) {
            // 如果时间间隔不够，检查是否有非常显著的变化
            double factorChange = Math.abs(adjustmentFactor - lastLoggedAdjustmentFactor);
            double textIntervalChange = lastLoggedTextInterval > 0 ? 
                Math.abs((double)(currentTextInterval - lastLoggedTextInterval) / lastLoggedTextInterval) : 0;
            double mediaIntervalChange = lastLoggedMediaInterval > 0 ? 
                Math.abs((double)(currentMediaInterval - lastLoggedMediaInterval) / lastLoggedMediaInterval) : 0;
            
            // 只有在非常显著变化时才输出日志
            return factorChange > FACTOR_CHANGE_THRESHOLD || 
                   textIntervalChange > INTERVAL_CHANGE_THRESHOLD || 
                   mediaIntervalChange > INTERVAL_CHANGE_THRESHOLD;
        }
        
        // 时间间隔足够，且有明显调整时输出日志
        boolean shouldLog = Math.abs(adjustmentFactor - 1.0) > 0.1; // 提高阈值到10%
        
        // 更新记录的值
        if (shouldLog) {
            lastLogOutputTime = currentTime;
            lastLoggedAdjustmentFactor = adjustmentFactor;
            lastLoggedTextInterval = currentTextInterval;
            lastLoggedMediaInterval = currentMediaInterval;
        }
        
        return shouldLog;
    }
    
    /**
     * 清理过期的调整历史记录
     */
    private void cleanupAdjustmentHistory(long currentTime) {
        try {
            adjustmentHistory.entrySet().removeIf(entry -> {
                try {
                    String key = entry.getKey();
                    if (key.contains("_")) {
                        long timestamp = Long.parseLong(key.substring(key.lastIndexOf("_") + 1));
                        return currentTime - timestamp > 600000; // 清理10分钟前的记录
                    }
                    return false;
                } catch (Exception e) {
                    return true; // 清理无效记录
                }
            });
        } catch (Exception e) {
            logger.warn("DelayConfig", "清理历史记录失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查消息频率并调整
     */
    private void checkMessageFrequency() {
        try {
            long currentTime = System.currentTimeMillis();
            long lastCheck = lastFrequencyCheck.get();
            
            if (currentTime - lastCheck >= frequencyCheckInterval) {
                // 计算总消息数
                int totalMessages = messageFrequency.values().stream()
                    .mapToInt(count -> count.intValue())
                    .sum();
                
                // 如果消息频率过高，增加延迟
                if (totalMessages > highFrequencyThreshold) {
                    double frequencyFactor = Math.min(highFrequencyMultiplier, 1.0 + (totalMessages - highFrequencyThreshold) * 0.02);
                    currentTextInterval = Math.round(currentTextInterval * frequencyFactor);
                    currentMediaInterval = Math.round(currentMediaInterval * frequencyFactor);
                    
                    if (enableMetricsCollection) {
                        logger.info("DelayConfig", String.format("检测到高频消息(%d条)，调整延迟因子: %.2f", totalMessages, frequencyFactor));
                    }
                }
                
                // 重置计数器
                messageFrequency.clear();
                lastFrequencyCheck.set(currentTime);
            }
        } catch (Exception e) {
            logger.warn("DelayConfig", "消息频率检查失败: " + e.getMessage());
        }
    }
    
    /**
     * 记录消息发送（用于频率监控）
     */
    public void recordMessageSent(String messageType) {
        if (enableAdaptiveAdjustment) {
            messageFrequency.computeIfAbsent(messageType, k -> new AtomicLong(0)).incrementAndGet();
        }
    }
    
    /**
     * 获取当前文本消息间隔（动态调整后的值）
     */
    public long getCurrentTextMessageInterval() {
        return currentTextInterval;
    }
    
    /**
     * 获取当前媒体消息间隔（动态调整后的值）
     */
    public long getCurrentMediaMessageInterval() {
        return currentMediaInterval;
    }
    
    /**
     * 获取当前系统负载
     */
    public double getCurrentSystemLoad() {
        return currentSystemLoad;
    }
    
    /**
     * 获取当前内存使用率
     */
    public double getCurrentMemoryUsage() {
        return currentMemoryUsage;
    }
    
    /**
     * 重新初始化配置（移除热更新功能）
     */
    public void reloadConfig() {
        logger.info("DelayConfig", "重新初始化延迟配置");
        // 重置为默认最佳性能参数
        resetToOptimalDefaults();
        logger.info("DelayConfig", "延迟配置重新初始化完成");
    }
    
    /**
      * 重置为最佳性能默认值
      */
     private void resetToOptimalDefaults() {
         baseTextMessageInterval = 50;
         baseMediaMessageInterval = 150;
         messageGroupDelay = 30;
         currentTextInterval = 50;
         currentMediaInterval = 150;
         activeMultiplier = 0.8;
         inactiveMultiplier = 1.1;
         minInterval = 20;
         maxInterval = 2000;
         maxRetries = 2;
         retryBaseDelay = 500;
         retryMaxDelay = 3000;
         retryBackoffMultiplier = 1.5;
     }
    
    /**
     * 启用或禁用详细日志输出
     * @param enabled true启用详细日志，false禁用（默认）
     */
    public void setVerboseLogging(boolean enabled) {
        this.enableVerboseLogging = enabled;
        if (enabled) {
            logger.info("DelayConfig", "详细日志已启用，将输出智能延迟调整信息");
        } else {
            logger.info("DelayConfig", "详细日志已禁用，智能延迟调整信息将不再输出");
        }
    }
    
    /**
     * 获取详细日志状态
     * @return true表示启用详细日志，false表示禁用
     */
    public boolean isVerboseLoggingEnabled() {
        return enableVerboseLogging;
    }
    
    /**
     * 关闭自适应监控（用于应用关闭时清理资源）
     */
    public void shutdown() {
        if (adaptiveScheduler != null && !adaptiveScheduler.isShutdown()) {
            adaptiveScheduler.shutdown();
            try {
                if (!adaptiveScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    adaptiveScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                adaptiveScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("DelayConfig", "自适应监控已关闭");
        }
    }
    
    // Getter方法 - 返回动态调整后的值
    public long getTextMessageInterval() {
        return currentTextInterval;
    }
    
    public long getMediaMessageInterval() {
        return currentMediaInterval;
    }
    
    public long getMessageGroupDelay() {
        return messageGroupDelay;
    }
    
    /**
     * @deprecated 活跃度调整功能已被移除，此方法将始终返回1.0
     */
    @Deprecated
    public double getActiveMultiplier() {
        return 1.0; // 固定返回1.0，不再进行活跃度调整
    }

    /**
     * @deprecated 活跃度调整功能已被移除，此方法将始终返回1.0
     */
    @Deprecated
    public double getInactiveMultiplier() {
        return 1.0; // 固定返回1.0，不再进行活跃度调整
    }
    
    public long getMinInterval() {
        return minInterval;
    }
    
    public long getMaxInterval() {
        return maxInterval;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public long getRetryBaseDelay() {
        return retryBaseDelay;
    }
    
    public long getRetryMaxDelay() {
        return retryMaxDelay;
    }
    
    public double getRetryBackoffMultiplier() {
        return retryBackoffMultiplier;
    }
    
    // 特性开关getter方法
    public boolean isAdaptiveAdjustmentEnabled() {
        return enableAdaptiveAdjustment;
    }
    
    public boolean isMetricsCollectionEnabled() {
        return enableMetricsCollection;
    }
    
    // 特性开关setter方法
    public void setMetricsCollectionEnabled(boolean enabled) {
        this.enableMetricsCollection = enabled;
    }
    
    public void setAdaptiveAdjustmentEnabled(boolean enabled) {
        this.enableAdaptiveAdjustment = enabled;
        if (enabled && adaptiveScheduler.isShutdown()) {
            initializeAdaptiveMonitoring();
        }
    }
    

    
    // 负载均衡配置
    public int getLoadBalanceThreshold() { return 100; } // 负载均衡阈值
    public double getHighLoadMultiplier() { return highLoadMultiplier; }
    
    // 度量报告配置 - 硬编码最佳性能参数
    public boolean isPeriodicReportingEnabled() { return true; }
    public int getReportIntervalMinutes() { return 5; } // 5分钟报告间隔
    public int getSnapshotIntervalMinutes() { return 1; } // 1分钟快照间隔
    public int getMaxHistoricalSnapshots() { return 60; } // 保留60个历史快照
    public boolean isAutoSaveReportsEnabled() { return false; } // 关闭自动保存以提升性能
    

    
    @Override
    public String toString() {
        return String.format(
            "DelayConfig{" +
            "currentTextInterval=%d, currentMediaInterval=%d, groupDelay=%d, " +
            "minInterval=%d, maxInterval=%d, " +
            "maxRetries=%d, retryBaseDelay=%d, retryMaxDelay=%d, retryBackoffMultiplier=%.2f, " +
            "systemLoad=%.2f, memoryUsage=%.2f, adaptiveEnabled=%s" +
            "}",
            currentTextInterval, currentMediaInterval, messageGroupDelay,
            minInterval, maxInterval,
            maxRetries, retryBaseDelay, retryMaxDelay, retryBackoffMultiplier,
            currentSystemLoad, currentMemoryUsage, enableAdaptiveAdjustment
        );
    }
}