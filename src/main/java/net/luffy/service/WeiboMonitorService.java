package net.luffy.service;

import cn.hutool.json.JSONObject;
import net.luffy.model.WeiboData;
import net.luffy.util.WeiboUtils;
import net.luffy.util.sender.MessageSender;

import net.luffy.Newboy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 微博监控服务
 * 基于qqtools项目的微博监控逻辑重构
 */
public class WeiboMonitorService {
    
    private static final Logger logger = LoggerFactory.getLogger(WeiboMonitorService.class);
    private static final int MONITOR_INTERVAL = 45; // 监控间隔45秒
    private static final String WEIBO_IDS_FILE = "weibo_latest_ids.properties"; // 微博ID持久化文件
    
    private final WeiboApiService weiboApiService;
    private final MessageSender messageSender;
    private final ScheduledExecutorService scheduler;
    // 延迟服务已移除
    
    // 存储每个用户的最新微博ID，用于判断是否有新微博
    private final Map<String, Long> userLatestWeiboId = new ConcurrentHashMap<>();
    // 存储每个超话的最新微博ID
    private final Map<String, Long> superTopicLatestWeiboId = new ConcurrentHashMap<>();
    // 存储用户UID到lfid的映射
    private final Map<String, String> userLfidCache = new ConcurrentHashMap<>();
    // 存储用户昵称缓存
    private final Map<String, String> userNicknameCache = new ConcurrentHashMap<>();
    
    // 监控配置
    private final Set<String> monitoredUsers = ConcurrentHashMap.newKeySet();
    private final Set<String> monitoredSuperTopics = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> userGroupMapping = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> superTopicGroupMapping = new ConcurrentHashMap<>();
    
    public WeiboMonitorService(WeiboApiService weiboApiService, MessageSender messageSender) {
        this.weiboApiService = weiboApiService;
        this.messageSender = messageSender;
        this.scheduler = Executors.newScheduledThreadPool(2);
        // 延迟服务已移除
        
        // 加载持久化的微博ID
        loadPersistedWeiboIds();
    }
    
    /**
     * 启动监控服务
     */
    public void startMonitoring() {
        // 启动普通微博监控
        scheduler.scheduleWithFixedDelay(this::monitorWeibo, 0, MONITOR_INTERVAL, TimeUnit.SECONDS);
        
        // 启动超话监控
        scheduler.scheduleWithFixedDelay(this::monitorSuperTopic, 10, MONITOR_INTERVAL, TimeUnit.SECONDS);
    }
    
