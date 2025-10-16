package net.luffy.util;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.luffy.Newboy;

/**
 * 自适应线程池管理器
 * 基于CPU使用率和队列长度动态调整线程池大小，优化CPU占用
 */
public class AdaptiveThreadPoolManager {
    private static final AdaptiveThreadPoolManager INSTANCE = new AdaptiveThreadPoolManager();
    
    // 自适应线程池
    private final ThreadPoolExecutor adaptivePool;
    private final ScheduledExecutorService monitorExecutor;
    
    // 监控指标
    private final AtomicLong lastAdjustTime = new AtomicLong(0);
    private final AtomicInteger adjustmentCount = new AtomicInteger(0);
    
    // 配置参数 - 大幅增加线程池大小以提高并发处理能力
    private static final int MIN_POOL_SIZE = 6; // 从3增加到6
    private static final int MAX_POOL_SIZE = 16; // 从8增加到16，大幅提升并发能力
    private static final int INITIAL_POOL_SIZE = 8; // 从4增加到8
    private static final long ADJUSTMENT_INTERVAL_MS = 15000; // 从30秒减少到15秒，更快响应
    private static final double HIGH_CPU_THRESHOLD = 0.85; // 从75%增加到85%，允许更高CPU使用率
    private static final double LOW_CPU_THRESHOLD = 0.3;   // 从40%降低到30%，更积极地增加线程
    private static final int HIGH_QUEUE_THRESHOLD = 20;    // 从40减少到20，更快响应队列积压
    
    private AdaptiveThreadPoolManager() {
        // 线程池参数初始化完成
        
        // 确保参数合法性
        if (INITIAL_POOL_SIZE > MAX_POOL_SIZE) {
            throw new IllegalArgumentException("初始线程池大小(" + INITIAL_POOL_SIZE + ")不能大于最大线程池大小(" + MAX_POOL_SIZE + ")");
        }
        if (MIN_POOL_SIZE > MAX_POOL_SIZE) {
            throw new IllegalArgumentException("最小线程池大小(" + MIN_POOL_SIZE + ")不能大于最大线程池大小(" + MAX_POOL_SIZE + ")");
        }
        
        // 创建自适应线程池
        this.adaptivePool = new ThreadPoolExecutor(
            INITIAL_POOL_SIZE,
            MAX_POOL_SIZE,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500), // 从200增加到500，提高队列容量
            r -> {
                Thread t = new Thread(r, "Adaptive-Pool-" + System.currentTimeMillis());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 创建监控执行器
        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Pool-Monitor");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        // 启动自动调整
        startAutoAdjustment();
    }
    
    public static AdaptiveThreadPoolManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 启动自动调整机制
     */
    private void startAutoAdjustment() {
        monitorExecutor.scheduleAtFixedRate(this::performAdjustment, 
            ADJUSTMENT_INTERVAL_MS, ADJUSTMENT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 执行线程池调整
     */
    private void performAdjustment() {
        try {
            double cpuUsage = getCpuUsage();
            int queueSize = adaptivePool.getQueue().size();
            int currentPoolSize = adaptivePool.getCorePoolSize();
            int activeThreads = adaptivePool.getActiveCount();
            
            boolean adjusted = false;
            String adjustmentReason = "";
            
            // 高CPU使用率且线程池较大时，减少线程数
            if (cpuUsage > HIGH_CPU_THRESHOLD && currentPoolSize > MIN_POOL_SIZE) {
                int newSize = Math.max(MIN_POOL_SIZE, currentPoolSize - 1);
                adaptivePool.setCorePoolSize(newSize);
                adjustmentReason = String.format("CPU使用率过高(%.1f%%)，减少线程池大小: %d -> %d", 
                    cpuUsage * 100, currentPoolSize, newSize);
                adjusted = true;
            }
            // 低CPU使用率但队列积压严重时，增加线程数
            else if (cpuUsage < LOW_CPU_THRESHOLD && queueSize > HIGH_QUEUE_THRESHOLD && 
                     currentPoolSize < MAX_POOL_SIZE) {
                int newSize = Math.min(MAX_POOL_SIZE, currentPoolSize + 1);
                adaptivePool.setCorePoolSize(newSize);
                adjustmentReason = String.format("队列积压严重(%d)且CPU使用率较低(%.1f%%)，增加线程池大小: %d -> %d", 
                    queueSize, cpuUsage * 100, currentPoolSize, newSize);
                adjusted = true;
            }
            
            if (adjusted) {
                lastAdjustTime.set(System.currentTimeMillis());
                adjustmentCount.incrementAndGet();
                // 线程池调整完成
            }
            
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[自适应线程池] 调整过程中发生错误", e);
        }
    }
    
    /**
     * 获取CPU使用率
     */
    private double getCpuUsage() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                return ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
            }
            double loadAverage = osBean.getSystemLoadAverage();
            int processors = Runtime.getRuntime().availableProcessors();
            // 防止除零错误
            return loadAverage > 0 && processors > 0 ? loadAverage / processors : 0.5;
        } catch (Exception e) {
            return 0.5; // 默认值
        }
    }
    
    /**
     * 提交任务到自适应线程池
     */
    public <T> CompletableFuture<T> submitTask(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, adaptivePool);
    }
    
    /**
     * 提交任务到自适应线程池
     */
    public CompletableFuture<Void> submitTask(Runnable task) {
        return CompletableFuture.runAsync(task, adaptivePool);
    }
    
    /**
     * 获取线程池状态信息
     */
    public String getPoolStatus() {
        return String.format(
            "自适应线程池状态:\n" +
            "核心线程数: %d/%d\n" +
            "活跃线程数: %d\n" +
            "队列大小: %d\n" +
            "已完成任务: %d\n" +
            "调整次数: %d\n" +
            "最后调整时间: %s\n" +
            "当前CPU使用率: %.1f%%",
            adaptivePool.getCorePoolSize(), MAX_POOL_SIZE,
            adaptivePool.getActiveCount(),
            adaptivePool.getQueue().size(),
            adaptivePool.getCompletedTaskCount(),
            adjustmentCount.get(),
            lastAdjustTime.get() > 0 ? new java.util.Date(lastAdjustTime.get()).toString() : "未调整",
            getCpuUsage() * 100
        );
    }
    
    /**
     * 手动调整线程池大小
     */
    public boolean adjustPoolSize(int newSize) {
        if (newSize < MIN_POOL_SIZE || newSize > MAX_POOL_SIZE) {
            return false;
        }
        
        int oldSize = adaptivePool.getCorePoolSize();
        adaptivePool.setCorePoolSize(newSize);
        
        // 手动调整线程池大小完成
        
        return true;
    }
    
    /**
     * 关闭线程池
     */
    public void shutdown() {
        monitorExecutor.shutdown();
        adaptivePool.shutdown();
        
        try {
            if (!monitorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitorExecutor.shutdownNow();
            }
            if (!adaptivePool.awaitTermination(10, TimeUnit.SECONDS)) {
                adaptivePool.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitorExecutor.shutdownNow();
            adaptivePool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 获取底层执行器
     */
    public Executor getExecutor() {
        return adaptivePool;
    }
    
    /**
     * 获取底层执行器服务
     */
    public ExecutorService getExecutorService() {
        return adaptivePool;
    }
    
    /**
     * 执行任务
     */
    public void execute(Runnable task) {
        adaptivePool.execute(task);
    }
    
    /**
     * 获取事件处理器线程池
     * EventBusManager使用此方法获取统一管理的线程池
     */
    public ExecutorService getEventProcessorPool() {
        return adaptivePool;
    }
}