package net.luffy.util.delay;

import net.luffy.util.sender.Pocket48ActivityMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.luffy.util.delay.DelayConfig;
import java.time.LocalTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

/**
 * 智能延迟策略
 * 提供基于多维度因素的智能延迟计算，包括消息类型、内容长度、时间段、群组活跃度等
 */
public class DelayPolicy {
    private static final Logger logger = LoggerFactory.getLogger(DelayPolicy.class);
    
    // 优化后的默认延迟配置（毫秒）- 最佳性能参数
    private static final long DEFAULT_TEXT_INTERVAL = 300;      // 文本消息基准间隔 300ms
    private static final long DEFAULT_MEDIA_INTERVAL = 800;     // 媒体消息基准间隔 800ms
    private static final double DEFAULT_ACTIVE_MULTIPLIER = 0.6; // 活跃时延迟倍率
    private static final double DEFAULT_INACTIVE_MULTIPLIER = 1.1; // 非活跃时延迟倍率
    private static final long DEFAULT_MIN_INTERVAL = 200;       // 最小间隔 200ms
    private static final long DEFAULT_MAX_INTERVAL = 5000;      // 最大间隔 5秒
    
    // 智能调整参数
    private static final long PEAK_HOUR_START = 9;              // 高峰期开始时间
    private static final long PEAK_HOUR_END = 22;               // 高峰期结束时间
    private static final double PEAK_HOUR_MULTIPLIER = 1.05;    // 高峰期延迟倍率
    private static final double OFF_PEAK_MULTIPLIER = 0.8;      // 低峰期延迟倍率
    private static final int FLOOD_DETECTION_WINDOW = 10000;    // 刷屏检测窗口（毫秒）
    private static final int FLOOD_THRESHOLD = 5;               // 刷屏阈值（消息数）
    private static final double FLOOD_PENALTY_MULTIPLIER = 1.8; // 刷屏惩罚倍率
    
    // 可配置参数
    private long textInterval = DEFAULT_TEXT_INTERVAL;
    private long mediaInterval = DEFAULT_MEDIA_INTERVAL;
    private double activeMultiplier = DEFAULT_ACTIVE_MULTIPLIER;
    private double inactiveMultiplier = DEFAULT_INACTIVE_MULTIPLIER;
    private long minInterval = DEFAULT_MIN_INTERVAL;
    private long maxInterval = DEFAULT_MAX_INTERVAL;
    
    // 智能调整相关
    private final Pocket48ActivityMonitor activityMonitor;
    private final Map<String, AtomicLong> groupMessageCount = new ConcurrentHashMap<>();
    private final Map<String, Long> groupLastMessageTime = new ConcurrentHashMap<>();
    private final Map<String, Long> delayPredictionHistory = new ConcurrentHashMap<>();
    
    public DelayPolicy() {
        this.activityMonitor = Pocket48ActivityMonitor.getInstance();
    }
    
    /**
     * 智能计算消息发送延迟
     * @param isMediaMessage 是否为媒体消息（图片、视频等）
     * @return 延迟毫秒数
     */
    public long calculateSendDelay(boolean isMediaMessage) {
        return calculateSendDelay(isMediaMessage, null, null, 0);
    }
    
    /**
     * 简化版发送延迟计算（向后兼容）
     * @param isMediaMessage 是否为媒体消息
     * @param isActive 是否为活跃时段
     * @return 延迟毫秒数
     */
    public long calculateSendDelay(boolean isMediaMessage, boolean isActive) {
        return calculateSendDelay(isMediaMessage, null, null, 0);
    }
    
    /**
     * 智能计算消息发送延迟（增强版）
     * @param isMediaMessage 是否为媒体消息
     * @param groupId 群组ID（用于群组活跃度分析）
     * @param messageContent 消息内容（用于长度分析）
     * @param contentLength 内容长度
     * @return 延迟毫秒数
     */
    public long calculateSendDelay(boolean isMediaMessage, String groupId, String messageContent, int contentLength) {
        // 1. 基础间隔
        long baseInterval = isMediaMessage ? mediaInterval : textInterval;
        
        // 2. 活跃度调整
        boolean isActive = activityMonitor.isGlobalActive();
        double multiplier = isActive ? activeMultiplier : inactiveMultiplier;
        
        // 3. 基于内容长度的智能调整
        double contentMultiplier = calculateContentLengthMultiplier(contentLength, isMediaMessage);
        
        // 4. 基于时间段的动态调整
        double timeMultiplier = calculateTimeBasedMultiplier();
        
        // 5. 基于群组活跃度的调整
        double groupMultiplier = calculateGroupActivityMultiplier(groupId);
        
        // 6. 连续消息检测（防刷屏）
        double floodMultiplier = detectAndPenalizeFlooding(groupId);
        
        // 7. 综合计算最终延迟
        double finalMultiplier = multiplier * contentMultiplier * timeMultiplier * groupMultiplier * floodMultiplier;
        long delay = Math.round(baseInterval * finalMultiplier);
        
        // 8. 应用最小/最大限制
        delay = Math.max(minInterval, Math.min(maxInterval, delay));
        
        // 9. 更新延迟预测历史
        updateDelayPrediction(groupId, delay);
        
        // 10. 记录度量
        if (DelayConfig.getInstance().isMetricsCollectionEnabled()) {
            String messageType = isMediaMessage ? "media" : "text";
            DelayMetricsCollector.getInstance().recordDelayCalculation(messageType, delay, isActive);
        }
        
        logger.debug("智能延迟计算: {}ms (基础={}, 媒体={}, 活跃={}, 内容={}x, 时间={}x, 群组={}x, 防刷={}x)", 
                    delay, baseInterval, isMediaMessage, isActive, contentMultiplier, timeMultiplier, groupMultiplier, floodMultiplier);
        
        return delay;
    }
    
