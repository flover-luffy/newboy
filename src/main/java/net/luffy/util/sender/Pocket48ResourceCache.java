package net.luffy.util.sender;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.luffy.util.UnifiedLogger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.Collections;
import net.luffy.util.UnifiedSchedulerManager;
import net.luffy.util.ImageFormatDetector;

/**
 * 口袋48资源缓存管理器
 * 用于缓存已下载的媒体资源，避免重复下载
 */
public class Pocket48ResourceCache {
    
    private static final Pocket48ResourceCache INSTANCE = new Pocket48ResourceCache();
    private static final UnifiedLogger logger = UnifiedLogger.getInstance();
    
    // 缓存目录
    private final Path cacheDir;
    // 内存缓存：URL -> 本地文件路径
    private final ConcurrentHashMap<String, String> urlToFileMap;
    // 文件访问时间记录
    private final ConcurrentHashMap<String, Long> fileAccessTime;
    private final ConcurrentHashMap<String, Long> fileChecksums;
    private final AtomicLong cacheHits;
    private final AtomicLong cacheMisses;
    
    // LRU缓存实现
    private final Map<String, String> lruCache;
    
    // 缓存配置 - 精简版
    // 缓存配置 - 优化为实时性
    private static final int MAX_CACHE_ENTRIES = 2000; // 增加到2000个条目
    private static final long CACHE_EXPIRE_TIME = 2 * 60 * 60 * 1000; // 从8小时缩短到2小时，提升实时性
    private static final long CLEANUP_INTERVAL = 30 * 60 * 1000; // 从6小时缩短到30分钟清理一次，更频繁清理
    private static final long MAX_CACHE_SIZE = 1024 * 1024 * 1024; // 增加到1GB
    private static final int SHARD_COUNT = 16; // 目录分片数量
    
    // 定时清理任务
    private String cleanupTaskId;
    
    // 缓存控制开关 - 启用缓存以提升性能
    private volatile boolean cacheEnabled = true; // 改为true，启用缓存
    
