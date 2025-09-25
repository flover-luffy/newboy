package net.luffy.command;

import net.luffy.Newboy;
import net.luffy.handler.WeiboHandler;
import cn.hutool.json.JSONObject;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.event.events.UserMessageEvent;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.PlainText;

import java.util.List;
import java.util.ArrayList;

/**
 * 微博命令处理器
 * 专门处理微博相关的所有命令逻辑
 * 从CommandOperator中分离出来，提供更清晰的职责分工
 */
public class WeiboCommandHandler {
    
    private static final WeiboCommandHandler INSTANCE = new WeiboCommandHandler();
    
    private WeiboCommandHandler() {
    }
    
    public static WeiboCommandHandler getInstance() {
        return INSTANCE;
    }
    
    /**
     * 处理私聊微博订阅管理命令
     */
    public Message handlePrivateWeiboCommand(String[] args, UserMessageEvent event) {
        if (args.length < 2) {
            return new PlainText("❌ 参数不足\n💡 使用方法：/微博 <操作> [参数]\n📋 可用操作：关注、取消关注、关注列表、搜索、查询");
        }

        switch (args[1]) {
            case "关注列表": {
                return getPrivateWeiboSubscribeList(event.getSender().getId());
            }
            case "搜索": {
                if (args.length < 3) {
                    return new PlainText("❌ 请输入搜索关键词\n💡 使用方法：/微博 搜索 <关键词>");
                }
                return searchWeiboForPrivate(args[2]);
            }
            case "查询": {
                if (args.length < 3) {
                    return new PlainText("❌ 请输入用户ID\n💡 使用方法：/微博 查询 <用户ID>");
                }
                return queryWeiboUserForPrivate(args[2]);
            }
            case "关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/微博 关注 <用户ID> <群号>\n📝 示例：/微博 关注 123456789 987654321");
                }
                return addPrivateWeiboSubscribe(args[2], args[3], event);
            }
            case "取消关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/微博 取消关注 <用户ID> <群号>\n📝 示例：/微博 取消关注 123456789 987654321");
                }
                return removePrivateWeiboSubscribe(args[2], args[3], event);
            }
            default:
                return new PlainText("❌ 未知操作\n💡 使用 /微博 查看可用命令");
        }
    }
    
    /**
     * 获取私聊用户的微博订阅列表
     */
    private Message getPrivateWeiboSubscribeList(long userId) {
        StringBuilder result = new StringBuilder();
        result.append("🐦 您管理的微博订阅列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        boolean hasAnySubscription = false;
        
        // 遍历所有群组，查找该用户有管理权限的群组
        for (Bot bot : Bot.getInstances()) {
            for (Group group : bot.getGroups()) {
                // 检查用户是否有该群的管理权限
                if (Newboy.INSTANCE.getConfig().isAdmin(group, userId)) {
                    long groupId = group.getId();
                    
                    // 检查该群是否有微博订阅
                    if (Newboy.INSTANCE.getProperties().weibo_user_subscribe.containsKey(groupId)) {
                        hasAnySubscription = true;
                        result.append(String.format("🏠 群组: %s (%d)\n", group.getName(), groupId));
                        
                        // 获取该群的订阅列表
                        var subscribe = Newboy.INSTANCE.getProperties().weibo_user_subscribe.get(groupId);
                        if (subscribe != null && !subscribe.isEmpty()) {
                            for (long weiboUserId : subscribe) {
                                try {
                                    // 尝试获取用户信息
                                    JSONObject userInfoJson = Newboy.INSTANCE.getHandlerWeibo().getUserInfo(String.valueOf(weiboUserId));
                                    if (userInfoJson != null) {
                                        String screenName = "未知用户";
                                        try {
                                            if (userInfoJson.containsKey("screen_name")) {
                                                screenName = userInfoJson.getStr("screen_name");
                                            } else if (userInfoJson.containsKey("nickname")) {
                                                screenName = userInfoJson.getStr("nickname");
                                            }
                                        } catch (Exception e) {
                                            // 忽略解析错误，使用默认值
                                        }
                                        result.append(String.format("  👤 %s (@%s, ID: %d)\n", 
                                            screenName, screenName, weiboUserId));
                                    } else {
                                        result.append(String.format("  👤 用户ID: %d (信息获取失败)\n", weiboUserId));
                                    }
                                } catch (Exception e) {
                                    result.append(String.format("  👤 用户ID: %d (信息获取失败)\n", weiboUserId));
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
            result.append("📝 您暂未管理任何微博订阅\n");
            result.append("💡 使用 /微博 关注 <用户ID> <群号> 来添加订阅");
        }
        
        return new PlainText(result.toString());
    }
    
    /**
     * 搜索微博用户
     */
    private Message searchWeiboForPrivate(String keyword) {
        try {
            WeiboHandler handler = Newboy.INSTANCE.getHandlerWeibo();
            if (handler == null) {
                return new PlainText("❌ 微博服务未初始化，无法执行搜索");
            }
            
            // 搜索功能暂不可用
            return new PlainText(String.format("🔍 搜索结果\n━━━━━━━━━━━━━━━━━━━━\n❌ 搜索功能暂时不可用，请直接使用用户ID进行操作\n\n💡 使用 /微博 关注 <用户ID> <群号> 来添加订阅"));
            
        } catch (Exception e) {
            return new PlainText(String.format("❌ 搜索失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 查询微博用户信息
     */
    private Message queryWeiboUserForPrivate(String userIdStr) {
        try {
            long userId = Long.parseLong(userIdStr);
            
            WeiboHandler handler = Newboy.INSTANCE.getHandlerWeibo();
            if (handler == null) {
                return new PlainText("❌ 微博服务未初始化，无法执行查询");
            }
            
            // 查询用户信息
            JSONObject userInfoJson = handler.getUserInfo(String.valueOf(userId));
            
            if (userInfoJson == null) {
                return new PlainText(String.format("❌ 未找到用户ID: %s\n💡 请检查用户ID是否正确", userIdStr));
            }
            
            // 从JSON中提取用户信息
            String screenName = "未知用户";
            try {
                if (userInfoJson.containsKey("screen_name")) {
                    screenName = userInfoJson.getStr("screen_name");
                } else if (userInfoJson.containsKey("nickname")) {
                    screenName = userInfoJson.getStr("nickname");
                }
            } catch (Exception e) {
                // 忽略解析错误，使用默认值
            }
            
            StringBuilder result = new StringBuilder();
            result.append("👤 用户信息\n");
            result.append("━━━━━━━━━━━━━━━━━━━━\n");
            result.append(String.format("📝 昵称: %s\n", screenName));
            result.append(String.format("🆔 用户ID: %s\n", userId));
            // 粉丝数信息暂不可用
            // 关注数信息暂不可用
            // 微博数信息暂不可用
            
            // 用户简介信息暂不可用
            if (false) {
                result.append(String.format("💬 简介: %s\n", ""));
            }
            
            // 认证信息暂不可用
            if (false) {
                result.append("✅ 已认证\n");
                if (false) {
                    result.append(String.format("🏆 认证信息: %s\n", ""));
                }
            }
            
            result.append("\n💡 使用 /微博 关注 <用户ID> <群号> 来添加订阅");
            
            return new PlainText(result.toString());
            
        } catch (NumberFormatException e) {
            return new PlainText("❌ 用户ID格式错误，请输入数字");
        } catch (Exception e) {
            return new PlainText(String.format("❌ 查询失败: %s", e.getMessage()));
        }
    }
    
    /**
     * 添加私聊微博订阅
     */
    private Message addPrivateWeiboSubscribe(String userIdStr, String groupIdStr, UserMessageEvent event) {
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
            
            // 验证用户ID
            WeiboHandler handler = Newboy.INSTANCE.getHandlerWeibo();
            if (handler == null) {
                return new PlainText("❌ 微博服务未初始化，无法添加订阅");
            }
            
            JSONObject userInfoJson = handler.getUserInfo(String.valueOf(userId));
            if (userInfoJson == null) {
                return new PlainText(String.format("❌ 用户ID %d 不存在或无法访问", userId));
            }
            
            // 从JSON中提取用户昵称
            String screenName = "未知用户";
            try {
                if (userInfoJson.containsKey("screen_name")) {
                    screenName = userInfoJson.getStr("screen_name");
                } else if (userInfoJson.containsKey("nickname")) {
                    screenName = userInfoJson.getStr("nickname");
                }
            } catch (Exception e) {
                // 忽略解析错误，使用默认值
            }
            
            // 添加订阅
            if (Newboy.INSTANCE.getConfig().addWeiboUserSubscribe(userId, groupId)) {
                return new PlainText(String.format("✅ 成功为群组 \"%s\" 添加微博订阅\n👤 用户: %s (ID: %d)", 
                    group.getName(), screenName, userId));
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
     * 移除私聊微博订阅
     */
    private Message removePrivateWeiboSubscribe(String userIdStr, String groupIdStr, UserMessageEvent event) {
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
            if (Newboy.INSTANCE.getConfig().rmWeiboUserSubscribe(userId, groupId)) {
                return new PlainText(String.format("✅ 成功为群组 \"%s\" 移除微博订阅\n👤 用户ID: %d", 
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
     * 获取微博帮助信息
     */
    public String getHelpText() {
        return "📱 微博功能帮助 (v2.0 稳定增强版)\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🎯 可用命令:\n\n" +
                "📋 /微博 关注列表\n" +
                "  查看当前关注的微博用户列表\n" +
                "  ⚡ 实时状态显示，快速响应\n\n" +
                "➕ /微博 关注 <用户UID> <群号>\n" +
                "  为指定群组关注微博用户\n" +
                "  示例: /微博 关注 1234567890 987654321\n" +
                "  ⚡ 即时生效，自动开始监控\n\n" +
                "➖ /微博 取消关注 <用户UID> <群号>\n" +
                "  为指定群组取消关注微博用户\n" +
                "  示例: /微博 取消关注 1234567890 987654321\n" +
                "  ⚡ 立即停止推送\n\n" +
                "🎭 /超话 - 微博超话功能\n" +
                "  • /超话 关注列表 - 查看关注的超话\n" +
                "  • /超话 关注 <超话ID> <群号> - 为群组关注超话\n" +
                "  • /超话 取消关注 <超话ID> <群号> - 为群组取消关注\n" +
                "  ⚡ 超话内容实时推送\n\n" +
                "🚀 系统优化特性:\n" +
                "  • 统一HTTP客户端 - 连接池复用，性能大幅提升\n" +
                "  • 错误处理优化 - 网络异常自动重试，确保稳定性\n" +
                "  • 智能频率控制 - 避免API限制，长期稳定运行\n" +
                "  • 内容去重机制 - 避免重复推送，提升用户体验\n" +
                "  • 异步处理 - 多用户监控互不影响，响应更快\n" +
                "  • 数据缓存优化 - 减少重复请求，提高效率\n\n" +
                "💡 使用提示:\n" +
                "  • 用户UID可通过微博用户主页获取\n" +
                "  • 超话ID可通过超话页面URL获取\n" +
                "  • 支持通过私聊为指定群组管理关注\n" +
                "  • 需要相应群组的管理员权限\n" +
                "  • 关注后将自动推送该用户的最新微博\n" +
                "  • 系统已优化网络处理，推送更及时更稳定\n" +
                "  • 支持图片、视频等多媒体内容推送";
    }
}