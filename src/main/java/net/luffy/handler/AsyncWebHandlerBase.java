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
     * 同步POST请求（兼容旧接口）- 带重试机制
     */
    protected String post(String url, String body) {
        int maxRetries = 3;
        long baseDelay = 1000; // 1秒基础延迟
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return UnifiedHttpClient.getInstance().post(url, body);
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("POST请求失败（已重试" + maxRetries + "次）: " + e.getMessage(), e);
                }
                
                // 指数退避重试
                try {
                    long delay = baseDelay * (1L << (attempt - 1));
                    Thread.sleep(Math.min(delay, 10000)); // 最大延迟10秒
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("POST请求被中断: " + e.getMessage(), e);
                }
            }
        }
        return null; // 不会到达这里
    }
    
    /**
     * 带Map Headers的同步POST请求
     */
    protected String post(String url, String body, Map<String, String> headers) {
        try {
            return UnifiedHttpClient.getInstance().post(url, body, headers);
        } catch (Exception e) {
            throw new RuntimeException("POST请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 带Headers的同步POST请求（兼容旧接口）
     */
    protected String post(String url, okhttp3.Headers headers, String body) {
        try {
            Map<String, String> headerMap = new HashMap<>();
            if (headers != null) {
                for (String name : headers.names()) {
                    headerMap.put(name, headers.get(name));
                }
            }
            return UnifiedHttpClient.getInstance().post(url, body, headerMap);
        } catch (Exception e) {
            throw new RuntimeException("POST请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 同步GET请求（兼容旧接口）- 带重试机制
     */
    protected String get(String url) {
        int maxRetries = 3;
        long baseDelay = 1000; // 1秒基础延迟
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return UnifiedHttpClient.getInstance().get(url);
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("GET请求失败（已重试" + maxRetries + "次）: " + e.getMessage(), e);
                }
                
                // 指数退避重试
                try {
                    long delay = baseDelay * (1L << (attempt - 1));
                    Thread.sleep(Math.min(delay, 10000)); // 最大延迟10秒
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("GET请求被中断: " + e.getMessage(), e);
                }
            }
        }
        return null; // 不会到达这里
    }
    
    /**
     * 带Headers的同步GET请求（兼容旧接口）- 带重试机制
     */
    protected String get(String url, okhttp3.Headers headers) {
        int maxRetries = 3;
        long baseDelay = 1000; // 1秒基础延迟
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Map<String, String> headerMap = new HashMap<>();
                if (headers != null) {
                    for (String name : headers.names()) {
                        headerMap.put(name, headers.get(name));
                    }
                }
                return UnifiedHttpClient.getInstance().get(url, headerMap);
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("GET请求失败（已重试" + maxRetries + "次）: " + e.getMessage(), e);
                }
                
                // 指数退避重试
                try {
                    long delay = baseDelay * (1L << (attempt - 1));
                    Thread.sleep(Math.min(delay, 10000)); // 最大延迟10秒
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("GET请求被中断: " + e.getMessage(), e);
                }
            }
        }
        return null; // 不会到达这里
    }
    
    /**
     * 带Map Headers的同步GET请求 - 带重试机制
     */
    protected String get(String url, Map<String, String> headers) {
        int maxRetries = 5;
        long baseDelay = 1000; // 1秒基础延迟
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return UnifiedHttpClient.getInstance().get(url, headers);
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("GET请求失败（已重试" + maxRetries + "次）: " + e.getMessage(), e);
                }
                
                // 指数退避重试，适当放宽延迟时间
                try {
                    long delay = Math.min(baseDelay * (long) Math.pow(1.5, attempt - 1), 5000);
                    Thread.sleep(delay); // 最大延迟5秒
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("GET请求被中断: " + e.getMessage(), e);
                }
            }
        }
        return null; // 不会到达这里
    }
    
    /**
     * 异步POST请求 - 使用统一HTTP客户端
     */
    protected CompletableFuture<String> postAsync(String url, String body) {
        requestCount.incrementAndGet();
        lastRequestTime = System.currentTimeMillis();
        
        return UnifiedHttpClient.getInstance().postAsync(url, body)
                .exceptionally(throwable -> {
                    failureCount.incrementAndGet();
                    throw new RuntimeException("异步POST请求失败: " + throwable.getMessage(), throwable);
                });
    }

    /**
     * 异步GET请求 - 使用统一HTTP客户端
     */
    protected CompletableFuture<String> getAsync(String url) {
        requestCount.incrementAndGet();
        lastRequestTime = System.currentTimeMillis();
        
        return UnifiedHttpClient.getInstance().getAsync(url)
                .exceptionally(throwable -> {
                    failureCount.incrementAndGet();
                    throw new RuntimeException("异步GET请求失败: " + throwable.getMessage(), throwable);
                });
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
    
    // HTTP请求构建和执行已迁移到UnifiedHttpClient
    
    /**
     * 计算重试延迟（指数退避）- 保留给子类使用
     */
    protected long calculateRetryDelay(int retryCount) {
        return Math.min(config.getRetryBaseDelay() * (1L << (retryCount - 1)), config.getRetryMaxDelay());
    }
    
    /**
     * 带自定义Headers的异步POST请求 - 使用统一HTTP客户端
     */
    protected CompletableFuture<String> postAsync(String url, Map<String, String> headers, String body) {
        requestCount.incrementAndGet();
        lastRequestTime = System.currentTimeMillis();
        
        return UnifiedHttpClient.getInstance().postAsync(url, body, headers)
                .exceptionally(throwable -> {
                    failureCount.incrementAndGet();
                    throw new RuntimeException("异步POST请求失败: " + throwable.getMessage(), throwable);
                });
    }

    /**
     * 带自定义Headers的异步GET请求 - 使用统一HTTP客户端
     */
    protected CompletableFuture<String> getAsync(String url, Map<String, String> headers) {
        requestCount.incrementAndGet();
        lastRequestTime = System.currentTimeMillis();
        
        return UnifiedHttpClient.getInstance().getAsync(url, headers)
                .exceptionally(throwable -> {
                    failureCount.incrementAndGet();
                    throw new RuntimeException("异步GET请求失败: " + throwable.getMessage(), throwable);
                });
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