    private Pocket48ResourceCache() {
        this.urlToFileMap = new ConcurrentHashMap<>();
        this.fileAccessTime = new ConcurrentHashMap<>();
        this.fileChecksums = new ConcurrentHashMap<>();
        this.cacheHits = new AtomicLong(0);
        this.cacheMisses = new AtomicLong(0);
        this.lruCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            }
        );
        
        // 创建缓存目录和分片目录
        this.cacheDir = Paths.get(System.getProperty("user.dir"), "cache", "pocket48");
        try {
            Files.createDirectories(cacheDir);
            // 创建分片目录
            for (int i = 0; i < SHARD_COUNT; i++) {
                Files.createDirectories(cacheDir.resolve(String.format("shard_%02d", i)));
            }
        } catch (IOException e) {
            throw new RuntimeException("无法创建缓存目录: " + e.getMessage(), e);
        }
        
        // 使用统一调度器启动清理任务
        UnifiedSchedulerManager scheduler = UnifiedSchedulerManager.getInstance();
        this.cleanupTaskId = scheduler.scheduleCleanupTask(
            this::cleanupExpiredFiles, 
            CLEANUP_INTERVAL, 
            CLEANUP_INTERVAL
        );
    }
    
    public static Pocket48ResourceCache getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取缓存文件
     * @param url 资源URL
     * @return 缓存文件，如果不存在则返回null
     */
    public File getCachedFile(String url) {
        if (!cacheEnabled) {
            cacheMisses.incrementAndGet();
            return null;
        }
        
        // 首先检查LRU缓存
        String filePath = lruCache.get(url);
        if (filePath == null) {
            filePath = urlToFileMap.get(url);
        }
        
        if (filePath != null) {
            File file = new File(filePath);
            if (file.exists()) {
                // 验证文件完整性
                Long expectedChecksum = fileChecksums.get(filePath);
                if (expectedChecksum != null && !validateFileIntegrity(file, expectedChecksum)) {
                    logger.warn("Pocket48ResourceCache", "缓存文件校验失败，删除损坏文件: " + file.getName());
                    file.delete();
                    urlToFileMap.remove(url);
                    fileAccessTime.remove(filePath);
                    fileChecksums.remove(filePath);
                    lruCache.remove(url);
                    cacheMisses.incrementAndGet();
                    return null;
                }
                
                // 更新访问时间和LRU缓存
                fileAccessTime.put(filePath, System.currentTimeMillis());
                lruCache.put(url, filePath);
                cacheHits.incrementAndGet();
                return file;
            } else {
                // 文件不存在，清理映射
                urlToFileMap.remove(url);
                fileAccessTime.remove(filePath);
                fileChecksums.remove(filePath);
                lruCache.remove(url);
            }
        }
        
        cacheMisses.incrementAndGet();
        return null;
    }
    
    /**
     * 将文件添加到缓存 - 优化版
     * @param url 资源URL
     * @param sourceFile 源文件
     * @param fileExtension 文件扩展名
     * @return 缓存的文件
     */
    public File cacheFile(String url, File sourceFile, String fileExtension) {
        // 移除缓存检查，直接执行缓存操作
        
        // UnifiedLogger不需要设置UseParentHandlers
        
        try {
            // 使用分片目录生成缓存文件
            File cacheFile = getCacheFile(url, fileExtension);
            
            // 移除缓存文件存在检查，直接复制文件
            
            // 复制文件到缓存目录
            Files.copy(sourceFile.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            
            logger.info("Pocket48ResourceCache", "文件缓存完成: " + cacheFile.getName() + ", 大小: " + cacheFile.length() + " bytes");
            
            // 移除图片完整性验证，减少系统开销
            
            // 更新缓存映射
            urlToFileMap.put(url, cacheFile.getAbsolutePath());
            fileAccessTime.put(cacheFile.getAbsolutePath(), System.currentTimeMillis());
            
            // 文件已缓存
            
            // 移除缓存大小检查，减少系统开销
            
            return cacheFile;
        } catch (IOException e) {
            logger.error("Pocket48ResourceCache", "[缓存错误] 无法缓存文件: " + e.getMessage());
            return sourceFile; // 返回原文件
        }
    }
    
    /**
     * 判断文件是否为图片文件
     * @param file 文件
     * @return 是否为图片文件
     */
    private boolean isImageFile(File file) {
        String fileName = file.getName().toLowerCase();
        return fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || 
               fileName.endsWith(".png") || fileName.endsWith(".gif") || 
               fileName.endsWith(".bmp") || fileName.endsWith(".webp");
    }
    
    /**
     * 直接缓存下载的文件
     * @param url 资源URL
     * @param inputStream 输入流
     * @param fileExtension 文件扩展名
     * @return 缓存的文件
     */
    public File cacheFromStream(String url, InputStream inputStream, String fileExtension) {
        // UnifiedLogger不需要设置UseParentHandlers
        
        try {
            // 移除缓存检查，直接创建新文件确保数据实时性
            
            // 生成缓存文件名
            String fileName = generateCacheFileName(url, fileExtension);
            File cacheFile = new File(cacheDir.toFile(), fileName);
            
            // 写入缓存文件
            long totalBytes = 0;
            try (FileOutputStream outputStream = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
            }
            
            logger.info("Pocket48ResourceCache", "缓存文件写入完成: " + cacheFile.getName() + ", 大小: " + totalBytes + " bytes");
            
            // 移除复杂的图片格式检测和扩展名修正，减少系统开销
            File finalCacheFile = cacheFile;
            
            // 移除图片完整性验证，减少系统开销
            
            // 移除校验和计算，减少系统开销
            
            // 更新缓存映射
            String finalPath = finalCacheFile.getAbsolutePath();
            urlToFileMap.put(url, finalPath);
            fileAccessTime.put(finalPath, System.currentTimeMillis());
            lruCache.put(url, finalPath);
            
            logger.info("Pocket48ResourceCache", "文件已缓存: " + finalCacheFile.getName());
            
            // 移除缓存大小检查，减少系统开销
            
            return finalCacheFile;
        } catch (IOException e) {
            logger.error("Pocket48ResourceCache", "[缓存错误] 无法缓存文件: " + e.getMessage());
            return null; // 缓存失败
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                // 忽略关闭异常
            }
        }
    }
    
    /**
     * 根据检测到的图片格式获取正确的文件扩展名
     * @param detectedFormat 检测到的图片格式
     * @return 正确的文件扩展名
     */
    private String getCorrectExtension(String detectedFormat) {
        if (detectedFormat == null || "UNKNOWN".equals(detectedFormat)) {
            return ".jpg"; // 默认为jpg
        }
        
        switch (detectedFormat.toUpperCase()) {
            case "JPEG":
            case "JPG":
                return ".jpg";
            case "PNG":
                return ".png";
            case "GIF":
                return ".gif";
            case "BMP":
                return ".bmp";
            case "WEBP":
                return ".webp";
            default:
                return ".jpg"; // 默认为jpg
        }
    }
    
    /**
     * 根据URL计算分片索引
     * @param url 资源URL
     * @return 分片索引
     */
    private int getShardIndex(String url) {
        return Math.abs(url.hashCode()) % SHARD_COUNT;
    }
    
    /**
     * 获取分片目录
     * @param url 资源URL
     * @return 分片目录路径
     */
    private Path getShardDirectory(String url) {
        int shardIndex = getShardIndex(url);
        return cacheDir.resolve(String.format("shard_%02d", shardIndex));
    }
    
    /**
     * 计算文件校验和
     * @param file 文件
     * @return CRC32校验和
     */
    private long calculateChecksum(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            CRC32 crc32 = new CRC32();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                crc32.update(buffer, 0, bytesRead);
            }
            return crc32.getValue();
        } catch (IOException e) {
            UnifiedLogger logger = UnifiedLogger.getInstance();
            logger.warn("Pocket48ResourceCache", "计算文件校验和失败: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * 验证文件完整性
     * @param file 文件
     * @param expectedChecksum 期望的校验和
     * @return true如果文件完整
     */
    private boolean validateFileIntegrity(File file, long expectedChecksum) {
        if (expectedChecksum == 0) {
            return true; // 没有校验和信息，跳过验证
        }
        long actualChecksum = calculateChecksum(file);
        return actualChecksum == expectedChecksum;
    }
    
    /**
     * 生成缓存文件名
     * @param url 资源URL
     * @param fileExtension 文件扩展名
     * @return 缓存文件名
     */
    private String generateCacheFileName(String url, String fileExtension) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(url.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return "pocket48_" + sb.toString() + fileExtension;
        } catch (NoSuchAlgorithmException e) {
            // 如果MD5不可用，使用hashCode
            return "pocket48_" + Math.abs(url.hashCode()) + fileExtension;
        }
    }
    
    /**
     * 获取缓存文件完整路径（包含分片目录）
     * @param url 资源URL
     * @param fileExtension 文件扩展名
     * @return 缓存文件完整路径
     */
    private File getCacheFile(String url, String fileExtension) {
        Path shardDir = getShardDirectory(url);
        String fileName = generateCacheFileName(url, fileExtension);
        return shardDir.resolve(fileName).toFile();
    }
    
    /**
     * 清理过期文件和检查缓存大小
     */
    private void cleanupExpiredFiles() {
         try {
            long currentTime = System.currentTimeMillis();
            int cleanedCount = 0;
            
            // 清理过期文件
            for (String filePath : fileAccessTime.keySet()) {
                Long accessTime = fileAccessTime.get(filePath);
                if (accessTime != null && (currentTime - accessTime) > CACHE_EXPIRE_TIME) {
                    File file = new File(filePath);
                    if (file.exists() && file.delete()) {
                        fileAccessTime.remove(filePath);
                        fileChecksums.remove(filePath);
                        // 从URL映射和LRU缓存中移除
                        urlToFileMap.entrySet().removeIf(entry -> {
                            if (entry.getValue().equals(filePath)) {
                                lruCache.remove(entry.getKey());
                                return true;
                            }
                            return false;
                        });
                        cleanedCount++;
                    }
                }
            }
            
            // 清理过期文件后，检查缓存大小
            checkCacheSize();
            
            if (cleanedCount > 0) {
                logger.info("Pocket48ResourceCache", "清理了 " + cleanedCount + " 个过期缓存文件");
            }
        } catch (Exception e) {
            logger.warn("Pocket48ResourceCache", "缓存清理过程中发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 检查缓存大小，如果超过限制则清理最旧的文件
     */
    private void checkCacheSize() {
         try {
            long totalSize = Files.walk(cacheDir)
                .filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();
                
            if (totalSize > MAX_CACHE_SIZE) {
                logger.info("Pocket48ResourceCache", "缓存大小超限 (" + (totalSize / 1024 / 1024) + "MB > " + (MAX_CACHE_SIZE / 1024 / 1024) + "MB)，开始清理最旧文件");
                // 缓存大小超限，开始清理最旧文件
                cleanOldestFiles(totalSize - MAX_CACHE_SIZE / 2); // 清理到一半大小
            }
        } catch (IOException e) {
            logger.warn("Pocket48ResourceCache", "缓存大小检查失败: " + e.getMessage());
        }
    }
    
    /**
     * 清理最旧的文件
     * @param targetCleanSize 目标清理大小
     */
    private void cleanOldestFiles(long targetCleanSize) {
         int cleanedCount = 0;
        long cleanedSize = 0;
        
        // 按访问时间排序，清理最旧的文件
        fileAccessTime.entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e1.getValue(), e2.getValue()))
            .limit(50) // 最多清理50个文件
            .forEach(entry -> {
                String filePath = entry.getKey();
                File file = new File(filePath);
                if (file.exists()) {
                    long fileSize = file.length();
                    if (file.delete()) {
                        fileAccessTime.remove(filePath);
                        fileChecksums.remove(filePath);
                        // 从URL映射和LRU缓存中移除
                        urlToFileMap.entrySet().removeIf(urlEntry -> {
                            if (urlEntry.getValue().equals(filePath)) {
                                lruCache.remove(urlEntry.getKey());
                                return true;
                            }
                            return false;
                        });
                    }
                }
            });
        
        logger.info("Pocket48ResourceCache", "基于LRU策略清理了最旧的缓存文件");
    }
    
    /**
     * 获取缓存统计信息
     * @return 缓存统计信息
     */
    public String getCacheStats() {
        try {
            long fileCount = Files.walk(cacheDir)
                .filter(Files::isRegularFile)
                .count();
                
            long totalSize = Files.walk(cacheDir)
                .filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();
            
            long hits = cacheHits.get();
            long misses = cacheMisses.get();
            double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) * 100 : 0;
            
            // 统计分片目录使用情况
            StringBuilder shardStats = new StringBuilder();
            for (int i = 0; i < SHARD_COUNT; i++) {
                Path shardDir = cacheDir.resolve(String.format("shard_%02d", i));
                try {
                    long shardFileCount = Files.walk(shardDir)
                        .filter(Files::isRegularFile)
                        .count();
                    if (shardFileCount > 0) {
                        shardStats.append(String.format("分片%02d: %d文件; ", i, shardFileCount));
                    }
                } catch (IOException e) {
                    // 忽略分片统计错误
                }
            }
                
            return String.format("缓存统计: %d个文件, %.2fMB, 命中率: %.1f%% (%d命中/%d未命中), LRU条目: %d, 校验和: %d\n分片分布: %s", 
                fileCount, totalSize / 1024.0 / 1024.0, hitRate, hits, misses, 
                lruCache.size(), fileChecksums.size(), shardStats.toString());
        } catch (IOException e) {
            return "缓存统计获取失败: " + e.getMessage();
        }
    }
    
    /**
     * 清空所有缓存
     */
    public void clearAll() {
         try {
            Files.walk(cacheDir)
                .filter(Files::isRegularFile)
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // 忽略删除失败
                    }
                });
            urlToFileMap.clear();
            fileAccessTime.clear();
            fileChecksums.clear();
            lruCache.clear();
            cacheHits.set(0);
            cacheMisses.set(0);
            logger.info("Pocket48ResourceCache", "所有缓存已清空，统计信息已重置");
        } catch (IOException e) {
            logger.warn("Pocket48ResourceCache", "缓存清空过程中发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 设置缓存启用状态
     * @param enabled true启用缓存，false禁用缓存
     */
    public void setCacheEnabled(boolean enabled) {
        this.cacheEnabled = enabled;
        if (!enabled) {
            // 禁用缓存时清空所有缓存
            clearAll();
        }
    }
    
    /**
     * 检查缓存是否启用
     * @return true如果缓存启用
     */
    public boolean isCacheEnabled() {
        return cacheEnabled;
    }
    
    /**
     * 清空所有缓存（别名方法）
     */
    public void clearAllCache() {
        clearAll();
    }
    
    /**
     * 获取缓存命中率
     * @return 缓存命中率（百分比）
     */
    public double getCacheHitRate() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        return (hits + misses) > 0 ? (double) hits / (hits + misses) * 100 : 0;
    }
    
    /**
     * 重置缓存统计信息
     */
    public void resetStats() {
         cacheHits.set(0);
        cacheMisses.set(0);
        logger.info("Pocket48ResourceCache", "缓存统计信息已重置");
    }
    
    /**
     * 验证所有缓存文件的完整性
     * @return 验证结果报告
     */
    public String validateAllFiles() {
        int totalFiles = 0;
        int validFiles = 0;
        int corruptedFiles = 0;
        
        for (Map.Entry<String, Long> entry : fileChecksums.entrySet()) {
            String filePath = entry.getKey();
            Long expectedChecksum = entry.getValue();
            File file = new File(filePath);
            
            totalFiles++;
            if (file.exists()) {
                if (validateFileIntegrity(file, expectedChecksum)) {
                    validFiles++;
                } else {
                    corruptedFiles++;
                    logger.warn("Pocket48ResourceCache", "发现损坏的缓存文件: " + file.getName());
                }
            } else {
                corruptedFiles++;
            }
        }
        
        return String.format("文件完整性验证: 总计%d个文件, %d个有效, %d个损坏", 
            totalFiles, validFiles, corruptedFiles);
    }
    
    /**
     * 获取分片使用统计
     * @return 分片使用统计信息
     */
    public String getShardStats() {
        StringBuilder stats = new StringBuilder("分片使用统计:\n");
        
        for (int i = 0; i < SHARD_COUNT; i++) {
            Path shardDir = cacheDir.resolve(String.format("shard_%02d", i));
            try {
                long fileCount = Files.walk(shardDir)
                    .filter(Files::isRegularFile)
                    .count();
                    
                long totalSize = Files.walk(shardDir)
                    .filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
                    
                stats.append(String.format("分片%02d: %d个文件, %.2fMB\n", 
                    i, fileCount, totalSize / 1024.0 / 1024.0));
            } catch (IOException e) {
                stats.append(String.format("分片%02d: 统计失败\n", i));
            }
        }
        
        return stats.toString();
    }
    
    /**
     * 关闭缓存管理器
     */
    public void shutdown() {
         if (cleanupTaskId != null) {
            UnifiedSchedulerManager.getInstance().cancelTask(cleanupTaskId);
        }
        logger.info("Pocket48ResourceCache", "Pocket48ResourceCache已关闭，最终统计: " + getCacheStats());
    }
}