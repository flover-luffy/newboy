package net.luffy.util;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.luffy.Newboy;
import net.luffy.util.MessageDelayConfig;

/**
 * CPU负载均衡器
 * 实时监控CPU使用率，动态调整处理策略以优化性能
 */
public class CpuLoadBalancer {
    private static final CpuLoadBalancer INSTANCE = new CpuLoadBalancer();
    
    // 监控组件
    private final ScheduledExecutorService monitorExecutor;
    private final OperatingSystemMXBean osBean;
    private final ThreadMXBean threadBean;
    
    // 负载状态
    private final AtomicReference<LoadLevel> currentLoadLevel = new AtomicReference<>(LoadLevel.NORMAL);
    private final AtomicInteger consecutiveHighLoadCount = new AtomicInteger(0);
    private final AtomicLong lastLoadCheckTime = new AtomicLong(0);
    
    // 性能指标
    private volatile double currentCpuUsage = 0.0;
    private volatile long currentMemoryUsage = 0;
    private volatile int currentThreadCount = 0;
    
    // 配置参数
    private static final long MONITOR_INTERVAL_MS = 5000; // 5秒监控间隔
    private static final double HIGH_CPU_THRESHOLD = 0.8;  // 80% CPU阈值
    private static final double CRITICAL_CPU_THRESHOLD = 0.9; // 90% 临界阈值
    private static final double LOW_CPU_THRESHOLD = 0.3;   // 30% 低负载阈值
    private static final int HIGH_LOAD_TRIGGER_COUNT = 3;  // 连续高负载触发次数
    
    // 动态调整策略
    private volatile int messageDelayMultiplier = 1;       // 消息延迟倍数
    private volatile int batchSizeMultiplier = 1;          // 批处理大小倍数
    private volatile boolean enableResourceCleanup = false; // 是否启用资源清理
    
    private CpuLoadBalancer() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
        
