package net.luffy.util.summary;

import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 房间每日统计数据
 * 记录单个房间在一天内的详细消息统计信息
 */
public class RoomDailyStats {
    
    private final long roomId;
    private final String roomName;
    private final String ownerName;
    
    // 基础统计
    private final AtomicLong totalMessages = new AtomicLong(0);
    private final AtomicLong textMessages = new AtomicLong(0);
    private final AtomicLong imageMessages = new AtomicLong(0);
    private final AtomicLong audioMessages = new AtomicLong(0);
    private final AtomicLong videoMessages = new AtomicLong(0);
    private final AtomicLong otherMessages = new AtomicLong(0);
    
    // 时间统计
    private final Map<Integer, AtomicInteger> hourlyStats = new ConcurrentHashMap<>();
    private volatile LocalDateTime firstMessageTime;
    private volatile LocalDateTime lastMessageTime;
    
    // 消息类型统计
    private final Map<String, AtomicInteger> messageTypeStats = new ConcurrentHashMap<>();
    
    // 成员活跃度统计
    private final Map<String, AtomicInteger> memberActivityStats = new ConcurrentHashMap<>();
    
    // 热门关键词统计
    private final Map<String, AtomicInteger> keywordStats = new ConcurrentHashMap<>();
    
    public RoomDailyStats(long roomId, String roomName, String ownerName) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.ownerName = ownerName;
        
