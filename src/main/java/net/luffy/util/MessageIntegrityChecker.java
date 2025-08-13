package net.luffy.util;

import net.luffy.model.Pocket48Message;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息完整性检查器
 * 提供消息序列化、去重、连续性验证等功能
 */
public class MessageIntegrityChecker {
    
    // 每个房间的消息序列号
    private static final Map<Long, AtomicLong> roomSequenceNumbers = new ConcurrentHashMap<>();
    
    // 每个房间的消息去重缓存（保留最近1000条消息的ID）
    private static final Map<Long, Set<String>> roomMessageCache = new ConcurrentHashMap<>();
    
    // 缓存大小限制
    private static final int MAX_CACHE_SIZE = 1000;
    
    // 消息时间窗口缓存（用于检测短时间内的重复消息）
    private static final Map<String, Long> messageTimeWindow = new ConcurrentHashMap<>();
    private static final long TIME_WINDOW_THRESHOLD = 5 * 60 * 1000; // 5分钟内的重复消息
    
    // 每个房间的最后消息时间戳
    private static final Map<Long, Long> lastMessageTimestamp = new ConcurrentHashMap<>();
    
    // 消息时间间隔异常阈值（毫秒）- 调整为更合理的值
    private static final long TIME_GAP_THRESHOLD = 6 * 60 * 60 * 1000; // 6小时
    
    // 每个房间的消息时间戳历史（用于更智能的时间连续性检查）
    private static final Map<Long, List<Long>> roomTimestampHistory = new ConcurrentHashMap<>();
    
    // 时间戳历史记录的最大数量
    private static final int MAX_TIMESTAMP_HISTORY = 50;
    
    /**
     * 为消息分配序列号
     * @param roomId 房间ID
     * @param message 消息对象
     * @return 分配的序列号
     */
    public static long assignSequenceNumber(long roomId, Pocket48Message message) {
        AtomicLong sequenceCounter = roomSequenceNumbers.computeIfAbsent(roomId, k -> new AtomicLong(0));
        long sequenceNumber = sequenceCounter.incrementAndGet();
        
        // 移除正常情况下的序列号日志，减少日志噪音
        
        return sequenceNumber;
    }
    
