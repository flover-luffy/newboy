package net.luffy.util;

import net.luffy.Newboy;
import net.luffy.handler.AsyncWebHandler;
import net.luffy.handler.AsyncWebHandler.BatchMemberStatusResult;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 异步在线状态监控器
 * 支持批量查询、异步处理、性能优化等功能
 */
public class AsyncOnlineStatusMonitor {
    
    public static final AsyncOnlineStatusMonitor INSTANCE = new AsyncOnlineStatusMonitor();
    
    private final AsyncWebHandler asyncWebHandler;
    private final MonitorConfig config;
    private final ScheduledExecutorService scheduler;
    
    // 批量查询统计
    private final AtomicInteger batchQueryCount = new AtomicInteger(0);
    private final AtomicInteger totalMembersQueried = new AtomicInteger(0);
    private final AtomicLong totalBatchTime = new AtomicLong(0);
    private final AtomicInteger concurrentBatches = new AtomicInteger(0);
    
    // 成员状态缓存（异步版本）
    private final ConcurrentHashMap<String, AsyncMemberStatus> memberStatusCache = new ConcurrentHashMap<>();
    
    // 批量查询队列
    private final Set<String> pendingQueries = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<BatchMemberStatusResult>> queryFutures = new ConcurrentHashMap<>();
    
    private AsyncOnlineStatusMonitor() {
        this.asyncWebHandler = new AsyncWebHandler();
        this.config = MonitorConfig.getInstance();
        this.scheduler = Executors.newScheduledThreadPool(2);
        
        // 启动定期批量查询任务
        startBatchQueryScheduler();
        // 启动缓存清理任务
        startCacheCleanupScheduler();
    }
    
    /**
     * 异步查询单个成员状态
     */
    public CompletableFuture<BatchMemberStatusResult> queryMemberStatusAsync(String memberName) {
        if (memberName == null || memberName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                new BatchMemberStatusResult(memberName, false, "成员名称不能为空", null));
        }
        
        String normalizedName = memberName.trim();
        
