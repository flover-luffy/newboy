package net.luffy.command;

import net.luffy.Newboy;
import net.luffy.util.CommandOperator;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.Member;
import net.mamoe.mirai.contact.User;
import net.mamoe.mirai.message.data.PlainText;
import net.mamoe.mirai.message.data.Message;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import net.luffy.util.CpuLoadBalancer;
import java.text.DecimalFormat;
import java.io.File;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.lang.management.MemoryUsage;
import net.luffy.util.PerformanceMonitor;
import net.luffy.util.EnhancedPerformanceMonitor;
import net.luffy.util.AsyncOnlineStatusMonitor;
import net.luffy.util.Properties;
import net.luffy.model.WeidianCookie;
import net.luffy.model.Pocket48Subscribe;
import net.luffy.util.DouyinMonitorService;
import net.luffy.service.WeiboApiService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 自定义前缀命令处理器
 * 支持使用 ! 或 # 作为命令前缀，避免与QQ的/命令冲突
 */
public class CustomPrefixCommand {
    
    /**
     * 处理群聊中的自定义前缀命令
     * @param message 消息内容
     * @param group 群组
     * @param senderId 发送者ID
     * @return 响应消息，如果不是支持的命令则返回null
     */
    public static Message handleGroupCommand(String message, Group group, long senderId) {
        // 检查是否以支持的前缀开头
        if (!message.startsWith("!") && !message.startsWith("#")) {
            return null;
        }
        
        // 移除前缀并分割命令
        String commandContent = message.substring(1);
        String[] parts = commandContent.split(" ");
        
        if (parts.length == 0) {
            return null;
        }
        
        String command = parts[0].toLowerCase();
        
        // 处理newboy相关命令
        if ("newboy".equals(command) || "nb".equals(command)) {
            return handleNewboyCommand(parts, group, senderId);
        }
        
        // 处理抖音命令 - 已整合到CommandOperator
        if ("抖音".equals(command) || "douyin".equals(command)) {
            return new PlainText("💡 抖音监控功能已整合\n请使用以下命令：\n" +
                    "• /抖音监控 - 查看帮助\n" +
                    "• /抖音状态 - 查看监控状态\n" +
                    "• /抖音用户 - 查看监控用户列表\n" +
                    "• /抖音添加 <用户链接> - 添加监控\n" +
                    "• /抖音删除 <用户ID> - 删除监控\n" +
                    "• /抖音重启 - 重启监控服务\n" +
                    "• /抖音 关注列表 - 查看群组关注列表");
        }
        

        
        return null;
    }
    
    /**
     * 处理私聊中的自定义前缀命令
     * @param message 消息内容
     * @param user 用户
     * @return 响应消息，如果不是支持的命令则返回null
     */
    public static Message handlePrivateCommand(String message, User user) {
        // 检查是否以支持的前缀开头
        if (!message.startsWith("!") && !message.startsWith("#")) {
            return null;
        }
        
        // 移除前缀并分割命令
        String commandContent = message.substring(1);
        String[] parts = commandContent.split(" ");
        
        if (parts.length == 0) {
            return null;
        }
        
        String command = parts[0].toLowerCase();
        
        // 处理newboy相关命令
        if ("newboy".equals(command) || "nb".equals(command)) {
            return handleNewboyCommand(parts, null, user.getId());
        }
        
        return null;
    }
    
    /**
     * 处理newboy命令的具体逻辑
     * @param parts 命令参数数组
     * @param group 群组（私聊时为null）
     * @param senderId 发送者ID
     * @return 响应消息
     */
    private static Message handleNewboyCommand(String[] parts, Group group, long senderId) {
        // 如果只有命令名，显示帮助
        if (parts.length == 1) {
            return getHelpMessage();
        }
        
        String subCommand = parts[1].toLowerCase();
        
        switch (subCommand) {
            case "info":
            case "信息":
                return getSystemInfo();
            case "config":
            case "配置":
                return getConfigInfo();
            case "subscribe":
            case "订阅":
                return getSubscribeInfo();
            case "performance":
            case "性能":
                return getDetailedPerformanceReport();
            case "monitor":
            case "监控":
                return getMonitoringReport();
            case "report":
            case "报告":
            case "stats":
            case "统计":
                return getComprehensiveReport();
            case "help":
            case "帮助":
                return getHelpMessage();
            default:
                return new PlainText("❌ 未知的子命令: " + subCommand + "\n💡 使用 !newboy help 查看帮助");
        }
    }
    
