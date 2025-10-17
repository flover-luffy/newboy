package net.luffy.util;

import net.luffy.Newboy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 统一资源管理器
 * 负责协调和管理所有系统资源，包括线程池、缓存、监控器等
 * 实现资源的统一分配、监控和清理
 * 集成智能缓存、性能监控、并发安全和错误处理等核心组件
 */
public class UnifiedResourceManager {
    
    private static final UnifiedResourceManager INSTANCE = new UnifiedResourceManager();
    
    // 资源注册表
    private final Map<String, ManagedResource> resources = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock resourceLock = new ReentrantReadWriteLock();
    
    // 系统状态
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private final AtomicLong lastHealthCheck = new AtomicLong(0);
    
    // 核心组件
    private AdaptiveThreadPoolManager threadPoolManager;
    private UnifiedSchedulerManager schedulerManager;
    private PerformanceMonitor performanceMonitor;
    
    // 新增核心组件
    private EnhancedPerformanceMonitor enhancedMonitor;
    private ConcurrencySafetyUtils concurrencyUtils;
    private ErrorHandlingManager errorManager;
    
    // 资源统计
    private final AtomicInteger activeResources = new AtomicInteger(0);
    private final AtomicLong totalMemoryUsage = new AtomicLong(0);
    private final Map<String, Object> managedResources = new ConcurrentHashMap<>();
    
    // 系统监控
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    
    // 资源健康状态
    private final Map<String, ResourceHealth> resourceHealthMap = new ConcurrentHashMap<>();
    
    // 资源监控
    private final ResourceMonitor resourceMonitor;
    
    private UnifiedResourceManager() {
        this.resourceMonitor = new ResourceMonitor();
    }
    
    public static UnifiedResourceManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 初始化资源管理器
     */
    public void initialize() {
        if (initialized.compareAndSet(false, true)) {
            // 正在初始化统一资源管理器
            
            // 初始化核心组件
            initializeCoreComponents();
            
            // 注册核心资源
            registerCoreResources();
            
            // 启动资源监控
            resourceMonitor.start();
            
            // 统一资源管理器初始化完成
        }
    }
    
    /**
     * 初始化核心组件
     */
    private void initializeCoreComponents() {
        try {
            // 初始化线程池管理器
            threadPoolManager = AdaptiveThreadPoolManager.getInstance();
            
            // 初始化调度器管理器
            schedulerManager = UnifiedSchedulerManager.getInstance();
            
            // 初始化性能监控器
            performanceMonitor = PerformanceMonitor.getInstance();
            
            // 初始化增强性能监控器
            enhancedMonitor = EnhancedPerformanceMonitor.getInstance();
            
            // 初始化并发安全工具
            concurrencyUtils = ConcurrencySafetyUtils.getInstance();
            
            // 初始化错误处理管理器
            errorManager = ErrorHandlingManager.getInstance();
            
            // 核心组件初始化完成
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("UnifiedResourceManager", "[统一资源管理器] 核心组件初始化失败", e);
            throw new RuntimeException("资源管理器初始化失败", e);
        }
    }
    
    /**
     * 注册核心资源
     */
    private void registerCoreResources() {
        // 注册线程池管理器
        registerResource("adaptive-thread-pool", new ManagedResource() {
            @Override
            public String getName() { return "自适应线程池"; }
            
            @Override
            public ResourceStatus getStatus() {
                try {
                    AdaptiveThreadPoolManager manager = AdaptiveThreadPoolManager.getInstance();
                    return new ResourceStatus(true, "运行正常", getThreadPoolMetrics());
                } catch (Exception e) {
                    return new ResourceStatus(false, "异常: " + e.getMessage(), null);
                }
            }
            
            @Override
            public void cleanup() {
                AdaptiveThreadPoolManager.getInstance().shutdown();
            }
            
            @Override
            public long getMemoryUsage() {
                return 0; // 线程池本身内存占用较小
            }
        });
        
        // 注册性能监控器
        registerResource("performance-monitor", new ManagedResource() {
            @Override
            public String getName() { return "性能监控器"; }
            
            @Override
            public ResourceStatus getStatus() {
                PerformanceMonitor monitor = PerformanceMonitor.getInstance();
                return new ResourceStatus(true, "监控中", getPerformanceMetrics());
            }
            
            @Override
            public void cleanup() {
                PerformanceMonitor.getInstance().disablePeriodicReporting();
            }
            
            @Override
            public long getMemoryUsage() {
                return estimatePerformanceMonitorMemory();
            }
        });
        
        // 注册统一调度器
        registerResource("unified-scheduler", new ManagedResource() {
            @Override
            public String getName() { return "统一调度器"; }
            
            @Override
            public ResourceStatus getStatus() {
                UnifiedSchedulerManager scheduler = UnifiedSchedulerManager.getInstance();
                return new ResourceStatus(true, "调度中", getSchedulerMetrics());
            }
            
            @Override
            public void cleanup() {
                UnifiedSchedulerManager.getInstance().shutdown();
            }
            
            @Override
            public long getMemoryUsage() {
                return estimateSchedulerMemory();
            }
        });
        
        // 注册CPU负载均衡器
        registerResource("cpu-load-balancer", new ManagedResource() {
            @Override
            public String getName() { return "CPU负载均衡器"; }
            
            @Override
            public ResourceStatus getStatus() {
                CpuLoadBalancer balancer = CpuLoadBalancer.getInstance();
                return new ResourceStatus(true, "均衡中", getCpuBalancerMetrics());
            }
            
            @Override
            public void cleanup() {
                CpuLoadBalancer.getInstance().shutdown();
            }
            
            @Override
            public long getMemoryUsage() {
                return 1024 * 1024; // 估算1MB
            }
        });
    }
    
