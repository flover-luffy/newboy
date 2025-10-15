package net.luffy.handler;

import net.luffy.Newboy;
import net.luffy.util.Properties;
import net.luffy.util.MonitorConfig;
import net.luffy.util.UnifiedHttpClient;
// OkHttp imports removed - migrated to UnifiedHttpClient
// HttpLoggingInterceptor import removed - migrated to UnifiedHttpClient

import java.util.Map;
import java.util.HashMap;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步Web处理器基类
 * 替换原有的WebHandler，提供异步HTTP处理能力
 */
public class AsyncWebHandlerBase {

    public final Properties properties;
    private String cronScheduleID;
    
    // 配置管理
    private final MonitorConfig config = MonitorConfig.getInstance();
    
    // HTTP客户端已迁移到UnifiedHttpClient
    
    // 性能监控
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastRequestTime = 0;

    public AsyncWebHandlerBase() {
        this.properties = Newboy.INSTANCE.getProperties();
        // HTTP客户端创建已迁移到UnifiedHttpClient
    }

    /*----------------------------------*/

    public String getCronScheduleID() {
        return cronScheduleID;
    }

    public void setCronScheduleID(String cronScheduleID) {
        this.cronScheduleID = cronScheduleID;
    }

    protected void logInfo(String msg) {
        properties.logger.info(msg);
    }

    protected void logError(String msg) {
        properties.logger.error(msg);
    }
    
    protected void logWarning(String msg) {
        properties.logger.warning(msg);
    }

    /**
     * 同步POST请求（兼容旧接口）- 使用UnifiedHttpClient的统一重试机制
     */
    protected String post(String url, String body) {
        return executeHttpRequest(() -> UnifiedHttpClient.getInstance().post(url, body), "POST");
    }
    
    /**
     * 带超时的同步POST请求 - 使用UnifiedHttpClient的统一重试机制
     */
    protected String postWithTimeout(String url, String body, Map<String, String> headers, int connectTimeout, int readTimeout) {
        return executeHttpRequest(() -> UnifiedHttpClient.getInstance().postWithTimeout(url, body, headers, connectTimeout, readTimeout), "POST");
    }
    
    /**
     * 带Map Headers的同步POST请求
     */
    protected String post(String url, String body, Map<String, String> headers) {
        return executeHttpRequest(() -> UnifiedHttpClient.getInstance().post(url, body, headers), "POST");
    }
    
    /**
     * 带Headers的同步POST请求（兼容旧接口）
     */
    protected String post(String url, okhttp3.Headers headers, String body) {
        Map<String, String> headerMap = convertOkHttpHeaders(headers);
        return executeHttpRequest(() -> UnifiedHttpClient.getInstance().post(url, body, headerMap), "POST");
    }

    /**
     * 同步GET请求（兼容旧接口）- 使用UnifiedHttpClient的统一重试机制
     */
    protected String get(String url) {
        return executeHttpRequest(() -> UnifiedHttpClient.getInstance().get(url), "GET");
    }
    
    /**
     * 带Headers的同步GET请求（兼容旧接口）- 使用UnifiedHttpClient的统一重试机制
     */
    protected String get(String url, okhttp3.Headers headers) {
        Map<String, String> headerMap = convertOkHttpHeaders(headers);
        return executeHttpRequest(() -> UnifiedHttpClient.getInstance().get(url, headerMap).getBody(), "GET");
    }
    
    /**
     * 带Map Headers的同步GET请求 - 使用UnifiedHttpClient的统一重试机制
     */
    protected String get(String url, Map<String, String> headers) {
        return executeHttpRequest(() -> UnifiedHttpClient.getInstance().get(url, headers).getBody(), "GET");
    }
    
    /**
     * 异步POST请求 - 使用统一HTTP客户端
     */
    protected CompletableFuture<String> postAsync(String url, String body) {
        return executeAsyncRequest(() -> UnifiedHttpClient.getInstance().postAsync(url, body), "异步POST");
    }

    /**
     * 异步GET请求 - 使用统一HTTP客户端
     */
    protected CompletableFuture<String> getAsync(String url) {
        return executeAsyncRequest(() -> UnifiedHttpClient.getInstance().getAsync(url), "异步GET");
    }
    
