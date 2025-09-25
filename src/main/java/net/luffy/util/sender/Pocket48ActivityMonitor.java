package net.luffy.util.sender;

import net.luffy.Newboy;
import net.luffy.util.UnifiedSchedulerManager;
import net.luffy.util.SmartCacheManager;
import net.luffy.util.UnifiedLogger;
import net.luffy.util.Pocket48MetricsCollector;
import net.luffy.model.Pocket48Message;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * 口袋48消息活跃度监控器
 * 监控消息频率，动态控制缓存策略
 */
public class Pocket48ActivityMonitor {
    
    private static volatile Pocket48ActivityMonitor instance;
    private final Object lock = new Object();
    
    // 活跃度检测配置 - 禁用活跃度监控
    private static final long ACTIVITY_WINDOW_MS = 10 * 60 * 1000; // 保留窗口配置但不使用
    private static final int ACTIVE_THRESHOLD = Integer.MAX_VALUE; // 设置为最大值，永远不会达到活跃状态
    private static final int INACTIVE_THRESHOLD = 0; // 设置为0，始终保持非活跃状态
    private static final long MONITOR_INTERVAL_MS = 60 * 60 * 1000; // 延长到1小时，减少检查频率
    
    // 缓存TTL配置 - 禁用活跃度相关的缓存调整
    private static final long ACTIVE_CACHE_TTL = 60 * 60 * 1000; // 统一设置为1小时
    private static final long INACTIVE_CACHE_TTL = 60 * 60 * 1000; // 统一设置为1小时
    private static final long DEFAULT_CACHE_TTL = 60 * 60 * 1000; // 统一设置为1小时
    
    // 消息统计
    private final Map<Long, List<Long>> roomMessageTimes = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> roomMessageCounts = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> roomActiveStatus = new ConcurrentHashMap<>();
    
    // 全局统计
    private final AtomicLong totalMessages = new AtomicLong(0);
    private final AtomicInteger activeRooms = new AtomicInteger(0);
    private final AtomicBoolean globalActiveStatus = new AtomicBoolean(false);
    
    // 缓存管理
    private SmartCacheManager cacheManager;
    private final AtomicBoolean cacheEnabled = new AtomicBoolean(true);
    
    // 统一日志和指标收集
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final Pocket48MetricsCollector metricsCollector = Pocket48MetricsCollector.getInstance();
    
    // 监控任务ID
    private String monitorTaskId;
    private String reportTaskId; // 周期报告任务ID
    
    private Pocket48ActivityMonitor() {
        this.cacheManager = SmartCacheManager.getInstance();
        startActivityMonitoring();
        startPeriodicReporting();
    }
    
    public static Pocket48ActivityMonitor getInstance() {
        if (instance == null) {
            synchronized (Pocket48ActivityMonitor.class) {
                if (instance == null) {
                    instance = new Pocket48ActivityMonitor();
                }
            }
        }
        return instance;
    }
    
    /**
     * 记录消息活动 - 简化版本，仅记录基本统计
     */
    public void recordMessageActivity(long roomId, Pocket48Message message) {
        if (message == null) return;
        
        // 仅更新全局统计，不进行复杂的活跃度计算
        totalMessages.incrementAndGet();
        
        // 记录指标
        metricsCollector.recordQueueOffer();
        
        logger.debug("Pocket48Activity", "记录消息: " + roomId + ", 类型: " + message.getType());
    }

    /**
     * 批量记录消息活动 - 简化版本
     */
    public void recordBatchMessageActivity(long roomId, List<Pocket48Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        
        // 仅更新全局统计
        totalMessages.addAndGet(messages.size());
        
        // 批量记录指标
        for (int i = 0; i < messages.size(); i++) {
            metricsCollector.recordQueueOffer();
        }
        
        logger.debug("Pocket48Activity", "批量记录消息: " + roomId + ", 数量: " + messages.size());
    }

