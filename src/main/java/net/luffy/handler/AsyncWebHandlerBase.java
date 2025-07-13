package net.luffy.handler;

import net.luffy.Newboy;
import net.luffy.util.Properties;
import net.luffy.util.MonitorConfig;
import okhttp3.*;

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
    
    // OkHttp客户端
    protected final OkHttpClient httpClient;
    
    // 性能监控
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long lastRequestTime = 0;

    public AsyncWebHandlerBase() {
        this.properties = Newboy.INSTANCE.getProperties();
        this.httpClient = createHttpClient();
    }
    
    /**
     * 创建OkHttp客户端
     */
    private OkHttpClient createHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.getReadTimeout(), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .build();
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
     * 同步POST请求（兼容旧接口）
     */
    protected String post(String url, String body) {
        try {
            return postAsync(url, body).get(config.getReadTimeout(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("POST请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 带Headers的同步POST请求（兼容旧接口）
     */
    protected String post(String url, okhttp3.Headers headers, String body) {
        try {
            RequestBody requestBody = RequestBody.create(body, MediaType.parse("application/json; charset=utf-8"));
            Request.Builder builder = buildRequest(url).post(requestBody);
            if (headers != null) {
                builder.headers(headers);
            }
            Request request = builder.build();
            
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                response.close();
                throw new RuntimeException("HTTP请求失败: " + response.code() + " " + url);
            }
            
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                response.close();
                throw new RuntimeException("响应体为空: " + url);
            }
            
            String result = responseBody.string();
            response.close();
            return result;
        } catch (Exception e) {
            throw new RuntimeException("POST请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 同步GET请求（兼容旧接口）
     */
    protected String get(String url) {
        try {
            return getAsync(url).get(config.getReadTimeout(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("GET请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 带Headers的同步GET请求（兼容旧接口）
     */
    protected String get(String url, okhttp3.Headers headers) {
        try {
            Request.Builder builder = buildRequest(url).get();
            if (headers != null) {
                builder.headers(headers);
            }
            Request request = builder.build();
            
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                response.close();
                throw new RuntimeException("HTTP请求失败: " + response.code() + " " + url);
            }
            
            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                throw new RuntimeException("响应体为空: " + url);
            }
            
            String result = body.string();
            response.close();
            return result;
        } catch (Exception e) {
            throw new RuntimeException("GET请求失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 异步POST请求
     */
    protected CompletableFuture<String> postAsync(String url, String body) {
        RequestBody requestBody = RequestBody.create(body, MediaType.parse("application/json; charset=utf-8"));
        Request request = buildRequest(url)
                .post(requestBody)
                .build();
        
        return executeRequestAsync(request, url, "POST");
    }

    /**
     * 异步GET请求
     */
    protected CompletableFuture<String> getAsync(String url) {
        Request request = buildRequest(url)
                .get()
                .build();
        
        return executeRequestAsync(request, url, "GET");
    }
    
    /**
     * 获取输入流（用于下载文件等）
     */
    protected InputStream getInputStream(String url) {
        try {
            Request request = buildRequest(url).get().build();
            Response response = httpClient.newCall(request).execute();
            
            if (!response.isSuccessful()) {
                response.close();
                throw new RuntimeException("HTTP请求失败: " + response.code() + " " + url);
            }
            
            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                throw new RuntimeException("响应体为空: " + url);
            }
            
            return body.byteStream();
        } catch (IOException e) {
            throw new RuntimeException("获取输入流失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 构建请求对象
     */
    protected Request.Builder buildRequest(String url) {
        return new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .addHeader("Connection", "keep-alive");
    }
    
    /**
     * 执行异步请求
     */
    private CompletableFuture<String> executeRequestAsync(Request request, String url, String method) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        executeWithRetryAsync(request, url, method, 0, future);
        
        return future;
    }
    
    /**
     * 带重试机制的异步请求执行
     */
    private void executeWithRetryAsync(Request request, String url, String method, int retryCount, CompletableFuture<String> future) {
        requestCount.incrementAndGet();
        lastRequestTime = System.currentTimeMillis();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                failureCount.incrementAndGet();
                
                if (retryCount < config.getMaxRetries()) {
                    long delay = calculateRetryDelay(retryCount + 1);
                    logWarning(String.format("请求失败 [%s %s] - 第 %d 次重试，%d 毫秒后重试: %s", 
                        method, url, retryCount + 1, delay, e.getMessage()));
                    
                    // 延迟重试
                    CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
                            .execute(() -> executeWithRetryAsync(request, url, method, retryCount + 1, future));
                } else {
                    logError(String.format("请求最终失败 [%s %s] - 已重试 %d 次: %s", 
                        method, url, config.getMaxRetries(), e.getMessage()));
                    future.completeExceptionally(new RuntimeException("请求失败，已达到最大重试次数: " + e.getMessage(), e));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        response.close();
                        onFailure(call, new IOException("HTTP错误: " + response.code()));
                        return;
                    }
                    
                    ResponseBody body = response.body();
                    if (body == null) {
                        response.close();
                        onFailure(call, new IOException("响应体为空"));
                        return;
                    }
                    
                    String responseText = body.string();
                    response.close();
                    
                    if (retryCount > 0) {
                        logInfo(String.format("请求成功 [%s %s] - 重试 %d 次后成功", method, url, retryCount));
                    }
                    
                    future.complete(responseText);
                } catch (Exception e) {
                    response.close();
                    onFailure(call, new IOException("处理响应失败: " + e.getMessage(), e));
                }
            }
        });
    }
    
    /**
     * 计算重试延迟（指数退避）
     */
    protected long calculateRetryDelay(int retryCount) {
        return Math.min(config.getRetryBaseDelay() * (1L << (retryCount - 1)), config.getRetryMaxDelay());
    }
    
    /**
     * 设置请求头（子类可重写）
     */
    protected Request.Builder setHeader(Request.Builder builder) {
        return builder;
    }
    
    /**
     * Hutool HttpRequest兼容性方法 - 用于旧代码迁移
     * @deprecated 建议使用新的异步方法
     */
    @Deprecated
    protected cn.hutool.http.HttpRequest setHeader(cn.hutool.http.HttpRequest request) {
        // 为了兼容性，直接返回原请求对象
        // 子类可以重写此方法来设置特定的请求头
        return request;
    }
    
    /**
     * 获取性能统计信息
     */
    public String getPerformanceStats() {
        int total = requestCount.get();
        int failures = failureCount.get();
        double successRate = total > 0 ? ((double)(total - failures) / total) * 100 : 0;
        
        return String.format("异步请求统计: 总计 %d, 失败 %d, 成功率 %.1f%%, 最后请求: %s",
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
     * 关闭HTTP客户端
     */
    public void shutdown() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }
}