    /**
     * 注册资源
     */
    public void registerResource(String id, ManagedResource resource) {
        resourceLock.writeLock().lock();
        try {
            resources.put(id, resource);
            // 注册资源: " + id + " (" + resource.getName() + ")
        } finally {
            resourceLock.writeLock().unlock();
        }
    }
    
    /**
     * 获取系统健康状态
     */
    public SystemHealthStatus getSystemHealth() {
        resourceLock.readLock().lock();
        try {
            List<ResourceStatus> resourceStatuses = new ArrayList<>();
            long totalMemoryUsage = 0;
            int healthyCount = 0;
            
            for (Map.Entry<String, ManagedResource> entry : resources.entrySet()) {
                try {
                    ResourceStatus status = entry.getValue().getStatus();
                    resourceStatuses.add(status);
                    totalMemoryUsage += entry.getValue().getMemoryUsage();
                    if (status.isHealthy()) {
                        healthyCount++;
                    }
                } catch (Exception e) {
                    ResourceStatus errorStatus = new ResourceStatus(false, 
                        "获取状态失败: " + e.getMessage(), null);
                    resourceStatuses.add(errorStatus);
                }
            }
            
            boolean overallHealthy = healthyCount == resources.size();
            double healthScore = resources.isEmpty() ? 1.0 : (double) healthyCount / resources.size();
            
            return new SystemHealthStatus(overallHealthy, healthScore, 
                totalMemoryUsage, resourceStatuses);
            
        } finally {
            resourceLock.readLock().unlock();
        }
    }
    
    /**
     * 执行资源清理
     */
    public void performResourceCleanup(boolean force) {
        if (shutdownInProgress.get()) {
            return;
        }
        
        // 开始资源清理 (强制: " + force + ")
        
        resourceLock.readLock().lock();
        try {
            for (Map.Entry<String, ManagedResource> entry : resources.entrySet()) {
                try {
                    if (force || shouldCleanupResource(entry.getValue())) {
                        entry.getValue().cleanup();
                        // 清理资源: " + entry.getKey()
                    }
                } catch (Exception e) {
                    UnifiedLogger.getInstance().error("UnifiedResourceManager", 
                        String.format("[统一资源管理器] 清理资源失败: %s", entry.getKey()), e);
                }
            }
        } finally {
            resourceLock.readLock().unlock();
        }
    }
    
    /**
     * 关闭资源管理器
     */
    public void shutdown() {
        if (shutdownInProgress.compareAndSet(false, true)) {
            // 正在关闭统一资源管理器
            
            // 停止资源监控
            resourceMonitor.stop();
            
            // 关闭核心组件
            shutdownCoreComponents();
            
            // 清理所有资源
            performResourceCleanup(true);
            
            // 清空资源注册表
            resourceLock.writeLock().lock();
            try {
                resources.clear();
                resourceHealthMap.clear();
                managedResources.clear();
            } finally {
                resourceLock.writeLock().unlock();
            }
            
            // 统一资源管理器已关闭
        }
    }
    
    /**
      * 关闭核心组件
      */
     private void shutdownCoreComponents() {
         try {
             // 清理错误处理管理器
             if (errorManager != null) {
                 errorManager.cleanup();
             }
             
             // 清理增强性能监控器
             if (enhancedMonitor != null) {
                 enhancedMonitor.cleanup();
             }
             
             // 关闭并发安全工具
             if (concurrencyUtils != null) {
                 concurrencyUtils.shutdown();
             }
             
             // 核心组件关闭完成
         } catch (Exception e) {
             UnifiedLogger.getInstance().error("UnifiedResourceManager", "[统一资源管理器] 核心组件关闭失败", e);
         }
     }
    
