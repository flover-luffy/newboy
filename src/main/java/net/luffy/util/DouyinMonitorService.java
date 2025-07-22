package net.luffy.util;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import net.luffy.util.UnifiedJsonParser;
import net.luffy.util.UnifiedHttpClient;
// 移除了对旧DouyinHandler的依赖
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
// 已迁移到UnifiedHttpClient，移除okhttp3依赖

import java.util.Map;
import java.util.HashMap;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 抖音监控服务
 * 基于qqtools项目的Worker机制移植到Java
 * 实现用户作品监控、实时推送等功能
 */
public class DouyinMonitorService {
    
    private static final String API_AWEME_POST = "https://www.douyin.com/aweme/v1/web/aweme/post/";
    private static final String API_TTWID = "https://ttwid.bytedance.com/ttwid/union/register/";
    
    // 单例实例
    private static volatile DouyinMonitorService instance;
    
    private final DouyinSignatureGenerator signatureGenerator;
    private final ScheduledExecutorService scheduler;
    private final Map<String, UserMonitorInfo> monitoredUsers;
    private final AtomicBoolean isRunning;
    // 移除了DouyinHandler依赖，现在使用内置的签名生成器
    
    // 限流相关
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private static final long MIN_REQUEST_INTERVAL = 2000; // 2秒最小间隔
    
    // 调试模式
    private static final boolean DEBUG_MODE = Boolean.getBoolean("douyin.debug") || 
        System.getProperty("http.debug", "false").equals("true");
    
    /**
     * 用户监控信息
     */
    public static class UserMonitorInfo {
        public String secUserId;
        public String nickname;
        public String lastAwemeId;
        public long lastCheckTime;
        public long lastUpdateTime;
        public int failureCount;
        public boolean isActive;
        
        public UserMonitorInfo(String secUserId) {
            this.secUserId = secUserId;
            this.lastCheckTime = System.currentTimeMillis();
            this.lastUpdateTime = System.currentTimeMillis();
            this.failureCount = 0;
            this.isActive = true;
        }
    }
    
    private DouyinMonitorService() {
        this.signatureGenerator = new DouyinSignatureGenerator();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.monitoredUsers = new ConcurrentHashMap<>();
        this.isRunning = new AtomicBoolean(false);
        // 不再依赖旧的DouyinHandler
    }
    
    /**
     * 获取单例实例
     * @return DouyinMonitorService实例
     */
    public static DouyinMonitorService getInstance() {
        if (instance == null) {
            synchronized (DouyinMonitorService.class) {
                if (instance == null) {
                    instance = new DouyinMonitorService();
                }
            }
        }
        return instance;
    }
    
    /**
     * 检查监控服务是否正在运行
     * @return 是否正在运行
     */
    public boolean isRunning() {
        return isRunning.get();
    }
    
    /**
     * 启动监控服务
     * @param intervalMinutes 检查间隔（分钟）
     */
    public void startMonitoring(int intervalMinutes) {
        if (isRunning.compareAndSet(false, true)) {
            // 启动抖音监控服务
            
            // 从配置文件加载监控用户
            loadMonitorUsersFromConfig();
            
            // 定期检查用户更新
            scheduler.scheduleWithFixedDelay(
                this::checkAllUsers,
                0,
                intervalMinutes,
                TimeUnit.MINUTES
            );
            
            // 定期清理失效用户
            scheduler.scheduleWithFixedDelay(
                this::cleanupInactiveUsers,
                1,
                60,
                TimeUnit.MINUTES
            );
        }
    }
    