    /**
     * 基于内容长度计算调整倍率
     * @param contentLength 内容长度
     * @param isMediaMessage 是否为媒体消息
     * @return 调整倍率
     */
    private double calculateContentLengthMultiplier(int contentLength, boolean isMediaMessage) {
        if (contentLength <= 0) return 1.0;
        
        if (isMediaMessage) {
            // 媒体消息：大文件需要更长延迟
            if (contentLength > 10 * 1024 * 1024) return 1.8;      // >10MB
            if (contentLength > 5 * 1024 * 1024) return 1.5;       // >5MB
            if (contentLength > 1024 * 1024) return 1.2;           // >1MB
            return 1.0;
        } else {
            // 文本消息：长文本需要适当增加延迟
            if (contentLength > 1000) return 1.4;                  // >1000字符
            if (contentLength > 500) return 1.2;                   // >500字符
            if (contentLength > 200) return 1.1;                   // >200字符
            if (contentLength < 20) return 0.8;                    // 短消息可以更快
            return 1.0;
        }
    }
    
    /**
     * 基于时间段计算调整倍率
     * @return 调整倍率
     */
    private double calculateTimeBasedMultiplier() {
        LocalTime now = LocalTime.now();
        int hour = now.getHour();
        
        // 高峰期（9:00-22:00）适当增加延迟
        if (hour >= PEAK_HOUR_START && hour <= PEAK_HOUR_END) {
            return PEAK_HOUR_MULTIPLIER;
        } else {
            // 低峰期可以减少延迟
            return OFF_PEAK_MULTIPLIER;
        }
    }
    
    /**
     * 基于群组活跃度计算调整倍率
     * @param groupId 群组ID
     * @return 调整倍率
     */
    private double calculateGroupActivityMultiplier(String groupId) {
        if (groupId == null) return 1.0;
        
        try {
            // 检查群组最近的消息频率
            AtomicLong messageCount = groupMessageCount.get(groupId);
            Long lastMessageTime = groupLastMessageTime.get(groupId);
            long currentTime = System.currentTimeMillis();
            
            if (messageCount != null && lastMessageTime != null) {
                long timeDiff = currentTime - lastMessageTime;
                long count = messageCount.get();
                
                // 如果群组很活跃（最近有很多消息），增加延迟
                if (timeDiff < 60000 && count > 10) {  // 1分钟内超过10条消息
                    return 1.2;
                } else if (timeDiff < 300000 && count > 20) {  // 5分钟内超过20条消息
                    return 1.1;
                } else if (timeDiff > 3600000) {  // 超过1小时没有消息，可以加速
                    return 0.7;
                }
            }
            
            return 1.0;
        } catch (Exception e) {
            logger.warn("群组活跃度计算失败: {}", e.getMessage());
            return 1.0;
        }
    }
    
    /**
     * 检测并惩罚刷屏行为
     * @param groupId 群组ID
     * @return 调整倍率
     */
    private double detectAndPenalizeFlooding(String groupId) {
        if (groupId == null) return 1.0;
        
        try {
            long currentTime = System.currentTimeMillis();
            AtomicLong messageCount = groupMessageCount.computeIfAbsent(groupId, k -> new AtomicLong(0));
            Long lastTime = groupLastMessageTime.get(groupId);
            
            // 更新消息计数和时间
            if (lastTime == null || currentTime - lastTime > FLOOD_DETECTION_WINDOW) {
                // 重置计数窗口
                messageCount.set(1);
                groupLastMessageTime.put(groupId, currentTime);
                return 1.0;
            } else {
                // 在检测窗口内，增加计数
                long count = messageCount.incrementAndGet();
                groupLastMessageTime.put(groupId, currentTime);
                
                // 如果超过刷屏阈值，应用惩罚
                if (count > FLOOD_THRESHOLD) {
                    double penalty = Math.min(FLOOD_PENALTY_MULTIPLIER, 1.0 + (count - FLOOD_THRESHOLD) * 0.3);
                    logger.info("检测到群组 {} 可能的刷屏行为，消息数: {}, 应用延迟惩罚: {}x", groupId, count, penalty);
                    return penalty;
                }
                
                return 1.0;
            }
        } catch (Exception e) {
            logger.warn("刷屏检测失败: {}", e.getMessage());
            return 1.0;
        }
    }
    
