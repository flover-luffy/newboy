package net.luffy.util.summary;

import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import net.luffy.util.UnifiedLogger;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 每日总结内容收集器
 * 负责收集和存储口袋48房间的消息具体内容，用于生成内容摘要
 */
public class DailySummaryDataCollector {
    
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    // 数据存储
    private final Map<String, RoomContentData> currentDayData = new ConcurrentHashMap<>();
    private LocalDate currentDate;
    
    // 配置参数
    private static final String DATA_DIR = "data/daily_content/";
    private static final String DATA_STORAGE_DIR = "data/daily_content/";
    private static final String IMAGE_DIR = "data/daily_images/";
    private static final int DATA_RETENTION_DAYS = 30;
    private static final int MAX_DAILY_MESSAGES_PER_ROOM = 1000; // 每个房间每日最大消息数
    
    // 缓存和处理器
    private final Map<String, RoomContentData> roomDataCache = new ConcurrentHashMap<>();
    private ImageContentProcessor imageProcessor;
    
    // 内容过滤器
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    private static final Pattern MENTION_PATTERN = Pattern.compile("@[\\w\\u4e00-\\u9fa5]+");
    
    private static volatile DailySummaryDataCollector instance;
    
    private DailySummaryDataCollector() {
        this.currentDate = LocalDate.now();
        this.imageProcessor = new ImageContentProcessor();
        initializeDirectories();
        loadTodayData();
    }
    
