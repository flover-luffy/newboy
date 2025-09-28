package net.luffy.util.summary;

import net.luffy.model.Pocket48MessageType;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 房间内容数据
 * 存储单个房间每日的具体消息内容，用于生成内容摘要
 */
public class RoomContentData {
    
    private String roomId;
    private String roomName;
    private LocalDateTime date;
    
    // 消息内容存储
    private List<MessageContent> textMessages;      // 文字消息
    private List<MediaContent> imageMessages;       // 图片消息
    private List<MediaContent> audioMessages;       // 语音消息
    private List<MediaContent> videoMessages;       // 视频消息
    private List<MessageContent> liveMessages;      // 直播消息
    private List<MessageContent> otherMessages;     // 其他类型消息
    
    // 成员活跃度
    private Map<String, MemberActivity> memberActivities;
    
    // 热门话题和关键词
    private List<String> hotTopics;
    private Map<String, Integer> keywordFrequency;
    
    public RoomContentData() {
        this.textMessages = new ArrayList<>();
        this.imageMessages = new ArrayList<>();
        this.audioMessages = new ArrayList<>();
        this.videoMessages = new ArrayList<>();
        this.liveMessages = new ArrayList<>();
        this.otherMessages = new ArrayList<>();
        this.memberActivities = new HashMap<>();
        this.hotTopics = new ArrayList<>();
        this.keywordFrequency = new HashMap<>();
    }
    
    public RoomContentData(String roomId, String roomName, LocalDateTime date) {
        this();
        this.roomId = roomId;
        this.roomName = roomName;
        this.date = date;
    }
    
    /**
     * 记录消息内容
     */
    public void recordMessage(String nickName, String starName, Pocket48MessageType type, 
                             String content, String resourceUrl, long timestamp) {
        
        // 更新成员活跃度
        updateMemberActivity(nickName, starName, type);
        
        // 根据消息类型分类存储
        switch (type) {
            case TEXT:
            case GIFT_TEXT:
            case REPLY:
            case GIFTREPLY:
            case FAIPAI_TEXT:
            case AGENT_QCHAT_TEXT:
            case AGENT_QCHAT_TEXT_REPLY:
            case AGENT_WARMUP_TEXT:
                recordTextMessage(nickName, starName, content, timestamp);
                extractKeywords(content);
                break;
                
            case IMAGE:
            case EXPRESSIMAGE:
            case GIFT_SKILL_IMG:
            case AGENT_WARMUP_IMG:
                recordImageMessage(nickName, starName, content, resourceUrl, timestamp);
                break;
                
            case AUDIO:
            case FLIPCARD_AUDIO:
            case AGENT_WARMUP_AUDIO:
                recordAudioMessage(nickName, starName, content, resourceUrl, timestamp);
                break;
                
            case VIDEO:
            case FLIPCARD_VIDEO:
            case AGENT_WARMUP_VIDEO:
                recordVideoMessage(nickName, starName, content, resourceUrl, timestamp);
                break;
                
            case LIVEPUSH:
            case SHARE_LIVE:
                recordLiveMessage(nickName, starName, content, resourceUrl, timestamp);
                break;
                
            default:
                recordOtherMessage(nickName, starName, type.name(), content, resourceUrl, timestamp);
                break;
        }
    }
    
