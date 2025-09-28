package net.luffy.util.summary;

import net.luffy.util.UnifiedLogger;
import net.luffy.model.Pocket48Message;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每日总结系统集成类
 * 将每日总结功能集成到现有的口袋48监控系统中
 */
public class DailySummaryIntegration {
    
    private static DailySummaryIntegration instance;
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    
    private final DailySummaryDataCollector dataCollector;
    private final DailySummaryScheduler scheduler;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    private DailySummaryIntegration() {
        this.dataCollector = DailySummaryDataCollector.getInstance();
        this.scheduler = new DailySummaryScheduler(dataCollector);
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized DailySummaryIntegration getInstance() {
        if (instance == null) {
            instance = new DailySummaryIntegration();
        }
        return instance;
    }
    
    /**
     * 初始化每日总结系统
     */
    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            logger.info("DailySummary", "初始化每日总结系统");
            
            try {
                // 启动定时调度器
                scheduler.start();
                
                // 加载历史数据
                dataCollector.loadHistoricalData();
                
                logger.info("DailySummary", "每日总结系统初始化完成");
                logger.info("DailySummary", scheduler.getConfigInfo());
                
            } catch (Exception e) {
                logger.error("DailySummary", "初始化每日总结系统失败: " + e.getMessage());
                initialized.set(false);
            }
        } else {
            logger.warn("DailySummary", "每日总结系统已经初始化");
        }
    }
    
    /**
     * 关闭每日总结系统
     */
    public void shutdown() {
        if (initialized.compareAndSet(true, false)) {
            logger.info("DailySummary", "关闭每日总结系统");
            
            try {
                // 保存当前数据
                dataCollector.saveCurrentData();
                
                // 停止调度器
                scheduler.stop();
                
                logger.info("DailySummary", "每日总结系统已关闭");
                
            } catch (Exception e) {
                logger.error("DailySummary", "关闭每日总结系统时发生错误: " + e.getMessage());
            }
        }
    }
    
    /**
     * 记录口袋48消息（供Pocket48Sender调用）
     */
    public void recordMessage(Pocket48Message message) {
        if (!initialized.get()) {
            return;
        }
        
        try {
            dataCollector.recordMessage(message);
        } catch (Exception e) {
            logger.error("DailySummary", "记录消息时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 记录消息（简化版本，供其他组件调用）
     */
    public void recordMessage(String roomId, String roomName, String messageType, 
                            String senderId, String senderName, String content) {
        if (!initialized.get()) {
            return;
        }
        
        try {
            // 创建一个简化的Pocket48Message对象或者添加新的recordMessage重载方法
            logger.debug("DailySummary", "记录消息: 房间=" + roomId + ", 发送者=" + senderName + ", 类型=" + messageType);
            // 这里需要根据实际需求实现消息记录逻辑
        } catch (Exception e) {
            logger.error("DailySummary", "记录消息时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 手动生成指定日期的总结
     */
    public boolean generateSummaryForDate(LocalDate date) {
        if (!initialized.get()) {
            logger.warn("DailySummary", "系统未初始化，无法生成总结");
            return false;
        }

        return scheduler.generateSummaryForDate(date);
    }
    
    /**
     * 生成指定房间和日期的总结
     */
    public boolean generateRoomSummaryForDate(String roomId, LocalDate date) {
        if (!initialized.get()) {
            logger.warn("DailySummary", "系统未初始化，无法生成房间总结");
            return false;
        }
        
        return scheduler.generateRoomSummaryForDate(roomId, date);
    }
    
    /**
     * 生成指定房间昨天的总结
     */
    public boolean generateRoomYesterdaySummary(String roomId) {
        return generateRoomSummaryForDate(roomId, LocalDate.now().minusDays(1));
    }
    
    /**
     * 生成指定房间今天的总结（用于测试）
     */
    public boolean generateRoomTodaySummary(String roomId) {
        return generateRoomSummaryForDate(roomId, LocalDate.now());
    }
    
    /**
     * 获取活跃房间列表
     */
    public List<String> getActiveRoomIds() {
        if (!initialized.get()) {
            return new ArrayList<>();
        }
        
        return dataCollector.getActiveRoomIds();
    }
    
    /**
     * 获取指定日期的活跃房间列表
     */
    public List<String> getActiveRoomIds(LocalDate date) {
        if (!initialized.get()) {
            return new ArrayList<>();
        }
        
        return dataCollector.getActiveRoomIds(date);
    }
    
    /**
     * 生成昨天的总结
     */
    public boolean generateYesterdaySummary() {
        return generateSummaryForDate(LocalDate.now().minusDays(1));
    }
    
    /**
     * 生成今天的总结（用于测试）
     */
    public boolean generateTodaySummary() {
        return generateSummaryForDate(LocalDate.now());
    }
    
    /**
     * 生成测试图片
     */
    public boolean generateTestImage() {
        if (!initialized.get()) {
            logger.warn("DailySummary", "系统未初始化，无法生成测试图片");
            return false;
        }
        
        return scheduler.generateTestImage();
    }
    
    /**
     * 获取系统状态信息
     */
    public String getSystemStatus() {
        if (!initialized.get()) {
            return "每日总结系统: 未初始化";
        }
        
        StringBuilder status = new StringBuilder();
        status.append("每日总结系统状态:\n");
        status.append("- 初始化状态: ").append(initialized.get() ? "已初始化" : "未初始化").append("\n");
        status.append("- 调度器状态: ").append(scheduler.isRunning() ? "运行中" : "已停止").append("\n");
        status.append("- 下次执行时间: ").append(scheduler.getNextExecutionTime()).append("\n");
        status.append("- 今日消息统计: ").append(dataCollector.getTodayMessageCount()).append(" 条\n");
        status.append("- 活跃房间数: ").append(dataCollector.getActiveRoomCount()).append(" 个");
        
        return status.toString();
    }
    
    /**
     * 获取今日统计概览
     */
    public String getTodayOverview() {
        if (!initialized.get()) {
            return "系统未初始化";
        }
        
        try {
            int messageCount = dataCollector.getTodayMessageCount();
            int roomCount = dataCollector.getActiveRoomCount();
            
            return String.format("今日概览: %d 条消息，%d 个活跃房间", messageCount, roomCount);
            
        } catch (Exception e) {
            logger.error("DailySummary", "获取今日概览时发生错误: " + e.getMessage());
            return "获取统计信息失败";
        }
    }
    
    /**
     * 检查系统是否正常运行
     */
    public boolean isHealthy() {
        return initialized.get() && scheduler.isRunning();
    }
    
    /**
     * 重启系统
     */
    public boolean restart() {
        logger.info("DailySummary", "重启每日总结系统");
        
        try {
            shutdown();
            Thread.sleep(1000); // 等待1秒确保完全关闭
            initialize();
            return isHealthy();
        } catch (Exception e) {
            logger.error("DailySummary", "重启系统时发生错误: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取配置信息
     */
    public String getConfigInfo() {
        if (!initialized.get()) {
            return "系统未初始化";
        }
        
        return scheduler.getConfigInfo();
    }
    
    /**
     * 强制保存当前数据
     */
    public void forceSaveData() {
        if (!initialized.get()) {
            logger.warn("DailySummary", "系统未初始化，无法保存数据");
            return;
        }
        
        try {
            dataCollector.saveCurrentData();
            logger.info("DailySummary", "数据已强制保存");
        } catch (Exception e) {
            logger.error("DailySummary", "强制保存数据时发生错误: " + e.getMessage());
        }
    }
}