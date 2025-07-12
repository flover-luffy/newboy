package net.luffy.util;

import net.luffy.Newboy;
import net.luffy.handler.Xox48Handler;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.PlainText;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 增强的在线状态监控器
 * 提供成员在线状态的监控和通知功能，支持健康检查、失败统计、性能监控等
 */
public class OnlineStatusMonitor {
    
    public static OnlineStatusMonitor INSTANCE;
    
    // 存储每个群的监控配置：群ID -> 成员名称 -> 上次状态
    private final Map<Long, Map<String, Integer>> groupMonitorConfig = new ConcurrentHashMap<>();
    
    // 存储监控状态的变化历史
    private final Map<String, StatusHistory> statusHistory = new ConcurrentHashMap<>();
    
    // 健康检查和监控指标
    private final Map<String, MemberHealthStats> memberHealthStats = new ConcurrentHashMap<>();
    private final AtomicLong totalChecks = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicLong totalNotifications = new AtomicLong(0);
    private volatile long lastCheckTime = 0;
    private volatile boolean isHealthy = true;
    
    // 配置管理
    private final MonitorConfig config = MonitorConfig.getInstance();
    
    // 上次健康警告发送时间
    private volatile long lastHealthWarningTime = 0;
    
    // 定时任务ID
    private String cronScheduleID;
    
    public OnlineStatusMonitor() {
        INSTANCE = this;
    }
    
    /**
     * 从配置文件初始化监控配置
     * 这个方法应该在配置加载完成后调用
     */
    public void initFromConfig() {
        // 清空现有配置
        groupMonitorConfig.clear();
        statusHistory.clear();
        
        // 从Properties中加载配置
        Map<Long, List<String>> configSubscribe = Newboy.INSTANCE.getProperties().onlineStatus_subscribe;
        
        for (Map.Entry<Long, List<String>> entry : configSubscribe.entrySet()) {
            long groupId = entry.getKey();
            List<String> memberNames = entry.getValue();
            
            if (memberNames != null && !memberNames.isEmpty()) {
                Map<String, Integer> groupConfig = new ConcurrentHashMap<>();
                
                for (String memberName : memberNames) {
                    try {
                        // 查询当前状态作为初始状态
                        Xox48Handler.OnlineStatusResult result = 
                            Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
                        
                        if (result.isSuccess()) {
                            int currentStatus = result.getIsOnline();
                            String userName = result.getName();
                            
                            groupConfig.put(userName, currentStatus);
                            statusHistory.put(userName, new StatusHistory(userName, currentStatus));
                            
                            Newboy.INSTANCE.getLogger().info(
                                String.format("已加载监控配置: 群%d - %s (状态: %s)", 
                                    groupId, userName, currentStatus == 1 ? "在线" : "离线"));
                        } else {
                            // 如果查询失败，设置默认状态为离线
                            groupConfig.put(memberName, 0);
                            statusHistory.put(memberName, new StatusHistory(memberName, 0));
                            
                            Newboy.INSTANCE.getLogger().warning(
                                String.format("加载监控配置时查询失败: 群%d - %s, 设置为离线状态", 
                                    groupId, memberName));
                        }
                    } catch (Exception e) {
                        // 异常情况下设置默认状态为离线
                        groupConfig.put(memberName, 0);
                        statusHistory.put(memberName, new StatusHistory(memberName, 0));
                        
                        Newboy.INSTANCE.getLogger().warning(
                            String.format("加载监控配置时发生异常: 群%d - %s, 错误: %s", 
                                groupId, memberName, e.getMessage()));
                    }
                }
                
                if (!groupConfig.isEmpty()) {
                    groupMonitorConfig.put(groupId, groupConfig);
                }
            }
        }
        
        Newboy.INSTANCE.getLogger().info(
            String.format("在线状态监控配置加载完成: %d个群组, 共%d个监控成员", 
                groupMonitorConfig.size(), 
                groupMonitorConfig.values().stream().mapToInt(Map::size).sum()));
    }
    