    /**
     * 获取智能缓存管理器
     */
    /**
     * 获取增强性能监控器
     */
    public EnhancedPerformanceMonitor getEnhancedMonitor() {
        return enhancedMonitor;
    }
    
    /**
     * 获取并发安全工具
     */
    public ConcurrencySafetyUtils getConcurrencyUtils() {
        return concurrencyUtils;
    }
    
    /**
      * 获取错误处理管理器
      */
     public ErrorHandlingManager getErrorManager() {
         return errorManager;
     }
     
     /**
      * 获取综合系统报告
      */
     public String getComprehensiveSystemReport() {
         StringBuilder report = new StringBuilder();
         
         report.append("🏗️ 统一资源管理器 - 综合系统报告\n");
         report.append("═══════════════════════════════════════════════\n");
         
         // 系统状态概览
         report.append("📊 系统状态概览:\n");
         report.append(String.format("  初始化状态: %s\n", initialized.get() ? "✅ 已初始化" : "❌ 未初始化"));
         report.append(String.format("  关闭请求: %s\n", shutdownInProgress.get() ? "⚠️ 是" : "✅ 否"));
         report.append(String.format("  活跃资源数: %d\n", activeResources.get()));
         report.append(String.format("  总内存使用: %.2f MB\n", totalMemoryUsage.get() / 1024.0 / 1024.0));
         
         // 核心组件状态
         report.append("\n🔧 核心组件状态:\n");
         report.append(String.format("  线程池管理器: %s\n", threadPoolManager != null ? "✅ 活跃" : "❌ 未初始化"));
         report.append(String.format("  调度器管理器: %s\n", schedulerManager != null ? "✅ 活跃" : "❌ 未初始化"));
         report.append(String.format("  性能监控器: %s\n", performanceMonitor != null ? "✅ 活跃" : "❌ 未初始化"));
         report.append(String.format("  智能缓存管理器: %s\n", "❌ 已移除"));
         report.append(String.format("  增强性能监控器: %s\n", enhancedMonitor != null ? "✅ 活跃" : "❌ 未初始化"));
         report.append(String.format("  并发安全工具: %s\n", concurrencyUtils != null ? "✅ 活跃" : "❌ 未初始化"));
         report.append(String.format("  错误处理管理器: %s\n", errorManager != null ? "✅ 活跃" : "❌ 未初始化"));
         
         // 资源健康状态
         if (!resourceHealthMap.isEmpty()) {
             report.append("\n💚 资源健康状态:\n");
             resourceHealthMap.forEach((id, health) -> {
                 report.append(String.format("  %s: %s (%s)\n", 
                     id, 
                     health.isHealthy() ? "✅ 健康" : "❌ 异常", 
                     health.getStatus()));
             });
         }
         
         // 详细组件报告
         if (initialized.get()) {
             // 增强性能监控报告
             if (enhancedMonitor != null) {
                 report.append("\n").append(enhancedMonitor.getPerformanceReport());
             }
             
             // 并发安全报告
             if (concurrencyUtils != null) {
                 report.append("\n").append(concurrencyUtils.getConcurrencyReport());
             }
             
             // 错误处理报告
             if (errorManager != null) {
                 report.append("\n").append(errorManager.getErrorReport());
             }
         }
         
         report.append("\n═══════════════════════════════════════════════\n");
         report.append("📝 报告生成时间: ").append(new java.util.Date()).append("\n");
         
         return report.toString();
     }
     
     /**
      * 执行全系统健康检查
      */
     public Map<String, Boolean> performSystemHealthCheck() {
         Map<String, Boolean> healthResults = new HashMap<>();
         
         try {
             // 检查核心组件
             healthResults.put("unified_resource_manager", initialized.get() && !shutdownInProgress.get());
             healthResults.put("thread_pool_manager", threadPoolManager != null);
             healthResults.put("scheduler_manager", schedulerManager != null);
             healthResults.put("enhanced_monitor", enhancedMonitor != null);
             healthResults.put("concurrency_utils", concurrencyUtils != null);
             healthResults.put("error_manager", errorManager != null);
             
             // 检查内存使用情况
             double memoryUsage = getMemoryUsagePercentage();
             healthResults.put("memory_usage_ok", memoryUsage < 85.0); // 85%以下认为健康
             
             // 检查线程数量
             int threadCount = threadBean.getThreadCount();
             healthResults.put("thread_count_ok", threadCount < 200); // 200个线程以下认为健康
             
             // 执行错误处理管理器的健康检查
             if (errorManager != null) {
                 Map<String, Boolean> errorHealthChecks = errorManager.performHealthChecks();
                 healthResults.putAll(errorHealthChecks);
             }
             
         } catch (Exception e) {
             UnifiedLogger.getInstance().error("UnifiedResourceManager", "[统一资源管理器] 健康检查失败", e);
             healthResults.put("health_check_error", false);
         }
         
         return healthResults;
     }
     
