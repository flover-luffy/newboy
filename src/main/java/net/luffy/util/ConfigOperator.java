package net.luffy.util;

import cn.hutool.core.io.FileUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.setting.Setting;
import net.luffy.Newboy;
import net.luffy.model.Pocket48Subscribe;
import net.luffy.model.WeidianCookie;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.MemberPermission;
import net.mamoe.mirai.contact.NormalMember;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ConfigOperator {

    private Setting setting;
    private Properties properties;

    public void load(Properties properties) {
        this.properties = properties;
        File file = properties.configData;
        if (!file.exists()) {
            FileUtil.touch(file);
            Setting setting = new Setting(file, StandardCharsets.UTF_8, false);
            
            // ========== 基础配置 ==========
            setting.set("enable", "true");  // 是否启用插件
            setting.set("save_login", "false");  // 是否保存登录状态
            setting.set("ylg", "false");  // 是否启用YLG功能
            setting.set("admins", "");  // 管理员QQ号，多个用逗号分隔
            setting.set("secureGroup", "");  // 安全群组，多个用逗号分隔
            
            // ========== 欢迎语配置 ==========
            setting.set("welcome", "[]");  // 群欢迎语配置，格式: [{\"1\":群号,\"2\":\"欢迎语\"}]
            
            // ========== 定时任务配置 ==========
            setting.setByGroup("schedule", "pocket48", "* * * * *");  // 口袋48检查频率(每分钟)
            setting.setByGroup("schedule", "weibo", "*/5 * * * *");  // 微博检查频率(每5分钟)
            setting.setByGroup("schedule_order", "weidian", "*/10 * * * *");  // 微店订单检查频率(每10分钟)
            setting.setByGroup("schedule_item", "weidian", "*/10 * * * *");  // 微店商品检查频率(每10分钟)
            setting.setByGroup("schedule", "onlineStatus", "*/2 * * * *");  // 在线状态检查频率(每2分钟)
            
            // ========== 口袋48配置 ==========
            setting.setByGroup("account", "pocket48", "");  // 口袋48账号
            setting.setByGroup("password", "pocket48", "");  // 口袋48密码
            setting.setByGroup("token", "pocket48", "");  // 口袋48 Token(推荐使用)
            setting.setByGroup("subscribe", "pocket48", "[]");  // 口袋48订阅配置
            
            // ========== 微博配置 ==========
            setting.setByGroup("subscribe", "weibo", "[]");  // 微博订阅配置
            
            // ========== 微店配置 ==========
            setting.setByGroup("shops", "weidian", "[]");  // 微店配置
            
            // ========== 在线状态监控配置 ==========
            setting.set("onlineStatus_enable", "false");  // 是否启用在线状态监控
            setting.setByGroup("subscribe", "onlineStatus", "[]");  // 在线状态监控订阅配置
            
            setting.store();
            Newboy.INSTANCE.getLogger().info("首次加载已生成 config/net.luffy.newboy/config.setting 配置文件");
            Newboy.INSTANCE.getLogger().info("请根据需要修改配置文件后重启插件");
            Newboy.INSTANCE.getLogger().info("配置文件路径: config/net.luffy.newboy/config.setting");
        }

        this.setting = new Setting(file, StandardCharsets.UTF_8, false);
        init();
    }

    public Setting getSetting() {
        return setting;
    }

    public void init() {
        properties.enable = setting.getBool("enable", true);
        properties.save_login = setting.getBool("save_login", false);
        properties.ylg = setting.getBool("ylg", false);
        properties.admins = setting.getStrings("admins");
        properties.secureGroup = setting.getStrings("secureGroup");
        if (properties.admins == null)
            properties.admins = new String[]{};
        if (properties.secureGroup == null)
            properties.secureGroup = new String[]{};

        for (Object a :
                JSONUtil.parseArray(setting.getStr("welcome", "[]")).toArray()) {
            JSONObject welcome = JSONUtil.parseObj(a);
            properties.welcome.put(
                    welcome.getLong("1"),
                    welcome.getStr("2")
            );
        }

        //schedule pattern
        properties.pocket48_pattern = setting.getStr("schedule", "pocket48", "* * * * *");

        properties.weibo_pattern = setting.getStr("schedule", "weibo", "*/5 * * * *");
        properties.weidian_pattern_order = setting.getStr("schedule_order", "weidian", "*/2 * * * *");
        properties.weidian_pattern_item = setting.getStr("schedule_item", "weidian", "*/5 * * * *");
        properties.onlineStatus_pattern = setting.getStr("schedule", "onlineStatus", "*/2 * * * *");
        
        //在线状态监控
        properties.onlineStatus_enable = setting.getBool("onlineStatus_enable", false);

        //口袋48
        properties.pocket48_account = setting.getStr("account", "pocket48", "");
        properties.pocket48_password = setting.getStr("password", "pocket48", "");
        properties.pocket48_token = setting.getStr("token", "pocket48", "");

        for (Object a :
                JSONUtil.parseArray(setting.getByGroup("subscribe", "pocket48")).toArray()) {
            JSONObject sub = JSONUtil.parseObj(a);
            @SuppressWarnings("unchecked")
            List<Long> rooms = (List<Long>) sub.getBeanList("roomSubs", Long.class);
            @SuppressWarnings("unchecked")
            List<Long> stars = (List<Long>) sub.getBeanList("starSubs", Long.class);

            properties.pocket48_subscribe
                    .put(sub.getLong("qqGroup"),
                            new Pocket48Subscribe(
                                    sub.getBool("showAtOne", true),
                                    rooms == null ? new ArrayList<>() : rooms,
                                    stars == null ? new ArrayList<>() : stars
                            ));
        }

        for (Object a :
                JSONUtil.parseArray(setting.getByGroup("roomConnection", "pocket48")).toArray()) {
            JSONObject sid = JSONUtil.parseObj(a);
            properties.pocket48_serverID.put(sid.getLong("roomID"), sid.getLong("serverID"));
        }



        //微博
        for (Object a :
                JSONUtil.parseArray(setting.getByGroup("subscribe", "weibo")).toArray()) {
            JSONObject subs = JSONUtil.parseObj(a);

            long g = subs.getLong("qqGroup");
            @SuppressWarnings("unchecked")
            List<Long> userSubs = (List<Long>) subs.getBeanList("userSubs", Long.class);
            properties.weibo_user_subscribe.put(g, userSubs == null ? new ArrayList<>() : userSubs);

            @SuppressWarnings("unchecked")
            List<String> sTopicSubs = (List<String>) subs.getBeanList("superTopicSubs", String.class);
            properties.weibo_superTopic_subscribe.put(g, sTopicSubs == null ? new ArrayList<>() : sTopicSubs);

        }

        //微店
        for (Object a :
                JSONUtil.parseArray(setting.getByGroup("shops", "weidian")).toArray()) {
            JSONObject shop = JSONUtil.parseObj(a);

            long g = shop.getLong("qqGroup");
            String cookie = shop.getStr("cookie", "");
            boolean autoDeliver = shop.getBool("autoDeliver", false);
            boolean doBroadCast = shop.getBool("doBroadCast", true);
            List<Long> highlight = shop.getBeanList("highlight", Long.class);
            List<Long> shielded = shop.getBeanList("shielded", Long.class);
            properties.weidian_cookie.put(g, WeidianCookie.construct(cookie, autoDeliver, doBroadCast,
                    highlight == null ? new ArrayList<>() : highlight,
                    shielded == null ? new ArrayList<>() : shielded));

        }
        
        //在线状态监控
        for (Object a :
                JSONUtil.parseArray(setting.getByGroup("subscribe", "onlineStatus")).toArray()) {
            JSONObject sub = JSONUtil.parseObj(a);
            
            long g = sub.getLong("qqGroup");
            List<String> memberSubs = sub.getBeanList("memberSubs", String.class);
            properties.onlineStatus_subscribe.put(g, memberSubs == null ? new ArrayList<>() : memberSubs);
        }
    }

    //修改配置并更新缓存的方法
    public void swch(boolean on) {
        setting.set("enable", String.valueOf(on));
        setting.store();
        properties.enable = setting.getBool("enable");
    }

    public boolean setWelcome(String welcome, long group) {
        properties.welcome.put(group, welcome);
        saveWelcome();
        return true;
    }

    public boolean closeWelcome(long group) {
        properties.welcome.remove(group);
        saveWelcome();
        return true;
    }

    public boolean setAndSaveToken(String token) {
        properties.pocket48_token = token;
        setting.setByGroup("token", "pocket48", token);
        setting.store();
        return true;
    }

    public String getToken() {
        return setting.getStr("token", "pocket48", properties.pocket48_token);
    }

    public boolean addPocket48RoomSubscribe(long room_id, long group) {
        if (!properties.pocket48_subscribe.containsKey(group)) {
            properties.pocket48_subscribe.put(group, new Pocket48Subscribe(
                    true, new ArrayList<>(), new ArrayList<>()
            ));
        }

        if (properties.pocket48_subscribe.get(group).getRoomIDs().contains(room_id))
            return false;

        properties.pocket48_subscribe.get(group).getRoomIDs().add(room_id);
        savePocket48SubscribeConfig();
        return true;
    }

    public boolean rmPocket48RoomSubscribe(long room_id, long group) {
        if (!properties.pocket48_subscribe.get(group).getRoomIDs().contains(room_id))
            return false;

        properties.pocket48_subscribe.get(group).getRoomIDs().remove(room_id);
        savePocket48SubscribeConfig();
        return true;
    }

    public boolean addRoomIDConnection(long room_id, long sever_id) {
        if (properties.pocket48_serverID.containsKey(room_id))
            return false;

        properties.pocket48_serverID.put(room_id, sever_id);
        savePocket48RoomIDConnectConfig();
        return true;
    }

    public boolean rmRoomIDConnection(long room_id, long sever_id) {
        if (!properties.pocket48_serverID.containsKey(room_id))
            return false;

        properties.pocket48_serverID.remove(room_id, sever_id);
        savePocket48RoomIDConnectConfig();
        return true;
    }




    public boolean addWeiboUserSubscribe(long id, long group) {
        if (!properties.weibo_user_subscribe.containsKey(group)) {
            properties.weibo_user_subscribe.put(group, new ArrayList<>());
            properties.weibo_superTopic_subscribe.put(group, new ArrayList<>());
        }

        if (properties.weibo_user_subscribe.get(group).contains(id))
            return false;

        properties.weibo_user_subscribe.get(group).add(id);
        saveWeiboConfig();
        return true;
    }

    public boolean rmWeiboUserSubscribe(long id, long group) {
        if (!properties.weibo_user_subscribe.get(group).contains(id))
            return false;

        properties.weibo_user_subscribe.get(group).remove(id);
        saveWeiboConfig();
        return true;
    }

    public boolean addWeiboSTopicSubscribe(String id, long group) {
        if (!properties.weibo_user_subscribe.containsKey(group)) {
            properties.weibo_user_subscribe.put(group, new ArrayList<>());
            properties.weibo_superTopic_subscribe.put(group, new ArrayList<>());
        }

        if (properties.weibo_superTopic_subscribe.get(group).contains(id))
            return false;

        properties.weibo_superTopic_subscribe.get(group).add(id);
        saveWeiboConfig();
        return true;
    }

    public boolean rmWeiboSTopicSubscribe(String id, long group) {
        if (!properties.weibo_superTopic_subscribe.get(group).contains(id))
            return false;

        properties.weibo_superTopic_subscribe.get(group).remove(id);
        saveWeiboConfig();
        return true;
    }

    public boolean setWeidianCookie(String cookie, long group) {
        boolean autoDeliver = false;
        boolean doBroadcast = true;
        List<Long> highlightItem = new ArrayList<>();
        List<Long> shieldItem = new ArrayList<>();
        if (properties.weidian_cookie.containsKey(group)) {
            autoDeliver = properties.weidian_cookie.get(group).autoDeliver;
            doBroadcast = properties.weidian_cookie.get(group).doBroadcast;
            highlightItem = properties.weidian_cookie.get(group).highlightItem;
            shieldItem = properties.weidian_cookie.get(group).shieldedItem;
        }
        properties.weidian_cookie.put(group, WeidianCookie.construct(cookie, autoDeliver, doBroadcast, highlightItem, shieldItem));
        saveWeidianConfig();
        return true;
    }

    public int switchWeidianAutoDeliver(long group) {
        if (!properties.weidian_cookie.containsKey(group))
            return -1;

        WeidianCookie cookie = properties.weidian_cookie.get(group);
        cookie.autoDeliver = !cookie.autoDeliver;
        saveWeidianConfig();
        return cookie.autoDeliver ? 1 : 0;
    }

    public int switchWeidianDoBroadCast(long group) {
        if (!properties.weidian_cookie.containsKey(group))
            return -1;

        WeidianCookie cookie = properties.weidian_cookie.get(group);
        cookie.doBroadcast = !cookie.doBroadcast;
        saveWeidianConfig();
        return cookie.doBroadcast ? 1 : 0;
    }

    public boolean rmWeidianCookie(long group) {
        if (!properties.weidian_cookie.containsKey(group)) {
            return false;
        }

        properties.weidian_cookie.remove(group);
        saveWeidianConfig();
        return true;
    }

    public int highlightWeidianItem(long group, long itemid) {
        if (!properties.weidian_cookie.containsKey(group)) {
            return -1;
        }

        List<Long> it = properties.weidian_cookie.get(group).highlightItem;
        if (it.contains(itemid)) {
            it.remove(itemid);
        } else {
            it.add(itemid);
        }
        saveWeidianConfig();
        return it.contains(itemid) ? 1 : 0;
    }

    public int shieldWeidianItem(long group, long itemid) {
        if (!properties.weidian_cookie.containsKey(group)) {
            return -1;
        }

        List<Long> it = properties.weidian_cookie.get(group).shieldedItem;
        if (it.contains(itemid)) {
            it.remove(itemid);
        } else {
            it.add(itemid);
        }
        saveWeidianConfig();
        return it.contains(itemid) ? 1 : 0;
    }

    public void saveWelcome() {
        String a = "[";
        for (long group : properties.welcome.keySet()) {
            JSONObject object = new JSONObject();
            object.set("1", group);
            object.set("2", properties.welcome.get(group));
            a += object + ",";
        }
        setting.set("welcome", (a.length() > 1 ? a.substring(0, a.length() - 1) : a) + "]");
        setting.store();
    }

    public void savePocket48SubscribeConfig() {
        String a = "[";
        for (long group : properties.pocket48_subscribe.keySet()) {
            JSONObject object = new JSONObject();
            Pocket48Subscribe subscribe = properties.pocket48_subscribe.get(group);
            object.set("qqGroup", group);
            object.set("showAtOne", subscribe.showAtOne());
            object.set("starSubs", subscribe.getStarIDs().toArray());
            object.set("roomSubs", subscribe.getRoomIDs().toArray());
            a += object + ",";
        }
        setting.setByGroup("subscribe", "pocket48", (a.length() > 1 ? a.substring(0, a.length() - 1) : a) + "]");
        setting.store();
    }

    public void savePocket48RoomIDConnectConfig() {
        String a = "[";
        for (long room_id : properties.pocket48_serverID.keySet()) {
            JSONObject object = new JSONObject();
            object.set("roomID", room_id);
            object.set("serverID", properties.pocket48_serverID.get(room_id));
            a += object + ",";
        }
        setting.setByGroup("roomConnection", "pocket48", (a.length() > 1 ? a.substring(0, a.length() - 1) : a) + "]");
        setting.store();
    }



    public void saveWeiboConfig() {
        String a = "[";
        for (long group : properties.weibo_user_subscribe.keySet()) {
            JSONObject object = new JSONObject();
            object.set("qqGroup", group);
            object.set("userSubs", properties.weibo_user_subscribe.get(group));
            object.set("superTopicSubs", properties.weibo_superTopic_subscribe.get(group));
            a += object + ",";
        }
        setting.setByGroup("subscribe", "weibo", (a.length() > 1 ? a.substring(0, a.length() - 1) : a) + "]");
        setting.store();
    }

    public void saveWeidianConfig() {
        String a = "[";
        for (long group : properties.weidian_cookie.keySet()) {
            JSONObject object = new JSONObject();
            object.set("qqGroup", group);
            object.set("cookie", properties.weidian_cookie.get(group).cookie);
            object.set("autoDeliver", properties.weidian_cookie.get(group).autoDeliver);
            object.set("highlight", properties.weidian_cookie.get(group).highlightItem.toString());
            a += object + ",";
        }
        setting.setByGroup("shops", "weidian", (a.length() > 1 ? a.substring(0, a.length() - 1) : a) + "]");
        setting.store();
    }

    public boolean isAdmin(Group group, long qqID) {
        for (String g : properties.secureGroup) {
            if (g.equals(String.valueOf(group.getId())))
                return true;
        }

        for (String a : properties.admins) {
            if (a.equals(String.valueOf(qqID)))
                return true;
        }

        NormalMember m = group.get(qqID);
        if (m == null)
            return false;

        return m.getPermission() == MemberPermission.ADMINISTRATOR || m.getPermission() == MemberPermission.OWNER;
    }

    public boolean isAdmin(long qqID) {
        for (String a : properties.admins) {
            if (a.equals(String.valueOf(qqID)))
                return true;
        }
        return false;
    }
    
    // ========== 在线状态监控配置管理方法 ==========
    
    public boolean addOnlineStatusSubscribe(String memberName, long group) {
        if (!properties.onlineStatus_subscribe.containsKey(group)) {
            properties.onlineStatus_subscribe.put(group, new ArrayList<>());
        }
        
        if (properties.onlineStatus_subscribe.get(group).contains(memberName))
            return false;
            
        properties.onlineStatus_subscribe.get(group).add(memberName);
        saveOnlineStatusConfig();
        return true;
    }
    
    public boolean rmOnlineStatusSubscribe(String memberName, long group) {
        if (!properties.onlineStatus_subscribe.containsKey(group) || 
            !properties.onlineStatus_subscribe.get(group).contains(memberName))
            return false;
            
        properties.onlineStatus_subscribe.get(group).remove(memberName);
        saveOnlineStatusConfig();
        return true;
    }
    
    public int switchOnlineStatusMonitor() {
        properties.onlineStatus_enable = !properties.onlineStatus_enable;
        setting.set("onlineStatus_enable", String.valueOf(properties.onlineStatus_enable));
        setting.store();
        return properties.onlineStatus_enable ? 1 : 0;
    }

    /**
     * 设置在线状态监控间隔
     * @param pattern Cron表达式
     * @return 是否设置成功
     */
    public boolean setOnlineStatusInterval(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return false;
        }
        properties.onlineStatus_pattern = pattern.trim();
        setting.setByGroup("schedule", "onlineStatus", pattern.trim());
        setting.store();
        return true;
    }

    /**
     * 获取在线状态监控间隔
     * @return Cron表达式
     */
    public String getOnlineStatusInterval() {
        return properties.onlineStatus_pattern;
    }

    public void saveOnlineStatusConfig() {
        String a = "[";
        for (long group : properties.onlineStatus_subscribe.keySet()) {
            JSONObject object = new JSONObject();
            object.set("qqGroup", group);
            object.set("memberSubs", properties.onlineStatus_subscribe.get(group));
            a += object + ",";
        }
        setting.setByGroup("subscribe", "onlineStatus", (a.length() > 1 ? a.substring(0, a.length() - 1) : a) + "]");
        setting.store();
    }
}