    public static DailySummaryDataCollector getInstance() {
        if (instance == null) {
            synchronized (DailySummaryDataCollector.class) {
                if (instance == null) {
                    instance = new DailySummaryDataCollector();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化目录结构
     */
    private void initializeDirectories() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            Files.createDirectories(Paths.get(IMAGE_DIR));
            logger.info("DailySummaryDataCollector", "数据目录初始化完成");
        } catch (IOException e) {
            logger.error("DailySummaryDataCollector", "创建数据目录失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取房间内容数据
     */
    private RoomContentData getRoomContentData(String roomId, LocalDate date) {
        return getRoomContentData(roomId, "", date);
    }
    
    /**
     * 获取房间内容数据（带房间名称）
     */
    private RoomContentData getRoomContentData(String roomId, String roomName, LocalDate date) {
        String key = roomId + "_" + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return roomDataCache.computeIfAbsent(key, k -> {
            // 尝试从文件加载
            RoomContentData roomData = loadRoomData(roomId, date);
            if (roomData == null) {
                 // 创建新的房间数据
                 roomData = new RoomContentData(roomId, roomName, date.atStartOfDay());
             }
            return roomData;
        });
    }
    
    /**
     * 保存房间数据
     */
    private void saveRoomData(String roomId, LocalDate date, RoomContentData roomData) {
        try {
            String key = roomId + "_" + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            roomDataCache.put(key, roomData);
            
            // 异步保存到文件
            // 这里可以实现文件保存逻辑
            logger.debug("DailySummaryDataCollector", "房间数据已缓存: " + roomId);
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "保存房间数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 从文件加载房间数据
     */
    private RoomContentData loadRoomData(String roomId, LocalDate date) {
        try {
            String filename = DATA_DIR + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "_" + roomId + ".json";
            File file = new File(filename);
            
            if (file.exists()) {
                String jsonStr = Files.readString(Paths.get(filename));
                JSONObject jsonData = JSONUtil.parseObj(jsonStr);
                return parseRoomContentData(jsonData);
            }
        } catch (Exception e) {
            logger.debug("DailySummaryDataCollector", "加载房间数据失败: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * 加载历史数据
     */
    public void loadHistoricalData() {
        try {
            File dataDir = new File(DATA_DIR);
            if (!dataDir.exists()) {
                return;
            }
            
            File[] files = dataDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    try {
                        String jsonStr = Files.readString(file.toPath());
                        JSONObject jsonData = JSONUtil.parseObj(jsonStr);
                        // 处理历史数据
                        logger.debug("DailySummaryDataCollector", "加载历史文件: " + file.getName());
                    } catch (Exception e) {
                        logger.error("DailySummaryDataCollector", "加载历史文件失败: " + file.getName() + ", " + e.getMessage());
                    }
                }
            }
            
            logger.info("DailySummaryDataCollector", "历史数据加载完成");
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "加载历史数据失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定日期的房间数据
     */
    public Map<String, RoomContentData> getRoomDataForDate(LocalDate date) {
        Map<String, RoomContentData> result = new HashMap<>();
        String dateKey = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        
        // 从缓存中查找匹配的数据
        for (Map.Entry<String, RoomContentData> entry : roomDataCache.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("_" + dateKey)) {
                String roomId = key.substring(0, key.lastIndexOf("_"));
                result.put(roomId, entry.getValue());
            }
        }
        
        return result;
    }
    
    /**
     * 获取指定房间和日期的数据
     */
    public RoomContentData getRoomDataForDate(String roomId, LocalDate date) {
        String key = roomId + "_" + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        RoomContentData roomData = roomDataCache.get(key);
        
        if (roomData == null) {
            // 尝试从文件加载
            roomData = loadRoomData(roomId, date);
        }
        
        return roomData;
    }
    
    /**
     * 获取所有活跃房间ID列表
     */
    public List<String> getActiveRoomIds() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(currentDayData.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取指定日期的活跃房间ID列表
     */
    public List<String> getActiveRoomIds(LocalDate date) {
        String dateKey = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        List<String> roomIds = new ArrayList<>();
        
        for (String key : roomDataCache.keySet()) {
            if (key.endsWith("_" + dateKey)) {
                String roomId = key.substring(0, key.lastIndexOf("_"));
                roomIds.add(roomId);
            }
        }
        
        return roomIds;
    }
    
    /**
     * 保存所有数据
     */
    public void saveAllData() {
        try {
            for (Map.Entry<String, RoomContentData> entry : roomDataCache.entrySet()) {
                String key = entry.getKey();
                String[] parts = key.split("_");
                if (parts.length >= 4) { // roomId_yyyy-MM-dd format
                    String roomId = parts[0];
                    String dateStr = parts[1] + "-" + parts[2] + "-" + parts[3];
                    LocalDate date = LocalDate.parse(dateStr);
                    saveRoomData(roomId, date, entry.getValue());
                }
            }
            logger.info("DailySummaryDataCollector", "所有数据保存完成");
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "保存所有数据失败: " + e.getMessage());
        }
    }

    /**
     * 记录消息内容
     */
    public void recordMessage(Pocket48Message message) {
        if (message == null) {
            return;
        }
        
        try {
            Object roomIdObj = message.getRoom().getRoomId();
            String roomId = roomIdObj != null ? String.valueOf(roomIdObj) : null;
            String roomName = message.getRoom().getRoomName() != null ? message.getRoom().getRoomName() : "";
            if (roomId == null || roomId.trim().isEmpty()) {
                return;
            }
            
            LocalDate today = LocalDate.now();
            RoomContentData roomData = getRoomContentData(roomId, roomName, today);
            
            // 记录基本消息信息
            String nickName = message.getNickName() != null ? message.getNickName() : "";
            String starName = message.getStarName() != null ? message.getStarName() : "";
            String content = extractMessageContent(message);
            String resourceUrl = message.getResourceUrl();
            long timestamp = System.currentTimeMillis(); // 使用当前时间戳作为默认值
            
            // 尝试获取消息时间戳
            try {
                // 使用反射尝试获取时间戳
                java.lang.reflect.Method getTimestampMethod = message.getClass().getMethod("getTimestamp");
                Object timestampObj = getTimestampMethod.invoke(message);
                if (timestampObj instanceof Long) {
                    timestamp = (Long) timestampObj;
                } else if (timestampObj instanceof Integer) {
                    timestamp = ((Integer) timestampObj).longValue();
                }
            } catch (Exception e) {
                // 如果getTimestamp方法不存在或出错，使用默认时间戳
                logger.debug("DailySummaryDataCollector", "无法获取消息时间戳，使用当前时间");
            }
            
            Pocket48MessageType messageType = message.getType();
            roomData.recordMessage(nickName, starName, messageType, content, resourceUrl, timestamp);
            
            // 处理图片消息
            if (Pocket48MessageType.IMAGE.equals(messageType) && message.getResourceUrl() != null) {
                // 异步处理图片
                imageProcessor.processImageAsync(message.getResourceUrl(), roomId, message.getMessageId())
                        .thenAccept(imageInfo -> {
                            if (imageInfo != null) {
                                // 将图片信息添加到房间数据中
                                synchronized (roomData) {
                                    roomData.addImageInfo(imageInfo);
                                }
                                logger.debug("DailySummaryDataCollector", 
                                    "图片处理完成: " + imageInfo.url + ", 房间: " + roomId);
                            }
                        })
                        .exceptionally(throwable -> {
                            logger.error("DailySummaryDataCollector", 
                                "图片处理失败: " + message.getResourceUrl() + ", " + throwable.getMessage());
                            return null;
                        });
            }
            
            // 保存数据
            saveRoomData(roomId, today, roomData);
            
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "记录消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 提取消息内容
     */
    private String extractMessageContent(Pocket48Message message) {
        String content = message.getText();
        if (content == null || content.trim().isEmpty()) {
            content = message.getBody();
        }
        
        if (content == null) {
            content = "";
        }
        
        // 清理内容：移除过长的URL，保留@提及
        content = cleanMessageContent(content);
        
        return content;
    }
    
    /**
     * 清理消息内容
     */
    private String cleanMessageContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }
        
        // 替换长URL为占位符
        content = URL_PATTERN.matcher(content).replaceAll("[链接]");
        
        // 限制内容长度
        if (content.length() > 500) {
            content = content.substring(0, 500) + "...";
        }
        
        return content.trim();
    }
    
    /**
     * 获取房间总消息数
     */
    private int getTotalMessageCount(RoomContentData roomData) {
        return roomData.getTextMessages().size() + 
               roomData.getImageMessages().size() + 
               roomData.getAudioMessages().size() + 
               roomData.getVideoMessages().size() + 
               roomData.getLiveMessages().size() + 
               roomData.getOtherMessages().size();
    }
    
    /**
     * 检查日期滚动
     */
    private void checkDateRollover() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            logger.info("DailySummaryDataCollector", "日期滚动: " + currentDate + " -> " + today);
            
            // 保存昨天的数据
            saveDayData(currentDate);
            
            // 清理旧数据
            cleanupOldData();
            
            // 重置当前数据
            currentDayData.clear();
            currentDate = today;
        }
    }
    
    /**
     * 加载今日数据
     */
    private void loadTodayData() {
        try {
            String filename = DATA_DIR + currentDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".json";
            File file = new File(filename);
            
            if (file.exists()) {
                String jsonStr = Files.readString(Paths.get(filename));
                JSONObject jsonData = JSONUtil.parseObj(jsonStr);
                
                // 解析房间数据
                JSONObject roomsData = jsonData.getJSONObject("rooms");
                if (roomsData != null) {
                    for (String roomId : roomsData.keySet()) {
                        JSONObject roomJson = roomsData.getJSONObject(roomId);
                        RoomContentData roomData = parseRoomContentData(roomJson);
                        if (roomData != null) {
                            currentDayData.put(roomId, roomData);
                        }
                    }
                }
                
                logger.info("DailySummaryDataCollector", 
                    "加载今日数据完成，房间数: " + currentDayData.size());
            }
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "加载今日数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 保存日期数据
     */
    private void saveDayData(LocalDate date) {
        try {
            if (currentDayData.isEmpty()) {
                return;
            }
            
            JSONObject jsonData = new JSONObject();
            jsonData.set("date", date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            jsonData.set("timestamp", System.currentTimeMillis());
            
            JSONObject roomsData = new JSONObject();
            for (Map.Entry<String, RoomContentData> entry : currentDayData.entrySet()) {
                roomsData.set(entry.getKey(), serializeRoomContentData(entry.getValue()));
            }
            jsonData.set("rooms", roomsData);
            
            String filename = DATA_DIR + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".json";
            Files.writeString(Paths.get(filename), jsonData.toStringPretty());
            
            logger.info("DailySummaryDataCollector", 
                "保存日期数据完成: " + date + ", 房间数: " + currentDayData.size());
                
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "保存日期数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 序列化房间内容数据
     */
    private JSONObject serializeRoomContentData(RoomContentData roomData) {
        JSONObject json = new JSONObject();
        
        json.set("roomId", roomData.getRoomId());
        json.set("roomName", roomData.getRoomName());
        json.set("date", roomData.getDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        
        // 序列化消息内容（只保存摘要，不保存全部内容以节省空间）
        RoomContentData.ContentSummary summary = roomData.getContentSummary();
        json.set("summary", serializeContentSummary(summary));
        
        // 保存部分精选内容
        json.set("highlightTexts", serializeMessageContents(summary.highlightTexts));
        json.set("topKeywords", summary.topKeywords);
        json.set("topActiveMembers", serializeMemberActivities(summary.topActiveMembers));
        json.set("activeHours", summary.activeHours);
        
        return json;
    }
    
    /**
     * 序列化内容摘要
     */
    private JSONObject serializeContentSummary(RoomContentData.ContentSummary summary) {
        JSONObject json = new JSONObject();
        json.set("totalMessageCount", summary.totalMessageCount);
        json.set("textMessageCount", summary.textMessageCount);
        json.set("imageMessageCount", summary.imageMessageCount);
        json.set("audioMessageCount", summary.audioMessageCount);
        json.set("videoMessageCount", summary.videoMessageCount);
        json.set("liveMessageCount", summary.liveMessageCount);
        json.set("otherMessageCount", summary.otherMessageCount);
        return json;
    }
    
    /**
     * 序列化消息内容列表
     */
    private List<JSONObject> serializeMessageContents(List<RoomContentData.MessageContent> messages) {
        return messages.stream().map(msg -> {
            JSONObject json = new JSONObject();
            json.set("nickName", msg.nickName);
            json.set("starName", msg.starName);
            json.set("content", msg.content);
            json.set("timestamp", msg.timestamp);
            json.set("hour", msg.hour);
            return json;
        }).collect(Collectors.toList());
    }
    
    /**
     * 序列化成员活跃度列表
     */
    private List<JSONObject> serializeMemberActivities(List<RoomContentData.MemberActivity> activities) {
        return activities.stream().map(activity -> {
            JSONObject json = new JSONObject();
            json.set("nickName", activity.getNickName());
            json.set("starName", activity.getStarName());
            json.set("messageCount", activity.getMessageCount());
            json.set("messageTypes", activity.getMessageTypes().stream()
                    .map(Enum::name).collect(Collectors.toList()));
            return json;
        }).collect(Collectors.toList());
    }
    
    /**
     * 解析房间内容数据
     */
    private RoomContentData parseRoomContentData(JSONObject json) {
        try {
            String roomId = json.getStr("roomId");
            String roomName = json.getStr("roomName");
            LocalDateTime date = LocalDateTime.parse(json.getStr("date"), 
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            RoomContentData roomData = new RoomContentData(roomId, roomName, date);
            
            // 这里可以根据需要解析更多详细数据
            // 目前只解析摘要信息以节省内存
            
            return roomData;
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "解析房间数据失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 清理旧数据
     */
    private void cleanupOldData() {
        try {
            LocalDate cutoffDate = LocalDate.now().minusDays(DATA_RETENTION_DAYS);
            
            // 清理过期的房间数据文件
            Files.list(Paths.get(DATA_STORAGE_DIR))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> {
                        try {
                            String filename = path.getFileName().toString();
                            String dateStr = filename.substring(filename.lastIndexOf('_') + 1, filename.lastIndexOf('.'));
                            LocalDate fileDate = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                            return fileDate.isBefore(cutoffDate);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                            logger.info("DailySummaryDataCollector", "删除过期数据文件: " + path.getFileName());
                        } catch (Exception e) {
                            logger.warn("DailySummaryDataCollector", "删除文件失败: " + path.getFileName());
                        }
                    });
            
            // 清理图片缓存
            imageProcessor.cleanupExpiredCache();
            
            logger.info("DailySummaryDataCollector", "清理过期数据完成");
            
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "清理过期数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 关闭收集器
     */
    public void shutdown() {
        try {
            // 保存所有待保存的数据
            for (Map.Entry<String, RoomContentData> entry : roomDataCache.entrySet()) {
                String[] parts = entry.getKey().split("_");
                if (parts.length == 2) {
                    String roomId = parts[0];
                    LocalDate date = LocalDate.parse(parts[1]);
                    saveRoomData(roomId, date, entry.getValue());
                }
            }
            
            // 关闭图片处理器
            imageProcessor.shutdown();
            
            logger.info("DailySummaryDataCollector", "数据收集器已关闭");
            
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "关闭数据收集器失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取指定日期的内容摘要
     */
    public Map<String, RoomContentData.ContentSummary> getDayContentSummary(LocalDate date) {
        lock.readLock().lock();
        try {
            if (date.equals(currentDate)) {
                // 返回当前数据的摘要
                Map<String, RoomContentData.ContentSummary> summaries = new HashMap<>();
                for (Map.Entry<String, RoomContentData> entry : currentDayData.entrySet()) {
                    summaries.put(entry.getKey(), entry.getValue().getContentSummary());
                }
                return summaries;
            } else {
                // 从文件加载历史数据摘要
                return loadHistoryContentSummary(date);
            }
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 加载历史内容摘要
     */
    private Map<String, RoomContentData.ContentSummary> loadHistoryContentSummary(LocalDate date) {
        Map<String, RoomContentData.ContentSummary> summaries = new HashMap<>();
        
        try {
            String filename = DATA_DIR + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".json";
            File file = new File(filename);
            
            if (file.exists()) {
                String jsonStr = Files.readString(Paths.get(filename));
                JSONObject jsonData = JSONUtil.parseObj(jsonStr);
                JSONObject roomsData = jsonData.getJSONObject("rooms");
                
                if (roomsData != null) {
                    for (String roomId : roomsData.keySet()) {
                        JSONObject roomJson = roomsData.getJSONObject(roomId);
                        RoomContentData.ContentSummary summary = parseContentSummary(roomJson);
                        if (summary != null) {
                            summaries.put(roomId, summary);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "加载历史摘要失败: " + e.getMessage());
        }
        
        return summaries;
    }
    
    /**
     * 解析内容摘要
     */
    private RoomContentData.ContentSummary parseContentSummary(JSONObject roomJson) {
        try {
            RoomContentData.ContentSummary summary = new RoomContentData.ContentSummary();
            
            summary.roomId = roomJson.getStr("roomId");
            summary.roomName = roomJson.getStr("roomName");
            summary.date = LocalDateTime.parse(roomJson.getStr("date"), 
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            JSONObject summaryJson = roomJson.getJSONObject("summary");
            if (summaryJson != null) {
                summary.totalMessageCount = summaryJson.getInt("totalMessageCount", 0);
                summary.textMessageCount = summaryJson.getInt("textMessageCount", 0);
                summary.imageMessageCount = summaryJson.getInt("imageMessageCount", 0);
                summary.audioMessageCount = summaryJson.getInt("audioMessageCount", 0);
                summary.videoMessageCount = summaryJson.getInt("videoMessageCount", 0);
                summary.liveMessageCount = summaryJson.getInt("liveMessageCount", 0);
                summary.otherMessageCount = summaryJson.getInt("otherMessageCount", 0);
            }
            
            // 解析其他字段
            summary.topKeywords = roomJson.getBeanList("topKeywords", String.class);
            if (summary.topKeywords == null) summary.topKeywords = new ArrayList<>();
            
            summary.activeHours = new HashMap<>();
            JSONObject activeHoursJson = roomJson.getJSONObject("activeHours");
            if (activeHoursJson != null) {
                for (String hour : activeHoursJson.keySet()) {
                    summary.activeHours.put(Integer.parseInt(hour), activeHoursJson.getInt(hour));
                }
            }
            
            return summary;
        } catch (Exception e) {
            logger.error("DailySummaryDataCollector", "解析内容摘要失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取今日消息总数
     */
    public int getTodayMessageCount() {
        lock.readLock().lock();
        try {
            return currentDayData.values().stream()
                    .mapToInt(this::getTotalMessageCount)
                    .sum();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取活跃房间数
     */
    public int getActiveRoomCount() {
        lock.readLock().lock();
        try {
            return currentDayData.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 获取今日概览
     */
    public String getTodayOverview() {
        lock.readLock().lock();
        try {
            if (currentDayData.isEmpty()) {
                return "📊 今日暂无数据";
            }
            
            int totalRooms = currentDayData.size();
            int totalMessages = currentDayData.values().stream()
                    .mapToInt(this::getTotalMessageCount)
                    .sum();
            
            StringBuilder sb = new StringBuilder();
            sb.append("📊 今日数据概览\n");
            sb.append("监控房间: ").append(totalRooms).append("个\n");
            sb.append("收集消息: ").append(totalMessages).append("条\n");
            
            // 显示最活跃的房间
            Optional<Map.Entry<String, RoomContentData>> mostActive = currentDayData.entrySet().stream()
                    .max(Comparator.comparingInt(entry -> getTotalMessageCount(entry.getValue())));
            
            if (mostActive.isPresent()) {
                RoomContentData roomData = mostActive.get().getValue();
                sb.append("最活跃房间: ").append(roomData.getRoomName())
                  .append(" (").append(getTotalMessageCount(roomData)).append("条)");
            }
            
            return sb.toString();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * 手动保存当前数据
     */
    public void saveCurrentData() {
        lock.writeLock().lock();
        try {
            saveDayData(currentDate);
            logger.info("DailySummaryDataCollector", "手动保存数据完成");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * 获取系统状态
     */
    public String getSystemStatus() {
        lock.readLock().lock();
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("🔧 内容收集系统状态\n");
            sb.append("当前日期: ").append(currentDate).append("\n");
            sb.append("监控房间数: ").append(currentDayData.size()).append("\n");
            sb.append("数据目录: ").append(DATA_DIR).append("\n");
            sb.append("图片目录: ").append(IMAGE_DIR).append("\n");
            sb.append("数据保留天数: ").append(DATA_RETENTION_DAYS).append("天\n");
            
            // 内存使用情况
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            sb.append("内存使用: ").append(usedMemory / 1024 / 1024).append("MB");
            
            return sb.toString();
        } finally {
            lock.readLock().unlock();
        }
    }
}