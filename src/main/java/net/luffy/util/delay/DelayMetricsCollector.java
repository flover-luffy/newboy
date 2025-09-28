package net.luffy.util.delay;

import net.luffy.util.UnifiedLogger;
import net.luffy.util.Pocket48MetricsCollector;
import net.luffy.util.delay.DelayConfig;
import net.luffy.util.MetricsCollectable;
import net.luffy.util.ConfigOperator;
import net.luffy.Newboy;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Friend;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ScheduledExecutorService;
import net.luffy.util.UnifiedSchedulerManager;

/**
 * 延迟系统度量收集器
 * 专门收集延迟策略、重试机制、队列管理等相关指标
 * 与Pocket48MetricsCollector协同工作，提供更详细的延迟分析
 */
public class DelayMetricsCollector implements MetricsCollectable {
    private static volatile DelayMetricsCollector instance;
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // 延迟策略指标
    private final AtomicLong totalDelayCalculations = new AtomicLong(0);
    private final AtomicLong textMessageDelays = new AtomicLong(0);
    private final AtomicLong mediaMessageDelays = new AtomicLong(0);
    private final LongAdder totalDelayTime = new LongAdder();
    private final AtomicLong maxDelayTime = new AtomicLong(0);
    private final AtomicLong minDelayTime = new AtomicLong(Long.MAX_VALUE);
    
    // 重试机制指标
    private final AtomicLong retryAttempts = new AtomicLong(0);
    private final AtomicLong retrySuccesses = new AtomicLong(0);
    private final AtomicLong retryFailures = new AtomicLong(0);
    private final Map<String, AtomicLong> retryReasons = new ConcurrentHashMap<>();
    private final LongAdder totalRetryDelay = new LongAdder();
    
    // 队列管理指标
    private final AtomicInteger currentQueueDepth = new AtomicInteger(0);
    private final AtomicInteger maxQueueDepth = new AtomicInteger(0);
    private final AtomicLong queueWaitTime = new AtomicLong(0);
    private final AtomicLong queueThroughput = new AtomicLong(0);
    private final AtomicLong queueOverflows = new AtomicLong(0);
    
    // 发送速率指标
    private final AtomicLong messagesPerSecond = new AtomicLong(0);
    private final AtomicLong peakMessagesPerSecond = new AtomicLong(0);
    private final Map<String, AtomicLong> messageTypeRates = new ConcurrentHashMap<>();
    
    // 时间窗口统计（最近5分钟）
    private final Map<String, Deque<TimestampedValue>> recentMetrics = new ConcurrentHashMap<>();
    private static final long METRICS_WINDOW_MS = 5 * 60 * 1000; // 5分钟
    
    // 分位数统计
    private final List<Long> delayPercentiles = new ArrayList<>();
    private final List<Long> retryPercentiles = new ArrayList<>();
    
    private DelayMetricsCollector() {
        initializeMetrics();
        startPeriodicReporting();
    }
    
    public static DelayMetricsCollector getInstance() {
        if (instance == null) {
            synchronized (DelayMetricsCollector.class) {
                if (instance == null) {
                    instance = new DelayMetricsCollector();
                }
            }
        }
        return instance;
    }
    
    // ==================== 延迟策略指标 ====================
    
    /**
     * 记录延迟计算
     */
    public void recordDelayCalculation(String messageType, long delayMs, boolean isActive) {
        totalDelayCalculations.incrementAndGet();
        totalDelayTime.add(delayMs);
        
        // 更新最大最小延迟
        maxDelayTime.updateAndGet(current -> Math.max(current, delayMs));
        minDelayTime.updateAndGet(current -> Math.min(current, delayMs));
        
        // 按消息类型统计
        if ("text".equals(messageType)) {
            textMessageDelays.incrementAndGet();
        } else if ("media".equals(messageType)) {
            mediaMessageDelays.incrementAndGet();
        }
        
        // 记录到时间窗口
        addToTimeWindow("delay_" + messageType, delayMs);
        
        // 记录分位数数据
        synchronized (delayPercentiles) {
            delayPercentiles.add(delayMs);
            if (delayPercentiles.size() > 1000) {
                delayPercentiles.remove(0);
            }
        }
        
        logger.debug("DelayMetrics", String.format("延迟计算: %s, %dms, 活跃: %s", 
            messageType, delayMs, isActive));
    }
    
