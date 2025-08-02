package net.luffy.handler;

import net.luffy.util.MonitorConfig;
import net.luffy.util.UnifiedHttpClient;
import net.luffy.util.UnifiedJsonParser;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 异步Web处理器
 * 使用统一HTTP客户端和JSON解析器实现高性能异步HTTP请求
 * 支持批量查询、连接池管理、请求统计等功能
 */
public class AsyncWebHandler {
    
    private static volatile AsyncWebHandler instance;
    
    private final UnifiedHttpClient unifiedClient;
    private final UnifiedJsonParser jsonParser;
    // OkHttpClient已迁移到UnifiedHttpClient，不再需要直接实例
    private final MonitorConfig config;
    
    // 性能统计
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger successfulRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private volatile long lastRequestTime = 0;
    
    private AsyncWebHandler() {
        this.config = MonitorConfig.getInstance();
        this.unifiedClient = UnifiedHttpClient.getInstance();
        this.jsonParser = UnifiedJsonParser.getInstance();
        // HTTP客户端已统一到UnifiedHttpClient
    }
    
    /**
     * 获取单例实例
     */
    public static AsyncWebHandler getInstance() {
        if (instance == null) {
            synchronized (AsyncWebHandler.class) {
                if (instance == null) {
                    instance = new AsyncWebHandler();
                }
            }
        }
        return instance;
    }
    
    // HTTP客户端创建已迁移到UnifiedHttpClient
    
    /**
     * 异步GET请求 - 使用统一HTTP客户端
     */
    public CompletableFuture<String> getAsync(String url) {
        // 统计由UnifiedHttpClient处理，避免重复计数
        lastRequestTime = System.currentTimeMillis();
        
        return unifiedClient.getAsync(url)
                .whenComplete((result, throwable) -> {
                    // 记录到全局性能监控器
                    try {
                        long responseTime = System.currentTimeMillis() - lastRequestTime;
                        net.luffy.util.PerformanceMonitor.getInstance().recordQuery(responseTime);
                    } catch (Exception e) {
                        // 忽略性能监控记录失败
                    }
                });
    }
    
    /**
     * 异步POST请求 - JSON格式，使用统一HTTP客户端
     */
    public CompletableFuture<String> postAsync(String url, String jsonBody) {
        // 统计由UnifiedHttpClient处理，避免重复计数
        lastRequestTime = System.currentTimeMillis();
        
        return unifiedClient.postAsync(url, jsonBody);
    }
    
    /**
     * 异步POST请求 - Form格式（用于Xox48 API）- 使用统一HTTP客户端
     */
    public CompletableFuture<String> postFormAsync(String url, String formBody) {
        // 统计由UnifiedHttpClient处理，避免重复计数
        lastRequestTime = System.currentTimeMillis();
        
        return unifiedClient.postAsync(url, formBody, getXox48Headers())
                .whenComplete((result, throwable) -> {
                    // 记录到全局性能监控器
                    try {
                        long responseTime = System.currentTimeMillis() - lastRequestTime;
                        net.luffy.util.PerformanceMonitor.getInstance().recordQuery(responseTime);
                    } catch (Exception e) {
                        // 忽略性能监控记录失败
                    }
                });
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
    
    // HTTP请求执行已迁移到UnifiedHttpClient
    
    /**
     * 获取Xox48 API专用请求头
     */
    private java.util.Map<String, String> getXox48Headers() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        headers.put("X-Requested-With", "XMLHttpRequest");
        return headers;
    }
    
    /**
     * 解析成员状态响应 - 使用统一JSON解析器
     */
    private BatchMemberStatusResult parseMemberStatusResponse(String memberName, String response) {
        try {
            // 使用统一JSON解析器解析响应
            cn.hutool.json.JSONObject jsonResponse = jsonParser.parseObj(response);
            
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
     * 获取性能统计信息 - 整合统一客户端统计
     */
    public String getPerformanceStats() {
        long total = totalRequests.get();
        long successful = successfulRequests.get();
        long failed = failedRequests.get();
        long totalTime = totalResponseTime.get();
        long avgResponseTime = total > 0 ? totalTime / total : 0;
        double successRate = total > 0 ? (double) successful / total * 100 : 0;
        
        // 格式化时间为统一格式 yyyy-MM-dd HH:mm:ss
        String formattedTime = "无";
        if (lastRequestTime > 0) {
            java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(lastRequestTime), 
                java.time.ZoneId.systemDefault()
            );
            formattedTime = dateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        
        return String.format(
                "异步HTTP统计: 总请求 %d, 成功 %d, 失败 %d, 成功率 %.1f%%, 平均响应时间 %dms\n" +
                "异步HTTP性能统计: 最后请求时间 %s\n" +
                "最后请求时间: %s\n" +
                "\n统一客户端统计:\n%s\n" +
                "\n统一JSON解析器统计:\n%s",
                total, successful, failed, successRate, avgResponseTime,
                formattedTime,
                formattedTime,
                unifiedClient.getPerformanceStats(),
                jsonParser.getPerformanceStats()
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
        
        // 重置统一客户端和JSON解析器的统计数据
        unifiedClient.resetStats();
        jsonParser.resetStats();
    }
    
    /**
     * 关闭客户端
     */
    public void shutdown() {
        // 统一客户端由单例管理，不需要手动关闭
        // 重置本地统计数据
        resetStats();
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