    /**
     * 记录文字消息
     */
    private void recordTextMessage(String nickName, String starName, String content, long timestamp) {
        MessageContent message = new MessageContent();
        message.nickName = nickName;
        message.starName = starName;
        message.content = content;
        message.timestamp = timestamp;
        message.hour = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), 
                                             java.time.ZoneId.systemDefault()).getHour();
        textMessages.add(message);
    }
    
    /**
     * 记录图片消息
     */
    private void recordImageMessage(String nickName, String starName, String content, 
                                   String resourceUrl, long timestamp) {
        MediaContent media = new MediaContent();
        media.nickName = nickName;
        media.starName = starName;
        media.content = content;
        media.resourceUrl = resourceUrl;
        media.timestamp = timestamp;
        media.hour = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), 
                                           java.time.ZoneId.systemDefault()).getHour();
        imageMessages.add(media);
    }
    
    /**
     * 记录语音消息
     */
    private void recordAudioMessage(String nickName, String starName, String content, 
                                   String resourceUrl, long timestamp) {
        MediaContent media = new MediaContent();
        media.nickName = nickName;
        media.starName = starName;
        media.content = content;
        media.resourceUrl = resourceUrl;
        media.timestamp = timestamp;
        media.hour = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), 
                                           java.time.ZoneId.systemDefault()).getHour();
        audioMessages.add(media);
    }
    
    /**
     * 记录视频消息
     */
    private void recordVideoMessage(String nickName, String starName, String content, 
                                   String resourceUrl, long timestamp) {
        MediaContent media = new MediaContent();
        media.nickName = nickName;
        media.starName = starName;
        media.content = content;
        media.resourceUrl = resourceUrl;
        media.timestamp = timestamp;
        media.hour = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), 
                                           java.time.ZoneId.systemDefault()).getHour();
        videoMessages.add(media);
    }
    
    /**
     * 记录直播消息
     */
    private void recordLiveMessage(String nickName, String starName, String content, 
                                  String resourceUrl, long timestamp) {
        MessageContent message = new MessageContent();
        message.nickName = nickName;
        message.starName = starName;
        message.content = content;
        message.timestamp = timestamp;
        message.hour = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), 
                                             java.time.ZoneId.systemDefault()).getHour();
        liveMessages.add(message);
    }
    
    /**
     * 记录其他类型消息
     */
    private void recordOtherMessage(String nickName, String starName, String messageType, 
                                   String content, String resourceUrl, long timestamp) {
        MessageContent message = new MessageContent();
        message.nickName = nickName;
        message.starName = starName;
        message.content = "[" + messageType + "] " + content;
        message.timestamp = timestamp;
        message.hour = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), 
                                             java.time.ZoneId.systemDefault()).getHour();
        otherMessages.add(message);
    }
    
    /**
     * 更新成员活跃度
     */
    private void updateMemberActivity(String nickName, String starName, Pocket48MessageType type) {
        String key = nickName + (starName != null ? " (" + starName + ")" : "");
        MemberActivity activity = memberActivities.computeIfAbsent(key, k -> new MemberActivity(nickName, starName));
        activity.incrementMessageCount();
        activity.addMessageType(type);
    }
    
    /**
     * 提取关键词
     */
    private void extractKeywords(String content) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        
        // 简单的关键词提取：去除标点符号，分词，统计频率
        String cleanContent = content.replaceAll("[\\p{Punct}\\s]+", " ").trim();
        String[] words = cleanContent.split("\\s+");
        
        for (String word : words) {
            if (word.length() >= 2 && !isStopWord(word)) {
                keywordFrequency.merge(word, 1, Integer::sum);
            }
        }
    }
    
    /**
     * 判断是否为停用词
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of("的", "了", "在", "是", "我", "你", "他", "她", "它", "们", 
                                      "这", "那", "有", "和", "与", "或", "但", "不", "没", "很", 
                                      "就", "都", "被", "从", "把", "为", "所", "以", "及", "等");
        return stopWords.contains(word);
    }
    
    /**
     * 获取热门关键词
     */
    public List<String> getTopKeywords(int limit) {
        return keywordFrequency.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取最活跃成员
     */
    public List<MemberActivity> getTopActiveMembers(int limit) {
        return memberActivities.values().stream()
                .sorted((a, b) -> Integer.compare(b.getMessageCount(), a.getMessageCount()))
                .limit(limit)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取内容摘要
     */
    public ContentSummary getContentSummary() {
        ContentSummary summary = new ContentSummary();
        summary.roomId = this.roomId;
        summary.roomName = this.roomName;
        summary.date = this.date;
        
        // 统计各类消息数量
        summary.textMessageCount = textMessages.size();
        summary.imageMessageCount = imageMessages.size();
        summary.audioMessageCount = audioMessages.size();
        summary.videoMessageCount = videoMessages.size();
        summary.liveMessageCount = liveMessages.size();
        summary.otherMessageCount = otherMessages.size();
        summary.totalMessageCount = summary.textMessageCount + summary.imageMessageCount + 
                                   summary.audioMessageCount + summary.videoMessageCount + 
                                   summary.liveMessageCount + summary.otherMessageCount;
        
        // 获取热门内容
        summary.topKeywords = getTopKeywords(10);
        summary.topActiveMembers = getTopActiveMembers(5);
        
        // 获取精选文字内容（最长的几条消息作为代表）
        summary.highlightTexts = textMessages.stream()
                .filter(msg -> msg.content != null && msg.content.length() > 10)
                .sorted((a, b) -> Integer.compare(b.content.length(), a.content.length()))
                .limit(5)
                .collect(Collectors.toList());
        
        // 获取活跃时段
        summary.activeHours = calculateActiveHours();
        
        return summary;
    }
    
    /**
     * 计算活跃时段
     */
    private Map<Integer, Integer> calculateActiveHours() {
        Map<Integer, Integer> hourlyCount = new HashMap<>();
        
        // 统计所有消息的小时分布
        List<MessageContent> allMessages = new ArrayList<>();
        allMessages.addAll(textMessages);
        allMessages.addAll(liveMessages);
        allMessages.addAll(otherMessages);
        
        for (MessageContent msg : allMessages) {
            hourlyCount.merge(msg.hour, 1, Integer::sum);
        }
        
        // 添加媒体消息的小时分布
        List<MediaContent> allMediaMessages = new ArrayList<>();
        allMediaMessages.addAll(imageMessages);
        allMediaMessages.addAll(audioMessages);
        allMediaMessages.addAll(videoMessages);
        
        for (MediaContent msg : allMediaMessages) {
            hourlyCount.merge(msg.hour, 1, Integer::sum);
        }
        
        return hourlyCount;
    }
    
    // Getter和Setter方法
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    
    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }
    
    public LocalDateTime getDate() { return date; }
    public void setDate(LocalDateTime date) { this.date = date; }
    
    public List<MessageContent> getTextMessages() { return new ArrayList<>(textMessages); }
    public List<MediaContent> getImageMessages() { return new ArrayList<>(imageMessages); }
    public List<MediaContent> getAudioMessages() { return new ArrayList<>(audioMessages); }
    public List<MediaContent> getVideoMessages() { return new ArrayList<>(videoMessages); }
    public List<MessageContent> getLiveMessages() { return new ArrayList<>(liveMessages); }
    public List<MessageContent> getOtherMessages() { return new ArrayList<>(otherMessages); }
    
    public Map<String, MemberActivity> getMemberActivities() { return new HashMap<>(memberActivities); }
    public Map<String, Integer> getKeywordFrequency() { return new HashMap<>(keywordFrequency); }
    
    /**
     * 消息内容类
     */
    public static class MessageContent {
        public String nickName;
        public String starName;
        public String content;
        public long timestamp;
        public int hour;
        
        public String getDisplayName() {
            return starName != null ? starName : nickName;
        }
        
        public String getFormattedTime() {
            return DateTimeFormatter.ofPattern("HH:mm:ss")
                    .format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), 
                                                  java.time.ZoneId.systemDefault()));
        }
    }
    
    /**
     * 媒体内容类
     */
    public static class MediaContent extends MessageContent {
        public String resourceUrl;
        public String thumbnailUrl;
    }
    
    /**
     * 成员活跃度类
     */
    public static class MemberActivity {
        private String nickName;
        private String starName;
        private int messageCount;
        private Set<Pocket48MessageType> messageTypes;
        
        public MemberActivity(String nickName, String starName) {
            this.nickName = nickName;
            this.starName = starName;
            this.messageCount = 0;
            this.messageTypes = new HashSet<>();
        }
        
        public void incrementMessageCount() {
            this.messageCount++;
        }
        
        public void addMessageType(Pocket48MessageType type) {
            this.messageTypes.add(type);
        }
        
        public String getDisplayName() {
            return starName != null ? starName : nickName;
        }
        
        // Getter方法
        public String getNickName() { return nickName; }
        public String getStarName() { return starName; }
        public int getMessageCount() { return messageCount; }
        public Set<Pocket48MessageType> getMessageTypes() { return new HashSet<>(messageTypes); }
    }
    
    /**
     * 内容摘要类
     */
    public static class ContentSummary {
        public String roomId;
        public String roomName;
        public LocalDateTime date;
        
        public int totalMessageCount;
        public int textMessageCount;
        public int imageMessageCount;
        public int audioMessageCount;
        public int videoMessageCount;
        public int liveMessageCount;
        public int otherMessageCount;
        
        public List<String> topKeywords;
        public List<MemberActivity> topActiveMembers;
        public List<MessageContent> highlightTexts;
        public Map<Integer, Integer> activeHours;
        
        public String generateTextSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("【").append(roomName).append("】每日内容摘要\n");
            sb.append("日期: ").append(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).append("\n\n");
            
            sb.append("📊 消息统计:\n");
            sb.append("总消息数: ").append(totalMessageCount).append("\n");
            sb.append("文字消息: ").append(textMessageCount).append("\n");
            sb.append("图片消息: ").append(imageMessageCount).append("\n");
            sb.append("语音消息: ").append(audioMessageCount).append("\n");
            sb.append("视频消息: ").append(videoMessageCount).append("\n");
            sb.append("直播消息: ").append(liveMessageCount).append("\n\n");
            
            if (!topActiveMembers.isEmpty()) {
                sb.append("👥 活跃成员:\n");
                for (int i = 0; i < Math.min(3, topActiveMembers.size()); i++) {
                    MemberActivity member = topActiveMembers.get(i);
                    sb.append((i + 1)).append(". ").append(member.getDisplayName())
                      .append(" (").append(member.getMessageCount()).append("条)\n");
                }
                sb.append("\n");
            }
            
            if (!topKeywords.isEmpty()) {
                sb.append("🔥 热门关键词:\n");
                sb.append(String.join(", ", topKeywords.subList(0, Math.min(8, topKeywords.size()))));
                sb.append("\n\n");
            }
            
            if (!highlightTexts.isEmpty()) {
                sb.append("💬 精选内容:\n");
                for (int i = 0; i < Math.min(3, highlightTexts.size()); i++) {
                    MessageContent msg = highlightTexts.get(i);
                    sb.append("• ").append(msg.getDisplayName()).append(": ");
                    String content = msg.content.length() > 50 ? 
                                   msg.content.substring(0, 50) + "..." : msg.content;
                    sb.append(content).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
    
    // 图片信息列表
    private List<ImageContentProcessor.ImageInfo> imageInfos = new ArrayList<>();

    /**
     * 添加图片信息
     */
    public void addImageInfo(ImageContentProcessor.ImageInfo imageInfo) {
        if (imageInfo != null) {
            imageInfos.add(imageInfo);
        }
    }

    /**
     * 获取图片信息列表
     */
    public List<ImageContentProcessor.ImageInfo> getImageInfos() {
        return new ArrayList<>(imageInfos);
    }

    /**
     * 获取图片统计信息
     */
    public Map<String, Object> getImageStats() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("totalImages", imageInfos.size());
        stats.put("totalImageSize", imageInfos.stream().mapToLong(img -> img.fileSize).sum());
        stats.put("duplicateImages", imageInfos.stream().mapToInt(img -> img.isDuplicate ? 1 : 0).sum());
        
        // 按格式统计
        Map<String, Long> formatCount = imageInfos.stream()
                .collect(Collectors.groupingBy(img -> img.format, Collectors.counting()));
        stats.put("formatDistribution", formatCount);
        
        // 按类型统计
        Map<String, Long> typeCount = imageInfos.stream()
                .collect(Collectors.groupingBy(img -> img.imageType, Collectors.counting()));
        stats.put("typeDistribution", typeCount);
        
        return stats;
    }
}