    /**
     * 停止监控服务
     */
    public void stopMonitoring() {
        if (isRunning.compareAndSet(true, false)) {
            // 停止抖音监控服务
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 添加监控用户
     * @param secUserId 用户ID
     * @return 是否添加成功
     */
    public boolean addMonitorUser(String secUserId) {
        if (monitoredUsers.containsKey(secUserId)) {
            return false;
        }
        
        UserMonitorInfo userInfo = new UserMonitorInfo(secUserId);
        
        // 初始化用户信息
        try {
            JSONObject userDetail = getUserInfo(secUserId);
            if (userDetail != null) {
                // 获取最新作品ID作为基准
                JSONArray awemeList = userDetail.getJSONArray("aweme_list");
                if (awemeList != null && !awemeList.isEmpty()) {
                    JSONObject latestAweme = awemeList.getJSONObject(0);
                    userInfo.lastAwemeId = latestAweme.getStr("aweme_id");
                    
                    // 从作品信息中获取用户昵称
                    JSONObject author = latestAweme.getJSONObject("author");
                    if (author != null) {
                        userInfo.nickname = author.getStr("nickname", "未知用户");
                    }
                } else {
                    // 用户没有作品，无法获取昵称
                }
            }
        } catch (Exception e) {
            // 初始化用户信息失败
        }
        
        monitoredUsers.put(secUserId, userInfo);
        // 添加抖音监控用户
        return true;
    }
    
    /**
     * 移除监控用户
     * @param secUserId 用户ID
     * @return 是否移除成功
     */
    public boolean removeMonitorUser(String secUserId) {
        UserMonitorInfo removed = monitoredUsers.remove(secUserId);
        if (removed != null) {
            // 移除抖音监控用户
            return true;
        }
        return false;
    }
    
    /**
     * 从配置文件加载监控用户
     */
    private void loadMonitorUsersFromConfig() {
        try {
            Map<Long, List<String>> douyinSubscribe = Newboy.INSTANCE.getProperties().douyin_user_subscribe;
            if (douyinSubscribe == null || douyinSubscribe.isEmpty()) {
                // 配置文件中没有抖音监控用户配置
                return;
            }
            
            Set<String> allUsers = new HashSet<>();
            for (List<String> userList : douyinSubscribe.values()) {
                if (userList != null) {
                    allUsers.addAll(userList);
                }
            }
            
            if (allUsers.isEmpty()) {
                // 配置文件中没有配置抖音监控用户
                return;
            }
            
            int loadedCount = 0;
            for (String userId : allUsers) {
                if (userId != null && !userId.trim().isEmpty()) {
                    if (addMonitorUser(userId.trim())) {
                        loadedCount++;
                    }
                }
            }
            
            // 从配置文件加载抖音监控用户完成
            
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("从配置文件加载抖音监控用户失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查所有用户的更新
     */
    private void checkAllUsers() {
        if (monitoredUsers.isEmpty()) {
            return;
        }
        
        // 开始检查抖音用户更新
        
        for (UserMonitorInfo userInfo : monitoredUsers.values()) {
            if (!userInfo.isActive) {
                continue;
            }
            
            try {
                checkUserUpdate(userInfo);
                // 限流等待
                waitForRateLimit();
            } catch (Exception e) {
                userInfo.failureCount++;
                // 检查用户更新失败，记录错误
                Newboy.INSTANCE.getLogger().error(
                    String.format("检查用户 %s 更新失败 (失败次数: %d): %s", 
                        userInfo.nickname, userInfo.failureCount, e.getMessage())
                );
                
                // 连续失败过多次则暂时停用
                if (userInfo.failureCount >= 5) {
                    userInfo.isActive = false;
                    Newboy.INSTANCE.getLogger().error("用户 " + userInfo.nickname + " 连续失败过多，暂时停用监控");
                }
            }
        }
    }
    
    /**
     * 检查单个用户的更新
     * @param userInfo 用户信息
     */
    private void checkUserUpdate(UserMonitorInfo userInfo) {
        JSONObject userDetail = getUserInfo(userInfo.secUserId);
        if (userDetail == null) {
            return;
        }
        
        JSONArray awemeList = userDetail.getJSONArray("aweme_list");
        if (awemeList == null || awemeList.isEmpty()) {
            return;
        }
        
        JSONObject latestAweme = awemeList.getJSONObject(0);
        String latestAwemeId = latestAweme.getStr("aweme_id");
        
        // 检查是否有新作品
        if (userInfo.lastAwemeId != null && !userInfo.lastAwemeId.equals(latestAwemeId)) {
            // 发现新作品
            long createTime = latestAweme.getLong("create_time", 0L) * 1000;
            
            // 确保新作品的时间晚于上次检查时间
            if (createTime > userInfo.lastUpdateTime) {
                handleNewAweme(userInfo, latestAweme);
                userInfo.lastUpdateTime = createTime;
            }
        }
        
        // 更新用户信息
        userInfo.lastAwemeId = latestAwemeId;
        userInfo.lastCheckTime = System.currentTimeMillis();
        userInfo.failureCount = 0; // 重置失败计数
        
        // 更新昵称（可能会变化）
        if (awemeList != null && !awemeList.isEmpty()) {
            JSONObject author = latestAweme.getJSONObject("author");
            if (author != null) {
                String currentNickname = author.getStr("nickname");
                if (currentNickname != null && !currentNickname.equals(userInfo.nickname)) {
                    // 用户昵称变更
                    userInfo.nickname = currentNickname;
                }
            }
        }
    }
    
    /**
     * 处理新作品
     * @param userInfo 用户信息
     * @param aweme 作品信息
     */
    private void handleNewAweme(UserMonitorInfo userInfo, JSONObject aweme) {
        try {
            String message = formatAwemeMessage(userInfo, aweme);
            
            // 通过内置服务发送消息到相关群组
            // 这里需要根据实际的群组订阅关系来发送
            notifySubscribedGroups(userInfo.secUserId, message);
            
            // 检测到用户新作品
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("处理新作品失败: " + e.getMessage());
        }
    }
    
    /**
     * 格式化作品消息
     * @param userInfo 用户信息
     * @param aweme 作品信息
     * @return 格式化的消息
     */
    private String formatAwemeMessage(UserMonitorInfo userInfo, JSONObject aweme) {
        StringBuilder message = new StringBuilder();
        message.append("🎵 抖音新作品推送\n\n");
        message.append("👤 用户: ").append(userInfo.nickname).append("\n");
        
        String desc = aweme.getStr("desc", "");
        if (!desc.isEmpty()) {
            message.append("📝 描述: ").append(desc).append("\n");
        }
        
        // 获取作品统计信息
        JSONObject statistics = aweme.getJSONObject("statistics");
        if (statistics != null) {
            int diggCount = statistics.getInt("digg_count", 0);
            int commentCount = statistics.getInt("comment_count", 0);
            int shareCount = statistics.getInt("share_count", 0);
            
            message.append("❤️ 点赞: ").append(formatCount(diggCount));
            message.append(" 💬 评论: ").append(formatCount(commentCount));
            message.append(" 🔄 分享: ").append(formatCount(shareCount)).append("\n");
        }
        
        // 作品链接
        String awemeId = aweme.getStr("aweme_id");
        if (awemeId != null) {
            message.append("🔗 链接: https://www.douyin.com/video/").append(awemeId);
        }
        
        return message.toString();
    }
    
    /**
     * 格式化数字显示
     */
    private String formatCount(int count) {
        if (count >= 10000) {
            return String.format("%.1fw", count / 10000.0);
        }
        return String.valueOf(count);
    }
    
    /**
     * 通知订阅的群组
     * @param secUserId 用户ID
     * @param message 消息内容
     */
    private void notifySubscribedGroups(String secUserId, String message) {
        // 获取订阅该用户的群组列表
        Map<Long, List<String>> subscriptions = Newboy.INSTANCE.getProperties().douyin_user_subscribe;
        
        for (Map.Entry<Long, List<String>> entry : subscriptions.entrySet()) {
            if (entry.getValue().contains(secUserId)) {
                long groupId = entry.getKey();
                
                // 发送消息到群组
                try {
                    Bot bot = Newboy.getBot();
                    if (bot != null) {
                        Group group = bot.getGroup(groupId);
                        if (group != null) {
                            group.sendMessage(message);
                        }
                    }
                } catch (Exception e) {
                    Newboy.INSTANCE.getLogger().error(
                        String.format("发送抖音消息到群 %d 失败: %s", groupId, e.getMessage())
                    );
                }
            }
        }
    }
    
    /**
     * 获取用户信息
     * @param secUserId 用户ID
     * @return 用户信息JSON
     */
    private JSONObject getUserInfo(String secUserId) {
        return getUserInfoWithRetry(secUserId, 3);
    }
    
    /**
     * 获取用户信息（带重试机制）
     * @param secUserId 用户ID
     * @param maxRetries 最大重试次数
     * @return 用户信息JSON
     */
    private JSONObject getUserInfoWithRetry(String secUserId, int maxRetries) {
        Exception lastException = null;
        
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    // 重试前等待，避免频繁请求
                    Thread.sleep(1000 * (attempt + 1));
                    Newboy.INSTANCE.getLogger().info(
                        String.format("重试获取抖音用户信息，用户: %s, 第%d次尝试", secUserId, attempt + 1)
                    );
                }
                
                JSONObject result = performGetUserInfo(secUserId);
                if (result != null) {
                    if (attempt > 0) {
                        Newboy.INSTANCE.getLogger().info(
                            String.format("重试成功获取抖音用户信息，用户: %s", secUserId)
                        );
                    }
                    return result;
                }
            } catch (Exception e) {
                lastException = e;
                Newboy.INSTANCE.getLogger().error(
                     String.format("获取抖音用户信息失败，用户: %s, 尝试: %d/%d, 错误: %s", 
                         secUserId, attempt + 1, maxRetries, e.getMessage())
                 );
            }
        }
        
        // 所有重试都失败
        Newboy.INSTANCE.getLogger().error(
            String.format("获取抖音用户信息最终失败，用户: %s, 已重试%d次", secUserId, maxRetries),
            lastException
        );
        return null;
    }
    
    /**
     * 执行获取用户信息的实际请求
     * @param secUserId 用户ID
     * @return 用户信息JSON
     */
    private JSONObject performGetUserInfo(String secUserId) {
        try {
            Map<String, String> params = signatureGenerator.buildAwemePostQuery(secUserId, null, 18);
            String queryString = signatureGenerator.buildQueryString(params);
            String userAgent = signatureGenerator.getRandomUserAgent();
            
            // 生成签名
            String aBogus = signatureGenerator.generateABogus(queryString);
            params.put("a_bogus", aBogus);
            
            // 构建最终URL
            String finalQueryString = signatureGenerator.buildQueryString(params);
            String url = API_AWEME_POST + "?" + finalQueryString;
            
            // 构建请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", userAgent);
            headers.put("Referer", "https://www.douyin.com/user/" + secUserId);
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            // 移除Accept-Encoding，让OkHttp自动处理压缩
            headers.put("Connection", "keep-alive");
            headers.put("Sec-Fetch-Dest", "empty");
            headers.put("Sec-Fetch-Mode", "cors");
            headers.put("Sec-Fetch-Site", "same-origin");
            headers.put("Cache-Control", "no-cache");
            headers.put("Pragma", "no-cache");
            
            // 添加Cookie
            // Cookie管理现在通过配置文件处理
            String cookie = Newboy.INSTANCE.getProperties().douyin_cookie;
            if (cookie != null && !cookie.isEmpty()) {
                headers.put("Cookie", cookie);
            }
            
            // 调试模式下输出请求信息
            if (DEBUG_MODE) {
                Newboy.INSTANCE.getLogger().info("抖音API请求URL: " + url);
                Newboy.INSTANCE.getLogger().info("抖音API请求头: " + headers.toString());
            }
            
            String responseBody = UnifiedHttpClient.getInstance().get(url, headers);
            
            // 调试模式下输出响应信息
            if (DEBUG_MODE) {
                Newboy.INSTANCE.getLogger().info("抖音API响应长度: " + 
                    (responseBody != null ? responseBody.length() : "null"));
                if (responseBody != null && responseBody.length() < 1000) {
                    Newboy.INSTANCE.getLogger().info("抖音API完整响应: " + responseBody);
                }
            }
            
            // 详细的响应分析
            if (responseBody == null) {
                Newboy.INSTANCE.getLogger().error("抖音API返回空响应");
                return null;
            }
            
            // 检查响应是否包含乱码或二进制数据
            if (containsBinaryData(responseBody)) {
                Newboy.INSTANCE.getLogger().error("抖音API返回二进制数据或编码错误，可能是压缩问题: " + 
                    getResponsePreview(responseBody));
                return null;
            }
            
            // 检查响应是否为有效JSON
            String trimmedResponse = responseBody.trim();
            if (trimmedResponse.startsWith("{")) {
                try {
                    JSONObject result = UnifiedJsonParser.getInstance().parseObj(responseBody);
                    
                    if (result.getInt("status_code", -1) == 0) {
                        return result;
                    } else {
                        Newboy.INSTANCE.getLogger().error(
                            "抖音API返回错误状态: " + result.getInt("status_code", -1) + 
                            ", 消息: " + result.getStr("status_msg", "未知错误")
                        );
                    }
                } catch (Exception parseException) {
                    Newboy.INSTANCE.getLogger().error("解析抖音API响应JSON失败: " + parseException.getMessage() + 
                        ", 响应内容: " + getResponsePreview(responseBody));
                }
            } else {
                Newboy.INSTANCE.getLogger().error("抖音API返回非JSON格式响应: " + getResponsePreview(responseBody));
            }
        } catch (Exception e) {
            // 抛出异常让重试机制处理
            throw new RuntimeException("获取抖音用户信息失败: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * 限流等待
     */
    private void waitForRateLimit() {
        long currentTime = System.currentTimeMillis();
        long lastTime = lastRequestTime.get();
        long elapsed = currentTime - lastTime;
        
        if (elapsed < MIN_REQUEST_INTERVAL) {
            try {
                Thread.sleep(MIN_REQUEST_INTERVAL - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        lastRequestTime.set(System.currentTimeMillis());
    }
    
    /**
     * 清理不活跃的用户
     */
    private void cleanupInactiveUsers() {
        long currentTime = System.currentTimeMillis();
        long inactiveThreshold = 24 * 60 * 60 * 1000; // 24小时
        
        Iterator<Map.Entry<String, UserMonitorInfo>> iterator = monitoredUsers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, UserMonitorInfo> entry = iterator.next();
            UserMonitorInfo userInfo = entry.getValue();
            
            if (!userInfo.isActive && (currentTime - userInfo.lastCheckTime) > inactiveThreshold) {
                iterator.remove();
                // 清理不活跃用户
            }
        }
    }
    
    /**
     * 获取监控状态
     * @return 监控状态信息
     */
    public String getMonitorStatus() {
        StringBuilder status = new StringBuilder();
        status.append("抖音监控服务状态:\n");
        status.append("运行状态: ").append(isRunning.get() ? "运行中" : "已停止").append("\n");
        status.append("监控用户数: ").append(monitoredUsers.size()).append("\n");
        
        int activeUsers = (int) monitoredUsers.values().stream().mapToLong(u -> u.isActive ? 1 : 0).sum();
        status.append("活跃用户数: ").append(activeUsers).append("\n");
        
        return status.toString();
    }
    
    /**
     * 获取用户列表
     * @return 用户列表信息
     */
    public String getUserList() {
        if (monitoredUsers.isEmpty()) {
            return "当前没有监控的抖音用户";
        }
        
        StringBuilder list = new StringBuilder();
        list.append("抖音监控用户列表:\n");
        
        int index = 1;
        for (UserMonitorInfo userInfo : monitoredUsers.values()) {
            list.append(index++).append(". ");
            list.append(userInfo.nickname).append(" (").append(userInfo.secUserId).append(")\n");
            list.append("   状态: ").append(userInfo.isActive ? "活跃" : "暂停");
            list.append(", 失败次数: ").append(userInfo.failureCount).append("\n");
        }
        
        return list.toString();
    }
    
    /**
     * 重新激活用户监控
     * @param secUserId 用户ID
     * @return 是否成功
     */
    public boolean reactivateUser(String secUserId) {
        UserMonitorInfo userInfo = monitoredUsers.get(secUserId);
        if (userInfo != null && !userInfo.isActive) {
            userInfo.isActive = true;
            userInfo.failureCount = 0;
            // 重新激活用户监控
            return true;
        }
        return false;
    }
    
    /**
     * 获取监控用户的昵称
     * @param secUserId 用户ID
     * @return 用户昵称，如果用户不存在则返回null
     */
    public String getMonitoredUserNickname(String secUserId) {
        UserMonitorInfo userInfo = monitoredUsers.get(secUserId);
        return userInfo != null ? userInfo.nickname : null;
    }
    
    /**
     * 获取监控状态（别名方法）
     * @return 监控状态信息
     */
    public String getStatus() {
        return getMonitorStatus();
    }
    
    /**
     * 获取监控用户列表（别名方法）
     * @return 用户列表信息
     */
    public String getMonitoredUsersList() {
        return getUserList();
    }
    
    /**
     * 检查响应是否包含二进制数据或乱码
     * @param response 响应字符串
     * @return 是否包含二进制数据
     */
    private boolean containsBinaryData(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        
        // 检查是否包含大量不可打印字符
        int nonPrintableCount = 0;
        int totalChars = Math.min(response.length(), 200); // 只检查前200个字符
        
        for (int i = 0; i < totalChars; i++) {
            char c = response.charAt(i);
            // 检查是否为不可打印字符（排除常见的空白字符）
            if (c < 32 && c != '\t' && c != '\n' && c != '\r') {
                nonPrintableCount++;
            }
        }
        
        // 如果不可打印字符超过10%，认为是二进制数据
        return (double) nonPrintableCount / totalChars > 0.1;
    }
    
    /**
     * 获取响应内容的安全预览
     * @param response 响应字符串
     * @return 预览字符串
     */
    private String getResponsePreview(String response) {
        if (response == null) {
            return "null";
        }
        
        if (response.isEmpty()) {
            return "empty";
        }
        
        // 限制预览长度
        int previewLength = Math.min(100, response.length());
        String preview = response.substring(0, previewLength);
        
        // 替换不可打印字符为可读形式
        StringBuilder safePreview = new StringBuilder();
        for (char c : preview.toCharArray()) {
            if (c >= 32 && c <= 126) {
                // 可打印ASCII字符
                safePreview.append(c);
            } else if (c == '\t') {
                safePreview.append("\\t");
            } else if (c == '\n') {
                safePreview.append("\\n");
            } else if (c == '\r') {
                safePreview.append("\\r");
            } else {
                // 其他不可打印字符显示为十六进制
                safePreview.append(String.format("\\x%02X", (int) c));
            }
        }
        
        if (response.length() > previewLength) {
            safePreview.append("...(truncated)");
        }
        
        return safePreview.toString();
    }
    
    /**
     * 重新加载配置中的监控用户
     */
    public void reloadMonitorUsers() {
        // 重新加载抖音监控用户配置
        loadMonitorUsersFromConfig();
    }
}