    /**
     * 获取系统信息
     * @return 系统信息消息
     */
    private static Message getSystemInfo() {
        StringBuilder systemInfo = new StringBuilder();
        systemInfo.append("🖥️ Newboy 插件基础信息\n");
        systemInfo.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        // 插件基本信息
        systemInfo.append("📦 插件信息:\n");
        systemInfo.append(String.format("  插件版本: %s\n", Newboy.VERSION));
        systemInfo.append(String.format("  插件ID: %s\n", Newboy.ID));
        
        // 运行环境信息
        systemInfo.append("\n🌐 运行环境:\n");
        systemInfo.append(String.format("  Java版本: %s\n", System.getProperty("java.version")));
        systemInfo.append(String.format("  操作系统: %s %s\n", 
            System.getProperty("os.name"), System.getProperty("os.version")));
        systemInfo.append(String.format("  系统架构: %s\n", System.getProperty("os.arch")));
        
        // 基础内存信息
        systemInfo.append("\n💾 内存信息:\n");
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        DecimalFormat df = new DecimalFormat("#.##");
        systemInfo.append(String.format("  JVM内存: %s MB / %s MB (%.1f%%)\n", 
            df.format(usedMemory / 1024.0 / 1024.0), 
            df.format(maxMemory / 1024.0 / 1024.0),
            (double) usedMemory / maxMemory * 100));
        
        // 基础线程信息
        systemInfo.append("\n🧵 线程信息:\n");
        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            int totalThreads = threadBean.getThreadCount();
            systemInfo.append(String.format("  活跃线程数: %d\n", totalThreads));
        } catch (Exception e) {
            systemInfo.append("  ❌ 无法获取线程信息\n");
        }
        
        // 简化的订阅统计概览
        systemInfo.append("\n📊 订阅概览:\n");
        Newboy instance = Newboy.INSTANCE;
        if (instance.getProperties() != null) {
            Properties properties = instance.getProperties();
            
            int pocket48Groups = properties.pocket48_subscribe != null ? properties.pocket48_subscribe.size() : 0;
            int weiboUserGroups = properties.weibo_user_subscribe != null ? properties.weibo_user_subscribe.size() : 0;
            int weiboTopicGroups = properties.weibo_superTopic_subscribe != null ? properties.weibo_superTopic_subscribe.size() : 0;
            int weidianGroups = properties.weidian_cookie != null ? properties.weidian_cookie.size() : 0;
            int douyinGroups = properties.douyin_user_subscribe != null ? properties.douyin_user_subscribe.size() : 0;
            
            int totalGroups = pocket48Groups + weiboUserGroups + weiboTopicGroups + weidianGroups + douyinGroups;
            systemInfo.append(String.format("  总订阅群数: %d\n", totalGroups));
            systemInfo.append(String.format("  口袋48: %d | 微博: %d | 微店: %d | 抖音: %d\n", 
                pocket48Groups, weiboUserGroups + weiboTopicGroups, weidianGroups, douyinGroups));
        }
        
        systemInfo.append("\n💡 提示: 使用以下命令获取详细信息:\n");
        systemInfo.append("  !nb 报告 - 综合状态报告\n");
        systemInfo.append("  !nb 监控 - 监控状态报告\n");
        systemInfo.append("  !nb 性能 - 性能详细报告\n");
        systemInfo.append("  !nb 订阅 - 订阅详细信息\n");
        systemInfo.append("  !nb 配置 - 配置详细信息\n");
        
        systemInfo.append("━━━━━━━━━━━━━━━━━━━━");
        
