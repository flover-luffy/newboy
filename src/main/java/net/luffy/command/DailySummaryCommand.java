package net.luffy.command;

import net.luffy.util.UnifiedLogger;
import net.luffy.util.summary.DailySummaryIntegration;
import net.luffy.util.summary.SubscriptionManager;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.event.events.GroupMessageEvent;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.PlainText;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 每日总结命令处理器
 * 提供用户控制每日总结功能的命令接口
 */
public class DailySummaryCommand {
    
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final DailySummaryIntegration summaryIntegration = DailySummaryIntegration.getInstance();
    private final SubscriptionManager subscriptionManager = SubscriptionManager.getInstance();
    
    /**
     * 处理每日总结相关命令
     * 
     * 支持的简洁命令：
     * 英文命令：
     * - /zj status - 查看系统状态
     * - /zj today - 生成今日总结（测试用）
     * - /zj yesterday - 生成昨日总结
     * - /zj generate YYYY-MM-DD - 生成指定日期的总结
     * - /zj room <roomId> [YYYY-MM-DD] - 生成指定房间的总结
     * - /zj rooms - 查看活跃房间列表
     * - /zj test - 生成测试图片
     * - /zj restart - 重启系统
     * - /zj help - 显示帮助信息
     * 
     * 中文简洁命令：
     * - 总结 状态 - 查看系统状态
     * - 总结 今日 - 生成今日总结（测试用）
     * - 总结 昨日 - 生成昨日总结
     * - 总结 生成 YYYY-MM-DD - 生成指定日期的总结
     * - 总结 房间 <roomId> [YYYY-MM-DD] - 生成指定房间的总结
     * - 总结 房间列表 - 查看活跃房间列表
     * - 总结 测试 - 生成测试图片
     * - 总结 重启 - 重启系统
     * - 总结 帮助 - 显示帮助信息
     */
    public boolean handleCommand(GroupMessageEvent event, String command) {
        try {
            String[] parts = command.trim().split("\\s+");
            
            // 检查是否是英文命令格式
            if (parts.length >= 2 && "/zj".equals(parts[0])) {
                return handleEnglishCommand(event, parts);
            }
            
            // 检查是否是中文命令格式
            if (parts.length >= 2 && "总结".equals(parts[0])) {
                return handleChineseCommand(event, parts);
            }
            
            return false;
            
        } catch (Exception e) {
            logger.error("DailySummary", "处理命令时发生错误: " + e.getMessage());
            try {
                event.getGroup().sendMessage("命令处理失败: " + e.getMessage());
            } catch (Exception ignored) {}
            return true;
        }
    }
    
    /**
     * 处理英文命令
     */
    private boolean handleEnglishCommand(GroupMessageEvent event, String[] parts) {
        String subCommand = parts[1].toLowerCase();
        Group group = event.getGroup();
        
        switch (subCommand) {
            case "status":
                handleStatusCommand(group);
                return true;
                
            case "today":
                handleTodayCommand(group);
                return true;
                
            case "yesterday":
                handleYesterdayCommand(group);
                return true;
                
            case "generate":
                if (parts.length >= 3) {
                    handleGenerateCommand(group, parts[2]);
                } else {
                    group.sendMessage("请指定日期，格式：/zj generate YYYY-MM-DD");
                }
                return true;
                
            case "room":
                if (parts.length >= 3) {
                    String roomId = parts[2];
                    String dateStr = parts.length >= 4 ? parts[3] : null;
                    handleRoomCommand(group, roomId, dateStr);
                } else {
                    group.sendMessage("请指定房间ID，格式：/zj room <roomId> [YYYY-MM-DD]");
                }
                return true;
                
            case "rooms":
                handleRoomsCommand(group);
                return true;
                
            case "test":
                handleTestCommand(group);
                return true;
                
            case "restart":
                handleRestartCommand(group);
                return true;
                
            case "help":
                handleHelpCommand(group);
                return true;
                
            case "subscribe":
                handleSubscribeCommand(event, parts);
                return true;
                
            case "unsubscribe":
                handleUnsubscribeCommand(event, parts);
                return true;
                
            case "subscriptions":
                handleSubscriptionListCommand(event, parts);
                return true;
                
            default:
                group.sendMessage("未知命令，使用 /zj help 或 总结 帮助 查看帮助");
                return true;
        }
    }
    
