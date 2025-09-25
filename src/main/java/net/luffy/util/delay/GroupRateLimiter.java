package net.luffy.util.delay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 基于群组的智能速率限制器
 * 实现令牌桶算法，提供突发控制、平滑限流和自适应调整
 * 优化版本：提升性能参数，添加自适应功能和性能监控
 */
public class GroupRateLimiter {
    private static final Logger logger = LoggerFactory.getLogger(GroupRateLimiter.class);
    
    // 极致优化的默认配置
    private static final int DEFAULT_BURST_CAPACITY = 20;       // 默认突发容量（提升至20，更高吞吐量）
    private static final long DEFAULT_REFILL_INTERVAL_MS = 50;  // 默认补充间隔50ms（极致减少延迟）
    private static final int DEFAULT_REFILL_TOKENS = 4;         // 默认每次补充4个令牌（显著提升吞吐量）
    
    // 极致优化的自适应调整参数
    private static final int HIGH_FREQUENCY_THRESHOLD = 20;     // 高频消息阈值（每分钟，进一步提升）
    private static final int MEDIUM_FREQUENCY_THRESHOLD = 10;   // 中频消息阈值（每分钟）
    private static final int LOW_FREQUENCY_THRESHOLD = 4;       // 低频消息阈值（每分钟）
    private static final double ADAPTIVE_FACTOR_HIGH = 2.5;    // 高频时的容量倍率（大幅提升）
    private static final double ADAPTIVE_FACTOR_MEDIUM = 1.5;  // 中频时的容量倍率（提升）
    private static final double ADAPTIVE_FACTOR_LOW = 0.8;     // 低频时的容量倍率（优化）
    
    // 智能时间段调整参数
    private static final double PEAK_HOUR_MULTIPLIER = 1.8;    // 高峰期容量倍率
    private static final double OFF_PEAK_MULTIPLIER = 0.9;     // 低峰期容量倍率
    private static final int[] PEAK_HOURS = {9, 10, 11, 14, 15, 16, 19, 20, 21}; // 高峰时段
    
    // 极致优化的突发流量检测参数
    private static final int BURST_DETECTION_WINDOW = 6000;    // 突发检测窗口6秒（更敏感响应）
    private static final int BURST_THRESHOLD = 30;             // 突发阈值（6秒内30次请求）
    private static final int SUPER_BURST_THRESHOLD = 50;       // 超级突发阈值（6秒内50次请求）
    private static final int EXTREME_BURST_THRESHOLD = 80;     // 极限突发阈值（6秒内80次请求）
    private static final double BURST_CAPACITY_MULTIPLIER = 2.5; // 突发模式容量倍率（提升）
    private static final double SUPER_BURST_MULTIPLIER = 3.5;  // 超级突发模式倍率
    private static final double EXTREME_BURST_MULTIPLIER = 5.0; // 极限突发模式倍率
    
    private final ConcurrentHashMap<String, EnhancedTokenBucket> buckets = new ConcurrentHashMap<>();
    private final int baseBurstCapacity;
    private final long baseRefillIntervalMs;
    private final int baseRefillTokens;
    
    // 性能监控
    private final ConcurrentHashMap<String, GroupMetrics> groupMetrics = new ConcurrentHashMap<>();
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalWaitTime = new AtomicLong(0);
    private final AtomicLong totalBurstEvents = new AtomicLong(0);
    