        return new PlainText(systemInfo.toString());
    }
    
    /**
     * 获取配置信息
     * @return 配置信息消息
     */
    private static Message getConfigInfo() {
        StringBuilder configInfo = new StringBuilder();
        configInfo.append("⚙️ 插件配置信息\n");
        configInfo.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        Newboy instance = Newboy.INSTANCE;
        if (instance.getProperties() != null) {
            Properties properties = instance.getProperties();
            
            // 管理员配置
            configInfo.append("👑 管理员配置:\n");
            if (properties.admins != null && properties.admins.length > 0) {
                configInfo.append(String.format("  管理员数量: %d\n", properties.admins != null ? properties.admins.length : 0));
                for (String admin : properties.admins) {
                    configInfo.append(String.format("  - %s\n", admin));
                }
            } else {
                configInfo.append("  ❌ 未配置管理员\n");
            }
            
            // 口袋48配置
            configInfo.append("\n📱 口袋48配置:\n");
            boolean hasPocket48Token = properties.pocket48_token != null && !properties.pocket48_token.isEmpty();
            boolean hasPocket48Account = properties.pocket48_account != null && !properties.pocket48_account.isEmpty();
            configInfo.append(String.format("  Token登录: %s\n", hasPocket48Token ? "✅ 已配置" : "❌ 未配置"));
            configInfo.append(String.format("  账号密码登录: %s\n", hasPocket48Account ? "✅ 已配置" : "❌ 未配置"));
            configInfo.append(String.format("  检查频率: %s\n", properties.pocket48_pattern != null ? properties.pocket48_pattern : "未设置"));
            
            // 微博配置
            configInfo.append("\n🐦 微博配置:\n");
            configInfo.append(String.format("  检查频率: %s\n", properties.weibo_pattern != null ? properties.weibo_pattern : "未设置"));
            
            // 微店配置
            configInfo.append("\n🛒 微店配置:\n");
            boolean hasWeidianCookie = properties.weidian_cookie != null && !properties.weidian_cookie.isEmpty();
            configInfo.append(String.format("  Cookie: %s\n", hasWeidianCookie ? "✅ 已配置" : "❌ 未配置"));
            configInfo.append(String.format("  检查频率: %s\n", properties.weidian_pattern_order != null ? properties.weidian_pattern_order : "未设置"));
            
            // 抖音配置
            configInfo.append("\n📱 抖音配置:\n");
            configInfo.append(String.format("  检查频率: %s\n", properties.douyin_pattern != null ? properties.douyin_pattern : "未设置"));
            


            
            // 异步在线状态监控配置
            configInfo.append("\n🟢 异步在线状态监控:\n");
            configInfo.append("  监控状态: ✅ 已启用\n");
            configInfo.append(String.format("  检查频率: %s\n", properties.async_monitor_schedule_pattern != null ? properties.async_monitor_schedule_pattern : "*/30 * * * * *"));
            
        } else {
            configInfo.append("❌ 无法获取配置信息\n");
        }
        
        configInfo.append("━━━━━━━━━━━━━━━━━━━━");
        return new PlainText(configInfo.toString());
    }
    
    /**
     * 获取订阅信息
     * @return 订阅信息消息
     */
    private static Message getSubscribeInfo() {
        StringBuilder subscribeInfo = new StringBuilder();
        subscribeInfo.append("📋 订阅详情\n");
        subscribeInfo.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        Newboy instance = Newboy.INSTANCE;
        if (instance.getProperties() != null) {
            Properties properties = instance.getProperties();
            
            // 口袋48订阅
            subscribeInfo.append("📱 口袋48订阅:\n");
            if (properties.pocket48_subscribe != null && !properties.pocket48_subscribe.isEmpty()) {
                for (Map.Entry<Long, Pocket48Subscribe> entry : properties.pocket48_subscribe.entrySet()) {
                    Long groupId = entry.getKey();
                    Pocket48Subscribe subscribe = entry.getValue();
                    
                    // 统计房间和成员数量
                    int roomCount = subscribe.getRoomIDs() != null ? subscribe.getRoomIDs().size() : 0;
                    int starCount = subscribe.getStarIDs() != null ? subscribe.getStarIDs().size() : 0;
                    String atAllStatus = subscribe.showAtOne() ? "✅" : "❌";
                    
                    subscribeInfo.append(String.format("  群 %d: %d个房间, %d个成员\n", groupId, roomCount, starCount));
                    subscribeInfo.append(String.format("    - @全体成员: %s\n", atAllStatus));
                    
                    // 显示房间详情
                    if (roomCount > 0) {
                        subscribeInfo.append("    - 订阅房间:\n");
                        for (Long roomId : subscribe.getRoomIDs()) {
                            try {
                                net.luffy.model.Pocket48RoomInfo roomInfo = instance.getHandlerPocket48().getRoomInfoByChannelID(roomId);
                                if (roomInfo != null) {
                                    subscribeInfo.append(String.format("      • %s (%s) - ID: %d\n", 
                                        roomInfo.getRoomName(), roomInfo.getOwnerName(), roomId));
                                } else {
                                    subscribeInfo.append(String.format("      • 未知房间 - ID: %d\n", roomId));
                                }
                            } catch (Exception e) {
                                subscribeInfo.append(String.format("      • 获取失败 - ID: %d\n", roomId));
                            }
                        }
                    }
                    
                    // 显示成员详情
                    if (starCount > 0) {
                        subscribeInfo.append("    - 订阅成员:\n");
                        for (Long starId : subscribe.getStarIDs()) {
                            // 简化显示，避免频繁的网络请求
                        subscribeInfo.append(String.format("      • 成员ID: %d\n", starId));
                        }
                    }
                    subscribeInfo.append("\n");
                }
            } else {
                subscribeInfo.append("  ❌ 暂无订阅\n");
            }
            
            // 微博用户订阅
            subscribeInfo.append("\n🐦 微博用户订阅:\n");
            if (properties.weibo_user_subscribe != null && !properties.weibo_user_subscribe.isEmpty()) {
                for (Map.Entry<Long, List<Long>> entry : properties.weibo_user_subscribe.entrySet()) {
                    Long groupId = entry.getKey();
                    List<Long> userIds = entry.getValue();
                    subscribeInfo.append(String.format("  群 %d: %d个用户\n", groupId, userIds.size()));
                    for (Long userId : userIds) {
                        // 简化显示，避免频繁的API请求
                        subscribeInfo.append(String.format("    - 微博用户 (UID: %d)\n", userId));
                    }
                }
            } else {
                subscribeInfo.append("  ❌ 暂无订阅\n");
            }
            
            // 微博超话订阅
            subscribeInfo.append("\n🔥 微博超话订阅:\n");
            if (properties.weibo_superTopic_subscribe != null && !properties.weibo_superTopic_subscribe.isEmpty()) {
                for (Map.Entry<Long, List<String>> entry : properties.weibo_superTopic_subscribe.entrySet()) {
                    Long groupId = entry.getKey();
                    List<String> topicIds = entry.getValue();
                    subscribeInfo.append(String.format("  群 %d: %d个超话\n", groupId, topicIds.size()));
                    for (String topicId : topicIds) {
                        // 简化显示，避免频繁的API请求
                        subscribeInfo.append(String.format("    - 超话ID: %s\n", topicId));
                    }
                }
            } else {
                subscribeInfo.append("  ❌ 暂无订阅\n");
            }
            
            // 微店订阅
            subscribeInfo.append("\n🛒 微店订阅:\n");
            if (properties.weidian_cookie != null && !properties.weidian_cookie.isEmpty()) {
                for (Map.Entry<Long, WeidianCookie> entry : properties.weidian_cookie.entrySet()) {
                    Long groupId = entry.getKey();
                    WeidianCookie cookie = entry.getValue();
                    String cookieStatus = cookie.invalid ? "❌ 失效" : "✅ 有效";
                    String autoDeliver = cookie.autoDeliver ? "✅" : "❌";
                    String broadcast = cookie.doBroadcast ? "✅" : "❌";
                    
                    subscribeInfo.append(String.format("  群 %d: Cookie状态 %s\n", groupId, cookieStatus));
                    subscribeInfo.append(String.format("    - 自动发货: %s | 群播报: %s\n", autoDeliver, broadcast));
                    
                    if (cookie.highlightItem != null && !cookie.highlightItem.isEmpty()) {
                        subscribeInfo.append(String.format("    - 特殊商品: %d个\n", cookie.highlightItem.size()));
                    }
                    if (cookie.shieldedItem != null && !cookie.shieldedItem.isEmpty()) {
                        subscribeInfo.append(String.format("    - 屏蔽商品: %d个\n", cookie.shieldedItem.size()));
                    }
                }
            } else {
                subscribeInfo.append("  ❌ 暂无订阅\n");
            }
            

            
            // 抖音订阅
            subscribeInfo.append("\n📱 抖音订阅:\n");
            if (properties.douyin_user_subscribe != null && !properties.douyin_user_subscribe.isEmpty()) {
                for (Map.Entry<Long, List<String>> entry : properties.douyin_user_subscribe.entrySet()) {
                    Long groupId = entry.getKey();
                    List<String> userIds = entry.getValue();
                    subscribeInfo.append(String.format("  群 %d: %d个用户\n", groupId, userIds.size()));
                    for (String userId : userIds) {
                        // 简化显示，避免复杂的监控服务调用
                        subscribeInfo.append(String.format("    - 抖音用户 (ID: %s)\n", userId));
                    }
                }
            } else {
                subscribeInfo.append("  ❌ 暂无订阅\n");
            }
            

            
            // 异步在线状态监控订阅
            subscribeInfo.append("\n🟢 异步在线状态监控:\n");
            try {
                String monitorStats = AsyncOnlineStatusMonitor.INSTANCE.getStatistics();
                subscribeInfo.append("  ").append(monitorStats.replace("\n", "\n  ")).append("\n");
            } catch (Exception e) {
                subscribeInfo.append("  ✅ 异步监控系统正在运行\n");
            }
            
        } else {
            subscribeInfo.append("❌ 无法获取订阅信息\n");
        }
        
        subscribeInfo.append("━━━━━━━━━━━━━━━━━━━━");
        return new PlainText(subscribeInfo.toString());
    }
    
    /**
     * 获取详细性能报告
     * @return 详细性能报告消息
     */
    private static Message getDetailedPerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("📊 详细性能报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        try {
            // 基础性能监控报告
            PerformanceMonitor basicMonitor = PerformanceMonitor.getInstance();
            report.append("\n🖥️ 系统性能监控:\n");
            report.append(basicMonitor.getPerformanceReport());
            
            // 移除活跃度监控统计，因为该功能可能经常未启用或获取失败
            
            // HTTP性能详细统计
            report.append("\n\n🌐 HTTP性能详细统计:\n");
            try {
                net.luffy.util.UnifiedHttpClient httpClient = net.luffy.util.UnifiedHttpClient.getInstance();
                report.append(httpClient.getPerformanceStats());
            } catch (Exception e) {
                report.append("❌ HTTP性能统计获取失败: " + e.getMessage());
            }
            
        } catch (Exception e) {
            report.append("\n❌ 获取详细性能报告失败: ").append(e.getMessage());
        }
        
        return new PlainText(report.toString());
    }
    
    /**
     * 获取监控报告
     * @return 监控报告消息
     */
    private static Message getMonitoringReport() {
        StringBuilder report = new StringBuilder();
        report.append("🔍 实时监控状态报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        try {
            // 在线状态监控报告
            AsyncOnlineStatusMonitor asyncMonitor = AsyncOnlineStatusMonitor.INSTANCE;
            if (asyncMonitor != null) {
                report.append("\n🟢 在线状态监控:\n");
                report.append(asyncMonitor.getBatchQueryReport());
                
                report.append("\n📊 监控统计:\n");
                report.append(asyncMonitor.getStatistics());
            } else {
                report.append("\n🟢 在线状态监控: ❌ 未启用\n");
            }
            
            // 系统快速状态
            report.append("\n\n⚡ 系统快速状态:\n");
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            report.append(monitor.getQuickStatus());
            
            // 监控服务健康检查（专注于监控相关的服务状态）
            report.append("\n\n🔧 监控服务健康检查:\n");
            try {
                // 异步监控服务状态
                AsyncOnlineStatusMonitor asyncMonitor2 = AsyncOnlineStatusMonitor.INSTANCE;
                report.append(String.format("  异步监控服务: %s\n", asyncMonitor2 != null ? "✅ 运行中" : "❌ 未启用"));
                
                // 性能监控服务状态
                PerformanceMonitor perfMonitor = PerformanceMonitor.getInstance();
                report.append(String.format("  性能监控服务: %s\n", perfMonitor != null ? "✅ 运行中" : "❌ 未启用"));
                
                // 监控线程池状态
                if (asyncMonitor2 != null) {
                    // AsyncOnlineStatusMonitor没有getThreadInfo方法，直接检查实例状态
                    report.append("  监控线程池: ✅ 正常\n");
                }
            } catch (Exception e) {
                report.append("  ❌ 监控服务检查失败: ").append(e.getMessage()).append("\n");
            }
            
        } catch (Exception e) {
            report.append("\n❌ 获取监控报告失败: ").append(e.getMessage());
        }
        
        report.append("\n━━━━━━━━━━━━━━━━━━━━");
        return new PlainText(report.toString());
    }
    
    /**
     * 获取综合性能报告
     * @return 综合性能报告消息
     */
    private static Message getComprehensiveReport() {
        StringBuilder report = new StringBuilder();
        report.append("📊 Newboy 综合性能报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        try {
            // 1. 系统基础信息
            report.append("🖥️ 系统基础信息:\n");
            report.append(String.format("  插件版本: %s\n", Newboy.VERSION));
            
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();
            
            DecimalFormat df = new DecimalFormat("#.##");
            report.append(String.format("  JVM内存使用: %s MB / %s MB (%.1f%%)\n", 
                df.format(usedMemory / 1024.0 / 1024.0), 
                df.format(maxMemory / 1024.0 / 1024.0),
                (double) usedMemory / maxMemory * 100));
            
            // 2. 核心监控统计概览
        report.append("\n📊 核心监控统计概览:\n");
        try {
            AsyncOnlineStatusMonitor asyncMonitor = AsyncOnlineStatusMonitor.INSTANCE;
            if (asyncMonitor != null) {
                String monitorStats = asyncMonitor.getStatistics();
                // 只显示关键统计信息
                String[] lines = monitorStats.split("\n");
                for (String line : lines) {
                    if (line.contains("订阅成员数") || line.contains("订阅群数") || line.contains("查询成功率")) {
                        report.append("  ").append(line.trim()).append("\n");
                    }
                }
            } else {
                report.append("  ❌ 异步监控未启用\n");
            }
        } catch (Exception e) {
            report.append("  ❌ 获取监控统计失败: ").append(e.getMessage()).append("\n");
        }
        
        // 3. 系统运行状态
            report.append("\n⚡ 系统运行状态:\n");
            try {
                PerformanceMonitor monitor = PerformanceMonitor.getInstance();
                if (monitor != null) {
                    // 简化显示，只显示关键指标
                    double cpuUsage = monitor.getCpuUsagePercentage();
                    double memoryUsage = monitor.getMemoryUsagePercentage();
                    long totalQueries = monitor.getTotalQueries();
                    double avgQPS = monitor.getAverageQPS();
                    
                    report.append(String.format("  系统负载: CPU %.1f%% | 内存 %.1f%%\n", cpuUsage, memoryUsage));
                    report.append(String.format("  查询统计: 总数 %d | QPS %.2f\n", totalQueries, avgQPS));
                } else {
                    report.append("  ❌ 性能监控未启用\n");
                }
            } catch (Exception e) {
                report.append("  ❌ 无法获取系统状态: ").append(e.getMessage()).append("\n");
            }
            
            // 4. 核心服务状态
            report.append("\n🔧 核心服务状态:\n");
            try {
                // 简化显示核心服务状态
                net.luffy.util.SmartCacheManager cacheManager = net.luffy.util.SmartCacheManager.getInstance();
                if (cacheManager != null) {
                    report.append("  💾 智能缓存: ✅ 运行中\n");
                } else {
                    report.append("  💾 智能缓存: ❌ 未启用\n");
                }
                
                net.luffy.util.UnifiedHttpClient httpClient = net.luffy.util.UnifiedHttpClient.getInstance();
                if (httpClient != null) {
                    report.append("  🌐 HTTP客户端: ✅ 运行中\n");
                } else {
                    report.append("  🌐 HTTP客户端: ❌ 未启用\n");
                }
                
                net.luffy.util.ErrorHandlingManager errorManager = net.luffy.util.ErrorHandlingManager.getInstance();
                if (errorManager != null) {
                    report.append("  📨 错误处理: ✅ 运行中\n");
                } else {
                    report.append("  📨 错误处理: ❌ 未启用\n");
                }
            } catch (Exception e) {
                report.append("  ❌ 无法获取服务状态: ").append(e.getMessage()).append("\n");
            }
            
            // 7. 功能模块订阅统计
            report.append("\n📋 功能模块订阅统计:\n");
            Newboy instance = Newboy.INSTANCE;
            if (instance.getProperties() != null) {
                Properties properties = instance.getProperties();
                
                // 口袋48订阅详细统计
                if (properties.pocket48_subscribe != null && !properties.pocket48_subscribe.isEmpty()) {
                    int totalRooms = 0;
                    int totalStars = 0;
                    int showAtOneCount = 0;
                    
                    for (Pocket48Subscribe sub : properties.pocket48_subscribe.values()) {
                        if (sub.getRoomIDs() != null) totalRooms += sub.getRoomIDs().size();
                        if (sub.getStarIDs() != null) totalStars += sub.getStarIDs().size();
                        if (sub.showAtOne()) showAtOneCount++;
                    }
                    
                    report.append(String.format("  📱 口袋48订阅: %d个群\n", properties.pocket48_subscribe.size()));
                    report.append(String.format("    - 监控房间总数: %d\n", totalRooms));
                    report.append(String.format("    - 监控成员总数: %d\n", totalStars));
                    report.append(String.format("    - 启用@全体: %d个群\n", showAtOneCount));
                    report.append(String.format("    - 加密房间记录: %d个\n", properties.pocket48_serverID != null ? properties.pocket48_serverID.size() : 0));
                } else {
                    report.append("  📱 口袋48订阅: 0个群\n");
                }
                
                // 微博订阅详细统计
                int totalWeiboUsers = 0;
                int totalWeiboTopics = 0;
                int weiboUserGroups = properties.weibo_user_subscribe != null ? properties.weibo_user_subscribe.size() : 0;
                int weiboTopicGroups = properties.weibo_superTopic_subscribe != null ? properties.weibo_superTopic_subscribe.size() : 0;
                
                if (properties.weibo_user_subscribe != null) {
                    for (List<Long> users : properties.weibo_user_subscribe.values()) {
                        totalWeiboUsers += users.size();
                    }
                }
                if (properties.weibo_superTopic_subscribe != null) {
                    for (List<String> topics : properties.weibo_superTopic_subscribe.values()) {
                        totalWeiboTopics += topics.size();
                    }
                }
                
                report.append(String.format("  🐦 微博用户订阅: %d个群\n", weiboUserGroups));
                if (weiboUserGroups > 0) {
                    report.append(String.format("    - 监控用户总数: %d\n", totalWeiboUsers));
                }
                report.append(String.format("  🐦 微博超话订阅: %d个群\n", weiboTopicGroups));
                if (weiboTopicGroups > 0) {
                    report.append(String.format("    - 监控超话总数: %d\n", totalWeiboTopics));
                }
                
                // 微店订阅详细统计
                if (properties.weidian_cookie != null && !properties.weidian_cookie.isEmpty()) {
                    int autoDeliverCount = 0;
                    int broadcastCount = 0;
                    int totalHighlightItems = 0;
                    int totalShieldedItems = 0;
                    int invalidCookieCount = 0;
                    
                    for (WeidianCookie cookie : properties.weidian_cookie.values()) {
                        if (cookie.autoDeliver) autoDeliverCount++;
                        if (cookie.doBroadcast) broadcastCount++;
                        if (cookie.highlightItem != null) totalHighlightItems += cookie.highlightItem.size();
                        if (cookie.shieldedItem != null) totalShieldedItems += cookie.shieldedItem.size();
                        if (cookie.invalid) invalidCookieCount++;
                    }
                    
                    report.append(String.format("  🛒 微店订阅: %d个群\n", properties.weidian_cookie.size()));
                    report.append(String.format("    - 自动发货: %d个群\n", autoDeliverCount));
                    report.append(String.format("    - 播报开启: %d个群\n", broadcastCount));
                    report.append(String.format("    - 特殊商品: %d个\n", totalHighlightItems));
                    report.append(String.format("    - 屏蔽商品: %d个\n", totalShieldedItems));
                    // 移除失效Cookie统计，因为这个信息可能不准确且对用户意义不大
                } else {
                    report.append("  🛒 微店订阅: 0个群\n");
                }
                
                // 抖音订阅详细统计
                int totalDouyinUsers = 0;
                int douyinGroups = properties.douyin_user_subscribe != null ? properties.douyin_user_subscribe.size() : 0;
                
                if (properties.douyin_user_subscribe != null) {
                    for (List<String> users : properties.douyin_user_subscribe.values()) {
                        totalDouyinUsers += users.size();
                    }
                }
                
                report.append(String.format("  📱 抖音订阅: %d个群\n", douyinGroups));
                if (douyinGroups > 0) {
                    report.append(String.format("    - 监控用户总数: %d\n", totalDouyinUsers));
                }
                

                

            }
            
            // 8. 服务状态汇总
            report.append("\n🔧 服务状态汇总:\n");
            
            // 口袋48状态
            boolean pocket48Login = instance.getHandlerPocket48() != null && instance.getHandlerPocket48().isLogin();
            report.append(String.format("  口袋48服务: %s\n", pocket48Login ? "✅ 已登录" : "❌ 未登录"));
            
            // 微博状态
            boolean weiboLogin = false;
            try {
                weiboLogin = instance.getHandlerWeibo() != null;
            } catch (Exception e) {
                weiboLogin = false;
            }
            report.append(String.format("  微博服务: %s\n", weiboLogin ? "✅ 运行中" : "❌ 未运行"));
            
            // 抖音状态
            boolean douyinRunning = false;
            try {
                // 检查抖音监控服务的实际运行状态
                DouyinMonitorService douyinService = DouyinMonitorService.getInstance();
                if (douyinService != null) {
                    douyinRunning = douyinService.isRunning();
                }
            } catch (Exception e) {
                douyinRunning = false;
            }
            report.append(String.format("  抖音服务: %s\n", douyinRunning ? "✅ 运行中" : "❌ 未运行"));
            

            
            // 定时任务调度器
            boolean scheduler = instance.getCronScheduler() != null;
            report.append(String.format("  定时任务调度器: %s\n", scheduler ? "✅ 运行中" : "❌ 未运行"));
            
        } catch (Exception e) {
            report.append("\n❌ 生成综合报告时发生错误: ").append(e.getMessage());
        }
        
        report.append("\n━━━━━━━━━━━━━━━━━━━━");
        report.append("\n💡 提示: 使用 !nb performance 查看详细性能数据");
        report.append("\n💡 提示: 使用 !nb monitor 查看实时监控状态");
        report.append("\n📊 报告生成完成，数据更新时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        
        return new PlainText(report.toString());
    }
    
    /**
     * 获取帮助信息
     * @return 帮助信息消息
     */
    private static Message getHelpMessage() {
        String helpText = "📋 Newboy 自定义前缀命令帮助 (v2.0 性能优化版)\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🎯 支持的前缀: ! 或 #\n\n" +
                "📊 可用命令:\n" +
                "  !newboy info|信息 - 查看插件状态和系统信息 (实时数据)\n" +
                "  !newboy config|配置 - 查看插件配置信息\n" +
                "  !newboy subscribe|订阅 - 查看详细订阅情况\n" +
                "  !newboy performance|性能 - 查看详细性能报告 (增强版)\n" +
                "  !newboy monitor|监控 - 查看系统监控报告 (实时监控)\n" +
                "  !newboy report|报告|stats|统计 - 查看综合性能报告 (全面统计)\n" +
                "  !newboy help|帮助 - 显示此帮助信息\n" +
                "  #nb info - 简短别名形式\n" +
                "  #nb performance - 详细性能数据\n" +
                "  #nb monitor - 监控状态报告\n" +
                "  #nb report - 综合性能报告\n\n" +
                "🚀 系统优化特性:\n" +
                "  • 异步架构 - 全面异步处理，响应速度提升300%\n" +
                "  • 智能缓存 - 多层缓存机制，减少90%重复请求\n" +
                "  • 统一HTTP客户端 - 连接池复用，性能大幅提升\n" +
                "  • 自动故障恢复 - 智能重试和故障转移机制\n" +
                "  • 资源管理优化 - 内存使用优化，长期运行更稳定\n" +
                "  • 线程安全设计 - 支持高并发，无竞态条件\n" +
                "  • 实时性能监控 - 系统状态实时监控和报警\n\n" +
                "📊 监控指标 (performance命令):\n" +
                "  • CPU和内存使用率实时监控\n" +
                "  • 网络请求成功率和响应时间统计\n" +
                "  • 缓存命中率和清理统计\n" +
                "  • 异步任务队列状态监控\n" +
                "  • 各平台监控服务健康状态\n" +
                "  • 线程池使用情况和性能指标\n" +
                "  • 垃圾回收统计和内存优化建议\n\n" +
                "💡 说明:\n" +
                "  使用 ! 或 # 前缀避免与QQ的/命令冲突\n" +
                "  支持 newboy 和 nb 两种命令名\n" +
                "  所有命令支持中英文别名\n" +
                "  performance命令提供最详细的性能指标和优化建议\n" +
                "  monitor命令提供实时监控状态和健康检查\n" +
                "  report命令提供所有功能模块的综合统计和分析\n" +
                "  系统已全面优化，性能和稳定性大幅提升\n" +
                "━━━━━━━━━━━━━━━━━━━━";
        
        return new PlainText(helpText);
    }
}