    /**
     * 更新延迟预测历史
     * @param groupId 群组ID
     * @param delay 实际延迟
     */
    private void updateDelayPrediction(String groupId, long delay) {
        if (groupId != null) {
            delayPredictionHistory.put(groupId, delay);
            
            // 清理过期的历史记录（保留最近100个群组的记录）
            if (delayPredictionHistory.size() > 100) {
                delayPredictionHistory.entrySet().removeIf(entry -> 
                    System.currentTimeMillis() - entry.getValue() > 3600000); // 1小时过期
            }
        }
    }
    
    /**
     * 预测最优延迟（基于历史数据）
     * @param groupId 群组ID
     * @param isMediaMessage 是否为媒体消息
     * @return 预测的最优延迟
     */
    public long predictOptimalDelay(String groupId, boolean isMediaMessage) {
        if (groupId == null) {
            return isMediaMessage ? mediaInterval : textInterval;
        }
        
        Long historicalDelay = delayPredictionHistory.get(groupId);
        if (historicalDelay != null) {
            // 基于历史延迟进行微调
            double adjustmentFactor = calculateTimeBasedMultiplier() * calculateGroupActivityMultiplier(groupId);
            return Math.round(historicalDelay * adjustmentFactor);
        }
        
        return isMediaMessage ? mediaInterval : textInterval;
    }
    
    /**
     * 计算重试延迟（指数退避）- 优化版平滑退避策略
     * @param retryCount 重试次数（从0开始）
     * @return 延迟毫秒数
     */
    public long calculateRetryDelay(int retryCount) {
        return calculateRetryDelay(retryCount, "general");
    }
    
    /**
     * 计算重试延迟（指数退避）- 优化版平滑退避策略
     * @param retryCount 重试次数（从0开始）
     * @param context 上下文信息
     * @return 延迟毫秒数
     */
    public long calculateRetryDelay(int retryCount, String context) {
        DelayConfig config = DelayConfig.getInstance();
        
        // 基础延迟计算
        long baseDelay = config.getRetryBaseDelay();
        double backoffMultiplier = config.getRetryBackoffMultiplier();
        long maxDelay = config.getRetryMaxDelay();
        
        // 优化的平滑退避策略：使用更温和的指数增长
        // 添加随机抖动以避免雷群效应
        double jitterFactor = 0.1 * Math.random(); // 10%的随机抖动
        double smoothBackoff = Math.min(backoffMultiplier, 1.5 + retryCount * 0.2); // 更平滑的增长
        
        long delay = Math.round(baseDelay * Math.pow(smoothBackoff, retryCount) * (1 + jitterFactor));
        
        // 应用时间段调整
        delay = Math.round(delay * calculateTimeBasedMultiplier());
        
        // 确保不超过最大延迟
        delay = Math.min(delay, maxDelay);
        
        // 确保最小延迟（避免过于频繁的重试）
        delay = Math.max(delay, baseDelay / 2);
        
        if (config.isMetricsCollectionEnabled()) {
            logger.debug("计算重试延迟 - 重试次数: {}, 上下文: {}, 基础延迟: {}ms, 最终延迟: {}ms", 
                        retryCount, context, baseDelay, delay);
        }
        
        return delay;
    }
    
    /**
     * 获取消息组间延迟（用于替换原有的复杂逻辑）
     * @return 延迟毫秒数
     */
    public long getMessageGroupDelay() {
        // 消息组间使用固定的短延迟，避免阻塞
        return Math.round(textInterval * 0.3); // 30%的文本间隔
    }
    
    // 配置更新方法
    public void updateConfig(long textInterval, long mediaInterval, 
                           double activeMultiplier, double inactiveMultiplier,
                           long minInterval, long maxInterval) {
        this.textInterval = Math.max(0, textInterval);
        this.mediaInterval = Math.max(0, mediaInterval);
        this.activeMultiplier = Math.max(0.1, activeMultiplier);
        this.inactiveMultiplier = Math.max(0.1, inactiveMultiplier);
        this.minInterval = Math.max(0, minInterval);
        this.maxInterval = Math.max(minInterval, maxInterval);
        
        logger.info("DelayPolicy config updated: text={}ms, media={}ms, active={}x, inactive={}x, min={}ms, max={}ms",
                   this.textInterval, this.mediaInterval, this.activeMultiplier, 
                   this.inactiveMultiplier, this.minInterval, this.maxInterval);
    }
    
    // Getter方法
    public long getTextInterval() { return textInterval; }
    public long getMediaInterval() { return mediaInterval; }
    public double getActiveMultiplier() { return activeMultiplier; }
    public double getInactiveMultiplier() { return inactiveMultiplier; }
    public long getMinInterval() { return minInterval; }
    public long getMaxInterval() { return maxInterval; }
}