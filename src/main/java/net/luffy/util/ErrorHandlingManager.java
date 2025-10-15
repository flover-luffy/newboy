package net.luffy.util;

import net.luffy.Newboy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.util.function.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


/**
 * 错误处理与恢复管理器
 * 提供智能错误处理、重试机制、断路器模式和系统恢复功能
 */
public class ErrorHandlingManager {
    
    private static final ErrorHandlingManager INSTANCE = new ErrorHandlingManager();
    
    // 重试配置
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    
    // 断路器配置
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final long DEFAULT_TIMEOUT_MS = 60000; // 1分钟
    private static final int DEFAULT_SUCCESS_THRESHOLD = 3;
    
    // 错误统计
    private final Map<String, ErrorStats> errorStats = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Queue<ErrorRecord> recentErrors = new ConcurrentLinkedQueue<>();
    
    // 恢复策略
    private final Map<Class<? extends Exception>, RecoveryStrategy> recoveryStrategies = new ConcurrentHashMap<>();
    private final Map<Class<? extends Exception>, AsyncRecoveryStrategy> asyncRecoveryStrategies = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Boolean>> healthChecks = new ConcurrentHashMap<>();
    
    // 告警系统
    private final Set<String> activeAlerts = ConcurrentHashMap.newKeySet();
    private final AtomicReference<ErrorAlertCallback> alertCallback = new AtomicReference<>();
    
    // 统计计数器
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong totalRetries = new AtomicLong(0);
    private final AtomicLong totalRecoveries = new AtomicLong(0);
    
    // 使用统一调度器管理器
    private final ScheduledExecutorService delayScheduler = UnifiedSchedulerManager.getInstance().getScheduledExecutor();
    
    private ErrorHandlingManager() {
        // 初始化默认恢复策略
        initializeDefaultRecoveryStrategies();
        // 启动错误清理任务
        startErrorCleanupTask();
    }
    
    public static ErrorHandlingManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 执行带重试的操作
     */
    public <T> T executeWithRetry(String operationName, Supplier<T> operation) {
        return executeWithRetry(operationName, operation, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_DELAY_MS);
    }
    
    /**
     * 执行带重试的操作（异步版本）
     */
    public <T> CompletableFuture<T> executeWithRetryAsync(String operationName, Supplier<T> operation, 
                                                         int maxRetries, long retryDelayMs) {
        return executeWithRetryAsyncInternal(operationName, operation, maxRetries, retryDelayMs, 0, null);
    }
    
