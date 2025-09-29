package net.luffy.util.summary;

import net.luffy.util.UnifiedLogger;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

/**
 * 图片内容处理器
 * 
 * 主要功能：
 * 1. 下载和缓存房间图片
 * 2. 分析图片基本信息（尺寸、格式、大小）
 * 3. 生成图片缩略图
 * 4. 检测重复图片
 * 5. 管理图片存储和清理
 * 6. 提供图片内容分析数据
 * 
 * @author Luffy
 * @since 2024-01-20
 */
public class ImageContentProcessor {
    
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(4);
    
    // 存储配置
    private static final String IMAGE_CACHE_DIR = "data/image_cache/";
    private static final String THUMBNAIL_DIR = "data/thumbnails/";
    private static final int MAX_DOWNLOAD_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int THUMBNAIL_SIZE = 200;
    private static final int CACHE_RETENTION_DAYS = 7;
    
    // 图片信息缓存
    private final Map<String, ImageInfo> imageInfoCache = new ConcurrentHashMap<>();
    private final Map<String, String> imageHashCache = new ConcurrentHashMap<>();
    
    public ImageContentProcessor() {
        initializeDirectories();
        loadImageCache();
    }
    
    /**
     * 初始化存储目录
     */
    private void initializeDirectories() {
        try {
            Files.createDirectories(Paths.get(IMAGE_CACHE_DIR));
            Files.createDirectories(Paths.get(THUMBNAIL_DIR));
            logger.info("ImageContentProcessor", "初始化存储目录完成");
        } catch (Exception e) {
            logger.error("ImageContentProcessor", "初始化存储目录失败: " + e.getMessage());
        }
    }
    
    /**
     * 加载图片缓存信息
     */
    private void loadImageCache() {
        // 这里可以从文件加载已有的图片信息缓存
        // 简化实现，实际可以使用JSON文件存储
        logger.info("ImageContentProcessor", "加载图片缓存信息完成");
    }
    