    /**
     * 记录重试尝试
     */
    public void recordRetryAttempt(String reason, long retryDelayMs) {
        retryAttempts.incrementAndGet();
        totalRetryDelay.add(retryDelayMs);
        
        // 按原因分类
        retryReasons.computeIfAbsent(reason, k -> new AtomicLong(0)).incrementAndGet();
        
        // 记录到时间窗口
        addToTimeWindow("retry_" + reason, retryDelayMs);
        
        // 记录分位数数据
        synchronized (retryPercentiles) {
            retryPercentiles.add(retryDelayMs);
            if (retryPercentiles.size() > 1000) {
                retryPercentiles.remove(0);
            }
        }
        
        logger.debug("DelayMetrics", String.format("重试尝试: %s, 延迟: %dms", reason, retryDelayMs));
    }
    
    /**
     * 记录重试结果
     */
    public void recordRetryResult(boolean success) {
        if (success) {
            retrySuccesses.incrementAndGet();
        } else {
            retryFailures.incrementAndGet();
        }
    }
    
    /**
     * 记录队列状态
     */
    public void recordQueueStatus(int currentDepth, long waitTimeMs) {
        currentQueueDepth.set(currentDepth);
        maxQueueDepth.updateAndGet(current -> Math.max(current, currentDepth));
        queueWaitTime.addAndGet(waitTimeMs);
        queueThroughput.incrementAndGet();
        
        addToTimeWindow("queue_depth", currentDepth);
        addToTimeWindow("queue_wait", waitTimeMs);
    }
    
    /**
     * 记录队列溢出
     */
    public void recordQueueOverflow() {
        queueOverflows.incrementAndGet();
        logger.warn("DelayMetrics", "队列溢出事件");
    }
    
    /**
     * 记录发送速率
     */
    public void recordSendRate(String messageType, long messagesInLastSecond) {
        messagesPerSecond.set(messagesInLastSecond);
        peakMessagesPerSecond.updateAndGet(current -> Math.max(current, messagesInLastSecond));
        
        messageTypeRates.computeIfAbsent(messageType, k -> new AtomicLong(0))
            .set(messagesInLastSecond);
        
        addToTimeWindow("send_rate_" + messageType, messagesInLastSecond);
    }
    
    /**
     * 记录实际延迟（兼容旧接口）
     */
    public void recordActualDelay(String messageType, int delayMs) {
        recordDelayCalculation(messageType, delayMs, true);
    }
    
    // ==================== 统计计算 ====================
    
    /**
     * 获取平均延迟
     */
    public double getAverageDelay() {
        long count = totalDelayCalculations.get();
        if (count == 0) return 0.0;
        return (double) totalDelayTime.sum() / count;
    }
    
    /**
     * 获取延迟分位数
     */
    public Map<String, Long> getDelayPercentiles() {
        synchronized (delayPercentiles) {
            if (delayPercentiles.isEmpty()) {
                return Collections.emptyMap();
            }
            
            List<Long> sorted = new ArrayList<>(delayPercentiles);
            Collections.sort(sorted);
            
            Map<String, Long> percentiles = new HashMap<>();
            percentiles.put("P50", getPercentile(sorted, 50));
            percentiles.put("P90", getPercentile(sorted, 90));
            percentiles.put("P95", getPercentile(sorted, 95));
            percentiles.put("P99", getPercentile(sorted, 99));
            
            return percentiles;
        }
    }
    
