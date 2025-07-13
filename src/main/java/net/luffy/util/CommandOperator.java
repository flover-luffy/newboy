package net.luffy.util;

// HttpRequest已迁移到异步处理器
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import net.luffy.handler.AsyncWebHandlerBase;
import net.luffy.handler.Pocket48Handler;
import net.luffy.handler.WeidianHandler;
import net.luffy.handler.Xox48Handler;
import net.luffy.util.OnlineStatusMonitor;
import net.luffy.model.Pocket48RoomInfo;
import net.luffy.model.WeidianCookie;
import net.luffy.model.WeidianItem;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.event.events.UserMessageEvent;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.PlainText;
import net.mamoe.mirai.utils.ExternalResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class CommandOperator extends AsyncWebHandlerBase {

    public static CommandOperator INSTANCE;

    public CommandOperator() {
        INSTANCE = this;
        //需自行编写指令执行方法
    }

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
                                String out = "";
                                int count = 1;
                                for (Object liveRoom : Newboy.INSTANCE.getHandlerPocket48().getLiveList()) {
                                    JSONObject liveRoom1 = JSONUtil.parseObj(liveRoom);
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
                                String out = "";
                                int count = 1;
                                for (Object liveRoom : Newboy.INSTANCE.getHandlerPocket48().getRecordList()) {
                                    JSONObject liveRoom1 = JSONUtil.parseObj(liveRoom);
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
                                    out.append("❌ 未找到相关结果\n");
                                    out.append("💡 提示：仅支持搜索在团小偶像/队伍名");
                                    return new PlainText(out.toString());
                                }

                                int count = 1;
                                for (Object server_ : servers) {
                                    JSONObject server = JSONUtil.parseObj(server_);
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
                                if (info == null)
                                    return new PlainText("❌ 用户不存在");

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
                                            fan.append("\n").append(i + 1).append(". ").append(JSONUtil.parseObj(fans[i]).getStr("nickName"));
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
                                        return new PlainText("❌ Server_id不存在或房间信息获取失败");
                                    }
                                }
                                return new PlainText("❌ 请输入合法的Server_id");
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

                                if (Newboy.INSTANCE.getConfig().rmPocket48RoomSubscribe(Long.valueOf(args[2]), group)) {
                                    Pocket48RoomInfo roomInfo = Newboy.INSTANCE.getHandlerPocket48().getRoomInfoByChannelID(Long.valueOf(args[2]));
                                    if (roomInfo != null) {
                                        String roomName = roomInfo.getRoomName();
                                        String ownerName = roomInfo.getOwnerName();
                                        return new PlainText("本群取消关注：" + roomName + "(" + ownerName + ")");
                                    } else return new PlainText("本群取消关注：未知房间");
                                } else
                                    return new PlainText("本群没有关注此房间捏~");

                            }
                        }
                    case 4:
                        if (args[1].equals("连接")) {
                            long room_id = Long.valueOf(args[2]);
                            long server_id = Long.valueOf(args[3]);
                            if (testRoomIDWithServerID(room_id, server_id)) {
                                if (Newboy.INSTANCE.getConfig().addRoomIDConnection(room_id, server_id))
                                    return new PlainText("连接成功");
                                else
                                    return new PlainText("建立过此连接");
                            } else
                                return new PlainText("您输入的ServerId并不包含此RoomId");
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
                                if (a == null)
                                    return new PlainText("超话id不存在。");
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
                            for (long id : Newboy.INSTANCE.getProperties().weibo_user_subscribe.get(group)) {
                                String name = Newboy.INSTANCE.getHandlerWeibo().getUserName(id);
                                out.append(count).append(". ").append(name).append("\n");
                                out.append("   用户ID：").append(id).append("\n");
                                
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
                    return new PlainText("❌ 请输入成员名称\n💡 使用方法：/监控添加 成员名称");
                }
                
                String memberName = args[1].trim();
                String result = OnlineStatusMonitor.INSTANCE.addMonitor(group, memberName);
                return new PlainText(result);
            }
            case "/监控移除":
            case "/monitor_remove": {
                if (args.length < 2 || args[1] == null || args[1].trim().isEmpty()) {
                    return new PlainText("❌ 请输入成员名称\n💡 使用方法：/监控移除 成员名称");
                }
                
                String memberName = args[1].trim();
                String result = OnlineStatusMonitor.INSTANCE.removeMonitor(group, memberName);
                return new PlainText(result);
            }
            case "/监控列表":
            case "/monitor_list": {
                String result = OnlineStatusMonitor.INSTANCE.getMonitorList(group);
                return new PlainText(result);
            }
            case "/监控开关":
            case "/monitor_toggle": {
                if (!Newboy.INSTANCE.getConfig().isAdmin(g, senderID))
                    return new PlainText("权限不足喵");
                    
                boolean enabled = OnlineStatusMonitor.INSTANCE.toggleMonitoring();
                return new PlainText(enabled ? "✅ 在线状态监控已开启" : "❌ 在线状态监控已关闭");
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
            case "/weidian":
            // 进群欢迎功能已移除
            /*
            case "/欢迎": {
                try {
                    long groupId = Long.valueOf(args[1]);
                    Message test = testPermission(groupId, event);
                    if (test != null)
                        return test;
                } catch (Exception e) {
                    return getCategorizedHelp(event.getSender().getId());
                }
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
            case "/监控添加":
            case "/monitor_add": {
                if (args.length < 2 || args[1] == null || args[1].trim().isEmpty()) {
                    return new PlainText("❌ 请输入成员名称\n💡 使用方法：/监控添加 成员名称");
                }
                
                String memberName = args[1].trim();
                String result = OnlineStatusMonitor.INSTANCE.addMonitor(event.getSender().getId(), memberName);
                return new PlainText(result);
            }
            case "/监控移除":
            case "/monitor_remove": {
                if (args.length < 2 || args[1] == null || args[1].trim().isEmpty()) {
                    return new PlainText("❌ 请输入成员名称\n💡 使用方法：/监控移除 成员名称");
                }
                
                String memberName = args[1].trim();
                String result = OnlineStatusMonitor.INSTANCE.removeMonitor(event.getSender().getId(), memberName);
                return new PlainText(result);
            }
            case "/监控列表":
            case "/monitor_list": {
                String result = OnlineStatusMonitor.INSTANCE.getMonitorList(event.getSender().getId());
                return new PlainText(result);
            }
            case "/口袋":
            case "/pocket": {
                return handlePrivatePocket48Command(args, event);
            }
            case "/微博":
            case "/weibo": {
                return handlePrivateWeiboCommand(args, event);
            }
            case "/超话":
            case "/supertopic": {
                return handlePrivateSuperTopicCommand(args, event);
            }
            case "/微店":
            case "/weidian": {
                long groupId;
                try {
                    groupId = Long.valueOf(args[1]);
                } catch (Exception e) {
                    return getCategorizedHelp(event.getSender().getId());
                }

                if (args[2].startsWith("cookie")) {
                    if (args[2].contains(" ")) {
                        String cookie = args[2].substring(args[2].indexOf(" ") + 1);
                        Newboy.INSTANCE.getConfig().setWeidianCookie(cookie, groupId);
                        WeidianCookie cookie1 = Newboy.INSTANCE.getProperties().weidian_cookie.get(groupId);
                        return new PlainText("设置Cookie成功，当前自动发货为：" + (cookie1.autoDeliver ? "开启" : "关闭") + "。您可以通过\"/微店 " + groupId + " 自动发货\"切换");
                    }
                    return new PlainText("请输入Cookie");
                }

                if (!Newboy.INSTANCE.getProperties().weidian_cookie.containsKey(groupId)) {
                    return new PlainText("该群未设置Cookie");
                } else {
                    String[] argsIn = args[2].split(" ");
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
                                    boolean pre = cookie.autoDeliver;
                                    cookie.autoDeliver = true;
                                    weidian.getOrderList(cookie);
                                    cookie.autoDeliver = pre;
                                    return new PlainText("全部订单发货成功(不包括包含屏蔽商品的订单)");
                                }
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

                                    long id = Long.valueOf(argsIn[1]);
                                    WeidianItem item = weidian.searchItem(cookie, id);
                                    if (item == null) {
                                        return new PlainText("❌ 未找到该商品\n\n💡 提示：您可以使用 \"/微店 " + groupId + " 全部\" 获取商品列表");
                                    } else {
                                        StringBuilder itemInfo = new StringBuilder();
                                        itemInfo.append("🛍️ 商品详情\n");
                        
                                        String status = cookie.shieldedItem.contains(id) ? "🚫 屏蔽" : (cookie.highlightItem.contains(id) ? "🔗 特殊链" : "🔗 普链");
                                        itemInfo.append("状态：").append(status).append("\n");
                                        itemInfo.append("商品ID：").append(item.id).append("\n");
                                        itemInfo.append("商品名称：").append(item.name).append("\n");
                        
                                        return new PlainText(itemInfo.toString())
                                                .plus(Newboy.INSTANCE.getHandlerWeidianSender().executeItemMessages(item, event.getBot().getGroup(groupId), 0).getMessage());
                                    }
                                }
                            }
                        default:
                            return getCategorizedHelp(event.getSender().getId());
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
                        log.plus("口袋48关注失效群: " + match + "个\n");
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
                        log.plus("微博关注失效群: " + match + "个\n");
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
                        log.plus("微店播报失效群: " + match + "个\n");
                        match = 0;
                    }
                }
                //getHelp(0);
            }
            // 进群欢迎功能已移除
            /*
            case "/欢迎": {
                long groupId;
                try {
                    groupId = Long.valueOf(args[1]);
                } catch (Exception e) {
                    return getCategorizedHelp(event.getSender().getId());
                }

                if (!args[2].equals("取消")) {
                    Newboy.INSTANCE.getConfig().setWelcome(args[2], groupId);
                    return new PlainText("设置成功");
                } else {
                    Newboy.INSTANCE.getConfig().closeWelcome(groupId);
                    return new PlainText("取消成功");
                }
            }
            */
        }
        return null;
    }





    private String[] splitPrivateCommand(String command) {
        String[] out = new String[3];
        int i = 0;
        for (; i < 2; i++) {
            if (command.contains(" ")) {
                out[i] = command.substring(0, command.indexOf(" "));
                command = command.substring(command.indexOf(" ") + 1);
            } else {
                break;
            }
        }
        out[i] = command;
        
        // 确保数组中的空位置为空字符串而不是null
        for (int j = 0; j < out.length; j++) {
            if (out[j] == null) {
                out[j] = "";
            }
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
            return new PlainText("❌ 参数不足\n💡 使用方法：/微博 <操作> [参数]\n📋 可用操作：关注、取消关注、关注列表");
        }

        switch (args[1]) {
            case "关注列表": {
                return getPrivateWeiboSubscribeList(event.getSender().getId());
            }
            case "关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/微博 关注 <用户UID> <群号>\n📝 示例：/微博 关注 1234567890 987654321");
                }
                return addPrivateWeiboSubscribe(args[2], args[3], event);
            }
            case "取消关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/微博 取消关注 <用户UID> <群号>\n📝 示例：/微博 取消关注 1234567890 987654321");
                }
                return removePrivateWeiboSubscribe(args[2], args[3], event);
            }
            default:
                return new PlainText("❌ 未知操作\n📋 可用操作：关注、取消关注、关注列表");
        }
    }

    // 私聊超话订阅管理
    private Message handlePrivateSuperTopicCommand(String[] args, UserMessageEvent event) {
        if (args.length < 2) {
            return new PlainText("❌ 参数不足\n💡 使用方法：/超话 <操作> [参数]\n📋 可用操作：关注、取消关注、关注列表");
        }

        switch (args[1]) {
            case "关注列表": {
                return getPrivateSuperTopicSubscribeList(event.getSender().getId());
            }
            case "关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/超话 关注 <超话ID> <群号>\n📝 示例：/超话 关注 abc123 987654321");
                }
                return addPrivateSuperTopicSubscribe(args[2], args[3], event);
            }
            case "取消关注": {
                if (args.length < 4) {
                    return new PlainText("❌ 参数不足\n💡 使用方法：/超话 取消关注 <超话ID> <群号>\n📝 示例：/超话 取消关注 abc123 987654321");
                }
                return removePrivateSuperTopicSubscribe(args[2], args[3], event);
            }
            default:
                return new PlainText("❌ 未知操作\n📋 可用操作：关注、取消关注、关注列表");
        }
    }

    // 分类帮助信息
    public Message getCategorizedHelp(long contactId) {
        StringBuilder help = new StringBuilder();
        help.append("🤖 Newboy 帮助菜单\n");
        help.append("━━━━━━━━━━━━━━━━━━━━\n\n");
        
        help.append("📱 口袋48功能\n");
        help.append("群聊命令：\n");
        help.append("  /口袋 搜索 <关键词> - 搜索成员/队伍\n");
        help.append("  /口袋 查询 <用户ID> - 查询用户信息\n");
        help.append("  /口袋 关注 <房间ID> - 关注房间\n");
        help.append("  /口袋 取消关注 <房间ID> - 取消关注\n");
        help.append("  /口袋 关注列表 - 查看关注列表\n");
        help.append("私聊命令：\n");
        help.append("  /口袋 关注 <房间ID> <群号> - 为指定群添加关注\n");
        help.append("  /口袋 取消关注 <房间ID> <群号> - 为指定群取消关注\n");
        help.append("  /口袋 关注列表 - 查看所有群的关注情况\n\n");
        
        help.append("🐦 微博功能\n");
        help.append("群聊命令：\n");
        help.append("  /微博 关注 <用户UID> - 关注微博用户\n");
        help.append("  /微博 取消关注 <用户UID> - 取消关注\n");
        help.append("  /微博 关注列表 - 查看关注列表\n");
        help.append("私聊命令：\n");
        help.append("  /微博 关注 <用户UID> <群号> - 为指定群添加关注\n");
        help.append("  /微博 取消关注 <用户UID> <群号> - 为指定群取消关注\n");
        help.append("  /微博 关注列表 - 查看所有群的关注情况\n\n");
        
        help.append("🔥 超话功能\n");
        help.append("群聊命令：\n");
        help.append("  /超话 关注 <超话ID> - 关注超话\n");
        help.append("  /超话 取消关注 <超话ID> - 取消关注\n");
        help.append("  /超话 关注列表 - 查看关注列表\n");
        help.append("私聊命令：\n");
        help.append("  /超话 关注 <超话ID> <群号> - 为指定群添加关注\n");
        help.append("  /超话 取消关注 <超话ID> <群号> - 为指定群取消关注\n");
        help.append("  /超话 关注列表 - 查看所有群的关注情况\n\n");
        
        help.append("🛍️ 微店功能\n");
        help.append("私聊命令：\n");
        help.append("  /微店 <群号> cookie <Cookie> - 设置微店Cookie\n");
        help.append("  /微店 <群号> 全部 - 查看商品列表\n");
        help.append("  /微店 <群号> 自动发货 - 切换自动发货\n");
        help.append("  /微店 <群号> 群播报 - 切换群播报\n");
        help.append("  /微店 <群号> 查 <商品ID> - 查看商品详情\n\n");
        
        help.append("👥 在线状态监控\n");
        help.append("群聊命令：\n");
        help.append("  /newboy monitor - 查看监控帮助\n");
        help.append("私聊命令：\n");
        help.append("  /监控添加 <成员名> - 添加监控\n");
        help.append("  /监控移除 <成员名> - 移除监控\n");
        help.append("  /监控列表 - 查看监控列表\n");
        help.append("  /在线 <成员名> - 查询在线状态\n\n");
        
        help.append("🔧 管理功能\n");
        help.append("私聊命令（管理员）：\n");
        // 进群欢迎功能已移除
        help.append("  /清理 - 清理失效群配置\n\n");
        
        help.append("💡 提示：\n");
        help.append("• 私聊命令可以无感添加配置，不打扰群组\n");
        help.append("• 管理员可以通过私聊为任意群配置功能\n");
        help.append("• 使用 /帮助 随时查看此帮助信息");
        
        return new PlainText(help.toString());
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
            out.append("\n❌ 未找到相关结果\n");
            out.append("💡 提示：仅支持搜索在团小偶像/队伍名");
            return new PlainText(out.toString());
        }

        int count = 1;
        for (Object server_ : servers) {
            JSONObject server = JSONUtil.parseObj(server_);
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
            return new PlainText("❌ 用户ID格式错误，请输入数字");
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
            return new PlainText("❌ 参数格式错误，房间ID和群号必须是数字");
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
                return new PlainText(String.format("✅ 成功为群 %d 移除口袋48订阅\n🆔 房间ID：%d", groupId, roomId));
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
        
        for (long groupId : properties.weibo_user_subscribe.keySet()) {
            if (!properties.weibo_user_subscribe.get(groupId).isEmpty()) {
                hasSubscription = true;
                result.append("\n🏠 群组：").append(groupId).append("\n");
                
                int count = 1;
                for (long uid : properties.weibo_user_subscribe.get(groupId)) {
                    result.append("  ").append(count).append(". 用户UID: ").append(uid).append("\n");
                    count++;
                }
            }
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
            
            boolean success = Newboy.INSTANCE.getConfig().addWeiboUserSubscribe(uid, groupId);
            if (success) {
                return new PlainText(String.format("✅ 成功为群 %d 添加微博订阅\n👤 用户UID：%d", groupId, uid));
            } else {
                return new PlainText(String.format("❌ 群 %d 已订阅用户 %d", groupId, uid));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 参数格式错误，用户UID和群号必须是数字");
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
                return new PlainText(String.format("✅ 成功为群 %d 移除微博订阅\n👤 用户UID：%d", groupId, uid));
            } else {
                return new PlainText(String.format("❌ 群 %d 未订阅用户 %d", groupId, uid));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 参数格式错误，用户UID和群号必须是数字");
        }
    }

    // 获取私聊超话订阅列表
    private Message getPrivateSuperTopicSubscribeList(long userId) {
        StringBuilder result = new StringBuilder();
        result.append("🔥 超话订阅列表\n");
        result.append("━━━━━━━━━━━━━━━━━━━━\n");
        
        Properties properties = Newboy.INSTANCE.getProperties();
        boolean hasSubscription = false;
        
        for (long groupId : properties.weibo_superTopic_subscribe.keySet()) {
            if (!properties.weibo_superTopic_subscribe.get(groupId).isEmpty()) {
                hasSubscription = true;
                result.append("\n🏠 群组：").append(groupId).append("\n");
                
                int count = 1;
                for (String topicId : properties.weibo_superTopic_subscribe.get(groupId)) {
                    result.append("  ").append(count).append(". 超话ID: ").append(topicId).append("\n");
                    count++;
                }
            }
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
            
            boolean success = Newboy.INSTANCE.getConfig().addWeiboSTopicSubscribe(topicId, groupId);
            if (success) {
                return new PlainText(String.format("✅ 成功为群 %d 添加超话订阅\n🔥 超话ID：%s", groupId, topicId));
            } else {
                return new PlainText(String.format("❌ 群 %d 已订阅超话 %s", groupId, topicId));
            }
        } catch (NumberFormatException e) {
            return new PlainText("❌ 群号格式错误，必须是数字");
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
                return new PlainText(String.format("✅ 成功为群 %d 移除超话订阅\n🔥 超话ID：%s", groupId, topicId));
            } else {
                return new PlainText(String.format("❌ 群 %d 未订阅超话 %s", groupId, topicId));
            }
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
}
