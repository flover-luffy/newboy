package net.luffy.util.sender;

import net.luffy.handler.AsyncWebHandlerBase;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;

/**
 * 口袋48资源处理器
 * 专门处理口袋48的视频、音频、图片等资源下载
 * 解决认证头缺失导致的资源无法访问问题
 */
public class Pocket48ResourceHandler extends AsyncWebHandlerBase {
    
    /**
     * 获取口袋48资源的输入流
     * 添加必要的请求头以确保资源能够正常访问
     * 
     * @param url 资源URL
     * @return 资源输入流
     * @throws RuntimeException 当请求失败时抛出
     */
    public InputStream getPocket48InputStream(String url) {
        try {
            Request request = buildPocket48Request(url).get().build();
            Response response = httpClient.newCall(request).execute();
            
            if (!response.isSuccessful()) {
                response.close();
                throw new RuntimeException("口袋48资源请求失败: " + response.code() + " " + url);
            }
            
            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                throw new RuntimeException("口袋48资源响应体为空: " + url);
            }
            
            return body.byteStream();
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
            Request request = buildPocket48Request(url).head().build();
            Response response = httpClient.newCall(request).execute();
            
            Pocket48ResourceInfo info = new Pocket48ResourceInfo();
            info.setUrl(url);
            info.setAvailable(response.isSuccessful());
            info.setStatusCode(response.code());
            info.setContentType(response.header("Content-Type"));
            info.setContentLength(response.header("Content-Length"));
            
            response.close();
            return info;
        } catch (IOException e) {
            Pocket48ResourceInfo info = new Pocket48ResourceInfo();
            info.setUrl(url);
            info.setAvailable(false);
            info.setErrorMessage(e.getMessage());
            return info;
        }
    }
    
    /**
     * 构建口袋48专用的请求对象
     * 添加必要的认证头信息
     * 
     * @param url 请求URL
     * @return 请求构建器
     */
    private Request.Builder buildPocket48Request(String url) {
        return new Request.Builder()
                .url(url)
                // 口袋48专用User-Agent
                .addHeader("User-Agent", "PocketFans201807/7.1.34 (iPhone; iOS 19.0; Scale/2.00)")
                // 重要：添加Referer头，某些资源需要此头信息
                .addHeader("Referer", "https://pocketapi.48.cn/")
                // 接受所有类型的内容
                .addHeader("Accept", "*/*")
                // 语言偏好
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                // 连接保持
                .addHeader("Connection", "keep-alive")
                // 缓存控制
                .addHeader("Cache-Control", "no-cache")
                // 编码支持
                .addHeader("Accept-Encoding", "gzip, deflate, br");
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