package net.luffy.util;

import net.luffy.util.delay.DelayMetricsCollector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Pocket48监控指标收集器
 * 收集关键路径的性能指标：下载成功率、重试次数、平均延迟、队列长度、丢弃率
 */
public class Pocket48MetricsCollector implements MetricsCollectable {
    private static final Pocket48MetricsCollector INSTANCE = new Pocket48MetricsCollector();
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // 下载相关指标
    private final AtomicLong downloadAttempts = new AtomicLong(0);
    private final AtomicLong downloadSuccesses = new AtomicLong(0);
    private final AtomicLong downloadFailures = new AtomicLong(0);
    
    // 重试相关指标
    private final AtomicLong totalRetries = new AtomicLong(0);
    private final AtomicInteger maxRetries = new AtomicInteger(0);
    
    // 延迟相关指标
    private final LongAdder totalLatency = new LongAdder();
    private final AtomicLong latencyCount = new AtomicLong(0);
    private final AtomicLong maxLatency = new AtomicLong(0);
    private final AtomicLong minLatency = new AtomicLong(Long.MAX_VALUE);
    
    // 队列相关指标
    private final AtomicInteger currentQueueSize = new AtomicInteger(0);
    private final AtomicInteger maxQueueSize = new AtomicInteger(0);
    private final AtomicLong queueOffers = new AtomicLong(0);
    private final AtomicLong queuePolls = new AtomicLong(0);
    
    // 丢弃相关指标
    private final AtomicLong messagesDropped = new AtomicLong(0);
    private final AtomicLong mediaDropped = new AtomicLong(0);
    private final AtomicLong textDropped = new AtomicLong(0);
    
    // 缓存相关指标
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong cacheEvictions = new AtomicLong(0);
    
    // 错误分类计数
    private final Map<String, AtomicLong> errorTypes = new ConcurrentHashMap<>();
    
    // 自定义指标
    private final Map<String, AtomicLong> customMetrics = new ConcurrentHashMap<>();
    
    // 时间窗口统计（最近1小时）
    private final Map<String, AtomicLong> hourlyStats = new ConcurrentHashMap<>();
    private volatile long lastHourlyReset = System.currentTimeMillis();
    
    private Pocket48MetricsCollector() {
        // 初始化小时统计
        resetHourlyStats();
    }
    
    public static Pocket48MetricsCollector getInstance() {
        return INSTANCE;
    }
    
    // ==================== 下载指标 ====================
    
    /**
     * 记录下载尝试
     */
    public void recordDownloadAttempt() {
        downloadAttempts.incrementAndGet();
        incrementHourlyStat("download_attempts");
    }
    
    /**
     * 记录下载成功
     */
    public void recordDownloadSuccess(long latencyMs) {
        downloadSuccesses.incrementAndGet();
        recordLatency(latencyMs);
        incrementHourlyStat("download_successes");
        
        // 同步到延迟度量收集器
        DelayMetricsCollector.getInstance().recordDelayCalculation("download", latencyMs, true);
        
        logger.debug("Pocket48Metrics", "下载成功，延迟: " + latencyMs + "ms");
    }
    
    /**
     * 记录下载失败
     */
    public void recordDownloadFailure(String errorType) {
        downloadFailures.incrementAndGet();
        errorTypes.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
        incrementHourlyStat("download_failures");
        
        // 同步到延迟度量收集器
        DelayMetricsCollector.getInstance().recordRetryAttempt(errorType, 0);
        DelayMetricsCollector.getInstance().recordRetryResult(false);
        
        logger.warn("Pocket48Metrics", "下载失败，错误类型: " + errorType);
    }
    
    /**
     * 获取下载成功率
     */
    public double getDownloadSuccessRate() {
        long attempts = downloadAttempts.get();
        if (attempts == 0) return 0.0;
        return (double) downloadSuccesses.get() / attempts * 100.0;
    }
    
    // ==================== 重试指标 ====================
    
    /**
     * 记录重试次数
     */
    public void recordRetry(int retryCount) {
        totalRetries.addAndGet(retryCount);
        maxRetries.updateAndGet(current -> Math.max(current, retryCount));
        incrementHourlyStat("retries");
        if (retryCount > 0) {
            logger.info("Pocket48Metrics", "执行重试，次数: " + retryCount);
        }
    }
    