    private <T> CompletableFuture<T> executeWithRetryAsyncInternal(String operationName, Supplier<T> operation,
                                                                  int maxRetries, long retryDelayMs, 
                                                                  int attempt, Exception lastException) {
        try {
            T result = operation.get();
            
            if (attempt > 0) {
                recordSuccessAfterRetry(operationName, attempt);
            }
            
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            recordError(operationName, e, attempt);
            
            if (attempt < maxRetries) {
                totalRetries.incrementAndGet();
                
                // 计算退避延迟
                long delay = calculateBackoffDelay(retryDelayMs, attempt);
                
                // 使用统一日志记录器记录重试信息
                UnifiedLogger.getInstance().info("ErrorHandling", 
                    String.format("[错误处理] 操作 '%s' 第 %d 次重试，%dms 后重试", 
                        operationName, attempt + 1, delay));
                
                // 异步延迟后重试
                return CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }).thenCompose(v -> executeWithRetryAsyncInternal(
                        operationName, operation, maxRetries, retryDelayMs, attempt + 1, e));
            } else {
                // 所有重试都失败了
                recordFinalFailure(operationName, e, maxRetries);
                CompletableFuture<T> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(new RuntimeException(
                    String.format("操作 '%s' 在 %d 次重试后仍然失败", operationName, maxRetries), e));
                return failedFuture;
            }
        }
    }
    
    /**
     * 执行带重试的操作（同步版本，保持向后兼容）
     */
    public <T> T executeWithRetry(String operationName, Supplier<T> operation, 
                                 int maxRetries, long retryDelayMs) {
        try {
            return executeWithRetryAsync(operationName, operation, maxRetries, retryDelayMs).get();
        } catch (Exception e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw new RuntimeException("异步重试执行失败", e);
        }
    }
    
    /**
     * 使用断路器执行操作
     */
    public <T> T executeWithCircuitBreaker(String circuitName, Supplier<T> operation) {
        CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(circuitName);
        return circuitBreaker.execute(operation);
    }
    
    /**
     * 执行带恢复策略的操作
     */
    public <T> T executeWithRecovery(String operationName, Supplier<T> operation, 
                                    Class<? extends Exception> exceptionType) {
        try {
            return operation.get();
        } catch (Exception e) {
            if (exceptionType.isInstance(e)) {
                RecoveryStrategy strategy = recoveryStrategies.get(exceptionType);
                if (strategy != null) {
                    UnifiedLogger.getInstance().info("ErrorHandling", 
                        String.format("[错误恢复] 尝试恢复操作 '%s'", operationName));
                    
                    if (strategy.recover(e)) {
                        totalRecoveries.incrementAndGet();
                        // 恢复成功，重新执行
                        return operation.get();
                    }
                }
            }
            throw e;
        }
    }
    
    /**
     * 记录错误
     */
    public void recordError(String context, Exception exception) {
        recordError(context, exception, 0);
    }
    
    private void recordError(String context, Exception exception, int attemptNumber) {
        totalErrors.incrementAndGet();
        
        // 更新错误统计
        ErrorStats stats = errorStats.computeIfAbsent(context, k -> new ErrorStats());
        stats.recordError(exception.getClass().getSimpleName());
        
        // 记录错误详情
        ErrorRecord record = new ErrorRecord(
            context, 
            exception, 
            attemptNumber, 
            LocalDateTime.now()
        );
        
        recentErrors.offer(record);
        
        // 限制队列大小
        while (recentErrors.size() > 1000) {
            recentErrors.poll();
        }
        
        // 检查是否需要触发告警
        checkErrorAlerts(context, exception);
        
        // 记录日志
        if (attemptNumber == 0) {
            UnifiedLogger.getInstance().error("ErrorHandling", 
                String.format("[错误处理] 上下文: %s, 异常: %s", context, exception.getMessage()), 
                exception);
        }
    }
    
    /**
     * 注册恢复策略
     */
    public void registerRecoveryStrategy(Class<? extends Exception> exceptionType, 
                                        RecoveryStrategy strategy) {
        recoveryStrategies.put(exceptionType, strategy);
        // 注册恢复策略
    }
    
    /**
     * 注册异步恢复策略
     */
    public void registerAsyncRecoveryStrategy(Class<? extends Exception> exceptionType, 
                                            AsyncRecoveryStrategy strategy) {
        asyncRecoveryStrategies.put(exceptionType, strategy);
        // 注册异步恢复策略
    }
    
    /**
     * 注册健康检查
     */
    public void registerHealthCheck(String name, Supplier<Boolean> healthCheck) {
        healthChecks.put(name, healthCheck);
    }
    
    /**
     * 执行健康检查
     */
    public Map<String, Boolean> performHealthChecks() {
        Map<String, Boolean> results = new HashMap<>();
        
        for (Map.Entry<String, Supplier<Boolean>> entry : healthChecks.entrySet()) {
            try {
                boolean healthy = entry.getValue().get();
                results.put(entry.getKey(), healthy);
                
                if (!healthy) {
                    // 健康检查失败
                }
            } catch (Exception e) {
                results.put(entry.getKey(), false);
                UnifiedLogger.getInstance().error("ErrorHandling", 
                    String.format("[健康检查] %s 检查异常", entry.getKey()), e);
            }
        }
        
        return results;
    }
    
    /**
     * 获取错误统计报告
     */
    public String getErrorReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("🚨 错误处理监控报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
        
        // 总体统计
        report.append("📊 总体统计:\n");
        report.append(String.format("  总错误数: %d\n", totalErrors.get()));
        report.append(String.format("  总重试数: %d\n", totalRetries.get()));
        report.append(String.format("  总恢复数: %d\n", totalRecoveries.get()));
        report.append(String.format("  断路器数: %d\n", circuitBreakers.size()));
        
        // 错误统计Top 10
        report.append("\n🔥 错误热点 (Top 10):\n");
        errorStats.entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e2.getValue().getTotalCount(), e1.getValue().getTotalCount()))
            .limit(10)
            .forEach(entry -> {
                ErrorStats stats = entry.getValue();
                report.append(String.format("  %s: %d次 (最近: %s)\n",
                    entry.getKey(), stats.getTotalCount(), stats.getLastErrorTime()));
            });
        
        // 断路器状态
        report.append("\n⚡ 断路器状态:\n");
        for (Map.Entry<String, CircuitBreaker> entry : circuitBreakers.entrySet()) {
            CircuitBreaker cb = entry.getValue();
            report.append(String.format("  %s: %s (失败: %d/%d)\n",
                entry.getKey(), cb.getState(), cb.getFailureCount(), cb.getFailureThreshold()));
        }
        
        // 健康检查
        Map<String, Boolean> healthResults = performHealthChecks();
        if (!healthResults.isEmpty()) {
            report.append("\n💚 健康检查:\n");
            healthResults.forEach((name, healthy) -> 
                report.append(String.format("  %s: %s\n", name, healthy ? "✅ 健康" : "❌ 异常")));
        }
        
        // 最近错误
        report.append("\n📝 最近错误 (最新5条):\n");
        recentErrors.stream()
            .sorted((e1, e2) -> e2.getTimestamp().compareTo(e1.getTimestamp()))
            .limit(5)
            .forEach(error -> report.append(String.format("  [%s] %s: %s\n",
                error.getTimestamp().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                error.getContext(),
                error.getException().getMessage())));
        
        return report.toString();
    }
    
    /**
     * 设置错误告警回调
     */
    public void setErrorAlertCallback(ErrorAlertCallback callback) {
        this.alertCallback.set(callback);
    }
    
    /**
     * 清理过期数据
     */
    public void cleanup() {
        // 清理过期的错误记录（保留最近1小时）
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        recentErrors.removeIf(error -> error.getTimestamp().isBefore(cutoff));
        
        // 重置断路器（如果需要）
        circuitBreakers.values().forEach(CircuitBreaker::tryReset);
        
        // 清理过期告警
        activeAlerts.removeIf(alert -> alert.contains("[已解决]"));
    }
    
    // 私有方法
    private void initializeDefaultRecoveryStrategies() {
        // 网络连接异常恢复策略（异步版本）
        registerAsyncRecoveryStrategy(java.net.ConnectException.class, (exception) -> {
            // 尝试重新建立网络连接
            return CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(2000); // 异步等待2秒
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).thenApply(v -> true)
                .exceptionally(e -> false);
        });
        
        // 内存不足恢复策略（异步版本）
        registerAsyncRecoveryStrategy(RuntimeException.class, (exception) -> {
            if (exception.getCause() instanceof OutOfMemoryError) {
                // 检测到内存不足，尝试清理缓存
                try {
                    // 触发缓存清理
                    SmartCacheManager.getInstance().performMemoryPressureCleanup();
                    System.gc();
                    // 异步等待1秒
                    return CompletableFuture.runAsync(() -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).thenApply(v -> true)
                        .exceptionally(e -> false);
                } catch (Exception e) {
                    return CompletableFuture.completedFuture(false);
                }
            }
            return CompletableFuture.completedFuture(false);
        });
        
        // 为了向后兼容，保留同步版本的恢复策略
        registerRecoveryStrategy(java.net.ConnectException.class, (exception) -> {
            try {
                AsyncRecoveryStrategy asyncStrategy = asyncRecoveryStrategies.get(java.net.ConnectException.class);
                if (asyncStrategy != null) {
                    return asyncStrategy.recover(exception).get();
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        });
        
        registerRecoveryStrategy(RuntimeException.class, (exception) -> {
            if (exception.getCause() instanceof OutOfMemoryError) {
                try {
                    SmartCacheManager.getInstance().performMemoryPressureCleanup();
                    System.gc();
                    Thread.sleep(1000); // 同步等待1秒
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            return false;
        });
    }
    
    private void startErrorCleanupTask() {
        UnifiedSchedulerManager.getInstance().scheduleCleanupTask(
            this::cleanup, 10 * 60 * 1000, 10 * 60 * 1000); // 每10分钟清理一次
    }
    
    private long calculateBackoffDelay(long baseDelay, int attempt) {
        return (long) (baseDelay * Math.pow(DEFAULT_BACKOFF_MULTIPLIER, attempt));
    }
    
    // delayAsync方法已移除，统一使用UnifiedDelayService.getInstance().delayAsync()
    
    private void recordSuccessAfterRetry(String operationName, int attempts) {
        // 操作重试成功
    }
    
    private void recordFinalFailure(String operationName, Exception exception, int maxRetries) {
        UnifiedLogger.getInstance().error("ErrorHandling", 
            String.format("[错误处理] 操作 '%s' 最终失败，已重试 %d 次", operationName, maxRetries));
        
        // 触发严重错误告警
        triggerAlert("CRITICAL_FAILURE", 
            String.format("操作 '%s' 在 %d 次重试后仍然失败: %s", 
                operationName, maxRetries, exception.getMessage()));
    }
    
    private CircuitBreaker getOrCreateCircuitBreaker(String name) {
        return circuitBreakers.computeIfAbsent(name, k -> 
            new CircuitBreaker(name, DEFAULT_FAILURE_THRESHOLD, DEFAULT_TIMEOUT_MS, DEFAULT_SUCCESS_THRESHOLD));
    }
    
    private void checkErrorAlerts(String context, Exception exception) {
        ErrorStats stats = errorStats.get(context);
        if (stats != null && stats.getTotalCount() % 10 == 0) { // 每10个错误触发一次告警
            triggerAlert("HIGH_ERROR_RATE", 
                String.format("上下文 '%s' 错误频率过高: %d次", context, stats.getTotalCount()));
        }
    }
    
    private void triggerAlert(String alertType, String message) {
        String alertKey = alertType + "_" + System.currentTimeMillis();
        activeAlerts.add(alertKey + ": " + message);
        
        ErrorAlertCallback callback = alertCallback.get();
        if (callback != null) {
            callback.onErrorAlert(alertType, message);
        }
        
        UnifiedLogger.getInstance().error("ErrorHandling", String.format("[错误告警] %s: %s", alertType, message));
    }
    
    // 内部类：错误统计
    private static class ErrorStats {
        private final AtomicLong totalCount = new AtomicLong(0);
        private final Map<String, AtomicLong> errorTypes = new ConcurrentHashMap<>();
        private volatile LocalDateTime lastErrorTime;
        
        public void recordError(String errorType) {
            totalCount.incrementAndGet();
            errorTypes.computeIfAbsent(errorType, k -> new AtomicLong(0)).incrementAndGet();
            lastErrorTime = LocalDateTime.now();
        }
        
        public long getTotalCount() {
            return totalCount.get();
        }
        
        public LocalDateTime getLastErrorTime() {
            return lastErrorTime;
        }
        
        public Map<String, Long> getErrorTypes() {
            return errorTypes.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().get()
                ));
        }
    }
    
    // 内部类：断路器
    private static class CircuitBreaker {
        private final String name;
        private final int failureThreshold;
        private final long timeoutMs;
        private final int successThreshold;
        
        private volatile State state = State.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private volatile long lastFailureTime = 0;
        
        public CircuitBreaker(String name, int failureThreshold, long timeoutMs, int successThreshold) {
            this.name = name;
            this.failureThreshold = failureThreshold;
            this.timeoutMs = timeoutMs;
            this.successThreshold = successThreshold;
        }
        
        public <T> T execute(Supplier<T> operation) {
            if (state == State.OPEN) {
                if (System.currentTimeMillis() - lastFailureTime > timeoutMs) {
                    state = State.HALF_OPEN;
                    successCount.set(0);
                } else {
                    throw new RuntimeException("断路器开启，拒绝执行: " + name);
                }
            }
            
            try {
                T result = operation.get();
                onSuccess();
                return result;
            } catch (Exception e) {
                onFailure();
                throw e;
            }
        }
        
        private void onSuccess() {
            if (state == State.HALF_OPEN) {
                if (successCount.incrementAndGet() >= successThreshold) {
                    state = State.CLOSED;
                    failureCount.set(0);
                }
            } else {
                failureCount.set(0);
            }
        }
        
        private void onFailure() {
            lastFailureTime = System.currentTimeMillis();
            
            if (failureCount.incrementAndGet() >= failureThreshold) {
                state = State.OPEN;
            }
        }
        
        public void tryReset() {
            if (state == State.OPEN && 
                System.currentTimeMillis() - lastFailureTime > timeoutMs * 2) {
                state = State.CLOSED;
                failureCount.set(0);
            }
        }
        
        public State getState() { return state; }
        public int getFailureCount() { return failureCount.get(); }
        public int getFailureThreshold() { return failureThreshold; }
        
        public enum State {
            CLOSED, OPEN, HALF_OPEN
        }
    }
    
    // 内部类：错误记录
    private static class ErrorRecord {
        private final String context;
        private final Exception exception;
        private final int attemptNumber;
        private final LocalDateTime timestamp;
        
        public ErrorRecord(String context, Exception exception, int attemptNumber, LocalDateTime timestamp) {
            this.context = context;
            this.exception = exception;
            this.attemptNumber = attemptNumber;
            this.timestamp = timestamp;
        }
        
        public String getContext() { return context; }
        public Exception getException() { return exception; }
        public int getAttemptNumber() { return attemptNumber; }
        public LocalDateTime getTimestamp() { return timestamp; }
    }
    
    // 接口定义
    @FunctionalInterface
    public interface RecoveryStrategy {
        boolean recover(Exception exception);
    }
    
    @FunctionalInterface
    public interface AsyncRecoveryStrategy {
        CompletableFuture<Boolean> recover(Exception exception);
    }
    
    @FunctionalInterface
    public interface ErrorAlertCallback {
        void onErrorAlert(String alertType, String message);
    }
}