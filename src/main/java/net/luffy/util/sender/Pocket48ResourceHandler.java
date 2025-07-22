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
            File tempFile = downloadToTempFileInternal(url, ".tmp");
            return new FileInputStream(tempFile);
        } catch (IOException e) {
            throw new RuntimeException("获取口袋48资源流失败: " + e.getMessage(), e);
        }
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
     * 带重试机制的资源获取
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
                    try {
                        // 指数退避重试策略
                        Thread.sleep(1000 * (long) Math.pow(2, i));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试被中断", ie);
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
     * 带重试机制下载口袋48资源到本地临时文件
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
                    try {
                        // 指数退避重试策略
                        Thread.sleep(1000 * (long) Math.pow(2, i));
                        // 下载重试
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("下载重试被中断", ie);
                    }
                }
            }
        }
        
        throw new RuntimeException("下载重试 " + maxRetries + " 次后仍然失败: " + lastException.getMessage(), lastException);
    }
    
    /**
     * 内部下载方法（集成缓存）
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名
     * @return 本地临时文件
     * @throws IOException 当下载失败时抛出
     */
    private File downloadToTempFileInternal(String url, String fileExtension) throws IOException {
        // 下载开始
        
        Pocket48ResourceCache cache = Pocket48ResourceCache.getInstance();
        
        // 使用UnifiedHttpClient下载文件并直接缓存
        try (InputStream inputStream = UnifiedHttpClient.getInstance().getInputStreamWithHeaders(url, getPocket48Headers())) {
            // 直接从流缓存文件
            File cachedFile = cache.cacheFromStream(url, inputStream, fileExtension);
            if (cachedFile != null) {
                // 下载完成
                return cachedFile;
            } else {
                throw new IOException("缓存文件失败: " + url);
            }
        } catch (Exception e) {
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