package net.luffy.util;



import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一的定时任务管理器
 * 用于优化CPU占用，避免多个独立调度器同时运行
 */
public class UnifiedSchedulerManager {
    private static final UnifiedLogger logger = UnifiedLogger.getInstance();
    private static final UnifiedSchedulerManager INSTANCE = new UnifiedSchedulerManager();
    
    // 全局调度器：使用单个调度器管理所有定时任务
    private final ScheduledExecutorService globalScheduler;
    
    // 任务注册表
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskCounter = new AtomicInteger(0);
    
    // 配置参数 - 增加线程池大小以适应低CPU占用率
    private static final int SCHEDULER_CORE_POOL_SIZE = 4; // 从2增加到4
    private static final int MAX_CONCURRENT_TASKS = 20; // 从10增加到20
    
    private UnifiedSchedulerManager() {
        this.globalScheduler = Executors.newScheduledThreadPool(SCHEDULER_CORE_POOL_SIZE, r -> {
            Thread t = new Thread(r, "Unified-Scheduler-" + taskCounter.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY); // 设置为最低优先级，减少CPU竞争
            return t;
        });
    }
    
    public static UnifiedSchedulerManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 任务类型枚举
     */
    public enum TaskType {
        CLEANUP("cleanup", "清理任务"),
        MONITOR("monitor", "监控任务"),
        BATCH("batch", "批量任务");
        
        private final String prefix;
        private final String description;
        
        TaskType(String prefix, String description) {
            this.prefix = prefix;
            this.description = description;
        }
        
        public String getPrefix() {
            return prefix;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 统一的任务调度方法
     * @param taskType 任务类型
     * @param task 要执行的任务
     * @param initialDelay 初始延迟（毫秒）
     * @param period 执行周期（毫秒）
     * @return 任务ID
     */
    public String scheduleTask(TaskType taskType, Runnable task, long initialDelay, long period) {
        return scheduleTask(taskType.getPrefix(), wrapTask(task, taskType), initialDelay, period);
    }
    
    /**
     * 调度清理任务（低频率）- 兼容性方法
     * @param task 清理任务
     * @param initialDelay 初始延迟（毫秒）
     * @param period 执行周期（毫秒）
     * @return 任务ID
     */
    @Deprecated
    public String scheduleCleanupTask(Runnable task, long initialDelay, long period) {
        return scheduleTask(TaskType.CLEANUP, task, initialDelay, period);
    }
    
    /**
     * 调度监控任务（中频率）- 兼容性方法
     * @param task 监控任务
     * @param initialDelay 初始延迟（毫秒）
     * @param period 执行周期（毫秒）
     * @return 任务ID
     */
    @Deprecated
    public String scheduleMonitorTask(Runnable task, long initialDelay, long period) {
        return scheduleTask(TaskType.MONITOR, task, initialDelay, period);
    }
    
    /**
     * 调度批量任务（高频率）- 兼容性方法
     * @param task 批量任务
     * @param initialDelay 初始延迟（毫秒）
     * @param period 执行周期（毫秒）
     * @return 任务ID
     */
    @Deprecated
    public String scheduleBatchTask(Runnable task, long initialDelay, long period) {
        return scheduleTask(TaskType.BATCH, task, initialDelay, period);
    }
    
    /**
     * 通用任务调度方法
     * @param prefix 任务前缀
     * @param task 任务
     * @param initialDelay 初始延迟
     * @param period 执行周期
     * @return 任务ID
     */
    private String scheduleTask(String prefix, Runnable task, long initialDelay, long period) {
        if (scheduledTasks.size() >= MAX_CONCURRENT_TASKS) {
            logger.warn("UnifiedSchedulerManager", "[调度器警告] 已达到最大并发任务数限制: " + MAX_CONCURRENT_TASKS);
            return null;
        }
        
        String taskId = prefix + "-" + System.currentTimeMillis();
        ScheduledFuture<?> future = globalScheduler.scheduleAtFixedRate(
            task, initialDelay, period, TimeUnit.MILLISECONDS);
        
        scheduledTasks.put(taskId, future);
        return taskId;
    }
    
    /**
     * 包装任务，添加异常处理和性能监控
     * @param task 原始任务
     * @param taskType 任务类型
     * @return 包装后的任务
     */
    private Runnable wrapTask(Runnable task, TaskType taskType) {
        return () -> {
            long startTime = System.currentTimeMillis();
            try {
                task.run();
            } catch (Exception e) {
                logger.error("UnifiedSchedulerManager", "[调度器错误] " + taskType.getDescription() + "执行异常: " + e.getMessage());
            } finally {
                long duration = System.currentTimeMillis() - startTime;
                if (duration > 1000) { // 超过1秒的任务记录警告
                    logger.warn("UnifiedSchedulerManager", "[调度器警告] " + taskType.getDescription() + "执行耗时: " + duration + "ms");
                }
            }
        };
    }
    
    /**
     * 包装任务，添加异常处理和性能监控（兼容性方法）
     * @param task 原始任务
     * @return 包装后的任务
     */
    private Runnable wrapTask(Runnable task) {
        return wrapTask(task, TaskType.MONITOR); // 默认为监控任务类型
    }
    
    /**
     * 取消指定任务
     * @param taskId 任务ID
     * @return 是否成功取消
     */
    public boolean cancelTask(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            return future.cancel(false);
        }
        return false;
    }
    
    /**
     * 获取调度器状态信息
     * @return 状态信息
     */
    public String getSchedulerStatus() {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) globalScheduler;
        return String.format(
            "调度器状态 - 活跃任务: %d, 已调度任务: %d, 核心线程: %d, 活跃线程: %d",
            scheduledTasks.size(),
            executor.getTaskCount(),
            executor.getCorePoolSize(),
            executor.getActiveCount()
        );
    }
    
    /**
     * 优雅关闭调度器
     */
    public void shutdown() {
        // 取消所有任务
        scheduledTasks.values().forEach(future -> future.cancel(false));
        scheduledTasks.clear();
        
        // 关闭调度器
        globalScheduler.shutdown();
        try {
            if (!globalScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                globalScheduler.shutdownNow();
                if (!globalScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("UnifiedSchedulerManager", "[调度器错误] 无法正常关闭调度器");
                }
            }
        } catch (InterruptedException e) {
            globalScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 执行单次任务
     * @param task 要执行的任务
     */
    public void executeTask(Runnable task) {
        globalScheduler.execute(wrapTask(task));
    }
    
    /**
     * 获取底层执行器（用于CompletableFuture）
     * @return 执行器
     */
    public Executor getExecutor() {
        return globalScheduler;
    }
    
    /**
     * 获取底层调度执行器（用于延迟任务）
     * @return 调度执行器
     */
    public ScheduledExecutorService getScheduledExecutor() {
        return globalScheduler;
    }
    
    /**
     * 检查调度器是否健康
     * @return 是否健康
     */
    public boolean isHealthy() {
        return !globalScheduler.isShutdown() && 
               !globalScheduler.isTerminated() &&
               scheduledTasks.size() < MAX_CONCURRENT_TASKS;
    }
}