package net.luffy.handler;

import net.luffy.util.MonitorConfig;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 异步Web处理器
 * 使用OkHttp和CompletableFuture实现高性能异步HTTP请求
 * 支持批量查询、连接池管理、请求统计等功能
 */
public class AsyncWebHandler {
    
    private final OkHttpClient client;
    private final MonitorConfig config;
    
    // 性能统计
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger successfulRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private volatile long lastRequestTime = 0;
    
    public AsyncWebHandler() {
        this.config = MonitorConfig.getInstance();
        this.client = createHttpClient();
    }
    
    /**
     * 创建配置化的OkHttp客户端
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
    
    /**
     * 异步GET请求
     */
    public CompletableFuture<String> getAsync(String url) {
        return executeAsync(url, "GET", null);
    }
    
    /**
     * 异步POST请求 - JSON格式
     */
    public CompletableFuture<String> postAsync(String url, String jsonBody) {
        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
        return executeAsync(url, "POST", body);
    }
    
    /**
     * 异步POST请求 - Form格式（用于Xox48 API）
     */
    public CompletableFuture<String> postFormAsync(String url, String formBody) {
        RequestBody body = RequestBody.create(formBody, MediaType.get("application/x-www-form-urlencoded; charset=utf-8"));
        return executeAsyncWithHeaders(url, "POST", body, getXox48Headers());
    }
    
    /**
     * 批量异步GET请求
     * @param urls URL列表
     * @return 包含所有响应的CompletableFuture
     */
    public CompletableFuture<List<AsyncResponse>> batchGetAsync(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        List<CompletableFuture<AsyncResponse>> futures = urls.stream()
                .map(url -> getAsync(url)
                        .thenApply(response -> new AsyncResponse(url, response, null))
                        .exceptionally(throwable -> new AsyncResponse(url, null, throwable)))
                .collect(Collectors.toList());
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }
    
