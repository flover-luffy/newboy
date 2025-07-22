package net.luffy.util.sender;

import net.luffy.Newboy;
import net.luffy.util.UnifiedResourceManager;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.luffy.util.CpuLoadBalancer;
import net.luffy.util.UnifiedSchedulerManager;
import net.luffy.model.Pocket48Message;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.List;
import java.io.File;
import java.util.ArrayList;

/**
 * 口袋48统一资源管理器
 * 整合原有的多个资源管理器，提供统一的资源管理接口
 * 包含配置管理、缓存管理、资源优化和资源处理功能
 */
public class Pocket48UnifiedResourceManager {
    
    private static volatile Pocket48UnifiedResourceManager instance;
    private final Object lock = new Object();
    
    // 核心组件
    private final Pocket48ResourceManager configManager;
    private final Pocket48ResourceCache cacheManager;
    private final Pocket48ResourceHandler resourceHandler;
    private final Pocket48ResourceOptimizer optimizer;
    
    // 统一资源管理器引用
    private final UnifiedResourceManager unifiedManager;
    
    // 资源统计
    private final AtomicLong totalCacheHits = new AtomicLong(0);
    private final AtomicLong totalCacheMisses = new AtomicLong(0);
    private final AtomicLong totalResourcesProcessed = new AtomicLong(0);
    private final AtomicLong totalMemoryUsed = new AtomicLong(0);
    
    // 性能监控
    private final Map<String, Object> performanceMetrics = new ConcurrentHashMap<>();
    
    private Pocket48UnifiedResourceManager() {
        this.unifiedManager = UnifiedResourceManager.getInstance();
        this.configManager = Pocket48ResourceManager.getInstance();
        this.cacheManager = Pocket48ResourceCache.getInstance();
        this.resourceHandler = new Pocket48ResourceHandler();
        this.optimizer = new Pocket48ResourceOptimizer(resourceHandler);
        
        initialize();
    }
    
