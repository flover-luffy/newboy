package net.luffy.util.delay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.luffy.util.delay.DelayConfig;

/**
 * 智能延迟策略
 * 提供基于多维度因素的智能延迟计算，包括消息类型、内容长度、时间段、群组活跃度等
 */
public class DelayPolicy {
    private static final Logger logger = LoggerFactory.getLogger(DelayPolicy.class);
    
    // 默认延迟配置（移除时间段和刷屏检测相关常量）
    private static final long DEFAULT_TEXT_INTERVAL = 150;      // 文本消息基准间隔 150ms（优化：从300ms降低）
    private static final long DEFAULT_MEDIA_INTERVAL = 400;     // 媒体消息基准间隔 400ms（优化：从800ms降低）
    private static final long DEFAULT_MIN_INTERVAL = 100;       // 最小间隔 100ms（优化：从200ms降低）
    private static final long DEFAULT_MAX_INTERVAL = 5000;      // 最大间隔 5秒（限制最大延迟）
    
    // 可配置参数（移除活跃度相关参数）
    private long textInterval = DEFAULT_TEXT_INTERVAL;
    private long mediaInterval = DEFAULT_MEDIA_INTERVAL;
    private long minInterval = DEFAULT_MIN_INTERVAL;
    private long maxInterval = DEFAULT_MAX_INTERVAL;
    
    // 智能调整相关（移除群组活跃度和刷屏检测相关数据结构）
    
    public DelayPolicy() {
        // 简化构造函数
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
     * @param isActive 是否为活跃时段（已废弃，不再使用）
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
        
        // 2. 基于内容长度的智能调整
        double contentMultiplier = calculateContentLengthMultiplier(contentLength, isMediaMessage);
        
        // 3. 简化计算最终延迟（移除时间段、群组活跃度、刷屏检测调整）
        double finalMultiplier = contentMultiplier;
        long delay = Math.round(baseInterval * finalMultiplier);
        
        // 4. 应用最小/最大限制
        delay = Math.max(minInterval, Math.min(maxInterval, delay));
        
        // 5. 记录度量
        if (DelayConfig.getInstance().isMetricsCollectionEnabled()) {
            String messageType = isMediaMessage ? "media" : "text";
            DelayMetricsCollector.getInstance().recordDelayCalculation(messageType, delay, false);
        }
        
        logger.debug("简化延迟计算: {}ms (基础={}, 媒体={}, 内容={}x)", 
                    delay, baseInterval, isMediaMessage, contentMultiplier);
        
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
     * 预测最优延迟（简化版）
     * @param groupId 群组ID
     * @param isMediaMessage 是否为媒体消息
     * @return 预测的最优延迟
     */
    public long predictOptimalDelay(String groupId, boolean isMediaMessage) {
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
        long maxDelay = Math.min(config.getRetryMaxDelay(), 5000); // 强制限制最大延迟为5秒
        
        // 优化的平滑退避策略：使用更温和的指数增长
        // 添加随机抖动以避免雷群效应
        double jitterFactor = 0.1 * Math.random(); // 10%的随机抖动
        double smoothBackoff = Math.min(backoffMultiplier, 1.5 + retryCount * 0.2); // 更平滑的增长
        
        long delay = Math.round(baseDelay * Math.pow(smoothBackoff, retryCount) * (1 + jitterFactor));
        
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
                           long minInterval, long maxInterval) {
        this.textInterval = Math.max(0, textInterval);
        this.mediaInterval = Math.max(0, mediaInterval);
        this.minInterval = Math.max(0, minInterval);
        this.maxInterval = Math.max(minInterval, maxInterval);
        
        logger.info("DelayPolicy config updated: text={}ms, media={}ms, min={}ms, max={}ms",
                   this.textInterval, this.mediaInterval, this.minInterval, this.maxInterval);
    }
    
    // Getter方法（移除活跃度相关）
    public long getTextInterval() { return textInterval; }
    public long getMediaInterval() { return mediaInterval; }
    public long getMinInterval() { return minInterval; }
    public long getMaxInterval() { return maxInterval; }
}