        this.monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CPU-LoadBalancer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        
        startMonitoring();
    }
    
    public static CpuLoadBalancer getInstance() {
        return INSTANCE;
    }
    
    /**
     * 启动CPU监控
     */
    private void startMonitoring() {
        monitorExecutor.scheduleAtFixedRate(this::performLoadCheck, 
            MONITOR_INTERVAL_MS, MONITOR_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 执行负载检查
     */
    private void performLoadCheck() {
        try {
            // 更新性能指标
            updatePerformanceMetrics();
            
            // 评估负载级别
            LoadLevel newLoadLevel = evaluateLoadLevel();
            LoadLevel oldLoadLevel = currentLoadLevel.get();
            
            // 负载级别变化时调整策略
            if (newLoadLevel != oldLoadLevel) {
                currentLoadLevel.set(newLoadLevel);
                adjustProcessingStrategy(newLoadLevel, oldLoadLevel);
                
                // 发布性能事件
                EventBusManager.getInstance().publishAsync(
                    new EventBusManager.PerformanceEvent(
                        "CpuLoadBalancer", currentCpuUsage, currentMemoryUsage, currentThreadCount));
            }
            
            lastLoadCheckTime.set(System.currentTimeMillis());
            
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[CPU负载均衡器] 监控过程中发生错误", e);
        }
    }
    
    /**
     * 更新性能指标
     */
    private void updatePerformanceMetrics() {
        // CPU使用率
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                currentCpuUsage = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
            } else {
                double loadAverage = osBean.getSystemLoadAverage();
                int processors = Runtime.getRuntime().availableProcessors();
                // 防止除零错误
                currentCpuUsage = loadAverage > 0 && processors > 0 ? loadAverage / processors : 0.5;
            }
        } catch (Exception e) {
            currentCpuUsage = 0.5; // 默认值
        }
        
        // 内存使用
        Runtime runtime = Runtime.getRuntime();
        currentMemoryUsage = runtime.totalMemory() - runtime.freeMemory();
        
        // 线程数
        currentThreadCount = threadBean.getThreadCount();
    }
    
    /**
     * 评估负载级别
     */
    private LoadLevel evaluateLoadLevel() {
        if (currentCpuUsage >= CRITICAL_CPU_THRESHOLD) {
            consecutiveHighLoadCount.incrementAndGet();
            return LoadLevel.CRITICAL;
        } else if (currentCpuUsage >= HIGH_CPU_THRESHOLD) {
            int count = consecutiveHighLoadCount.incrementAndGet();
            return count >= HIGH_LOAD_TRIGGER_COUNT ? LoadLevel.HIGH : LoadLevel.NORMAL;
        } else if (currentCpuUsage <= LOW_CPU_THRESHOLD) {
            consecutiveHighLoadCount.set(0);
            return LoadLevel.LOW;
        } else {
            consecutiveHighLoadCount.set(0);
            return LoadLevel.NORMAL;
        }
    }
    
    /**
     * 调整处理策略
     */
    private void adjustProcessingStrategy(LoadLevel newLevel, LoadLevel oldLevel) {
        String adjustmentInfo = "";
        
        // 尝试获取配置化的延迟倍数，如果获取失败则使用默认值
        int configuredHighMultiplier = 1;
        int configuredCriticalMultiplier = 2;
        
        try {
            MessageDelayConfig delayConfig = MessageDelayConfig.getInstance();
            configuredHighMultiplier = delayConfig.getHighLoadMultiplier();
            configuredCriticalMultiplier = delayConfig.getCriticalLoadMultiplier();
        } catch (Exception e) {
            // 如果配置获取失败，使用默认值
        }
        
        switch (newLevel) {
            case CRITICAL:
                messageDelayMultiplier = configuredCriticalMultiplier;
                batchSizeMultiplier = 1;
                enableResourceCleanup = true;
                adjustmentInfo = "进入临界负载模式：适度增加延迟，启用资源清理";
                
                // 触发紧急资源清理
                triggerEmergencyCleanup();
                break;
                
            case HIGH:
                messageDelayMultiplier = configuredHighMultiplier;
                batchSizeMultiplier = 1;
                enableResourceCleanup = true;
                adjustmentInfo = "进入高负载模式：根据配置调整延迟，启用资源清理";
                break;
                
            case NORMAL:
                messageDelayMultiplier = 1;
                batchSizeMultiplier = 1;
                enableResourceCleanup = false;
                adjustmentInfo = "恢复正常负载模式";
                break;
                
            case LOW:
                messageDelayMultiplier = 1;
                batchSizeMultiplier = 2; // 低负载时可以增加批处理大小
                enableResourceCleanup = false;
                adjustmentInfo = "进入低负载模式：可以增加处理量";
                break;
        }
        
        // 静默调整处理策略，不输出日志
    }
    
    /**
     * 触发紧急资源清理
     */
    private void triggerEmergencyCleanup() {
        CompletableFuture.runAsync(() -> {
            try {
                // 强制垃圾回收
                System.gc();
                
                // 清理缓存
                if (Newboy.INSTANCE != null) {
                    // 这里可以添加具体的缓存清理逻辑
                }
                
                // 发布清理事件
                EventBusManager.getInstance().publishAsync(
                    new EventBusManager.ResourceCleanupEvent("Emergency", 1, 
                        Runtime.getRuntime().freeMemory()));
                
                // 紧急资源清理完成
                
            } catch (Exception e) {
                Newboy.INSTANCE.getLogger().error("[CPU负载均衡器] 紧急清理失败", e);
            }
        }, AdaptiveThreadPoolManager.getInstance().getExecutor());
    }
    
    /**
     * 获取当前负载级别
     */
    public LoadLevel getCurrentLoadLevel() {
        return currentLoadLevel.get();
    }
    
    /**
     * 获取动态延迟时间
     */
    public int getDynamicDelay(int baseDelay) {
        return baseDelay * messageDelayMultiplier;
    }
    
    /**
     * 获取动态批处理大小
     */
    public int getDynamicBatchSize(int baseBatchSize) {
        return baseBatchSize * batchSizeMultiplier;
    }
    
    /**
     * 是否应该启用资源清理
     */
    public boolean shouldEnableResourceCleanup() {
        return enableResourceCleanup;
    }
    
    /**
     * 获取当前CPU使用率
     */
    public double getCurrentCpuUsage() {
        return currentCpuUsage;
    }
    
    /**
     * 获取负载均衡器状态
     */
    public String getLoadBalancerStatus() {
        return String.format(
            "CPU负载均衡器状态:\n" +
            "当前负载级别: %s\n" +
            "CPU使用率: %.1f%%\n" +
            "内存使用: %d MB\n" +
            "线程数: %d\n" +
            "延迟倍数: %dx\n" +
            "批处理倍数: %dx\n" +
            "资源清理: %s\n" +
            "连续高负载次数: %d\n" +
            "最后检查时间: %s",
            currentLoadLevel.get(),
            currentCpuUsage * 100,
            currentMemoryUsage / 1024 / 1024,
            currentThreadCount,
            messageDelayMultiplier,
            batchSizeMultiplier,
            enableResourceCleanup ? "启用" : "禁用",
            consecutiveHighLoadCount.get(),
            lastLoadCheckTime.get() > 0 ? new java.util.Date(lastLoadCheckTime.get()).toString() : "未检查"
        );
    }
    
    /**
     * 手动触发负载检查
     */
    public void triggerLoadCheck() {
        performLoadCheck();
    }
    
    /**
     * 关闭负载均衡器
     */
    public void shutdown() {
        monitorExecutor.shutdown();
        try {
            if (!monitorExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                monitorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            monitorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 负载级别枚举
     */
    public enum LoadLevel {
        LOW("低负载"),
        NORMAL("正常负载"),
        HIGH("高负载"),
        CRITICAL("临界负载");
        
        private final String description;
        
        LoadLevel(String description) {
            this.description = description;
        }
        
        @Override
        public String toString() {
            return description;
        }
    }
}