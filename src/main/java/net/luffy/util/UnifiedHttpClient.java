package net.luffy.util;

import okhttp3.*;
import okhttp3.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.luffy.util.UnifiedSchedulerManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 统一HTTP客户端
 * 基于OkHttp实现，提供同步和异步请求功能
 * 支持连接池管理、重试机制、性能监控
 */
public class UnifiedHttpClient {
    
    private static final Logger logger = LoggerFactory.getLogger(UnifiedHttpClient.class);
    private static volatile UnifiedHttpClient instance;
    private final OkHttpClient client;
    private final Executor asyncExecutor;
    
    /**
     * HTTP响应封装类
     */
    public static class HttpResponse {
        private final int statusCode;
        private final java.util.Map<String, String> headers;
        private final String body;
        
        public HttpResponse(int statusCode, java.util.Map<String, String> headers, String body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
        
        public java.util.Map<String, String> getHeaders() {
            return headers;
        }
        
        public String getBody() {
            return body;
        }
    }
    
    // 性能统计
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    
    // 异步重试调度器 - 使用统一调度器
    private final ScheduledExecutorService retryScheduler = UnifiedSchedulerManager.getInstance().getScheduledExecutor();
    
    private UnifiedHttpClient() {
        this.client = createOptimizedClient();
        // 使用统一线程池管理
        this.asyncExecutor = AdaptiveThreadPoolManager.getInstance().getExecutor();
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
     * 验证并标准化超时配置
     */
    private static class TimeoutConfig {
        private final int connectTimeout;
        private final int readTimeout;
        private final int writeTimeout;
        
        public TimeoutConfig(int connectTimeout, int readTimeout, int writeTimeout) {
            // 验证超时配置的合理性
            this.connectTimeout = Math.max(1000, Math.min(connectTimeout, 60000)); // 1秒到60秒
            this.readTimeout = Math.max(5000, Math.min(readTimeout, 300000)); // 5秒到5分钟
            this.writeTimeout = Math.max(5000, Math.min(writeTimeout, 300000)); // 5秒到5分钟
        }
        
        public int getConnectTimeout() { return connectTimeout; }
        public int getReadTimeout() { return readTimeout; }
        public int getWriteTimeout() { return writeTimeout; }
    }
    
    /**
     * 获取默认超时配置
     */
    private TimeoutConfig getDefaultTimeoutConfig() {
        MonitorConfig config = MonitorConfig.getInstance();
        return new TimeoutConfig(
            config.getConnectTimeout(),
            config.getReadTimeout(),
            config.getReadTimeout()
        );
    }
    
    /**
     * 创建带超时验证的临时客户端
     */
    private OkHttpClient createClientWithTimeout(int connectTimeoutMs, int readTimeoutMs, int writeTimeoutMs) {
        TimeoutConfig timeoutConfig = new TimeoutConfig(connectTimeoutMs, readTimeoutMs, writeTimeoutMs);
        return client.newBuilder()
                .connectTimeout(timeoutConfig.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutConfig.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutConfig.getWriteTimeout(), TimeUnit.MILLISECONDS)
                .build();
    }
    
    /**
     * 创建优化的调度器
     */
    private Dispatcher createOptimizedDispatcher() {
        Dispatcher dispatcher = new Dispatcher();
        // 设置最大并发请求数
        dispatcher.setMaxRequests(128);  // 总的最大并发请求数
        dispatcher.setMaxRequestsPerHost(32);  // 每个主机的最大并发请求数
        return dispatcher;
    }
    
    /**
     * 创建优化的OkHttp客户端
     */
    private OkHttpClient createOptimizedClient() {
        MonitorConfig config = MonitorConfig.getInstance();
        return new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout(), TimeUnit.MILLISECONDS)  // 使用配置的连接超时时间
                .readTimeout(config.getReadTimeout(), TimeUnit.MILLISECONDS)     // 使用配置的读取超时时间
                .writeTimeout(config.getReadTimeout(), TimeUnit.MILLISECONDS)    // 写入超时使用读取超时时间
                .connectionPool(new ConnectionPool(200, 5, TimeUnit.MINUTES))  // 优化连接池：200个连接，5分钟空闲时间
                .dispatcher(createOptimizedDispatcher())  // 使用优化的调度器
                .retryOnConnectionFailure(true)      // 启用连接失败重试机制
                .addInterceptor(new RetryInterceptor(config.getMaxRetries())) // 使用配置的重试次数
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
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
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
     * HEAD请求（支持自定义请求头）
     */
    public HttpResponse head(String url, java.util.Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).head();
        
        // 添加默认请求头
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
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
            // 转换响应头
            java.util.Map<String, String> responseHeaders = new java.util.HashMap<>();
            for (String name : response.headers().names()) {
                responseHeaders.put(name, response.header(name));
            }
            
            return new HttpResponse(response.code(), responseHeaders, "");
        }
    }
    
