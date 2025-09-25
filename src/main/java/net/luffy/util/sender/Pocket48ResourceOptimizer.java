package net.luffy.util.sender;

import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.luffy.util.UnifiedSchedulerManager;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;

/**
 * 口袋48资源优化器 - 优化版
 * 提供资源预加载、异步缓存管理和批量处理功能
 * 新增：异步缓存清理、缓存开关控制、智能清理策略
 */
public class Pocket48ResourceOptimizer {
    
    // 资源缓存映射
    private final Map<String, File> resourceCache = new ConcurrentHashMap<>();
    
    // 预加载线程池 - 使用统一线程池管理
    private final ExecutorService preloadExecutor = AdaptiveThreadPoolManager.getInstance().getExecutorService();
    
    // 异步缓存清理线程池 - 使用统一调度器
    private final ScheduledExecutorService cacheCleanupExecutor = UnifiedSchedulerManager.getInstance().getScheduledExecutor();
    
    // 资源处理器引用
    private final Pocket48ResourceHandler resourceHandler;
    
    // 缓存统计
    private volatile int cacheHits = 0;
    private volatile int cacheMisses = 0;
    
    // 缓存控制开关
    private final AtomicBoolean cacheEnabled = new AtomicBoolean(true);
    
    // 上次清理时间
    private final AtomicLong lastCleanupTime = new AtomicLong(0);
    
    // 清理频率控制（毫秒）
    private static final long MIN_CLEANUP_INTERVAL = 3 * 60 * 1000; // 优化为3分钟，提高清理频率
    
    public Pocket48ResourceOptimizer(Pocket48ResourceHandler resourceHandler) {
        this.resourceHandler = resourceHandler;
    }
    
