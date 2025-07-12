package net.luffy.util;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
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

public class CommandOperator {

    public static CommandOperator INSTANCE;
    private final List<String> helps = new ArrayList<>();
    private final HashMap<Long, List<String>> localHelps = new HashMap<>();

    public CommandOperator() {
        INSTANCE = this;
        initHelp();
        //需自行编写指令执行方法，本operator的插件外部方法仅addHelp
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
                                try (ExternalResource avatarResource = ExternalResource.create(HttpRequest.get(avatar).execute().bodyStream())) {
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
                        return getHelp(2);
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
                        return getHelp(3);
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
                        return getHelp(4);
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
                return getHelp(-1, group);
        }

        return null;
    }

    public Message executePrivate(String message, UserMessageEvent event) {
        String[] args = splitPrivateCommand(message);

        //权限检测
        switch (args[0]) {
            case "/微店":
            case "/weidian":
            case "/欢迎": {
                try {
                    long groupId = Long.valueOf(args[1]);
                    Message test = testPermission(groupId, event);
                    if (test != null)
                        return test;
                } catch (Exception e) {
                    return args[0].equals("微店") ? getHelp(5) : getHelp(1);
                }
            }
        }

        switch (args[0]) {
            case "/帮助":
            case "/help":
            case "/?":
                return getHelp(-1, event.getSender().getId());
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
            case "/微店":
            case "/weidian": {
                long groupId;
                try {
                    groupId = Long.valueOf(args[1]);
                } catch (Exception e) {
                    return getHelp(5);
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
                            return getHelp(7);
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
            case "/欢迎": {
                long groupId;
                try {
                    groupId = Long.valueOf(args[1]);
                } catch (Exception e) {
                    return getHelp(1);
                }

                if (!args[2].equals("取消")) {
                    Newboy.INSTANCE.getConfig().setWelcome(args[2], groupId);
                    return new PlainText("设置成功");
                } else {
                    Newboy.INSTANCE.getConfig().closeWelcome(groupId);
                    return new PlainText("取消成功");
                }
            }
        }
        return null;
    }

    private void initHelp() {
        addHelp("【管理员指令】\n" //0
                + "(私聊) /清理\n");

        addHelp("【通用】\n" //1
                + "(私聊) /欢迎 <群id> 欢迎词(填写\"取消\"关闭)\n"
                + "/在线 <成员名称> - 查询成员在线状态\n"
                + "/监控添加 <成员ID> - 添加成员在线状态监控\n"
                + "/监控移除 <成员ID> - 移除成员在线状态监控\n"
                + "/监控列表 - 查看当前监控列表\n"
                + "/监控开关 - 开启/关闭监控功能(管理员)\n"
                + "/监控查询 <成员ID> - 查询指定成员在线状态\n"
                + "(私聊) /监控添加 <成员名称> - 私聊添加监控\n"
                + "(私聊) /监控移除 <成员名称> - 私聊移除监控\n"
                + "(私聊) /监控列表 - 私聊查看监控列表\n");

        addHelp("【口袋48相关】\n" //2
                + "/口袋 搜索 <在团小偶像或队伍名>\n"
                + "/口袋 查询 <ID>\n"
                + "/口袋 查询2 <Server_id>\n"
                + "/口袋 关注 <房间ID>\n"
                + "/口袋 取消关注 <房间ID>\n"
                + "/口袋 关注列表\n"
                + "/口袋 连接 <加密房间ID> <ServerId>\n"
                + "注1：关注步骤：搜索名字，关注房间\n"
                + "注2：不知道密码的加密房间如果知道Server_Id，通过连接功能连接以后照样可以关注并获取消息\n");



        addHelp("【微博超话相关】\n" //3
                + "/超话 关注 <超话ID>\n"
                + "/超话 取消关注 <超话ID>\n"
                + "/超话 关注列表\n");

        addHelp("【微博相关】\n" //4
                + "/微博 关注 <UID>\n"
                + "/微博 取消关注 <UID>\n"
                + "/微博 关注列表\n");

        addHelp("【微店相关】\n" //5
                + "(私聊)/微店 <群id> cookie <Cookie>\n"
                + "(私聊)/微店 <群id> 群播报\n"
                + "(私聊)/微店 <群id> 自动发货\n"
                + "(私聊)/微店 <群id> 全部发货\n"
                + "(私聊)/微店 <群id> 查 <商品id>\n"
                + "(私聊)/微店 <群id> # <商品id>\n"
                + "(私聊)/微店 <群id> 屏蔽 <商品id>\n"
                + "(私聊)/微店 <群id> 全部\n"
                + "(私聊)/微店 <群id> 关闭\n"
                + "注：\"#\"指令的意思是切换一个商品的普链/特殊链形质，特殊链会实时播报\n"
                + "注：\"查询\"#指令可以获取榜单\n");
    }

    public Message getHelp(int id) {
        return getHelp(id, 0);
    }

    public Message getHelp(int id, long contactId) {
        if (id < getSize() && id > -1)
            return new PlainText(helps.get(id));

        else {
            String a = "";
            for (String help : this.helps) {
                a += help;
            }

            String b = getLocalHelp(contactId);
            return new PlainText(b == null ? a : a + b);
        }
    }

    public String getLocalHelp(long contactId) {
        if (!this.localHelps.containsKey(contactId))
            return null;

        String a = "";
        for (String help : this.localHelps.get(contactId)) {
            a += help;
        }
        return a;
    }

    public int getSize() {
        return this.helps.size();
    }

    public void addHelp(String help) {
        this.helps.add(help);
    }

    public void addLocalHelp(long contactId, String help) {
        if (!this.localHelps.containsKey(contactId))
            this.localHelps.put(contactId, new ArrayList<>());
        this.localHelps.get(contactId).add(help);
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