    /**
     * GET请求返回HttpResponse（支持自定义请求头）
     */
    public HttpResponse get(String url, java.util.Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        
        // 添加默认请求头
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
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
            // 转换响应头
            java.util.Map<String, String> responseHeaders = new java.util.HashMap<>();
            for (String name : response.headers().names()) {
                responseHeaders.put(name, response.header(name));
            }
            
            // 获取响应体
            String body = "";
            ResponseBody responseBody = response.body();
            if (responseBody != null) {
                body = responseBody.string();
            }
            
            return new HttpResponse(response.code(), responseHeaders, body);
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
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
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
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
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
     * 带动态超时配置的POST请求 - 支持快速失败机制
     */
    public String postWithTimeout(String url, String body, java.util.Map<String, String> headers, 
                                int connectTimeout, int readTimeout) throws IOException {
        // 使用统一的超时配置验证
        OkHttpClient tempClient = createClientWithTimeout(connectTimeout, readTimeout, readTimeout);
        
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
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
               .addHeader("Accept", "application/json, text/plain, */*");
        
        // 添加自定义请求头
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        Request request = builder.build();
        
        try (Response response = tempClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP请求失败: " + response.code() + " " + response.message());
            }
            ResponseBody responseBody = response.body();
            return responseBody != null ? responseBody.string() : "";
        }
    }
    
    /**
     * 异步GET请求
     * @param url 请求URL
     * @return CompletableFuture<String> 异步响应结果
     */
    public CompletableFuture<String> getAsync(String url) {
        return getAsyncWithRetry(url, 0);
    }
    