    /**
     * 检查房间是否活跃 - 始终返回false，禁用活跃度检测
     */
    public boolean isRoomActive(long roomId) {
        return false; // 始终返回非活跃状态
    }
    
    /**
     * 检查全局是否活跃 - 始终返回false，禁用活跃度检测
     */
    public boolean isGlobalActive() {
        return false; // 始终返回非活跃状态
    }

    /**
     * 获取当前缓存TTL - 固定返回默认值
     */
    public long getCurrentCacheTTL() {
        return DEFAULT_CACHE_TTL; // 始终返回固定TTL
    }
    
    /**
     * 是否启用缓存 - 始终启用
     */
    public boolean isCacheEnabled() {
        return true; // 始终启用缓存功能
    }

    /**
     * 动态设置缓存状态 - 简化版本
     */
    public void setCacheEnabled(boolean enabled) {
        cacheEnabled.set(enabled);
        // 移除复杂的缓存配置更新逻辑
    }
    
    /**
     * 获取活跃度统计报告 - 简化版本
     */
    public String getActivityReport() {
        StringBuilder report = new StringBuilder();
        report.append("📊 口袋48消息统计报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━\n");
        report.append("🌐 全局状态: 非活跃（已禁用活跃度检测）\n");
        report.append(String.format("📝 总消息数: %d\n", totalMessages.get()));
        report.append("💾 缓存状态: 启用\n");
        report.append(String.format("⏱️ 当前TTL: %d 分钟\n", getCurrentCacheTTL() / 60000));
        
        return report.toString();
    }
    
    // 私有方法
    
    /**
     * 启动活跃度监控 - 禁用监控功能
     */
    private void startActivityMonitoring() {
        // 不启动任何监控任务，禁用活跃度监控
        logger.info("Pocket48Activity", "活跃度监控已禁用");
    }
    
    /**
     * 检查活跃度状态 - 禁用状态检查
     */
    private void checkActivityStatus() {
        // 不执行任何活跃度检查逻辑
        // 所有房间和全局状态始终保持非活跃
    }
    
    /**
     * 清理过期数据
     */
    private void cleanupExpiredData(long roomId, long currentTime) {
        List<Long> messageTimes = roomMessageTimes.get(roomId);
        if (messageTimes != null) {
            // 移除超过活跃度窗口的消息时间
            messageTimes.removeIf(time -> (currentTime - time) > ACTIVITY_WINDOW_MS);
            
            // 更新消息计数
            roomMessageCounts.computeIfAbsent(roomId, k -> new AtomicInteger(0)).set(messageTimes.size());
        }
    }
    
    /**
     * 更新缓存配置 - 简化版本，确保实时数据访问
     */
    private void updateCacheConfiguration() {
        try {
            // 获取Pocket48专用缓存
            SmartCacheManager.LRUCache<String, SmartCacheManager.CacheEntry> pocket48Cache = 
                cacheManager.getCache("pocket48_messages", 1000, getCurrentCacheTTL());
            
            // 移除缓存清理逻辑，确保缓存始终可用以支持实时数据访问
            // 缓存配置已更新，保持缓存可用状态
            
        } catch (Exception e) {
            logger.error("Pocket48Activity", "缓存配置更新失败", e);
        }
    }
    
    /**
     * 启动周期性报告 - 禁用报告功能
     */
    private void startPeriodicReporting() {
        // 不启动周期性报告任务
        logger.info("Pocket48Activity", "周期性报告已禁用");
    }
    
    /**
     * 生成周期性报告 - 禁用报告生成
     */
    private void generatePeriodicReport() {
        // 不生成任何报告
    }
    
    /**
     * 关闭监控器
     */
    public void shutdown() {
        if (monitorTaskId != null) {
            UnifiedSchedulerManager.getInstance().cancelTask(monitorTaskId);
        }
        if (reportTaskId != null) {
            UnifiedSchedulerManager.getInstance().cancelTask(reportTaskId);
        }
        logger.info("Pocket48Activity", "活跃度监控器已关闭");
    }
}