    /**
     * 预加载资源列表
     * @param resourceUrls 资源URL列表
     * @return 预加载任务的Future列表
     */
    public List<CompletableFuture<Void>> preloadResources(List<String> resourceUrls) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (String url : resourceUrls) {
            if (url != null && !resourceCache.containsKey(url)) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // 根据URL推断文件扩展名，避免使用.tmp后缀
                        String fileExtension = getFileExtensionFromUrl(url);
                        File cachedFile = resourceHandler.downloadToTempFile(url, fileExtension);
                        if (cachedFile != null && cachedFile.exists()) {
                            resourceCache.put(url, cachedFile);
                            // 预加载成功
                        }
                    } catch (Exception e) {
                        // 静默处理预加载失败
                    }
                }, preloadExecutor);
                futures.add(future);
            }
        }
        
        return futures;
    }
    
    /**
     * 获取缓存的资源文件
     * @param resourceUrl 资源URL
     * @return 缓存的文件，如果不存在则返回null
     */
    public File getCachedResource(String resourceUrl) {
        File cachedFile = resourceCache.get(resourceUrl);
        if (cachedFile != null && cachedFile.exists()) {
            cacheHits++;
            return cachedFile;
        }
        cacheMisses++;
        return null;
    }
    
    /**
     * 智能获取资源 - 优化版
     * 支持缓存开关控制，优先使用缓存，缓存未命中时下载
     * @param resourceUrl 资源URL
     * @return 资源文件
     */
    public File getResourceSmart(String resourceUrl) {
        // 如果缓存被禁用，直接下载资源
        if (!cacheEnabled.get()) {
            try {
                String fileExtension = getFileExtensionFromUrl(resourceUrl);
                return resourceHandler.downloadToTempFile(resourceUrl, fileExtension);
            } catch (Exception e) {
                return null;
            }
        }
        
        // 先检查缓存
        File cachedFile = getCachedResource(resourceUrl);
        if (cachedFile != null) {
            return cachedFile;
        }
        
        // 缓存未命中，下载资源
        try {
            // 根据URL推断文件扩展名，避免使用.tmp后缀
            String fileExtension = getFileExtensionFromUrl(resourceUrl);
            File downloadedFile = resourceHandler.downloadToTempFile(resourceUrl, fileExtension);
            if (downloadedFile != null && downloadedFile.exists()) {
                // 仅在缓存启用时添加到缓存
                if (cacheEnabled.get()) {
                    resourceCache.put(resourceUrl, downloadedFile);
                }
                return downloadedFile;
            }
        } catch (Exception e) {
            // 静默处理资源下载失败
        }
        
        return null;
    }
    
    /**
     * 从URL推断文件扩展名
     * @param url 资源URL
     * @return 文件扩展名
     */
    private String getFileExtensionFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return ".dat";
        }
        
        // 移除查询参数
        String cleanUrl = url.split("\\?")[0];
        
        // 获取文件名部分
        int lastSlash = cleanUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < cleanUrl.length() - 1) {
            String fileName = cleanUrl.substring(lastSlash + 1);
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0 && lastDot < fileName.length() - 1) {
                return fileName.substring(lastDot);
            }
        }
        
        // 根据URL路径推断类型
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.contains("/image/") || lowerUrl.contains("/img/") || lowerUrl.contains("/cover/")) {
            return ".jpg";
        } else if (lowerUrl.contains("/video/") || lowerUrl.contains("/mp4/")) {
            return ".mp4";
        } else if (lowerUrl.contains("/audio/") || lowerUrl.contains("/voice/")) {
            return ".amr";
        }
        
        // 默认扩展名
        return ".dat";
    }
    
    /**
     * 批量预加载消息中的所有媒体资源
     * @param messages 消息列表
     * @return 预加载任务的Future列表
     */
    public List<CompletableFuture<Void>> preloadMessageResources(List<Pocket48Message> messages) {
        List<String> resourceUrls = new ArrayList<>();
        
        for (Pocket48Message message : messages) {
            if (message == null) continue;
            
            // 使用扩展后的Pocket48Message模型提取资源URL
            if (message.isMediaMessage()) {
                // 获取主要资源URL
                String primaryUrl = message.getPrimaryResourceUrl();
                if (primaryUrl != null && !primaryUrl.trim().isEmpty()) {
                    resourceUrls.add(primaryUrl);
                }
                
                // 获取缩略图URL（如果有）
                String thumbnailUrl = message.getThumbnailUrl();
                if (thumbnailUrl != null && !thumbnailUrl.trim().isEmpty() && !thumbnailUrl.equals(primaryUrl)) {
                    resourceUrls.add(thumbnailUrl);
                }
                
                // 获取所有相关资源URL
                List<String> allUrls = message.getResourceUrls();
                if (allUrls != null) {
                    for (String url : allUrls) {
                        if (url != null && !url.trim().isEmpty() && !resourceUrls.contains(url)) {
                            resourceUrls.add(url);
                        }
                    }
                }
            }
        }
        
        return preloadResources(resourceUrls);
    }
    
    /**
     * 异步清理过期的缓存文件 - 优化版
     * @param maxAgeMinutes 最大缓存时间（分钟）
     */
    public void cleanupExpiredCache(int maxAgeMinutes) {
        // 检查线程池状态，避免向已关闭的线程池提交任务
        if (cacheCleanupExecutor.isShutdown() || cacheCleanupExecutor.isTerminated()) {
            return;
        }
        
        // 如果缓存被禁用，直接清空所有缓存
        if (!cacheEnabled.get()) {
            clearAllCache();
            return;
        }
        
        // 智能频率控制：避免频繁清理
        long currentTime = System.currentTimeMillis();
        long lastCleanup = lastCleanupTime.get();
        
        if (currentTime - lastCleanup < MIN_CLEANUP_INTERVAL) {
            // 距离上次清理时间太短，跳过本次清理
            return;
        }
        
        // 再次检查线程池状态（双重检查）
        if (cacheCleanupExecutor.isShutdown() || cacheCleanupExecutor.isTerminated()) {
            return;
        }
        
        // 异步执行清理操作，避免阻塞主线程
        try {
            cacheCleanupExecutor.submit(() -> {
                try {
                    performCacheCleanup(maxAgeMinutes);
                    lastCleanupTime.set(currentTime);
                } catch (Exception e) {
                    // 静默处理清理异常，避免影响主流程
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池已关闭，静默处理
        }
    }
    
    /**
     * 执行实际的缓存清理操作
     * @param maxAgeMinutes 最大缓存时间（分钟）
     */
    private void performCacheCleanup(int maxAgeMinutes) {
        long maxAge = System.currentTimeMillis() - (maxAgeMinutes * 60 * 1000L);
        
        // 批量收集需要删除的条目，减少锁竞争
        List<String> keysToRemove = new ArrayList<>();
        List<File> filesToDelete = new ArrayList<>();
        
        resourceCache.forEach((key, file) -> {
            if (file == null || !file.exists() || file.lastModified() < maxAge) {
                keysToRemove.add(key);
                if (file != null && file.exists()) {
                    filesToDelete.add(file);
                }
            }
        });
        
        // 批量删除缓存条目
        keysToRemove.forEach(resourceCache::remove);
        
        // 批量删除文件（在后台线程中执行）
        for (File file : filesToDelete) {
            try {
                file.delete();
            } catch (Exception e) {
                // 静默处理文件删除失败
            }
        }
    }
    
    /**
     * 清空所有缓存
     */
    public void clearAllCache() {
        // 检查线程池状态，避免向已关闭的线程池提交任务
        if (cacheCleanupExecutor.isShutdown() || cacheCleanupExecutor.isTerminated()) {
            // 线程池已关闭，同步执行清理
            try {
                List<File> filesToDelete = new ArrayList<>(resourceCache.values());
                resourceCache.clear();
                
                // 删除所有缓存文件
                for (File file : filesToDelete) {
                    if (file != null && file.exists()) {
                        try {
                            file.delete();
                        } catch (Exception e) {
                            // 静默处理
                        }
                    }
                }
            } catch (Exception e) {
                // 静默处理清理异常
            }
            return;
        }
        
        // 异步执行清理
        try {
            cacheCleanupExecutor.submit(() -> {
                try {
                    List<File> filesToDelete = new ArrayList<>(resourceCache.values());
                    resourceCache.clear();
                    
                    // 删除所有缓存文件
                    for (File file : filesToDelete) {
                        if (file != null && file.exists()) {
                            try {
                                file.delete();
                            } catch (Exception e) {
                                // 静默处理
                            }
                        }
                    }
                } catch (Exception e) {
                    // 静默处理清理异常
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池已关闭，静默处理
        }
    }
    
    /**
     * 启用或禁用缓存
     * @param enabled true启用，false禁用
     */
    public void setCacheEnabled(boolean enabled) {
        cacheEnabled.set(enabled);
        if (!enabled) {
            // 禁用缓存时立即清空所有缓存
            clearAllCache();
        }
    }
    
    /**
     * 检查缓存是否启用
     * @return true如果缓存启用
     */
    public boolean isCacheEnabled() {
        return cacheEnabled.get();
    }
    
    /**
     * 获取缓存统计信息
     * @return 缓存统计字符串
     */
    public String getCacheStats() {
        int total = cacheHits + cacheMisses;
        double hitRate = total > 0 ? (double) cacheHits / total * 100 : 0;
        
        return String.format("[缓存统计] 命中率: %.1f%% (%d/%d), 缓存大小: %d", 
                           hitRate, cacheHits, total, resourceCache.size());
    }
    
    /**
     * 关闭优化器 - 统一线程池管理版
     */
    public void shutdown() {
        // 清理所有缓存
        clearAllCache();
        
        // 线程池现在由AdaptiveThreadPoolManager和UnifiedSchedulerManager统一管理
        // 不需要在这里单独关闭
        System.out.println("[Pocket48ResourceOptimizer] 已关闭，线程池由统一管理器处理");
    }
}