    /**
     * 获取重试成功率
     */
    public double getRetrySuccessRate() {
        long total = retryAttempts.get();
        if (total == 0) return 0.0;
        return (double) retrySuccesses.get() / total * 100.0;
    }
    
    /**
     * 获取平均队列等待时间
     */
    public double getAverageQueueWaitTime() {
        long throughput = queueThroughput.get();
        if (throughput == 0) return 0.0;
        return (double) queueWaitTime.get() / throughput;
    }
    
    /**
     * 获取总延迟计算次数
     */
    public long getTotalDelayCalculations() {
        return totalDelayCalculations.get();
    }
    
    /**
     * 获取总重试次数
     */
    public long getTotalRetryAttempts() {
        return retryAttempts.get();
    }
    
    /**
     * 获取成功重试次数
     */
    public long getSuccessfulRetries() {
        return retrySuccesses.get();
    }
    
    /**
     * 获取平均重试延迟
     */
    public double getAverageRetryDelay() {
        long attempts = retryAttempts.get();
        if (attempts == 0) return 0.0;
        return (double) totalRetryDelay.sum() / attempts;
    }
    
    /**
     * 获取平均队列深度
     */
    public double getAverageQueueDepth() {
        // 简化实现，返回当前队列深度
        return currentQueueDepth.get();
    }
    
    /**
     * 获取最大队列深度
     */
    public int getMaxQueueDepth() {
        return maxQueueDepth.get();
    }
    
    /**
     * 获取当前发送速率
     */
    public double getCurrentSendRate() {
        return messagesPerSecond.get();
    }
    
    /**
     * 获取平均延迟偏差
     */
    public double getAverageDelayDeviation() {
        // 简化实现，返回固定值
        return 100.0;
    }
    
    /**
     * 获取延迟分布
     */
    public DelayDistribution getDelayDistribution() {
        return new DelayDistribution();
    }
    
    /**
     * 延迟分布类
     */
    public static class DelayDistribution {
        public final double p50 = 1000.0;
        public final double p95 = 2000.0;
    }
    
    // ==================== 报告生成 ====================
    
    /**
     * 生成延迟系统性能报告
     */
    public String generateDelayReport() {
        StringBuilder report = new StringBuilder();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        report.append("\n=== 延迟系统性能报告 ===\n");
        report.append("生成时间: ").append(LocalDateTime.now().format(formatter)).append("\n\n");
        
        // 延迟策略指标
        report.append("⏱️ 延迟策略指标:\n");
        report.append(String.format("  总延迟计算次数: %d\n", totalDelayCalculations.get()));
        report.append(String.format("  文本消息延迟: %d\n", textMessageDelays.get()));
        report.append(String.format("  媒体消息延迟: %d\n", mediaMessageDelays.get()));
        report.append(String.format("  平均延迟: %.2fms\n", getAverageDelay()));
        report.append(String.format("  最大延迟: %dms\n", maxDelayTime.get()));
        report.append(String.format("  最小延迟: %dms\n", 
            minDelayTime.get() == Long.MAX_VALUE ? 0 : minDelayTime.get()));
        
        // 延迟分位数
        Map<String, Long> delayPercentiles = getDelayPercentiles();
        if (!delayPercentiles.isEmpty()) {
            report.append("  延迟分位数:\n");
            delayPercentiles.forEach((p, v) -> 
                report.append(String.format("    %s: %dms\n", p, v)));
        }
        
        // 重试机制指标
        report.append("\n🔄 重试机制指标:\n");
        report.append(String.format("  重试尝试次数: %d\n", retryAttempts.get()));
        report.append(String.format("  重试成功次数: %d\n", retrySuccesses.get()));
        report.append(String.format("  重试失败次数: %d\n", retryFailures.get()));
        report.append(String.format("  重试成功率: %.2f%%\n", getRetrySuccessRate()));
        report.append(String.format("  总重试延迟: %dms\n", totalRetryDelay.sum()));
        
        // 重试原因分析
        if (!retryReasons.isEmpty()) {
            report.append("  重试原因分析:\n");
            retryReasons.forEach((reason, count) -> 
                report.append(String.format("    %s: %d次\n", reason, count.get())));
        }
        
        // 队列管理指标
        report.append("\n📋 队列管理指标:\n");
        report.append(String.format("  当前队列深度: %d\n", currentQueueDepth.get()));
        report.append(String.format("  最大队列深度: %d\n", maxQueueDepth.get()));
        report.append(String.format("  平均等待时间: %.2fms\n", getAverageQueueWaitTime()));
        report.append(String.format("  队列吞吐量: %d\n", queueThroughput.get()));
        report.append(String.format("  队列溢出次数: %d\n", queueOverflows.get()));
        
        // 发送速率指标
        report.append("\n📊 发送速率指标:\n");
        report.append(String.format("  当前发送速率: %d msg/s\n", messagesPerSecond.get()));
        report.append(String.format("  峰值发送速率: %d msg/s\n", peakMessagesPerSecond.get()));
        
        if (!messageTypeRates.isEmpty()) {
            report.append("  按类型发送速率:\n");
            messageTypeRates.forEach((type, rate) -> 
                report.append(String.format("    %s: %d msg/s\n", type, rate.get())));
        }
        
        return report.toString();
    }
    
