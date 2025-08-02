package net.luffy.util;

import net.luffy.model.Pocket48Subscribe;
import net.luffy.model.WeidianCookie;
import net.mamoe.mirai.utils.MiraiLogger;

import java.io.File;
import java.util.HashMap;
import java.util.List;

public class Properties {

    public MiraiLogger logger;
    public File configData;
    public boolean enable;
    public boolean save_login;
    public String[] admins;
    public String[] secureGroup;
    // 进群欢迎功能已移除

    //口袋48
    public String pocket48_pattern;
    public String pocket48_account;
    public String pocket48_password;
    public String pocket48_token;
    public HashMap<Long, Pocket48Subscribe> pocket48_subscribe = new HashMap<>();
    public HashMap<Long, Long> pocket48_serverID = new HashMap<>();//加密房间的severID记录



    //微博
    public String weibo_pattern;
    public HashMap<Long, List<Long>> weibo_user_subscribe = new HashMap<>();
    public HashMap<Long, List<String>> weibo_superTopic_subscribe = new HashMap<>();

    //微店
    public String weidian_pattern_order;
    public String weidian_pattern_item;
    public HashMap<Long, WeidianCookie> weidian_cookie = new HashMap<>();

    //抖音
    public String douyin_pattern;
    public String douyin_cookie;
    public String douyin_user_agent;
    public String douyin_referer;
    public int douyin_api_timeout;
    public int douyin_max_retries;
    public HashMap<Long, List<String>> douyin_user_subscribe = new HashMap<>();




    //在线状态监控配置已迁移到异步监控系统
    
    // 异步监控配置
    public String async_monitor_schedule_pattern;
    
    // 消息延迟优化配置 - 更激进的平衡模式参数
    public String message_delay_optimization_mode;
    public int message_delay_text;
    public int message_delay_media;
    public int message_delay_group_high_priority;
    public int message_delay_group_low_priority;
    public int message_delay_processing_timeout;
    public double message_delay_high_load_multiplier;
    public double message_delay_critical_load_multiplier;
    
    // 口袋48异步处理队列配置已迁移到 Pocket48ResourceManager

}
