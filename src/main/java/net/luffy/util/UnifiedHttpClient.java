package net.luffy.util;

import okhttp3.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一HTTP客户端
 * 基于OkHttp实现，提供同步和异步请求功能
 * 支持连接池管理、重试机制、性能监控
 */
public class UnifiedHttpClient {
    
    private static volatile UnifiedHttpClient instance;
    private final OkHttpClient client;
    private final Executor asyncExecutor;
    
    // 性能统计 - 使用AtomicLong确保线程安全
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    
    private UnifiedHttpClient() {
        this.client = createOptimizedClient();
        this.asyncExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "UnifiedHttpClient-Async");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * 获取单例实例
     */
    public static UnifiedHttpClient getInstance() {
        if (instance == null) {
            synchronized (UnifiedHttpClient.class) {
                if (instance == null) {
                    instance = new UnifiedHttpClient();
                }
            }
        }
        return instance;
    }
    
    /**
     * 创建优化的OkHttp客户端
     */
    private OkHttpClient createOptimizedClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)  // 增加连接超时为10秒，提高网络不稳定情况下的成功率
                .readTimeout(15, TimeUnit.SECONDS)     // 增加读取超时为15秒，适应大文件下载
                .writeTimeout(15, TimeUnit.SECONDS)    // 增加写入超时为15秒，适应大文件上传
                .connectionPool(new ConnectionPool(50, 5, TimeUnit.MINUTES))  // 增加连接池大小到50
                .retryOnConnectionFailure(true)      // 启用连接失败重试机制
                .addInterceptor(new RetryInterceptor(3)) // 添加全局重试拦截器，最多重试3次
                .addInterceptor(new LoggingInterceptor())
                .addInterceptor(new PerformanceInterceptor())
                .build();
    }
    
    /**
     * 同步GET请求
     */
    public String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .addHeader("Connection", "keep-alive")
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP请求失败: " + response.code() + " " + response.message());
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }
    
    /**
     * 同步GET请求（支持自定义请求头）
     */
    public String get(String url, java.util.Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        
        // 添加默认请求头
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
               .addHeader("Accept", "application/json, text/plain, */*")
               .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
               .addHeader("Connection", "keep-alive");
        
        // 添加自定义请求头
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        Request request = builder.build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP请求失败: " + response.code() + " " + response.message());
            }
            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }
    
    /**
     * 同步POST请求
     */
    public String post(String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json, text/plain, */*")
                .addHeader("Content-Type", "application/json")
                .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP请求失败: " + response.code() + " " + response.message());
            }
            ResponseBody responseBody = response.body();
            return responseBody != null ? responseBody.string() : "";
        }
    }
    
    /**
     * 同步POST请求（支持自定义请求头）
     */
    public String post(String url, String body, java.util.Map<String, String> headers) throws IOException {
        // 根据Content-Type决定MediaType
        String contentType = "application/json; charset=utf-8";
        if (headers != null && headers.containsKey("Content-Type")) {
            contentType = headers.get("Content-Type");
        }
        
        RequestBody requestBody = RequestBody.create(body, MediaType.get(contentType));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(requestBody);
        
        // 添加默认请求头
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
               .addHeader("Accept", "application/json, text/plain, */*");
        
        // 添加自定义请求头
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        Request request = builder.build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP请求失败: " + response.code() + " " + response.message());
            }
            ResponseBody responseBody = response.body();
            return responseBody != null ? responseBody.string() : "";
        }
    }
    
    /**
     * 异步GET请求
     */
    public CompletableFuture<String> getAsync(String url) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json, text/plain, */*")
                .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        future.completeExceptionally(new IOException("HTTP请求失败: " + response.code()));
                        return;
                    }
                    ResponseBody body = response.body();
                    String result = body != null ? body.string() : "";
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                } finally {
                    response.close();
                }
            }
        });
        
        return future;
    }
    
    /**
     * 异步GET请求（支持自定义请求头）
     */
    public CompletableFuture<String> getAsync(String url, java.util.Map<String, String> headers) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Accept", "application/json, text/plain, */*");
        
        // 添加自定义请求头
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        Request request = builder.build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        future.completeExceptionally(new IOException("HTTP请求失败: " + response.code()));
                        return;
                    }
                    ResponseBody body = response.body();
                    String result = body != null ? body.string() : "";
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                } finally {
                    response.close();
                }
            }
        });
        
        return future;
    }
    
    /**
     * 异步POST请求
     */
    public CompletableFuture<String> postAsync(String url, String jsonBody) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Content-Type", "application/json")
                .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        future.completeExceptionally(new IOException("HTTP请求失败: " + response.code()));
                        return;
                    }
                    ResponseBody responseBody = response.body();
                    String result = responseBody != null ? responseBody.string() : "";
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                } finally {
                    response.close();
                }
            }
        });
        
        return future;
    }
    
    /**
     * 异步POST请求（支持自定义请求头）
     */
    public CompletableFuture<String> postAsync(String url, String body, java.util.Map<String, String> headers) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        RequestBody requestBody = RequestBody.create(body, MediaType.get("application/x-www-form-urlencoded; charset=utf-8"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(requestBody);
        
        // 添加默认请求头
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
               .addHeader("Accept", "application/json, text/plain, */*");
        
        // 添加自定义请求头
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        Request request = builder.build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        future.completeExceptionally(new IOException("HTTP请求失败: " + response.code()));
                        return;
                    }
                    ResponseBody responseBody = response.body();
                    String result = responseBody != null ? responseBody.string() : "";
                    future.complete(result);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                } finally {
                    response.close();
                }
            }
        });
        
        return future;
    }
    
    /**
     * 获取输入流（用于下载文件等）
     */
    public InputStream getInputStream(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
        
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("HTTP请求失败: " + response.code() + " " + response.message());
        }
        
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            response.close();
            throw new IOException("响应体为空");
        }
        
        return responseBody.byteStream();
    }
    
    /**
     * 获取输入流（带自定义请求头）
     */
    public InputStream getInputStreamWithHeaders(String url, java.util.Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        
        // 添加自定义请求头
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        Request request = builder.build();
        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            response.close();
            throw new IOException("HTTP请求失败: " + response.code() + " " + response.message());
        }
        
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            response.close();
            throw new IOException("响应体为空");
        }
        
        return responseBody.byteStream();
    }
    
    /**
     * 获取原始OkHttpClient（用于特殊需求）
     */
    public OkHttpClient getClient() {
        return client;
    }
    
    /**
     * 获取性能统计信息
     */
    public String getPerformanceStats() {
        long total = totalRequests.get();
        long successful = successfulRequests.get();
        long failed = failedRequests.get();
        long totalTime = totalResponseTime.get();
        long avgResponseTime = total > 0 ? totalTime / total : 0;
        double successRate = total > 0 ? (double) successful / total * 100 : 0;
        
        return String.format("HTTP性能统计 - 总请求: %d, 成功: %d, 失败: %d, 成功率: %.1f%%, 平均响应时间: %dms",
                total, successful, failed, successRate, avgResponseTime);
    }
    
    /**
     * 重置性能统计数据
     */
    public void resetStats() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        totalResponseTime.set(0);
    }
    
    /**
     * 性能监控拦截器
     */
    private class PerformanceInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            long startTime = System.currentTimeMillis();
            long currentTotal = totalRequests.incrementAndGet();
            
            // 添加调试日志
            if (System.getProperty("http.debug", "false").equals("true")) {
                System.out.printf("[HTTP-STATS] 请求开始 - 总请求数: %d, URL: %s%n", 
                    currentTotal, chain.request().url());
            }
            
            try {
                Response response = chain.proceed(chain.request());
                long responseTime = System.currentTimeMillis() - startTime;
                totalResponseTime.addAndGet(responseTime);
                
                if (response.isSuccessful()) {
                    long currentSuccessful = successfulRequests.incrementAndGet();
                    if (System.getProperty("http.debug", "false").equals("true")) {
                        System.out.printf("[HTTP-STATS] 请求成功 - 成功数: %d, 响应时间: %dms%n", 
                            currentSuccessful, responseTime);
                    }
                } else {
                    long currentFailed = failedRequests.incrementAndGet();
                    if (System.getProperty("http.debug", "false").equals("true")) {
                        System.out.printf("[HTTP-STATS] 请求失败 - 失败数: %d, 状态码: %d%n", 
                            currentFailed, response.code());
                    }
                }
                
                // 记录到全局性能监控器
                try {
                    net.luffy.util.PerformanceMonitor.getInstance().recordQuery(responseTime);
                } catch (Exception e) {
                    // 忽略性能监控记录失败，避免因为Mirai依赖问题导致HTTP请求失败
                }
                
                return response;
            } catch (IOException e) {
                long responseTime = System.currentTimeMillis() - startTime;
                totalResponseTime.addAndGet(responseTime);
                long currentFailed = failedRequests.incrementAndGet();
                
                if (System.getProperty("http.debug", "false").equals("true")) {
                    System.out.printf("[HTTP-STATS] 请求异常 - 失败数: %d, 异常: %s%n", 
                        currentFailed, e.getMessage());
                }
                
                // 记录到全局性能监控器
                try {
                    net.luffy.util.PerformanceMonitor.getInstance().recordQuery(responseTime);
                } catch (Exception ex) {
                    // 忽略性能监控记录失败，避免因为Mirai依赖问题导致HTTP请求失败
                }
                
                throw e;
            }
        }
    }
    
    /**
     * 重试拦截器
     * 实现全局HTTP请求重试机制，支持指数退避策略
     */
    private static class RetryInterceptor implements Interceptor {
        private final int maxRetries;
        private final long baseDelayMs = 1000; // 基础延迟1秒
        
        public RetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }
        
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            IOException lastException = null;
            int retryCount = 0;
            
            while (retryCount <= maxRetries) {
                try {
                    // 尝试执行请求
                    Response response = chain.proceed(request);
                    
                    // 检查响应是否成功
                    if (response.isSuccessful() || !isRetryable(response.code())) {
                        return response; // 成功或不可重试状态码，直接返回
                    }
                    
                    // 关闭当前响应，准备重试
                    if (response.body() != null) {
                        response.close();
                    }
                    
                    // 如果已达到最大重试次数，返回最后一个响应
                    if (retryCount == maxRetries) {
                        return response;
                    }
                    
                } catch (IOException e) {
                    // 保存异常，准备重试
                    lastException = e;
                    
                    // 如果已达到最大重试次数，抛出最后捕获的异常
                    if (retryCount == maxRetries) {
                        throw lastException;
                    }
                }
                
                // 计算重试延迟（指数退避策略）
                long delayMs = calculateRetryDelay(retryCount);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("重试被中断", e);
                }
                
                // 记录重试日志
                if (System.getProperty("http.debug", "false").equals("true")) {
                    System.out.printf("[HTTP-RETRY] 第%d次重试 %s %s (延迟: %dms)%n", 
                        retryCount + 1, request.method(), request.url(), delayMs);
                }
                
                retryCount++;
            }
            
            // 如果执行到这里，说明所有重试都失败了
            throw new IOException("达到最大重试次数 " + maxRetries + " 后仍然失败", lastException);
        }
        
        /**
         * 判断HTTP状态码是否可以重试
         * @param code HTTP状态码
         * @return 是否可重试
         */
        private boolean isRetryable(int code) {
            // 5xx服务器错误和部分4xx客户端错误可以重试
            return code >= 500 || code == 429 || code == 408;
        }
        
        /**
         * 计算重试延迟时间（指数退避策略）
         * @param retryCount 当前重试次数
         * @return 延迟毫秒数
         */
        private long calculateRetryDelay(int retryCount) {
            // 指数退避策略：baseDelay * (1.5^retryCount)，最大10秒
            return Math.min((long)(baseDelayMs * Math.pow(1.5, retryCount)), 10000);
        }
    }
    
    /**
     * 日志拦截器（简化版）
     */
    private static class LoggingInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            long startTime = System.currentTimeMillis();
            
            Response response = chain.proceed(request);
            long responseTime = System.currentTimeMillis() - startTime;
            
            // 只在调试模式下记录详细日志
            if (System.getProperty("http.debug", "false").equals("true")) {
                System.out.printf("[HTTP] %s %s - %d (%dms)%n", 
                    request.method(), request.url(), response.code(), responseTime);
            }
            
            return response;
        }
    }
    
    /**
     * 关闭客户端资源
     */
    public void shutdown() {
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}