    /**
     * 检查消息是否重复（改进版）
     * @param roomId 房间ID
     * @param message 消息对象
     * @return 如果是重复消息返回true
     */
    public static boolean isDuplicateMessage(long roomId, Pocket48Message message) {
        String messageId = generateMessageId(message);
        long currentTime = System.currentTimeMillis();
        
        // 检查时间窗口内的重复消息
        Long lastSeenTime = messageTimeWindow.get(messageId);
        if (lastSeenTime != null && (currentTime - lastSeenTime) < TIME_WINDOW_THRESHOLD) {
            System.out.println(String.format("[去重] 检测到时间窗口内重复消息: %s (间隔: %d毫秒)", 
                messageId, currentTime - lastSeenTime));
            return true;
        }
        
        Set<String> messageCache = roomMessageCache.computeIfAbsent(roomId, k -> 
            Collections.synchronizedSet(new LinkedHashSet<>()));
        
        // 检查是否重复
        boolean isDuplicate = messageCache.contains(messageId);
        
        if (isDuplicate) {
            System.out.println(String.format("[去重] 房间 %d 发现重复消息: %s", roomId, messageId));
            return true;
        }
        
        // 添加到缓存
        synchronized (messageCache) {
            messageCache.add(messageId);
            
            // 限制缓存大小
            if (messageCache.size() > MAX_CACHE_SIZE) {
                Iterator<String> iterator = messageCache.iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        }
        
        // 更新时间窗口缓存
        messageTimeWindow.put(messageId, currentTime);
        
        // 清理过期的时间窗口记录
        cleanupTimeWindow(currentTime);
        
        return false;
    }
    
    /**
     * 清理过期的时间窗口记录
     * @param currentTime 当前时间
     */
    private static void cleanupTimeWindow(long currentTime) {
        // 定期清理过期记录（每100次调用清理一次）
        if (Math.random() < 0.01) {
            messageTimeWindow.entrySet().removeIf(entry -> 
                (currentTime - entry.getValue()) > TIME_WINDOW_THRESHOLD * 2);
        }
    }
    
    /**
     * 检查消息时间连续性（改进版）
     * @param roomId 房间ID
     * @param message 消息对象
     * @return 如果时间间隔异常返回true
     */
    public static boolean checkTimeContinuity(long roomId, Pocket48Message message) {
        long currentTimestamp = message.getTime();
        
        // 获取或创建时间戳历史
        List<Long> timestampHistory = roomTimestampHistory.computeIfAbsent(roomId, k -> 
            Collections.synchronizedList(new ArrayList<>()));
        
        boolean isAnomalous = false;
        
        synchronized (timestampHistory) {
            if (!timestampHistory.isEmpty()) {
                // 检查与最近几条消息的时间间隔
                long lastTimestamp = timestampHistory.get(timestampHistory.size() - 1);
                long timeGap = Math.abs(currentTimestamp - lastTimestamp);
                
                // 只有当时间间隔超过阈值且不是正常的时间跳跃时才报告异常
                if (timeGap > TIME_GAP_THRESHOLD) {
                    // 检查是否是系统重启或长时间离线后的正常恢复
                    boolean isNormalRecovery = isNormalTimeRecovery(timestampHistory, currentTimestamp);
                    
                    if (!isNormalRecovery) {
                        System.err.println(String.format("[警告] 房间 %d 消息时间间隔异常: %d 毫秒 (上次: %d, 当前: %d)", 
                            roomId, timeGap, lastTimestamp, currentTimestamp));
                        isAnomalous = true;
                    } else {
                        // 移除正常时间恢复的信息日志，减少日志噪音
                    }
                }
            }
            
            // 添加当前时间戳到历史记录
            timestampHistory.add(currentTimestamp);
            
            // 限制历史记录大小
            if (timestampHistory.size() > MAX_TIMESTAMP_HISTORY) {
                timestampHistory.remove(0);
            }
        }
        
        // 更新最后消息时间戳
        lastMessageTimestamp.put(roomId, currentTimestamp);
        return isAnomalous;
    }
    
    /**
     * 判断是否是正常的时间恢复（如系统重启后）
     * @param timestampHistory 时间戳历史
     * @param currentTimestamp 当前时间戳
     * @return 如果是正常恢复返回true
     */
    private static boolean isNormalTimeRecovery(List<Long> timestampHistory, long currentTimestamp) {
        if (timestampHistory.size() < 3) {
            return true; // 历史记录不足，认为是正常的
        }
        
        // 检查最近几条消息的时间间隔是否都很大
        int largeGapCount = 0;
        for (int i = timestampHistory.size() - 3; i < timestampHistory.size() - 1; i++) {
            long gap = Math.abs(timestampHistory.get(i + 1) - timestampHistory.get(i));
            if (gap > TIME_GAP_THRESHOLD / 2) { // 使用较小的阈值检查
                largeGapCount++;
            }
        }
        
        // 如果最近的消息间隔都很大，可能是系统问题，认为是正常恢复
        return largeGapCount >= 2;
    }
    
    /**
     * 验证消息批次的完整性
     * @param roomId 房间ID
     * @param messages 消息批次
     * @return 验证结果
     */
    public static IntegrityCheckResult validateMessageBatch(long roomId, Pocket48Message[] messages) {
        IntegrityCheckResult result = new IntegrityCheckResult();
        
        if (messages == null || messages.length == 0) {
            result.setValid(true);
            return result;
        }
        
        int duplicateCount = 0;
        int timeAnomalyCount = 0;
        List<String> issues = new ArrayList<>();
        boolean needsSorting = false;
        
        // 检查消息时间顺序
        for (int i = 1; i < messages.length; i++) {
            if (messages[i].getTime() < messages[i-1].getTime()) {
                needsSorting = true;
                break;
            }
        }
        
        // 如果检测到时间顺序异常，自动按时间戳排序
        if (needsSorting) {
            Arrays.sort(messages, (m1, m2) -> Long.compare(m1.getTime(), m2.getTime()));
            System.out.println(String.format("[完整性] 房间 %d 消息批次时间顺序异常，已自动排序", roomId));
        }
        
        // 逐个检查消息
        for (Pocket48Message message : messages) {
            // 分配序列号
            assignSequenceNumber(roomId, message);
            
            // 检查重复
            if (isDuplicateMessage(roomId, message)) {
                duplicateCount++;
            }
            
            // 检查时间连续性
            if (checkTimeContinuity(roomId, message)) {
                timeAnomalyCount++;
            }
        }
        
        result.setValid(duplicateCount == 0 && timeAnomalyCount == 0 && issues.isEmpty());
        result.setDuplicateCount(duplicateCount);
        result.setTimeAnomalyCount(timeAnomalyCount);
        result.setIssues(issues);
        result.setTotalMessages(messages.length);
        
        // 只有在存在重复消息或时间连续性异常时才输出警告
        if (duplicateCount > 0 || timeAnomalyCount > 0) {
            System.err.println(String.format("[完整性] 房间 %d 消息批次验证警告: 重复 %d, 时间异常 %d", 
                roomId, duplicateCount, timeAnomalyCount));
        }
        
        return result;
    }
    
    /**
     * 生成消息唯一标识（改进版）
     * @param message 消息对象
     * @return 消息唯一标识
     */
    private static String generateMessageId(Pocket48Message message) {
        // 使用更稳定的字段组合生成唯一标识
        String content = message.getBody() != null ? message.getBody().trim() : "";
        String nickname = message.getNickName() != null ? message.getNickName().trim() : "";
        
        // 添加消息类型和更多字段以提高唯一性
        StringBuilder idBuilder = new StringBuilder();
        idBuilder.append(message.getTime()).append("_");
        idBuilder.append(nickname).append("_");
        
        // 对于图片消息，使用特殊标识
        if (content.contains("发送了一张图片") || content.contains("[overflow:image")) {
            idBuilder.append("IMG_").append(content.hashCode());
        } else {
            // 对于文本消息，使用内容哈希
            idBuilder.append("TXT_").append(content.hashCode());
        }
        
        return idBuilder.toString();
    }
    
    /**
     * 清理指定房间的缓存
     * @param roomId 房间ID
     */
    public static void clearRoomCache(long roomId) {
        roomSequenceNumbers.remove(roomId);
        roomMessageCache.remove(roomId);
        lastMessageTimestamp.remove(roomId);
        System.out.println(String.format("[清理] 房间 %d 完整性检查缓存已清理", roomId));
    }
    
    /**
     * 清理所有缓存
     */
    public static void clearAllCache() {
        roomSequenceNumbers.clear();
        roomMessageCache.clear();
        lastMessageTimestamp.clear();
        System.out.println("[清理] 所有完整性检查缓存已清理");
    }
    
    /**
     * 获取房间统计信息
     * @param roomId 房间ID
     * @return 统计信息
     */
    public static String getRoomStats(long roomId) {
        AtomicLong sequenceCounter = roomSequenceNumbers.get(roomId);
        Set<String> messageCache = roomMessageCache.get(roomId);
        Long lastTimestamp = lastMessageTimestamp.get(roomId);
        
        long totalMessages = sequenceCounter != null ? sequenceCounter.get() : 0;
        int cacheSize = messageCache != null ? messageCache.size() : 0;
        
        return String.format("房间 %d: 总消息数 %d, 缓存大小 %d, 最后消息时间 %s", 
            roomId, totalMessages, cacheSize, 
            lastTimestamp != null ? new Date(lastTimestamp).toString() : "无");
    }
    
    /**
     * 完整性检查结果
     */
    public static class IntegrityCheckResult {
        private boolean valid;
        private int duplicateCount;
        private int timeAnomalyCount;
        private int totalMessages;
        private List<String> issues;
        
        public IntegrityCheckResult() {
            this.issues = new ArrayList<>();
        }
        
        // Getters and Setters
        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
        
        public int getDuplicateCount() { return duplicateCount; }
        public void setDuplicateCount(int duplicateCount) { this.duplicateCount = duplicateCount; }
        
        public int getTimeAnomalyCount() { return timeAnomalyCount; }
        public void setTimeAnomalyCount(int timeAnomalyCount) { this.timeAnomalyCount = timeAnomalyCount; }
        
        public int getTotalMessages() { return totalMessages; }
        public void setTotalMessages(int totalMessages) { this.totalMessages = totalMessages; }
        
        public List<String> getIssues() { return issues; }
        public void setIssues(List<String> issues) { this.issues = issues; }
        
        @Override
        public String toString() {
            return String.format("IntegrityCheckResult{valid=%s, total=%d, duplicates=%d, timeAnomalies=%d, issues=%d}", 
                valid, totalMessages, duplicateCount, timeAnomalyCount, issues.size());
        }
    }
}