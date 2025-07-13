package net.luffy.handler;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import net.luffy.util.AsyncOnlineStatusMonitor;
import okhttp3.Request;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Arrays;
import java.util.Random;

/**
 * 增强的Xox48异步处理器
 * 提供成员在线状态查询，支持异步处理、多UA随机选择、缓存、重试、性能监控等功能
 */
public class Xox48Handler extends AsyncWebHandlerBase {

    private static final String API_MEMBER_ONLINE = "https://xox48.top/Api/member_online";
    
    // 配置管理
    private final net.luffy.util.MonitorConfig config = net.luffy.util.MonitorConfig.getInstance();
    
    // 异步监控器
    private final AsyncOnlineStatusMonitor asyncMonitor = AsyncOnlineStatusMonitor.INSTANCE;
    
    // 多个User-Agent配置，每次请求随机选择
    private static final List<String> USER_AGENTS = Arrays.asList(
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 15_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.6 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    );
    
    // 随机数生成器，用于UA选择
    private final Random random = new Random();
    
    // 缓存和失败统计
    private final ConcurrentHashMap<String, CachedResult> resultCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailureStats> failureStats = new ConcurrentHashMap<>();

    public Xox48Handler() {
        super();
    }

    /**
     * 获取默认请求头（使用随机UA）
     */
    private okhttp3.Headers getDefaultHeaders() {
        String randomUserAgent = getRandomUserAgent();
        return new okhttp3.Headers.Builder()
                .add("Accept", "application/json, text/plain, */*")
                .add("Content-Type", "application/x-www-form-urlencoded")
                .add("Sec-Fetch-Site", "same-origin")
                .add("Origin", "https://xox48.top")
                .add("Sec-Fetch-Mode", "cors")
                .add("User-Agent", randomUserAgent)
                .add("Referer", "https://xox48.top/v2024/")
                .add("Sec-Fetch-Dest", "empty")
                .add("Accept-Language", "zh-SG,zh-CN;q=0.9,zh-Hans;q=0.8")
                .add("Priority", "u=3, i")
                .add("Accept-Encoding", "gzip, deflate, br, zstd")
                .add("Connection", "keep-alive")
                .build();
    }
    
    /**
     * 随机选择User-Agent
     */
    private String getRandomUserAgent() {
        return USER_AGENTS.get(random.nextInt(USER_AGENTS.size()));
    }

    /**
     * 异步查询成员在线状态 - 新的异步方法
     * 功能特性：
     * 1. 异步处理：使用CompletableFuture实现非阻塞查询
     * 2. 批量优化：自动合并到批量查询中提升性能
     * 3. 随机UA：每次请求使用不同的User-Agent
     * 4. 缓存机制：30秒内重复查询直接返回缓存结果
     * 5. 失败统计：记录连续失败次数，超过阈值进入冷却期
     * @param name 成员名称
     * @return CompletableFuture包装的在线状态信息对象
     */
    public CompletableFuture<OnlineStatusResult> queryMemberOnlineStatusAsync(String name) {
        if (name == null || name.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                new OnlineStatusResult(false, "成员名称不能为空", name, -1, null, null, null));
        }
        
        String normalizedName = name.trim();
        