    /**
     * 监控指定群组的成员列表（已弃用，使用checkStatusChanges代替）
     * @param bot 机器人实例
     * @param groupId 群ID
     * @param memberIds 成员ID列表
     * @deprecated 此方法已弃用，新的监控系统使用成员名称而不是ID
     */
    @Deprecated
    public void monitorGroupMembers(Bot bot, long groupId, java.util.List<Long> memberIds) {
        // 此方法已弃用，因为新的监控系统使用成员名称而不是成员ID
        // 如果需要监控成员，请使用 addMonitor(groupId, memberName) 方法
        Newboy.INSTANCE.getLogger().warning("monitorGroupMembers方法已弃用，请使用基于成员名称的新监控系统");
    }
    
    /**
     * 解析在线状态字符串
     * @param statusResult 状态查询结果
     * @return 1表示在线，0表示离线
     */
    private int parseOnlineStatus(String statusResult) {
        if (statusResult != null && statusResult.contains("在线")) {
            return 1;
        }
        return 0;
    }
    
    /**
     * 解析在线状态结果对象
     * @param result 状态查询结果对象
     * @return 1表示在线，2表示离线，-1表示未知
     */
    private int parseOnlineStatus(Xox48Handler.OnlineStatusResult result) {
        if (result != null && result.isSuccess()) {
            return result.getIsOnline();
        }
        return -1;
    }
    
    /**
     * 为指定群添加成员监控
     * @param groupId 群ID
     * @param memberName 成员名称
     * @return 操作结果消息
     */
    public String addMonitor(long groupId, String memberName) {
        groupMonitorConfig.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>());
        
