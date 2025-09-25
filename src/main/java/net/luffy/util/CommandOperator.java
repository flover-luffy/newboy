package net.luffy.util;

// HttpRequest已迁移到异步处理器
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.luffy.handler.AsyncWebHandlerBase;
import net.luffy.handler.Pocket48Handler;
import net.luffy.handler.WeidianHandler;
import net.luffy.handler.WeidianSenderHandler;
import net.luffy.handler.Xox48Handler;
import net.luffy.handler.WeiboHandler;
import net.luffy.command.Pocket48CommandHandler;
import net.luffy.command.WeiboCommandHandler;
import net.luffy.command.DouyinCommandHandler;

import net.luffy.util.AsyncOnlineStatusMonitor;
import net.luffy.util.DouyinMonitorService;
import net.luffy.util.sender.Pocket48ResourceCache;
import net.luffy.util.SmartCacheManager;
import net.luffy.util.JsonOptimizer;
import net.luffy.service.WeiboApiService;
import net.luffy.service.WeiboMonitorService;
import net.luffy.model.Pocket48RoomInfo;
import net.luffy.model.WeidianBuyer;
import net.luffy.model.WeidianCookie;
import net.luffy.model.WeidianItem;
import net.luffy.model.WeidianOrder;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.event.events.UserMessageEvent;
import net.mamoe.mirai.message.data.Image;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.PlainText;
import net.mamoe.mirai.utils.ExternalResource;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CommandOperator extends AsyncWebHandlerBase {

    private static final Logger logger = LoggerFactory.getLogger(CommandOperator.class);
    public static CommandOperator INSTANCE;

    public CommandOperator() {
        INSTANCE = this;
        //需自行编写指令执行方法
    }

    // 抖音监控命令处理


    public Message executePublic(String[] args, Group g, long senderID) {
        long group = g.getId();

        switch (args[0]) {
            case "/version": {
                return new PlainText(Newboy.VERSION);
            }
            case "/在线":
            case "/online": {
                if (args.length < 2 || args[1].trim().isEmpty()) {
                    return new PlainText("❌ 请输入要查询的成员名称\n💡 使用方法：/在线 成员名称");
                }
                String memberName = args[1].trim();
                Xox48Handler.OnlineStatusResult result = Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
                return new PlainText(result.formatResult());
            }
            case "/口袋":
            case "/pocket":
                switch (args.length) {
                    case 2:
                        switch (args[1]) {
                            case "关注列表": {
                                StringBuilder out = new StringBuilder();
                                out.append("📱 口袋48关注列表\n");
                        
                                
                                if (!Newboy.INSTANCE.getProperties().pocket48_subscribe.containsKey(group))
                                    return new PlainText("暂无关注的房间");

                                int count = 1;
                                for (long room_id : Newboy.INSTANCE.getProperties().pocket48_subscribe.get(group).getRoomIDs()) {
                                    try {
                                        Pocket48RoomInfo roomInfo = Newboy.INSTANCE.getHandlerPocket48().getRoomInfoByChannelID(room_id);
                                        if (roomInfo != null) {
                                            String roomName = roomInfo.getRoomName();
                                            String ownerName = roomInfo.getOwnerName();
                                            out.append(count).append(". ").append(roomName).append("\n");
                                            out.append("   主播：").append(ownerName).append("\n");
                                            out.append("   房间ID：").append(room_id).append("\n");
                                        } else {
                                            out.append(count).append(". 未知房间\n");
                                            out.append("   房间ID：").append(room_id).append("\n");
                                        }
                                        
                                        if (count < Newboy.INSTANCE.getProperties().pocket48_subscribe.get(group).getRoomIDs().size()) {
                                            out.append("\n");
                                        }
                                        count++;
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        out.append(count).append(". 获取信息失败\n");
                                        out.append("   房间ID：").append(room_id).append("\n");
                                        count++;
                                    }
                                }
                                return new PlainText(out.toString());
                            }
                            case "余额": { //隐藏命令&之前也没发布过
                                return new PlainText("" + Newboy.INSTANCE.getHandlerPocket48().getBalance());
                            }
                            case "查直播": { //历史命令1
                                List<Object> liveList = Newboy.INSTANCE.getHandlerPocket48().getLiveList();
                                
                                // 检查直播列表是否为空
                                if (liveList == null || liveList.isEmpty()) {
                                    return new PlainText("当前暂无小偶像直播");
                                }
                                
                                String out = "";
                                int count = 1;
                                for (Object liveRoom : liveList) {
                                    JSONObject liveRoom1 = UnifiedJsonParser.getInstance().parseObj(liveRoom.toString());
                                    JSONObject userInfo = liveRoom1.getJSONObject("userInfo");

                                    String title = liveRoom1.getStr("title");
                                    String name = userInfo.getStr("starName");
                                    String userId = userInfo.getStr("userId");
                                    out += count + ". (" + userId + ")" + name + ": " + title + "\n";
                                    count++;
                                }
                                return new PlainText(out);
                            }
                            case "查录播": { //隐藏命令2
                                List<Object> recordList = Newboy.INSTANCE.getHandlerPocket48().getRecordList();
                                
                                // 检查录播列表是否为空
                                if (recordList == null || recordList.isEmpty()) {
                                    return new PlainText("当前暂无录播内容");
                                }
                                
                                String out = "";
                                int count = 1;
                                for (Object liveRoom : recordList) {
                                    JSONObject liveRoom1 = UnifiedJsonParser.getInstance().parseObj(liveRoom.toString());
                                    JSONObject userInfo = liveRoom1.getJSONObject("userInfo");

                                    String title = liveRoom1.getStr("title");
                                    String name = userInfo.getStr("starName");
                                    String userId = userInfo.getStr("userId");
                                    out += count + ". (" + userId + ")" + name + ": " + title + "\n";
                                    count++;
                                }
                                return new PlainText(out);
                            }
                        }
                    case 3:
                        switch (args[1]) {
                            case "搜索": {
                                Object[] servers = Newboy.INSTANCE.getHandlerPocket48().search(args[2]);
                                StringBuilder out = new StringBuilder();
                                out.append("🔍 搜索结果：").append(args[2]).append("\n");
                    

                                if (servers.length == 0) {
                                    // 静默处理搜索无结果的情况，不向群组推送
                                    Newboy.INSTANCE.getLogger().info("搜索无结果: " + args[2]);
                                    return null;
                                }

                                int count = 1;
                                for (Object server_ : servers) {
                                    JSONObject server = UnifiedJsonParser.getInstance().parseObj(server_.toString());
                                    String name = server.getStr("serverDefaultName");
                                    String serverName = server.getStr("serverName");
                                    long starId = server.getLong("serverOwner");
                                    Long serverId = server.getLong("serverId");

                                    out.append("\n📍 ").append(count).append(". ").append(name);
                                    if (!name.equals(serverName)) {
                                        out.append("(").append(serverName).append(")");
                                    }
                                    out.append("\n👤 用户ID: ").append(starId);
                                    out.append("\n🏠 服务器ID: ").append(serverId != null ? serverId : "未知");
                                    
                                    try {
                                        String roomInfo = informationFromPocketServerId(serverId);
                                        // 格式化房间信息
                                        String[] lines = roomInfo.split("\n");
                                        for (String line : lines) {
                                            if (line.startsWith("Server_id:")) {
                                                continue; // 跳过重复的Server_id信息
                                            } else if (line.contains(")") && !line.equals("无房间")) {
                                                // 格式化房间信息
                                                if (line.contains("加密房间")) {
                                                    out.append("\n🔒 ").append(line);
                                                } else if (line.contains("直播")) {
                                                    out.append("\n📺 ").append(line);
                                                } else {
                                                    out.append("\n🏠 ").append(line);
                                                }
                                            } else if (line.equals("无房间")) {
                                                out.append("\n❌ 无可用房间");
                                            }
                                        }
                                    } catch (Exception e) {
                                        out.append("\n❌ 房间信息获取失败");
                                    }
                                    
                                    if (count < servers.length) {
                                
                                    }
                                    count++;
                                }
                                return new PlainText(out.toString());
                            }
                            case "查询": {
                                long star_ID = Long.valueOf(args[2]);
                                JSONObject info = Newboy.INSTANCE.getHandlerPocket48().getUserInfo(star_ID);
                                if (info == null) {
                                    // 静默处理用户不存在的情况，不向群组推送错误消息
                                    Newboy.INSTANCE.getLogger().info("用户不存在: " + star_ID);
                                    return null;
                                }

                                boolean star = info.getBool("isStar");
                                int followers = info.getInt("followers");
                                int friends = info.getInt("friends");
                                String nickName = info.getStr("nickname");
                                String starName = info.getStr("starName");
                                String avatar = Pocket48Handler.SOURCEROOT + info.getStr("avatar");
                                
                                StringBuilder out = new StringBuilder();
                                out.append("👤 用户信息查询\n");
                        
                                out.append(star ? "🌟 【成员】" : "👥 【聚聚】");
                                out.append(nickName);
                                if (starName != null) {
                                    out.append("(").append(starName).append(")");
                                }
                                out.append("\n📊 关注 ").append(friends).append(" | 粉丝 ").append(followers);
                                out.append("\n🆔 用户ID: ").append(star_ID);

                                //Server
                                Long serverId = Newboy.INSTANCE.getHandlerPocket48().getServerIDByStarID(star_ID);
                                try {
                                    String roomInfo = informationFromPocketServerId(serverId);
                                    String[] lines = roomInfo.split("\n");
                                    for (String line : lines) {
                                        if (line.startsWith("Server_id:")) {
                                            out.append("\n🏠 ").append(line);
                                        } else if (line.contains(")") && !line.equals("无房间")) {
                                            if (line.contains("加密房间")) {
                                                out.append("\n🔒 ").append(line);
                                            } else if (line.contains("直播")) {
                                                out.append("\n📺 ").append(line);
                                            } else {
                                                out.append("\n🏠 ").append(line);
                                            }
                                        } else if (line.equals("无房间")) {
                                            out.append("\n❌ 无可用房间");
                                        }
                                    }
                                } catch (Exception e) {
                                    out.append(serverId == null ? "" : "\n🏠 服务器ID: " + serverId + "\n❌ 房间信息获取失败");
                                }

                                //贡献榜
                                StringBuilder fan = new StringBuilder();
                                if (star) {
                                    fan.append("\n\n🏆 贡献榜:");
                                    JSONObject archives = Newboy.INSTANCE.getHandlerPocket48().getUserArchives(star_ID);
                                    if (archives != null) {
                                        Object[] fans = archives.getJSONArray("fansRank").stream().toArray();
                                        for (int i = 0; i < Math.min(fans.length, 5); i++) { // 只显示前5名
                                            fan.append("\n").append(i + 1).append(". ").append(UnifiedJsonParser.getInstance().parseObj(fans[i].toString()).getStr("nickName"));
                                        }
                                        if (fans.length > 5) {
                                            fan.append("\n...");
                                        }
                                    }
                                }

                                //头像
                                try (ExternalResource avatarResource = ExternalResource.create(getInputStream(avatar))) {
                                    return new PlainText(out.toString()).plus(
                                                    g.uploadImage(avatarResource))
                                            .plus(fan.toString());
                                } catch (IOException e) {
                                    return new PlainText(out.toString()).plus(fan.toString());
                                }

                            }
                            case "查询2": {
                                long server_id = Long.valueOf(args[2]);
                                if (server_id != 0) {
                                    try {
                                        StringBuilder out = new StringBuilder();
                                        out.append("🏠 服务器信息查询\n");
                        
                                        
                                        String roomInfo = informationFromPocketServerId(server_id);
                                        String[] lines = roomInfo.split("\n");
                                        for (String line : lines) {
                                            if (line.startsWith("Server_id:")) {
                                                out.append("🆔 ").append(line).append("\n");
                                            } else if (line.contains(")") && !line.equals("无房间")) {
                                                if (line.contains("加密房间")) {
                                                    out.append("🔒 ").append(line).append("\n");
                                                } else if (line.contains("直播")) {
                                                    out.append("📺 ").append(line).append("\n");
                                                } else {
                                                    out.append("🏠 ").append(line).append("\n");
                                                }
                                            } else if (line.equals("无房间")) {
                                                out.append("❌ 无可用房间\n");
                                            }
                                        }
                                        return new PlainText(out.toString());
                                    } catch (Exception e) {
                                        // 静默处理Server_id错误，不向群组推送错误消息
                                        Newboy.INSTANCE.getLogger().info("Server_id不存在或房间信息获取失败: " + args[2]);
                                        return null;
                                    }
                                }
                                // 静默处理非法Server_id，不向群组推送错误消息
                                Newboy.INSTANCE.getLogger().info("非法Server_id: " + args[2]);
                                return null;
                            }
                            case "关注": {
                                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                                    return new PlainText("权限不足喵");

                                Pocket48RoomInfo roomInfo = Newboy.INSTANCE.getHandlerPocket48().getRoomInfoByChannelID(Long.valueOf(args[2]));
                                if (roomInfo == null) {
                                    return new PlainText("房间ID不存在。查询房间ID请输入/口袋 查询 <成员ID>");
                                }

                                if (Newboy.INSTANCE.getConfig().addPocket48RoomSubscribe(Long.valueOf(args[2]), group)) {
                                    String roomName = roomInfo.getRoomName();
                                    String ownerName = roomInfo.getOwnerName();
                                    return new PlainText("本群新增关注：" + roomName + "(" + ownerName + ")");
                                } else
                                    return new PlainText("本群已经关注过这个房间了");
                            }
                            case "取消关注": {
                                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                                    return new PlainText("权限不足喵");

                                if (!Newboy.INSTANCE.getProperties().pocket48_subscribe.containsKey(group))
                                    return new PlainText("本群暂无房间关注，先添加一个吧~");

                                long roomId = Long.valueOf(args[2]);
                                if (Newboy.INSTANCE.getConfig().rmPocket48RoomSubscribe(roomId, group)) {
                                    // 检查是否是加密房间并移除连接配置
                                    boolean connectionRemoved = false;
                                    if (Newboy.INSTANCE.getProperties().pocket48_serverID.containsKey(roomId)) {
                                        long serverId = Newboy.INSTANCE.getProperties().pocket48_serverID.get(roomId);
                                        connectionRemoved = Newboy.INSTANCE.getConfig().rmRoomIDConnection(roomId, serverId);
                                    }
                                    
                                    Pocket48RoomInfo roomInfo = Newboy.INSTANCE.getHandlerPocket48().getRoomInfoByChannelID(roomId);
                                    if (roomInfo != null) {
                                        String roomName = roomInfo.getRoomName();
                                        String ownerName = roomInfo.getOwnerName();
                                        String message = "本群取消关注：" + roomName + "(" + ownerName + ")";
                                        if (connectionRemoved) {
                                            message += "\n🔒 已同时移除加密房间连接配置";
                                        }
                                        return new PlainText(message);
                                    } else {
                                        String message = "本群取消关注：未知房间";
                                        if (connectionRemoved) {
                                            message += "\n🔒 已同时移除加密房间连接配置";
                                        }
                                        return new PlainText(message);
                                    }
                                } else
                                    return new PlainText("本群没有关注此房间捏~");

                            }
                        }
                    case 4:
                        if (args[1].equals("连接")) {
                            if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                                return new PlainText("权限不足喵");
                                
                            long room_id = Long.valueOf(args[2]);
                            long server_id = Long.valueOf(args[3]);
                            if (testRoomIDWithServerID(room_id, server_id)) {
                                boolean connectionAdded = Newboy.INSTANCE.getConfig().addRoomIDConnection(room_id, server_id);
                                boolean subscriptionAdded = Newboy.INSTANCE.getConfig().addPocket48RoomSubscribe(room_id, group);
                                
                                if (connectionAdded && subscriptionAdded) {
                                    return new PlainText("✅ 连接成功并已添加到关注列表\n🔒 加密房间现在可以正常接收消息");
                                } else if (!connectionAdded && subscriptionAdded) {
                                    return new PlainText("✅ 连接已存在，已添加到关注列表\n🔒 加密房间现在可以正常接收消息");
                                } else if (connectionAdded && !subscriptionAdded) {
                                    return new PlainText("✅ 连接成功，但房间已在关注列表中\n🔒 加密房间现在可以正常接收消息");
                                } else {
                                    return new PlainText("✅ 连接已存在，房间已在关注列表中\n🔒 加密房间现在可以正常接收消息");
                                }
                            } else {
                                // 静默处理ServerId不包含RoomId的错误，不向群组推送错误消息
                                Newboy.INSTANCE.getLogger().info("ServerId不包含RoomId: " + args[2] + ", " + args[3]);
                                return null;
                            }
                        }
                    default:
                        return getCategorizedHelp(-1);
                }


            case "/超话":
                switch (args.length) {
                    case 2:
                        if (args[1].equals("关注列表")) {
                            StringBuilder out = new StringBuilder();
                            out.append("🎭 微博超话关注列表\n");
            
                            
                            if (!Newboy.INSTANCE.getProperties().weibo_superTopic_subscribe.containsKey(group)) {
                                out.append("暂无关注的超话");
                                return new PlainText(out.toString());
                            }

                            int count = 1;
                            for (String id : Newboy.INSTANCE.getProperties().weibo_superTopic_subscribe.get(group)) {
                                String a = Newboy.INSTANCE.getHandlerWeibo().getSuperTopicRes(id);
                                if (a == null) {
                                    out.append(count).append(". 不存在的超话\n");
                                    out.append("   超话ID：").append(id).append("\n");
                                } else {
                                    a = a.substring(a.indexOf("onick']='") + "onick']='".length());
                                    String name = a.substring(0, a.indexOf("';"));
                                    out.append(count).append(". ").append(name).append("\n");
                                    out.append("   超话ID：").append(id).append("\n");
                                }
                                
                                if (count < Newboy.INSTANCE.getProperties().weibo_superTopic_subscribe.get(group).size()) {
                                    out.append("\n");
                                }
                                count++;
                            }
                            return new PlainText(out.toString());
                        }
                    case 3:
                        switch (args[1]) {
                            case "关注": {
                                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                                    return new PlainText("权限不足喵");

                                String a = Newboy.INSTANCE.getHandlerWeibo().getSuperTopicRes(args[2]);
                                if (a == null) {
                                    // 静默处理超话id不存在的情况，不向群组推送错误消息
                                    Newboy.INSTANCE.getLogger().info("超话id不存在: " + args[2]);
                                    return null;
                                }
                                else {
                                    if (Newboy.INSTANCE.getConfig().addWeiboSTopicSubscribe(args[2], group)) {
                                        a = a.substring(a.indexOf("onick']='") + "onick']='".length());
                                        String name = a.substring(0, a.indexOf("';"));
                                        return new PlainText("本群新增超话关注：" + name);
                                    } else return new PlainText("本群已经关注过这个超话了");
                                }
                            }

                            case "取消关注": {
                                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                                    return new PlainText("权限不足喵");

                                if (!Newboy.INSTANCE.getProperties().weibo_superTopic_subscribe.containsKey(group))
                                    return new PlainText("本群暂无超话关注，先添加一个吧~");

                                if (Newboy.INSTANCE.getConfig().rmWeiboSTopicSubscribe(args[2], group)) {
                                    String a = Newboy.INSTANCE.getHandlerWeibo().getSuperTopicRes(args[2]);
                                    if (a == null)
                                        return new PlainText("本群取消关注超话：未知");
                                    else {
                                        a = a.substring(a.indexOf("onick']='") + "onick']='".length());
                                        String name = a.substring(0, a.indexOf("';"));
                                        return new PlainText("本群取消关注超话：" + name);
                                    }
                                } else
                                    return new PlainText("本群没有关注此超话捏~");
                            }
                        }
                    default:
                        return getCategorizedHelp(-1);
                }
            case "/微博":
            case "/weibo":
                switch (args.length) {
                    case 2:
                        if (args[1].equals("关注列表")) {
                            StringBuilder out = new StringBuilder();
                            out.append("📱 微博用户关注列表\n");
            
                            
                            if (!Newboy.INSTANCE.getProperties().weibo_user_subscribe.containsKey(group)) {
                                out.append("暂无关注的用户");
                                return new PlainText(out.toString());
                            }

                            int count = 1;
                            WeiboApiService weiboApiService = new WeiboApiService();
                            
                            for (long id : Newboy.INSTANCE.getProperties().weibo_user_subscribe.get(group)) {
                                String name = "未知用户";
                                String lastUpdateTime = "暂无微博";
                                
                                try {
                                    String nickname = weiboApiService.getUserNickname(String.valueOf(id));
                                    if (nickname != null && !nickname.equals("未知用户")) {
                                        name = nickname;
                                    }
                                    
                                    // 获取最新微博时间
                                    String latestTime = weiboApiService.getUserLatestWeiboTime(String.valueOf(id));
                                    if (latestTime != null && !latestTime.equals("暂无微博")) {
                                        lastUpdateTime = latestTime;
                                    }
                                } catch (Exception e) {
                                    // 获取信息失败，使用默认值
                                }
                                
                                
                                out.append(count).append(". ").append(name).append("\n");
                                out.append("   用户ID：").append(id).append("\n");
                                out.append("   最后更新：").append(lastUpdateTime).append("\n");
                                
                                if (count < Newboy.INSTANCE.getProperties().weibo_user_subscribe.get(group).size()) {
                                    out.append("\n");
                                }
                                count++;
                            }
                            return new PlainText(out.toString());
                        }
                    case 3:
                        switch (args[1]) {
                            case "关注": {
                                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                                    return new PlainText("权限不足喵");

                                String name = Newboy.INSTANCE.getHandlerWeibo().getUserName(Long.valueOf(args[2]));
                                if (name.equals("未知用户"))
                                    return new PlainText("博主id不存在。");
                                else {
                                    if (Newboy.INSTANCE.getConfig().addWeiboUserSubscribe(Long.valueOf(args[2]), group))
                                        return new PlainText("本群新增微博关注：" + name);
                                    else
                                        return new PlainText("本群已经关注过这个博主了");
                                }
                            }
                            case "取消关注": {
                                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                                    return new PlainText("权限不足喵");

                                if (!Newboy.INSTANCE.getProperties().weibo_user_subscribe.containsKey(group))
                                    return new PlainText("本群暂无微博关注，先添加一个吧~");

                                if (Newboy.INSTANCE.getConfig().rmWeiboUserSubscribe(Long.valueOf(args[2]), group))
                                    return new PlainText("本群取消关注超话：" +
                                            Newboy.INSTANCE.getHandlerWeibo().getUserName(Long.valueOf(args[2])));
                                else
                                    return new PlainText("本群没有关注此超话捏~");
                            }
                        }
                    default:
                        return getCategorizedHelp(-1);
                }
            case "/监控添加":
            case "/monitor_add": {
                if (args.length < 2 || args[1] == null || args[1].trim().isEmpty()) {
                    // 静默处理参数不足的情况，不向群组推送错误消息
                    Newboy.INSTANCE.getLogger().info("监控添加参数不足");
                    return null;
                }
                
                String memberName = args[1].trim();
                String result = AsyncOnlineStatusMonitor.INSTANCE.addSubscribedMember(group, memberName);
                return new PlainText(result);
            }
            case "/监控移除":
            case "/monitor_remove": {
                if (args.length < 2 || args[1] == null || args[1].trim().isEmpty()) {
                    return new PlainText("❌ 请输入成员名称\n💡 使用方法：/监控移除 成员名称");
                }
                
                String memberName = args[1].trim();
                String result = AsyncOnlineStatusMonitor.INSTANCE.removeSubscribedMember(group, memberName);
                return new PlainText(result);
            }
            case "/抖音":
            case "/douyin":
                switch (args.length) {
                    case 2:
                        if (args[1].equals("关注列表")) {
                            StringBuilder out = new StringBuilder();
                            out.append("📱 抖音用户关注列表\n");
            
                            if (!Newboy.INSTANCE.getProperties().douyin_user_subscribe.containsKey(group)) {
                                out.append("暂无关注的用户");
                                return new PlainText(out.toString());
                            }

                            int count = 1;
                            for (String secUserId : Newboy.INSTANCE.getProperties().douyin_user_subscribe.get(group)) {
                                // 尝试从监控服务获取用户昵称和最后更新时间
                                String name = "抖音用户";
                                String lastUpdateTime = "未知";
                                try {
                                    DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
                                    if (monitorService != null) {
                                        // 确保用户在监控服务中
                                        monitorService.addMonitorUser(secUserId);
                                        
                                        String nickname = monitorService.getMonitoredUserNickname(secUserId);
                                        if (nickname != null && !nickname.isEmpty() && !nickname.equals("未知用户")) {
                                            name = nickname;
                                        }
                                        
                                        // 获取最后更新时间
                            DouyinMonitorService.UserMonitorInfo userInfo = monitorService.getMonitoredUserInfo(secUserId);
                            if (userInfo != null) {
                                if (userInfo.lastUpdateTime > 0) {
                                    LocalDateTime dateTime = LocalDateTime.ofInstant(
                                        java.time.Instant.ofEpochMilli(userInfo.lastUpdateTime), 
                                        ZoneId.systemDefault());
                                    lastUpdateTime = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                                } else {
                                    lastUpdateTime = "暂无作品";
                                }
                            }
                                    }
                                } catch (Exception e) {
                                    // 如果获取失败，使用默认名称
                                }
                                
                                out.append(count).append(". ").append(name).append("\n");
                                out.append("   用户ID：").append(secUserId).append("\n");
                                out.append("   最后更新：").append(lastUpdateTime).append("\n");
                                
                                if (count < Newboy.INSTANCE.getProperties().douyin_user_subscribe.get(group).size()) {
                                    out.append("\n");
                                }
                                count++;
                            }
                            return new PlainText(out.toString());
                        }
                    case 3:
                        switch (args[1]) {
                            case "关注": {
                                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                                    return new PlainText("权限不足喵");

                                String secUserId = args[2];
                                // 简化处理，直接使用输入的ID
                                if (secUserId.contains("douyin.com")) {
                                    return new PlainText("请使用抖音监控命令处理分享链接");
                                }
                                
                                // 添加到配置
                                if (Newboy.INSTANCE.getConfig().addDouyinUserSubscribe(secUserId, group)) {
                                    // 尝试添加到监控服务并获取用户昵称
                                    String name = "抖音用户";
                                    try {
                                        DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
                                        if (monitorService != null) {
                                            // 添加到监控服务
                                            monitorService.addMonitorUser(secUserId);
                                            // 获取用户昵称
                                            String nickname = monitorService.getMonitoredUserNickname(secUserId);
                                            if (nickname != null && !nickname.isEmpty() && !nickname.equals("未知用户")) {
                                                name = nickname;
                                            }
                                        }
                                    } catch (Exception e) {
                                        // 如果获取失败，使用默认名称
                                    }
                                    return new PlainText("本群新增抖音关注：" + name + "\n用户ID：" + secUserId);
                                } else {
                                    return new PlainText("本群已经关注过这个用户了");
                                }
                            }
                            case "取消关注": {
                                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                                    return new PlainText("权限不足喵");

                                if (!Newboy.INSTANCE.getProperties().douyin_user_subscribe.containsKey(group))
                                    return new PlainText("本群暂无抖音关注，先添加一个吧~");

                                String secUserId = args[2];
                                if (Newboy.INSTANCE.getConfig().rmDouyinUserSubscribe(secUserId, group))
                                    return new PlainText("本群取消关注抖音用户：" + secUserId);
                                else
                                    return new PlainText("本群没有关注此用户捏~");
                            }
                        }
                    default:
                        return getCategorizedHelp(-1);
                }

            case "/监控列表":
            case "/monitor_list": {
                String result = AsyncOnlineStatusMonitor.INSTANCE.getSubscribedMembers(group);
                return new PlainText(result);
            }
            case "/监控开关":
            case "/monitor_toggle": {
                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                    return new PlainText("权限不足喵");
                    
                // 异步监控器始终启用，这里返回状态信息
                return new PlainText("✅ 异步在线状态监控正在运行中\n📊 统计信息:\n" + AsyncOnlineStatusMonitor.INSTANCE.getStatistics());
            }
            case "/监控查询":
            case "/monitor_check": {
                if (args.length < 2 || args[1] == null || args[1].trim().isEmpty()) {
                    return new PlainText("❌ 请输入成员名称\n💡 使用方法：/监控查询 成员名称");
                }
                
                String memberName = args[1].trim();
                try {
                    Xox48Handler.OnlineStatusResult result = Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
                    return new PlainText(result.formatResult());
                } catch (Exception e) {
                    return new PlainText("❌ 查询失败：" + e.getMessage());
                }
            }
            case "/抖音监控":
            case "/douyin_monitor":
            case "/抖音状态":
            case "/douyin_status":
            case "/抖音用户":
            case "/douyin_users":
            case "/抖音添加":
            case "/douyin_add":
            case "/抖音删除":
            case "/douyin_remove":
            case "/抖音重启":
            case "/douyin_restart":
                return DouyinCommandHandler.getInstance().handlePublicDouyinCommand(args, g, senderID);

            case "/帮助":
            case "/help":
            case "/?":
                return getCategorizedHelp(-1);
        }

        return null;
    }

    public Message executePrivate(String message, UserMessageEvent event) {
        String[] args = splitPrivateCommand(message);

        //权限检测
        switch (args[0]) {
            case "/微店":
            case "/weidian": {
                try {
                    long groupId = Long.valueOf(args[1]);
                    Message test = testPermission(groupId, event);
                    if (test != null)
                        return test;
                } catch (Exception e) {
                    return getCategorizedHelp(event.getSender().getId());
                }
                break;
            }

        }

        switch (args[0]) {
            case "/帮助":
            case "/help":
            case "/?":
                return getCategorizedHelp(event.getSender().getId());
            case "/在线":
            case "/online": {
                if (args.length < 2 || args[1] == null || args[1].trim().isEmpty()) {
                    return new PlainText("❌ 请输入要查询的成员名称\n💡 使用方法：/在线 成员名称");
                }
                
                String memberName = args[1].trim();
                Xox48Handler.OnlineStatusResult result = Newboy.INSTANCE.getHandlerXox48().queryMemberOnlineStatus(memberName);
                return new PlainText(result.formatResult());
            }
            case "/监控":
            case "/monitor": {
                return handlePrivateAsyncMonitorCommand(args, event);
            }
            case "/口袋":
            case "/pocket":
                return Pocket48CommandHandler.getInstance().handlePrivatePocket48Command(args, event);
            case "/微博":
            case "/weibo":
                return WeiboCommandHandler.getInstance().handlePrivateWeiboCommand(args, event);
            case "/超话":
            case "/supertopic":
                return handlePrivateSuperTopicCommand(args, event);
            case "/抖音":
            case "/douyin":
                return DouyinCommandHandler.getInstance().handlePrivateDouyinCommand(args, event);
            case "/抖音监控":
            case "/抖音用户":
            case "/抖音状态":
            case "/抖音添加":
            case "/抖音删除":
            case "/抖音重启":
                return handleDouyinMonitorCommand(args, event);

            case "/微店":
            case "/weidian": {
                long groupId;
                try {
                    groupId = Long.valueOf(args[1]);
                } catch (Exception e) {
                    return getCategorizedHelp(event.getSender().getId());
                }

                if (args[2].startsWith("cookie")) {
                    String cookie;
                    if (args[2].equals("cookie") && args.length > 3) {
                        // 格式：/微店 群号 cookie <实际cookie内容>
                        // 将第4个参数开始的所有内容重新组合成完整cookie
                        StringBuilder cookieBuilder = new StringBuilder();
                        for (int i = 3; i < args.length; i++) {
                            if (args[i] != null && !args[i].trim().isEmpty()) {
                                if (cookieBuilder.length() > 0) {
                                    cookieBuilder.append(" ");
                                }
                                cookieBuilder.append(args[i]);
                            }
                        }
                        cookie = cookieBuilder.toString();
                    } else if (args[2].contains(" ")) {
                        // 传统格式：cookie <实际cookie内容>
                        cookie = args[2].substring(args[2].indexOf(" ") + 1);
                    } else if (args[2].length() > 6) {
                        // 直接提供cookie内容，去掉"cookie"前缀
                        cookie = args[2].substring(6);
                    } else {
                        return new PlainText("❌ 请输入Cookie\n💡 使用方法：/微店 " + groupId + " cookie <您的cookie内容>\n📝 或者直接：/微店 " + groupId + " <您的完整cookie>");
                    }
                    
                    // 验证cookie格式（必须包含wdtoken）
                    if (!cookie.contains("wdtoken=")) {
                        return new PlainText("❌ Cookie格式错误\n💡 Cookie必须包含wdtoken参数\n📋 请确保从微店网站复制完整的Cookie");
                    }
                    
                    try {
                        Newboy.INSTANCE.getConfig().setWeidianCookie(cookie, groupId);
                        WeidianCookie cookie1 = Newboy.INSTANCE.getProperties().weidian_cookie.get(groupId);
                        if (cookie1 == null) {
                            return new PlainText("❌ Cookie设置失败\n💡 请检查Cookie格式是否正确");
                        }
                        return new PlainText("✅ 设置Cookie成功\n🚚 当前自动发货：" + (cookie1.autoDeliver ? "开启" : "关闭") + "\n📢 当前群播报：" + (cookie1.doBroadcast ? "开启" : "关闭") + "\n💡 您可以通过\"/微店 " + groupId + " 自动发货\"和\"/微店 " + groupId + " 群播报\"进行切换");
                    } catch (Exception e) {
                        return new PlainText("❌ Cookie设置失败：" + e.getMessage());
                    }
                }
                
                // 支持直接输入完整cookie（不以cookie开头但包含wdtoken）
                // 检查是否包含wdtoken，如果包含则重新组合所有参数为完整cookie
                StringBuilder fullCommand = new StringBuilder();
                for (int i = 2; i < args.length; i++) {
                    if (args[i] != null && !args[i].trim().isEmpty()) {
                        if (fullCommand.length() > 0) {
                            fullCommand.append(" ");
                        }
                        fullCommand.append(args[i]);
                    }
                }
                String potentialCookie = fullCommand.toString();
                
                if (potentialCookie.contains("wdtoken=") && potentialCookie.contains(";")) {
                    try {
                        Newboy.INSTANCE.getConfig().setWeidianCookie(potentialCookie, groupId);
                        WeidianCookie cookie1 = Newboy.INSTANCE.getProperties().weidian_cookie.get(groupId);
                        if (cookie1 == null) {
                            return new PlainText("❌ Cookie设置失败\n💡 请检查Cookie格式是否正确");
                        }
                        return new PlainText("✅ 设置Cookie成功\n🚚 当前自动发货：" + (cookie1.autoDeliver ? "开启" : "关闭") + "\n📢 当前群播报：" + (cookie1.doBroadcast ? "开启" : "关闭") + "\n💡 您可以通过\"/微店 " + groupId + " 自动发货\"和\"/微店 " + groupId + " 群播报\"进行切换");
                    } catch (Exception e) {
                        return new PlainText("❌ Cookie设置失败：" + e.getMessage());
                    }
                }

                if (!Newboy.INSTANCE.getProperties().weidian_cookie.containsKey(groupId)) {
                    return new PlainText("该群未设置Cookie");
                } else {
                    // 检查第三个参数是否为空
                    if (args[2] == null || args[2].trim().isEmpty()) {
                        return new PlainText("❌ 请输入操作命令\n💡 使用方法：/微店 " + groupId + " <操作>\n📋 可用操作：全部、关闭、自动发货、群播报、全部发货、# <商品ID>、屏蔽 <商品ID>、查 <商品ID>");
                    }
                    
                    // 将第三个参数及后续参数组合起来再分割
                    StringBuilder commandBuilder = new StringBuilder(args[2]);
                    for (int i = 3; i < args.length; i++) {
                        commandBuilder.append(" ").append(args[i]);
                    }
                    String[] argsIn = commandBuilder.toString().split(" ");
                    switch (argsIn.length) {
                        case 1:
                            switch (argsIn[0]) {
                                case "全部": {
                                    WeidianHandler weidian = Newboy.INSTANCE.getHandlerWeidian();
                                    WeidianCookie cookie = Newboy.INSTANCE.getProperties().weidian_cookie.get(groupId);

                                    StringBuilder o = new StringBuilder();
                                    o.append("📊 微店状态\n");
        
                                    o.append("群播报：").append(cookie.doBroadcast ? "✅ 开启" : "❌ 关闭").append("\n");
                                    o.append("自动发货：").append(cookie.autoDeliver ? "✅ 开启" : "❌ 关闭").append("\n");

                                    WeidianItem[] items = weidian.getItems(cookie);
                                    if (items == null) {
                                        if (!cookie.invalid) {
                                            cookie.invalid = true;
                                        }
                                        return new PlainText(o + "\n---------\n获取商品列表错误，请重新提交Cookie");
                                    }

        
                                    o.append("📦 商品列表 (共").append(items.length).append("个)\n");
        
                                    for (int i = 0; i < items.length; i++) {
                                        String status = cookie.shieldedItem.contains(items[i].id) ? "🚫 屏蔽" :
                                                (cookie.highlightItem.contains(items[i].id) ? "🔗 特殊链" : "🔗 普链");
                                        o.append("\n").append(i + 1).append(". ").append(items[i].name);
                                        o.append("\n   ID: ").append(items[i].id);
                                        o.append("\n   状态: ").append(status);
                                        if (i < items.length - 1) {
                                            o.append("\n");
                                        }
                                    }

                                    if (cookie.invalid) {
                                        cookie.invalid = false;
            
                                        o.append("✅ Cookie状态：有效，无需更换");
                                    }
                                    return new PlainText(o.toString());
                                }
                                case "关闭": {
                                    Newboy.INSTANCE.getConfig().rmWeidianCookie(groupId);
                                    return new PlainText("该群微店播报重置");
                                }
                                case "自动发货": {
                                    return new PlainText("自动发货设为：" + (Newboy.INSTANCE.getConfig().switchWeidianAutoDeliver(groupId) == 1 ? "开启" : "关闭"));
                                }
                                case "群播报": {
                                    return new PlainText("群播报设为：" + (Newboy.INSTANCE.getConfig().switchWeidianDoBroadCast(groupId) == 1 ? "开启" : "关闭"));
                                }
                                case "全部发货": {
                                    WeidianHandler weidian = Newboy.INSTANCE.getHandlerWeidian();
                                    WeidianCookie cookie = Newboy.INSTANCE.getProperties().weidian_cookie.get(groupId);
                                    
                                    if (cookie == null) {
                                        return new PlainText("❌ 该群未配置微店Cookie\n🔧 请使用以下命令设置：\n`/微店 " + groupId + " cookie <您的Cookie>`");
                                    }
                                    
                                    if (cookie.invalid) {
                                        return new PlainText("❌ 微店Cookie已失效\n🔧 请重新设置Cookie：\n`/微店 " + groupId + " cookie <新Cookie>`");
                                    }
                                    
                                    
                                    boolean pre = cookie.autoDeliver;
                                    cookie.autoDeliver = true;
                                    
                                    try {
                                        WeidianOrder[] orders = weidian.getOrderList(cookie);
                                        // Newboy.INSTANCE.getLogger().info("[全部发货] 处理完成，订单数量: " + (orders != null ? orders.length : 0));
                                        return new PlainText("✅ 全部发货命令执行完成\n📦 处理订单数量: " + (orders != null ? orders.length : 0) + "\n💡 详细日志已写入日志文件\n⚠️ 不包括包含屏蔽商品的订单");
                                    } catch (Exception e) {
                                        // Newboy.INSTANCE.getLogger().warning("[全部发货] 执行异常: " + e.getMessage());
                                        e.printStackTrace();
                                        return new PlainText("❌ 全部发货执行失败: " + e.getMessage());
                                    } finally {
                                        cookie.autoDeliver = pre;
                                        // Newboy.INSTANCE.getLogger().info("[全部发货] 恢复Cookie自动发货状态: " + cookie.autoDeliver);
                                    }
                                }
                                case "状态":
                                case "检查": {
                                    WeidianHandler weidian = Newboy.INSTANCE.getHandlerWeidian();
                                    WeidianCookie cookie = Newboy.INSTANCE.getProperties().weidian_cookie.get(groupId);
                                    
                                    if (cookie == null) {
                                        return new PlainText("❌ 该群未配置微店Cookie\n🔧 请使用以下命令设置：\n`/微店 " + groupId + " cookie <您的Cookie>`");
                                    }
                                    
                                    StringBuilder status = new StringBuilder();
                                    status.append("🏪 微店状态检查\n");
                                    status.append("群号：").append(groupId).append("\n");
                                    status.append("群播报：").append(cookie.doBroadcast ? "✅ 开启" : "❌ 关闭").append("\n");
                                    status.append("自动发货：").append(cookie.autoDeliver ? "✅ 开启" : "❌ 关闭").append("\n");
                                    
                                    // 测试API连接
                                    status.append("\n🔍 正在检查Cookie状态...");
                                    WeidianItem[] items = weidian.getItems(cookie);
                                    
                                    if (items != null) {
                                        status.append("\n✅ Cookie状态：正常");
                                        status.append("\n📦 商品数量：").append(items.length).append("个");
                                        if (cookie.invalid) {
                                            cookie.invalid = false;
                                        }
                                    } else {
                                        status.append("\n❌ Cookie状态：失效");
                                        status.append("\n🔧 请重新设置Cookie：\n`/微店 ").append(groupId).append(" cookie <新Cookie>`");
                                        if (!cookie.invalid) {
                                            cookie.invalid = true;
                                        }
                                    }
                                    
                                    return new PlainText(status.toString());
                                }
                                default:
                                    // 静默处理未知操作，不向群组推送错误消息
                                    Newboy.INSTANCE.getLogger().info("微店未知操作(case 1): " + argsIn[0]);
                                    return null;
                            }
                        case 2:
                            switch (argsIn[0]) {
                                case "#": {
                                    long id = Long.valueOf(argsIn[1]);
                                    switch (Newboy.INSTANCE.getConfig().highlightWeidianItem(groupId, id)) {
                                        case -1:
                                            return new PlainText("未设置cookie");
                                        case 0:
                                            return new PlainText("将商品id为" + id + "的商品设为：普链");
                                        case 1:
                                            return new PlainText("将商品id为" + id + "的商品设为：特殊链");
                                    }
                                }
                                case "屏蔽": {
                                    long id = Long.valueOf(argsIn[1]);
                                    switch (Newboy.INSTANCE.getConfig().shieldWeidianItem(groupId, id)) {
                                        case -1:
                                            return new PlainText("未设置cookie");
                                        case 0:
                                            return new PlainText("取消屏蔽id为" + id + "的商品");
                                        case 1:
                                            return new PlainText("已屏蔽id为" + id + "的商品");
                                    }
                                }
                                case "查": {
                                    WeidianHandler weidian = Newboy.INSTANCE.getHandlerWeidian();
                                    WeidianCookie cookie = Newboy.INSTANCE.getProperties().weidian_cookie.get(groupId);
                                    
                                    if (cookie == null) {
                                        return new PlainText("❌ 该群未设置微店Cookie");
                                    }

                                    long id = Long.valueOf(argsIn[1]);
                                    WeidianItem item = weidian.searchItem(cookie, id);
                                    if (item == null) {
                                        return new PlainText("❌ 未找到商品ID: " + id + "\n可能原因：\n1. 商品ID不存在\n2. Cookie已失效\n3. 网络连接问题");
                                    } else {
                                        // 获取购买者信息和统计数据
                                        WeidianBuyer[] buyers = weidian.getItemBuyer(cookie, id);
                                        
                                        // 构建消息，包含图片
                                        Message itemMessage = new PlainText(item.name + "\n");
                                        
                                        // 尝试加载并嵌入商品图片
                                        if (!item.pic.equals("")) {
                                            try {
                                                WeidianSenderHandler handler = Newboy.INSTANCE.getHandlerWeidianSender();
                                                try (InputStream imageStream = handler.getRes(item.pic)) {
                                                    if (imageStream != null) {
                                                        // 无论群聊还是私聊都嵌入图片
                                                        if (event.getSubject() instanceof Group) {
                                                            Group group = (Group) event.getSubject();
                                                            try (ExternalResource imageResource = ExternalResource.create(imageStream)) {
                                                                Image image = group.uploadImage(imageResource);
                                                                itemMessage = itemMessage.plus(image);
                                                            }
                                                        } else {
                                                            // 私聊中也嵌入图片
                                                            try (ExternalResource imageResource = ExternalResource.create(imageStream)) {
                                                                Image image = event.getSubject().uploadImage(imageResource);
                                                                itemMessage = itemMessage.plus(image);
                                                            }
                                                        }
                                                    } else {
                                                        itemMessage = itemMessage.plus(new PlainText("[商品图片无法获取]\n"));
                                                        // Newboy.INSTANCE.getLogger().warning("[微店查询] 商品ID " + id + " 图片数据为空，URL: " + item.pic);
                                                    }
                                                }
                                            } catch (Exception e) {
                                                // 图片加载失败时显示提示，不再显示URL链接
                                                itemMessage = itemMessage.plus(new PlainText("[图片加载失败: " + e.getMessage() + "]\n"));
                                                // Newboy.INSTANCE.getLogger().warning("[微店查询] 商品ID " + id + " 图片加载失败: " + e.getMessage());
                                            }
                                        } else {
                                            itemMessage = itemMessage.plus(new PlainText("[暂无商品图片]\n"));
                                        }
                                        
                                        // 显示购买统计信息
                                        if (buyers != null && buyers.length > 0) {
                                            long totalAmount = 0;
                                            for (WeidianBuyer buyer : buyers) {
                                                totalAmount += buyer.contribution;
                                            }
                                            
                                            itemMessage = itemMessage.plus(new PlainText("人数：" + buyers.length + "\n"));
                                            itemMessage = itemMessage.plus(new PlainText("进度：¥" + String.format("%.2f", totalAmount / 100.0) + "\n"));
                                            itemMessage = itemMessage.plus(new PlainText("人均：¥" + String.format("%.2f", totalAmount / 100.0 / buyers.length) + "\n"));
                                            itemMessage = itemMessage.plus(new PlainText(cn.hutool.core.date.DateTime.now() + "\n"));
                                            itemMessage = itemMessage.plus(new PlainText("──────────────────────\n"));
                                            itemMessage = itemMessage.plus(new PlainText("购买者列表:\n"));
                                            for (int i = 0; i < buyers.length; i++) {
                                                itemMessage = itemMessage.plus(new PlainText((i + 1) + ". ¥" + String.format("%.2f", buyers[i].contribution / 100.0) + " " + buyers[i].name + "\n"));
                                            }
                                        } else {
                                            itemMessage = itemMessage.plus(new PlainText("人数：0\n"));
                                            itemMessage = itemMessage.plus(new PlainText("进度：¥0.00\n"));
                                            itemMessage = itemMessage.plus(new PlainText(cn.hutool.core.date.DateTime.now() + "\n"));
                                            itemMessage = itemMessage.plus(new PlainText("暂无购买记录\n"));
                                        }
                                        
                                        return itemMessage;
                                    }
                                }
                                default:
                                    // 静默处理未知操作，不向群组推送错误消息
                                    Newboy.INSTANCE.getLogger().info("微店未知操作(case 2): " + argsIn[0]);
                                    return null;
                            }
                        default:
                            // 静默处理未知操作，不向群组推送错误消息
                            Newboy.INSTANCE.getLogger().info("微店未知操作参数数量(default): " + argsIn.length);
                            return null;
                    }
                }
            }
            case "/清理": {
                if (testPermission(event) == null) {
                    Properties properties = Newboy.INSTANCE.getProperties();
                    ConfigOperator config = Newboy.INSTANCE.getConfig();
                    Bot b = event.getBot();
                    Message log = new PlainText("【清理完成】\n");

                    //口袋48
                    int match = 0;
                    for (long group : properties.pocket48_subscribe.keySet()) {
                        if (b.getGroup(group) == null) {
                            properties.pocket48_subscribe.remove(group);
                            match++;
                        }
                    }
                    if (match > 0) {
                        config.savePocket48SubscribeConfig();
                        log = log.plus(new PlainText("口袋48关注失效群: " + match + "个\n"));
                        match = 0;
                    }



                    //微博
                    for (long group : properties.weibo_user_subscribe.keySet()) {
                        if (b.getGroup(group) == null) {
                            properties.weibo_user_subscribe.remove(group);
                            properties.weibo_superTopic_subscribe.remove(group);
                            match++;
                        }
                    }
                    if (match > 0) {
                        config.saveWeiboConfig();
                        log = log.plus(new PlainText("微博关注失效群: " + match + "个\n"));
                        match = 0;
                    }

                    //微店
                    for (long group : properties.weidian_cookie.keySet()) {
                        if (b.getGroup(group) == null) {
                            properties.weidian_cookie.remove(group);
                            match++;
                        }
                    }
                    if (match > 0) {
                        config.saveWeidianConfig();
                        log = log.plus(new PlainText("微店播报失效群: " + match + "个\n"));
                        match = 0;
                    }
                    
                    return log;
                } else {
                    return new PlainText("权限不足喵");
                }
            }
            case "/清理缓存":
            case "/clearcache": {
                if (testPermission(event) == null) {
                    try {
                        // 清理智能缓存管理器
                        SmartCacheManager.getInstance().clearAllCaches();
                        
                        // 清理口袋48资源缓存
                        Pocket48ResourceCache.getInstance().clearAll();
                        
                        // 清理JSON优化器缓存
                        JsonOptimizer.clearCache();
                        
                        // 清理Xox48Handler缓存
                        Newboy.INSTANCE.getHandlerXox48().resetCache();
                        
                        // 口袋48资源优化器缓存已通过其他组件清理
                        
                        // 强制垃圾回收
                        System.gc();
                        
                        return new PlainText("🧹 缓存清理完成\n" +
                                           "✅ 智能缓存管理器已清理\n" +
                                           "✅ 口袋48资源缓存已清理\n" +
                                           "✅ JSON优化器缓存已清理\n" +
                                           "✅ Xox48Handler缓存已清理\n" +
                                           "♻️ 已建议JVM进行垃圾回收");
                    } catch (Exception e) {
                        return new PlainText("❌ 清理缓存时发生错误: " + e.getMessage());
                    }
                } else {
                    return new PlainText("权限不足喵");
                }
            }

        }
        return null;
    }





    private String[] splitPrivateCommand(String command) {
        // 使用空格分割命令，支持更多参数
        String[] parts = command.trim().split("\\s+");
        
        // 确保至少有4个元素来支持跨群管理命令
        String[] out = new String[Math.max(4, parts.length)];
        
        // 复制分割后的参数
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i];
        }
        
        // 确保数组中的空位置为空字符串而不是null
        for (int j = parts.length; j < out.length; j++) {
            out[j] = "";
        }
        
        return out;
    }

    //私聊权限检测
    public Message testPermission(long groupId, UserMessageEvent event) {
        Group group = event.getBot().getGroup(groupId);
        if (group == null) {
            return new PlainText("群号不存在或机器人不在群");
        }

        if (!Newboy.INSTANCE.getConfig().isAdmin(group, event.getSender().getId())) {
            return new PlainText("权限不足喵");
        }
        return null;
    }

    // 私聊口袋48订阅管理
    private Message handlePrivatePocket48Command(String[] args, UserMessageEvent event) {
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
                return new PlainText("❌ 未知操作\n📋 可用操作：关注、取消关注、关注列表、搜索、查询");
        }
    }

    // 私聊微博订阅管理
    private Message handlePrivateWeiboCommand(String[] args, UserMessageEvent event) {
        if (args.length < 2) {
            return new PlainText("🐦 微博功能\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    "📋 可用命令:\n" +
                    "• /微博 关注列表 - 查看订阅列表\n" +
                    "• /微博 关注 <用户UID> <群号> - 添加订阅\n" +
                    "• /微博 取消关注 <用户UID> <群号> - 取消订阅\n" +
                    "• /微博 用户信息 <用户UID> - 查看用户信息\n" +
                    "• /微博 状态 - 查看监控状态\n\n" +
                    "💡 提示: 基于qqtools项目重构的微博监控功能");
        }

        switch (args[1]) {
            case "关注列表": {
                return getPrivateWeiboSubscribeList(event.getSender().getId());
            }
            case "关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 用法: /微博 关注 <用户UID> <群号>");
                }
                return addPrivateWeiboSubscribe(args[2], args[3], event);
            }
            case "取消关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 用法: /微博 取消关注 <用户UID> <群号>");
                }
                return removePrivateWeiboSubscribe(args[2], args[3], event);
            }
            case "用户信息": {
                if (args.length < 3) {
                    return new PlainText("❌ 参数不足\n💡 用法: /微博 用户信息 <用户UID>");
                }
                return getWeiboUserInfo(args[2]);
            }
            case "状态": {
                return getWeiboMonitorStatus();
            }
            default:
                return new PlainText("❌ 未知操作\n💡 使用 /微博 查看可用命令");
        }
    }

    // 私聊超话订阅管理
    private Message handlePrivateSuperTopicCommand(String[] args, UserMessageEvent event) {
        if (args.length < 2) {
            return new PlainText("🔥 超话功能\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    "📋 可用命令:\n" +
                    "• /超话 关注列表 - 查看订阅列表\n" +
                    "• /超话 关注 <容器ID> <群号> - 添加订阅\n" +
                    "• /超话 取消关注 <容器ID> <群号> - 取消订阅\n" +
                    "• /超话 状态 - 查看监控状态\n\n" +
                    "💡 提示: 基于qqtools项目重构的超话监控功能");
        }

        switch (args[1]) {
            case "关注列表": {
                return getPrivateSuperTopicSubscribeList(event.getSender().getId());
            }
            case "关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 用法: /超话 关注 <容器ID> <群号>");
                }
                return addPrivateSuperTopicSubscribe(args[2], args[3], event);
            }
            case "取消关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 用法: /超话 取消关注 <容器ID> <群号>");
                }
                return removePrivateSuperTopicSubscribe(args[2], args[3], event);
            }
            case "状态": {
                return getWeiboMonitorStatus();
            }
            default:
                return new PlainText("❌ 未知操作\n💡 使用 /超话 查看可用命令");
        }
    }

    // 分类帮助信息
    public Message getCategorizedHelp(long contactId) {
        return new PlainText("📋 Newboy 机器人帮助\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🎯 可用命令:\n\n" +
                "📱 /口袋 - 口袋48功能\n" +
                "  • /口袋 关注列表 - 查看订阅列表\n" +
                "  • /口袋 搜索 <关键词> - 搜索成员/团体\n" +
                "  • /口袋 查询 <用户ID> - 查询用户信息\n" +
                "  • /口袋 关注 <房间ID> <群号> - 添加订阅\n" +
                "  • /口袋 取消关注 <房间ID> <群号> - 取消订阅\n\n" +
                "📱 /微博 - 微博监控功能\n" +
                "  • /微博 关注列表 - 查看关注的微博用户\n" +
                "  • /微博 关注 <用户UID> <群号> - 关注微博用户\n" +
                "  • /微博 取消关注 <用户UID> <群号> - 取消关注\n\n" +
                "🎭 /超话 - 微博超话功能\n" +
                "  • /超话 关注列表 - 查看关注的超话\n" +
                "  • /超话 关注 <超话ID> <群号> - 关注超话\n" +
                "  • /超话 取消关注 <超话ID> <群号> - 取消关注\n\n" +
                "🛒 /微店 - 微店管理功能\n" +
                "  • /微店 <群号> 全部 - 查看所有商品\n" +
                "  • /微店 <群号> # <商品ID> - 查看商品详情\n" +
                "  • /微店 <群号> 屏蔽 <商品ID> - 屏蔽商品\n\n" +
                "🎵 /抖音 - 抖音用户关注功能\n" +
                "  • /抖音 关注列表 - 查看关注的抖音用户\n" +
                "  • /抖音 关注 <用户ID> <群号> - 关注抖音用户\n" +
                "  • /抖音 取消关注 <用户ID> <群号> - 取消关注\n\n" +
                "🎵 /抖音监控 - 抖音监控服务管理\n" +
                "  • /抖音监控 启动 - 启动监控服务\n" +
                "  • /抖音监控 停止 - 停止监控服务\n" +
                "  • /抖音状态 - 查看监控状态\n" +
                "  • /抖音用户 - 查看监控用户列表\n" +
                "  • /抖音添加 <用户链接> - 添加监控用户\n" +
                "  • /抖音删除 <用户ID> - 删除监控用户\n" +
                "  • /抖音重启 - 重启监控服务\n\n" +
                "📊 /监控 - 在线状态监控\n" +
                "  • /监控 列表 - 查看监控列表\n" +
                "  • /监控 添加 <成员名> <群号> - 添加成员监控\n" +
                "  • /监控 移除 <成员名> <群号> - 移除成员监控\n\n" +
                "🧹 /清理缓存 - 系统缓存管理\n" +
                "  • /清理缓存 - 清理所有系统缓存\n" +
                "  • /clearcache - 清理缓存（英文别名）\n\n" +
                "❓ /帮助 - 显示此帮助信息\n\n" +
                "💡 提示:\n" +
                "  • 大部分命令支持中英文别名\n" +
                "  • 管理员权限命令需要相应权限\n" +
                "  • 支持通过私聊为指定群组管理订阅");
    }

    // 获取私聊口袋48订阅列表
    private Message getPrivatePocket48SubscribeList(long userId) {
        StringBuilder result = new StringBuilder();
        result.append("📱 口袋48订阅列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        Properties properties = Newboy.INSTANCE.getProperties();
        boolean hasSubscription = false;
        
        for (long groupId : properties.pocket48_subscribe.keySet()) {
            if (!properties.pocket48_subscribe.get(groupId).getRoomIDs().isEmpty()) {
                hasSubscription = true;
                result.append("\n🏠 群组：").append(groupId).append("\n");
                
                int count = 1;
                for (long roomId : properties.pocket48_subscribe.get(groupId).getRoomIDs()) {
                    try {
                        Pocket48RoomInfo roomInfo = Newboy.INSTANCE.getHandlerPocket48().getRoomInfoByChannelID(roomId);
                        if (roomInfo != null) {
                            result.append("  ").append(count).append(". ").append(roomInfo.getRoomName());
                            result.append(" (").append(roomInfo.getOwnerName()).append(")\n");
                            result.append("     房间ID: ").append(roomId).append("\n");
                        } else {
                            result.append("  ").append(count).append(". 未知房间 (ID: ").append(roomId).append(")\n");
                        }
                        count++;
                    } catch (Exception e) {
                        result.append("  ").append(count).append(". 获取信息失败 (ID: ").append(roomId).append(")\n");
                        count++;
                    }
                }
            }
        }
        
        if (!hasSubscription) {
            result.append("\n❌ 暂无订阅\n");
            result.append("💡 使用 /口袋 关注 <房间ID> <群号> 添加订阅");
        }
        
        return new PlainText(result.toString());
    }

    // 搜索口袋48（私聊版本）
    private Message searchPocket48ForPrivate(String keyword) {
        Object[] servers = Newboy.INSTANCE.getHandlerPocket48().search(keyword);
        StringBuilder out = new StringBuilder();
        out.append("🔍 搜索结果：").append(keyword).append("\n");
        out.append("━━━━━━━━━━━━━━━━━━━━\n");

        if (servers.length == 0) {
            // 静默处理搜索无结果的情况，不向群组推送
            Newboy.INSTANCE.getLogger().info("私聊搜索无结果: " + keyword);
            return null;
        }

        int count = 1;
        for (Object server_ : servers) {
            JSONObject server = UnifiedJsonParser.getInstance().parseObj(server_.toString());
            String name = server.getStr("serverDefaultName");
            String serverName = server.getStr("serverName");
            long starId = server.getLong("serverOwner");
            Long serverId = server.getLong("serverId");

            out.append("\n📍 ").append(count).append(". ").append(name);
            if (!name.equals(serverName)) {
                out.append("(").append(serverName).append(")");
            }
            out.append("\n👤 用户ID: ").append(starId);
            out.append("\n🏠 服务器ID: ").append(serverId != null ? serverId : "未知");
            
            try {
                String roomInfo = informationFromPocketServerId(serverId);
                String[] lines = roomInfo.split("\n");
                for (String line : lines) {
                    if (line.startsWith("Server_id:")) {
                        continue;
                    } else if (line.contains(")") && !line.equals("无房间")) {
                        if (line.contains("加密房间")) {
                            out.append("\n🔒 ").append(line);
                        } else if (line.contains("直播")) {
                            out.append("\n📺 ").append(line);
                        } else {
                            out.append("\n🏠 ").append(line);
                        }
                    } else if (line.equals("无房间")) {
                        out.append("\n❌ 无可用房间");
                    }
                }
            } catch (Exception e) {
                out.append("\n❌ 房间信息获取失败");
            }
            
            out.append("\n💡 使用 /口袋 关注 <房间ID> <群号> 添加订阅");
            if (count < servers.length) {
                out.append("\n");
            }
            count++;
        }
        return new PlainText(out.toString());
    }

    // 查询口袋48用户（私聊版本）
    private Message queryPocket48UserForPrivate(String userIdStr) {
        try {
            long userId = Long.parseLong(userIdStr);
            JSONObject info = Newboy.INSTANCE.getHandlerPocket48().getUserInfo(userId);
            if (info == null) {
                return new PlainText("❌ 用户不存在");
            }

            boolean star = info.getBool("isStar");
            int followers = info.getInt("followers");
            int friends = info.getInt("friends");
            String nickName = info.getStr("nickname");
            String starName = info.getStr("starName");

            StringBuilder result = new StringBuilder();
            result.append("👤 用户信息\n");
            result.append("━━━━━━━━━━━━━━━━━━━━\n");
            result.append("昵称：").append(nickName).append("\n");
            if (star && starName != null && !starName.isEmpty()) {
                result.append("艺名：").append(starName).append("\n");
            }
            result.append("用户ID：").append(userId).append("\n");
            result.append("身份：").append(star ? "⭐ 偶像" : "👤 普通用户").append("\n");
            result.append("关注者：").append(followers).append("\n");
            result.append("好友：").append(friends).append("\n");
            result.append("\n💡 使用 /口袋 搜索 ").append(nickName).append(" 查找相关房间");

            return new PlainText(result.toString());
        } catch (NumberFormatException e) {
            // 静默处理格式错误，不向群组推送错误消息
            Newboy.INSTANCE.getLogger().info("用户ID格式错误: " + userIdStr);
            return null;
        }
    }

    // 添加私聊口袋48订阅
    private Message addPrivatePocket48Subscribe(String roomIdStr, String groupIdStr, UserMessageEvent event) {
        try {
            long roomId = Long.parseLong(roomIdStr);
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            boolean success = Newboy.INSTANCE.getConfig().addPocket48RoomSubscribe(roomId, groupId);
            if (success) {
                try {
                    Pocket48RoomInfo roomInfo = Newboy.INSTANCE.getHandlerPocket48().getRoomInfoByChannelID(roomId);
                    if (roomInfo != null) {
                        return new PlainText(String.format("✅ 成功为群 %d 添加口袋48订阅\n🏠 房间：%s\n👤 主播：%s\n🆔 房间ID：%d", 
                            groupId, roomInfo.getRoomName(), roomInfo.getOwnerName(), roomId));
                    } else {
                        return new PlainText(String.format("✅ 成功为群 %d 添加口袋48订阅\n🆔 房间ID：%d\n⚠️ 无法获取房间详细信息", groupId, roomId));
                    }
                } catch (Exception e) {
                    return new PlainText(String.format("✅ 成功为群 %d 添加口袋48订阅\n🆔 房间ID：%d\n⚠️ 获取房间信息时出错", groupId, roomId));
                }
            } else {
                return new PlainText(String.format("❌ 群 %d 已订阅房间 %d", groupId, roomId));
            }
        } catch (NumberFormatException e) {
            // 静默处理参数格式错误，不向群组推送错误消息
            Newboy.INSTANCE.getLogger().info("参数格式错误: " + roomIdStr + ", " + groupIdStr);
            return null;
        }
    }

    // 移除私聊口袋48订阅
    private Message removePrivatePocket48Subscribe(String roomIdStr, String groupIdStr, UserMessageEvent event) {
        try {
            long roomId = Long.parseLong(roomIdStr);
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            boolean success = Newboy.INSTANCE.getConfig().rmPocket48RoomSubscribe(roomId, groupId);
            if (success) {
                // 检查是否是加密房间并移除连接配置
                boolean connectionRemoved = false;
                if (Newboy.INSTANCE.getProperties().pocket48_serverID.containsKey(roomId)) {
                    long serverId = Newboy.INSTANCE.getProperties().pocket48_serverID.get(roomId);
                    connectionRemoved = Newboy.INSTANCE.getConfig().rmRoomIDConnection(roomId, serverId);
                }
                
                String message = String.format("✅ 成功为群 %d 移除口袋48订阅\n🆔 房间ID：%d", groupId, roomId);
                if (connectionRemoved) {
                    message += "\n🔒 已同时移除加密房间连接配置";
                }
                return new PlainText(message);
            } else {
                return new PlainText(String.format("❌ 群 %d 未订阅房间 %d", groupId, roomId));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 参数格式错误，房间ID和群号必须是数字");
        }
    }

    // 获取私聊微博订阅列表
    private Message getPrivateWeiboSubscribeList(long userId) {
        StringBuilder result = new StringBuilder();
        result.append("🐦 微博订阅列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        Properties properties = Newboy.INSTANCE.getProperties();
        boolean hasSubscription = false;
        
        try {
            WeiboApiService weiboApiService = new WeiboApiService();
            
            for (long groupId : properties.weibo_user_subscribe.keySet()) {
                if (!properties.weibo_user_subscribe.get(groupId).isEmpty()) {
                    hasSubscription = true;
                    result.append("\n🏠 群组：").append(groupId).append("\n");
                    
                    int count = 1;
                    for (long uid : properties.weibo_user_subscribe.get(groupId)) {
                        // 使用新的API服务获取用户昵称和最新微博时间
                        String name = "微博用户";
                        String lastUpdateTime = "暂无微博";
                        try {
                            String nickname = weiboApiService.getUserNickname(String.valueOf(uid));
                            if (nickname != null && !nickname.equals("未知用户")) {
                                name = nickname;
                            }
                            
                            // 获取最新微博时间
                            String latestTime = weiboApiService.getUserLatestWeiboTime(String.valueOf(uid));
                            if (latestTime != null && !latestTime.equals("暂无微博")) {
                                lastUpdateTime = latestTime;
                            }
                        } catch (Exception e) {
                            // 如果获取失败，使用默认名称
                        }
                        
                        result.append("  ").append(count).append(". ").append(name).append("\n");
                        result.append("     用户UID：").append(uid).append("\n");
                        result.append("     最后更新：").append(lastUpdateTime).append("\n");
                        
                        if (count < properties.weibo_user_subscribe.get(groupId).size()) {
                            result.append("\n");
                        }
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            result.append("\n❌ 获取订阅列表失败: ").append(e.getMessage());
        }
        
        if (!hasSubscription) {
            result.append("\n❌ 暂无订阅\n");
            result.append("💡 使用 /微博 关注 <用户UID> <群号> 添加订阅");
        }
        
        return new PlainText(result.toString());
    }

    // 添加私聊微博订阅
    private Message addPrivateWeiboSubscribe(String uidStr, String groupIdStr, UserMessageEvent event) {
        try {
            long uid = Long.parseLong(uidStr);
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            // 验证用户UID是否有效
            WeiboApiService weiboApiService = new WeiboApiService();
            String nickname = weiboApiService.getUserNickname(uidStr);
            if (nickname == null || nickname.equals("未知用户")) {
                return new PlainText("❌ 无效的用户UID，请检查后重试");
            }
            
            boolean success = Newboy.INSTANCE.getConfig().addWeiboUserSubscribe(uid, groupId);
            if (success) {
                // 添加到监控服务
                try {
                    WeiboHandler weiboHandler = Newboy.INSTANCE.getHandlerWeibo();
                    if (weiboHandler != null) {
                        // 通过API添加监控
                        JSONObject request = new JSONObject();
                        request.set("uid", uidStr);
                        request.set("groupIds", new String[]{groupIdStr});
                        // 这里可以调用WeiboHandler的API
                    }
                } catch (Exception e) {
                    // 如果添加监控失败，记录日志但不影响订阅添加
                    Newboy.INSTANCE.getLogger().error("添加微博监控失败: " + e.getMessage());
                }
                
                return new PlainText(String.format("✅ 成功为群 %d 添加微博订阅\n👤 用户：%s (UID: %d)", groupId, nickname, uid));
            } else {
                return new PlainText(String.format("❌ 群 %d 已订阅用户 %d", groupId, uid));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 参数格式错误，请检查用户UID和群号");
        } catch (Exception e) {
            return new PlainText("❌ 添加订阅失败: " + e.getMessage());
        }
    }

    // 移除私聊微博订阅
    private Message removePrivateWeiboSubscribe(String uidStr, String groupIdStr, UserMessageEvent event) {
        try {
            long uid = Long.parseLong(uidStr);
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            boolean success = Newboy.INSTANCE.getConfig().rmWeiboUserSubscribe(uid, groupId);
            if (success) {
                // 从监控服务移除
                try {
                    WeiboHandler weiboHandler = Newboy.INSTANCE.getHandlerWeibo();
                    if (weiboHandler != null) {
                        // 通过API移除监控
                        // 这里可以调用WeiboHandler的API
                    }
                } catch (Exception e) {
                    // 如果移除监控失败，记录日志但不影响订阅移除
                    Newboy.INSTANCE.getLogger().error("移除微博监控失败: " + e.getMessage());
                }
                
                return new PlainText(String.format("✅ 成功为群 %d 移除微博订阅\n👤 用户UID：%d", groupId, uid));
            } else {
                return new PlainText(String.format("❌ 群 %d 未订阅用户 %d", groupId, uid));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 参数格式错误，请检查用户UID和群号");
        } catch (Exception e) {
            return new PlainText("❌ 移除订阅失败: " + e.getMessage());
        }
    }

    // 获取私聊超话订阅列表
    private Message getPrivateSuperTopicSubscribeList(long userId) {
        StringBuilder result = new StringBuilder();
        result.append("🔥 超话订阅列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        Properties properties = Newboy.INSTANCE.getProperties();
        boolean hasSubscription = false;
        
        try {
            WeiboApiService weiboApiService = new WeiboApiService();
            
            for (long groupId : properties.weibo_superTopic_subscribe.keySet()) {
                if (!properties.weibo_superTopic_subscribe.get(groupId).isEmpty()) {
                    hasSubscription = true;
                    result.append("\n🏠 群组：").append(groupId).append("\n");
                    
                    int count = 1;
                    for (String topicId : properties.weibo_superTopic_subscribe.get(groupId)) {
                        // 尝试获取超话名称
                        String topicName = "超话";
                        try {
                            // 这里可以通过API获取超话名称，暂时使用ID
                            topicName = "超话ID: " + topicId;
                        } catch (Exception e) {
                            // 如果获取失败，使用默认名称
                        }
                        
                        result.append("  ").append(count).append(". ").append(topicName).append("\n");
                        result.append("     超话ID：").append(topicId).append("\n");
                        
                        if (count < properties.weibo_superTopic_subscribe.get(groupId).size()) {
                            result.append("\n");
                        }
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            result.append("\n❌ 获取订阅列表失败: ").append(e.getMessage());
        }
        
        if (!hasSubscription) {
            result.append("\n❌ 暂无订阅\n");
            result.append("💡 使用 /超话 关注 <超话ID> <群号> 添加订阅");
        }
        
        return new PlainText(result.toString());
    }

    // 添加私聊超话订阅
    private Message addPrivateSuperTopicSubscribe(String topicId, String groupIdStr, UserMessageEvent event) {
        try {
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            // 验证超话ID是否有效
            WeiboApiService weiboApiService = new WeiboApiService();
            try {
                // 这里可以验证超话ID的有效性
                // 暂时跳过验证，直接添加
            } catch (Exception e) {
                // 验证失败时的处理
            }
            
            boolean success = Newboy.INSTANCE.getConfig().addWeiboSTopicSubscribe(topicId, groupId);
            if (success) {
                // 添加到监控服务
                try {
                    WeiboHandler weiboHandler = Newboy.INSTANCE.getHandlerWeibo();
                    if (weiboHandler != null) {
                        // 通过API添加超话监控
                        JSONObject request = new JSONObject();
                        request.set("topicId", topicId);
                        request.set("groupIds", new String[]{groupIdStr});
                        // 这里可以调用WeiboHandler的API
                    }
                } catch (Exception e) {
                    // 如果添加监控失败，记录日志但不影响订阅添加
                    Newboy.INSTANCE.getLogger().error("添加超话监控失败: " + e.getMessage());
                }
                
                return new PlainText(String.format("✅ 成功为群 %d 添加超话订阅\n🔥 超话ID：%s", groupId, topicId));
            } else {
                return new PlainText(String.format("❌ 群 %d 已订阅超话 %s", groupId, topicId));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 参数格式错误，请检查超话ID和群号");
        } catch (Exception e) {
            return new PlainText("❌ 添加订阅失败: " + e.getMessage());
        }
    }

    // 移除私聊超话订阅
    private Message removePrivateSuperTopicSubscribe(String topicId, String groupIdStr, UserMessageEvent event) {
        try {
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            boolean success = Newboy.INSTANCE.getConfig().rmWeiboSTopicSubscribe(topicId, groupId);
            if (success) {
                // 从监控服务移除
                try {
                    WeiboHandler weiboHandler = Newboy.INSTANCE.getHandlerWeibo();
                    if (weiboHandler != null) {
                        // 通过API移除超话监控
                        // 这里可以调用WeiboHandler的API
                    }
                } catch (Exception e) {
                    // 如果移除监控失败，记录日志但不影响订阅移除
                    Newboy.INSTANCE.getLogger().error("移除超话监控失败: " + e.getMessage());
                }
                
                return new PlainText(String.format("✅ 成功为群 %d 移除超话订阅\n🔥 超话ID：%s", groupId, topicId));
            } else {
                return new PlainText(String.format("❌ 群 %d 未订阅超话 %s", groupId, topicId));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 参数格式错误，请检查超话ID和群号");
        } catch (Exception e) {
            return new PlainText("❌ 移除订阅失败: " + e.getMessage());
        }
    }

    // 处理私聊异步监控命令
    private Message handlePrivateAsyncMonitorCommand(String[] args, UserMessageEvent event) {
        if (args.length < 2 || args[1] == null || args[1].trim().isEmpty()) {
            StringBuilder help = new StringBuilder();
            help.append("📱 异步在线状态监控系统\n");
            help.append("━━━━━━━━━━━━━━━━━━━━\n");
            help.append("✅ 异步监控系统正在自动运行\n");
            help.append("📊 监控统计信息:\n");
            help.append(AsyncOnlineStatusMonitor.INSTANCE.getStatistics());
            help.append("\n\n💡 可用命令:\n");
            help.append("  /监控 添加 <成员名> <群号> - 为指定群添加成员监控\n");
            help.append("  /监控 移除 <成员名> <群号> - 为指定群移除成员监控\n");
            help.append("  /监控 列表 - 查看所有群的监控情况\n");
            help.append("  /在线 <成员名> - 查询成员在线状态\n");
            help.append("\n群聊命令:\n");
            help.append("  /监控添加 <成员名> - 添加监控\n");
            help.append("  /监控移除 <成员名> - 移除监控\n");
            help.append("  /监控列表 - 查看当前群监控列表");
            return new PlainText(help.toString());
        }

        switch (args[1]) {
            case "列表":
            case "list": {
                return getPrivateAsyncMonitorSubscribeList(event.getSender().getId());
            }
            case "添加":
            case "add": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/监控 添加 <成员名> <群号>\n📝 示例：/监控 添加 张三 987654321");
                }
                return addPrivateAsyncMonitorSubscribe(args[2], args[3], event);
            }
            case "移除":
            case "remove": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/监控 移除 <成员名> <群号>\n📝 示例：/监控 移除 张三 987654321");
                }
                return removePrivateAsyncMonitorSubscribe(args[2], args[3], event);
            }
            default:
                return new PlainText("❌ 未知操作\n📋 可用操作：添加、移除、列表");
        }
    }

    // 获取私聊异步监控订阅列表
    private Message getPrivateAsyncMonitorSubscribeList(long userId) {
        StringBuilder result = new StringBuilder();
        result.append("📱 异步在线状态监控订阅列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        AsyncOnlineStatusMonitor monitor = AsyncOnlineStatusMonitor.INSTANCE;
        
        result.append("\n📊 监控系统统计:\n");
        result.append(monitor.getStatistics());
        result.append("\n\n💡 使用方法:\n");
        result.append("  /监控 添加 <成员名> <群号> - 添加监控\n");
        result.append("  /监控 移除 <成员名> <群号> - 移除监控\n");
        result.append("  /监控 列表 - 查看此列表");
        
        return new PlainText(result.toString());
    }

    // 添加私聊异步监控订阅
    private Message addPrivateAsyncMonitorSubscribe(String memberName, String groupIdStr, UserMessageEvent event) {
        try {
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            AsyncOnlineStatusMonitor monitor = AsyncOnlineStatusMonitor.INSTANCE;
            String result = monitor.addSubscribedMember(groupId, memberName);
            
            return new PlainText(String.format("✅ 成功为群 %d 添加异步监控\n👤 成员：%s\n📊 %s", groupId, memberName, result));
        } catch (NumberFormatException e) {
            return new PlainText("❌ 群号格式错误，必须是数字");
        }
    }

    // 移除私聊异步监控订阅
    private Message removePrivateAsyncMonitorSubscribe(String memberName, String groupIdStr, UserMessageEvent event) {
        try {
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            AsyncOnlineStatusMonitor monitor = AsyncOnlineStatusMonitor.INSTANCE;
            String result = monitor.removeSubscribedMember(groupId, memberName);
            
            return new PlainText(String.format("✅ 成功为群 %d 移除异步监控\n👤 成员：%s\n📊 %s", groupId, memberName, result));
        } catch (NumberFormatException e) {
            return new PlainText("❌ 群号格式错误，必须是数字");
        }
    }

    public Message testPermission(UserMessageEvent event) {
        if (!Newboy.INSTANCE.getConfig().isAdmin(event.getSender().getId())) {
            return new PlainText("权限不足喵");
        }
        return null;
    }

    // 私聊抖音监控命令处理
    private Message handleDouyinMonitorCommand(String[] args, UserMessageEvent event) {
        // 权限检查
        if (!Newboy.INSTANCE.getConfig().isAdmin(event.getSender().getId())) {
            return new PlainText("权限不足喵");
        }

        switch (args[0]) {
            case "/抖音监控":
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
                        return startDouyinMonitoringPrivate();
                    case "停止":
                    case "stop":
                        return stopDouyinMonitoringPrivate();
                    default:
                        return new PlainText("❌ 未知操作\n💡 可用操作: 启动、停止");
                }
            case "/抖音状态":
                return getDouyinMonitoringStatus();
            case "/抖音用户":
                return getDouyinMonitoredUsersList();
            case "/抖音添加":
                if (args.length < 2) {
                    return new PlainText("❌ 请提供用户链接或用户ID\n💡 使用方法: /抖音添加 <用户链接或用户ID>");
                }
                return handleDouyinAddCommandPrivate(args[1]);
            case "/抖音删除":
                if (args.length < 2) {
                    return new PlainText("❌ 请提供用户ID\n💡 使用方法: /抖音删除 <用户ID>");
                }
                return handleDouyinRemoveCommandPrivate(args[1]);
            case "/抖音重启":
                return handleDouyinRestartCommandPrivate();
            default:
                return new PlainText("❌ 未知的抖音监控命令");
        }
    }

    // 私聊启动抖音监控服务
    private Message startDouyinMonitoringPrivate() {
        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            if (monitorService.isRunning()) {
                return new PlainText("✅ 抖音监控服务已在运行中");
            }

            monitorService.startMonitoring(10); // 默认10分钟检查间隔
            return new PlainText("✅ 抖音监控服务已启动");
        } catch (Exception e) {
            return new PlainText("❌ 启动抖音监控服务失败: " + e.getMessage());
        }
    }

    // 私聊停止抖音监控服务
    private Message stopDouyinMonitoringPrivate() {
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

    // 私聊添加抖音监控用户
    private Message handleDouyinAddCommandPrivate(String userInput) {
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

    // 私聊删除抖音监控用户
    private Message handleDouyinRemoveCommandPrivate(String secUserId) {
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

    // 私聊重启抖音监控服务
    private Message handleDouyinRestartCommandPrivate() {
        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            monitorService.stopMonitoring();
            // 使用异步延迟替代Thread.sleep，避免阻塞
            delayAsync(1000).thenRun(() -> {
                try {
                    monitorService.startMonitoring(10); // 默认10分钟检查间隔
                } catch (Exception e) {
                    logger.error("启动抖音监控服务失败: {}", e.getMessage());
                }
            });
            return new PlainText("✅ 抖音监控服务重启中...");
        } catch (Exception e) {
            return new PlainText("❌ 重启抖音监控服务失败: " + e.getMessage());
        }
    }

    // 私聊抖音订阅管理
    private Message handlePrivateDouyinCommand(String[] args, UserMessageEvent event) {
        if (args.length < 2) {
            return new PlainText("❌ 参数不足\n💡 使用方法：/抖音 <操作> [参数]\n📋 可用操作：关注、取消关注、关注列表");
        }

        switch (args[1]) {
            case "关注列表": {
                return getPrivateDouyinSubscribeList(event.getSender().getId());
            }
            case "关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/抖音 关注 <用户ID或分享链接> <群号>\n📝 示例：/抖音 关注 MS4wLjABAAAA... 987654321");
                }
                return addPrivateDouyinSubscribe(args[2], args[3], event);
            }
            case "取消关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/抖音 取消关注 <用户ID> <群号>\n📝 示例：/抖音 取消关注 MS4wLjABAAAA... 987654321");
                }
                return removePrivateDouyinSubscribe(args[2], args[3], event);
            }
            default:
                return new PlainText("❌ 未知操作\n📋 可用操作：关注、取消关注、关注列表");
        }
    }

    // 获取私聊抖音订阅列表
    private Message getPrivateDouyinSubscribeList(long userId) {
        StringBuilder result = new StringBuilder();
        result.append("📱 抖音订阅列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        Properties properties = Newboy.INSTANCE.getProperties();
        boolean hasSubscription = false;
        
        for (long groupId : properties.douyin_user_subscribe.keySet()) {
            if (!properties.douyin_user_subscribe.get(groupId).isEmpty()) {
                hasSubscription = true;
                result.append("\n🏠 群组：").append(groupId).append("\n");
                
                int count = 1;
                for (String secUserId : properties.douyin_user_subscribe.get(groupId)) {
                    // 尝试从监控服务获取用户昵称和最后更新时间
                    String name = "抖音用户";
                    String lastUpdateTime = "未知";
                    try {
                        DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
                        if (monitorService != null) {
                            // 确保用户在监控服务中
                            monitorService.addMonitorUser(secUserId);
                            
                            String nickname = monitorService.getMonitoredUserNickname(secUserId);
                            if (nickname != null && !nickname.isEmpty() && !nickname.equals("未知用户")) {
                                name = nickname;
                            }
                            
                            // 获取最后更新时间
                            DouyinMonitorService.UserMonitorInfo userInfo = monitorService.getMonitoredUserInfo(secUserId);
                            if (userInfo != null) {
                                if (userInfo.lastUpdateTime > 0) {
                                    LocalDateTime dateTime = LocalDateTime.ofInstant(
                                        java.time.Instant.ofEpochMilli(userInfo.lastUpdateTime), 
                                        ZoneId.systemDefault());
                                    lastUpdateTime = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                                } else {
                                    lastUpdateTime = "暂无作品";
                                }
                            }
                        }
                    } catch (Exception e) {
                        // 如果获取失败，使用默认名称
                    }
                    
                    result.append("  ").append(count).append(". ").append(name).append("\n");
                    result.append("     用户ID：").append(secUserId).append("\n");
                    result.append("     最后更新：").append(lastUpdateTime).append("\n");
                    
                    if (count < properties.douyin_user_subscribe.get(groupId).size()) {
                        result.append("\n");
                    }
                    count++;
                }
            }
        }
        
        if (!hasSubscription) {
            result.append("\n❌ 暂无订阅\n");
            result.append("💡 使用 /抖音 关注 <用户ID> <群号> 添加订阅");
        }
        
        return new PlainText(result.toString());
    }

    // 添加私聊抖音订阅
    private Message addPrivateDouyinSubscribe(String secUserIdStr, String groupIdStr, UserMessageEvent event) {
        try {
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            String secUserId = secUserIdStr;
            // 简化处理，直接使用输入的ID
            if (secUserId.contains("douyin.com")) {
                return new PlainText("❌ 请使用抖音监控命令处理分享链接");
            }
            
            boolean success = Newboy.INSTANCE.getConfig().addDouyinUserSubscribe(secUserId, groupId);
            if (success) {
                return new PlainText(String.format("✅ 成功为群 %d 添加抖音订阅\n👤 用户ID：%s", groupId, secUserId));
            } else {
                return new PlainText(String.format("❌ 群 %d 已订阅用户 %s", groupId, secUserId));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 群号格式错误，必须是数字");
        }
    }

    // 移除私聊抖音订阅
    private Message removePrivateDouyinSubscribe(String secUserId, String groupIdStr, UserMessageEvent event) {
        try {
            long groupId = Long.parseLong(groupIdStr);
            
            // 权限检查
            Message permissionTest = testPermission(groupId, event);
            if (permissionTest != null) {
                return permissionTest;
            }
            
            boolean success = Newboy.INSTANCE.getConfig().rmDouyinUserSubscribe(secUserId, groupId);
            if (success) {
                return new PlainText(String.format("✅ 成功为群 %d 移除抖音订阅\n👤 用户ID：%s", groupId, secUserId));
            } else {
                return new PlainText(String.format("❌ 群 %d 未订阅用户 %s", groupId, secUserId));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 群号格式错误，必须是数字");
        }
    }

    /* 函数工具 */
    private boolean testRoomIDWithServerID(long room_id, long server_id) {
        for (long i : Newboy.INSTANCE.getHandlerPocket48().getChannelIDBySeverID(server_id)) {
            if (i == room_id)
                return true;
        }
        return false;
    }

    private String informationFromPocketServerId(long server_id) throws Exception {
        String out = "Server_id: " + server_id + "\n";
        Long[] rooms = Newboy.INSTANCE.getHandlerPocket48().getChannelIDBySeverID(server_id);
        //无房间
        if (rooms.length == 0) {
            return out + "无房间\n";
        }
        //有房间
        else {
            for (Long i : rooms) {
                try {
                    Pocket48RoomInfo info = Newboy.INSTANCE.getHandlerPocket48().getRoomInfoByChannelID(i);
                    if (info != null) { //口袋48bug之已删除的房间也会保留，但无法获取信息，见陈琳Server的(3311605)都是小团体
                        out += (i != null) ? "(" + i + ")" + info.getRoomName() + "\n" : "";
                    }
                } catch (Exception e) {
                }
            }
            return out;
        }
    }
    
    /**
     * 获取微博用户信息
     * @param uid 用户UID
     * @return 用户信息消息
     */
    private Message getWeiboUserInfo(String uid) {
        try {
            String userInfo = Newboy.INSTANCE.getHandlerWeibo().getWeiboUserInfo(uid);
            return new PlainText("👤 微博用户信息\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    userInfo);
        } catch (Exception e) {
            return new PlainText("❌ 获取用户信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取微博监控状态
     * @return 监控状态消息
     */
    private Message getWeiboMonitorStatus() {
        try {
            String status = Newboy.INSTANCE.getHandlerWeibo().getWeiboMonitorStatus();
            return new PlainText("📊 微博监控状态\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    status);
        } catch (Exception e) {
            return new PlainText("❌ 获取监控状态失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取抖音监控状态
     * @return 监控状态消息
     */
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
            logger.error("获取抖音监控服务状态失败: {}", e.getMessage());
            return new PlainText("📱 抖音监控状态\n运行状态: ❌ 服务异常");
        }
    }
    
    /**
     * 获取抖音监控用户列表
     * @return 监控用户列表消息
     */
    private Message getDouyinMonitoredUsersList() {
        try {
            DouyinMonitorService monitorService = DouyinMonitorService.getInstance();
            return new PlainText(monitorService.getMonitoredUsersList());
        } catch (Exception e) {
            logger.error("获取抖音监控用户列表失败: {}", e.getMessage());
            return new PlainText("❌ 获取监控用户列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 异步延迟执行 - 使用统一延迟服务
     * @param delayMs 延迟毫秒数
     * @return CompletableFuture<Void>
     */
    private CompletableFuture<Void> delayAsync(int delayMs) {
        return net.luffy.util.delay.UnifiedDelayService.getInstance().delayAsync(delayMs);
    }

}