    /**
     * 记录重试尝试（新增方法）
     */
    public void recordRetryAttempt(String operation, int attemptNumber, String errorType) {
        totalRetries.incrementAndGet();
        maxRetries.updateAndGet(current -> Math.max(current, attemptNumber));
        incrementHourlyStat("retry_attempts");
        
        // 记录错误类型
        if (errorType != null && !errorType.isEmpty()) {
            errorTypes.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
        }
        
        logger.debug("Pocket48Metrics", String.format("重试尝试 - 操作: %s, 第%d次, 错误: %s", 
            operation, attemptNumber, errorType));
    }
    
    /**
     * 获取平均重试次数
     */
    public double getAverageRetries() {
        long attempts = downloadAttempts.get();
        if (attempts == 0) return 0.0;
        return (double) totalRetries.get() / attempts;
    }
    
    // ==================== 延迟指标 ====================
    
    /**
     * 记录延迟
     */
    public void recordLatency(long latencyMs) {
        totalLatency.add(latencyMs);
        latencyCount.incrementAndGet();
        maxLatency.updateAndGet(current -> Math.max(current, latencyMs));
        minLatency.updateAndGet(current -> Math.min(current, latencyMs));
    }
    
    /**
     * 获取平均延迟
     */
    public double getAverageLatency() {
        long count = latencyCount.get();
        if (count == 0) return 0.0;
        return (double) totalLatency.sum() / count;
    }
    
    // ==================== 队列指标 ====================
    
    /**
     * 更新队列大小
     */
    public void updateQueueSize(int size) {
        currentQueueSize.set(size);
        maxQueueSize.updateAndGet(current -> Math.max(current, size));
    }
    
    /**
     * 记录队列入队
     */
    public void recordQueueOffer() {
        queueOffers.incrementAndGet();
        incrementHourlyStat("queue_offers");
    }
    
    /**
     * 记录队列出队
     */
    public void recordQueuePoll() {
        queuePolls.incrementAndGet();
        incrementHourlyStat("queue_polls");
    }
    
    // ==================== 丢弃指标 ====================
    
    /**
     * 记录消息丢弃
     */
    public void recordMessageDropped(String messageType) {
        messagesDropped.incrementAndGet();
        if ("media".equals(messageType)) {
            mediaDropped.incrementAndGet();
        } else if ("text".equals(messageType)) {
            textDropped.incrementAndGet();
        }
        incrementHourlyStat("messages_dropped");
        logger.warn("Pocket48Metrics", "消息丢弃，类型: " + messageType);
    }
    
    /**
     * 获取丢弃率
     */
    public double getDropRate() {
        long total = queueOffers.get();
        if (total == 0) return 0.0;
        return (double) messagesDropped.get() / total * 100.0;
    }
    
    // ==================== 缓存指标 ====================
    