    // 自适应调整
    private final ScheduledExecutorService adaptiveScheduler = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "GroupRateLimiter-Adaptive");
            t.setDaemon(true);
            return t;
        }
    );
    
    // 全局自适应状态
    private volatile boolean enableAdaptiveAdjustment = true;
    private volatile double globalLoadFactor = 1.0;
    
    public GroupRateLimiter() {
        this(DEFAULT_BURST_CAPACITY, DEFAULT_REFILL_INTERVAL_MS, DEFAULT_REFILL_TOKENS);
    }
    
    public GroupRateLimiter(int burstCapacity, long refillIntervalMs, int refillTokens) {
        this.baseBurstCapacity = Math.max(1, burstCapacity);
        this.baseRefillIntervalMs = Math.max(100, refillIntervalMs);
        this.baseRefillTokens = Math.max(1, refillTokens);
        
        // 启动自适应调整任务
        startAdaptiveAdjustment();
        
        logger.debug("Enhanced GroupRateLimiter initialized: burst={}, refillInterval={}ms, refillTokens={}, adaptive=enabled",
                   this.baseBurstCapacity, this.baseRefillIntervalMs, this.baseRefillTokens);
    }
    
    /**
     * 尝试获取发送许可（增强版）
     * @param groupId 群组ID
     * @return 是否获得许可
     */
    public boolean tryAcquire(String groupId) {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        EnhancedTokenBucket bucket = buckets.computeIfAbsent(groupId, k -> new EnhancedTokenBucket(groupId));
        boolean acquired = bucket.tryConsume();
        
        // 更新群组指标
        GroupMetrics metrics = groupMetrics.computeIfAbsent(groupId, k -> new GroupMetrics());
        metrics.recordRequest(acquired);
        
        if (!acquired) {
            long waitTime = bucket.getWaitTimeMs();
            totalWaitTime.addAndGet(waitTime);
        }
        
        return acquired;
    }
    
    /**
     * 获取下次可发送的等待时间（增强版）
     * @param groupId 群组ID
     * @return 等待毫秒数，0表示可立即发送
     */
    public long getWaitTimeMs(String groupId) {
        EnhancedTokenBucket bucket = buckets.computeIfAbsent(groupId, k -> new EnhancedTokenBucket(groupId));
        return bucket.getWaitTimeMs();
    }
    
    /**
     * 重置指定群组的限流状态
     * @param groupId 群组ID
     */
    public void resetGroup(String groupId) {
        buckets.remove(groupId);
        groupMetrics.remove(groupId);
        logger.debug("Reset rate limiter for group: {}", groupId);
    }
    
    /**
     * 清理所有限流状态
     */
    public void clearAll() {
        buckets.clear();
        groupMetrics.clear();
        logger.info("Cleared all rate limiter states");
    }
    
    /**
     * 获取当前活跃群组数量
     * @return 活跃群组数量
     */
    public int getActiveGroupCount() {
        return buckets.size();
    }
    
    /**
     * 获取增强的性能统计信息
     * @return 性能统计字符串
     */
    public String getPerformanceStats() {
        long totalReq = totalRequests.get();
        long totalWait = totalWaitTime.get();
        long totalBurst = totalBurstEvents.get();
        double avgWaitTime = totalReq > 0 ? (double) totalWait / totalReq : 0.0;
        
        double avgSuccessRate = groupMetrics.values().stream()
                .mapToDouble(GroupMetrics::getSuccessRate)
                .average().orElse(1.0);
        
        return String.format("GroupRateLimiter Stats: requests=%d, avgWait=%.2fms, successRate=%.2f%%, bursts=%d, activeGroups=%d, globalLoad=%.2f",
                totalReq, avgWaitTime, avgSuccessRate * 100, totalBurst, getActiveGroupCount(), globalLoadFactor);
    }
    
    /**
     * 获取指定群组的详细指标
     * @param groupId 群组ID
     * @return 群组指标，如果不存在返回null
     */
    public GroupMetrics getGroupMetrics(String groupId) {
        return groupMetrics.get(groupId);
    }
    
    /**
     * 启用或禁用自适应调整
     * @param enabled 是否启用
     */
    public void setAdaptiveAdjustmentEnabled(boolean enabled) {
        this.enableAdaptiveAdjustment = enabled;
        logger.info("Adaptive adjustment {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * 手动设置全局负载因子
     * @param loadFactor 负载因子（0.5-2.0）
     */
    public void setGlobalLoadFactor(double loadFactor) {
        this.globalLoadFactor = Math.max(0.5, Math.min(2.0, loadFactor));
        logger.info("Global load factor set to: {}", this.globalLoadFactor);
    }
    
    /**
     * 关闭限流器，释放资源
     */
    public void shutdown() {
        try {
            adaptiveScheduler.shutdown();
            if (!adaptiveScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                adaptiveScheduler.shutdownNow();
            }
            logger.info("GroupRateLimiter shutdown completed");
        } catch (InterruptedException e) {
            adaptiveScheduler.shutdownNow();
            Thread.currentThread().interrupt();
            logger.warn("GroupRateLimiter shutdown interrupted", e);
        }
    }
    
    /**
     * 启动自适应调整任务
     */
    private void startAdaptiveAdjustment() {
        adaptiveScheduler.scheduleAtFixedRate(this::performAdaptiveAdjustment, 30, 30, TimeUnit.SECONDS);
        logger.debug("Adaptive adjustment scheduler started");
    }
    
    /**
     * 执行自适应调整
     */
    private void performAdaptiveAdjustment() {
        if (!enableAdaptiveAdjustment) {
            return;
        }
        
        try {
            // 分析全局负载
            analyzeGlobalLoad();
            
            // 获取时间段调整因子
            double timeBasedFactor = getTimeBasedAdjustmentFactor();
            
            // 调整各群组参数
            for (Map.Entry<String, EnhancedTokenBucket> entry : buckets.entrySet()) {
                String groupId = entry.getKey();
                EnhancedTokenBucket bucket = entry.getValue();
                GroupMetrics metrics = groupMetrics.get(groupId);
                
                if (metrics != null) {
                    bucket.adjustCapacity(metrics, globalLoadFactor, timeBasedFactor);
                }
            }
            
            // 清理不活跃的群组
            cleanupInactiveGroups();
            
            logger.debug("Adaptive adjustment completed: globalLoad={}, timeBasedFactor={}, activeGroups={}", 
                        globalLoadFactor, timeBasedFactor, getActiveGroupCount());
        } catch (Exception e) {
            logger.warn("Adaptive adjustment failed", e);
        }
    }
    
    /**
     * 获取基于时间段的调整因子
     */
    private double getTimeBasedAdjustmentFactor() {
        int currentHour = java.time.LocalTime.now().getHour();
        
        // 检查是否为高峰时段
        for (int peakHour : PEAK_HOURS) {
            if (currentHour == peakHour) {
                return PEAK_HOUR_MULTIPLIER;
            }
        }
        
        return OFF_PEAK_MULTIPLIER;
    }
    
    /**
     * 分析全局负载情况
     */
    private void analyzeGlobalLoad() {
        long totalReq = totalRequests.get();
        long totalWait = totalWaitTime.get();
        
        if (totalReq > 0) {
            double avgWaitTime = (double) totalWait / totalReq;
            
            // 基于平均等待时间调整全局负载因子
            if (avgWaitTime > 1000) { // 平均等待超过1秒
                globalLoadFactor = Math.min(2.0, globalLoadFactor * 1.1);
            } else if (avgWaitTime < 100) { // 平均等待小于100ms
                globalLoadFactor = Math.max(0.5, globalLoadFactor * 0.95);
            }
        }
        
        // 重置统计计数器（避免溢出）
        if (totalReq > 1000000) {
            totalRequests.set(0);
            totalWaitTime.set(0);
        }
    }
    
    /**
     * 清理不活跃的群组
     */
    private void cleanupInactiveGroups() {
        long currentTime = System.currentTimeMillis();
        List<String> inactiveGroups = new ArrayList<>();
        
        for (Map.Entry<String, GroupMetrics> entry : groupMetrics.entrySet()) {
            String groupId = entry.getKey();
            GroupMetrics metrics = entry.getValue();
            
            // 如果群组超过10分钟没有活动，标记为不活跃
            if (currentTime - metrics.getLastActivityTime() > 600000) {
                inactiveGroups.add(groupId);
            }
        }
        
        // 清理不活跃的群组
        for (String groupId : inactiveGroups) {
            buckets.remove(groupId);
            groupMetrics.remove(groupId);
        }
        
        if (!inactiveGroups.isEmpty()) {
            logger.debug("Cleaned up {} inactive groups", inactiveGroups.size());
        }
    }
    
    /**
     * 增强版令牌桶实现
     * 支持自适应容量调整和突发流量检测
     */
    private class EnhancedTokenBucket {
        private final String groupId;
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicLong tokens;
        private volatile long lastRefillTime = System.currentTimeMillis();
        
        // 自适应参数
        private volatile int currentCapacity;
        private volatile long currentRefillInterval;
        private volatile int currentRefillTokens;
        
        // 多级突发流量检测
        private final List<Long> recentRequests = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean inBurstMode = false;
        private volatile boolean inSuperBurstMode = false;
        private volatile boolean inExtremeBurstMode = false;
        private volatile long burstModeStartTime = 0;
        private volatile long superBurstModeStartTime = 0;
        private volatile long extremeBurstModeStartTime = 0;
        private volatile long maxWaitTime = 0; // 最大等待时间统计
        
        public EnhancedTokenBucket(String groupId) {
            this.groupId = groupId;
            this.currentCapacity = baseBurstCapacity;
            this.currentRefillInterval = baseRefillIntervalMs;
            this.currentRefillTokens = baseRefillTokens;
            this.tokens = new AtomicLong(currentCapacity);
        }
        
        public boolean tryConsume() {
            long currentTime = System.currentTimeMillis();
            
            // 记录请求时间用于突发检测
            recordRequest(currentTime);
            
            // 检测突发流量
            detectBurstTraffic(currentTime);
            
            // 补充令牌
            refillTokens();
            
            // 尝试消费令牌
            boolean consumed = tokens.getAndUpdate(current -> current > 0 ? current - 1 : current) > 0;
            
            return consumed;
        }
        
        public long getWaitTimeMs() {
            refillTokens();
            if (tokens.get() > 0) {
                return 0;
            }
            
            // 计算下次补充令牌的时间
            long timeSinceLastRefill = System.currentTimeMillis() - lastRefillTime;
            long waitTime = Math.max(0, currentRefillInterval - timeSinceLastRefill);
            
            // 更新最大等待时间统计
            if (waitTime > maxWaitTime) {
                maxWaitTime = waitTime;
            }
            
            return waitTime;
        }
        
        /**
         * 获取最大等待时间
         */
        public long getMaxWaitTime() {
            return maxWaitTime;
        }
        
        /**
         * 根据群组指标调整容量（极致优化版）
         */
        public void adjustCapacity(GroupMetrics metrics, double globalFactor, double timeBasedFactor) {
            try {
                double frequency = metrics.getRequestFrequency();
                double successRate = metrics.getSuccessRate();
                double adjustmentFactor = 1.0;
                
                // 基于消息频率的多级调整
                if (frequency > HIGH_FREQUENCY_THRESHOLD) {
                    adjustmentFactor = ADAPTIVE_FACTOR_HIGH;
                } else if (frequency > MEDIUM_FREQUENCY_THRESHOLD) {
                    adjustmentFactor = ADAPTIVE_FACTOR_MEDIUM;
                } else if (frequency < LOW_FREQUENCY_THRESHOLD) {
                    adjustmentFactor = ADAPTIVE_FACTOR_LOW;
                }
                
                // 基于成功率的额外调整
                if (successRate < 0.8) {
                    adjustmentFactor *= 1.2; // 成功率低时增加容量
                } else if (successRate > 0.95) {
                    adjustmentFactor *= 0.9; // 成功率高时可适当减少容量
                }
                
                // 应用多重因子
                adjustmentFactor *= globalFactor;        // 全局负载因子
                adjustmentFactor *= timeBasedFactor;     // 时间段因子
                
                // 检测突发模式并应用额外倍率
                if (inBurstMode) {
                    int recentCount = recentRequests.size();
                    if (recentCount >= EXTREME_BURST_THRESHOLD) {
                        adjustmentFactor *= EXTREME_BURST_MULTIPLIER;
                    } else if (recentCount >= SUPER_BURST_THRESHOLD) {
                        adjustmentFactor *= SUPER_BURST_MULTIPLIER;
                    } else {
                        adjustmentFactor *= BURST_CAPACITY_MULTIPLIER;
                    }
                }
                
                // 计算新的容量参数（更激进的调整）
                int newCapacity = (int) Math.round(baseBurstCapacity * adjustmentFactor);
                long newRefillInterval = (long) Math.round(baseRefillIntervalMs / Math.sqrt(adjustmentFactor));
                int newRefillTokens = Math.max(1, (int) Math.round(baseRefillTokens * Math.sqrt(adjustmentFactor)));
                
                // 扩大调整范围以支持更高性能
                newCapacity = Math.max(2, Math.min(100, newCapacity));
                newRefillInterval = Math.max(50, Math.min(3000, newRefillInterval));
                newRefillTokens = Math.max(1, Math.min(20, newRefillTokens));
                
                // 应用新参数
                if (newCapacity != currentCapacity || newRefillInterval != currentRefillInterval || newRefillTokens != currentRefillTokens) {
                    currentCapacity = newCapacity;
                    currentRefillInterval = newRefillInterval;
                    currentRefillTokens = newRefillTokens;
                    
                    // 调整当前令牌数（不超过新容量）
                    final int finalCapacity = currentCapacity;
                    tokens.updateAndGet(current -> Math.min(finalCapacity, current));
                    
                    logger.debug("Group {} capacity adjusted: capacity={}, interval={}ms, tokens={}, frequency={:.2f}, successRate={:.2f}%, adjustmentFactor={:.2f}",
                                groupId, currentCapacity, currentRefillInterval, currentRefillTokens, frequency, successRate * 100, adjustmentFactor);
                }
            } catch (Exception e) {
                logger.warn("GroupRateLimiter", "Failed to adjust capacity for group " + groupId + ": " + e.getMessage());
            }
        }
        
        /**
         * 记录请求时间
         */
        private void recordRequest(long currentTime) {
            recentRequests.add(currentTime);
            
            // 清理过期请求记录
            recentRequests.removeIf(time -> currentTime - time > BURST_DETECTION_WINDOW);
        }
        
        /**
         * 增强的突发流量检测（支持多级突发）
         */
        private void detectBurstTraffic(long currentTime) {
            int recentCount = recentRequests.size();
            
            // 检测各级突发模式
            if (!inBurstMode && recentCount >= EXTREME_BURST_THRESHOLD) {
                enterExtremeBurstMode(currentTime);
            } else if (!inBurstMode && recentCount >= SUPER_BURST_THRESHOLD) {
                enterSuperBurstMode(currentTime);
            } else if (!inBurstMode && recentCount >= BURST_THRESHOLD) {
                enterBurstMode(currentTime);
            }
            // 检测是否退出突发模式
            else if (inBurstMode && (currentTime - burstModeStartTime > 20000 || recentCount < BURST_THRESHOLD / 3)) {
                exitBurstMode();
            }
        }
        
        /**
         * 进入普通突发模式
         */
        private void enterBurstMode(long currentTime) {
            inBurstMode = true;
            burstModeStartTime = currentTime;
            totalBurstEvents.incrementAndGet();
            
            int originalCapacity = currentCapacity;
            currentCapacity = Math.min(100, (int) (currentCapacity * BURST_CAPACITY_MULTIPLIER));
            
            logger.info("Group {} entered burst mode: capacity increased from {} to {}", 
                       groupId, originalCapacity, currentCapacity);
        }
        
        /**
         * 进入超级突发模式
         */
        private void enterSuperBurstMode(long currentTime) {
            inBurstMode = true;
            burstModeStartTime = currentTime;
            totalBurstEvents.incrementAndGet();
            
            int originalCapacity = currentCapacity;
            currentCapacity = Math.min(100, (int) (currentCapacity * SUPER_BURST_MULTIPLIER));
            currentRefillInterval = Math.max(50, currentRefillInterval / 2);
            
            logger.warn("Group {} entered SUPER burst mode: capacity increased from {} to {}, interval reduced to {}ms", 
                       groupId, originalCapacity, currentCapacity, currentRefillInterval);
        }
        
        /**
         * 进入极限突发模式
         */
        private void enterExtremeBurstMode(long currentTime) {
            inBurstMode = true;
            burstModeStartTime = currentTime;
            totalBurstEvents.incrementAndGet();
            
            int originalCapacity = currentCapacity;
            currentCapacity = Math.min(100, (int) (currentCapacity * EXTREME_BURST_MULTIPLIER));
            currentRefillInterval = Math.max(30, currentRefillInterval / 4);
            
            logger.warn("Group {} entered EXTREME burst mode: capacity increased from {} to {}, interval reduced to {}ms", 
                       groupId, originalCapacity, currentCapacity, currentRefillInterval);
        }
        
        /**
         * 退出突发模式
         */
        private void exitBurstMode() {
            inBurstMode = false;
            currentCapacity = baseBurstCapacity;
            currentRefillInterval = baseRefillIntervalMs;
            
            logger.info("Group {} exited burst mode: capacity and interval restored to {} and {}ms", 
                       groupId, currentCapacity, currentRefillInterval);
        }
        
        /**
         * 极致优化的令牌补充方法（支持多级突发模式）
         */
        private void refillTokens() {
            long currentTime = System.currentTimeMillis();
            long timeSinceLastRefill = currentTime - lastRefillTime;
            
            if (timeSinceLastRefill >= currentRefillInterval) {
                // 使用tryLock减少阻塞
                if (lock.tryLock()) {
                    try {
                        // 双重检查
                        long actualTimeSinceRefill = System.currentTimeMillis() - lastRefillTime;
                        
                        if (actualTimeSinceRefill >= currentRefillInterval) {
                            // 计算应该补充的令牌数（支持批量补充）
                            long intervalsElapsed = actualTimeSinceRefill / currentRefillInterval;
                            long tokensToAdd = intervalsElapsed * currentRefillTokens;
                            
                            // 根据突发模式调整补充速度
                            double burstMultiplier = 1.0;
                            if (inExtremeBurstMode) {
                                burstMultiplier = 3.0; // 极限模式：3倍补充
                            } else if (inSuperBurstMode) {
                                burstMultiplier = 2.5; // 超级模式：2.5倍补充
                            } else if (inBurstMode) {
                                burstMultiplier = 2.0; // 普通突发：2倍补充
                            }
                            
                            tokensToAdd = (long) (tokensToAdd * burstMultiplier);
                            
                            // 限制最大补充量避免过度补充
                            tokensToAdd = Math.min(tokensToAdd, currentCapacity);
                            
                            if (tokensToAdd > 0) {
                                // 使用更高效的原子更新
                                final int finalCapacity = currentCapacity;
                                final long finalTokensToAdd = tokensToAdd;
                                tokens.updateAndGet(current -> Math.min(finalCapacity, current + finalTokensToAdd));
                                
                                // 更新最后补充时间
                                lastRefillTime = System.currentTimeMillis() - (actualTimeSinceRefill % currentRefillInterval);
                                
                                // 记录补充统计
                                if (burstMultiplier > 1.0) {
                                    logger.trace("快速补充令牌: 倍率={}, 补充={}, 当前={}/{}", 
                                               burstMultiplier, finalTokensToAdd, tokens.get(), finalCapacity);
                                }
                            }
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }
        
        /**
         * 检查是否处于各种突发模式
         */
        public boolean isInBurstMode() {
            return inBurstMode;
        }
        
        public boolean isInSuperBurstMode() {
            return inSuperBurstMode;
        }
        
        public boolean isInExtremeBurstMode() {
            return inExtremeBurstMode;
        }
    }
    
    /**
     * 群组性能指标
     */
    public static class GroupMetrics {
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong successfulRequests = new AtomicLong(0);
        private final AtomicLong rejectedRequests = new AtomicLong(0);
        private volatile long lastActivityTime = System.currentTimeMillis();
        private final List<Long> requestTimes = Collections.synchronizedList(new ArrayList<>());
        
        public void recordRequest(boolean successful) {
            long currentTime = System.currentTimeMillis();
            totalRequests.incrementAndGet();
            lastActivityTime = currentTime;
            
            if (successful) {
                successfulRequests.incrementAndGet();
            } else {
                rejectedRequests.incrementAndGet();
            }
            
            // 记录请求时间用于频率计算
            requestTimes.add(currentTime);
            
            // 清理过期记录（保留最近1分钟）
            requestTimes.removeIf(time -> currentTime - time > 60000);
        }
        
        public double getRequestFrequency() {
            long currentTime = System.currentTimeMillis();
            // 计算最近1分钟的请求频率
            long recentCount = requestTimes.stream()
                    .mapToLong(time -> currentTime - time <= 60000 ? 1 : 0)
                    .sum();
            return recentCount; // 每分钟请求数
        }
        
        public double getSuccessRate() {
            long total = totalRequests.get();
            return total > 0 ? (double) successfulRequests.get() / total : 1.0;
        }
        
        public long getTotalRequests() {
            return totalRequests.get();
        }
        
        public long getSuccessfulRequests() {
            return successfulRequests.get();
        }
        
        public long getRejectedRequests() {
            return rejectedRequests.get();
        }
        
        public long getLastActivityTime() {
            return lastActivityTime;
        }
        
        @Override
        public String toString() {
            return String.format("GroupMetrics{total=%d, success=%d, rejected=%d, frequency=%.2f, successRate=%.2f%%}",
                    getTotalRequests(), getSuccessfulRequests(), getRejectedRequests(), 
                    getRequestFrequency(), getSuccessRate() * 100);
        }
    }
}