package net.luffy.command;

import net.luffy.Newboy;
import net.luffy.util.DouyinMonitorService;

import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.event.events.GroupMessageEvent;
import net.mamoe.mirai.event.events.UserMessageEvent;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.PlainText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/**
 * 抖音命令处理器
 * 专门处理抖音相关的所有命令逻辑
 * 从CommandOperator中分离出来，提供更清晰的职责分工
 */
public class DouyinCommandHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(DouyinCommandHandler.class);
    private static final DouyinCommandHandler INSTANCE = new DouyinCommandHandler();
    // 延迟服务已移除
    
    private DouyinCommandHandler() {
        // 延迟服务已移除，使用直接延迟
    }
    
    public static DouyinCommandHandler getInstance() {
        return INSTANCE;
    }
    
    /**
     * 处理群聊抖音监控命令
     */
    public Message handlePublicDouyinCommand(String[] args, Group group, long senderID) {
        // 将原有的监控命令逻辑委托给相应的方法
        String command = args[0];
        switch (command) {
            case "/抖音监控":
            case "/douyin_monitor":
                return handleDouyinMonitorCommand(args, group, senderID);
            case "/抖音状态":
            case "/douyin_status":
                return getDouyinMonitoringStatus();
            case "/抖音用户":
            case "/douyin_users":
                return getDouyinMonitoredUsersList();
            case "/抖音添加":
            case "/douyin_add":
                return handleDouyinAddCommand(args, group, senderID);
            case "/抖音删除":
            case "/douyin_remove":
                return handleDouyinRemoveCommand(args, group, senderID);
            case "/抖音重启":
            case "/douyin_restart":
                return handleDouyinRestartCommand(group, senderID);
            default:
                return new PlainText("❌ 未知的抖音命令");
        }
    }

    /**
     * 处理群聊抖音监控命令（原有逻辑）
     */
    private Message handleDouyinMonitorCommand(String[] args, Group group, long senderID) {
        if (args.length < 2) {
            return new PlainText("🎵 抖音监控功能\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    "📋 可用命令:\n" +
                    "• /抖音监控 启动 - 启动监控服务\n" +
                    "• /抖音监控 停止 - 停止监控服务\n" +
                    "• /抖音状态 - 查看监控状态\n" +
                    "• /抖音用户 - 查看监控用户列表\n" +
                    "• /抖音添加 <用户链接> - 添加监控用户\n" +
                    "• /抖音删除 <用户ID> - 删除监控用户\n" +
                    "• /抖音重启 - 重启监控服务\n\n" +
                    "💡 提示: 使用 /抖音 命令管理群组关注列表");
        }

        switch (args[1]) {
            case "启动":
            case "start":
                return startDouyinMonitoring(group.getId(), senderID);
            case "停止":
            case "stop":
                return stopDouyinMonitoring(group.getId(), senderID);
            default:
                return new PlainText("❌ 未知操作\n💡 可用操作: 启动、停止");
        }
    }

    // 启动抖音监控服务
    private Message startDouyinMonitoring(long group, long senderID) {
        if (!Newboy.INSTANCE.getConfig().isAdmin(senderID)) {
            return new PlainText("权限不足喵");
        }

        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            if (monitorService.isRunning()) {
                return new PlainText("✅ 抖音监控服务已在运行中");
            }

            monitorService.startMonitoring(10); // 默认10分钟检查间隔
            return new PlainText("✅ 抖音监控服务已启动");
        } catch (Exception e) {
            // 静默处理错误，不向群组推送错误消息
            Newboy.INSTANCE.getLogger().error("启动抖音监控服务失败: " + e.getMessage());
            return null;
        }
    }

    // 停止抖音监控服务
    private Message stopDouyinMonitoring(long group, long senderID) {
        if (!Newboy.INSTANCE.getConfig().isAdmin(senderID)) {
            return new PlainText("权限不足喵");
        }

        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            if (!monitorService.isRunning()) {
                return new PlainText("⚠️ 抖音监控服务未运行");
            }

            monitorService.stopMonitoring();
            return new PlainText("✅ 抖音监控服务已停止");
        } catch (Exception e) {
            return new PlainText("❌ 停止抖音监控服务失败: " + e.getMessage());
        }
    }

    // 获取抖音监控服务状态
    private Message getDouyinMonitoringStatus() {
        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            String statusText = monitorService.getStatus();
            
            // 安全检查：确保状态文本不为空且长度合理
            if (statusText == null || statusText.trim().isEmpty()) {
                return new PlainText("📱 抖音监控状态\n运行状态: ❌ 状态获取失败");
            }
            
            // 额外的长度检查
            if (statusText.length() > 1000) {
                statusText = statusText.substring(0, 997) + "...";
            }
            
            // 移除潜在的问题字符
            statusText = statusText.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
            
            return new PlainText(statusText);
        } catch (Exception e) {
            // 静默处理错误，返回简化的错误信息
            Newboy.INSTANCE.getLogger().error("获取抖音监控服务状态失败: " + e.getMessage());
            return new PlainText("📱 抖音监控状态\n运行状态: ❌ 服务异常");
        }
    }

    // 获取抖音监控用户列表
    private Message getDouyinMonitoredUsersList() {
        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            return new PlainText(monitorService.getMonitoredUsersList());
        } catch (Exception e) {
            // 静默处理错误，不向群组推送错误消息
            Newboy.INSTANCE.getLogger().error("获取抖音监控用户列表失败: " + e.getMessage());
            return null;
        }
    }

    // 添加抖音监控用户
    private Message handleDouyinAddCommand(String[] args, Group group, long senderID) {
        if (!Newboy.INSTANCE.getConfig().isAdmin(senderID)) {
            return new PlainText("权限不足喵");
        }

        if (args.length < 2) {
            return new PlainText("❌ 请提供用户链接或用户ID\n💡 使用方法: /抖音添加 <用户链接或用户ID>");
        }

        String userInput = args[1];
        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            
            // 提取用户ID
            String secUserId;
            if (userInput.contains("douyin.com")) {
                // 从分享链接提取用户ID的逻辑需要实现
                return new PlainText("❌ 暂不支持从分享链接提取用户ID，请直接使用用户ID");
            } else {
                secUserId = userInput;
            }

            boolean success = monitorService.addMonitorUser(secUserId);
            if (success) {
                String nickname = monitorService.getMonitoredUserNickname(secUserId);
                return new PlainText("✅ 成功添加抖音监控用户\n👤 用户: " + (nickname != null ? nickname : "未知用户") + "\n🆔 用户ID: " + secUserId);
            } else {
                return new PlainText("❌ 添加失败，用户可能已在监控列表中");
            }
        } catch (Exception e) {
            return new PlainText("❌ 添加抖音监控用户失败: " + e.getMessage());
        }
    }

    // 删除抖音监控用户
    private Message handleDouyinRemoveCommand(String[] args, Group group, long senderID) {
        if (!Newboy.INSTANCE.getConfig().isAdmin(senderID)) {
            return new PlainText("权限不足喵");
        }

        if (args.length < 2) {
            return new PlainText("❌ 请提供用户ID\n💡 使用方法: /抖音删除 <用户ID>");
        }

        String secUserId = args[1];
        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            boolean success = monitorService.removeMonitorUser(secUserId);
            if (success) {
                return new PlainText("✅ 成功删除抖音监控用户\n🆔 用户ID: " + secUserId);
            } else {
                return new PlainText("❌ 删除失败，用户不在监控列表中");
            }
        } catch (Exception e) {
            return new PlainText("❌ 删除抖音监控用户失败: " + e.getMessage());
        }
    }

    // 重启抖音监控服务
    private Message handleDouyinRestartCommand(Group group, long senderID) {
        if (!Newboy.INSTANCE.getConfig().isAdmin(senderID)) {
            return new PlainText("权限不足喵");
        }

        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            monitorService.stopMonitoring();
            // 延迟已移除，直接执行
            try {
                monitorService.startMonitoring(10); // 默认10分钟检查间隔
            } catch (Exception e) {
                logger.error("启动抖音监控服务失败: {}", e.getMessage());
            }
            return new PlainText("✅ 抖音监控服务重启中...");
        } catch (Exception e) {
            return new PlainText("❌ 重启抖音监控服务失败: " + e.getMessage());
        }
    }

    // 延迟方法已移除
    // private java.util.concurrent.CompletableFuture<Void> delayAsync(long delayMs) {
    //     return CompletableFuture.runAsync(() -> {
    //         try {
    //             Thread.sleep(delayMs);
    //         } catch (InterruptedException e) {
    //             Thread.currentThread().interrupt();
    //         }
    //     });
    // }
    
    /**
     * 处理私聊抖音订阅管理命令
     */
    public Message handlePrivateDouyinCommand(String[] args, UserMessageEvent event) {
        if (args.length < 2) {
            return new PlainText("❌ 参数不足\n💡 使用方法：/抖音 <操作> [参数]\n📋 可用操作：关注、取消关注、关注列表、搜索、查询");
        }

        switch (args[1]) {
            case "关注列表": {
                return getPrivateDouyinSubscribeList(event.getSender().getId());
            }
            case "搜索": {
                if (args.length < 3) {
                    return new PlainText("❌ 请输入搜索关键词\n💡 使用方法：/抖音 搜索 <关键词>");
                }
                return searchDouyinUser(args[2]);
            }
            case "查询": {
                if (args.length < 3) {
                    return new PlainText("❌ 请输入用户ID\n💡 使用方法：/抖音 查询 <用户ID>");
                }
                return queryDouyinUser(args[2]);
            }
            case "关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/抖音 关注 <用户ID> <群号>\n📝 示例：/抖音 关注 123456789 987654321");
                }
                return addPrivateDouyinSubscribe(args[2], args[3], event);
            }
            case "取消关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/抖音 取消关注 <用户ID> <群号>\n📝 示例：/抖音 取消关注 123456789 987654321");
                }
                return removePrivateDouyinSubscribe(args[2], args[3], event);
            }
            default:
                return new PlainText("❌ 未知操作\n💡 使用 /抖音 查看可用命令");
        }
    }
    
    /**
     * 获取群组的抖音监控列表
     */
    private Message getDouyinMonitorList(long groupId) {
        StringBuilder result = new StringBuilder();
        result.append("📱 抖音监控列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        // 检查该群是否有抖音订阅
        if (!Newboy.INSTANCE.getProperties().douyin_user_subscribe.containsKey(groupId)) {
            result.append("📝 本群暂无抖音监控\n");
            result.append("💡 使用 /抖音 添加监控 <用户ID> 来添加监控");
            return new PlainText(result.toString());
        }
        
        var subscribe = Newboy.INSTANCE.getProperties().douyin_user_subscribe.get(groupId);
            if (subscribe == null || subscribe.isEmpty()) {
            result.append("📝 本群暂无抖音监控\n");
            result.append("💡 使用 /抖音 添加监控 <用户ID> 来添加监控");
            return new PlainText(result.toString());
        }
        
        int count = 1;
        for (String userIdStr : subscribe) {
            try {
                long userId = Long.parseLong(userIdStr);
                // 使用DouyinMonitorService获取用户昵称
                DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
                String nickname = monitorService.getMonitoredUserNickname(userIdStr);
                if (nickname != null && !nickname.isEmpty()) {
                    result.append(String.format("%d. %s\n", count, nickname));
                    result.append(String.format("   用户ID: %d\n", userId));
                } else {
                    result.append(String.format("%d. 用户ID: %d (昵称未知)\n", count, userId));
                }
            } catch (Exception e) {
                result.append(String.format("%d. 用户ID: %s (信息获取失败)\n", count, userIdStr));
            }
            
            if (count < subscribe.size()) {
                result.append("\n");
            }
            count++;
        }
        
        result.append("\n💡 使用 /抖音 添加监控 <用户ID> 来添加更多监控");
        
        return new PlainText(result.toString());
    }
    
    /**
     * 获取私聊用户的抖音订阅列表
     */
    private Message getPrivateDouyinSubscribeList(long userId) {
        StringBuilder result = new StringBuilder();
        result.append("📱 您管理的抖音订阅列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        boolean hasAnySubscription = false;
        
        // 遍历所有群组，查找该用户有管理权限的群组
        for (Bot bot : Bot.getInstances()) {
            for (Group group : bot.getGroups()) {
                // 检查用户是否有该群的管理权限
                if (Newboy.INSTANCE.getConfig().isAdmin(group, userId)) {
                    long groupId = group.getId();
                    
                    // 检查该群是否有抖音订阅
                    if (Newboy.INSTANCE.getProperties().douyin_user_subscribe.containsKey(groupId)) {
                        hasAnySubscription = true;
                        result.append(String.format("🏠 群组: %s (%d)\n", group.getName(), groupId));
                        
                        // 获取该群的订阅列表
                         var subscribe = Newboy.INSTANCE.getProperties().douyin_user_subscribe.get(groupId);
                         if (subscribe != null && !subscribe.isEmpty()) {
                             for (String douyinUserId : subscribe) {
                            try {
                                // 使用DouyinMonitorService获取用户昵称
                                DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
                                String nickname = monitorService.getMonitoredUserNickname(douyinUserId);
                                if (nickname != null && !nickname.isEmpty()) {
                                    result.append(String.format("  👤 %s (ID: %s)\n", 
                                        nickname, douyinUserId));
                                } else {
                                    result.append(String.format("  👤 用户ID: %s (昵称未知)\n", douyinUserId));
                                }
                            } catch (Exception e) {
                                result.append(String.format("  👤 用户ID: %s (信息获取失败)\n", douyinUserId));
                            }
                        }
                        } else {
                            result.append("  📝 暂无订阅\n");
                        }
                        result.append("\n");
                    }
                }
            }
        }
        
        if (!hasAnySubscription) {
            result.append("📝 您暂未管理任何抖音订阅\n");
            result.append("💡 使用 /抖音 关注 <用户ID> <群号> 来添加订阅");
        }
        
        return new PlainText(result.toString());
    }
    
    /**
     * 搜索抖音用户
     */
    private Message searchDouyinUser(String keyword) {
        return new PlainText("❌ 抖音搜索功能暂不可用\n💡 请直接使用用户ID进行查询和订阅");
    }
    
    /**
     * 查询抖音用户信息
     */
    private Message queryDouyinUser(String userIdStr) {
        try {
            Long.parseLong(userIdStr); // 验证格式
            
            // 尝试从监控服务获取用户昵称
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            String nickname = monitorService.getMonitoredUserNickname(userIdStr);
            
            StringBuilder result = new StringBuilder();
            result.append("👤 用户信息\n");
            result.append("━━━━━━━━━━━━━━━━━━━━\n");
            result.append(String.format("🆔 用户ID: %s\n", userIdStr));
            
            if (nickname != null && !nickname.isEmpty()) {
                result.append(String.format("📝 昵称: %s\n", nickname));
                result.append("✅ 该用户已在监控列表中\n");
            } else {
                result.append("⚠️ 该用户不在监控列表中，无法获取详细信息\n");
            }
            
            result.append("\n💡 使用 /抖音 关注 <用户ID> <群号> 来添加订阅");
            
            return new PlainText(result.toString());
            
        } catch (NumberFormatException e) {
            return new PlainText("❌ 用户ID格式错误，请输入数字");
        } catch (Exception e) {
            return new PlainText(String.format("❌ 查询失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 添加群组抖音监控
     */
    private Message addDouyinMonitor(String userIdStr, GroupMessageEvent event) {
        try {
            long userId = Long.parseLong(userIdStr);
            long groupId = event.getGroup().getId();
            
            // 检查用户权限
            if (!Newboy.INSTANCE.getConfig().isAdmin(event.getGroup(), event.getSender().getId())) {
                return new PlainText("❌ 您没有管理权限");
            }
            
            // 添加监控
            if (Newboy.INSTANCE.getConfig().addDouyinUserSubscribe(String.valueOf(userId), groupId)) {
                // 尝试获取用户昵称
                DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
                String nickname = monitorService.getMonitoredUserNickname(userIdStr);
                
                if (nickname != null && !nickname.isEmpty()) {
                    return new PlainText(String.format("✅ 成功添加抖音监控\n👤 用户: %s (ID: %d)", 
                        nickname, userId));
                } else {
                    return new PlainText(String.format("✅ 成功添加抖音监控\n👤 用户ID: %d", userId));
                }
            } else {
                return new PlainText("❌ 本群已经监控了此用户");
            }
            
        } catch (NumberFormatException e) {
            return new PlainText("❌ 用户ID格式错误，请输入数字");
        } catch (Exception e) {
            return new PlainText(String.format("❌ 添加监控失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 移除群组抖音监控
     */
    private Message removeDouyinMonitor(String userIdStr, GroupMessageEvent event) {
        try {
            long userId = Long.parseLong(userIdStr);
            long groupId = event.getGroup().getId();
            
            // 检查用户权限
            if (!Newboy.INSTANCE.getConfig().isAdmin(event.getGroup(), event.getSender().getId())) {
                return new PlainText("❌ 您没有管理权限");
            }
            
            // 移除监控
            if (Newboy.INSTANCE.getConfig().rmDouyinUserSubscribe(String.valueOf(userId), groupId)) {
                return new PlainText(String.format("✅ 成功移除抖音监控\n👤 用户ID: %d", userId));
            } else {
                return new PlainText("❌ 本群没有监控此用户");
            }
            
        } catch (NumberFormatException e) {
            return new PlainText("❌ 用户ID格式错误，请输入数字");
        } catch (Exception e) {
            return new PlainText(String.format("❌ 移除监控失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 添加私聊抖音订阅
     */
    private Message addPrivateDouyinSubscribe(String userIdStr, String groupIdStr, UserMessageEvent event) {
        try {
            long userId = Long.parseLong(userIdStr);
            long groupId = Long.parseLong(groupIdStr);
            
            // 检查用户权限
            if (!Newboy.INSTANCE.getConfig().isAdmin(groupId, event.getSender().getId())) {
                return new PlainText(String.format("❌ 您没有群组 %d 的管理权限", groupId));
            }
            
            // 检查群组是否存在
            Group group = null;
            for (Bot bot : Bot.getInstances()) {
                group = bot.getGroup(groupId);
                if (group != null) break;
            }
            
            if (group == null) {
                return new PlainText(String.format("❌ 未找到群组 %d，请检查群号是否正确", groupId));
            }
            
            // 添加订阅
            if (Newboy.INSTANCE.getConfig().addDouyinUserSubscribe(String.valueOf(userId), groupId)) {
                // 尝试获取用户昵称
                DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
                String nickname = monitorService.getMonitoredUserNickname(userIdStr);
                
                if (nickname != null && !nickname.isEmpty()) {
                    return new PlainText(String.format("✅ 成功为群组 \"%s\" 添加抖音订阅\n👤 用户: %s (ID: %d)", 
                        group.getName(), nickname, userId));
                } else {
                    return new PlainText(String.format("✅ 成功为群组 \"%s\" 添加抖音订阅\n👤 用户ID: %d", 
                        group.getName(), userId));
                }
            } else {
                return new PlainText(String.format("❌ 群组 \"%s\" 已经订阅了此用户", group.getName()));
            }
            
        } catch (NumberFormatException e) {
            return new PlainText("❌ 用户ID或群号格式错误，请输入数字");
        } catch (Exception e) {
            return new PlainText(String.format("❌ 添加订阅失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 移除私聊抖音订阅
     */
    private Message removePrivateDouyinSubscribe(String userIdStr, String groupIdStr, UserMessageEvent event) {
        try {
            long userId = Long.parseLong(userIdStr);
            long groupId = Long.parseLong(groupIdStr);
            
            // 检查用户权限
            if (!Newboy.INSTANCE.getConfig().isAdmin(groupId, event.getSender().getId())) {
                return new PlainText(String.format("❌ 您没有群组 %d 的管理权限", groupId));
            }
            
            // 检查群组是否存在
            Group group = null;
            for (Bot bot : Bot.getInstances()) {
                group = bot.getGroup(groupId);
                if (group != null) break;
            }
            
            if (group == null) {
                return new PlainText(String.format("❌ 未找到群组 %d，请检查群号是否正确", groupId));
            }
            
            // 移除订阅
            if (Newboy.INSTANCE.getConfig().rmDouyinUserSubscribe(String.valueOf(userId), groupId)) {
                return new PlainText(String.format("✅ 成功为群组 \"%s\" 移除抖音订阅\n👤 用户ID: %d", 
                    group.getName(), userId));
            } else {
                return new PlainText(String.format("❌ 群组 \"%s\" 没有订阅此用户", group.getName()));
            }
            
        } catch (NumberFormatException e) {
            return new PlainText("❌ 用户ID或群号格式错误，请输入数字");
        } catch (Exception e) {
            return new PlainText(String.format("❌ 移除订阅失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 获取抖音帮助信息
     */
    public String getHelpText() {
        return "🎵 抖音功能帮助 (v2.0 监控优化版)\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🎯 可用命令:\n\n" +
                "📋 /抖音 关注列表\n" +
                "  查看当前关注的抖音用户列表\n" +
                "  ⚡ 实时状态显示，快速响应\n\n" +
                "➕ /抖音 关注 <用户ID> <群号>\n" +
                "  为指定群组关注抖音用户\n" +
                "  示例: /抖音 关注 123456789 987654321\n" +
                "  ⚡ 即时生效，自动开始监控\n\n" +
                "➖ /抖音 取消关注 <用户ID> <群号>\n" +
                "  为指定群组取消关注抖音用户\n" +
                "  示例: /抖音 取消关注 123456789 987654321\n" +
                "  ⚡ 立即停止推送\n\n" +
                "🎵 /抖音监控 - 监控服务管理 (独立服务)\n" +
                "  • /抖音监控 启动 - 启动监控服务\n" +
                "  • /抖音监控 停止 - 停止监控服务\n" +
                "  • /抖音状态 - 查看监控状态 (实时性能指标)\n" +
                "  • /抖音用户 - 查看监控用户列表\n" +
                "  • /抖音添加 <用户链接> - 添加监控用户\n" +
                "  • /抖音删除 <用户ID> - 删除监控用户\n" +
                "  • /抖音重启 - 重启监控服务 (自动故障恢复)\n\n" +
                "🚀 系统优化特性:\n" +
                "  • 独立监控服务 - 与主程序分离，稳定性更高\n" +
                "  • 性能监控 - 实时监控服务状态和性能指标\n" +
                "  • 自动故障恢复 - 检测到异常时自动重启服务\n" +
                "  • 智能重试机制 - 网络异常时自动重试，确保不丢失更新\n" +
                "  • 资源优化 - 内存和CPU使用优化，长期运行更稳定\n" +
                "  • 并发处理 - 支持多用户同时监控，互不影响\n\n" +
                "💡 使用提示:\n" +
                "  • 用户ID可通过抖音用户主页获取\n" +
                "  • 支持通过私聊为指定群组管理关注\n" +
                "  • 需要相应群组的管理员权限\n" +
                "  • 关注后将自动推送该用户的最新视频\n" +
                "  • 监控服务独立运行，重启主程序不影响监控\n" +
                "  • 支持批量管理和状态查询，操作更便捷";
    }
}