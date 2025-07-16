package net.luffy.util.sender;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.luffy.util.UnifiedSchedulerManager;

/**
 * 口袋48资源缓存管理器
 * 用于缓存已下载的媒体资源，避免重复下载
 */
public class Pocket48ResourceCache {
    
    private static final Pocket48ResourceCache INSTANCE = new Pocket48ResourceCache();
    
    // 缓存目录
    private final Path cacheDir;
    // 内存缓存：URL -> 本地文件路径
    private final ConcurrentHashMap<String, String> urlToFileMap;
    // 文件访问时间记录
    private final ConcurrentHashMap<String, Long> fileAccessTime;
    
    // 缓存配置
    private static final long CACHE_EXPIRE_TIME = 4 * 60 * 60 * 1000; // 4小时过期
    private static final long CLEANUP_INTERVAL = 2 * 60 * 60 * 1000; // 2小时清理一次
    private static final long MAX_CACHE_SIZE = 500 * 1024 * 1024; // 最大缓存500MB
    
    // 定时清理任务
    private String cleanupTaskId;
    
    private Pocket48ResourceCache() {
        // 初始化缓存目录
        this.cacheDir = Paths.get(System.getProperty("java.io.tmpdir"), "pocket48_cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            System.err.println("[缓存警告] 无法创建缓存目录: " + e.getMessage());
        }
        
        this.urlToFileMap = new ConcurrentHashMap<>();
        this.fileAccessTime = new ConcurrentHashMap<>();
        
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
     * 获取缓存的文件，如果不存在则返回null
     * @param url 资源URL
     * @return 缓存的文件，如果不存在则返回null
     */
    public File getCachedFile(String url) {
        String filePath = urlToFileMap.get(url);
        if (filePath != null) {
            File file = new File(filePath);
            if (file.exists()) {
                // 更新访问时间
                fileAccessTime.put(filePath, System.currentTimeMillis());
                System.out.println("[缓存命中] 使用缓存文件: " + url);
                return file;
            } else {
                // 文件已被删除，清理映射
                urlToFileMap.remove(url);
                fileAccessTime.remove(filePath);
            }
        }
        return null;
    }
    
    /**
     * 将文件添加到缓存
     * @param url 资源URL
     * @param sourceFile 源文件
     * @param fileExtension 文件扩展名
     * @return 缓存的文件
     */
    public File cacheFile(String url, File sourceFile, String fileExtension) {
        try {
            // 生成缓存文件名
            String fileName = generateCacheFileName(url, fileExtension);
            File cacheFile = new File(cacheDir.toFile(), fileName);
            
            // 如果缓存文件已存在，直接返回
            if (cacheFile.exists()) {
                fileAccessTime.put(cacheFile.getAbsolutePath(), System.currentTimeMillis());
                urlToFileMap.put(url, cacheFile.getAbsolutePath());
                return cacheFile;
            }
            
            // 复制文件到缓存目录
            Files.copy(sourceFile.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            
            // 更新缓存映射
            urlToFileMap.put(url, cacheFile.getAbsolutePath());
            fileAccessTime.put(cacheFile.getAbsolutePath(), System.currentTimeMillis());
            
            System.out.println("[缓存添加] 文件已缓存: " + url + " -> " + cacheFile.getAbsolutePath());
            
            // 检查缓存大小
            checkCacheSize();
            
            return cacheFile;
        } catch (IOException e) {
            System.err.println("[缓存错误] 无法缓存文件: " + e.getMessage());
            return sourceFile; // 返回原文件
        }
    }
    
    /**
     * 直接缓存下载的文件
     * @param url 资源URL
     * @param inputStream 输入流
     * @param fileExtension 文件扩展名
     * @return 缓存的文件
     */
    public File cacheFromStream(String url, InputStream inputStream, String fileExtension) {
        try {
            // 检查是否已有缓存
            File existingCache = getCachedFile(url);
            if (existingCache != null) {
                inputStream.close();
                return existingCache;
            }
            
            // 生成缓存文件名
            String fileName = generateCacheFileName(url, fileExtension);
            File cacheFile = new File(cacheDir.toFile(), fileName);
            
            // 写入缓存文件
            try (FileOutputStream outputStream = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
            
            // 更新缓存映射
            urlToFileMap.put(url, cacheFile.getAbsolutePath());
            fileAccessTime.put(cacheFile.getAbsolutePath(), System.currentTimeMillis());
            
            System.out.println("[缓存创建] 文件已缓存: " + url + " -> " + cacheFile.getAbsolutePath());
            
            // 检查缓存大小
            checkCacheSize();
            
            return cacheFile;
        } catch (IOException e) {
            System.err.println("[缓存错误] 无法从流缓存文件: " + e.getMessage());
            return null;
        } finally {
            try {
                inputStream.close();
            } catch (IOException e) {
                // 忽略关闭异常
            }
        }
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
     * 清理过期文件
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
                        // 从URL映射中移除
                        urlToFileMap.entrySet().removeIf(entry -> entry.getValue().equals(filePath));
                        cleanedCount++;
                    }
                }
            }
            
            if (cleanedCount > 0) {
                System.out.println("[缓存清理] 清理了 " + cleanedCount + " 个过期文件");
            }
        } catch (Exception e) {
            System.err.println("[缓存清理错误] " + e.getMessage());
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
                System.out.println("[缓存清理] 缓存大小超限 (" + (totalSize / 1024 / 1024) + "MB)，开始清理最旧文件");
                cleanOldestFiles(totalSize - MAX_CACHE_SIZE / 2); // 清理到一半大小
            }
        } catch (IOException e) {
            System.err.println("[缓存大小检查错误] " + e.getMessage());
        }
    }
    
    /**
     * 清理最旧的文件
     * @param targetCleanSize 目标清理大小
     */
    private void cleanOldestFiles(long targetCleanSize) {
        fileAccessTime.entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e1.getValue(), e2.getValue()))
            .limit(20) // 最多清理20个文件
            .forEach(entry -> {
                String filePath = entry.getKey();
                File file = new File(filePath);
                if (file.exists() && file.delete()) {
                    fileAccessTime.remove(filePath);
                    urlToFileMap.entrySet().removeIf(urlEntry -> urlEntry.getValue().equals(filePath));
                }
            });
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
                
            return String.format("缓存统计: %d 个文件, %.2f MB", 
                fileCount, totalSize / 1024.0 / 1024.0);
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
            System.out.println("[缓存清理] 所有缓存已清空");
        } catch (IOException e) {
            System.err.println("[缓存清空错误] " + e.getMessage());
        }
    }
    
    /**
     * 关闭缓存管理器
     */
    public void shutdown() {
        if (cleanupTaskId != null) {
            UnifiedSchedulerManager.getInstance().cancelTask(cleanupTaskId);
        }
    }
}