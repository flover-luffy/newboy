package net.luffy.handler;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import net.luffy.Newboy;
import net.luffy.util.Properties;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 增强的Web处理器基类
 * 提供重试机制、错误处理、性能监控等功能
 */
public class WebHandler {

    public final Properties properties;
    private String cronScheduleID;
    
    // 配置管理
    private final net.luffy.util.MonitorConfig config = net.luffy.util.MonitorConfig.getInstance();
    
    // 性能监控
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastRequestTime = 0;

    public WebHandler() {
        this.properties = Newboy.INSTANCE.getProperties();
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
     * 增强的POST请求，支持重试机制
     */
    protected String post(String url, String body) {
        return executeWithRetry(() -> {
            HttpRequest request = setHeader(HttpRequest.post(url)).body(body);
            HttpResponse response = request.execute();
            validateResponse(response, url);
            return response.body();
        }, url, "POST");
    }

    /**
     * 增强的GET请求，支持重试机制
     */
    protected String get(String url) {
        return executeWithRetry(() -> {
            HttpRequest request = setHeader(HttpRequest.get(url));
            HttpResponse response = request.execute();
            validateResponse(response, url);
            return response.body();
        }, url, "GET");
    }
    
    /**
     * 带重试机制的请求执行器
     */
    protected String executeWithRetry(RequestExecutor executor, String url, String method) {
        int retries = 0;
        Exception lastException = null;
        
        while (retries <= config.getMaxRetries()) {
            try {
                requestCount.incrementAndGet();
                lastRequestTime = System.currentTimeMillis();
                
                String result = executor.execute();
                
                // 请求成功，记录日志
                if (retries > 0) {
                    logInfo(String.format("请求成功 [%s %s] - 重试 %d 次后成功", method, url, retries));
                }
                
                return result;
                
            } catch (Exception e) {
                lastException = e;
                retries++;
                failureCount.incrementAndGet();
                
                if (retries <= config.getMaxRetries()) {
                    long delay = calculateRetryDelay(retries);
                    logWarning(String.format("请求失败 [%s %s] - 第 %d 次重试，%d 毫秒后重试: %s", 
                        method, url, retries, delay, e.getMessage()));
                    
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("请求被中断", ie);
                    }
                } else {
                    logError(String.format("请求最终失败 [%s %s] - 已重试 %d 次: %s", 
                        method, url, config.getMaxRetries(), e.getMessage()));
                }
            }
        }
        
        throw new RuntimeException(String.format("请求失败，已达到最大重试次数 %d: %s", 
            config.getMaxRetries(), lastException.getMessage()), lastException);
    }
    
    /**
     * 计算重试延迟（指数退避）
     */
    protected long calculateRetryDelay(int retryCount) {
        return Math.min(config.getRetryBaseDelay() * (1L << (retryCount - 1)), config.getRetryMaxDelay());
    }
    
    /**
     * 验证HTTP响应
     */
    protected void validateResponse(HttpResponse response, String url) {
        if (response == null) {
            throw new RuntimeException("HTTP响应为空: " + url);
        }
        
        int statusCode = response.getStatus();
        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException(String.format("HTTP请求失败 [%d]: %s", statusCode, url));
        }
        
        String body = response.body();
        if (body == null || body.trim().isEmpty()) {
            throw new RuntimeException("HTTP响应体为空: " + url);
        }
    }

    public HttpRequest setHeader_Public(HttpRequest request) {
        if (!properties.save_login)
            return setHeader(request);
        else return request;
    }

    /**
     * 设置请求头，包含超时配置
     */
    protected HttpRequest setHeader(HttpRequest request) {
        return request
                .setConnectionTimeout(config.getConnectTimeout())
                .setReadTimeout(config.getReadTimeout())
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Connection", "keep-alive");
    }
    
    /**
     * 获取性能统计信息
     */
    public String getPerformanceStats() {
        int total = requestCount.get();
        int failures = failureCount.get();
        double successRate = total > 0 ? ((double)(total - failures) / total) * 100 : 0;
        
        return String.format("请求统计: 总计 %d, 失败 %d, 成功率 %.1f%%, 最后请求: %s",
            total, failures, successRate, 
            lastRequestTime > 0 ? new java.util.Date(lastRequestTime).toString() : "无");
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
     * 请求执行器接口
     */
    @FunctionalInterface
    protected interface RequestExecutor {
        String execute() throws Exception;
    }
}