    /**
     * 批量查询成员在线状态
     * @param memberNames 成员名称列表
     * @return 批量查询结果
     */
    public CompletableFuture<List<BatchMemberStatusResult>> batchQueryMemberStatus(List<String> memberNames) {
        if (memberNames == null || memberNames.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        // 使用POST请求进行批量查询，而不是GET
        List<CompletableFuture<BatchMemberStatusResult>> futures = memberNames.stream()
                .map(memberName -> {
                    String requestBody = "name=" + encodeUrl(memberName);
                    return postFormAsync("https://xox48.top/Api/member_online", requestBody)
                            .thenApply(response -> parseMemberStatusResponse(memberName, response))
                            .exceptionally(throwable -> new BatchMemberStatusResult(memberName, false, 
                                "请求失败: " + throwable.getMessage(), null));
                })
                .collect(Collectors.toList());
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList()));
    }
    
    /**
     * 执行异步HTTP请求
     */
    private CompletableFuture<String> executeAsync(String url, String method, RequestBody body) {
        return executeAsyncWithHeaders(url, method, body, null);
    }
    
    /**
     * 执行带自定义请求头的异步HTTP请求
     */
    private CompletableFuture<String> executeAsyncWithHeaders(String url, String method, RequestBody body, java.util.Map<String, String> headers) {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        Request.Builder requestBuilder = new Request.Builder().url(url);
        
        // 添加自定义请求头
        if (headers != null) {
            for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                requestBuilder.addHeader(header.getKey(), header.getValue());
            }
        }
        
        // 设置请求方法
        switch (method.toUpperCase()) {
            case "GET":
                requestBuilder.get();
                break;
            case "POST":
                requestBuilder.post(body != null ? body : RequestBody.create("", null));
                break;
            default:
                future.completeExceptionally(new IllegalArgumentException("不支持的HTTP方法: " + method));
                return future;
        }
        
        Request request = requestBuilder.build();
        long startTime = System.currentTimeMillis();
        
        totalRequests.incrementAndGet();
        lastRequestTime = startTime;
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                failedRequests.incrementAndGet();
                totalResponseTime.addAndGet(System.currentTimeMillis() - startTime);
                future.completeExceptionally(e);
            }
            
            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                long responseTime = System.currentTimeMillis() - startTime;
                totalResponseTime.addAndGet(responseTime);
                
                try {
                    if (response.isSuccessful()) {
                        successfulRequests.incrementAndGet();
                        String responseBody = response.body() != null ? response.body().string() : "";
                        future.complete(responseBody);
                    } else {
                        failedRequests.incrementAndGet();
                        future.completeExceptionally(new IOException(
                                String.format("HTTP请求失败 [%d]: %s", response.code(), url)));
                    }
                } finally {
                    response.close();
                }
            }
        });
        
        return future;
    }
    
    /**
     * 获取Xox48 API专用请求头
     */
    private java.util.Map<String, String> getXox48Headers() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        headers.put("X-Requested-With", "XMLHttpRequest");
        return headers;
    }
    
    /**
     * 解析成员状态响应
     */
    private BatchMemberStatusResult parseMemberStatusResponse(String memberName, String response) {
        try {
            // 使用Hutool的JSONUtil解析响应
            cn.hutool.json.JSONObject jsonResponse = cn.hutool.json.JSONUtil.parseObj(response);
            
            // 检查API响应状态
            String msg = jsonResponse.getStr("msg");
            if (msg == null || !"success".equals(msg)) {
                return new BatchMemberStatusResult(memberName, false, 
                    msg != null ? msg : "API响应失败", response);
            }
            
            String error = jsonResponse.getStr("error");
            if (error == null || !"0".equals(error)) {
                return new BatchMemberStatusResult(memberName, false, 
                    "错误码异常: " + error, response);
            }
            
            // 解析嵌套的data结构
            cn.hutool.json.JSONObject outerData = jsonResponse.getJSONObject("data");
            if (outerData == null) {
                return new BatchMemberStatusResult(memberName, false, 
                    "响应数据格式异常", response);
            }
            
            cn.hutool.json.JSONObject data = outerData.getJSONObject("data");
            if (data == null) {
                return new BatchMemberStatusResult(memberName, false, 
                    "响应数据格式异常", response);
            }
            
            // 获取在线状态
            Integer isOnlineObj = data.getInt("is_online");
            if (isOnlineObj == null) {
                return new BatchMemberStatusResult(memberName, false, 
                    "无法获取在线状态", response);
            }
            
            int isOnline = isOnlineObj;
            String status;
            if (isOnline == 1) {
                status = "在线";
            } else if (isOnline == 2) {
                status = "离线";
            } else {
                status = "未知";
            }
            
            return new BatchMemberStatusResult(memberName, true, status, response);
            
        } catch (Exception e) {
            return new BatchMemberStatusResult(memberName, false, 
                "解析失败: " + e.getMessage(), response);
        }
    }
    
    /**
     * 从URL中提取成员名称
     */
    private String extractMemberNameFromUrl(String url) {
        try {
            int nameIndex = url.indexOf("name=");
            if (nameIndex != -1) {
                String nameParam = url.substring(nameIndex + 5);
                int endIndex = nameParam.indexOf("&");
                return endIndex != -1 ? nameParam.substring(0, endIndex) : nameParam;
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return "未知成员";
    }
    
    /**
     * URL编码
     */
    private String encodeUrl(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
    
    /**
     * 获取性能统计信息
     */
    public String getPerformanceStats() {
        int total = totalRequests.get();
        int success = successfulRequests.get();
        int failed = failedRequests.get();
        long avgResponseTime = total > 0 ? totalResponseTime.get() / total : 0;
        double successRate = total > 0 ? (double) success / total * 100 : 0;
        
        return String.format(
                "异步HTTP性能统计:\n" +
                "总请求数: %d\n" +
                "成功请求: %d\n" +
                "失败请求: %d\n" +
                "成功率: %.2f%%\n" +
                "平均响应时间: %dms\n" +
                "最后请求时间: %s",
                total, success, failed, successRate, avgResponseTime,
                lastRequestTime > 0 ? new java.util.Date(lastRequestTime).toString() : "无"
        );
    }
    
    /**
     * 重置统计数据
     */
    public void resetStats() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        totalResponseTime.set(0);
        lastRequestTime = 0;
    }
    
    /**
     * 关闭客户端
     */
    public void shutdown() {
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
    
    /**
     * 异步响应结果
     */
    public static class AsyncResponse {
        private final String url;
        private final String response;
        private final Throwable error;
        
        public AsyncResponse(String url, String response, Throwable error) {
            this.url = url;
            this.response = response;
            this.error = error;
        }
        
        public String getUrl() { return url; }
        public String getResponse() { return response; }
        public Throwable getError() { return error; }
        public boolean isSuccess() { return error == null && response != null; }
    }
    
    /**
     * 批量成员状态查询结果
     */
    public static class BatchMemberStatusResult {
        private final String memberName;
        private final boolean success;
        private final String status;
        private final String rawResponse;
        
        public BatchMemberStatusResult(String memberName, boolean success, String status, String rawResponse) {
            this.memberName = memberName;
            this.success = success;
            this.status = status;
            this.rawResponse = rawResponse;
        }
        
        public String getMemberName() { return memberName; }
        public boolean isSuccess() { return success; }
        public String getStatus() { return status; }
        public String getRawResponse() { return rawResponse; }
        
        @Override
        public String toString() {
            return String.format("[%s] %s: %s", memberName, success ? "成功" : "失败", status);
        }
    }
}