    /**
     * 停止监控服务
     */
    public void stopMonitoring() {
        logger.info("停止微博监控服务");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 添加用户监控
     * @param uid 用户UID
     * @param groupIds 群组ID列表
     */
    public void addUserMonitor(String uid, Set<String> groupIds) {
        monitoredUsers.add(uid);
        userGroupMapping.put(uid, new HashSet<>(groupIds));
        
        // 预加载用户信息
        loadUserInfo(uid);
        
        // 初始化最新微博ID，避免发送旧微博
        initializeUserLatestWeiboId(uid);
        
        logger.info("添加用户监控成功: {}", uid);
    }
    
    /**
     * 移除用户监控
     * @param uid 用户UID
     */
    public void removeUserMonitor(String uid) {
        monitoredUsers.remove(uid);
        userGroupMapping.remove(uid);
        userLatestWeiboId.remove(uid);
        userLfidCache.remove(uid);
        userNicknameCache.remove(uid);
        
        logger.info("移除用户监控: {}", uid);
    }
    
    /**
     * 添加超话监控
     * @param lfid 超话容器ID
     * @param groupIds 群组ID列表
     */
    public void addSuperTopicMonitor(String lfid, Set<String> groupIds) {
        monitoredSuperTopics.add(lfid);
        superTopicGroupMapping.put(lfid, new HashSet<>(groupIds));
        
        // 初始化最新微博ID，避免发送旧微博
        initializeSuperTopicLatestWeiboId(lfid);
        
        logger.info("添加超话监控成功: {}", lfid);
    }
    
    /**
     * 移除超话监控
     * @param lfid 超话容器ID
     */
    public void removeSuperTopicMonitor(String lfid) {
        monitoredSuperTopics.remove(lfid);
        superTopicGroupMapping.remove(lfid);
        superTopicLatestWeiboId.remove(lfid);
        
        logger.info("移除超话监控: {}", lfid);
    }
    
    /**
     * 监控普通微博
     */
    private void monitorWeibo() {
        for (String uid : monitoredUsers) {
            try {
                monitorUserWeibo(uid);
            } catch (Exception e) {
                logger.error("监控用户微博失败: {}", uid, e);
            }
        }
    }
    
    /**
     * 监控超话微博
     */
    private void monitorSuperTopic() {
        for (String lfid : monitoredSuperTopics) {
            try {
                monitorSuperTopicWeibo(lfid);
            } catch (Exception e) {
                logger.error("监控超话微博失败: {}", lfid, e);
            }
        }
    }
    
    /**
     * 监控指定用户的微博
     * @param uid 用户UID
     */
    private void monitorUserWeibo(String uid) {
        String lfid = getUserLfid(uid);
        if (lfid == null) {
            logger.warn("无法获取用户{}的lfid", uid);
            return;
        }
        
        JSONObject containerData = weiboApiService.requestWeiboContainer(lfid);
        if (containerData == null || containerData.getInt("ok", 0) != 1) {
            logger.warn("获取用户{}微博容器数据失败", uid);
            return;
        }
        
        JSONObject data = containerData.getJSONObject("data");
        if (data == null || !data.containsKey("cards")) {
            return;
        }
        
        // 过滤微博卡片
        List<WeiboData.WeiboCard> cards = WeiboUtils.filterCards(data.getJSONArray("cards"));
        if (cards.isEmpty()) {
            return;
        }
        
        // 检查是否有新微博
        Long latestId = userLatestWeiboId.get(uid);
        List<WeiboData.WeiboSendData> newWeibos = new ArrayList<>();
        
        for (WeiboData.WeiboCard card : cards) {
            if (card._id != null && (latestId == null || card._id > latestId)) {
                List<WeiboData.WeiboSendData> cardData = WeiboUtils.filterNewCards(Arrays.asList(card));
                newWeibos.addAll(cardData);
            }
        }
        
        if (!newWeibos.isEmpty()) {
            // 更新最新微博ID
            userLatestWeiboId.put(uid, cards.get(0)._id);
            
            // 持久化最新微博ID
            savePersistedWeiboIds();
            
            // 发送新微博
            Set<String> groupIds = userGroupMapping.get(uid);
            if (groupIds != null) {
                for (WeiboData.WeiboSendData weiboData : newWeibos) {
                    sendWeiboMessage(weiboData, null, groupIds);
                }
            }
        }
    }
    
    /**
     * 监控指定超话的微博
     * @param lfid 超话容器ID
     */
    private void monitorSuperTopicWeibo(String lfid) {
        JSONObject containerData = weiboApiService.requestWeiboContainer(lfid);
        if (containerData == null || containerData.getInt("ok", 0) != 1) {
            logger.warn("获取超话{}容器数据失败", lfid);
            return;
        }
        
        JSONObject data = containerData.getJSONObject("data");
        if (data == null || !data.containsKey("cards")) {
            return;
        }
        
        // 获取超话名称
        String superTopicName = "";
        if (data.containsKey("pageInfo")) {
            JSONObject pageInfo = data.getJSONObject("pageInfo");
            superTopicName = pageInfo.getStr("nick", "");
        }
        
        // 过滤超话微博卡片
        List<WeiboData.WeiboCard> cards = WeiboUtils.filterSuperTopicCards(data.getJSONArray("cards"));
        if (cards.isEmpty()) {
            return;
        }
        
        // 检查是否有新微博
        Long latestId = superTopicLatestWeiboId.get(lfid);
        List<WeiboData.WeiboSendData> newWeibos = new ArrayList<>();
        
        for (WeiboData.WeiboCard card : cards) {
            if (card._id != null && (latestId == null || card._id > latestId)) {
                List<WeiboData.WeiboSendData> cardData = WeiboUtils.filterNewCards(Arrays.asList(card));
                newWeibos.addAll(cardData);
            }
        }
        
        if (!newWeibos.isEmpty()) {
            // 更新最新微博ID
            superTopicLatestWeiboId.put(lfid, cards.get(0)._id);
            
            // 持久化最新微博ID
            savePersistedWeiboIds();
            
            // 发送新微博
            Set<String> groupIds = superTopicGroupMapping.get(lfid);
            if (groupIds != null) {
                for (WeiboData.WeiboSendData weiboData : newWeibos) {
                    sendWeiboMessage(weiboData, superTopicName, groupIds);
                }
            }
        }
    }
    
    /**
     * 发送微博消息
     * @param weiboData 微博数据
     * @param superTopicName 超话名称（可选）
     * @param groupIds 群组ID列表
     */
    private void sendWeiboMessage(WeiboData.WeiboSendData weiboData, String superTopicName, Set<String> groupIds) {
        String messageText = WeiboUtils.buildWeiboMessage(weiboData, superTopicName);
        
        for (String groupId : groupIds) {
            try {
                // 检查是否有图片，如果有则只发送第一张图片
                String firstImageUrl = null;
                if (weiboData.pics != null && !weiboData.pics.isEmpty()) {
                    // 只取第一张图片
                    firstImageUrl = WeiboUtils.processImageUrl(weiboData.pics.get(0), null);
                }
                
                // 发送文本消息和图片（如果有图片，图片会嵌入到文本消息的最后）
                // 启用@全体成员功能
                messageSender.sendGroupMessageWithImage(groupId, messageText, firstImageUrl, true);
                
                // 微博消息发送成功
            } catch (Exception e) {
                logger.error("发送微博消息到群组{}失败", groupId, e);
            }
        }
    }
    
    /**
     * 获取用户的lfid
     * @param uid 用户UID
     * @return lfid
     */
    private String getUserLfid(String uid) {
        String lfid = userLfidCache.get(uid);
        if (lfid == null) {
            try {
                lfid = weiboApiService.getUserWeiboLfid(uid);
                if (lfid != null) {
                    userLfidCache.put(uid, lfid);
                }
            } catch (Exception e) {
                logger.warn("获取用户{}的lfid失败: {}", uid, e.getMessage());
                // 对于HTTP 432错误，不抛出异常，返回null让上层处理
                if (e.getMessage() != null && e.getMessage().contains("432")) {
                    return null;
                }
                throw e;
            }
        }
        return lfid;
    }
    
    /**
     * 预加载用户信息
     * @param uid 用户UID
     */
    private void loadUserInfo(String uid) {
        int maxRetries = 3;
        int retryCount = 0;
        
        while (retryCount < maxRetries) {
            try {
                // 预加载lfid
                String lfid = getUserLfid(uid);
                if (lfid == null) {
                    throw new RuntimeException("无法获取用户lfid");
                }
                
                // 预加载昵称
                String nickname = weiboApiService.getUserNickname(uid);
                if (nickname != null) {
                    userNicknameCache.put(uid, nickname);
                }
                
                // 成功加载，退出重试循环
                logger.info("用户{}信息加载成功", uid);
                return;
                
            } catch (Exception e) {
                retryCount++;
                logger.warn("加载用户{}信息失败 (第{}次重试): {}", uid, retryCount, e.getMessage());
                
                if (retryCount >= maxRetries) {
                    logger.error("用户{}信息加载最终失败，已重试{}次", uid, maxRetries);
                    return;
                }
                
                // 计算重试延迟：2^retryCount * 1000ms，最大30秒
                long delayMs = Math.min((long)(Math.pow(2, retryCount) * 1000), 30000);
                try {
                    // 使用Thread.sleep进行同步延迟
                    Thread.sleep(delayMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    logger.error("用户{}信息加载延迟被中断", uid);
                    return;
                }
            }
        }
    }
    
    /**
     * 初始化用户最新微博ID
     * 获取当前最新微博ID作为基准，避免发送旧微博
     * @param uid 用户UID
     */
    private void initializeUserLatestWeiboId(String uid) {
        try {
            // 如果已经有记录的最新ID，则不需要重新初始化
            if (userLatestWeiboId.containsKey(uid)) {
                logger.info("用户{}已有最新微博ID记录，跳过初始化", uid);
                return;
            }
            
            String lfid = getUserLfid(uid);
            if (lfid == null) {
                logger.warn("无法获取用户{}的lfid，跳过初始化", uid);
                return;
            }
            
            JSONObject containerData = weiboApiService.requestWeiboContainer(lfid);
            if (containerData == null || containerData.getInt("ok", 0) != 1) {
                logger.warn("获取用户{}微博容器数据失败，跳过初始化", uid);
                return;
            }
            
            JSONObject data = containerData.getJSONObject("data");
            if (data == null || !data.containsKey("cards")) {
                logger.info("用户{}暂无微博数据", uid);
                return;
            }
            
            // 过滤微博卡片
            List<WeiboData.WeiboCard> cards = WeiboUtils.filterCards(data.getJSONArray("cards"));
            if (!cards.isEmpty()) {
                // 设置最新微博ID为当前最新的微博ID
                Long latestId = cards.get(0)._id;
                if (latestId != null) {
                    userLatestWeiboId.put(uid, latestId);
                    savePersistedWeiboIds();
                    logger.info("初始化用户{}最新微博ID: {}", uid, latestId);
                } else {
                    logger.warn("用户{}的最新微博ID为空", uid);
                }
            } else {
                logger.info("用户{}暂无有效微博数据", uid);
            }
        } catch (Exception e) {
            logger.error("初始化用户{}最新微博ID失败", uid, e);
        }
    }
    
    /**
     * 初始化超话最新微博ID
     * 获取当前最新微博ID作为基准，避免发送旧微博
     * @param lfid 超话容器ID
     */
    private void initializeSuperTopicLatestWeiboId(String lfid) {
        try {
            // 如果已经有记录的最新ID，则不需要重新初始化
            if (superTopicLatestWeiboId.containsKey(lfid)) {
                logger.info("超话{}已有最新微博ID记录，跳过初始化", lfid);
                return;
            }
            
            JSONObject containerData = weiboApiService.requestWeiboContainer(lfid);
            if (containerData == null || containerData.getInt("ok", 0) != 1) {
                logger.warn("获取超话{}容器数据失败，跳过初始化", lfid);
                return;
            }
            
            JSONObject data = containerData.getJSONObject("data");
            if (data == null || !data.containsKey("cards")) {
                logger.info("超话{}暂无微博数据", lfid);
                return;
            }
            
            // 过滤超话微博卡片
            List<WeiboData.WeiboCard> cards = WeiboUtils.filterSuperTopicCards(data.getJSONArray("cards"));
            if (!cards.isEmpty()) {
                // 设置最新微博ID为当前最新的微博ID
                Long latestId = cards.get(0)._id;
                if (latestId != null) {
                    superTopicLatestWeiboId.put(lfid, latestId);
                    savePersistedWeiboIds();
                    logger.info("初始化超话{}最新微博ID: {}", lfid, latestId);
                } else {
                    logger.warn("超话{}的最新微博ID为空", lfid);
                }
            } else {
                logger.info("超话{}暂无有效微博数据", lfid);
            }
        } catch (Exception e) {
            logger.error("初始化超话{}最新微博ID失败", lfid, e);
        }
    }
    
    /**
     * 获取监控状态
     * @return 监控状态信息
     */
    public Map<String, Object> getMonitorStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("monitoredUsers", monitoredUsers.size());
        status.put("monitoredSuperTopics", monitoredSuperTopics.size());
        status.put("userGroupMappings", userGroupMapping.size());
        status.put("superTopicGroupMappings", superTopicGroupMapping.size());
        return status;
    }
    
    /**
     * 加载持久化的微博ID
     */
    private void loadPersistedWeiboIds() {
        try {
            File configDir = Newboy.INSTANCE.getProperties().configData.getParentFile();
            File weiboIdsFile = new File(configDir, WEIBO_IDS_FILE);
            
            if (!weiboIdsFile.exists()) {
                logger.info("微博ID持久化文件不存在，将从头开始监控");
                return;
            }
            
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(weiboIdsFile)) {
                props.load(fis);
            }
            
            // 加载用户微博ID
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                try {
                    Long weiboId = Long.parseLong(value);
                    if (key.startsWith("user.")) {
                        String uid = key.substring(5); // 移除"user."前缀
                        userLatestWeiboId.put(uid, weiboId);
                    } else if (key.startsWith("supertopic.")) {
                        String lfid = key.substring(11); // 移除"supertopic."前缀
                        superTopicLatestWeiboId.put(lfid, weiboId);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("解析微博ID失败: {} = {}", key, value);
                }
            }
            
            logger.info("加载持久化微博ID完成 - 用户: {}, 超话: {}", 
                       userLatestWeiboId.size(), superTopicLatestWeiboId.size());
                       
        } catch (Exception e) {
            logger.error("加载持久化微博ID失败", e);
        }
    }
    
    /**
     * 保存微博ID到持久化文件
     */
    private void savePersistedWeiboIds() {
        try {
            File configDir = Newboy.INSTANCE.getProperties().configData.getParentFile();
            File weiboIdsFile = new File(configDir, WEIBO_IDS_FILE);
            
            Properties props = new Properties();
            
            // 保存用户微博ID
            for (Map.Entry<String, Long> entry : userLatestWeiboId.entrySet()) {
                props.setProperty("user." + entry.getKey(), entry.getValue().toString());
            }
            
            // 保存超话微博ID
            for (Map.Entry<String, Long> entry : superTopicLatestWeiboId.entrySet()) {
                props.setProperty("supertopic." + entry.getKey(), entry.getValue().toString());
            }
            
            try (FileOutputStream fos = new FileOutputStream(weiboIdsFile)) {
                props.store(fos, "微博最新ID持久化文件 - 自动生成，请勿手动修改");
            }
            
        } catch (Exception e) {
            logger.error("保存持久化微博ID失败", e);
        }
    }
}