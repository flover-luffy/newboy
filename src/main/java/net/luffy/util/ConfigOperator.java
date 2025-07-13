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
            // 创建Setting对象并设置基本的默认配置
            Setting tempSetting = new Setting(file, StandardCharsets.UTF_8, false);
            
            // 设置基本配置的默认值
            tempSetting.setByGroup("basic", "enable", "true");
            tempSetting.setByGroup("basic", "save_login", "false");
            tempSetting.setByGroup("basic", "admins", "123456789");
            tempSetting.setByGroup("basic", "secureGroup", "");
            
            // 设置定时任务默认配置
            tempSetting.setByGroup("schedule", "pocket48", "* * * * *");
            tempSetting.setByGroup("schedule", "weibo", "*/5 * * * *");
            tempSetting.setByGroup("schedule", "onlineStatus", "*/2 * * * *");
            tempSetting.setByGroup("schedule_order", "weidian", "*/10 * * * *");
            tempSetting.setByGroup("schedule_item", "weidian", "*/10 * * * *");
            
            // 设置口袋48默认配置
            tempSetting.setByGroup("pocket48", "account", "");
            tempSetting.setByGroup("pocket48", "password", "");
            tempSetting.setByGroup("pocket48", "token", "");
            
            // 设置在线状态监控默认配置
            tempSetting.setByGroup("onlineStatus", "enable", "true");
            
            // 设置订阅配置默认值
            tempSetting.setByGroup("subscribe", "pocket48", "[]");
            tempSetting.setByGroup("subscribe", "weibo", "[]");
            tempSetting.setByGroup("subscribe", "onlineStatus", "[]");
            
            // 设置商店配置默认值
            tempSetting.setByGroup("shops", "weidian", "[]");
            
            // 保存默认配置
            tempSetting.store();
            
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
        properties.enable = setting.getBool("basic", "enable", true);
        properties.save_login = setting.getBool("basic", "save_login", false);
        properties.admins = setting.getStrings("basic", "admins");
        properties.secureGroup = setting.getStrings("basic", "secureGroup");
        if (properties.admins == null)
            properties.admins = new String[]{};
        if (properties.secureGroup == null)
            properties.secureGroup = new String[]{};

        // 进群欢迎功能已移除

        //schedule pattern
        properties.pocket48_pattern = setting.getStr("schedule", "pocket48", "* * * * *");

        properties.weibo_pattern = setting.getStr("schedule", "weibo", "*/5 * * * *");
        properties.weidian_pattern_order = setting.getStr("schedule_order", "weidian", "*/2 * * * *");
        properties.weidian_pattern_item = setting.getStr("schedule_item", "weidian", "*/5 * * * *");
        properties.onlineStatus_pattern = setting.getStr("schedule", "onlineStatus", "*/2 * * * *");
        
        //在线状态监控
        properties.onlineStatus_enable = setting.getBool("onlineStatus", "enable", true);

        //口袋48
        properties.pocket48_account = setting.getStr("pocket48", "account", "");
        properties.pocket48_password = setting.getStr("pocket48", "password", "");
        properties.pocket48_token = setting.getStr("pocket48", "token", "");
        
        // 配置验证日志
        if (!properties.pocket48_token.isEmpty()) {
            Newboy.INSTANCE.getLogger().info("口袋48 Token 配置已读取，长度: " + properties.pocket48_token.length());
        } else if (!properties.pocket48_account.isEmpty() && !properties.pocket48_password.isEmpty()) {
            Newboy.INSTANCE.getLogger().info("口袋48账号密码配置已读取");
        } else {
            Newboy.INSTANCE.getLogger().warning("口袋48未配置登录信息，请在config.setting中配置token或账号密码");
        }

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
        setting.setByGroup("basic", "enable", String.valueOf(on));
        safeStoreConfig("基础配置");
        properties.enable = setting.getBool("basic", "enable", true);
    }

    // 进群欢迎功能已移除

    public boolean setAndSaveToken(String token) {
        properties.pocket48_token = token;
        setting.setByGroup("pocket48", "token", token);
        safeStoreConfig("口袋48 Token配置");
        return true;
    }

    public String getToken() {
        return setting.getStr("pocket48", "token", properties.pocket48_token);
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

    // 进群欢迎功能已移除

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
        
        // 安全保存配置，避免覆盖整个配置文件
        safeStoreConfig("口袋48订阅配置");
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
        safeStoreConfig("口袋48房间连接配置");
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
        safeStoreConfig("微博订阅配置");
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
        safeStoreConfig("微店配置");
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
        setting.setByGroup("onlineStatus", "enable", String.valueOf(properties.onlineStatus_enable));
        safeStoreConfig("在线状态监控开关配置");
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
        safeStoreConfig("在线状态监控间隔配置");
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
        
        // 只保存特定配置项，避免覆盖整个配置文件
        try {
            // 创建临时Setting对象来保存单个配置项
            File configFile = properties.configData;
            if (configFile.exists()) {
                // 读取现有配置
                Setting tempSetting = new Setting(configFile, StandardCharsets.UTF_8, false);
                // 只更新在线状态监控配置
                tempSetting.setByGroup("subscribe", "onlineStatus", (a.length() > 1 ? a.substring(0, a.length() - 1) : a) + "]");
                // 保存配置
                tempSetting.store();
                Newboy.INSTANCE.getLogger().info("在线状态监控配置已保存");
            } else {
                // 如果配置文件不存在，使用原有逻辑
                safeStoreConfig("在线状态监控配置（新建文件）");
            }
        } catch (Exception e) {
             Newboy.INSTANCE.getLogger().warning("保存在线状态监控配置失败: " + e.getMessage());
             // 降级到原有保存方式
             safeStoreConfig("在线状态监控配置（降级保存）");
         }
    }
    
    /**
     * 安全保存配置，避免覆盖整个配置文件
     * 由于hutool Setting类的限制，暂时使用原有保存方式
     * 但添加了详细的日志记录以便用户了解配置变更
     * @param configType 配置类型描述
     */
    private void safeStoreConfig(String configType) {
        try {
            // 记录配置保存操作
            Newboy.INSTANCE.getLogger().info("正在保存" + configType + "...");
            
            // 使用原有的保存方式，但添加更好的错误处理
            setting.store();
            
            Newboy.INSTANCE.getLogger().info(configType + "已成功保存到: " + properties.configData.getAbsolutePath());
            
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("保存" + configType + "失败: " + e.getMessage(), e);
            throw e; // 重新抛出异常，让调用者知道保存失败
        }
    }
}
