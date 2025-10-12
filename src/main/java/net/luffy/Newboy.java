package net.luffy;

import cn.hutool.cron.Scheduler;

import net.luffy.handler.Pocket48Handler;
import net.luffy.handler.WeiboHandler;
import net.luffy.handler.WeidianHandler;
import net.luffy.handler.WeidianSenderHandler;
import net.luffy.handler.Xox48Handler;
import net.luffy.util.AsyncOnlineStatusMonitor;

import net.luffy.model.EndTime;
import net.luffy.model.Pocket48SenderCache;
import net.luffy.model.Pocket48Subscribe;
import net.luffy.model.WeidianCookie;
import net.luffy.model.WeidianOrder;
import net.luffy.util.ConfigOperator;
import net.luffy.util.Properties;
import net.luffy.util.PropertiesCommon;
import net.luffy.util.sender.Pocket48Sender;
import net.luffy.util.sender.WeidianItemSender;
import net.luffy.util.sender.WeidianOrderSender;

import net.luffy.util.UnifiedSchedulerManager;
import net.luffy.util.PerformanceMonitor;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.luffy.util.CpuLoadBalancer;
import net.luffy.util.EventBusManager;
import net.luffy.util.UnifiedMetricsManager;
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
    private final ConfigOperator configOperator = ConfigOperator.getInstance();
    private final Properties properties = new Properties();
    public Pocket48Handler handlerPocket48;
    public WeiboHandler handlerWeibo;
    public WeidianHandler handlerWeidian;
    public WeidianSenderHandler handlerWeidianSender;
    public Xox48Handler handlerXox48;
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
        // 初始化统一调度器
        UnifiedSchedulerManager.getInstance();
        
        // 初始化CPU优化组件
        AdaptiveThreadPoolManager.getInstance();
        CpuLoadBalancer.getInstance();
        EventBusManager.getInstance();
        
        // 预热统一JSON解析器
        net.luffy.util.UnifiedJsonParser.getInstance();
        
        initProperties();
        loadConfig();
        registerPermission();
        registerCommands();
        initHandlers();

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
        }

        // 新的微博Handler会自动初始化，无需手动登录检查
        boolean weibo_has_login = true; // 新的微博监控服务总是可用的
        listenBroadcast(pocket48_has_login, weibo_has_login);

        // 启动定期性能报告（发送给第一个管理员）
        if (properties.admins != null && properties.admins.length > 0) {
            String firstAdmin = properties.admins[0];
            PerformanceMonitor.getInstance().enablePeriodicReporting(firstAdmin, 1440); // 24小时间隔
        }
    }

    private void initProperties() {
        properties.configData = resolveConfigFile(PropertiesCommon.configDataName);
        properties.logger = getLogger();
        handlerPocket48 = new Pocket48Handler();
        handlerWeibo = new WeiboHandler();
        handlerWeidian = new WeidianHandler();
        handlerWeidianSender = new WeidianSenderHandler();
        handlerXox48 = new Xox48Handler();
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
    
    public AsyncOnlineStatusMonitor getAsyncOnlineStatusMonitor() {
        return AsyncOnlineStatusMonitor.INSTANCE;
    }
    
    public Scheduler getCronScheduler() {
        return scheduler;
    }
    
    /**
     * 获取Bot实例（用于发送消息）
     * @return Bot实例，如果没有可用的Bot则返回null
     */
    public static Bot getBot() {
        List<Bot> bots = Bot.getInstances();
        return bots.isEmpty() ? null : bots.get(0);
    }
    

    
    /**
     * 停止所有定时任务
     */
    private void stopAllScheduledTasks() {
        if (scheduler != null) {
            try {
                scheduler.stop();
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
        // 手动初始化微博处理器，因为它不是Spring管理的Bean
        handlerWeibo.init();
        handlerWeidian = new WeidianHandler();
        handlerWeidianSender = new WeidianSenderHandler();
        handlerXox48 = new Xox48Handler();
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
        
        // 新的微博Handler会自动管理连接状态
        weibo_has_login = true;
        
        // 重新启动监听广播
        listenBroadcast(pocket48_has_login, weibo_has_login);
    }
    
    @Override
    public void onDisable() {
        try {
            stopAllScheduledTasks();
            PerformanceMonitor.getInstance().disablePeriodicReporting();
            
            // 关闭Pocket48相关组件
            shutdownPocket48Components();
            
            EventBusManager.getInstance().shutdown();
            CpuLoadBalancer.getInstance().shutdown();
            AdaptiveThreadPoolManager.getInstance().shutdown();
            
            net.luffy.util.UnifiedJsonParser.getInstance().clearCache();
            
            // 关闭统一指标管理器
            UnifiedMetricsManager.getInstance().shutdown();
            getLogger().info("统一指标管理器已关闭");
            
            // 关闭统一调度器管理器
            UnifiedSchedulerManager.getInstance().shutdown();
            getLogger().info("统一调度器管理器已关闭");
        } catch (Exception e) {
            getLogger().error("插件关闭时发生错误", e);
        }
    }
    
    /**
     * 关闭Pocket48相关组件
     */
    private void shutdownPocket48Components() {
        try {
            // 关闭Pocket48统一资源管理器
            net.luffy.util.sender.Pocket48UnifiedResourceManager.getInstance().shutdown();
            
            // 关闭Pocket48活跃度监控器
            net.luffy.util.sender.Pocket48ActivityMonitor.getInstance().shutdown();
            
            // 关闭Pocket48媒体队列
            net.luffy.util.sender.Pocket48MediaQueue.getInstance().shutdown();
            
            // 关闭Pocket48资源缓存
            net.luffy.util.sender.Pocket48ResourceCache.getInstance().shutdown();
            
            // 关闭Pocket48发送器缓存
            net.luffy.model.Pocket48SenderCache.shutdownCacheRefreshExecutor();
            
            // 关闭异步在线状态监控器
            AsyncOnlineStatusMonitor.INSTANCE.shutdown();
            
            // 关闭动态超时管理器
            net.luffy.util.DynamicTimeoutManager.getInstance().shutdown();
            
            // 关闭抖音监控服务
            net.luffy.util.DouyinMonitorService.getInstance().shutdown();
            
            // 关闭性能监控器
            net.luffy.util.PerformanceMonitor.getInstance().shutdown();
            
            // 关闭并发安全工具
            net.luffy.util.ConcurrencySafetyUtils.getInstance().shutdown();
            
            getLogger().info("[Pocket48] 所有组件已成功关闭");
        } catch (Exception e) {
            getLogger().error("[Pocket48] 组件关闭时发生错误", e);
        }
    }

    private void loadConfig() {
        configOperator.load(properties);
        
        try {
            AsyncOnlineStatusMonitor.INSTANCE.initializeMonitoring();
        } catch (Exception e) {
            getLogger().error("异步监控系统初始化失败: " + e.getMessage());
        }
        
        try {
            if (properties.douyin_user_subscribe != null && !properties.douyin_user_subscribe.isEmpty()) {
                net.luffy.util.DouyinMonitorService.getInstance().startMonitoring(10);
            }
        } catch (Exception e) {
            getLogger().error("抖音监控服务启动失败: " + e.getMessage());
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
    
    private void registerCommands() {
    }

    private void listenBroadcast(boolean pocket48_has_login, boolean weibo_has_login) {
        HashMap<Long, HashMap<Long, Long>> pocket48RoomEndTime = new HashMap<>();
        HashMap<Long, EndTime> weidianEndTime = new HashMap<>();
        HashMap<Long, HashMap<Long, List<Long>>> pocket48VoiceStatus = new HashMap<>();

        if (scheduler != null) {
            scheduler.stop();
        }
        scheduler = new Scheduler();

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

                                if (!pocket48RoomEndTime.containsKey(group)) {
                                    HashMap<Long, Long> groupEndTime = new HashMap<>();
                                    Pocket48Subscribe subscribe = properties.pocket48_subscribe.get(group);
                                    if (subscribe != null && subscribe.getRoomIDs() != null) {
                                        long currentTime = System.currentTimeMillis();
                                        for (Long roomId : subscribe.getRoomIDs()) {
                                            groupEndTime.put(roomId, currentTime);
                                        }
                                    }
                                    pocket48RoomEndTime.put(group, groupEndTime);
                                    pocket48VoiceStatus.put(group, new HashMap<>());
                                }

                                AdaptiveThreadPoolManager.getInstance().execute(new Pocket48Sender(b, group, pocket48RoomEndTime.get(group),
                                        pocket48VoiceStatus.get(group), cache));

                            }
                        }
                    } else {
                        getLogger()
                                .warning("口袋48已退出登录，请在控制台使用指令\"/newboy login <token>\"或\"/newboy login <账号> <密码>\"登录");
                    }

                }
            }));
        }

        // 微店订单播报
        handlerWeidian.setCronScheduleID(scheduler.schedule(properties.weidian_pattern_order, new Runnable() {
            @Override
            public void run() {
                HashMap<WeidianCookie, WeidianOrder[]> cache = new HashMap<>();
                Set<Long> processedGroups = new HashSet<>();
                
                // 检查配置状态
                int totalConfiguredGroups = properties.weidian_cookie.size();
                
                if (totalConfiguredGroups == 0) {
                    return;
                }

                int activeBotsCount = Bot.getInstances().size();
                
                if (activeBotsCount == 0) {
                    return;
                }

                int broadcastTaskCount = 0;
                int deliverOnlyTaskCount = 0;
                int skippedGroupCount = 0;

                for (Bot b : Bot.getInstances()) {
                    for (long group : properties.weidian_cookie.keySet()) {
                        WeidianCookie cookie = properties.weidian_cookie.get(group);
                        if (cookie == null) {
                            skippedGroupCount++;
                            continue;
                        }

                        if (!weidianEndTime.containsKey(group)) {
                            weidianEndTime.put(group, new EndTime());
                        }

                        // 如果需要播报且机器人在群中
                        if (cookie.doBroadcast && b.getGroup(group) != null) {
                            AdaptiveThreadPoolManager.getInstance().execute(new WeidianOrderSender(b, group, weidianEndTime.get(group), handlerWeidianSender, cache));
                            processedGroups.add(group);
                            broadcastTaskCount++;
                        }
                        // 如果只需要自动发货且还未处理过
                        else if (cookie.autoDeliver && !processedGroups.contains(group)) {
                            AdaptiveThreadPoolManager.getInstance().execute(new WeidianOrderSender(null, group, weidianEndTime.get(group), handlerWeidianSender, cache));
                            processedGroups.add(group);
                            deliverOnlyTaskCount++;
                        }
                        else {
                            skippedGroupCount++;
                        }
                    }
                }
            }
        }));

        // 微店商品播报
        handlerWeidian.setCronScheduleID(scheduler.schedule(properties.weidian_pattern_item, new Runnable() {
            @Override
            public void run() {
                int totalConfiguredGroups = properties.weidian_cookie.size();
                
                if (totalConfiguredGroups == 0) {
                    return;
                }

                int activeBotsCount = Bot.getInstances().size();
                
                if (activeBotsCount == 0) {
                    return;
                }

                int itemBroadcastTaskCount = 0;
                int skippedGroupCount = 0;

                for (Bot b : Bot.getInstances()) {
                    for (long group : properties.weidian_cookie.keySet()) {
                        WeidianCookie cookie = properties.weidian_cookie.get(group);
                        if (cookie == null) {
                            skippedGroupCount++;
                            continue;
                        }
                        
                        if (b.getGroup(group) == null) {
                            skippedGroupCount++;
                            continue;
                        }

                        AdaptiveThreadPoolManager.getInstance().execute(new WeidianItemSender(b, group, handlerWeidianSender));
                        itemBroadcastTaskCount++;
                    }
                }
            }
        }));
        
        if (properties.enable) {
            scheduler.start();
        }
    }
}
