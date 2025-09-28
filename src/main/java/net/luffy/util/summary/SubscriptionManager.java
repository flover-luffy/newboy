package net.luffy.util.summary;

import net.luffy.util.UnifiedLogger;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅管理系统
 * 负责管理房间和群组之间的订阅关系
 */
public class SubscriptionManager {
    private static final UnifiedLogger logger = UnifiedLogger.getInstance();
    private static final String CONFIG_FILE = "config/room_subscriptions.json";
    private static SubscriptionManager instance;
    
    // 房间ID -> 订阅群组列表的映射
    private final Map<String, Set<Long>> roomSubscriptions;
    private final Object lock = new Object();
    
    private SubscriptionManager() {
        this.roomSubscriptions = new HashMap<>();
        loadSubscriptions();
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized SubscriptionManager getInstance() {
        if (instance == null) {
            instance = new SubscriptionManager();
        }
        return instance;
    }
    
    /**
     * 获取订阅了指定房间的群组列表
     */
    public List<Long> getSubscribedGroups(String roomId) {
        Set<Long> groups = roomSubscriptions.get(roomId);
        return groups != null ? new ArrayList<>(groups) : new ArrayList<>();
    }
    
    /**
     * 添加房间订阅
     */
    public boolean addSubscription(String roomId, Long groupId) {
        try {
            roomSubscriptions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(groupId);
            saveSubscriptions();
            logger.info("SubscriptionManager", "添加订阅: 房间 " + roomId + " -> 群组 " + groupId);
            return true;
        } catch (Exception e) {
            logger.error("SubscriptionManager", "添加订阅失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 移除房间订阅
     */
    public boolean removeSubscription(String roomId, Long groupId) {
        try {
            Set<Long> groups = roomSubscriptions.get(roomId);
            if (groups != null) {
                boolean removed = groups.remove(groupId);
                if (groups.isEmpty()) {
                    roomSubscriptions.remove(roomId);
                }
                if (removed) {
                    saveSubscriptions();
                    logger.info("SubscriptionManager", "移除订阅: 房间 " + roomId + " -> 群组 " + groupId);
                }
                return removed;
            }
            return false;
        } catch (Exception e) {
            logger.error("SubscriptionManager", "移除订阅失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 获取房间的所有订阅群组
     */
    public Set<Long> getRoomSubscriptions(String roomId) {
        Set<Long> groups = roomSubscriptions.get(roomId);
        return groups != null ? new HashSet<>(groups) : new HashSet<>();
    }
    
    /**
     * 获取所有订阅关系
     */
    public Map<String, Set<Long>> getAllSubscriptions() {
        Map<String, Set<Long>> result = new HashMap<>();
        for (Map.Entry<String, Set<Long>> entry : roomSubscriptions.entrySet()) {
            result.put(entry.getKey(), new HashSet<>(entry.getValue()));
        }
        return result;
    }
    
    /**
     * 检查群组是否订阅了指定房间
     */
    public boolean isSubscribed(String roomId, Long groupId) {
        Set<Long> groups = roomSubscriptions.get(roomId);
        return groups != null && groups.contains(groupId);
    }
    
    /**
     * 获取群组订阅的所有房间
     */
    public List<String> getGroupSubscribedRooms(Long groupId) {
        List<String> rooms = new ArrayList<>();
        for (Map.Entry<String, Set<Long>> entry : roomSubscriptions.entrySet()) {
            if (entry.getValue().contains(groupId)) {
                rooms.add(entry.getKey());
            }
        }
        return rooms;
    }
    
    /**
     * 清空指定房间的所有订阅
     */
    public boolean clearRoomSubscriptions(String roomId) {
        try {
            Set<Long> removed = roomSubscriptions.remove(roomId);
            if (removed != null && !removed.isEmpty()) {
                saveSubscriptions();
                logger.info("SubscriptionManager", "清空房间订阅: " + roomId + ", 移除 " + removed.size() + " 个群组");
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.error("SubscriptionManager", "清空房间订阅失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 清空指定群组的所有订阅
     */
    public boolean clearGroupSubscriptions(Long groupId) {
        try {
            int removedCount = 0;
            Iterator<Map.Entry<String, Set<Long>>> iterator = roomSubscriptions.entrySet().iterator();
            
            while (iterator.hasNext()) {
                Map.Entry<String, Set<Long>> entry = iterator.next();
                if (entry.getValue().remove(groupId)) {
                    removedCount++;
                    if (entry.getValue().isEmpty()) {
                        iterator.remove();
                    }
                }
            }
            
            if (removedCount > 0) {
                saveSubscriptions();
                logger.info("SubscriptionManager", "清空群组订阅: " + groupId + ", 移除 " + removedCount + " 个房间");
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.error("SubscriptionManager", "清空群组订阅失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 从配置文件加载订阅关系
     */
    private void loadSubscriptions() {
        synchronized (lock) {
            try {
                File configFile = new File(CONFIG_FILE);
                if (!configFile.exists()) {
                    // 创建默认配置文件
                    logger.info("SubscriptionManager", "订阅配置文件不存在，将创建新的配置: " + CONFIG_FILE);
                    createDefaultConfig();
                    return;
                }
                
                try (FileReader reader = new FileReader(configFile)) {
                    StringBuilder content = new StringBuilder();
                    char[] buffer = new char[1024];
                    int length;
                    while ((length = reader.read(buffer)) != -1) {
                        content.append(buffer, 0, length);
                    }
                    
                    JSONObject jsonObject = JSONUtil.parseObj(content.toString());
                    for (String roomId : jsonObject.keySet()) {
                        JSONArray groupArray = jsonObject.getJSONArray(roomId);
                        Set<Long> groupIds = new HashSet<>();
                        for (int i = 0; i < groupArray.size(); i++) {
                            groupIds.add(groupArray.getLong(i));
                        }
                        roomSubscriptions.put(roomId, groupIds);
                    }
                }
                
                logger.info("SubscriptionManager", "成功加载订阅配置，共 " + roomSubscriptions.size() + " 个房间");
            } catch (IOException e) {
                logger.error("SubscriptionManager", "加载订阅配置失败", e);
                createDefaultConfig();
            }
        }
    }
    
    /**
     * 保存订阅配置到文件
     */
    private void saveSubscriptions() {
        synchronized (lock) {
            try {
                File configFile = new File(CONFIG_FILE);
                configFile.getParentFile().mkdirs();
                
                JSONObject jsonObject = new JSONObject();
                for (Map.Entry<String, Set<Long>> entry : roomSubscriptions.entrySet()) {
                    JSONArray groupArray = new JSONArray();
                    for (Long groupId : entry.getValue()) {
                        groupArray.add(groupId);
                    }
                    jsonObject.set(entry.getKey(), groupArray);
                }
                
                try (FileWriter writer = new FileWriter(configFile)) {
                    writer.write(JSONUtil.toJsonPrettyStr(jsonObject));
                }
                
                logger.info("SubscriptionManager", "订阅配置已保存到文件: " + CONFIG_FILE);
            } catch (IOException e) {
                logger.error("SubscriptionManager", "保存订阅配置失败", e);
            }
        }
    }
    
    /**
     * 创建默认配置文件
     */
    private void createDefaultConfig() {
        try {
            File configFile = new File(CONFIG_FILE);
            File parentDir = configFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            // 创建示例配置
            JSONObject defaultConfig = new JSONObject();
            // 可以在这里添加默认的订阅关系
            // JSONArray exampleGroups = new JSONArray();
            // exampleGroups.add(123456789L);
            // defaultConfig.set("示例房间ID", exampleGroups);
            
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(JSONUtil.toJsonPrettyStr(defaultConfig));
                logger.info("SubscriptionManager", "创建默认订阅配置文件: " + CONFIG_FILE);
            }
        } catch (Exception e) {
            logger.error("SubscriptionManager", "创建默认配置文件失败", e);
        }
    }
    
    /**
     * 获取订阅统计信息
     */
    public String getSubscriptionStats() {
        int totalRooms = roomSubscriptions.size();
        int totalSubscriptions = roomSubscriptions.values().stream()
                .mapToInt(Set::size)
                .sum();
        
        return String.format("订阅统计: %d 个房间, %d 个订阅关系", totalRooms, totalSubscriptions);
    }
    
    /**
     * 重新加载配置
     */
    public void reloadConfig() {
        logger.info("SubscriptionManager", "重新加载订阅配置");
        loadSubscriptions();
    }
}