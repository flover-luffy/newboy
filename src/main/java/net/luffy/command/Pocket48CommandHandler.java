package net.luffy.command;

import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;
import net.luffy.model.Pocket48RoomInfo;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.event.events.UserMessageEvent;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.PlainText;

import java.util.List;
import java.util.ArrayList;

/**
 * 口袋48命令处理器
 * 专门处理口袋48相关的所有命令逻辑
 * 从CommandOperator中分离出来，提供更清晰的职责分工
 */
public class Pocket48CommandHandler {
    
    private static final Pocket48CommandHandler INSTANCE = new Pocket48CommandHandler();
    
    private Pocket48CommandHandler() {
    }
    
    public static Pocket48CommandHandler getInstance() {
        return INSTANCE;
    }
    
    /**
     * 处理私聊口袋48订阅管理命令
     */
    public Message handlePrivatePocket48Command(String[] args, UserMessageEvent event) {
        if (args.length < 2) {
            return new PlainText("❌ 参数不足\n💡 使用方法：/口袋 <操作> [参数]\n📋 可用操作：关注、取消关注、关注列表、搜索、查询");
        }

        switch (args[1]) {
            case "关注列表": {
                return getPrivatePocket48SubscribeList(event.getSender().getId());
            }
            case "搜索": {
                if (args.length < 3) {
                    return new PlainText("❌ 请输入搜索关键词\n💡 使用方法：/口袋 搜索 <关键词>");
                }
                return searchPocket48ForPrivate(args[2]);
            }
            case "查询": {
                if (args.length < 3) {
                    return new PlainText("❌ 请输入用户ID\n💡 使用方法：/口袋 查询 <用户ID>");
                }
                return queryPocket48UserForPrivate(args[2]);
            }
            case "关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/口袋 关注 <房间ID> <群号>\n📝 示例：/口袋 关注 123456 987654321");
                }
                return addPrivatePocket48Subscribe(args[2], args[3], event);
            }
            case "取消关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/口袋 取消关注 <房间ID> <群号>\n📝 示例：/口袋 取消关注 123456 987654321");
                }
                return removePrivatePocket48Subscribe(args[2], args[3], event);
            }
            default:
                return new PlainText("❌ 未知操作\n💡 使用 /口袋 查看可用命令");
        }
    }
    
    /**
     * 获取私聊用户的口袋48订阅列表
     */
    private Message getPrivatePocket48SubscribeList(long userId) {
        StringBuilder result = new StringBuilder();
        result.append("📱 您管理的口袋48订阅列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        boolean hasAnySubscription = false;
        
        // 遍历所有群组，查找该用户有管理权限的群组
        for (Bot bot : Bot.getInstances()) {
            for (Group group : bot.getGroups()) {
                // 检查用户是否有该群的管理权限
                if (Newboy.INSTANCE.getConfig().isAdmin(group, userId)) {
                    long groupId = group.getId();
                    
                    // 检查该群是否有口袋48订阅
                    if (Newboy.INSTANCE.getProperties().pocket48_subscribe.containsKey(groupId)) {
                        hasAnySubscription = true;
                        result.append(String.format("🏠 群组: %s (%d)\n", group.getName(), groupId));
                        
                        // 获取该群的订阅列表
                        var subscribe = Newboy.INSTANCE.getProperties().pocket48_subscribe.get(groupId);
                        if (subscribe != null && !subscribe.getRoomIDs().isEmpty()) {
                            for (long roomId : subscribe.getRoomIDs()) {
                                try {
                                    // 尝试获取房间信息
                                    Pocket48RoomInfo roomInfo = Newboy.INSTANCE.getHandlerPocket48().getRoomInfoByChannelID(roomId);
                                    if (roomInfo != null) {
                                        result.append(String.format("  📺 %s (ID: %d)\n", roomInfo.getRoomName(), roomId));
                                    } else {
                                        result.append(String.format("  📺 房间ID: %d (信息获取失败)\n", roomId));
                                    }
                                } catch (Exception e) {
                                    result.append(String.format("  📺 房间ID: %d (信息获取失败)\n", roomId));
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
            result.append("📝 您暂未管理任何口袋48订阅\n");
            result.append("💡 使用 /口袋 关注 <房间ID> <群号> 来添加订阅");
        }
        
        return new PlainText(result.toString());
    }
    
    /**
     * 搜索口袋48成员或团体
     */
    private Message searchPocket48ForPrivate(String keyword) {
        try {
            Pocket48Handler handler = Newboy.INSTANCE.getHandlerPocket48();
            if (handler == null || !handler.isLogin()) {
                return new PlainText("❌ 口袋48服务未登录，无法执行搜索");
            }
            
            // 执行搜索
            Pocket48RoomInfo[] results = handler.searchRoom(keyword);
            
            if (results == null || results.length == 0) {
                return new PlainText(String.format("🔍 搜索结果\n━━━━━━━━━━━━━━━━━━━━\n❌ 未找到与 \"%s\" 相关的房间或成员", keyword));
            }
            
            StringBuilder result = new StringBuilder();
            result.append(String.format("🔍 搜索结果 (关键词: %s)\n", keyword));
            result.append("━━━━━━━━━━━━━━━━━━━━\n");
            
            int count = 1;
            for (Pocket48RoomInfo room : results) {
                if (count > 10) break; // 限制显示数量
                
                result.append(String.format("%d. %s\n", count, room.getRoomName()));
                result.append(String.format("   房间ID: %d\n", room.getRoomId()));
                result.append(String.format("   服务器ID: %d\n", room.getSeverId()));
                
                if (count < Math.min(results.length, 10)) {
                    result.append("\n");
                }
                count++;
            }
            
            if (results.length > 10) {
                result.append(String.format("\n... 还有 %d 个结果未显示", results.length - 10));
            }
            
            result.append("\n\n💡 使用 /口袋 关注 <房间ID> <群号> 来添加订阅");
            
            return new PlainText(result.toString());
            
        } catch (Exception e) {
            return new PlainText(String.format("❌ 搜索失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 查询口袋48用户信息
     */
    private Message queryPocket48UserForPrivate(String userIdStr) {
        try {
            long userId = Long.parseLong(userIdStr);
            
            Pocket48Handler handler = Newboy.INSTANCE.getHandlerPocket48();
            if (handler == null || !handler.isLogin()) {
                return new PlainText("❌ 口袋48服务未登录，无法执行查询");
            }
            
            // 查询用户信息
            Pocket48RoomInfo roomInfo = handler.getRoomInfoByChannelID(userId);
            
            if (roomInfo == null) {
                return new PlainText(String.format("❌ 未找到用户ID: %s\n💡 请检查用户ID是否正确", userIdStr));
            }
            
            StringBuilder result = new StringBuilder();
            result.append("👤 用户信息\n");
            result.append("━━━━━━━━━━━━━━━━━━━━\n");
            result.append(String.format("📺 房间名称: %s\n", roomInfo.getRoomName()));
            result.append(String.format("🆔 房间ID: %d\n", roomInfo.getRoomId()));
            result.append(String.format("🖥️ 服务器ID: %d\n", roomInfo.getSeverId()));
            result.append("\n💡 使用 /口袋 关注 <房间ID> <群号> 来添加订阅");
            
            return new PlainText(result.toString());
            
        } catch (NumberFormatException e) {
            return new PlainText("❌ 用户ID格式错误，请输入数字");
        } catch (Exception e) {
            return new PlainText(String.format("❌ 查询失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 添加私聊口袋48订阅
     */
    private Message addPrivatePocket48Subscribe(String roomIdStr, String groupIdStr, UserMessageEvent event) {
        try {
            long roomId = Long.parseLong(roomIdStr);
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
            
            // 验证房间ID
            Pocket48Handler handler = Newboy.INSTANCE.getHandlerPocket48();
            if (handler == null || !handler.isLogin()) {
                return new PlainText("❌ 口袋48服务未登录，无法添加订阅");
            }
            
            Pocket48RoomInfo roomInfo = handler.getRoomInfoByChannelID(roomId);
            if (roomInfo == null) {
                return new PlainText(String.format("❌ 房间ID %d 不存在或无法访问", roomId));
            }
            
            // 添加订阅
            if (Newboy.INSTANCE.getConfig().addPocket48RoomSubscribe(roomId, groupId)) {
                return new PlainText(String.format("✅ 成功为群组 \"%s\" 添加口袋48订阅\n📺 房间: %s (ID: %d)", 
                    group.getName(), roomInfo.getRoomName(), roomId));
            } else {
                return new PlainText(String.format("❌ 群组 \"%s\" 已经订阅了此房间", group.getName()));
            }
            
        } catch (NumberFormatException e) {
            return new PlainText("❌ 房间ID或群号格式错误，请输入数字");
        } catch (Exception e) {
            return new PlainText(String.format("❌ 添加订阅失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 移除私聊口袋48订阅
     */
    private Message removePrivatePocket48Subscribe(String roomIdStr, String groupIdStr, UserMessageEvent event) {
        try {
            long roomId = Long.parseLong(roomIdStr);
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
            if (Newboy.INSTANCE.getConfig().rmPocket48RoomSubscribe(roomId, groupId)) {
                return new PlainText(String.format("✅ 成功为群组 \"%s\" 移除口袋48订阅\n📺 房间ID: %d", 
                    group.getName(), roomId));
            } else {
                return new PlainText(String.format("❌ 群组 \"%s\" 没有订阅此房间", group.getName()));
            }
            
        } catch (NumberFormatException e) {
            return new PlainText("❌ 房间ID或群号格式错误，请输入数字");
        } catch (Exception e) {
            return new PlainText(String.format("❌ 移除订阅失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 获取口袋48帮助信息
     */
    public String getHelpText() {
        return "📱 口袋48功能帮助 (v2.0 异步优化版)\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🎯 可用命令:\n\n" +
                "📋 /口袋 关注列表\n" +
                "  查看当前所有订阅的口袋48房间\n" +
                "  ⚡ 快速响应，实时更新\n\n" +
                "🔍 /口袋 搜索 <关键词>\n" +
                "  搜索口袋48成员或团体\n" +
                "  示例: /口袋 搜索 李艺彤\n" +
                "  ⚡ 智能缓存，搜索结果更快\n\n" +
                "👤 /口袋 查询 <用户ID>\n" +
                "  查询指定用户的详细信息\n" +
                "  示例: /口袋 查询 12345\n" +
                "  ⚡ 异步查询，不阻塞其他操作\n\n" +
                "➕ /口袋 关注 <房间ID> <群号>\n" +
                "  为指定群组添加口袋48房间订阅\n" +
                "  示例: /口袋 关注 123456 987654321\n" +
                "  ⚡ 即时生效，自动开始监控\n\n" +
                "➖ /口袋 取消关注 <房间ID> <群号>\n" +
                "  为指定群组取消口袋48房间订阅\n" +
                "  示例: /口袋 取消关注 123456 987654321\n" +
                "  ⚡ 立即停止推送\n\n" +
                "🚀 系统优化特性:\n" +
                "  • 异步消息处理 - 消息获取不再阻塞，响应更快\n" +
                "  • 智能资源缓存 - 图片、表情自动缓存，减少加载时间\n" +
                "  • 自动重试机制 - 网络异常时自动重试，确保消息不丢失\n" +
                "  • 统一HTTP客户端 - 连接池复用，性能大幅提升\n" +
                "  • 线程安全设计 - 支持高并发，稳定可靠\n\n" +
                "💡 使用提示:\n" +
                "  • 房间ID可通过搜索功能获取\n" +
                "  • 支持通过私聊为指定群组管理订阅\n" +
                "  • 需要相应群组的管理员权限\n" +
                "  • 订阅后将自动推送该房间的最新消息\n" +
                "  • 系统已全面优化，消息推送更及时更稳定\n" +
                "  • 支持表情、图片等多媒体内容的快速加载";
    }
}