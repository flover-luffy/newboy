package net.luffy.util.sender;

import net.luffy.Newboy;
import net.luffy.util.UnifiedSchedulerManager;
import net.luffy.util.SmartCacheManager;
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
    
    // 活跃度检测配置
    private static final long ACTIVITY_WINDOW_MS = 5 * 60 * 1000; // 5分钟活跃度窗口
    private static final int ACTIVE_THRESHOLD = 10; // 5分钟内超过10条消息视为活跃
    private static final int INACTIVE_THRESHOLD = 3; // 5分钟内少于3条消息视为非活跃
    private static final long MONITOR_INTERVAL_MS = 30 * 1000; // 30秒检查一次
    
    // 缓存TTL配置
    private static final long ACTIVE_CACHE_TTL = 2 * 60 * 1000; // 活跃期：2分钟TTL
    private static final long INACTIVE_CACHE_TTL = 10 * 60 * 1000; // 非活跃期：10分钟TTL
    private static final long DEFAULT_CACHE_TTL = 10 * 60 * 1000; // 默认：10分钟TTL
    
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
    
    // 监控任务ID
    private String monitorTaskId;
    
    private Pocket48ActivityMonitor() {
        this.cacheManager = SmartCacheManager.getInstance();
        startActivityMonitoring();
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
     * 记录消息活动
     */
    public void recordMessageActivity(long roomId, Pocket48Message message) {
        if (message == null) return;
        
        long currentTime = System.currentTimeMillis();
        
        // 更新房间消息时间列表
        roomMessageTimes.computeIfAbsent(roomId, k -> new ArrayList<>()).add(currentTime);
        
        // 更新房间消息计数
        roomMessageCounts.computeIfAbsent(roomId, k -> new AtomicInteger(0)).incrementAndGet();
        
        // 更新全局统计
        totalMessages.incrementAndGet();
        
        // 清理过期数据
        cleanupExpiredData(roomId, currentTime);
    }
    
    /**
     * 记录批量消息活动
     */
    public void recordBatchMessageActivity(long roomId, List<Pocket48Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        
        long currentTime = System.currentTimeMillis();
        List<Long> messageTimes = roomMessageTimes.computeIfAbsent(roomId, k -> new ArrayList<>());
        
        // 批量添加消息时间
        for (Pocket48Message message : messages) {
            if (message != null) {
                messageTimes.add(currentTime);
                totalMessages.incrementAndGet();
            }
        }
        
        // 更新房间消息计数
        roomMessageCounts.computeIfAbsent(roomId, k -> new AtomicInteger(0)).addAndGet(messages.size());
        
        // 清理过期数据
        cleanupExpiredData(roomId, currentTime);
    }
    
    /**
     * 检查房间是否活跃
     */
    public boolean isRoomActive(long roomId) {
        return roomActiveStatus.getOrDefault(roomId, new AtomicBoolean(false)).get();
    }
    
    /**
     * 检查全局是否活跃
     */
    public boolean isGlobalActive() {
        return globalActiveStatus.get();
    }
    
    /**
     * 获取当前缓存TTL
     */
    public long getCurrentCacheTTL() {
        if (!cacheEnabled.get()) {
            return 0; // 禁用缓存
        }
        
        if (isGlobalActive()) {
            return ACTIVE_CACHE_TTL; // 活跃期短TTL
        } else {
            return INACTIVE_CACHE_TTL; // 非活跃期长TTL
        }
    }
    
    /**
     * 是否启用缓存
     */
    public boolean isCacheEnabled() {
        return cacheEnabled.get() && !isGlobalActive();
    }
    
    /**
     * 动态设置缓存状态
     */
    public void setCacheEnabled(boolean enabled) {
        cacheEnabled.set(enabled);
        updateCacheConfiguration();
    }
    
    /**
     * 获取活跃度统计报告
     */
    public String getActivityReport() {
        StringBuilder report = new StringBuilder();
        report.append("📊 口袋48消息活跃度报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━\n");
        report.append(String.format("🌐 全局状态: %s\n", isGlobalActive() ? "活跃" : "非活跃"));
        report.append(String.format("📈 活跃房间数: %d\n", activeRooms.get()));
        report.append(String.format("📝 总消息数: %d\n", totalMessages.get()));
        report.append(String.format("💾 缓存状态: %s\n", isCacheEnabled() ? "启用" : "禁用"));
        report.append(String.format("⏱️ 当前TTL: %d 分钟\n", getCurrentCacheTTL() / 60000));
        
        // 房间详情
        report.append("\n🏠 房间活跃度:\n");
        for (Map.Entry<Long, AtomicBoolean> entry : roomActiveStatus.entrySet()) {
            long roomId = entry.getKey();
            boolean active = entry.getValue().get();
            int messageCount = roomMessageCounts.getOrDefault(roomId, new AtomicInteger(0)).get();
            report.append(String.format("  房间 %d: %s (%d 条消息)\n", 
                roomId, active ? "活跃" : "非活跃", messageCount));
        }
        
        return report.toString();
    }
    
    // 私有方法
    
    /**
     * 启动活跃度监控
     */
    private void startActivityMonitoring() {
        UnifiedSchedulerManager scheduler = UnifiedSchedulerManager.getInstance();
        this.monitorTaskId = scheduler.scheduleCleanupTask(
            this::checkActivityStatus, 
            MONITOR_INTERVAL_MS, 
            MONITOR_INTERVAL_MS
        );
    }
    
    /**
     * 检查活跃度状态
     */
    private void checkActivityStatus() {
        try {
            long currentTime = System.currentTimeMillis();
            int currentActiveRooms = 0;
            
            // 检查每个房间的活跃度
            for (Map.Entry<Long, List<Long>> entry : roomMessageTimes.entrySet()) {
                long roomId = entry.getKey();
                List<Long> messageTimes = entry.getValue();
                
                // 清理过期数据
                cleanupExpiredData(roomId, currentTime);
                
                // 计算活跃度
                int recentMessageCount = messageTimes.size();
                boolean wasActive = roomActiveStatus.getOrDefault(roomId, new AtomicBoolean(false)).get();
                boolean isActive;
                
                if (wasActive) {
                    // 如果之前是活跃的，使用较低的阈值避免频繁切换
                    isActive = recentMessageCount >= INACTIVE_THRESHOLD;
                } else {
                    // 如果之前不活跃，使用较高的阈值确认活跃
                    isActive = recentMessageCount >= ACTIVE_THRESHOLD;
                }
                
                // 更新房间活跃状态
                roomActiveStatus.computeIfAbsent(roomId, k -> new AtomicBoolean(false)).set(isActive);
                
                if (isActive) {
                    currentActiveRooms++;
                }
            }
            
            // 更新全局活跃状态
            activeRooms.set(currentActiveRooms);
            boolean wasGlobalActive = globalActiveStatus.get();
            boolean isGlobalActive = currentActiveRooms > 0;
            
            if (wasGlobalActive != isGlobalActive) {
                globalActiveStatus.set(isGlobalActive);
                updateCacheConfiguration();
                
                // 状态变化已记录，仅在错误时输出日志
            }
            
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[口袋48活跃度] 活跃度检查失败", e);
        }
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
     * 更新缓存配置
     */
    private void updateCacheConfiguration() {
        try {
            // 获取Pocket48专用缓存
            SmartCacheManager.LRUCache<String, SmartCacheManager.CacheEntry> pocket48Cache = 
                cacheManager.getCache("pocket48_messages", 1000, getCurrentCacheTTL());
            
            // 如果是活跃期且禁用缓存，清理现有缓存
            if (isGlobalActive() && !isCacheEnabled()) {
                pocket48Cache.clear();
            }
            
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[口袋48活跃度] 缓存配置更新失败", e);
        }
    }
    
    /**
     * 关闭监控器
     */
    public void shutdown() {
        if (monitorTaskId != null) {
            UnifiedSchedulerManager.getInstance().cancelTask(monitorTaskId);
        }
    }
}