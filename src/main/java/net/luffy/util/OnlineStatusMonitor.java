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

/**
 * 在线状态监控器
 * 提供成员在线状态的监控和通知功能
 */
public class OnlineStatusMonitor {
    
    public static OnlineStatusMonitor INSTANCE;
    
    // 存储每个群的监控配置：群ID -> 成员名称 -> 上次状态
    private final Map<Long, Map<String, Integer>> groupMonitorConfig = new ConcurrentHashMap<>();
    
    // 存储监控状态的变化历史
    private final Map<String, StatusHistory> statusHistory = new ConcurrentHashMap<>();
    
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
     * 检查所有监控的成员状态变化
     * 这个方法应该被定时任务调用
     */
    public void checkStatusChanges() {
        for (Map.Entry<Long, Map<String, Integer>> groupEntry : groupMonitorConfig.entrySet()) {
            long groupId = groupEntry.getKey();
            Map<String, Integer> memberConfig = groupEntry.getValue();
            
            for (Map.Entry<String, Integer> memberEntry : memberConfig.entrySet()) {
                String memberName = memberEntry.getKey();
                int lastStatus = memberEntry.getValue();
                
                try {
                    // 使用成员名称查询当前状态
                    Xox48Handler.OnlineStatusResult result = Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
                    
                    if (!result.isSuccess()) {
                        Newboy.INSTANCE.getLogger().warning("监控成员 " + memberName + " 状态查询失败: " + result.getMessage());
                        continue;
                    }
                    
                    int currentStatus = result.getIsOnline();
                    
                    if (currentStatus != lastStatus) {
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
                        
                        // 发送通知到对应群
                        sendNotificationToGroup(groupId, notification);
                        
                        // 更新状态
                        memberConfig.put(memberName, currentStatus);
                        
                        // 更新历史记录
                        StatusHistory history = statusHistory.get(memberName);
                        if (history != null) {
                            history.recordChange(currentStatus);
                        }
                    }
                } catch (Exception e) {
                    Newboy.INSTANCE.getLogger().warning("监控成员 " + memberName + " 状态时发生错误: " + e.getMessage());
                }
            }
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
     * 获取监控配置统计信息
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
        
        // 显示各群组详情
        if (!groupMonitorConfig.isEmpty()) {
            result.append("\n📋 群组详情:\n");
            for (Map.Entry<Long, Map<String, Integer>> entry : groupMonitorConfig.entrySet()) {
                long groupId = entry.getKey();
                Map<String, Integer> members = entry.getValue();
                int groupOnline = (int) members.values().stream().filter(status -> status == 1).count();
                result.append(String.format("  群 %d: %d 个成员 (%d 在线)\n", 
                    groupId, members.size(), groupOnline));
            }
        }
        
        result.append("━━━━━━━━━━━━━━━━━━━━");
        return result.toString();
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
                this.lastChangeTime = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
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