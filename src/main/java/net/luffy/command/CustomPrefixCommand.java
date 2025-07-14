package net.luffy.command;

import net.luffy.Newboy;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.Member;
import net.mamoe.mirai.contact.User;
import net.mamoe.mirai.message.data.PlainText;
import net.mamoe.mirai.message.data.Message;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.text.DecimalFormat;
import java.io.File;
import java.util.Map;
import java.util.List;
import java.lang.management.MemoryUsage;
import net.luffy.util.Properties;
import net.luffy.model.WeidianCookie;
import net.luffy.model.Pocket48Subscribe;

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
        systemInfo.append("🖥️ Newboy 插件状态报告\n");
        systemInfo.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        // 插件基本信息
        systemInfo.append("📦 插件信息:\n");
        systemInfo.append(String.format("  插件版本: %s\n", Newboy.VERSION));
        systemInfo.append(String.format("  插件ID: %s\n", Newboy.ID));
        systemInfo.append(String.format("  Mirai Console: %s\n", net.mamoe.mirai.console.MiraiConsole.INSTANCE.getVersion()));
        
        // 服务状态
        systemInfo.append("\n🔧 服务状态:\n");
        Newboy instance = Newboy.INSTANCE;
        
        // 口袋48状态
        boolean pocket48Login = instance.getHandlerPocket48() != null && instance.getHandlerPocket48().isLogin();
        systemInfo.append(String.format("  口袋48服务: %s\n", pocket48Login ? "✅ 已登录" : "❌ 未登录"));
        
        // 微博状态
        boolean weiboLogin = false;
        try {
            weiboLogin = instance.getHandlerWeibo() != null;
        } catch (Exception e) {
            weiboLogin = false;
        }
        systemInfo.append(String.format("  微博服务: %s\n", weiboLogin ? "✅ 运行中" : "❌ 未运行"));
        
        // 在线状态监控
        boolean onlineMonitor = instance.getOnlineStatusMonitor() != null;
        systemInfo.append(String.format("  在线状态监控: %s\n", onlineMonitor ? "✅ 运行中" : "❌ 未运行"));
        
        // 定时任务调度器
        boolean scheduler = instance.getCronScheduler() != null;
        systemInfo.append(String.format("  定时任务调度器: %s\n", scheduler ? "✅ 运行中" : "❌ 未运行"));
        
        // 订阅统计
        systemInfo.append("\n📊 订阅统计:\n");
        if (instance.getProperties() != null) {
            Properties properties = instance.getProperties();
            
            // 口袋48订阅
            int pocket48Groups = properties.pocket48_subscribe != null ? properties.pocket48_subscribe.size() : 0;
            systemInfo.append(String.format("  口袋48订阅群数: %d\n", pocket48Groups));
            
            // 微博订阅
            int weiboUserGroups = properties.weibo_user_subscribe != null ? properties.weibo_user_subscribe.size() : 0;
            int weiboTopicGroups = properties.weibo_superTopic_subscribe != null ? properties.weibo_superTopic_subscribe.size() : 0;
            systemInfo.append(String.format("  微博用户订阅群数: %d\n", weiboUserGroups));
            systemInfo.append(String.format("  微博超话订阅群数: %d\n", weiboTopicGroups));
            
            // 微店订阅
            int weidianGroups = properties.weidian_cookie != null ? properties.weidian_cookie.size() : 0;
            systemInfo.append(String.format("  微店订阅群数: %d\n", weidianGroups));
            
            // 在线状态订阅
            int onlineGroups = properties.onlineStatus_subscribe != null ? properties.onlineStatus_subscribe.size() : 0;
            systemInfo.append(String.format("  在线状态订阅群数: %d\n", onlineGroups));
        }
        
        // 系统信息
        systemInfo.append("\n🔧 系统环境:\n");
        
        // 操作系统详细信息
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        systemInfo.append(String.format("  操作系统: %s\n", System.getProperty("os.name")));
        systemInfo.append(String.format("  系统版本: %s\n", System.getProperty("os.version")));
        systemInfo.append(String.format("  系统架构: %s\n", System.getProperty("os.arch")));
        
        // 处理器信息
        systemInfo.append(String.format("  处理器核心: %d 个\n", Runtime.getRuntime().availableProcessors()));
        try {
            // 尝试获取系统负载
            double systemLoad = osBean.getSystemLoadAverage();
            if (systemLoad >= 0) {
                systemInfo.append(String.format("  系统负载: %.2f\n", systemLoad));
            }
        } catch (Exception e) {
            // 忽略异常，某些系统可能不支持
        }
        
        // Java运行时信息
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        systemInfo.append(String.format("  Java版本: %s\n", System.getProperty("java.version")));
        systemInfo.append(String.format("  Java厂商: %s\n", System.getProperty("java.vendor")));
        systemInfo.append(String.format("  JVM名称: %s\n", System.getProperty("java.vm.name")));
        systemInfo.append(String.format("  JVM版本: %s\n", System.getProperty("java.vm.version")));
        
        // 运行时间
        long uptimeMs = runtimeBean.getUptime();
        long uptimeSeconds = uptimeMs / 1000;
        long hours = uptimeSeconds / 3600;
        long minutes = (uptimeSeconds % 3600) / 60;
        long seconds = uptimeSeconds % 60;
        systemInfo.append(String.format("  JVM运行时间: %d小时%d分钟%d秒\n", hours, minutes, seconds));
        
        // 内存信息
        systemInfo.append("\n💾 内存信息:\n");
        
        // JVM内存
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        DecimalFormat df = new DecimalFormat("#.##");
        
        systemInfo.append(String.format("  JVM已用内存: %s MB / %s MB (%.1f%%)\n", 
            df.format(usedMemory / 1024.0 / 1024.0), 
            df.format(totalMemory / 1024.0 / 1024.0),
            (double) usedMemory / totalMemory * 100));
        systemInfo.append(String.format("  JVM最大内存: %s MB\n", df.format(maxMemory / 1024.0 / 1024.0)));
        
        // 堆内存详情
        MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
        systemInfo.append(String.format("  堆内存已用: %s MB / %s MB\n", 
            df.format(heapMemory.getUsed() / 1024.0 / 1024.0),
            df.format(heapMemory.getCommitted() / 1024.0 / 1024.0)));
        
        // 非堆内存详情
        MemoryUsage nonHeapMemory = memoryBean.getNonHeapMemoryUsage();
        systemInfo.append(String.format("  非堆内存已用: %s MB / %s MB\n", 
            df.format(nonHeapMemory.getUsed() / 1024.0 / 1024.0),
            df.format(nonHeapMemory.getCommitted() / 1024.0 / 1024.0)));
        
        // 尝试获取物理内存信息（仅在支持的系统上）
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
                long totalPhysicalMemory = sunOsBean.getTotalPhysicalMemorySize();
                long freePhysicalMemory = sunOsBean.getFreePhysicalMemorySize();
                long usedPhysicalMemory = totalPhysicalMemory - freePhysicalMemory;
                
                systemInfo.append(String.format("  物理内存: %s GB / %s GB (%.1f%%)\n", 
                    df.format(usedPhysicalMemory / 1024.0 / 1024.0 / 1024.0),
                    df.format(totalPhysicalMemory / 1024.0 / 1024.0 / 1024.0),
                    (double) usedPhysicalMemory / totalPhysicalMemory * 100));
            }
        } catch (Exception e) {
            // 忽略异常，某些系统可能不支持
        }
        
        // 磁盘信息
        systemInfo.append("\n💿 磁盘信息:\n");
        try {
            File[] roots = File.listRoots();
            for (File root : roots) {
                long totalSpace = root.getTotalSpace();
                long freeSpace = root.getFreeSpace();
                long usedSpace = totalSpace - freeSpace;
                
                if (totalSpace > 0) {
                    systemInfo.append(String.format("  %s: %s GB / %s GB (%.1f%%)\n", 
                        root.getPath(),
                        df.format(usedSpace / 1024.0 / 1024.0 / 1024.0),
                        df.format(totalSpace / 1024.0 / 1024.0 / 1024.0),
                        (double) usedSpace / totalSpace * 100));
                }
            }
        } catch (Exception e) {
            systemInfo.append("  无法获取磁盘信息\n");
        }
        
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
            
            // 在线状态监控配置
            configInfo.append("\n🟢 在线状态监控:\n");
            configInfo.append(String.format("  监控开关: %s\n", properties.onlineStatus_enable ? "✅ 已启用" : "❌ 已禁用"));
            configInfo.append(String.format("  检查频率: %s\n", properties.onlineStatus_pattern != null ? properties.onlineStatus_pattern : "未设置"));
            
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
                    subscribeInfo.append(String.format("  群 %d: 已配置口袋48订阅\n", groupId));
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
                        subscribeInfo.append(String.format("    - 用户ID: %d\n", userId));
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
                    subscribeInfo.append(String.format("  群 %d: 已配置微店Cookie\n", groupId));
                }
            } else {
                subscribeInfo.append("  ❌ 暂无订阅\n");
            }
            
            // 在线状态订阅
            subscribeInfo.append("\n🟢 在线状态订阅:\n");
            if (properties.onlineStatus_subscribe != null && !properties.onlineStatus_subscribe.isEmpty()) {
                for (Map.Entry<Long, List<String>> entry : properties.onlineStatus_subscribe.entrySet()) {
                    Long groupId = entry.getKey();
                    List<String> memberNames = entry.getValue();
                    subscribeInfo.append(String.format("  群 %d: %d个成员\n", groupId, memberNames.size()));
                    for (String memberName : memberNames) {
                        subscribeInfo.append(String.format("    - 成员: %s\n", memberName));
                    }
                }
            } else {
                subscribeInfo.append("  ❌ 暂无订阅\n");
            }
            
        } else {
            subscribeInfo.append("❌ 无法获取订阅信息\n");
        }
        
        subscribeInfo.append("━━━━━━━━━━━━━━━━━━━━");
        return new PlainText(subscribeInfo.toString());
    }
    
    /**
     * 获取帮助信息
     * @return 帮助信息消息
     */
    private static Message getHelpMessage() {
        String helpText = "📋 Newboy 自定义前缀命令帮助\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🎯 支持的前缀: ! 或 #\n\n" +
                "📊 可用命令:\n" +
                "  !newboy info|信息 - 查看插件状态和系统信息\n" +
                "  !newboy config|配置 - 查看插件配置信息\n" +
                "  !newboy subscribe|订阅 - 查看详细订阅情况\n" +
                "  !newboy help|帮助 - 显示此帮助信息\n" +
                "  #nb info - 简短别名形式\n" +
                "  #nb config - 简短别名形式\n" +
                "  #nb subscribe - 简短别名形式\n\n" +
                "💡 说明:\n" +
                "  使用 ! 或 # 前缀避免与QQ的/命令冲突\n" +
                "  支持 newboy 和 nb 两种命令名\n" +
                "  所有命令支持中英文别名\n" +
                "━━━━━━━━━━━━━━━━━━━━";
        
        return new PlainText(helpText);
    }
}