        // 使用异步监控器进行查询
        return asyncMonitor.queryMemberStatusAsync(normalizedName)
                .thenApply(batchResult -> {
                    if (batchResult.isSuccess()) {
                        try {
                            // 解析批量查询结果为OnlineStatusResult
                            JSONObject jsonResponse = JSONUtil.parseObj(batchResult.getRawResponse());
                            return parseOnlineStatusResponse(jsonResponse, normalizedName);
                        } catch (Exception e) {
                            return new OnlineStatusResult(false, "解析响应失败: " + e.getMessage(), 
                                normalizedName, -1, null, null, null);
                        }
                    } else {
                        return new OnlineStatusResult(false, batchResult.getStatus(), 
                            normalizedName, -1, null, null, null);
                    }
                });
    }
    
    /**
     * 查询成员在线状态 - 支持成员名称（兼容性方法，内部使用异步实现）
     * 功能特性：
     * 1. 缓存机制：30秒内重复查询直接返回缓存结果
     * 2. 失败统计：记录连续失败次数，超过阈值进入冷却期
     * 3. 详细错误信息：提供具体的失败原因
     * 4. 性能监控：记录查询耗时和成功率
     * 5. 异步优化：内部使用异步处理器提升性能
     * @param name 成员名称
     * @return 在线状态信息对象，包含状态、消息等信息
     */
    public OnlineStatusResult queryMemberOnlineStatus(String name) {
        try {
            // 使用异步方法并等待结果，保持向后兼容
            return queryMemberOnlineStatusAsync(name).get();
        } catch (Exception e) {
            return new OnlineStatusResult(false, "查询异常: " + e.getMessage(), name, -1, null, null, null);
        }
    }
    
    /**
     * 查询成员在线状态 - 原始同步实现（已弃用，保留用于紧急回退）
     * @deprecated 请使用 queryMemberOnlineStatusAsync 或 queryMemberOnlineStatus
     */
    @Deprecated
    public OnlineStatusResult queryMemberOnlineStatusSync(String name) {
        if (name == null || name.trim().isEmpty()) {
            return new OnlineStatusResult(false, "成员名称不能为空", name, -1, null, null, null);
        }
        
        String normalizedName = name.trim();
        long currentTime = System.currentTimeMillis();
        
        // 1. 检查缓存
        CachedResult cached = resultCache.get(normalizedName);
        if (cached != null && (currentTime - cached.timestamp) < config.getCacheExpireTime()) {
            logInfo(String.format("使用缓存结果查询成员 %s 状态", normalizedName));
            return cached.result;
        }
        
        // 2. 检查失败统计，是否在冷却期
        FailureStats stats = failureStats.get(normalizedName);
        if (stats != null && stats.isInCooldown(currentTime)) {
            String cooldownMsg = String.format("成员 %s 查询失败次数过多，冷却中 (剩余 %d 秒)", 
                normalizedName, (stats.cooldownUntil - currentTime) / 1000);
            logWarning(cooldownMsg);
            return new OnlineStatusResult(false, cooldownMsg, normalizedName, -1, null, null, null);
        }
        
        // 3. 执行查询
        long startTime = System.currentTimeMillis();
        try {
            String requestBody = "name=" + normalizedName;
            
            okhttp3.Headers headers = getDefaultHeaders();
            
            String response = post(API_MEMBER_ONLINE, headers, requestBody);
            
            JSONObject jsonResponse = JSONUtil.parseObj(response);
            OnlineStatusResult result = parseOnlineStatusResponse(jsonResponse, normalizedName);
            
            long queryTime = System.currentTimeMillis() - startTime;
            
            if (result.isSuccess()) {
                // 查询成功，缓存结果并重置失败统计
                resultCache.put(normalizedName, new CachedResult(result, currentTime));
                failureStats.remove(normalizedName);
                
                logInfo(String.format("成功查询成员 %s 状态，耗时 %d ms", normalizedName, queryTime));
            } else {
                // 查询失败，更新失败统计
                updateFailureStats(normalizedName, currentTime);
                logWarning(String.format("查询成员 %s 状态失败: %s，耗时 %d ms", 
                    normalizedName, result.getMessage(), queryTime));
            }
            
            return result;
            
        } catch (Exception e) {
            long queryTime = System.currentTimeMillis() - startTime;
            updateFailureStats(normalizedName, currentTime);
            
            String errorMsg = String.format("查询异常: %s", e.getMessage());
            logError(String.format("查询成员 %s 状态异常: %s，耗时 %d ms", 
                normalizedName, e.getMessage(), queryTime));
            
            return new OnlineStatusResult(false, errorMsg, normalizedName, -1, null, null, null);
        }
    }
    
    /**
     * 批量异步查询成员在线状态
     * @param memberNames 成员名称列表
     * @return CompletableFuture包装的批量查询结果列表
     */
    public CompletableFuture<List<OnlineStatusResult>> batchQueryMemberOnlineStatusAsync(List<String> memberNames) {
        if (memberNames == null || memberNames.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        
        return asyncMonitor.batchQueryMemberStatus(memberNames)
                .thenApply(batchResults -> 
                    batchResults.stream()
                            .map(batchResult -> {
                                if (batchResult.isSuccess()) {
                                    try {
                                        JSONObject jsonResponse = JSONUtil.parseObj(batchResult.getRawResponse());
                                        return parseOnlineStatusResponse(jsonResponse, batchResult.getMemberName());
                                    } catch (Exception e) {
                                        return new OnlineStatusResult(false, "解析响应失败: " + e.getMessage(), 
                                            batchResult.getMemberName(), -1, null, null, null);
                                    }
                                } else {
                                    return new OnlineStatusResult(false, batchResult.getStatus(), 
                                        batchResult.getMemberName(), -1, null, null, null);
                                }
                            })
                            .collect(java.util.stream.Collectors.toList())
                );
    }
    
    /**
     * 批量查询成员在线状态（同步版本）
     * @param memberNames 成员名称列表
     * @return 批量查询结果列表
     */
    public List<OnlineStatusResult> batchQueryMemberOnlineStatus(List<String> memberNames) {
        try {
            return batchQueryMemberOnlineStatusAsync(memberNames).get();
        } catch (Exception e) {
            return memberNames.stream()
                    .map(name -> new OnlineStatusResult(false, "批量查询异常: " + e.getMessage(), name, -1, null, null, null))
                    .collect(java.util.stream.Collectors.toList());
        }
    }
    
    /**
     * 查询成员在线状态 - 支持成员ID
     * @param memberId 成员ID
     * @return 格式化的状态字符串
     */
    public String queryMemberOnlineStatus(Long memberId) {
        try {
            // 将成员ID转换为字符串进行查询
            OnlineStatusResult result = queryMemberOnlineStatus(String.valueOf(memberId));
            
            if (result.isSuccess()) {
                // 根据状态返回简化的字符串结果
                if (result.getIsOnline() == 1) {
                    return "成员 " + memberId + " 当前在线";
                } else if (result.getIsOnline() == 2) {
                    return "成员 " + memberId + " 当前离线";
                } else {
                    return "成员 " + memberId + " 状态未知";
                }
            } else {
                return "查询成员 " + memberId + " 状态失败: " + result.getMessage();
            }
        } catch (Exception e) {
            return "查询成员 " + memberId + " 状态时发生错误: " + e.getMessage();
        }
    }

    /**
     * 解析API响应 - 优化版本，首先检查msg字段
     */
    private OnlineStatusResult parseOnlineStatusResponse(JSONObject response, String queryName) {
        // 首先检查msg字段是否为"success"
        String msg = response.getStr("msg");
        if (msg == null || !"success".equals(msg)) {
            return new OnlineStatusResult(false, msg != null ? msg : "API响应失败", queryName, -1, null, null, null);
        }
        
        // 然后检查错误码，确保双重验证
        String error = response.getStr("error");
        if (error == null || !"0".equals(error)) {
            return new OnlineStatusResult(false, "错误码异常: " + error, queryName, -1, null, null, null);
        }
        
        // 直接获取嵌套数据，减少中间变量
        JSONObject outerData = response.getJSONObject("data");
        if (outerData == null) {
            return new OnlineStatusResult(false, "响应数据格式异常", queryName, -1, null, null, null);
        }
        
        JSONObject data = outerData.getJSONObject("data");
        if (data == null) {
            return new OnlineStatusResult(false, "响应数据格式异常", queryName, -1, null, null, null);
        }
        
        // 直接获取整数值，避免字符串转换
        Integer isOnlineObj = data.getInt("is_online");
        if (isOnlineObj == null) {
            return new OnlineStatusResult(false, "无法获取在线状态", queryName, -1, null, null, null);
        }
        
        int isOnline = isOnlineObj;
        String userName = data.getStr("user_name");
        if (userName == null || userName.isEmpty()) {
            userName = queryName;
        }
        
        // 预定义字段名，避免重复创建字符串
        String timeInfo = null;
        String lastActiveTime = null;
        
        // 使用位运算优化条件判断
        if ((isOnline & 1) == 1) { // isOnline == 1
            timeInfo = data.getStr("zx");
            lastActiveTime = data.getStr("sx_time");
        } else if ((isOnline & 2) == 2) { // isOnline == 2
            timeInfo = data.getStr("line");
            lastActiveTime = data.getStr("xx_time");
        }
        
        return new OnlineStatusResult(true, "查询成功", userName, isOnline, null, null, lastActiveTime, null, timeInfo);
    }

    /**
     * 根据isonline值获取状态文本
     */
    private String getStatusText(int isOnline) {
        switch (isOnline) {
            case 1:
                return "🟢 在线";
            case 2:
                return "🔴 离线";
            default:
                return "❓ 未知";
        }
    }

    /**
     * 在线状态查询结果类
     */
    public static class OnlineStatusResult {
        private final boolean success;
        private final String message;
        private final String name;
        private final int isOnline;
        private final String status;
        private final String team;
        private final String lastSeen;
        private final Integer score;
        private final String timeInfo; // 新增：时间信息（在线时长或离线时长）

        public OnlineStatusResult(boolean success, String message, String name, int isOnline, 
                                String status, String team, String lastSeen) {
            this(success, message, name, isOnline, status, team, lastSeen, null, null);
        }

        public OnlineStatusResult(boolean success, String message, String name, int isOnline, 
                                String status, String team, String lastSeen, Integer score) {
            this(success, message, name, isOnline, status, team, lastSeen, score, null);
        }

        public OnlineStatusResult(boolean success, String message, String name, int isOnline, 
                                String status, String team, String lastSeen, Integer score, String timeInfo) {
            this.success = success;
            this.message = message;
            this.name = name;
            this.isOnline = isOnline;
            this.status = status;
            this.team = team;
            this.lastSeen = lastSeen;
            this.score = score;
            this.timeInfo = timeInfo;
        }

        // Getters
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getName() { return name; }
        public int getIsOnline() { return isOnline; }
        public String getStatus() { return status; }
        public String getTeam() { return team; }
        public String getLastSeen() { return lastSeen; }
        public Integer getScore() { return score; }
        public String getTimeInfo() { return timeInfo; }

        /**
         * 格式化输出查询结果 - 高性能版本
         */
        public String formatResult() {
            if (!success) {
                return "❌ " + message;
            }

            // 预计算字符串长度，减少StringBuilder扩容
            int capacity = name.length() + 64;
            if (timeInfo != null) capacity += timeInfo.length() + 16;
            if (lastSeen != null) capacity += lastSeen.length() + 16;
            
            StringBuilder result = new StringBuilder(capacity);
            result.append(name).append('\n');
            
            // 使用位运算和预定义字符串减少条件判断
            if ((isOnline & 1) == 1) { // 在线
                result.append("当前在线\n");
                if (timeInfo != null && timeInfo.length() > 0) {
                    result.append("已在线时间 ").append(timeInfo).append('\n');
                }
                if (lastSeen != null && lastSeen.length() > 0) {
                    result.append("上线时间：").append(lastSeen);
                }
            } else if ((isOnline & 2) == 2) { // 离线
                result.append("当前离线\n");
                if (timeInfo != null && timeInfo.length() > 0) {
                    result.append("已离线 ").append(timeInfo).append('\n');
                }
                if (lastSeen != null && lastSeen.length() > 0) {
                    result.append("下线时间：").append(lastSeen);
                }
            } else {
                result.append("状态未知\n");
            }
            
            return result.toString();
        }
    }
    
    /**
     * 更新失败统计
     */
    private void updateFailureStats(String memberName, long currentTime) {
        failureStats.compute(memberName, (key, stats) -> {
            if (stats == null) {
                stats = new FailureStats();
            }
            stats.recordFailure(currentTime, config);
            return stats;
        });
    }
    
    /**
     * 清理过期的缓存和失败统计
     */
    public void cleanupCache() {
        long currentTime = System.currentTimeMillis();
        
        // 清理过期缓存
        resultCache.entrySet().removeIf(entry -> 
            (currentTime - entry.getValue().timestamp) > config.getCacheExpireTime());
        
        // 清理过期的失败统计
        failureStats.entrySet().removeIf(entry -> 
            !entry.getValue().isInCooldown(currentTime) && 
            entry.getValue().consecutiveFailures.get() == 0);
        
        logInfo(String.format("缓存清理完成: 缓存条目 %d, 失败统计 %d", 
            resultCache.size(), failureStats.size()));
    }
    
    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        long currentTime = System.currentTimeMillis();
        int validCacheCount = 0;
        int expiredCacheCount = 0;
        
        for (CachedResult cached : resultCache.values()) {
            if ((currentTime - cached.timestamp) < config.getCacheExpireTime()) {
                validCacheCount++;
            } else {
                expiredCacheCount++;
            }
        }
        
        int cooldownCount = (int) failureStats.values().stream()
            .filter(stats -> stats.isInCooldown(currentTime))
            .count();
        
        return String.format("缓存统计: 有效 %d, 过期 %d, 冷却中 %d, 失败统计 %d",
            validCacheCount, expiredCacheCount, cooldownCount, failureStats.size());
    }
    
    /**
     * 重置所有缓存和统计
     */
    public void resetCache() {
        resultCache.clear();
        failureStats.clear();
        resetStats();
        asyncMonitor.resetAsyncStats();
        logInfo("已重置所有缓存和统计信息（包括异步监控统计）");
    }
    
    /**
     * 获取异步监控统计信息
     */
    public String getAsyncMonitorStats() {
        return asyncMonitor.getAsyncMonitorStats();
    }
    
    /**
     * 获取批量查询性能报告
     */
    public String getBatchQueryReport() {
        return asyncMonitor.getBatchQueryReport();
    }
    
    /**
     * 获取完整的性能统计报告
     */
    public String getFullPerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("🚀 Xox48Handler 完整性能报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        report.append("📊 传统缓存统计:\n");
        report.append(getCacheStats()).append("\n\n");
        report.append("⚡ 异步监控统计:\n");
        report.append(getAsyncMonitorStats()).append("\n\n");
        report.append("📈 批量查询报告:\n");
        report.append(getBatchQueryReport()).append("\n\n");
        report.append("🔧 User-Agent 配置:\n");
        report.append(String.format("可用UA数量: %d\n", USER_AGENTS.size()));
        report.append("当前随机UA: ").append(getRandomUserAgent());
        report.append("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        return report.toString();
    }
    
    /**
     * 缓存结果内部类
     */
    private static class CachedResult {
        final OnlineStatusResult result;
        final long timestamp;
        
        CachedResult(OnlineStatusResult result, long timestamp) {
            this.result = result;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * 失败统计内部类
     */
    private static class FailureStats {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile long lastFailureTime = 0;
        volatile long cooldownUntil = 0;
        
        void recordFailure(long currentTime, net.luffy.util.MonitorConfig config) {
            int failures = consecutiveFailures.incrementAndGet();
            lastFailureTime = currentTime;
            
            if (failures >= config.getMaxConsecutiveFailures()) {
                cooldownUntil = currentTime + config.getFailureCooldown();
            }
        }
        
        boolean isInCooldown(long currentTime) {
            return currentTime < cooldownUntil;
        }
        
        void reset() {
            consecutiveFailures.set(0);
            lastFailureTime = 0;
            cooldownUntil = 0;
        }
    }
}