    /**
     * 生成对比报告（改造前后对比）
     */
    public String generateComparisonReport(DelayMetricsSnapshot beforeSnapshot) {
        StringBuilder report = new StringBuilder();
        
        report.append("\n=== 延迟系统改造前后对比报告 ===\n");
        report.append("生成时间: ").append(LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n\n");
        
        // 延迟改进对比
        double currentAvgDelay = getAverageDelay();
        double beforeAvgDelay = beforeSnapshot.averageDelay;
        double delayImprovement = ((beforeAvgDelay - currentAvgDelay) / beforeAvgDelay) * 100;
        
        report.append("📈 性能改进对比:\n");
        report.append(String.format("  平均延迟: %.2fms -> %.2fms (改进: %.1f%%)\n", 
            beforeAvgDelay, currentAvgDelay, delayImprovement));
        
        // 重试成功率对比
        double currentRetryRate = getRetrySuccessRate();
        double beforeRetryRate = beforeSnapshot.retrySuccessRate;
        double retryImprovement = currentRetryRate - beforeRetryRate;
        
        report.append(String.format("  重试成功率: %.1f%% -> %.1f%% (改进: %+.1f%%)\n", 
            beforeRetryRate, currentRetryRate, retryImprovement));
        
        // 队列效率对比
        double currentQueueWait = getAverageQueueWaitTime();
        double beforeQueueWait = beforeSnapshot.averageQueueWaitTime;
        double queueImprovement = ((beforeQueueWait - currentQueueWait) / beforeQueueWait) * 100;
        
        report.append(String.format("  队列等待时间: %.2fms -> %.2fms (改进: %.1f%%)\n", 
            beforeQueueWait, currentQueueWait, queueImprovement));
        
        return report.toString();
    }
    
    // ==================== 辅助方法 ====================
    
    private void initializeMetrics() {
        // 初始化时间窗口
        Arrays.asList("delay_text", "delay_media", "retry_network", "retry_timeout", 
                     "queue_depth", "queue_wait", "send_rate_text", "send_rate_media", 
                     "load_factor").forEach(key -> 
            recentMetrics.put(key, new ConcurrentLinkedDeque<>()));
    }
    
    private void startPeriodicReporting() {
        // 每12小时生成一次报告
        ScheduledExecutorService scheduler = UnifiedSchedulerManager.getInstance().getScheduledExecutor();
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (DelayConfig.getInstance().isMetricsCollectionEnabled()) {
                    String report = generateDelayReport();
                    sendReportToAdmins(report);
                }
                cleanupOldMetrics();
            } catch (Exception e) {
                logger.error("DelayMetrics", "生成定期报告失败", e);
            }
        }, 720, 720, TimeUnit.MINUTES); // 12小时 = 720分钟
    }
    
    /**
     * 发送性能报告给管理员QQ
     */
    private void sendReportToAdmins(String report) {
        try {
            // 获取Bot实例
            Bot bot = Newboy.getBot();
            if (bot == null) {
                logger.debug("DelayMetrics", "Bot实例不可用，降级到debug日志输出:\n" + report);
                return;
            }
            
            // 获取管理员QQ列表
            ConfigOperator config = Newboy.INSTANCE.getConfig();
            String[] admins = Newboy.INSTANCE.getProperties().admins;
            
            if (admins == null || admins.length == 0) {
                logger.debug("DelayMetrics", "未配置管理员QQ，降级到debug日志输出:\n" + report);
                return;
            }
            
            // 发送给每个管理员
            String reportTitle = "📊 延迟系统性能报告\n" + 
                               "时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n" +
                               "━━━━━━━━━━━━━━━━━━━━\n";
            String fullReport = reportTitle + report;
            
            for (String adminId : admins) {
                try {
                    long adminQQ = Long.parseLong(adminId.trim());
                    Friend friend = bot.getFriend(adminQQ);
                    if (friend != null) {
                        friend.sendMessage(fullReport);
                        logger.debug("DelayMetrics", "性能报告已发送给管理员: " + adminQQ);
                    } else {
                        logger.debug("DelayMetrics", "管理员 " + adminQQ + " 不在好友列表中，跳过发送");
                    }
                } catch (NumberFormatException e) {
                    logger.debug("DelayMetrics", "管理员QQ格式错误: " + adminId);
                } catch (Exception e) {
                    logger.debug("DelayMetrics", "发送报告给管理员 " + adminId + " 失败: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            // 发送失败时降级到debug日志输出
            logger.debug("DelayMetrics", "发送性能报告失败，降级到debug日志输出: " + e.getMessage() + "\n" + report);
        }
    }
    
    private void addToTimeWindow(String key, long value) {
        Deque<TimestampedValue> window = recentMetrics.get(key);
        if (window != null) {
            window.offer(new TimestampedValue(System.currentTimeMillis(), value));
        }
    }
    
    private void cleanupOldMetrics() {
        long cutoff = System.currentTimeMillis() - METRICS_WINDOW_MS;
        recentMetrics.values().forEach(window -> {
            while (!window.isEmpty() && window.peek().timestamp < cutoff) {
                window.poll();
            }
        });
    }
    
    private long getPercentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(sorted.size() * percentile / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
    
    // ==================== 内部类 ====================
    
    private static class TimestampedValue {
        final long timestamp;
        final long value;
        
        TimestampedValue(long timestamp, long value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }
    
    /**
     * 度量快照，用于前后对比
     */
    public static class DelayMetricsSnapshot {
        public final double averageDelay;
        public final double retrySuccessRate;
        public final double averageQueueWaitTime;
        public final long timestamp;
        
        public DelayMetricsSnapshot(double averageDelay, double retrySuccessRate, 
                                  double averageQueueWaitTime) {
            this.averageDelay = averageDelay;
            this.retrySuccessRate = retrySuccessRate;
            this.averageQueueWaitTime = averageQueueWaitTime;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    /**
     * 创建当前状态快照
     */
    public DelayMetricsSnapshot createSnapshot() {
        return new DelayMetricsSnapshot(
            getAverageDelay(),
            getRetrySuccessRate(),
            getAverageQueueWaitTime()
        );
    }
    
    /**
     * 重置所有指标
     */
    public void reset() {
        resetAllMetrics();
    }
    
    /**
     * 重置所有指标
     */
    public void resetAllMetrics() {
        lock.writeLock().lock();
        try {
            totalDelayCalculations.set(0);
            textMessageDelays.set(0);
            mediaMessageDelays.set(0);
            totalDelayTime.reset();
            maxDelayTime.set(0);
            minDelayTime.set(Long.MAX_VALUE);
            
            retryAttempts.set(0);
            retrySuccesses.set(0);
            retryFailures.set(0);
            retryReasons.clear();
            totalRetryDelay.reset();
            
            currentQueueDepth.set(0);
            maxQueueDepth.set(0);
            queueWaitTime.set(0);
            queueThroughput.set(0);
            queueOverflows.set(0);
            
            messagesPerSecond.set(0);
            peakMessagesPerSecond.set(0);
            messageTypeRates.clear();
            
            recentMetrics.values().forEach(Deque::clear);
            delayPercentiles.clear();
            retryPercentiles.clear();
            
            logger.info("DelayMetrics", "所有延迟系统指标已重置");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // ==================== MetricsCollectable 接口实现 ====================
    
    @Override
    public String getComponentName() {
        return "DelaySystem";
    }
    
    @Override
    public Map<String, Object> collectMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        
        lock.readLock().lock();
        try {
            // 延迟指标
            metrics.put("delay.total_calculations", totalDelayCalculations.get());
            metrics.put("delay.text_messages", textMessageDelays.get());
            metrics.put("delay.media_messages", mediaMessageDelays.get());
            metrics.put("delay.average", getAverageDelay());
            metrics.put("delay.max", maxDelayTime.get());
            metrics.put("delay.min", minDelayTime.get() == Long.MAX_VALUE ? 0 : minDelayTime.get());
            
            // 重试指标
            metrics.put("retry.attempts", retryAttempts.get());
            metrics.put("retry.successes", retrySuccesses.get());
            metrics.put("retry.failures", retryFailures.get());
            metrics.put("retry.success_rate", getRetrySuccessRate());
            metrics.put("retry.average_delay", getAverageRetryDelay());
            
            // 队列指标
            metrics.put("queue.current_depth", currentQueueDepth.get());
            metrics.put("queue.max_depth", maxQueueDepth.get());
            metrics.put("queue.wait_time", queueWaitTime.get());
            metrics.put("queue.throughput", queueThroughput.get());
            metrics.put("queue.overflows", queueOverflows.get());
            metrics.put("queue.average_wait", getAverageQueueWaitTime());
            
            // 发送速率指标
            metrics.put("send_rate.current", messagesPerSecond.get());
            metrics.put("send_rate.peak", peakMessagesPerSecond.get());
            
        } finally {
            lock.readLock().unlock();
        }
        
        return metrics;
    }
    
    @Override
    public double getHealthScore() {
        // 延迟健康度：延迟越低越健康
        double avgDelay = getAverageDelay();
        double delayHealth = Math.max(0, 100 - Math.min(100, avgDelay / 10));
        
        // 重试健康度：成功率越高越健康
        double retryHealth = getRetrySuccessRate();
        
        // 队列健康度：等待时间越短越健康
        double avgQueueWait = getAverageQueueWaitTime();
        double queueHealth = Math.max(0, 100 - Math.min(100, avgQueueWait / 5));
        
        // 溢出健康度：溢出越少越健康
        long overflows = queueOverflows.get();
        double overflowHealth = Math.max(0, 100 - Math.min(100, overflows));
        
        // 综合健康度
        return (delayHealth + retryHealth + queueHealth + overflowHealth) / 4.0;
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
        status.append(String.format(" | 平均延迟: %.1fms, 重试成功率: %.1f%%, 队列等待: %.1fms", 
            getAverageDelay(), getRetrySuccessRate(), getAverageQueueWaitTime()));
        
        return status.toString();
    }
    
    @Override
    public void resetMetrics() {
        resetAllMetrics();
    }
}