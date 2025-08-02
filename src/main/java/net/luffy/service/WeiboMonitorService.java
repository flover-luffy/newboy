package net.luffy.service;

import cn.hutool.json.JSONObject;
import net.luffy.model.WeiboData;
import net.luffy.util.WeiboUtils;
import net.luffy.util.sender.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
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
    
    private final WeiboApiService weiboApiService;
    private final MessageSender messageSender;
    private final ScheduledExecutorService scheduler;
    
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
    }
    
    /**
     * 启动监控服务
     */
    public void startMonitoring() {
        logger.info("启动微博监控服务");
        
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
        
        logger.info("添加用户监控: {} -> {}", uid, groupIds);
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
        
        logger.info("添加超话监控: {} -> {}", lfid, groupIds);
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
                // 发送文本消息
                messageSender.sendGroupMessage(groupId, messageText);
                
                // 发送图片（如果有）
                if (weiboData.pics != null && !weiboData.pics.isEmpty()) {
                    for (String picUrl : weiboData.pics) {
                        // 处理图片URL（添加代理前缀等）
                        String processedUrl = WeiboUtils.processImageUrl(picUrl, null);
                        messageSender.sendGroupImage(groupId, processedUrl);
                    }
                }
                
                logger.info("发送微博消息到群组{}: {}", groupId, weiboData.name);
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
            lfid = weiboApiService.getUserWeiboLfid(uid);
            if (lfid != null) {
                userLfidCache.put(uid, lfid);
            }
        }
        return lfid;
    }
    
    /**
     * 预加载用户信息
     * @param uid 用户UID
     */
    private void loadUserInfo(String uid) {
        // 预加载lfid
        getUserLfid(uid);
        
        // 预加载昵称
        String nickname = weiboApiService.getUserNickname(uid);
        if (nickname != null) {
            userNicknameCache.put(uid, nickname);
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
}