    /**
     * 处理图片URL，下载并分析图片
     */
    public CompletableFuture<ImageInfo> processImageAsync(String imageUrl, String roomId, String messageId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return processImage(imageUrl, roomId, messageId);
            } catch (Exception e) {
                logger.error("ImageContentProcessor", "处理图片失败: " + imageUrl + ", " + e.getMessage());
                return null;
            }
        }, downloadExecutor);
    }
    
    /**
     * 同步处理图片
     */
    public ImageInfo processImage(String imageUrl, String roomId, String messageId) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return null;
        }
        
        try {
            // 检查缓存
            String cacheKey = generateCacheKey(imageUrl);
            if (imageInfoCache.containsKey(cacheKey)) {
                return imageInfoCache.get(cacheKey);
            }
            
            // 下载图片
            byte[] imageData = downloadImage(imageUrl);
            if (imageData == null) {
                return null;
            }
            
            // 分析图片
            ImageInfo imageInfo = analyzeImage(imageData, imageUrl, roomId, messageId);
            if (imageInfo != null) {
                // 保存到缓存
                imageInfoCache.put(cacheKey, imageInfo);
                
                // 异步生成缩略图
                generateThumbnailAsync(imageData, imageInfo);
            }
            
            return imageInfo;
            
        } catch (Exception e) {
            logger.error("ImageContentProcessor", "处理图片失败: " + imageUrl + ", " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 下载图片
     */
    private byte[] downloadImage(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(imageUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                logger.warn("ImageContentProcessor", "下载图片失败，响应码: " + responseCode + ", URL: " + imageUrl);
                return null;
            }
            
            try (InputStream inputStream = connection.getInputStream();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                int totalBytes = 0;
                
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    if (totalBytes + bytesRead > MAX_DOWNLOAD_SIZE) {
                        logger.warn("ImageContentProcessor", "图片文件过大，跳过下载: " + imageUrl);
                        return null;
                    }
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                }
                
                return outputStream.toByteArray();
            }
            
        } catch (Exception e) {
            logger.error("ImageContentProcessor", "下载图片异常: " + imageUrl + ", " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * 分析图片信息
     */
    private ImageInfo analyzeImage(byte[] imageData, String imageUrl, String roomId, String messageId) {
        return analyzeImage(imageData, imageUrl, roomId, messageId, null, null, System.currentTimeMillis());
    }
    
    /**
     * 分析图片信息（完整版本）
     */
    private ImageInfo analyzeImage(byte[] imageData, String imageUrl, String roomId, String messageId, 
                                  String nickName, String starName, long timestamp) {
        try {
            // 读取图片
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                logger.warn("ImageContentProcessor", "无法解析图片: " + imageUrl);
                return null;
            }
            
            // 计算图片哈希
            String imageHash = calculateImageHash(imageData);
            
            // 检测重复图片
            boolean isDuplicate = imageHashCache.containsValue(imageHash);
            if (!isDuplicate) {
                imageHashCache.put(generateCacheKey(imageUrl), imageHash);
            }
            
            // 从URL提取文件名
            String fileName = extractFileName(imageUrl);
            
            // 创建图片信息
            ImageInfo imageInfo = new ImageInfo();
            imageInfo.url = imageUrl;
            imageInfo.roomId = roomId;
            imageInfo.messageId = messageId;
            imageInfo.nickName = nickName;
            imageInfo.starName = starName;
            imageInfo.fileName = fileName;
            imageInfo.width = image.getWidth();
            imageInfo.height = image.getHeight();
            imageInfo.fileSize = imageData.length;
            imageInfo.format = getImageFormat(imageUrl);
            imageInfo.hash = imageHash;
            imageInfo.isDuplicate = isDuplicate;
            imageInfo.aspectRatio = (double) imageInfo.width / imageInfo.height;
            imageInfo.timestamp = timestamp;
            imageInfo.processedTime = System.currentTimeMillis();
            
            // 分析图片类型
            imageInfo.imageType = analyzeImageType(imageInfo);
            
            // 保存原图到缓存
            saveImageToCache(imageData, imageInfo);
            
            logger.debug("ImageContentProcessor", "图片分析完成: " + imageUrl + 
                        ", 尺寸: " + imageInfo.width + "x" + imageInfo.height + 
                        ", 大小: " + imageInfo.fileSize + " bytes");
            
            return imageInfo;
            
        } catch (Exception e) {
            logger.error("ImageContentProcessor", "分析图片失败: " + imageUrl + ", " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 从URL提取文件名
     */
    private String extractFileName(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return "unknown";
        }
        
        try {
            // 从URL中提取文件名
            String[] parts = imageUrl.split("/");
            String fileName = parts[parts.length - 1];
            
            // 移除查询参数
            int queryIndex = fileName.indexOf('?');
            if (queryIndex > 0) {
                fileName = fileName.substring(0, queryIndex);
            }
            
            return fileName.isEmpty() ? "unknown" : fileName;
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * 分析图片类型
     */
    private String analyzeImageType(ImageInfo imageInfo) {
        // 根据尺寸和宽高比判断图片类型
        if (imageInfo.width == imageInfo.height) {
            return "square"; // 正方形图片
        } else if (imageInfo.aspectRatio > 1.5) {
            return "landscape"; // 横向图片
        } else if (imageInfo.aspectRatio < 0.7) {
            return "portrait"; // 纵向图片
        } else {
            return "normal"; // 普通图片
        }
    }
    
    /**
     * 获取图片格式
     */
    private String getImageFormat(String imageUrl) {
        String url = imageUrl.toLowerCase();
        if (url.contains(".jpg") || url.contains(".jpeg")) {
            return "JPEG";
        } else if (url.contains(".png")) {
            return "PNG";
        } else if (url.contains(".gif")) {
            return "GIF";
        } else if (url.contains(".webp")) {
            return "WEBP";
        } else {
            return "UNKNOWN";
        }
    }
    
    /**
     * 计算图片哈希值
     */
    private String calculateImageHash(byte[] imageData) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(imageData);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(imageData.length); // 降级方案
        }
    }
    
    /**
     * 生成缓存键
     */
    private String generateCacheKey(String imageUrl) {
        return String.valueOf(imageUrl.hashCode());
    }
    
    /**
     * 保存图片到缓存
     */
    private void saveImageToCache(byte[] imageData, ImageInfo imageInfo) {
        try {
            String filename = imageInfo.hash + "." + imageInfo.format.toLowerCase();
            Path cachePath = Paths.get(IMAGE_CACHE_DIR, filename);
            
            if (!Files.exists(cachePath)) {
                Files.write(cachePath, imageData);
                imageInfo.cachedPath = cachePath.toString();
            }
            
        } catch (Exception e) {
            logger.error("ImageContentProcessor", "保存图片缓存失败: " + e.getMessage());
        }
    }
    
    /**
     * 异步生成缩略图
     */
    private void generateThumbnailAsync(byte[] imageData, ImageInfo imageInfo) {
        CompletableFuture.runAsync(() -> {
            try {
                generateThumbnail(imageData, imageInfo);
            } catch (Exception e) {
                logger.error("ImageContentProcessor", "生成缩略图失败: " + e.getMessage());
            }
        }, downloadExecutor);
    }
    
    /**
     * 生成缩略图
     */
    private void generateThumbnail(byte[] imageData, ImageInfo imageInfo) {
        try {
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageData));
            if (originalImage == null) {
                return;
            }
            
            // 计算缩略图尺寸
            int thumbnailWidth, thumbnailHeight;
            if (imageInfo.width > imageInfo.height) {
                thumbnailWidth = THUMBNAIL_SIZE;
                thumbnailHeight = (int) (THUMBNAIL_SIZE / imageInfo.aspectRatio);
            } else {
                thumbnailHeight = THUMBNAIL_SIZE;
                thumbnailWidth = (int) (THUMBNAIL_SIZE * imageInfo.aspectRatio);
            }
            
            // 创建缩略图
            BufferedImage thumbnail = new BufferedImage(thumbnailWidth, thumbnailHeight, BufferedImage.TYPE_INT_RGB);
            thumbnail.createGraphics().drawImage(originalImage.getScaledInstance(thumbnailWidth, thumbnailHeight, BufferedImage.SCALE_SMOOTH), 0, 0, null);
            
            // 保存缩略图
            String thumbnailFilename = imageInfo.hash + "_thumb.jpg";
            Path thumbnailPath = Paths.get(THUMBNAIL_DIR, thumbnailFilename);
            ImageIO.write(thumbnail, "JPEG", thumbnailPath.toFile());
            
            imageInfo.thumbnailPath = thumbnailPath.toString();
            
        } catch (Exception e) {
            logger.error("ImageContentProcessor", "生成缩略图失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取房间图片统计
     */
    public RoomImageStats getRoomImageStats(String roomId, LocalDate date) {
        RoomImageStats stats = new RoomImageStats();
        stats.roomId = roomId;
        stats.date = date;
        
        // 统计该房间当日的图片信息
        for (ImageInfo imageInfo : imageInfoCache.values()) {
            if (roomId.equals(imageInfo.roomId)) {
                // 这里需要根据时间过滤，简化实现
                stats.totalImages++;
                stats.totalSize += imageInfo.fileSize;
                
                // 按格式统计
                stats.formatCount.merge(imageInfo.format, 1, Integer::sum);
                
                // 按类型统计
                stats.typeCount.merge(imageInfo.imageType, 1, Integer::sum);
                
                // 记录重复图片
                if (imageInfo.isDuplicate) {
                    stats.duplicateImages++;
                }
                
                // 记录大图片
                if (imageInfo.fileSize > 1024 * 1024) { // 1MB
                    stats.largeImages++;
                }
            }
        }
        
        return stats;
    }
    
    /**
     * 清理过期缓存
     */
    public void cleanupExpiredCache() {
        try {
            long cutoffTime = System.currentTimeMillis() - (CACHE_RETENTION_DAYS * 24 * 60 * 60 * 1000L);
            
            // 清理过期的图片信息
            imageInfoCache.entrySet().removeIf(entry -> entry.getValue().processedTime < cutoffTime);
            
            // 清理过期的文件
            cleanupExpiredFiles(IMAGE_CACHE_DIR, cutoffTime);
            cleanupExpiredFiles(THUMBNAIL_DIR, cutoffTime);
            
            logger.info("ImageContentProcessor", "清理过期缓存完成");
            
        } catch (Exception e) {
            logger.error("ImageContentProcessor", "清理过期缓存失败: " + e.getMessage());
        }
    }
    
    /**
     * 清理过期文件
     */
    private void cleanupExpiredFiles(String directory, long cutoffTime) {
        try {
            Files.list(Paths.get(directory))
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() < cutoffTime;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            logger.warn("ImageContentProcessor", "删除过期文件失败: " + path);
                        }
                    });
        } catch (Exception e) {
            logger.error("ImageContentProcessor", "清理目录失败: " + directory + ", " + e.getMessage());
        }
    }
    
    /**
     * 关闭处理器
     */
    public void shutdown() {
        try {
            downloadExecutor.shutdown();
            if (!downloadExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                downloadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            downloadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("ImageContentProcessor", "图片处理器已关闭");
    }
    
    /**
     * 图片信息类
     */
    public static class ImageInfo {
        public String url;
        public String roomId;
        public String messageId;
        public String nickName;
        public String starName;
        public String fileName;
        public int width;
        public int height;
        public long fileSize;
        public String format;
        public String hash;
        public boolean isDuplicate;
        public double aspectRatio;
        public String imageType;
        public long timestamp;
        public long processedTime;
        public String cachedPath;
        public String thumbnailPath;
    }
    
    /**
     * 房间图片统计类
     */
    public static class RoomImageStats {
        public String roomId;
        public LocalDate date;
        public int totalImages = 0;
        public long totalSize = 0;
        public int duplicateImages = 0;
        public int largeImages = 0;
        public Map<String, Integer> formatCount = new HashMap<>();
        public Map<String, Integer> typeCount = new HashMap<>();
    }
}