        // 获取当前状态作为初始状态
        try {
            // 使用成员名称查询，获取完整的结果对象
            Xox48Handler.OnlineStatusResult result = Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
            
            if (!result.isSuccess()) {
                return String.format("❌ 添加监控失败：%s", result.getMessage());
            }
            
            // 使用API返回的user_name作为存储的键值
            String userName = result.getName();
            int currentStatus = result.getIsOnline();
            
            groupMonitorConfig.get(groupId).put(userName, currentStatus);
            statusHistory.put(userName, new StatusHistory(userName, currentStatus));
            
            // 保存配置到文件
            Newboy.INSTANCE.getConfig().addOnlineStatusSubscribe(userName, groupId);
            
            String statusText = currentStatus == 1 ? "🟢 在线" : "🔴 离线";
            return String.format("✅ 已添加对 %s 的监控\n📊 当前状态：%s", userName, statusText);
        } catch (Exception e) {
            return String.format("❌ 添加监控失败：%s", e.getMessage());
        }
    }
    
    /**
     * 移除指定群的成员监控
     * @param groupId 群ID
     * @param memberName 成员名称
     * @return 操作结果消息
     */
    public String removeMonitor(long groupId, String memberName) {
        Map<String, Integer> groupConfig = groupMonitorConfig.get(groupId);
        if (groupConfig != null && groupConfig.containsKey(memberName)) {
            groupConfig.remove(memberName);
            
            // 从配置文件中移除
            Newboy.INSTANCE.getConfig().rmOnlineStatusSubscribe(memberName, groupId);
            
            return String.format("✅ 已移除对 %s 的监控", memberName);
        } else {
            return String.format("❌ 未找到对 %s 的监控配置", memberName);
        }
    }
    
    /**
     * 添加监控（简化版本，用于命令调用）
     */
    public void addMonitor(String memberName, long groupId) {
        addMonitor(groupId, memberName);
    }
    
    /**
     * 移除监控（简化版本，用于命令调用）
     */
    public void removeMonitor(String memberName, long groupId) {
        removeMonitor(groupId, memberName);
    }
    
    /**
     * 检查状态变化（简化版本，用于命令调用）
     */
    public String checkStatusChange(String memberName, long groupId) {
        return checkStatusChange(groupId, memberName);
    }
    
    /**
     * 获取指定群的监控列表
     * @param groupId 群ID
     * @return 监控列表信息
     */
    public String getMonitorList(long groupId) {
        return getMonitorList(groupId, false);
    }
    
    /**
     * 获取指定群的监控列表
     * @param groupId 群ID
     * @param realTime 是否显示实时状态对比
     * @return 格式化的监控列表字符串
     */
    public String getMonitorList(long groupId, boolean realTime) {
        Map<String, Integer> groupConfig = groupMonitorConfig.get(groupId);
        
        if (groupConfig == null || groupConfig.isEmpty()) {
            return "📋 当前群暂无监控配置";
        }
        
        StringBuilder result = new StringBuilder();
        result.append(realTime ? "📊 实时监控列表对比:\n" : "📋 当前群监控列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        int count = 1;
        for (Map.Entry<String, Integer> entry : groupConfig.entrySet()) {
            String memberName = entry.getKey();
            int cachedStatus = entry.getValue();
            String cachedStatusIcon = cachedStatus == 1 ? "🟢" : "🔴";
            String cachedStatusText = cachedStatus == 1 ? "在线" : "离线";
            
            if (realTime) {
                // 查询实时状态
                try {
                    Xox48Handler.OnlineStatusResult realTimeResult = 
                        Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
                    
                    if (realTimeResult.isSuccess()) {
                        int realTimeStatus = realTimeResult.getIsOnline();
                        String realTimeStatusIcon = realTimeStatus == 1 ? "🟢" : "🔴";
                        String realTimeStatusText = realTimeStatus == 1 ? "在线" : "离线";
                        
                        // 显示缓存状态 vs 实时状态
                        result.append(String.format("%d. %s\n", count++, memberName));
                        result.append(String.format("   缓存: %s %s | 实时: %s %s", 
                            cachedStatusIcon, cachedStatusText, realTimeStatusIcon, realTimeStatusText));
                        
                        // 标记状态不一致
                        if (cachedStatus != realTimeStatus) {
                            result.append(" ⚠️ 不一致");
                        }
                    } else {
                        result.append(String.format("%d. %s %s %s (实时查询失败)", 
                            count++, memberName, cachedStatusIcon, cachedStatusText));
                    }
                } catch (Exception e) {
                    result.append(String.format("%d. %s %s %s (查询异常)", 
                        count++, memberName, cachedStatusIcon, cachedStatusText));
                }
            } else {
                result.append(String.format("%d. %s - %s %s", count++, memberName, cachedStatusIcon, cachedStatusText));
            }
            
            // 添加历史信息
            StatusHistory history = statusHistory.get(memberName);
            if (history != null && history.getChangeCount() > 0) {
                result.append(String.format(" (变化%d次, 最后: %s)", 
                    history.getChangeCount(), history.getLastChangeTime()));
            }
            result.append("\n");
            
            if (count <= groupConfig.size()) {
                result.append("\n");
            }
        }
        
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        result.append(String.format("📈 总计监控: %d 个成员", groupConfig.size()));
        if (realTime) {
            result.append("\n💡 提示: 使用 '/newboy monitor sync' 同步状态");
        }
        
        return result.toString();
    }
    
    /**
     * 检查成员状态变化并发送通知
     * @param groupId 群ID
     * @param memberName 成员名称
     * @return 状态变化信息
     */
    public String checkStatusChange(long groupId, String memberName) {
        Map<String, Integer> groupConfig = groupMonitorConfig.get(groupId);
        if (groupConfig == null || !groupConfig.containsKey(memberName)) {
            return "❌ 该成员未在监控列表中";
        }
        
        try {
            // 使用成员名称查询状态
            Xox48Handler.OnlineStatusResult result = Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
            
            if (!result.isSuccess()) {
                return String.format("❌ 查询状态失败：%s", result.getMessage());
            }
            
            int currentStatus = result.getIsOnline();
            Integer lastStatus = groupConfig.get(memberName);
            
            if (lastStatus.equals(currentStatus)) {
                String statusText = currentStatus == 1 ? "🟢 在线" : "🔴 离线";
                return String.format("📊 %s 状态无变化：%s", memberName, statusText);
            } else {
                // 状态发生变化
                String oldStatusText = lastStatus == 1 ? "在线" : "离线";
                String newStatusText = currentStatus == 1 ? "在线" : "离线";
                String statusIcon = currentStatus == 1 ? "🟢" : "🔴";
                
                String notification;
                if (currentStatus == 1) {
                    // 上线通知
                    notification = String.format(
                        "%s\n" +
                        "🟢上线啦！\n" +
                        "上线时间：%s",
                        memberName,
                        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    );
                } else {
                    // 下线通知
                    notification = String.format(
                        "%s\n" +
                        "🔴下线啦！\n" +
                        "下线时间：%s",
                        memberName,
                        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    );
                }
                
                // 更新状态
                groupConfig.put(memberName, currentStatus);
                
                // 更新历史记录
                StatusHistory history = statusHistory.get(memberName);
                if (history != null) {
                    history.recordChange(currentStatus);
                }
                
                // 发送通知到群
                sendNotificationToGroup(groupId, notification);
                
                return notification;
            }
        } catch (Exception e) {
            return String.format("❌ 查询状态失败：%s", e.getMessage());
        }
    }
    
    /**
     * 检查所有监控的成员状态变化（增强版）
     * 这个方法应该被定时任务调用
     * 功能特性：
     * 1. 健康检查：监控整体系统健康状态
     * 2. 失败统计：记录每个成员的查询失败情况
     * 3. 自动恢复：对连续失败的成员进行特殊处理
     * 4. 性能监控：记录查询耗时和成功率
     */
    public void checkStatusChanges() {
        long startTime = System.currentTimeMillis();
        lastCheckTime = startTime;
        
        int totalMembers = 0;
        int successCount = 0;
        int failureCount = 0;
        int statusChangeCount = 0;
        
        Newboy.INSTANCE.getLogger().info("开始检查成员状态变化...");
        
        for (Map.Entry<Long, Map<String, Integer>> groupEntry : groupMonitorConfig.entrySet()) {
            long groupId = groupEntry.getKey();
            Map<String, Integer> memberConfig = groupEntry.getValue();
            
            for (Map.Entry<String, Integer> memberEntry : memberConfig.entrySet()) {
                String memberName = memberEntry.getKey();
                int lastStatus = memberEntry.getValue();
                totalMembers++;
                
                // 获取或创建健康统计
                MemberHealthStats healthStats = memberHealthStats.computeIfAbsent(
                    memberName, k -> new MemberHealthStats(memberName));
                
                try {
                    totalChecks.incrementAndGet();
                    
                    // 检查是否应该跳过此成员（连续失败过多）
                    if (healthStats.shouldSkipCheck()) {
                        Newboy.INSTANCE.getLogger().warning(
                            String.format("跳过成员 %s 检查 - 连续失败 %d 次，下次检查时间: %s", 
                                memberName, healthStats.consecutiveFailures.get(),
                                LocalDateTime.ofEpochSecond(healthStats.nextCheckTime / 1000, 0, 
                                    java.time.ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now()))
                                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
                        continue;
                    }
                    
                    // 使用成员名称查询当前状态
                    Xox48Handler.OnlineStatusResult result = Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
                    
                    if (!result.isSuccess()) {
                        failureCount++;
                        totalFailures.incrementAndGet();
                        healthStats.recordFailure();
                        
                        Newboy.INSTANCE.getLogger().warning(
                            String.format("监控成员 %s 状态查询失败: %s (连续失败 %d 次)", 
                                memberName, result.getMessage(), healthStats.consecutiveFailures.get()));
                        continue;
                    }
                    
                    // 查询成功，重置失败计数
                    successCount++;
                    healthStats.recordSuccess();
                    
                    int currentStatus = result.getIsOnline();
                    
                    if (currentStatus != lastStatus) {
                        statusChangeCount++;
                        // 状态发生变化，发送通知
                        String oldStatusText = lastStatus == 1 ? "在线" : "离线";
                        String newStatusText = currentStatus == 1 ? "在线" : "离线";
                        String statusIcon = currentStatus == 1 ? "🟢" : "🔴";
                        
                        String notification;
                        if (currentStatus == 1) {
                            // 上线通知
                            notification = String.format(
                                "%s\n" +
                                "🟢上线啦！\n" +
                                "上线时间：%s\n" +
                                "健康度：%.1f%%",
                                memberName,
                                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                                healthStats.getSuccessRate() * 100
                            );
                        } else {
                            // 下线通知
                            notification = String.format(
                                "%s\n" +
                                "🔴下线啦！\n" +
                                "下线时间：%s\n" +
                                "健康度：%.1f%%",
                                memberName,
                                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                                healthStats.getSuccessRate() * 100
                            );
                        }
                        
                        // 发送通知到对应群
                        sendNotificationToGroup(groupId, notification);
                        totalNotifications.incrementAndGet();
                        
                        // 更新状态
                        memberConfig.put(memberName, currentStatus);
                        
                        // 更新历史记录
                        StatusHistory history = statusHistory.get(memberName);
                        if (history != null) {
                            history.recordChange(currentStatus);
                        }
                        
                        Newboy.INSTANCE.getLogger().info(String.format(
                            "成员 %s 状态变化: %s → %s (健康度: %.1f%%)", 
                            memberName, oldStatusText, newStatusText, healthStats.getSuccessRate() * 100));
                    }
                } catch (Exception e) {
                    failureCount++;
                    totalFailures.incrementAndGet();
                    
                    // 获取健康统计并记录异常
                    MemberHealthStats healthStats = memberHealthStats.computeIfAbsent(
                        memberName, k -> new MemberHealthStats(memberName));
                    healthStats.recordFailure();
                    
                    Newboy.INSTANCE.getLogger().warning(
                        String.format("监控成员 %s 状态时发生错误: %s (连续失败 %d 次)", 
                            memberName, e.getMessage(), healthStats.consecutiveFailures.get()));
                }
            }
        }
        
        // 计算本次检查的性能指标
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        double successRate = totalMembers > 0 ? (double) successCount / totalMembers : 1.0;
        
        // 更新系统健康状态
        isHealthy = successRate >= (1.0 - config.getFailureRateThreshold()) && failureCount < totalMembers * 0.3;
        
        // 记录检查结果
        if (config.isVerboseLogging()) {
            Newboy.INSTANCE.getLogger().info(
                String.format("状态检查完成 - 耗时: %dms, 总成员: %d, 成功: %d, 失败: %d, 状态变化: %d, 成功率: %.1f%%, 系统健康: %s",
                    duration, totalMembers, successCount, failureCount, statusChangeCount, 
                    successRate * 100, isHealthy ? "是" : "否"));
        }
        
        // 如果系统不健康且启用了健康警告，发送警告
        if (!isHealthy && totalMembers > 0 && config.isSystemHealthWarning()) {
            long currentTime = System.currentTimeMillis();
            // 检查是否需要发送健康警告（避免频繁发送）
            if (currentTime - lastHealthWarningTime > config.getHealthWarningInterval()) {
                String healthWarning = String.format(
                    "⚠️ 监控系统健康警告\n" +
                    "成功率: %.1f%% (阈值: %.1f%%)\n" +
                    "失败数: %d/%d\n" +
                    "检查时间: %s\n" +
                    "建议检查网络连接和API状态",
                    successRate * 100, (1.0 - config.getFailureRateThreshold()) * 100,
                    failureCount, totalMembers,
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                
                // 向所有监控群发送健康警告
                for (long groupId : groupMonitorConfig.keySet()) {
                    sendNotificationToGroup(groupId, healthWarning);
                }
                
                lastHealthWarningTime = currentTime;
                Newboy.INSTANCE.getLogger().warning("已发送监控系统健康警告");
            }
        }
        
        // 定期清理过期的健康统计数据
        if (System.currentTimeMillis() % config.getHealthCheckInterval() < 60000) {
            cleanupHealthStats();
        }
    }
    
    /**
     * 发送通知到指定群
     */
    private void sendNotificationToGroup(long groupId, String message) {
        try {
            for (Bot bot : Bot.getInstances()) {
                Group group = bot.getGroup(groupId);
                if (group != null) {
                    group.sendMessage(new PlainText(message));
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 切换监控功能开关
     * @return 当前监控状态
     */
    public boolean toggleMonitoring() {
        boolean currentStatus = Newboy.INSTANCE.getProperties().onlineStatus_enable;
        boolean newStatus = !currentStatus;
        
        Newboy.INSTANCE.getProperties().onlineStatus_enable = newStatus;
        Newboy.INSTANCE.getConfig().saveOnlineStatusConfig();
        
        return newStatus;
    }
    
    /**
     * 获取监控功能状态
     * @return 监控是否开启
     */
    public boolean isMonitoringEnabled() {
        return Newboy.INSTANCE.getProperties().onlineStatus_enable;
    }
    
    /**
     * 设置定时任务ID
     * @param cronScheduleID 定时任务ID
     */
    public void setCronScheduleID(String cronScheduleID) {
        this.cronScheduleID = cronScheduleID;
    }
    
    /**
     * 获取定时任务ID
     * @return 定时任务ID
     */
    public String getCronScheduleID() {
        return cronScheduleID;
    }
    
    /**
     * 同步指定群组的所有监控成员状态
     * @param groupId 群ID
     * @return 同步结果信息
     */
    public String syncGroupStatus(long groupId) {
        Map<String, Integer> groupConfig = groupMonitorConfig.get(groupId);
        if (groupConfig == null || groupConfig.isEmpty()) {
            return "❌ 当前群组没有监控任何成员";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("🔄 正在同步状态...\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        int successCount = 0;
        int changeCount = 0;
        
        for (Map.Entry<String, Integer> entry : groupConfig.entrySet()) {
            String memberName = entry.getKey();
            int oldStatus = entry.getValue();
            
            try {
                Xox48Handler.OnlineStatusResult realTimeResult = 
                    Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
                
                if (realTimeResult.isSuccess()) {
                    int newStatus = realTimeResult.getIsOnline();
                    String oldStatusText = oldStatus == 1 ? "在线" : "离线";
                    String newStatusText = newStatus == 1 ? "在线" : "离线";
                    
                    if (oldStatus != newStatus) {
                        // 状态发生变化，更新缓存
                        groupConfig.put(memberName, newStatus);
                        
                        // 更新历史记录
                        StatusHistory history = statusHistory.get(memberName);
                        if (history != null) {
                            history.recordChange(newStatus);
                        }
                        
                        result.append(String.format("✅ %s: %s → %s\n", 
                            memberName, oldStatusText, newStatusText));
                        changeCount++;
                    } else {
                        result.append(String.format("➖ %s: %s (无变化)\n", 
                            memberName, newStatusText));
                    }
                    successCount++;
                } else {
                    result.append(String.format("❌ %s: 查询失败 - %s\n", 
                        memberName, realTimeResult.getMessage()));
                }
            } catch (Exception e) {
                result.append(String.format("❌ %s: 查询异常 - %s\n", 
                    memberName, e.getMessage()));
            }
        }
        
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        result.append(String.format("📊 同步完成: 成功 %d/%d, 状态变化 %d 个", 
            successCount, groupConfig.size(), changeCount));
        
        return result.toString();
    }
    
    /**
     * 获取监控配置统计信息（增强版）
     * @return 统计信息字符串
     */
    public String getMonitorStats() {
        StringBuilder result = new StringBuilder();
        result.append("📈 监控统计信息\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        result.append(String.format("🔧 监控状态: %s\n", 
            isMonitoringEnabled() ? "🟢 已启用" : "🔴 已禁用"));
        result.append(String.format("⏰ 监控间隔: %s\n", 
            Newboy.INSTANCE.getConfig().getOnlineStatusInterval()));
        result.append(String.format("🏢 监控群组: %d 个\n", groupMonitorConfig.size()));
        
        int totalMembers = 0;
        int onlineMembers = 0;
        for (Map<String, Integer> groupConfig : groupMonitorConfig.values()) {
            totalMembers += groupConfig.size();
            for (int status : groupConfig.values()) {
                if (status == 1) onlineMembers++;
            }
        }
        
        result.append(String.format("👥 监控成员: %d 个\n", totalMembers));
        result.append(String.format("🟢 在线成员: %d 个\n", onlineMembers));
        result.append(String.format("🔴 离线成员: %d 个\n", totalMembers - onlineMembers));
        result.append(String.format("🔍 总检查次数: %d\n", totalChecks.get()));
        result.append(String.format("❌ 总失败次数: %d\n", totalFailures.get()));
        result.append(String.format("📢 总通知次数: %d\n", totalNotifications.get()));
        
        double overallSuccessRate = totalChecks.get() > 0 ? 
            1.0 - (double) totalFailures.get() / totalChecks.get() : 1.0;
        result.append(String.format("✅ 整体成功率: %.1f%%\n", overallSuccessRate * 100));
        result.append(String.format("🏥 系统健康: %s\n", isHealthy ? "🟢 正常" : "🔴 异常"));
        
        if (lastCheckTime > 0) {
            result.append(String.format("⏰ 最后检查: %s\n", 
                LocalDateTime.ofEpochSecond(lastCheckTime / 1000, 0, 
                    java.time.ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now()))
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))));
        }
        
        // 显示各群组详情
        if (!groupMonitorConfig.isEmpty()) {
            result.append("\n📋 群组详情:\n");
            for (Map.Entry<Long, Map<String, Integer>> entry : groupMonitorConfig.entrySet()) {
                long groupId = entry.getKey();
                Map<String, Integer> members = entry.getValue();
                int groupOnline = (int) members.values().stream().filter(status -> status == 1).count();
                result.append(String.format("  群 %d: %d 个成员 (🟢%d 🔴%d)\n", 
                    groupId, members.size(), groupOnline, members.size() - groupOnline));
            }
        }
        
        result.append("━━━━━━━━━━━━━━━━━━━━");
        return result.toString();
    }
    
    /**
     * 重置所有统计数据
     */
    public void resetStats() {
        totalChecks.set(0);
        totalFailures.set(0);
        totalNotifications.set(0);
        memberHealthStats.clear();
        lastCheckTime = 0;
        isHealthy = true;
        
        Newboy.INSTANCE.getLogger().info("监控统计数据已重置");
    }
    
    /**
     * 获取指定成员的详细健康信息
     * @param memberName 成员名称
     * @return 成员健康信息字符串
     */
    public String getMemberHealthInfo(String memberName) {
        MemberHealthStats stats = memberHealthStats.get(memberName);
        if (stats == null) {
            return String.format("成员 %s 暂无健康统计数据", memberName);
        }
        
        StringBuilder info = new StringBuilder();
        info.append(String.format("👤 成员: %s\n", memberName));
        info.append("━━━━━━━━━━━━━━━━━━━━\n");
        info.append(String.format("🔍 总检查次数: %d\n", stats.totalChecks.get()));
        info.append(String.format("❌ 总失败次数: %d\n", stats.totalFailures.get()));
        info.append(String.format("🔥 连续失败: %d 次\n", stats.consecutiveFailures.get()));
        info.append(String.format("✅ 成功率: %.1f%%\n", stats.getSuccessRate() * 100));
        
        if (stats.lastCheckTime > 0) {
            info.append(String.format("⏰ 最后检查: %s\n", 
                LocalDateTime.ofEpochSecond(stats.lastCheckTime / 1000, 0, 
                    java.time.ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now()))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        }
        
        if (stats.shouldSkipCheck()) {
            info.append(String.format("⏸️ 下次检查: %s\n", 
                LocalDateTime.ofEpochSecond(stats.nextCheckTime / 1000, 0, 
                    java.time.ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now()))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
        }
        
        // 显示状态历史
        StatusHistory history = statusHistory.get(memberName);
        if (history != null) {
            info.append(String.format("📊 状态变化: %d 次\n", history.changeCount));
            if (history.lastChangeTime != null) {
                info.append(String.format("📅 最后变化: %s\n", history.lastChangeTime));
            }
            info.append(String.format("🔄 当前状态: %s\n", history.currentStatus == 1 ? "在线" : "离线"));
        }
        
        info.append("━━━━━━━━━━━━━━━━━━━━");
        return info.toString();
    }
    
    /**
     * 清理过期的健康统计数据
     */
    private void cleanupHealthStats() {
        long currentTime = System.currentTimeMillis();
        int cleanedCount = 0;
        
        var iterator = memberHealthStats.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            MemberHealthStats stats = entry.getValue();
            // 使用配置的保留时间
            if ((currentTime - stats.lastCheckTime) > config.getHealthStatsRetention()) {
                iterator.remove();
                cleanedCount++;
            }
        }
        
        if (cleanedCount > 0 && config.isVerboseLogging()) {
            Newboy.INSTANCE.getLogger().info(
                String.format("清理了 %d 个过期的成员健康统计数据", cleanedCount));
        }
    }
    
    /**
     * 获取系统健康状态报告
     * @return 健康状态报告字符串
     */
    public String getHealthReport() {
        StringBuilder report = new StringBuilder();
        report.append("🏥 监控系统健康报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        report.append(String.format("🔧 系统状态: %s\n", isHealthy ? "🟢 健康" : "🔴 异常"));
        report.append(String.format("⏰ 最后检查: %s\n", 
            lastCheckTime > 0 ? LocalDateTime.ofEpochSecond(lastCheckTime / 1000, 0, 
                java.time.ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now()))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "未知"));
        
        report.append(String.format("📊 总检查次数: %d\n", totalChecks.get()));
        report.append(String.format("❌ 总失败次数: %d\n", totalFailures.get()));
        report.append(String.format("📢 总通知次数: %d\n", totalNotifications.get()));
        
        double overallSuccessRate = totalChecks.get() > 0 ? 
            1.0 - (double) totalFailures.get() / totalChecks.get() : 1.0;
        report.append(String.format("✅ 整体成功率: %.1f%%\n", overallSuccessRate * 100));
        
        // 显示问题成员
        List<MemberHealthStats> problemMembers = memberHealthStats.values().stream()
            .filter(stats -> stats.consecutiveFailures.get() > 0)
            .sorted((a, b) -> Integer.compare(b.consecutiveFailures.get(), a.consecutiveFailures.get()))
            .collect(java.util.stream.Collectors.toList());
        
        if (!problemMembers.isEmpty()) {
            report.append("\n⚠️ 问题成员:\n");
            for (MemberHealthStats stats : problemMembers) {
                report.append(String.format("  %s: 连续失败 %d 次 (成功率: %.1f%%)\n",
                    stats.memberName, stats.consecutiveFailures.get(), stats.getSuccessRate() * 100));
            }
        }
        
        report.append("━━━━━━━━━━━━━━━━━━━━");
        return report.toString();
    }
    
    /**
     * 成员健康统计类
     */
    private class MemberHealthStats {
        private final String memberName;
        private final AtomicInteger totalChecks = new AtomicInteger(0);
        private final AtomicInteger totalFailures = new AtomicInteger(0);
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private volatile long lastCheckTime = System.currentTimeMillis();
        private volatile long nextCheckTime = 0;
        
        public MemberHealthStats(String memberName) {
            this.memberName = memberName;
        }
        
        public void recordSuccess() {
            totalChecks.incrementAndGet();
            consecutiveFailures.set(0);
            lastCheckTime = System.currentTimeMillis();
            nextCheckTime = 0; // 重置下次检查时间
        }
        
        public void recordFailure() {
            totalChecks.incrementAndGet();
            totalFailures.incrementAndGet();
            int failures = consecutiveFailures.incrementAndGet();
            lastCheckTime = System.currentTimeMillis();
            
            // 根据连续失败次数设置延迟检查时间
            if (failures >= config.getMaxConsecutiveFailures()) {
                // 延迟检查：失败次数越多，延迟越长
                long delayMinutes = Math.min(
                    failures * config.getFailureCooldownBase(), 
                    config.getFailureCooldownMax());
                nextCheckTime = System.currentTimeMillis() + delayMinutes * 60 * 1000;
                
                if (config.isVerboseLogging()) {
                    Newboy.INSTANCE.getLogger().warning(
                        String.format("成员 %s 连续失败 %d 次，延迟 %d 分钟后重试", 
                            memberName, failures, delayMinutes));
                }
            }
        }
        
        public boolean shouldSkipCheck() {
            return consecutiveFailures.get() >= config.getMaxConsecutiveFailures() && 
                   System.currentTimeMillis() < nextCheckTime;
        }
        
        public double getSuccessRate() {
            int total = totalChecks.get();
            if (total == 0) return 1.0;
            return 1.0 - (double) totalFailures.get() / total;
        }
        
        /**
         * 获取下次检查时间的可读格式
         */
        public String getNextCheckTimeFormatted() {
            if (nextCheckTime <= System.currentTimeMillis()) {
                return "立即";
            }
            return LocalDateTime.ofEpochSecond(nextCheckTime / 1000, 0, 
                java.time.ZoneOffset.systemDefault().getRules().getOffset(java.time.Instant.now()))
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        }
    }
    
    /**
     * 状态历史记录类
     */
    private static class StatusHistory {
        private final String memberName;
        private int currentStatus;
        private int changeCount;
        private String lastChangeTime;
        
        public StatusHistory(String memberName, int initialStatus) {
            this.memberName = memberName;
            this.currentStatus = initialStatus;
            this.changeCount = 0;
            this.lastChangeTime = null;
        }
        
        public void recordChange(int newStatus) {
            if (newStatus != currentStatus) {
                this.currentStatus = newStatus;
                this.changeCount++;
                this.lastChangeTime = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            }
        }
        
        public int getChangeCount() {
            return changeCount;
        }
        
        public String getLastChangeTime() {
            return lastChangeTime;
        }
    }
}