    /**
     * 获取输入流（用于下载文件等）
     */
    protected InputStream getInputStream(String url) {
        try {
            return UnifiedHttpClient.getInstance().getInputStream(url);
        } catch (Exception e) {
            throw new RuntimeException("获取输入流失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 通用HTTP请求执行方法，统一异常处理
     */
    private String executeHttpRequest(HttpRequestSupplier supplier, String method) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new RuntimeException(method + "请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 转换OkHttp Headers为Map格式
     */
    private Map<String, String> convertOkHttpHeaders(okhttp3.Headers headers) {
        Map<String, String> headerMap = new HashMap<>();
        if (headers != null) {
            for (String name : headers.names()) {
                headerMap.put(name, headers.get(name));
            }
        }
        return headerMap;
    }
    
    /**
     * HTTP请求供应商函数式接口
     */
    @FunctionalInterface
    private interface HttpRequestSupplier {
        String get() throws Exception;
    }
    
    // HTTP请求构建和执行已迁移到UnifiedHttpClient
    
    /**
     * 计算重试延迟（指数退避）- 保留给子类使用（延迟配置已移除）
     */
    protected long calculateRetryDelay(int retryCount) {
        // 使用默认配置（延迟配置已移除）
        long baseDelay = 1000L; // 默认基础延迟1秒
        double backoffMultiplier = 2.0; // 默认退避倍数
        long maxDelay = 30000L; // 默认最大延迟30秒
        return Math.min((long)(baseDelay * Math.pow(backoffMultiplier, retryCount - 1)), maxDelay);
    }
    
    /**
     * 带自定义Headers的异步POST请求 - 使用统一HTTP客户端
     */
    protected CompletableFuture<String> postAsync(String url, Map<String, String> headers, String body) {
        return executeAsyncRequest(() -> UnifiedHttpClient.getInstance().postAsync(url, body, headers), "异步POST");
    }

    /**
     * 带自定义Headers的异步GET请求 - 使用统一HTTP客户端
     */
    protected CompletableFuture<String> getAsync(String url, Map<String, String> headers) {
        return executeAsyncRequest(() -> UnifiedHttpClient.getInstance().getAsync(url, headers), "异步GET");
    }
    
    /**
     * 通用异步HTTP请求执行方法，统一性能监控和异常处理
     */
    private CompletableFuture<String> executeAsyncRequest(AsyncRequestSupplier supplier, String requestType) {
        requestCount.incrementAndGet();
        lastRequestTime = System.currentTimeMillis();
        
        return supplier.get()
                .exceptionally(throwable -> {
                    failureCount.incrementAndGet();
                    throw new RuntimeException(requestType + "请求失败: " + throwable.getMessage(), throwable);
                });
    }
    
    /**
     * 异步HTTP请求供应商函数式接口
     */
    @FunctionalInterface
    private interface AsyncRequestSupplier {
        CompletableFuture<String> get();
    }
    
    /**
     * 获取性能统计信息
     */
    public String getPerformanceStats() {
        int total = requestCount.get();
        int failures = failureCount.get();
        double successRate = total > 0 ? ((double)(total - failures) / total) * 100 : 0;
        
        // 格式化时间为统一格式 yyyy-MM-dd HH:mm:ss
        String formattedTime = "无";
        if (lastRequestTime > 0) {
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(lastRequestTime), 
                java.time.ZoneId.systemDefault()
            );
            formattedTime = dateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        
        return String.format("异步请求统计: 总计 %d, 失败 %d, 成功率 %.1f%%, 最后请求: %s",
            total, failures, successRate, formattedTime);
    }
    
    /**
     * 重置性能统计
     */
    public void resetStats() {
        requestCount.set(0);
        failureCount.set(0);
        lastRequestTime = 0;
    }
    
    /**
     * 关闭HTTP客户端 - 统一客户端由单例管理
     */
    public void shutdown() {
        // 重置统计数据
        resetStats();
        // 统一HTTP客户端由单例管理，无需手动关闭
    }
}