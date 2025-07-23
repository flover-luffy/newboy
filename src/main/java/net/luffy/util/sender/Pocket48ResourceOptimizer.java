package net.luffy.util.sender;

import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;

/**
 * 口袋48资源优化器
 * 提供资源预加载、缓存管理和批量处理功能
 */
public class Pocket48ResourceOptimizer {
    
    // 资源缓存映射
    private final Map<String, File> resourceCache = new ConcurrentHashMap<>();
    
    // 预加载线程池
    private final ExecutorService preloadExecutor = Executors.newFixedThreadPool(4);
    
    // 资源处理器引用
    private final Pocket48ResourceHandler resourceHandler;
    
    // 缓存统计
    private volatile int cacheHits = 0;
    private volatile int cacheMisses = 0;
    
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
                        System.err.println("[预加载失败] " + url + ": " + e.getMessage());
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
     * 智能获取资源（优先使用缓存，缓存未命中时下载）
     * @param resourceUrl 资源URL
     * @return 资源文件
     */
    public File getResourceSmart(String resourceUrl) {
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
                // 添加到缓存
                resourceCache.put(resourceUrl, downloadedFile);
                return downloadedFile;
            }
        } catch (Exception e) {
            System.err.println("[资源下载失败] " + resourceUrl + ": " + e.getMessage());
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
            
            // 提取各种类型的资源URL
            switch (message.getType()) {
                case AUDIO:
                case FLIPCARD_AUDIO:
                    // 音频消息暂不处理URL提取，因为Pocket48Message没有getBody()方法
                    break;
                    
                case VIDEO:
                case FLIPCARD_VIDEO:
                    // 视频消息暂不处理URL提取，因为Pocket48Message没有getBody()方法
                    break;
                    
                case IMAGE:
                case EXPRESSIMAGE:
                    // 图片消息暂不处理URL提取，因为Pocket48Message没有getBody()方法
                    break;
                    
                default:
                    // 其他类型暂不处理
                    break;
            }
        }
        
        return preloadResources(resourceUrls);
    }
    
    /**
     * 清理过期的缓存文件
     * @param maxAgeMinutes 最大缓存时间（分钟）
     */
    public void cleanupExpiredCache(int maxAgeMinutes) {
        long maxAge = System.currentTimeMillis() - (maxAgeMinutes * 60 * 1000L);
        
        resourceCache.entrySet().removeIf(entry -> {
            File file = entry.getValue();
            if (file == null || !file.exists() || file.lastModified() < maxAge) {
                if (file != null && file.exists()) {
                    try {
                        file.delete();
                        // 删除过期文件
                    } catch (Exception e) {
                        System.err.println("[缓存清理失败] " + file.getName() + ": " + e.getMessage());
                    }
                }
                return true;
            }
            return false;
        });
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
     * 关闭优化器，释放资源
     */
    public void shutdown() {
        try {
            preloadExecutor.shutdown();
            if (!preloadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                preloadExecutor.shutdownNow();
            }
            
            // 清理所有缓存文件
            cleanupExpiredCache(0);
            
            // 资源优化器已关闭
        } catch (InterruptedException e) {
            preloadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}