        // 初始化24小时统计
        for (int i = 0; i < 24; i++) {
            hourlyStats.put(i, new AtomicInteger(0));
        }
    }
    
    /**
     * 记录消息
     */
    public void recordMessage(Pocket48Message message) {
        if (message == null) return;
        
        totalMessages.incrementAndGet();
        
        // 记录消息时间
        LocalDateTime messageTime = LocalDateTime.now();
        if (firstMessageTime == null) {
            firstMessageTime = messageTime;
        }
        lastMessageTime = messageTime;
        
        // 记录小时统计
        int hour = messageTime.getHour();
        hourlyStats.get(hour).incrementAndGet();
        
        // 记录消息类型
        String messageType = getMessageType(message);
        messageTypeStats.computeIfAbsent(messageType, k -> new AtomicInteger(0)).incrementAndGet();
        
        // 记录基础类型统计
        switch (messageType) {
            case "文本":
                textMessages.incrementAndGet();
                // 提取关键词（仅对文本消息）
                if (message.getType() == Pocket48MessageType.TEXT || 
                    message.getType() == Pocket48MessageType.GIFT_TEXT ||
                    message.getType() == Pocket48MessageType.REPLY ||
                    message.getType() == Pocket48MessageType.GIFTREPLY) {
                    extractKeywords(message.getBody());
                }
                break;
            case "图片":
                imageMessages.incrementAndGet();
                break;
            case "音频":
                audioMessages.incrementAndGet();
                break;
            case "视频":
                videoMessages.incrementAndGet();
                break;
            default:
                otherMessages.incrementAndGet();
                break;
        }
        
        // 记录成员活跃度
        String memberName = message.getNickName();
        if (memberName != null && !memberName.isEmpty()) {
            memberActivityStats.computeIfAbsent(memberName, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }
    
    /**
     * 获取消息类型
     */
    private String getMessageType(Pocket48Message message) {
        Pocket48MessageType type = message.getType();
        if (type == null) return "其他";
        
        switch (type) {
            case TEXT:
            case GIFT_TEXT:
                return "文本";
            case IMAGE:
            case EXPRESSIMAGE:
                return "图片";
            case AUDIO:
            case FLIPCARD_AUDIO:
                return "音频";
            case VIDEO:
            case FLIPCARD_VIDEO:
                return "视频";
            case REPLY:
            case GIFTREPLY:
                return "回复";
            case FLIPCARD:
                return "翻牌";
            case LIVEPUSH:
                return "直播";
            default:
                return "其他";
        }
    }
    
    /**
     * 提取关键词（简单实现）
     */
    private void extractKeywords(String content) {
        if (content == null || content.trim().isEmpty()) return;
        
        // 简单的关键词提取：分割文本并统计常见词汇
        String[] words = content.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", " ")
                                .split("\\s+");
        
        for (String word : words) {
            word = word.trim();
            if (word.length() >= 2 && word.length() <= 10) { // 过滤长度
                keywordStats.computeIfAbsent(word, k -> new AtomicInteger(0)).incrementAndGet();
            }
        }
    }
    
    /**
     * 获取活跃时段分布
     */
    public Map<Integer, Integer> getHourlyStats() {
        Map<Integer, Integer> result = new HashMap<>();
        for (Map.Entry<Integer, AtomicInteger> entry : hourlyStats.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }
    
    /**
     * 获取消息类型统计
     */
    public Map<String, Integer> getMessageTypeStats() {
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<String, AtomicInteger> entry : messageTypeStats.entrySet()) {
            result.put(entry.getKey(), entry.getValue().get());
        }
        return result;
    }
    
    /**
     * 获取成员活跃度排行
     */
    public List<MemberActivity> getTopActiveMembers(int limit) {
        return memberActivityStats.entrySet().stream()
                .map(entry -> new MemberActivity(entry.getKey(), entry.getValue().get()))
                .sorted((a, b) -> Integer.compare(b.getMessageCount(), a.getMessageCount()))
                .limit(limit)
                .collect(ArrayList::new, (list, item) -> list.add(item), ArrayList::addAll);
    }
    
    /**
     * 获取热门关键词
     */
    public List<KeywordFrequency> getTopKeywords(int limit) {
        return keywordStats.entrySet().stream()
                .filter(entry -> entry.getValue().get() >= 3) // 至少出现3次
                .map(entry -> new KeywordFrequency(entry.getKey(), entry.getValue().get()))
                .sorted((a, b) -> Integer.compare(b.getFrequency(), a.getFrequency()))
                .limit(limit)
                .collect(ArrayList::new, (list, item) -> list.add(item), ArrayList::addAll);
    }
    
    /**
     * 获取活跃时段描述
     */
    public String getActiveTimeDescription() {
        Map<Integer, Integer> hourly = getHourlyStats();
        int maxHour = hourly.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(12);
        
        String timeRange;
        if (maxHour >= 6 && maxHour < 12) {
            timeRange = "上午";
        } else if (maxHour >= 12 && maxHour < 18) {
            timeRange = "下午";
        } else if (maxHour >= 18 && maxHour < 24) {
            timeRange = "晚上";
        } else {
            timeRange = "深夜";
        }
        
        return String.format("%s (%d:00-%d:00)", timeRange, maxHour, (maxHour + 1) % 24);
    }
    
    // Getters
    public long getRoomId() { return roomId; }
    public String getRoomName() { return roomName; }
    public String getOwnerName() { return ownerName; }
    public long getTotalMessages() { return totalMessages.get(); }
    public long getTextMessages() { return textMessages.get(); }
    public long getImageMessages() { return imageMessages.get(); }
    public long getAudioMessages() { return audioMessages.get(); }
    public long getVideoMessages() { return videoMessages.get(); }
    public long getOtherMessages() { return otherMessages.get(); }
    public LocalDateTime getFirstMessageTime() { return firstMessageTime; }
    public LocalDateTime getLastMessageTime() { return lastMessageTime; }
    
    /**
     * 转换为JSON
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("roomId", roomId);
        json.put("roomName", roomName);
        json.put("ownerName", ownerName);
        json.put("totalMessages", totalMessages.get());
        json.put("textMessages", textMessages.get());
        json.put("imageMessages", imageMessages.get());
        json.put("audioMessages", audioMessages.get());
        json.put("videoMessages", videoMessages.get());
        json.put("otherMessages", otherMessages.get());
        
        if (firstMessageTime != null) {
            json.put("firstMessageTime", firstMessageTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        if (lastMessageTime != null) {
            json.put("lastMessageTime", lastMessageTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        
        json.put("hourlyStats", getHourlyStats());
        json.put("messageTypeStats", getMessageTypeStats());
        json.put("topMembers", getTopActiveMembers(10));
        json.put("topKeywords", getTopKeywords(20));
        
        return json;
    }
    
    /**
     * 从JSON创建对象
     */
    public static RoomDailyStats fromJson(JSONObject json) {
        RoomDailyStats stats = new RoomDailyStats(
            json.getLong("roomId"),
            json.getStr("roomName"),
            json.getStr("ownerName")
        );
        
        stats.totalMessages.set(json.getLong("totalMessages", 0L));
        stats.textMessages.set(json.getLong("textMessages", 0L));
        stats.imageMessages.set(json.getLong("imageMessages", 0L));
        stats.audioMessages.set(json.getLong("audioMessages", 0L));
        stats.videoMessages.set(json.getLong("videoMessages", 0L));
        stats.otherMessages.set(json.getLong("otherMessages", 0L));
        
        String firstTimeStr = json.getStr("firstMessageTime");
        if (firstTimeStr != null) {
            stats.firstMessageTime = LocalDateTime.parse(firstTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        
        String lastTimeStr = json.getStr("lastMessageTime");
        if (lastTimeStr != null) {
            stats.lastMessageTime = LocalDateTime.parse(lastTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        
        // 恢复小时统计
        JSONObject hourlyJson = json.getJSONObject("hourlyStats");
        if (hourlyJson != null) {
            for (String key : hourlyJson.keySet()) {
                int hour = Integer.parseInt(key);
                int count = hourlyJson.getInt(key);
                stats.hourlyStats.get(hour).set(count);
            }
        }
        
        // 恢复消息类型统计
        JSONObject typeJson = json.getJSONObject("messageTypeStats");
        if (typeJson != null) {
            for (String key : typeJson.keySet()) {
                stats.messageTypeStats.put(key, new AtomicInteger(typeJson.getInt(key)));
            }
        }
        
        return stats;
    }
    
    /**
     * 成员活跃度数据类
     */
    public static class MemberActivity {
        private final String memberName;
        private final int messageCount;
        
        public MemberActivity(String memberName, int messageCount) {
            this.memberName = memberName;
            this.messageCount = messageCount;
        }
        
        public String getMemberName() { return memberName; }
        public int getMessageCount() { return messageCount; }
    }
    
    /**
     * 关键词频率数据类
     */
    public static class KeywordFrequency {
        private final String keyword;
        private final int frequency;
        
        public KeywordFrequency(String keyword, int frequency) {
            this.keyword = keyword;
            this.frequency = frequency;
        }
        
        public String getKeyword() { return keyword; }
        public int getFrequency() { return frequency; }
    }
}