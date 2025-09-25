package net.luffy.util.delay;

import net.luffy.util.UnifiedSchedulerManager;
import net.luffy.util.UnifiedLogger;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 统一延迟服务 - 替换项目中分散的延迟实现
 * 提供统一的异步延迟机制，避免直接使用 CompletableFuture.delayedExecutor
 */
public class UnifiedDelayService {
    private static final UnifiedLogger logger = UnifiedLogger.getInstance();
    private static volatile UnifiedDelayService instance;
    
    private final ScheduledExecutorService delayExecutor;
    
    private UnifiedDelayService() {
        this.delayExecutor = UnifiedSchedulerManager.getInstance().getScheduledExecutor();
    }
    
    public static UnifiedDelayService getInstance() {
        if (instance == null) {
            synchronized (UnifiedDelayService.class) {
                if (instance == null) {
                    instance = new UnifiedDelayService();
                }
            }
        }
        return instance;
    }
    
    /**
     * 异步延迟执行
     * @param delayMs 延迟毫秒数
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> delayAsync(long delayMs) {
        return delayAsync(delayMs, "general");
    }
    
    /**
     * 异步延迟执行（带类型标识）
     * @param delayMs 延迟毫秒数
     * @param delayType 延迟类型（用于度量收集）
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> delayAsync(long delayMs, String delayType) {
        if (delayMs <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        
        // 记录延迟度量
        if (DelayConfig.getInstance().isMetricsCollectionEnabled()) {
            DelayMetricsCollector.getInstance().recordDelayCalculation(delayType, delayMs, true);
        }
        
        CompletableFuture<Void> future = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();
        
        delayExecutor.schedule(() -> {
            try {
                future.complete(null);
                
                // 记录实际延迟时间
                if (DelayConfig.getInstance().isMetricsCollectionEnabled()) {
                    long actualDelay = System.currentTimeMillis() - startTime;
                    if (Math.abs(actualDelay - delayMs) > 100) { // 如果实际延迟与预期差异超过100ms
                        logger.debug("UnifiedDelayService", 
                            String.format("延迟时间偏差: 预期%dms, 实际%dms, 类型: %s", 
                                delayMs, actualDelay, delayType));
                    }
                }
            } catch (Exception e) {
                logger.error("UnifiedDelayService", "Delay execution completed with exception", e);
                future.completeExceptionally(e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        
        return future;
    }
    
    /**
     * 异步延迟执行（Duration版本）
     * @param duration 延迟时长
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> delayAsync(Duration duration) {
        return delayAsync(duration.toMillis());
    }
    
    /**
     * 带指数退避的延迟
     * @param baseDelayMs 基础延迟毫秒数
     * @param retryCount 重试次数（从0开始）
     * @param maxDelayMs 最大延迟毫秒数
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> delayWithExponentialBackoff(long baseDelayMs, int retryCount, long maxDelayMs) {
        return delayWithExponentialBackoff(baseDelayMs, retryCount, maxDelayMs, "retry");
    }
    
    /**
     * 带指数退避的延迟（带类型标识）
     * @param baseDelayMs 基础延迟毫秒数
     * @param retryCount 重试次数（从0开始）
     * @param maxDelayMs 最大延迟毫秒数
     * @param retryReason 重试原因（用于度量收集）
     * @return CompletableFuture<Void>
     */
    public CompletableFuture<Void> delayWithExponentialBackoff(long baseDelayMs, int retryCount, long maxDelayMs, String retryReason) {
        long delayMs = Math.min(baseDelayMs * (1L << retryCount), maxDelayMs);
        
        // 记录重试度量
        if (DelayConfig.getInstance().isMetricsCollectionEnabled()) {
            DelayMetricsCollector.getInstance().recordRetryAttempt(retryReason, delayMs);
        }
        
        return delayAsync(delayMs, "exponential_backoff_" + retryReason);
    }
    
    /**
     * 创建延迟执行器（用于替换 CompletableFuture.delayedExecutor）
     * @param delayMs 延迟毫秒数
     * @return ScheduledExecutorService
     */
    public ScheduledExecutorService createDelayedExecutor(long delayMs) {
        // 返回一个包装的执行器，确保使用统一的调度器
        return delayExecutor;
    }
    
    /**
     * 获取底层调度器（谨慎使用）
     * @return ScheduledExecutorService
     */
    public ScheduledExecutorService getScheduler() {
        return delayExecutor;
    }
}