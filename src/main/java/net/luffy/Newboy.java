package net.luffy;

import cn.hutool.cron.Scheduler;

import net.luffy.handler.Pocket48Handler;
import net.luffy.handler.WeiboHandler;
import net.luffy.handler.WeidianHandler;
import net.luffy.handler.WeidianSenderHandler;
import net.luffy.handler.Xox48Handler;


// import net.luffy.util.OnlineStatusMonitor; // 传统监控器已移除
import net.luffy.util.AsyncOnlineStatusMonitor;

import net.luffy.model.EndTime;
import net.luffy.model.Pocket48SenderCache;
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


    // 传统在线状态监控器已移除，使用AsyncOnlineStatusMonitor替代
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
        // HTTP调试模式已关闭 - 如需开启请通过JVM参数 -Dhttp.debug=true
        // System.setProperty("http.debug", "true");
        // getLogger().info("HTTP调试模式已开启");
        
        // 初始化统一调度器
        UnifiedSchedulerManager.getInstance();
        
        // 初始化CPU优化组件
        AdaptiveThreadPoolManager.getInstance();
        CpuLoadBalancer.getInstance();
        EventBusManager.getInstance();
        
        // 预热JSON解析器，提高首次解析性能
        // 预热统一JSON解析器
        net.luffy.util.UnifiedJsonParser.getInstance();
        
        initProperties();
        loadConfig();
        registerPermission();
        registerCommands();
        
        // 初始化处理器
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



        // 插件启动完成

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


        // 传统在线状态监控器初始化已移除

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



    
    // 传统在线状态监控器已移除，使用AsyncOnlineStatusMonitor替代
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

        
        // 传统在线状态监控器已移除，使用AsyncOnlineStatusMonitor替代

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
            // 停止所有定时任务
            stopAllScheduledTasks();
            

            
            // 停止定期性能报告
            PerformanceMonitor.getInstance().disablePeriodicReporting();
            
            // 传统在线状态监控器清理已移除
            
            // 关闭CPU优化组件
            EventBusManager.getInstance().shutdown();
            CpuLoadBalancer.getInstance().shutdown();
            AdaptiveThreadPoolManager.getInstance().shutdown();
            
            // 清理JSON解析器缓存
            // 清理统一JSON解析器缓存
        net.luffy.util.UnifiedJsonParser.getInstance().clearCache();
            
            // 关闭统一调度器（最后关闭）
            UnifiedSchedulerManager.getInstance().shutdown();
        } catch (Exception e) {
            getLogger().error("插件关闭时发生错误", e);
        }
    }

    private void loadConfig() {
        configOperator.load(properties);
        // 传统在线状态监控器配置初始化已移除，AsyncOnlineStatusMonitor会自动处理
        
        // 在配置加载完成后初始化异步监控系统
        try {
            AsyncOnlineStatusMonitor.INSTANCE.initializeMonitoring();
        } catch (Exception e) {
            getLogger().error("异步监控系统初始化失败: " + e.getMessage());
        }
        
        // 自动启动抖音监控服务
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
    
    /**
     * 注册命令
     */
    private void registerCommands() {
        // 命令注册已移除
    }

    private void listenBroadcast(boolean pocket48_has_login, boolean weibo_has_login) {

        // endTime: 已发送房间消息的最晚时间
        HashMap<Long, HashMap<Long, Long>> pocket48RoomEndTime = new HashMap<>();
        // 微博相关的endTime已移除，新的微博监控服务会自动管理状态
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

        // 新的微博监控服务已在WeiboHandler中自动启动，无需在此处重复调度

        // 抖音监听已移除 - 使用新的DouyinMonitorService替代

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
        
        // 传统在线状态监控调度已移除，AsyncOnlineStatusMonitor会自动启动

        // ------------------------------------------------
        if (properties.enable) {
            scheduler.start();
        } else {
            // 停止
        }
    }
}