    /**
     * 处理中文命令
     */
    private boolean handleChineseCommand(GroupMessageEvent event, String[] parts) {
        String subCommand = parts[1];
        Group group = event.getGroup();
        
        switch (subCommand) {
            case "状态":
                handleStatusCommand(group);
                return true;
                
            case "今日":
                handleTodayCommand(group);
                return true;
                
            case "昨日":
                handleYesterdayCommand(group);
                return true;
                
            case "生成":
                if (parts.length >= 3) {
                    handleGenerateCommand(group, parts[2]);
                } else {
                    group.sendMessage("请指定日期，格式：总结 生成 YYYY-MM-DD");
                }
                return true;
                
            case "房间":
                if (parts.length >= 3) {
                    String roomId = parts[2];
                    String dateStr = parts.length >= 4 ? parts[3] : null;
                    handleRoomCommand(group, roomId, dateStr);
                } else {
                    group.sendMessage("请指定房间ID，格式：总结 房间 <roomId> [YYYY-MM-DD]");
                }
                return true;
                
            case "房间列表":
                handleRoomsCommand(group);
                return true;
                
            case "测试":
                handleTestCommand(group);
                return true;
                
            case "重启":
                handleRestartCommand(group);
                return true;
                
            case "帮助":
                handleHelpCommand(group);
                return true;
                
            case "订阅":
                handleSubscribeCommand(event, parts);
                return true;
                
            case "取消订阅":
                handleUnsubscribeCommand(event, parts);
                return true;
                
            case "订阅列表":
                handleSubscriptionListCommand(event, parts);
                return true;
                
            default:
                group.sendMessage("未知命令，使用 总结 帮助 或 /zj help 查看帮助");
                return true;
        }
    }
    