    /**
     * 带重试的异步GET请求
     * @param url 请求URL
     * @param retryCount 当前重试次数
     * @return CompletableFuture<String> 异步响应结果
     */
    private CompletableFuture<String> getAsyncWithRetry(String url, int retryCount) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json, text/plain, */*")
                .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                handleAsyncRetry(future, () -> getAsyncWithRetry(url, retryCount + 1), 
                               retryCount, 0, e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        handleAsyncRetry(future, () -> getAsyncWithRetry(url, retryCount + 1), 
                                       retryCount, response.code(), 
                                       new IOException("HTTP请求失败: " + response.code()));
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
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
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
     * 处理异步重试逻辑（增强版）
     * @param future 要完成的CompletableFuture
     * @param retrySupplier 重试操作的供应商
     * @param retryCount 当前重试次数
     * @param responseCode HTTP响应码（0表示网络异常）
     * @param lastException 最后的异常
     */
    private <T> void handleAsyncRetry(CompletableFuture<T> future, 
                                     Supplier<CompletableFuture<T>> retrySupplier,
                                     int retryCount, int responseCode, Exception lastException) {
        final int maxRetries = 3; // 最大重试次数
        final long totalTimeoutMs = 60000; // 总超时时间60秒（与Pocket48Sender保持一致）
        
        // 检查是否应该重试
        if (retryCount >= maxRetries || 
            (responseCode > 0 && !RetryInterceptor.isRetryable(responseCode))) {
            // 包装异常信息，提供更详细的错误上下文
            String errorMessage = String.format("HTTP请求失败，已重试%d次: %s", retryCount, lastException.getMessage());
            Exception wrappedException = new IOException(errorMessage, lastException);
            future.completeExceptionally(wrappedException);
            return;
        }
        
        // 计算延迟时间
        long delayMs = RetryInterceptor.calculateRetryDelay(retryCount, responseCode);
        
        // 计算累积延迟时间，确保不超过总超时时间
        long cumulativeDelay = 0;
        for (int i = 0; i < retryCount; i++) {
            cumulativeDelay += RetryInterceptor.calculateRetryDelay(i, responseCode);
        }
        
        // 如果累积延迟加上当前延迟会超过总超时时间，则调整当前延迟
        if (cumulativeDelay + delayMs > totalTimeoutMs - 5000) { // 预留5秒给实际请求执行
            long remainingTime = totalTimeoutMs - cumulativeDelay - 5000;
            if (remainingTime <= 0) {
                // 没有足够时间进行重试，直接失败
                String errorMessage = String.format("HTTP请求重试超时，累积延迟已达%dms，超过总超时%dms", 
                    cumulativeDelay, totalTimeoutMs);
                Exception timeoutException = new IOException(errorMessage, lastException);
                future.completeExceptionally(timeoutException);
                return;
            }
            delayMs = Math.min(delayMs, remainingTime);
            logger.warn("[HTTP-RETRY-TIMEOUT] 调整重试延迟从{}ms到{}ms，避免超过总超时时间", 
                RetryInterceptor.calculateRetryDelay(retryCount, responseCode), delayMs);
        }
        
        // 记录重试日志
        if (System.getProperty("http.debug", "false").equals("true")) {
            logger.debug("[HTTP-ASYNC-RETRY] 第{}次重试 (延迟: {}ms, 响应码: {}, 错误: {})", 
                retryCount + 1, delayMs, responseCode, lastException.getMessage());
        }
        
        // 异步延迟后重试
        retryScheduler.schedule(() -> {
            try {
                CompletableFuture<T> retryFuture = retrySupplier.get();
                retryFuture.whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        future.completeExceptionally(throwable);
                    } else {
                        if (retryCount > 0) {
                            logger.info("[HTTP-ASYNC-RETRY] 异步请求重试成功，总重试次数: {}", retryCount + 1);
                        }
                        future.complete(result);
                    }
                });
            } catch (Exception e) {
                logger.warn("[HTTP-ASYNC-RETRY] 异步重试过程中发生异常: {}", e.getMessage());
                future.completeExceptionally(e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 统一的异步错误处理
     */
    private void handleAsyncError(CompletableFuture<?> future, Throwable error, String operation) {
        String errorMessage = String.format("%s失败: %s", operation, error.getMessage());
        logger.error(errorMessage, error);
        
        // 根据错误类型提供更具体的异常和错误信息
        Exception wrappedException;
        if (error instanceof java.net.SocketTimeoutException) {
            wrappedException = new IOException("网络请求超时: " + errorMessage, error);
        } else if (error instanceof java.net.ConnectException) {
            wrappedException = new IOException("网络连接失败: " + errorMessage, error);
        } else if (error instanceof java.net.UnknownHostException) {
            wrappedException = new IOException("域名解析失败: " + errorMessage, error);
        } else if (error instanceof IOException && error.getMessage() != null) {
            String msg = error.getMessage();
            if (msg.contains("HTTP请求失败: 503")) {
                wrappedException = new IOException("服务器暂时不可用(503): " + errorMessage, error);
            } else if (msg.contains("HTTP请求失败: 500")) {
                wrappedException = new IOException("服务器内部错误(500): " + errorMessage, error);
            } else if (msg.contains("HTTP请求失败: 429")) {
                wrappedException = new IOException("请求频率过高(429): " + errorMessage, error);
            } else if (msg.contains("HTTP请求失败: 408")) {
                wrappedException = new IOException("请求超时(408): " + errorMessage, error);
            } else if (msg.contains("HTTP请求失败:")) {
                wrappedException = new IOException("HTTP错误: " + errorMessage, error);
            } else {
                wrappedException = (IOException) error;
            }
        } else if (error instanceof IOException) {
            wrappedException = (IOException) error;
        } else {
            wrappedException = new IOException("未知错误: " + errorMessage, error);
        }
        
        future.completeExceptionally(wrappedException);
    }
    
    /**
     * 为异步请求添加超时监控
     */
    private <T> CompletableFuture<T> addTimeoutMonitoring(CompletableFuture<T> future, long timeoutMs, String operation) {
        if (timeoutMs <= 0) {
            return future;
        }
        
        CompletableFuture<T> timeoutFuture = new CompletableFuture<>();
        
        // 设置超时监控
        retryScheduler.schedule(() -> {
            if (!future.isDone()) {
                String timeoutMessage = String.format("%s超时 (%dms)", operation, timeoutMs);
                logger.warn(timeoutMessage);
                timeoutFuture.completeExceptionally(new java.net.SocketTimeoutException(timeoutMessage));
                future.cancel(true);
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        
        // 转发原始future的结果
        future.whenComplete((result, throwable) -> {
            if (!timeoutFuture.isDone()) {
                if (throwable != null) {
                    timeoutFuture.completeExceptionally(throwable);
                } else {
                    timeoutFuture.complete(result);
                }
            }
        });
        
        return timeoutFuture;
    }
    
    /**
     * 异步POST请求
     */
    public CompletableFuture<String> postAsync(String url, String jsonBody) {
        return postAsyncWithRetry(url, jsonBody, 0);
    }
    
    /**
     * 带重试的异步POST请求
     */
    private CompletableFuture<String> postAsyncWithRetry(String url, String jsonBody, int retryCount) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .addHeader("Content-Type", "application/json")
                .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                handleAsyncRetry(future, () -> postAsyncWithRetry(url, jsonBody, retryCount + 1), 
                               retryCount, 0, e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        handleAsyncRetry(future, () -> postAsyncWithRetry(url, jsonBody, retryCount + 1), 
                                       retryCount, response.code(), 
                                       new IOException("HTTP请求失败: " + response.code()));
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
        return postAsyncWithRetryAndHeaders(url, body, headers, 0);
    }
    
    /**
     * 带重试的异步POST请求（支持自定义请求头）
     */
    private CompletableFuture<String> postAsyncWithRetryAndHeaders(String url, String body, java.util.Map<String, String> headers, int retryCount) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
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
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
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
                handleAsyncRetry(future, () -> postAsyncWithRetryAndHeaders(url, body, headers, retryCount + 1), 
                               retryCount, 0, e);
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        handleAsyncRetry(future, () -> postAsyncWithRetryAndHeaders(url, body, headers, retryCount + 1), 
                                       retryCount, response.code(), 
                                       new IOException("HTTP请求失败: " + response.code()));
                        return;
                    }
                    ResponseBody responseBody = response.body();
                    String result = responseBody != null ? responseBody.string() : "";
                    if (retryCount > 0) {
                        logger.info("[HTTP-ASYNC-RETRY] 异步POST请求重试成功，总重试次数: {}", retryCount);
                    }
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
     * 带动态超时配置的异步POST请求 - 支持快速失败机制
     */
    public CompletableFuture<String> postWithTimeoutAsync(String url, String body, java.util.Map<String, String> headers, 
                                                         int connectTimeout, int readTimeout) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        // 创建临时客户端，使用自定义超时配置
        OkHttpClient tempClient = client.newBuilder()
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .build();
        
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
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
               .addHeader("Accept", "application/json, text/plain, */*");
        
        // 添加自定义请求头
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        Request request = builder.build();
        
        tempClient.newCall(request).enqueue(new Callback() {
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
     * 注意：此方法为同步方法，可能阻塞线程，建议使用getInputStreamAsync
     */
    public InputStream getInputStream(String url) throws IOException {
        return getInputStreamWithTimeout(url, null, 30000, 60000); // 默认30秒连接超时，60秒读取超时
    }
    
    /**
     * 获取输入流（带超时控制和重试机制）
     */
    public InputStream getInputStreamWithTimeout(String url, java.util.Map<String, String> headers, 
                                               int connectTimeoutMs, int readTimeoutMs) throws IOException {
        final int maxRetries = 3; // 最大重试次数
        IOException lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // 使用统一的超时配置验证
                OkHttpClient tempClient = createClientWithTimeout(connectTimeoutMs, readTimeoutMs, readTimeoutMs);
                
                Request.Builder builder = new Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
                
                // 添加自定义请求头
                if (headers != null) {
                    for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                        builder.addHeader(header.getKey(), header.getValue());
                    }
                }
                
                Request request = builder.build();
                Response response = tempClient.newCall(request).execute();
                
                if (!response.isSuccessful()) {
                    int responseCode = response.code();
                    String errorMessage = "HTTP请求失败: " + responseCode + " " + response.message();
                    response.close();
                    
                    // 检查是否为可重试的错误
                    if (attempt < maxRetries && RetryInterceptor.isRetryable(responseCode)) {
                        lastException = new IOException(errorMessage + " (尝试 " + (attempt + 1) + "/" + (maxRetries + 1) + ")");
                        
                        // 计算重试延迟
                        long retryDelay = RetryInterceptor.calculateRetryDelay(attempt, responseCode);
                        
                        logger.warn("[HTTP-SYNC-RETRY] 图片下载失败，准备重试 {}/{}, 状态码: {}, 延迟: {}ms, URL: {}", 
                            attempt + 1, maxRetries + 1, responseCode, retryDelay, url);
                        
                        // 同步延迟
                        try {
                            Thread.sleep(retryDelay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("重试被中断: " + ie.getMessage(), ie);
                        }
                        
                        continue; // 继续重试
                    } else {
                        // 不可重试的错误或已达到最大重试次数
                        throw new IOException(errorMessage);
                    }
                }
                
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    response.close();
                    throw new IOException("响应体为空");
                }
                
                // 成功获取输入流
                if (attempt > 0) {
                    logger.info("[HTTP-SYNC-RETRY] 图片下载重试成功，总重试次数: {}, URL: {}", attempt, url);
                }
                
                return responseBody.byteStream();
                
            } catch (IOException e) {
                lastException = e;
                
                // 对于网络异常，也进行重试
                if (attempt < maxRetries) {
                    // 计算重试延迟（网络异常使用响应码0）
                    long retryDelay = RetryInterceptor.calculateRetryDelay(attempt, 0);
                    
                    logger.warn("[HTTP-SYNC-RETRY] 图片下载网络异常，准备重试 {}/{}, 异常: {}, 延迟: {}ms, URL: {}", 
                        attempt + 1, maxRetries + 1, e.getMessage(), retryDelay, url);
                    
                    // 同步延迟
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("重试被中断: " + ie.getMessage(), ie);
                    }
                    
                    continue; // 继续重试
                }
            }
        }
        
        // 所有重试都失败了
        throw new IOException("图片下载失败，已重试 " + maxRetries + " 次: " + 
            (lastException != null ? lastException.getMessage() : "未知错误"), lastException);
    }
    
    /**
     * 异步获取输入流（推荐使用）
     */
    public CompletableFuture<InputStream> getInputStreamAsync(String url) {
        return getInputStreamAsync(url, null, 30000, 60000);
    }
    
    /**
     * 异步获取输入流（带超时控制）
     */
    public CompletableFuture<InputStream> getInputStreamAsync(String url, java.util.Map<String, String> headers,
                                                            int connectTimeoutMs, int readTimeoutMs) {
        CompletableFuture<InputStream> future = new CompletableFuture<>();
        
        // 使用统一的超时配置验证
        OkHttpClient tempClient = createClientWithTimeout(connectTimeoutMs, readTimeoutMs, readTimeoutMs);
        
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        
        // 添加自定义请求头
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                builder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        Request request = builder.build();
        
        tempClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                handleAsyncError(future, e, "异步InputStream请求");
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    response.close();
                    IOException error = new IOException("HTTP请求失败: " + response.code() + " " + response.message());
                    handleAsyncError(future, error, "异步InputStream请求");
                    return;
                }
                
                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    response.close();
                    IOException error = new IOException("响应体为空");
                    handleAsyncError(future, error, "异步InputStream响应处理");
                    return;
                }
                
                future.complete(responseBody.byteStream());
            }
        });
        
        return future;
    }
    
    /**
     * 获取输入流（带自定义请求头）
     * 注意：此方法为同步方法，可能阻塞线程，建议使用getInputStreamAsync
     */
    public InputStream getInputStreamWithHeaders(String url, java.util.Map<String, String> headers) throws IOException {
        return getInputStreamWithTimeout(url, headers, 30000, 60000); // 使用统一的超时控制方法
    }
    
    /**
     * 异步获取输入流（带自定义请求头）
     */
    public CompletableFuture<InputStream> getInputStreamWithHeadersAsync(String url, java.util.Map<String, String> headers) {
        return getInputStreamAsync(url, headers, 30000, 60000); // 使用统一的异步方法
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
                logger.debug("[HTTP-STATS] 请求开始 - 总请求数: {}, URL: {}", 
                    currentTotal, chain.request().url());
            }
            
            try {
                Response response = chain.proceed(chain.request());
                long responseTime = System.currentTimeMillis() - startTime;
                totalResponseTime.addAndGet(responseTime);
                
                if (response.isSuccessful()) {
                    long currentSuccessful = successfulRequests.incrementAndGet();
                    if (System.getProperty("http.debug", "false").equals("true")) {
                        logger.debug("[HTTP-STATS] 请求成功 - 成功数: {}, 响应时间: {}ms", 
                            currentSuccessful, responseTime);
                    }
                } else {
                    long currentFailed = failedRequests.incrementAndGet();
                    if (System.getProperty("http.debug", "false").equals("true")) {
                        logger.debug("[HTTP-STATS] 请求失败 - 失败数: {}, 状态码: {}", 
                            currentFailed, response.code());
                    }
                }
                
                // 记录到全局性能监控器 - 暂时注释掉避免Mirai依赖问题
                // try {
                //     net.luffy.util.PerformanceMonitor.getInstance().recordQuery(responseTime);
                // } catch (Exception e) {
                //     // 忽略性能监控记录失败，避免因为Mirai依赖问题导致HTTP请求失败
                // }
                
                return response;
            } catch (IOException e) {
                long responseTime = System.currentTimeMillis() - startTime;
                totalResponseTime.addAndGet(responseTime);
                long currentFailed = failedRequests.incrementAndGet();
                
                if (System.getProperty("http.debug", "false").equals("true")) {
                    logger.debug("[HTTP-STATS] 请求异常 - 失败数: {}, 异常: {}", 
                        currentFailed, e.getMessage());
                }
                
                // 记录到全局性能监控器 - 暂时注释掉避免Mirai依赖问题
                // try {
                //     net.luffy.util.PerformanceMonitor.getInstance().recordQuery(responseTime);
                // } catch (Exception ex) {
                //     // 忽略性能监控记录失败，避免因为Mirai依赖问题导致HTTP请求失败
                // }
                
                throw e;
            }
        }
    }
    
    /**
     * 重试拦截器
     * 实现全局HTTP请求重试机制，支持指数退避策略
     * 注意：此拦截器仅处理第一次请求，重试逻辑移至异步方法中实现
     */
    private static class RetryInterceptor implements Interceptor {
        private final int maxRetries;
        
        public RetryInterceptor(int maxRetries) {
            this.maxRetries = maxRetries;
        }
        
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            
            try {
                // 执行请求，不在拦截器中进行重试以避免阻塞
                Response response = chain.proceed(request);
                
                // 记录重试相关信息到请求头中，供异步重试使用
                if (System.getProperty("http.debug", "false").equals("true")) {
                    logger.debug("[HTTP-REQUEST] {} {} - {}", 
                        request.method(), request.url(), response.code());
                }
                
                return response;
            } catch (IOException e) {
                if (System.getProperty("http.debug", "false").equals("true")) {
                    logger.debug("[HTTP-REQUEST] {} {} - 异常: {}", 
                        request.method(), request.url(), e.getMessage());
                }
                throw e;
            }
        }
        
        /**
         * 判断HTTP状态码是否可以重试
         * @param code HTTP状态码
         * @return 是否可重试
         */
        public static boolean isRetryable(int code) {
            // 认证相关错误不应该重试，因为重试不会解决Cookie失效问题
            if (code == 401 || code == 403) {
                return false;
            }
            
            // 重定向错误通常表示需要重新登录，不应该重试
            if (code == 301 || code == 302) {
                return false;
            }
            
            // 5xx服务器错误和部分4xx客户端错误可以重试
            // 特别处理HTTP 432错误（微博API的非标准错误码，通常表示请求签名认证失败）
            return code >= 500 || code == 429 || code == 408 || code == 432;
        }
        
        /**
         * 计算重试延迟时间（指数退避策略）
         * @param retryCount 当前重试次数
         * @param responseCode HTTP响应码
         * @return 延迟毫秒数
         */
        public static long calculateRetryDelay(int retryCount, int responseCode) {
            // 使用默认配置（延迟配置已移除）
            long baseDelayMs = 1000L; // 默认基础延迟1秒
            
            // 对于HTTP 503错误（服务不可用），使用较短的重试间隔
            if (responseCode == 503) {
                // 对503错误使用较短的基础延迟（1秒）和较小的指数因子（1.5）
                long delay = Math.min((long)(1000 * Math.pow(1.5, retryCount)), 5000); // 最大5秒
                return delay;
            }
            
            // 对于HTTP 432错误（微博API认证失败），使用更长的延迟
            if (responseCode == 432) {
                // 对432错误使用更长的基础延迟（2秒）和更大的指数因子（1.8）
                long delay = Math.min((long)(2000 * Math.pow(1.8, retryCount)), 5000); // 最大5秒
                return delay;
            }
            
            // 其他5xx服务器错误使用中等延迟
            if (responseCode >= 500 && responseCode < 600) {
                // 对其他5xx错误使用中等延迟（1.5秒基础）
                long delay = Math.min((long)(1500 * Math.pow(1.6, retryCount)), 5000); // 最大5秒
                return delay;
            }
            
            // 其他错误使用标准指数退避策略：baseDelay * (backoffMultiplier^retryCount)，最大延迟由配置决定
            double backoffMultiplier = 2.0; // 默认退避倍数
            long maxDelay = Math.min(30000L, 5000); // 强制限制最大延迟为5秒
            return Math.min((long)(baseDelayMs * Math.pow(backoffMultiplier, retryCount)), maxDelay);
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
                logger.debug("[HTTP] {} {} - {} ({}ms)", 
                    request.method(), request.url(), response.code(), responseTime);
            }
            
            return response;
        }
    }
    
    /**
     * 关闭客户端资源 - 统一线程池管理版
     */
    public void shutdown() {
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
        // retryScheduler和asyncExecutor现在由统一管理器处理，不需要单独关闭
        System.out.println("[UnifiedHttpClient] 已关闭，线程池由统一管理器处理");
    }
}