     /**
      * 获取内存使用百分比
      */
     private double getMemoryUsagePercentage() {
         long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
         long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
         return maxMemory > 0 ? (double) usedMemory / maxMemory * 100 : 0;
     }
    
    // 辅助方法
    private boolean shouldCleanupResource(ManagedResource resource) {
        try {
            ResourceStatus status = resource.getStatus();
            return !status.isHealthy() || resource.getMemoryUsage() > 50 * 1024 * 1024; // 50MB阈值
        } catch (Exception e) {
            return true; // 异常时进行清理
        }
    }
    
    private Map<String, Object> getThreadPoolMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        // 实现线程池指标收集
        return metrics;
    }
    
    private Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        PerformanceMonitor monitor = PerformanceMonitor.getInstance();
        metrics.put("totalQueries", monitor.getTotalQueries());
        metrics.put("memoryUsage", monitor.getMemoryUsage());
        return metrics;
    }
    
    private Map<String, Object> getSchedulerMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        // 实现调度器指标收集
        return metrics;
    }
    
    private Map<String, Object> getCpuBalancerMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        // 实现CPU均衡器指标收集
        return metrics;
    }
    
    private long estimatePerformanceMonitorMemory() {
        return 5 * 1024 * 1024; // 估算5MB
    }
    
    private long estimateSchedulerMemory() {
        return 2 * 1024 * 1024; // 估算2MB
    }
    
    // 内部类
    public interface ManagedResource {
        String getName();
        ResourceStatus getStatus();
        void cleanup();
        long getMemoryUsage();
    }
    
    public static class ResourceStatus {
        private final boolean healthy;
        private final String message;
        private final Map<String, Object> metrics;
        
        public ResourceStatus(boolean healthy, String message, Map<String, Object> metrics) {
            this.healthy = healthy;
            this.message = message;
            this.metrics = metrics;
        }
        
        public boolean isHealthy() { return healthy; }
        public String getMessage() { return message; }
        public Map<String, Object> getMetrics() { return metrics; }
    }
    
    public static class SystemHealthStatus {
        private final boolean overallHealthy;
        private final double healthScore;
        private final long totalMemoryUsage;
        private final List<ResourceStatus> resourceStatuses;
        
        public SystemHealthStatus(boolean overallHealthy, double healthScore, 
                                long totalMemoryUsage, List<ResourceStatus> resourceStatuses) {
            this.overallHealthy = overallHealthy;
            this.healthScore = healthScore;
            this.totalMemoryUsage = totalMemoryUsage;
            this.resourceStatuses = resourceStatuses;
        }
        
        public boolean isOverallHealthy() { return overallHealthy; }
        public double getHealthScore() { return healthScore; }
        public long getTotalMemoryUsage() { return totalMemoryUsage; }
        public List<ResourceStatus> getResourceStatuses() { return resourceStatuses; }
    }
    
    /**
     * 资源健康状态类
     */
    public static class ResourceHealth {
        private final String resourceId;
        private final boolean healthy;
        private final long lastCheckTime;
        private final String status;
        private final Map<String, Object> metrics;
        
        public ResourceHealth(String resourceId, boolean healthy, String status, Map<String, Object> metrics) {
            this.resourceId = resourceId;
            this.healthy = healthy;
            this.lastCheckTime = System.currentTimeMillis();
            this.status = status;
            this.metrics = metrics != null ? new HashMap<>(metrics) : new HashMap<>();
        }
        
        public String getResourceId() { return resourceId; }
        public boolean isHealthy() { return healthy; }
        public long getLastCheckTime() { return lastCheckTime; }
        public String getStatus() { return status; }
        public Map<String, Object> getMetrics() { return new HashMap<>(metrics); }
    }
    
    // 资源监控器内部类
    private class ResourceMonitor {
        private volatile boolean running = false;
        
        public void start() {
            if (!running) {
                running = true;
                UnifiedSchedulerManager.getInstance().scheduleCleanupTask(
                    this::performHealthCheck, 60000, 60000); // 每分钟检查一次
            }
        }
        
        public void stop() {
            running = false;
        }
        
        private void performHealthCheck() {
            if (!running) return;
            
            try {
                SystemHealthStatus health = getSystemHealth();
                lastHealthCheck.set(System.currentTimeMillis());
                
                // 如果健康分数低于80%，触发清理
                if (health.getHealthScore() < 0.8) {
                    performResourceCleanup(false);
                }
                
            } catch (Exception e) {
                UnifiedLogger.getInstance().error("UnifiedResourceManager", "[统一资源管理器] 健康检查失败", e);
            }
        }
    }
}