    /**
     * 记录缓存命中
     */
    public void recordCacheHit() {
        cacheHits.incrementAndGet();
        incrementHourlyStat("cache_hits");
    }
    
    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
        incrementHourlyStat("cache_misses");
    }
    
    /**
     * 记录缓存驱逐
     */
    public void recordCacheEviction() {
        cacheEvictions.incrementAndGet();
        incrementHourlyStat("cache_evictions");
    }
    
    /**
     * 获取缓存命中率
     */
    public double getCacheHitRate() {
        long total = cacheHits.get() + cacheMisses.get();
        if (total == 0) return 0.0;
        return (double) cacheHits.get() / total * 100.0;
    }
    
    // ==================== 错误指标 ====================
    
    /**
     * 记录错误
     */
    public void recordError(String errorType) {
        errorTypes.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
        incrementHourlyStat("errors");
        logger.warn("Pocket48Metrics", "记录错误: " + errorType);
    }
    
    /**
     * 记录重试（无参数版本）
     */
    public void recordRetry() {
        recordRetry(1);
    }
    
    // ==================== 自定义指标 ====================
    
    /**
     * 记录自定义指标
     */
    public void recordCustomMetric(String metricName, long value) {
        customMetrics.put(metricName, new AtomicLong(value));
        logger.debug("Pocket48Metrics", "记录自定义指标: " + metricName + " = " + value);
    }
    
    /**
     * 获取自定义指标值
     */
    public long getCustomMetric(String metricName) {
        AtomicLong metric = customMetrics.get(metricName);
        return metric != null ? metric.get() : 0;
    }
    
    // ==================== 统计报告 ====================
    
    /**
     * 生成性能报告
     */
    public String generateReport() {
        checkAndResetHourlyStats();
        
        StringBuilder report = new StringBuilder();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        report.append("\n=== Pocket48 性能指标报告 ===").append("\n");
        report.append("生成时间: ").append(LocalDateTime.now().format(formatter)).append("\n\n");
        
        // 下载指标
        report.append("📥 下载指标:\n");
        report.append(String.format("  总尝试次数: %d\n", downloadAttempts.get()));
        report.append(String.format("  成功次数: %d\n", downloadSuccesses.get()));
        report.append(String.format("  失败次数: %d\n", downloadFailures.get()));
        report.append(String.format("  成功率: %.2f%%\n", getDownloadSuccessRate()));
        
        // 重试指标
        report.append("\n🔄 重试指标:\n");
        report.append(String.format("  总重试次数: %d\n", totalRetries.get()));
        report.append(String.format("  平均重试次数: %.2f\n", getAverageRetries()));
        report.append(String.format("  最大重试次数: %d\n", maxRetries.get()));
        
        // 延迟指标
        report.append("\n⏱️ 延迟指标:\n");
        report.append(String.format("  平均延迟: %.2fms\n", getAverageLatency()));
        report.append(String.format("  最大延迟: %dms\n", maxLatency.get()));
        report.append(String.format("  最小延迟: %dms\n", minLatency.get() == Long.MAX_VALUE ? 0 : minLatency.get()));
        
        // 队列指标
        report.append("\n📋 队列指标:\n");
        report.append(String.format("  当前队列长度: %d\n", currentQueueSize.get()));
        report.append(String.format("  最大队列长度: %d\n", maxQueueSize.get()));
        report.append(String.format("  入队次数: %d\n", queueOffers.get()));
        report.append(String.format("  出队次数: %d\n", queuePolls.get()));
        
        // 丢弃指标
        report.append("\n🗑️ 丢弃指标:\n");
        report.append(String.format("  总丢弃数: %d\n", messagesDropped.get()));
        report.append(String.format("  媒体丢弃数: %d\n", mediaDropped.get()));
        report.append(String.format("  文本丢弃数: %d\n", textDropped.get()));
        report.append(String.format("  丢弃率: %.2f%%\n", getDropRate()));
        
        // 缓存指标
        report.append("\n💾 缓存指标:\n");
        report.append(String.format("  缓存命中: %d\n", cacheHits.get()));
        report.append(String.format("  缓存未命中: %d\n", cacheMisses.get()));
        report.append(String.format("  缓存驱逐: %d\n", cacheEvictions.get()));
        report.append(String.format("  命中率: %.2f%%\n", getCacheHitRate()));
        
        // 错误分类
        if (!errorTypes.isEmpty()) {
            report.append("\n❌ 错误分类:\n");
            errorTypes.forEach((type, count) -> 
                report.append(String.format("  %s: %d\n", type, count.get()))
            );
        }
        
        // 最近1小时统计
        report.append("\n📊 最近1小时统计:\n");
        hourlyStats.forEach((key, value) -> 
            report.append(String.format("  %s: %d\n", key, value.get()))
        );
        
        return report.toString();
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 增加小时统计
     */
    private void incrementHourlyStat(String key) {
        hourlyStats.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * 检查并重置小时统计
     */
    private void checkAndResetHourlyStats() {
        long now = System.currentTimeMillis();
        if (now - lastHourlyReset > 1800000) { // 30分钟（优化为更频繁的重置）
            resetHourlyStats();
            lastHourlyReset = now;
        }
    }
    
    /**
     * 重置小时统计
     */
    private void resetHourlyStats() {
        lock.writeLock().lock();
        try {
            hourlyStats.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 重置所有指标
     */
    public void resetAllMetrics() {
        downloadAttempts.set(0);
        downloadSuccesses.set(0);
        downloadFailures.set(0);
        totalRetries.set(0);
        maxRetries.set(0);
        totalLatency.reset();
        latencyCount.set(0);
        maxLatency.set(0);
        minLatency.set(Long.MAX_VALUE);
        currentQueueSize.set(0);
        maxQueueSize.set(0);
        queueOffers.set(0);
        queuePolls.set(0);
        messagesDropped.set(0);
        mediaDropped.set(0);
        textDropped.set(0);
        cacheHits.set(0);
        cacheMisses.set(0);
        cacheEvictions.set(0);
        errorTypes.clear();
        resetHourlyStats();
        
        logger.info("Pocket48Metrics", "所有指标已重置");
    }
    
    // ==================== MetricsCollectable 接口实现 ====================
    
    @Override
    public String getComponentName() {
        return "Pocket48Core";
    }
    
    @Override
    public Map<String, Object> collectMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        // 下载指标
        metrics.put("download.attempts", downloadAttempts.get());
        metrics.put("download.successes", downloadSuccesses.get());
        metrics.put("download.failures", downloadFailures.get());
        metrics.put("download.success_rate", getDownloadSuccessRate());
        
        // 重试指标
        metrics.put("retry.total", totalRetries.get());
        metrics.put("retry.average", getAverageRetries());
        
        // 延迟指标
        metrics.put("latency.average", getAverageLatency());
        metrics.put("latency.max", maxLatency.get());
        metrics.put("latency.min", minLatency.get() == Long.MAX_VALUE ? 0 : minLatency.get());
        metrics.put("latency.total", totalLatency.sum());
        metrics.put("latency.count", latencyCount.get());
        
        // 队列指标
        metrics.put("queue.current_size", currentQueueSize.get());
        metrics.put("queue.max_size", maxQueueSize.get());
        metrics.put("queue.offers", queueOffers.get());
        metrics.put("queue.polls", queuePolls.get());
        
        // 丢弃指标
        metrics.put("drop.total", messagesDropped.get());
        metrics.put("drop.media", mediaDropped.get());
        metrics.put("drop.text", textDropped.get());
        metrics.put("drop.rate", getDropRate());
        
        // 缓存指标
        metrics.put("cache.hits", cacheHits.get());
        metrics.put("cache.misses", cacheMisses.get());
        metrics.put("cache.evictions", cacheEvictions.get());
        metrics.put("cache.hit_rate", getCacheHitRate());
        
        // 错误指标
        metrics.put("errors.total", errorTypes.values().stream().mapToLong(AtomicLong::get).sum());
        metrics.put("errors.types", errorTypes.size());
        
        // 自定义指标
        metrics.put("custom.count", customMetrics.size());
        
        return metrics;
    }
    
    @Override
    public double getHealthScore() {
        double downloadHealth = getDownloadSuccessRate();
        double cacheHealth = getCacheHitRate();
        double queueHealth = Math.max(0, 100 - getDropRate());
        
        // 延迟健康度：延迟越低越健康
        double avgLatency = getAverageLatency();
        double latencyHealth = Math.max(0, 100 - Math.min(100, avgLatency / 10));
        
        // 综合健康度
        return (downloadHealth + cacheHealth + queueHealth + latencyHealth) / 4.0;
    }
    
    @Override
    public String getStatusDescription() {
        double health = getHealthScore();
        StringBuilder status = new StringBuilder();
        
        status.append(String.format("健康度: %.1f%% ", health));
        
        if (health >= 90) {
            status.append("(优秀)");
        } else if (health >= 80) {
            status.append("(良好)");
        } else if (health >= 70) {
            status.append("(一般)");
        } else if (health >= 60) {
            status.append("(需要关注)");
        } else {
            status.append("(异常)");
        }
        
        // 添加关键指标
        status.append(String.format(" | 下载成功率: %.1f%%, 缓存命中率: %.1f%%, 丢弃率: %.1f%%", 
            getDownloadSuccessRate(), getCacheHitRate(), getDropRate()));
        
        return status.toString();
    }
    
    @Override
    public void resetMetrics() {
        resetAllMetrics();
    }
}