    /**
     * 处理状态查询命令
     */
    private void handleStatusCommand(Group group) {
        try {
            String status = summaryIntegration.getSystemStatus();
            String overview = summaryIntegration.getTodayOverview();
            
            MessageChain message = new PlainText("📊 每日总结系统状态\n\n")
                .plus(status)
                .plus("\n\n")
                .plus(overview);
                
            group.sendMessage(message);
            
        } catch (Exception e) {
            group.sendMessage("获取状态信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理今日总结命令（测试用）
     */
    private void handleTodayCommand(Group group) {
        try {
            group.sendMessage("🔄 正在生成今日总结（测试模式）...");
            
            boolean success = summaryIntegration.generateTodaySummary();
            
            if (success) {
                group.sendMessage("✅ 今日总结生成成功！图片已保存到 data/daily_images/ 目录");
            } else {
                group.sendMessage("❌ 今日总结生成失败，请检查日志或稍后重试");
            }
            
        } catch (Exception e) {
            group.sendMessage("生成今日总结时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 处理昨日总结命令
     */
    private void handleYesterdayCommand(Group group) {
        try {
            group.sendMessage("🔄 正在生成昨日总结...");
            
            boolean success = summaryIntegration.generateYesterdaySummary();
            
            if (success) {
                group.sendMessage("✅ 昨日总结生成成功！图片已保存到 data/daily_images/ 目录");
            } else {
                group.sendMessage("❌ 昨日总结生成失败，可能没有足够的数据或系统未初始化");
            }
            
        } catch (Exception e) {
            group.sendMessage("生成昨日总结时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 处理指定日期总结命令
     */
    private void handleGenerateCommand(Group group, String dateStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            
            // 检查日期是否合理（不能是未来日期，不能太久远）
            LocalDate today = LocalDate.now();
            if (date.isAfter(today)) {
                group.sendMessage("❌ 不能生成未来日期的总结");
                return;
            }
            
            if (date.isBefore(today.minusDays(30))) {
                group.sendMessage("❌ 不能生成30天前的总结（数据可能已清理）");
                return;
            }
            
            group.sendMessage("🔄 正在生成 " + dateStr + " 的总结...");
            
            boolean success = summaryIntegration.generateSummaryForDate(date);
            
            if (success) {
                group.sendMessage("✅ " + dateStr + " 总结生成成功！图片已保存到 data/daily_images/ 目录");
            } else {
                group.sendMessage("❌ " + dateStr + " 总结生成失败，可能没有该日期的数据");
            }
            
        } catch (DateTimeParseException e) {
            group.sendMessage("❌ 日期格式错误，请使用 YYYY-MM-DD 格式，例如：2024-01-15");
        } catch (Exception e) {
            group.sendMessage("生成指定日期总结时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 处理测试命令
     */
    private void handleTestCommand(Group group) {
        try {
            group.sendMessage("🧪 正在生成测试图片...");
            
            boolean success = summaryIntegration.generateTestImage();
            
            if (success) {
                group.sendMessage("✅ 测试图片生成成功！图片已保存到 data/daily_images/test_image.png");
            } else {
                group.sendMessage("❌ 测试图片生成失败，请检查系统状态");
            }
            
        } catch (Exception e) {
            group.sendMessage("生成测试图片时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 处理重启命令
     */
    private void handleRestartCommand(Group group) {
        try {
            group.sendMessage("🔄 正在重启每日总结系统...");
            
            boolean success = summaryIntegration.restart();
            
            if (success) {
                group.sendMessage("✅ 每日总结系统重启成功！");
            } else {
                group.sendMessage("❌ 系统重启失败，请检查日志");
            }
            
        } catch (Exception e) {
            group.sendMessage("重启系统时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 处理指定房间总结命令
     */
    private void handleRoomCommand(Group group, String roomId, String dateStr) {
        try {
            LocalDate date;
            if (dateStr != null) {
                try {
                    date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                } catch (DateTimeParseException e) {
                    group.sendMessage("❌ 日期格式错误，请使用 YYYY-MM-DD 格式，例如：2024-01-15");
                    return;
                }
            } else {
                date = LocalDate.now().minusDays(1); // 默认昨天
            }
            
            // 检查日期是否合理
            LocalDate today = LocalDate.now();
            if (date.isAfter(today)) {
                group.sendMessage("❌ 不能生成未来日期的总结");
                return;
            }
            
            if (date.isBefore(today.minusDays(30))) {
                group.sendMessage("❌ 不能生成30天前的总结（数据可能已清理）");
                return;
            }
            
            group.sendMessage("🔄 正在生成房间 " + roomId + " 在 " + date + " 的总结...");
            
            boolean success = summaryIntegration.generateRoomSummaryForDate(roomId, date);
            
            if (success) {
                group.sendMessage("✅ 房间 " + roomId + " 在 " + date + " 的总结生成成功！图片已保存到 data/daily_images/ 目录");
            } else {
                group.sendMessage("❌ 房间 " + roomId + " 在 " + date + " 的总结生成失败，可能没有该房间的数据");
            }
            
        } catch (Exception e) {
            group.sendMessage("生成房间总结时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 处理活跃房间列表命令
     */
    private void handleRoomsCommand(Group group) {
        try {
            List<String> activeRoomIds = summaryIntegration.getActiveRoomIds();
            
            if (activeRoomIds.isEmpty()) {
                group.sendMessage("📭 当前没有活跃的房间数据");
                return;
            }
            
            StringBuilder message = new StringBuilder("📋 活跃房间列表 (最近7天):\n\n");
            for (int i = 0; i < activeRoomIds.size() && i < 20; i++) { // 最多显示20个
                String roomId = activeRoomIds.get(i);
                message.append(String.format("%d. %s\n", i + 1, roomId));
            }
            
            if (activeRoomIds.size() > 20) {
                message.append(String.format("\n... 还有 %d 个房间", activeRoomIds.size() - 20));
            }
            
            message.append("\n💡 使用 /summary room <roomId> 生成指定房间的总结");
            
            group.sendMessage(message.toString());
            
        } catch (Exception e) {
            group.sendMessage("获取活跃房间列表失败: " + e.getMessage());
        }
    }
    /**
     * 处理帮助命令
     */
    private void handleHelpCommand(Group group) {
        try {
            MessageChain helpMessage = new PlainText("📖 每日总结系统命令帮助\n\n")
                .plus("🔍 英文命令格式：\n")
                .plus("  /zj status - 查看系统状态\n")
                .plus("  /zj today - 生成今日总结（测试）\n")
                .plus("  /zj yesterday - 生成昨日总结\n")
                .plus("  /zj generate YYYY-MM-DD - 生成指定日期总结\n")
                .plus("  /zj room <roomId> [YYYY-MM-DD] - 生成指定房间总结\n")
                .plus("  /zj rooms - 查看活跃房间列表\n")
                .plus("  /zj test - 生成测试图片\n")
                .plus("  /zj restart - 重启系统\n")
                .plus("  /zj help - 显示此帮助\n\n")
                .plus("🔍 中文简洁命令格式：\n")
                .plus("  总结 状态 - 查看系统状态\n")
                .plus("  总结 今日 - 生成今日总结（测试）\n")
                .plus("  总结 昨日 - 生成昨日总结\n")
                .plus("  总结 生成 YYYY-MM-DD - 生成指定日期总结\n")
                .plus("  总结 房间 <roomId> [YYYY-MM-DD] - 生成指定房间总结\n")
                .plus("  总结 房间列表 - 查看活跃房间列表\n")
                .plus("  总结 测试 - 生成测试图片\n")
                .plus("  总结 重启 - 重启系统\n")
                .plus("  总结 帮助 - 显示此帮助\n\n")
                .plus("🔧 订阅管理命令：\n")
                .plus("  总结 订阅 <房间ID> - 订阅房间总结到当前群\n")
                .plus("  总结 取消订阅 <房间ID> - 取消订阅房间总结\n")
                .plus("  总结 订阅列表 [房间ID] - 查看订阅列表\n")
                .plus("  /zj subscribe <roomId> - 订阅房间总结到当前群\n")
                .plus("  /zj unsubscribe <roomId> - 取消订阅房间总结\n")
                .plus("  /zj subscriptions [roomId] - 查看订阅列表\n\n")
                .plus("💡 提示：\n")
                .plus("- 系统每天00:05自动为每个房间生成前一天的总结\n")
                .plus("- 房间总结包含该房间的消息统计、热门话题等信息\n")
                .plus("- 使用 总结 房间列表 或 /zj rooms 查看可用的房间ID\n")
                .plus("- 支持中文和英文两种命令格式，使用更便捷\n")
                .plus("- 订阅房间后，系统会自动将该房间的每日总结发送到当前群组");
                
            group.sendMessage(helpMessage);
            
        } catch (Exception e) {
            group.sendMessage("显示帮助信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理订阅命令
     */
    private void handleSubscribeCommand(GroupMessageEvent event, String[] parts) {
        try {
            Group group = event.getGroup();
            
            if (parts.length < 3) {
                group.sendMessage("❌ 请指定房间ID\n用法: 总结 订阅 <房间ID> 或 /zj subscribe <roomId>");
                return;
            }
            
            String roomId = parts[2];
            Long groupId = group.getId();
            
            boolean success = subscriptionManager.addSubscription(roomId, groupId);
            
            if (success) {
                group.sendMessage("✅ 成功订阅房间 " + roomId + " 的每日总结\n" +
                                "系统将在每天00:05自动发送该房间的总结到本群");
            } else {
                group.sendMessage("❌ 订阅失败，可能该房间已被订阅或系统错误");
            }
            
        } catch (Exception e) {
            event.getGroup().sendMessage("处理订阅命令时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 处理取消订阅命令
     */
    private void handleUnsubscribeCommand(GroupMessageEvent event, String[] parts) {
        try {
            Group group = event.getGroup();
            
            if (parts.length < 3) {
                group.sendMessage("❌ 请指定房间ID\n用法: 总结 取消订阅 <房间ID> 或 /zj unsubscribe <roomId>");
                return;
            }
            
            String roomId = parts[2];
            Long groupId = group.getId();
            
            boolean success = subscriptionManager.removeSubscription(roomId, groupId);
            
            if (success) {
                group.sendMessage("✅ 成功取消订阅房间 " + roomId + " 的每日总结");
            } else {
                group.sendMessage("❌ 取消订阅失败，可能该房间未被订阅");
            }
            
        } catch (Exception e) {
            event.getGroup().sendMessage("处理取消订阅命令时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 处理订阅列表命令
     */
    private void handleSubscriptionListCommand(GroupMessageEvent event, String[] parts) {
        try {
            Group group = event.getGroup();
            Long groupId = group.getId();
            
            if (parts.length >= 3) {
                // 查看指定房间的订阅群组
                String roomId = parts[2];
                List<Long> subscribedGroups = subscriptionManager.getSubscribedGroups(roomId);
                
                if (subscribedGroups.isEmpty()) {
                    group.sendMessage("📋 房间 " + roomId + " 没有被任何群组订阅");
                } else {
                    StringBuilder message = new StringBuilder("📋 房间 " + roomId + " 的订阅群组列表：\n");
                    for (Long subGroupId : subscribedGroups) {
                        message.append("- 群组 ").append(subGroupId);
                        if (subGroupId.equals(groupId)) {
                            message.append(" (当前群)");
                        }
                        message.append("\n");
                    }
                    group.sendMessage(message.toString());
                }
            } else {
                // 查看当前群组订阅的房间
                List<String> subscribedRooms = subscriptionManager.getGroupSubscribedRooms(groupId);
                
                if (subscribedRooms.isEmpty()) {
                    group.sendMessage("📋 当前群组没有订阅任何房间的总结\n" +
                                    "使用 '总结 订阅 <房间ID>' 来订阅房间总结");
                } else {
                    StringBuilder message = new StringBuilder("📋 当前群组订阅的房间列表：\n");
                    for (String roomId : subscribedRooms) {
                        message.append("- 房间 ").append(roomId).append("\n");
                    }
                    message.append("\n💡 使用 '总结 取消订阅 <房间ID>' 来取消订阅");
                    group.sendMessage(message.toString());
                }
            }
            
        } catch (Exception e) {
            event.getGroup().sendMessage("处理订阅列表命令时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 检查用户是否有权限执行命令
     * 可以根据需要扩展权限检查逻辑
     */
    private boolean hasPermission(GroupMessageEvent event) {
        // 这里可以添加权限检查逻辑
        // 例如：只允许管理员或特定用户执行命令
        // 目前允许所有用户执行
        return true;
    }
    
    /**
     * 静态方法，用于在主命令处理器中调用
     */
    public static boolean processCommand(GroupMessageEvent event, String command) {
        DailySummaryCommand handler = new DailySummaryCommand();
        return handler.handleCommand(event, command);
    }
}