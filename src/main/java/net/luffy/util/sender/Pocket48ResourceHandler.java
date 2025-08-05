package net.luffy.util.sender;

import net.luffy.handler.AsyncWebHandlerBase;
import net.luffy.util.UnifiedHttpClient;
// OkHttp imports removed - migrated to UnifiedHttpClient

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 口袋48资源处理器
 * 专门处理口袋48的视频、音频、图片等资源下载
 * 解决认证头缺失导致的资源无法访问问题
 */
public class Pocket48ResourceHandler extends AsyncWebHandlerBase {
    
    /**
     * 获取口袋48资源的输入流（带缓存支持）
     * 添加必要的请求头以确保资源能够正常访问
     * 
     * @param url 资源URL
     * @return 资源输入流
     * @throws RuntimeException 当请求失败时抛出
     */
    public InputStream getPocket48InputStream(String url) {
        try {
            // 首先检查缓存
            Pocket48ResourceCache cache = Pocket48ResourceCache.getInstance();
            File cachedFile = cache.getCachedFile(url);
            if (cachedFile != null) {
                return new FileInputStream(cachedFile);
            }
            
            // 缓存未命中，从网络获取
            // 为了避免资源泄漏，我们下载到临时文件然后返回文件流
            // 根据URL推断文件扩展名，避免使用.tmp后缀
            String fileExtension = getFileExtensionFromUrl(url);
            File tempFile = downloadToTempFileInternal(url, fileExtension);
            return new FileInputStream(tempFile);
        } catch (IOException e) {
            throw new RuntimeException("获取口袋48资源流失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 从URL推断文件扩展名
     * @param url 资源URL
     * @return 文件扩展名
     */
    private String getFileExtensionFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return ".jpg"; // 默认为图片格式
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
        if (lowerUrl.contains("/image/") || lowerUrl.contains("/img/") || lowerUrl.contains("/cover/") || 
            lowerUrl.contains("cover") || lowerUrl.contains("thumb") || lowerUrl.contains("emotion") ||
            lowerUrl.contains("express") || lowerUrl.contains("emoji")) {
            return ".jpg";
        } else if (lowerUrl.contains("/video/") || lowerUrl.contains("/mp4/")) {
            return ".mp4";
        } else if (lowerUrl.contains("/audio/") || lowerUrl.contains("/voice/")) {
            return ".amr";
        }
        
        // 对于口袋48的资源，默认为图片格式
        return ".jpg";
    }
    
    /**
     * 检查口袋48资源是否可访问
     * 
     * @param url 资源URL
     * @return 资源信息对象
     */
    public Pocket48ResourceInfo checkResourceAvailability(String url) {
        try {
            // 使用UnifiedHttpClient进行HEAD请求检查资源可用性
            // 注意：UnifiedHttpClient目前不支持HEAD请求，使用GET请求代替
            String response = UnifiedHttpClient.getInstance().get(url, getPocket48Headers());
            
            Pocket48ResourceInfo info = new Pocket48ResourceInfo();
            info.setUrl(url);
            info.setAvailable(true); // 如果没有异常，说明资源可访问
            info.setStatusCode(200);
            info.setContentType("application/octet-stream"); // 默认类型
            
            return info;
        } catch (Exception e) {
            Pocket48ResourceInfo info = new Pocket48ResourceInfo();
            info.setUrl(url);
            info.setAvailable(false);
            info.setErrorMessage(e.getMessage());
            return info;
        }
    }
    
    /**
     * 获取口袋48专用的请求头
     * 添加必要的认证头信息
     * 
     * @return 请求头Map
     */
    private java.util.Map<String, String> getPocket48Headers() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        // 口袋48专用User-Agent
        headers.put("User-Agent", "PocketFans201807/7.1.34 (iPhone; iOS 19.0; Scale/2.00)");
        // 重要：添加Referer头，某些资源需要此头信息
        headers.put("Referer", "https://pocketapi.48.cn/");
        // 接受所有类型的内容
        headers.put("Accept", "*/*");
        // 语言偏好
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        // 连接保持
        headers.put("Connection", "keep-alive");
        // 缓存控制
        headers.put("Cache-Control", "no-cache");
        // 编码支持
        headers.put("Accept-Encoding", "gzip, deflate, br");
        return headers;
    }
    
    /**
     * 带重试机制的资源获取 - 优化为异步重试
     * 
     * @param url 资源URL
     * @param maxRetries 最大重试次数
     * @return 资源输入流
     */
    public InputStream getPocket48InputStreamWithRetry(String url, int maxRetries) {
        Exception lastException = null;
        
        for (int i = 0; i <= maxRetries; i++) {
            try {
                return getPocket48InputStream(url);
            } catch (Exception e) {
                lastException = e;
                if (i < maxRetries) {
                    // 优化的指数退避重试策略：适当放宽等待时间，最大等待5秒
                    long waitTime = Math.min(500 * (long) Math.pow(1.5, i), 5000);
                    try {
                        delayAsync(waitTime).get();
                    } catch (Exception ex) {
                        // 延迟失败时继续重试
                    }
                }
            }
        }
        
        throw new RuntimeException("重试 " + maxRetries + " 次后仍然失败: " + lastException.getMessage(), lastException);
    }
    
    /**
     * 下载口袋48资源到本地临时文件（带缓存支持）
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名（如 ".mp4", ".jpg"）
     * @return 本地临时文件
     * @throws RuntimeException 当下载失败时抛出
     */
    public File downloadToTempFile(String url, String fileExtension) {
        // 首先检查缓存
        Pocket48ResourceCache cache = Pocket48ResourceCache.getInstance();
        File cachedFile = cache.getCachedFile(url);
        if (cachedFile != null) {
            return cachedFile;
        }
        
        // 缓存未命中，下载文件
        return downloadToTempFileWithRetry(url, fileExtension, 3);
    }
    
    /**
     * 带重试机制下载口袋48资源到本地临时文件 - 优化为异步重试
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名（如 ".mp4", ".jpg"）
     * @param maxRetries 最大重试次数
     * @return 本地临时文件
     * @throws RuntimeException 当下载失败时抛出
     */
    public File downloadToTempFileWithRetry(String url, String fileExtension, int maxRetries) {
        Exception lastException = null;
        
        for (int i = 0; i <= maxRetries; i++) {
            try {
                return downloadToTempFileInternal(url, fileExtension);
            } catch (Exception e) {
                lastException = e;
                if (i < maxRetries) {
                    // 优化的指数退避重试策略：适当放宽等待时间，最大等待5秒
                    long waitTime = Math.min(500 * (long) Math.pow(1.5, i), 5000);
                    try {
                        delayAsync(waitTime).get();
                    } catch (Exception ex) {
                        // 延迟失败时继续重试
                    }
                    // 下载重试
                }
            }
        }
        
        throw new RuntimeException("下载重试 " + maxRetries + " 次后仍然失败: " + lastException.getMessage(), lastException);
    }
    
    /**
     * 异步延迟方法，替代Thread.sleep避免阻塞
     * @param delayMs 延迟毫秒数
     * @return CompletableFuture用于异步处理
     */
    private java.util.concurrent.CompletableFuture<Void> delayAsync(long delayMs) {
        try {
            // 使用统一调度器进行真正的异步延迟
            java.util.concurrent.CompletableFuture<Void> delayFuture = new java.util.concurrent.CompletableFuture<>();
            
            net.luffy.util.UnifiedSchedulerManager.getInstance().getExecutor().execute(() -> {
                try {
                    Thread.sleep(delayMs);
                    delayFuture.complete(null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    delayFuture.completeExceptionally(e);
                }
            });
            
            return delayFuture;
        } catch (Exception e) {
            // 如果异步延迟失败，返回立即完成的Future
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }
    
    /**
     * 内部下载方法（集成缓存，增强版）
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名
     * @return 本地临时文件
     * @throws IOException 当下载失败时抛出
     */
    private File downloadToTempFileInternal(String url, String fileExtension) throws IOException {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(this.getClass().getName());
        logger.setUseParentHandlers(false); // 不在控制台显示日志
        
        logger.info("开始下载资源: " + url);
        
        Pocket48ResourceCache cache = Pocket48ResourceCache.getInstance();
        
        // 使用UnifiedHttpClient下载文件并直接缓存
        try (InputStream inputStream = UnifiedHttpClient.getInstance().getInputStreamWithHeaders(url, getPocket48Headers())) {
            if (inputStream == null) {
                throw new IOException("获取输入流失败，可能是网络问题或资源不存在: " + url);
            }
            
            // 使用BufferedInputStream提高读取性能
            try (java.io.BufferedInputStream bufferedStream = new java.io.BufferedInputStream(inputStream, 8192)) {
                // 直接从流缓存文件
                File cachedFile = cache.cacheFromStream(url, bufferedStream, fileExtension);
                if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                    logger.info("资源下载并缓存成功: " + url + ", 文件大小: " + cachedFile.length() + " bytes");
                    
                    // 验证下载的文件是否为有效图片（如果是图片文件）
                    if (fileExtension != null && (fileExtension.toLowerCase().contains("jpg") || 
                        fileExtension.toLowerCase().contains("jpeg") || 
                        fileExtension.toLowerCase().contains("png") || 
                        fileExtension.toLowerCase().contains("gif"))) {
                        
                        // 验证图片完整性
                        if (!net.luffy.util.ImageFormatDetector.validateImageIntegrity(cachedFile)) {
                            logger.warning("下载的图片文件可能损坏: " + cachedFile.getAbsolutePath());
                            // 不抛出异常，让上层处理
                        }
                    }
                    
                    return cachedFile;
                } else {
                    throw new IOException("缓存文件失败或文件为空: " + url);
                }
            }
        } catch (Exception e) {
            logger.severe("下载失败: " + e.getMessage() + ", URL: " + url);
            throw new IOException("下载失败: " + e.getMessage() + " " + url, e);
        }
    }
    
    /**
     * 口袋48资源信息类
     */
    public static class Pocket48ResourceInfo {
        private String url;
        private boolean available;
        private int statusCode;
        private String contentType;
        private String contentLength;
        private String errorMessage;
        
        // Getters and Setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        
        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
        
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        
        public String getContentLength() { return contentLength; }
        public void setContentLength(String contentLength) { this.contentLength = contentLength; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        @Override
        public String toString() {
            return String.format("Pocket48ResourceInfo{url='%s', available=%s, statusCode=%d, contentType='%s', contentLength='%s', errorMessage='%s'}",
                    url, available, statusCode, contentType, contentLength, errorMessage);
        }
    }
}