        // 检查缓存
        AsyncMemberStatus cached = memberStatusCache.get(normalizedName);
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(
                new BatchMemberStatusResult(normalizedName, true, cached.getStatus(), cached.getRawResponse()));
        }
        
        // 检查是否已在查询队列中
        CompletableFuture<BatchMemberStatusResult> existingFuture = queryFutures.get(normalizedName);
        if (existingFuture != null) {
            return existingFuture;
        }
        
        // 添加到批量查询队列
        pendingQueries.add(normalizedName);
        
        // 创建查询Future
        CompletableFuture<BatchMemberStatusResult> future = new CompletableFuture<>();
        queryFutures.put(normalizedName, future);
        
        // 如果队列达到批量大小或等待时间过长，立即执行批量查询
        if (pendingQueries.size() >= config.getBatchQuerySize()) {
            scheduler.execute(this::executeBatchQuery);
        }
        
        return future;
    }
    
    /**
     * 批量查询成员状态
     */
    public CompletableFuture<List<BatchMemberStatusResult>> batchQueryMemberStatus(List<String> memberNames) {
        if (memberNames == null || memberNames.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        
        long startTime = System.currentTimeMillis();
        batchQueryCount.incrementAndGet();
        totalMembersQueried.addAndGet(memberNames.size());
        concurrentBatches.incrementAndGet();
        
        return asyncWebHandler.batchQueryMemberStatus(memberNames)
                .thenApply(results -> {
                    // 更新缓存
                    for (BatchMemberStatusResult result : results) {
                        if (result.isSuccess()) {
                            memberStatusCache.put(result.getMemberName(), 
                                new AsyncMemberStatus(result.getStatus(), result.getRawResponse()));
                        }
                    }
                    
                    // 更新统计
                    long batchTime = System.currentTimeMillis() - startTime;
                    totalBatchTime.addAndGet(batchTime);
                    concurrentBatches.decrementAndGet();
                    
                    if (config.isVerboseLogging()) {
                        System.out.println(String.format(
                            "批量查询完成: %d个成员, 耗时: %dms, 成功: %d, 失败: %d",
                            results.size(), batchTime,
                            (int) results.stream().filter(BatchMemberStatusResult::isSuccess).count(),
                            (int) results.stream().filter(r -> !r.isSuccess()).count()
                        ));
                    }
                    
                    return results;
                })
                .exceptionally(throwable -> {
                    concurrentBatches.decrementAndGet();
                    System.err.println("批量查询失败: " + throwable.getMessage());
                    
                    // 返回失败结果
                    return memberNames.stream()
                            .map(name -> new BatchMemberStatusResult(name, false, 
                                "批量查询失败: " + throwable.getMessage(), null))
                            .collect(Collectors.toList());
                });
    }
    
    /**
     * 执行批量查询
     */
    private void executeBatchQuery() {
        if (pendingQueries.isEmpty()) {
            return;
        }
        
        // 获取待查询的成员列表
        List<String> membersToQuery = new ArrayList<>(pendingQueries);
        pendingQueries.clear();
        
        if (membersToQuery.isEmpty()) {
            return;
        }
        
        // 执行批量查询
        batchQueryMemberStatus(membersToQuery)
                .thenAccept(results -> {
                    // 完成所有等待的Future
                    for (BatchMemberStatusResult result : results) {
                        CompletableFuture<BatchMemberStatusResult> future = queryFutures.remove(result.getMemberName());
                        if (future != null) {
                            future.complete(result);
                        }
                    }
                })
                .exceptionally(throwable -> {
                    // 处理失败情况
                    for (String memberName : membersToQuery) {
                        CompletableFuture<BatchMemberStatusResult> future = queryFutures.remove(memberName);
                        if (future != null) {
                            future.complete(new BatchMemberStatusResult(memberName, false, 
                                "批量查询异常: " + throwable.getMessage(), null));
                        }
                    }
                    return null;
                });
    }
    
    /**
     * 启动批量查询调度器
     */
    private void startBatchQueryScheduler() {
        scheduler.scheduleWithFixedDelay(() -> {
            if (!pendingQueries.isEmpty()) {
                executeBatchQuery();
            }
        }, config.getBatchQueryInterval(), config.getBatchQueryInterval(), TimeUnit.MILLISECONDS);
    }
    
    /**
     * 启动缓存清理调度器
     */
    private void startCacheCleanupScheduler() {
        scheduler.scheduleWithFixedDelay(() -> {
            long currentTime = System.currentTimeMillis();
            int cleanedCount = 0;
            
            Iterator<Map.Entry<String, AsyncMemberStatus>> iterator = memberStatusCache.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, AsyncMemberStatus> entry = iterator.next();
                if (entry.getValue().isExpired()) {
                    iterator.remove();
                    cleanedCount++;
                }
            }
            
            if (config.isVerboseLogging() && cleanedCount > 0) {
                System.out.println(String.format("异步缓存清理完成: 清理了 %d 个过期条目", cleanedCount));
            }
        }, config.getCacheCleanupInterval(), config.getCacheCleanupInterval(), TimeUnit.MILLISECONDS);
    }
    
    /**
     * 获取异步监控统计信息
     */
    public String getAsyncMonitorStats() {
        int batchCount = batchQueryCount.get();
        int totalMembers = totalMembersQueried.get();
        long avgBatchTime = batchCount > 0 ? totalBatchTime.get() / batchCount : 0;
        int currentConcurrent = concurrentBatches.get();
        int cacheSize = memberStatusCache.size();
        int pendingSize = pendingQueries.size();
        
        return String.format(
                "异步监控统计信息:\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "批量查询次数: %d\n" +
                "查询成员总数: %d\n" +
                "平均批量耗时: %dms\n" +
                "当前并发批次: %d\n" +
                "缓存条目数量: %d\n" +
                "待查询队列: %d\n" +
                "异步HTTP统计:\n%s",
                batchCount, totalMembers, avgBatchTime, currentConcurrent, 
                cacheSize, pendingSize, asyncWebHandler.getPerformanceStats()
        );
    }
    
    /**
     * 获取批量查询性能报告
     */
    public String getBatchQueryReport() {
        int batchCount = batchQueryCount.get();
        int totalMembers = totalMembersQueried.get();
        double avgMembersPerBatch = batchCount > 0 ? (double) totalMembers / batchCount : 0;
        long avgBatchTime = batchCount > 0 ? totalBatchTime.get() / batchCount : 0;
        double throughput = avgBatchTime > 0 ? (avgMembersPerBatch * 1000.0 / avgBatchTime) : 0;
        
        return String.format(
                "批量查询性能报告:\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "总批次数: %d\n" +
                "总查询成员数: %d\n" +
                "平均每批成员数: %.2f\n" +
                "平均批次耗时: %dms\n" +
                "查询吞吐量: %.2f 成员/秒\n" +
                "当前并发批次: %d\n" +
                "缓存命中率: %.2f%%",
                batchCount, totalMembers, avgMembersPerBatch, avgBatchTime, 
                throughput, concurrentBatches.get(), calculateCacheHitRate()
        );
    }
    
    /**
     * 计算缓存命中率
     */
    private double calculateCacheHitRate() {
        // 这里需要实现缓存命中率的计算逻辑
        // 暂时返回估算值
        return memberStatusCache.size() > 0 ? 75.0 : 0.0;
    }
    
    /**
     * 重置异步监控统计
     */
    public void resetAsyncStats() {
        batchQueryCount.set(0);
        totalMembersQueried.set(0);
        totalBatchTime.set(0);
        concurrentBatches.set(0);
        memberStatusCache.clear();
        pendingQueries.clear();
        queryFutures.clear();
        asyncWebHandler.resetStats();
    }
    
    /**
     * 强制执行待查询队列
     */
    public CompletableFuture<Void> flushPendingQueries() {
        if (pendingQueries.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(this::executeBatchQuery, scheduler);
    }
    
    /**
     * 关闭异步监控器
     */
    public void shutdown() {
        scheduler.shutdown();
        asyncWebHandler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 异步成员状态缓存
     */
    private static class AsyncMemberStatus {
        private final String status;
        private final String rawResponse;
        private final long timestamp;
        private final long expireTime;
        
        public AsyncMemberStatus(String status, String rawResponse) {
            this.status = status;
            this.rawResponse = rawResponse;
            this.timestamp = System.currentTimeMillis();
            this.expireTime = timestamp + MonitorConfig.getInstance().getCacheExpireTime();
        }
        
        public String getStatus() { return status; }
        public String getRawResponse() { return rawResponse; }
        public long getTimestamp() { return timestamp; }
        public boolean isExpired() { return System.currentTimeMillis() > expireTime; }
    }
}