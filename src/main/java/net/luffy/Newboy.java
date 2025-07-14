package net.luffy;

import cn.hutool.cron.Scheduler;

import net.luffy.handler.Pocket48Handler;
import net.luffy.handler.WeiboHandler;
import net.luffy.handler.WeidianHandler;
import net.luffy.handler.WeidianSenderHandler;
import net.luffy.handler.Xox48Handler;
import net.luffy.util.OnlineStatusMonitor;
import net.luffy.model.EndTime;
import net.luffy.model.Pocket48SenderCache;
import net.luffy.model.WeidianCookie;
import net.luffy.model.WeidianOrder;
import net.luffy.util.ConfigOperator;
import net.luffy.util.Properties;
import net.luffy.util.PropertiesCommon;
import net.luffy.util.sender.*;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.console.command.CommandManager;
import net.mamoe.mirai.console.permission.AbstractPermitteeId;
import net.mamoe.mirai.console.permission.PermissionId;
import net.mamoe.mirai.console.permission.PermissionService;
import net.mamoe.mirai.console.plugin.jvm.JavaPlugin;
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescriptionBuilder;
import net.mamoe.mirai.event.GlobalEventChannel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Newboy extends JavaPlugin {
    public static final String ID = "net.luffy.newboy";
    public static final String VERSION = "1.0.0";

    public static final Newboy INSTANCE = new Newboy();
    private final ConfigOperator configOperator = new ConfigOperator();
    private final Properties properties = new Properties();
    public Pocket48Handler handlerPocket48;
    public WeiboHandler handlerWeibo;
    public WeidianHandler handlerWeidian;
    public WeidianSenderHandler handlerWeidianSender;
    public Xox48Handler handlerXox48;
    private OnlineStatusMonitor onlineStatusMonitor;
    private Scheduler scheduler;

    private Newboy() {
        super(new JvmPluginDescriptionBuilder(ID, VERSION +
                "")
                .name("newboy")
                .author("luffy")
                .info("傻妞一号")
                .build());
    }

    @Override
    public void onEnable() {
        initProperties();
        loadConfig();
        registerPermission();

        GlobalEventChannel.INSTANCE.registerListenerHost(new Listener());

        // ------------------------------------------------
        // 服务

        // 口袋48登录
        final boolean pocket48_has_login;
        if (!properties.pocket48_token.equals("")) {
            this.handlerPocket48.login(properties.pocket48_token, false);
            pocket48_has_login = true;
        } else if (!(properties.pocket48_account.equals("") || properties.pocket48_password.equals(""))) {
            pocket48_has_login = this.handlerPocket48.login(
                    properties.pocket48_account,
                    properties.pocket48_password);
        } else {
            pocket48_has_login = false;
            getLogger().info("开启口袋48播报需填写config/net.luffy.newboy/config.setting并重启");
        }

        boolean weibo_has_login = false;
        try {
            this.handlerWeibo.updateLoginToSuccess();
            weibo_has_login = true;
            getLogger().info("微博Cookie更新成功");

        } catch (Exception e) {
            getLogger().info("微博Cookie更新失败");
        }
        boolean finalWeibo_has_login = weibo_has_login;
        listenBroadcast(pocket48_has_login, finalWeibo_has_login);

        getLogger().info("New boy!");

        // ------------------------------------------------
        // YLG功能已移除
    }

    private void initProperties() {
        properties.configData = resolveConfigFile(PropertiesCommon.configDataName);
        properties.logger = getLogger();
        handlerPocket48 = new Pocket48Handler();
        handlerWeibo = new WeiboHandler();
        handlerWeidian = new WeidianHandler();
        handlerWeidianSender = new WeidianSenderHandler();
        handlerXox48 = new Xox48Handler();
        onlineStatusMonitor = new OnlineStatusMonitor();
    }



    public ConfigOperator getConfig() {
        return configOperator;
    }

    public Properties getProperties() {
        return properties;
    }

    public Pocket48Handler getHandlerPocket48() {
        return handlerPocket48;
    }

    public WeiboHandler getHandlerWeibo() {
        return handlerWeibo;
    }

    public WeidianHandler getHandlerWeidian() {
        return handlerWeidian;
    }

    public WeidianSenderHandler getHandlerWeidianSender() {
        return handlerWeidianSender;
    }

    public Xox48Handler getHandlerXox48() {
        return handlerXox48;
    }
    
    public OnlineStatusMonitor getOnlineStatusMonitor() {
        return onlineStatusMonitor;
    }
    
    public Scheduler getCronScheduler() {
        return scheduler;
    }
    
    /**
     * 热重载插件配置和功能
     * 参考 debug-helper 项目实现
     */
    // 热重载功能已移除 - 该功能无法正常工作，请重启Mirai Console来重新加载插件
    
    /**
     * 停止所有定时任务
     */
    private void stopAllScheduledTasks() {
        if (scheduler != null) {
            try {
                scheduler.stop();
                getLogger().info("所有定时任务已停止");
            } catch (Exception e) {
                getLogger().error("停止定时任务时发生错误", e);
            }
        }
    }
    
    /**
     * 重新初始化处理器
     */
    private void initHandlers() {
        handlerPocket48 = new Pocket48Handler();
        handlerWeibo = new WeiboHandler();
        handlerWeidian = new WeidianHandler();
        handlerWeidianSender = new WeidianSenderHandler();
        handlerXox48 = new Xox48Handler();
        onlineStatusMonitor = new OnlineStatusMonitor();
    }
    
    /**
     * 重新启动服务
     */
    private void restartServices() {
        // 重新检查登录状态并启动服务
        boolean pocket48_has_login = false;
        boolean weibo_has_login = false;
        
        if (properties.pocket48_token != null && !properties.pocket48_token.isEmpty()) {
            handlerPocket48.login(properties.pocket48_token, false);
            pocket48_has_login = true;
        } else if (properties.pocket48_account != null && !properties.pocket48_account.isEmpty() &&
                   properties.pocket48_password != null && !properties.pocket48_password.isEmpty()) {
            pocket48_has_login = handlerPocket48.login(properties.pocket48_account, properties.pocket48_password);
        }
        
        try {
            handlerWeibo.updateLoginToSuccess();
            weibo_has_login = true;
        } catch (Exception e) {
            getLogger().warning("微博登录失败: " + e.getMessage());
            weibo_has_login = false;
        }
        
        // 重新启动监听广播
        listenBroadcast(pocket48_has_login, weibo_has_login);
    }
    
    @Override
    public void onDisable() {
        try {
            getLogger().info("正在关闭插件...");
            
            // 停止所有定时任务
            stopAllScheduledTasks();
            
            // 清理资源
            if (onlineStatusMonitor != null) {
                onlineStatusMonitor.cleanup();
            }
            
            getLogger().info("插件已安全关闭");
        } catch (Exception e) {
            getLogger().error("插件关闭时发生错误", e);
        }
    }

    private void loadConfig() {
        configOperator.load(properties);
        // 配置加载完成后，初始化在线状态监控配置
        if (onlineStatusMonitor != null) {
            onlineStatusMonitor.initFromConfig();
        }
    }

    public void registerPermission() {
        PermissionId permissionId = this.getParentPermission().getId();
        for (String a : properties.admins) {
            AbstractPermitteeId.ExactUser user = new AbstractPermitteeId.ExactUser(Long.parseLong(a));

            if (!PermissionService.hasPermission(user, permissionId)) {
                PermissionService.permit(user, permissionId);
            }
        }
    }

    private void listenBroadcast(boolean pocket48_has_login, boolean weibo_has_login) {

        // endTime: 已发送房间消息的最晚时间
        HashMap<Long, HashMap<Long, Long>> pocket48RoomEndTime = new HashMap<>();
        HashMap<Long, HashMap<String, Long>> weiboEndTime = new HashMap<>(); // 同时包含超话和个人(long -> String)
        HashMap<Long, EndTime> weidianEndTime = new HashMap<>();
        // status: 上次检测的开播状态
        HashMap<Long, HashMap<Long, List<Long>>> pocket48VoiceStatus = new HashMap<>();

        // 停止旧的调度器
        if (scheduler != null) {
            scheduler.stop();
        }
        
        scheduler = new Scheduler();

        // 服务
        if (pocket48_has_login) {
            handlerPocket48.setCronScheduleID(scheduler.schedule(properties.pocket48_pattern, new Runnable() {
                @Override
                public void run() {
                    if (getHandlerPocket48().isLogin()) {
                        HashMap<Long, Pocket48SenderCache> cache = new HashMap<>();

                        for (Bot b : Bot.getInstances()) {
                            for (long group : properties.pocket48_subscribe.keySet()) {
                                if (b.getGroup(group) == null)
                                    continue;

                                if (!pocket48RoomEndTime.containsKey(group))// 放到Runnable里面是因为可能实时更新新的群
                                {
                                    pocket48RoomEndTime.put(group, new HashMap<>());
                                    pocket48VoiceStatus.put(group, new HashMap<>());
                                }

                                new Thread(new Pocket48Sender(b, group, pocket48RoomEndTime.get(group),
                                        pocket48VoiceStatus.get(group), cache)).start();

                            }
                        }
                    } else {
                        getLogger()
                                .warning("口袋48已退出登录，请在控制台使用指令\"/newboy login <token>\"或\"/newboy login <账号> <密码>\"登录");
                    }

                }
            }));
        }

        if (weibo_has_login) {
            handlerWeibo.setCronScheduleID(scheduler.schedule(properties.weibo_pattern, new Runnable() {
                @Override
                public void run() {
                    for (Bot b : Bot.getInstances()) {
                        for (long group : properties.weibo_user_subscribe.keySet()) {
                            if (b.getGroup(group) == null)
                                continue;

                            if (!weiboEndTime.containsKey(group))
                                weiboEndTime.put(group, new HashMap<>());

                            new Thread(new WeiboSender(b, group, weiboEndTime.get(group))).start();
                        }
                    }
                }
            }));
        }

        // 微店订单播报
        scheduler.schedule(properties.weidian_pattern_order, new Runnable() {
            @Override
            public void run() {

                HashMap<WeidianCookie, WeidianOrder[]> cache = new HashMap<>();
                Set<Long> processedGroups = new HashSet<>();

                for (Bot b : Bot.getInstances()) {
                    for (long group : properties.weidian_cookie.keySet()) {
                        WeidianCookie cookie = properties.weidian_cookie.get(group);
                        if (cookie == null)
                            continue;

                        if (!weidianEndTime.containsKey(group))
                            weidianEndTime.put(group, new EndTime());

                        // 如果需要播报且机器人在群中
                        if (cookie.doBroadcast && b.getGroup(group) != null) {
                            new Thread(new WeidianOrderSender(b, group, weidianEndTime.get(group), handlerWeidianSender, cache))
                                    .start();
                            processedGroups.add(group);
                        }
                        // 如果只需要自动发货且还未处理过
                        else if (cookie.autoDeliver && !processedGroups.contains(group)) {
                            new Thread(new WeidianOrderSender(null, group, weidianEndTime.get(group), handlerWeidianSender, cache))
                                    .start();
                            processedGroups.add(group);
                        }
                    }
                }
            }
        });

        // 微店商品播报
        handlerWeidian.setCronScheduleID(scheduler.schedule(properties.weidian_pattern_item, new Runnable() {
            @Override
            public void run() {
                for (Bot b : Bot.getInstances()) {
                    for (long group : properties.weidian_cookie.keySet()) {
                        WeidianCookie cookie = properties.weidian_cookie.get(group);
                        if (cookie == null || b.getGroup(group) == null)
                            continue;

                        new Thread(new WeidianItemSender(b, group, handlerWeidianSender)).start();
                    }
                }
            }
        }));
        
        // 在线状态监控
        if (properties.onlineStatus_enable) {
            onlineStatusMonitor.setCronScheduleID(scheduler.schedule(properties.onlineStatus_pattern, new Runnable() {
                @Override
                public void run() {
                    for (Bot b : Bot.getInstances()) {
                        for (long group : properties.onlineStatus_subscribe.keySet()) {
                            if (b.getGroup(group) == null)
                                continue;
                                
                            List<String> memberNames = properties.onlineStatus_subscribe.get(group);
                            if (memberNames != null && !memberNames.isEmpty()) {
                                onlineStatusMonitor.checkStatusChanges();
                            }
                        }
                    }
                }
            }));
        }

        // ------------------------------------------------
        if (properties.enable) {
            scheduler.start();
        } else {
            // 停止
        }
    }
}