    public static Pocket48UnifiedResourceManager getInstance() {
        if (instance == null) {
            synchronized (Pocket48UnifiedResourceManager.class) {
                if (instance == null) {
                    instance = new Pocket48UnifiedResourceManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化统一资源管理器
     */
    private void initialize() {
        try {
            // 注册到统一资源管理器
            registerToUnifiedManager();
            
            // 启动性能监控
            startPerformanceMonitoring();
            
            // 口袋48统一资源管理器初始化完成
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[口袋48统一资源管理器] 初始化失败", e);
        }
    }
    
    /**
     * 注册到统一资源管理器
     */
    private void registerToUnifiedManager() {
        unifiedManager.registerResource("pocket48-unified-manager", 
            new UnifiedResourceManager.ManagedResource() {
                @Override
                public String getName() {
                    return "口袋48统一资源管理器";
                }
                
                @Override
                public UnifiedResourceManager.ResourceStatus getStatus() {
                    return new UnifiedResourceManager.ResourceStatus(true, 
                        "运行正常", getUnifiedMetrics());
                }
                
                @Override
                public void cleanup() {
                    shutdown();
                }
                
                @Override
                public long getMemoryUsage() {
                    return totalMemoryUsed.get();
                }
            });
    }
    
    /**
     * 启动性能监控
     */
    private void startPerformanceMonitoring() {
        UnifiedSchedulerManager.getInstance().scheduleCleanupTask(
            this::updatePerformanceMetrics, 30000, 30000); // 每30秒更新一次
    }
    
    /**
     * 更新性能指标
     */
    private void updatePerformanceMetrics() {
        try {
            performanceMetrics.put("cacheHits", totalCacheHits.get());
            performanceMetrics.put("cacheMisses", totalCacheMisses.get());
            performanceMetrics.put("resourcesProcessed", totalResourcesProcessed.get());
            performanceMetrics.put("memoryUsed", totalMemoryUsed.get());
            performanceMetrics.put("cacheHitRate", calculateCacheHitRate());
            performanceMetrics.put("activeConfigs", getActiveConfigCount());
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[口袋48统一资源管理器] 性能指标更新失败", e);
        }
    }
    
    /**
     * 计算缓存命中率
     */
    private double calculateCacheHitRate() {
        long hits = totalCacheHits.get();
        long misses = totalCacheMisses.get();
        long total = hits + misses;
        return total > 0 ? (double) hits / total * 100 : 0.0;
    }
    
    /**
     * 获取活跃配置数量
     */
    private int getActiveConfigCount() {
        int count = 0;
        if (configManager.isMediaQueueEnabled()) count++;
        if (configManager.isPrioritizeTextMessages()) count++;
        return count;
    }
    
    /**
     * 获取统一指标
     */
    private Map<String, Object> getUnifiedMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        metrics.putAll(performanceMetrics);
        // 配置指标已包含在其他地方
        return metrics;
    }
    
    // ==================== 配置管理接口 ====================
    
    public boolean isMediaQueueEnabled() {
        return configManager.isMediaQueueEnabled();
    }
    
    public void setMediaQueueEnabled(boolean enabled) {
        configManager.setMediaQueueEnabled(enabled);
    }
    
    public int getMediaQueueSize() {
        return configManager.getMediaQueueSize();
    }
    
    public void setMediaQueueSize(int size) {
        configManager.setMediaQueueSize(size);
    }
    
    public int getMediaThreadPoolSize() {
        return configManager.getMediaThreadPoolSize();
    }
    
    public void setMediaThreadPoolSize(int size) {
        configManager.setMediaThreadPoolSize(size);
    }
    
    public int getMediaProcessingTimeout() {
        return configManager.getMediaProcessingTimeout();
    }
    
    public void setMediaProcessingTimeout(int timeout) {
        configManager.setMediaProcessingTimeout(timeout);
    }
    
    public boolean isPrioritizeTextMessages() {
        return configManager.isPrioritizeTextMessages();
    }
    
    public void setPrioritizeTextMessages(boolean prioritize) {
        configManager.setPrioritizeTextMessages(prioritize);
    }
    
    public int getMediaBatchSize() {
        return configManager.getMediaBatchSize();
    }
    
    public void setMediaBatchSize(int size) {
        configManager.setMediaBatchSize(size);
    }
    
    public int getMediaRetryAttempts() {
        return configManager.getMediaRetryAttempts();
    }
    
    public void setMediaRetryAttempts(int attempts) {
        configManager.setMediaRetryAttempts(attempts);
    }
    
    // ==================== 缓存管理接口 ====================
    
    public File getCachedFile(String url) {
        File file = cacheManager.getCachedFile(url);
        if (file != null) {
            totalCacheHits.incrementAndGet();
        } else {
            totalCacheMisses.incrementAndGet();
        }
        return file;
    }
    
    public File cacheFile(String url, File sourceFile, String fileExtension) {
        File cachedFile = cacheManager.cacheFile(url, sourceFile, fileExtension);
        if (cachedFile != null) {
            totalMemoryUsed.addAndGet(cachedFile.length());
        }
        return cachedFile;
    }
    
    // ==================== 资源处理接口 ====================
    
    public java.io.InputStream getPocket48InputStream(String url) {
        totalResourcesProcessed.incrementAndGet();
        return resourceHandler.getPocket48InputStream(url);
    }
    
    public java.io.InputStream getPocket48InputStreamWithRetry(String url, int maxRetries) {
        totalResourcesProcessed.incrementAndGet();
        return resourceHandler.getPocket48InputStreamWithRetry(url, maxRetries);
    }
    
    public File downloadToTempFile(String url, String fileExtension) {
        totalResourcesProcessed.incrementAndGet();
        return resourceHandler.downloadToTempFile(url, fileExtension);
    }
    
    public File downloadToTempFileWithRetry(String url, String fileExtension, int maxRetries) {
        totalResourcesProcessed.incrementAndGet();
        return resourceHandler.downloadToTempFileWithRetry(url, fileExtension, maxRetries);
    }
    
    public Pocket48ResourceHandler.Pocket48ResourceInfo checkResourceAvailability(String url) {
        return resourceHandler.checkResourceAvailability(url);
    }
    
    // ==================== 资源优化接口 ====================
    
    public List<CompletableFuture<Void>> preloadResources(List<String> resourceUrls) {
        return optimizer.preloadResources(resourceUrls);
    }
    
    public File getCachedResource(String resourceUrl) {
        File file = optimizer.getCachedResource(resourceUrl);
        if (file != null) {
            totalCacheHits.incrementAndGet();
        } else {
            totalCacheMisses.incrementAndGet();
        }
        return file;
    }
    
    public File getResourceSmart(String resourceUrl) {
        totalResourcesProcessed.incrementAndGet();
        return optimizer.getResourceSmart(resourceUrl);
    }
    
    public void cleanupExpiredCache(int maxAgeMinutes) {
        optimizer.cleanupExpiredCache(maxAgeMinutes);
    }
    
    public String getCacheStats() {
        return optimizer.getCacheStats();
    }
    
    public List<CompletableFuture<Void>> preloadMessageResources(List<Pocket48Message> messages) {
        return optimizer.preloadMessageResources(messages);
    }
    
    // ==================== 系统管理接口 ====================
    
    /**
     * 获取系统状态报告
     */
    public String getSystemStatusReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== 口袋48统一资源管理器状态报告 ===\n");
        report.append(String.format("缓存命中率: %.2f%%\n", calculateCacheHitRate()));
        report.append(String.format("总处理资源数: %d\n", totalResourcesProcessed.get()));
        report.append(String.format("内存使用: %.2f MB\n", totalMemoryUsed.get() / 1024.0 / 1024.0));
        report.append(String.format("媒体队列状态: %s\n", isMediaQueueEnabled() ? "启用" : "禁用"));
        report.append(String.format("队列大小: %d\n", getMediaQueueSize()));
        report.append(String.format("线程池大小: %d\n", getMediaThreadPoolSize()));
        report.append(getCacheStats());
        return report.toString();
    }
    
    /**
     * 执行系统优化
     */
    public void performSystemOptimization() {
        try {
            // 清理过期缓存
            cleanupExpiredCache(240); // 4小时
            
            // 根据当前负载调整配置
            optimizeConfigurationBasedOnLoad();
            
            // 更新性能指标
            updatePerformanceMetrics();
            
            // 系统优化完成
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[口袋48统一资源管理器] 系统优化失败", e);
        }
    }
    
    /**
     * 根据负载优化配置
     */
    private void optimizeConfigurationBasedOnLoad() {
        // 获取当前CPU负载
        CpuLoadBalancer loadBalancer = CpuLoadBalancer.getInstance();
        double cpuUsage = loadBalancer.getCurrentCpuUsage();
        
        // 根据CPU使用率调整线程池大小
        if (cpuUsage > 0.8) {
            // 高负载时减少线程池大小
            int currentSize = getMediaThreadPoolSize();
            if (currentSize > 1) {
                setMediaThreadPoolSize(Math.max(1, currentSize - 1));
            }
        } else if (cpuUsage < 0.3) {
            // 低负载时可以适当增加线程池大小
            int currentSize = getMediaThreadPoolSize();
            if (currentSize < 4) {
                setMediaThreadPoolSize(currentSize + 1);
            }
        }
        
        // 根据缓存命中率调整队列大小
        double hitRate = calculateCacheHitRate();
        if (hitRate > 0.8) {
            // 高命中率时可以增加队列大小
            int currentSize = getMediaQueueSize();
            if (currentSize < 200) {
                setMediaQueueSize(Math.min(200, currentSize + 20));
            }
        }
    }
    
    /**
     * 关闭统一资源管理器
     */
    public void shutdown() {
        try {
            if (optimizer != null) {
                optimizer.shutdown();
            }
            
            // 清理缓存
            cleanupExpiredCache(0);
            
            // 口袋48统一资源管理器已关闭
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[口袋48统一资源管理器] 关闭时发生错误", e);
        }
    }
}