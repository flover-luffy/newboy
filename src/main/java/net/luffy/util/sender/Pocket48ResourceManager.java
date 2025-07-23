package net.luffy.util.sender;

import net.luffy.Newboy;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.luffy.util.CpuLoadBalancer;
import net.luffy.util.UnifiedResourceManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 口袋48资源管理器
 * 统一管理口袋48异步处理配置，包括媒体队列开关、队列大小、线程池配置等
 * 替代原有的配置文件方式，实现动态配置管理
 */
public class Pocket48ResourceManager {
    
    private static volatile Pocket48ResourceManager instance;
    private final Object lock = new Object();
    
    // 异步媒体队列配置
    private final AtomicBoolean mediaQueueEnabled = new AtomicBoolean(true);
    private final AtomicInteger mediaQueueSize = new AtomicInteger(100);
    private final AtomicInteger mediaThreadPoolSize = new AtomicInteger(2);
    private final AtomicInteger mediaProcessingTimeout = new AtomicInteger(10);
    private final AtomicBoolean prioritizeTextMessages = new AtomicBoolean(true);
    private final AtomicInteger mediaBatchSize = new AtomicInteger(5);
    private final AtomicInteger mediaRetryAttempts = new AtomicInteger(3);
    
    // 动态调整参数
    private final AtomicLong lastConfigUpdate = new AtomicLong(0);
    private final AtomicInteger configVersion = new AtomicInteger(1);
    
    // 系统负载相关
    private CpuLoadBalancer loadBalancer;
    private AdaptiveThreadPoolManager threadPoolManager;
    
    private Pocket48ResourceManager() {
        initialize();
    }
    
    public static Pocket48ResourceManager getInstance() {
        if (instance == null) {
            synchronized (Pocket48ResourceManager.class) {
                if (instance == null) {
                    instance = new Pocket48ResourceManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化资源管理器
     */
    private void initialize() {
        try {
            this.loadBalancer = CpuLoadBalancer.getInstance();
            this.threadPoolManager = AdaptiveThreadPoolManager.getInstance();
            
            // 根据系统资源动态调整初始配置
            adjustConfigurationBasedOnSystemResources();
            
            // 注册到统一资源管理器
            registerToUnifiedResourceManager();
            
            // 口袋48资源管理器初始化完成
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[口袋48资源管理器] 初始化失败", e);
        }
    }
    
    /**
     * 根据系统资源调整配置
     */
    private void adjustConfigurationBasedOnSystemResources() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemory = Runtime.getRuntime().maxMemory();
        
        // 根据CPU核心数调整线程池大小
        int optimalThreadPoolSize = Math.max(1, Math.min(cpuCores / 2, 4));
        mediaThreadPoolSize.set(optimalThreadPoolSize);
        
        // 根据内存大小调整队列大小
        int optimalQueueSize = maxMemory > 1024 * 1024 * 1024 ? 150 : 50; // 1GB以上内存使用150，否则50
        mediaQueueSize.set(optimalQueueSize);
        
        Newboy.INSTANCE.getLogger().info(
            String.format("[口袋48资源管理器] 根据系统资源调整配置 - CPU核心: %d, 线程池大小: %d, 队列大小: %d",
                cpuCores, optimalThreadPoolSize, optimalQueueSize));
    }
    
    /**
     * 注册到统一资源管理器
     */
    private void registerToUnifiedResourceManager() {
        UnifiedResourceManager.getInstance().registerResource("pocket48-resource-manager", 
            new UnifiedResourceManager.ManagedResource() {
                @Override
                public String getName() {
                    return "口袋48资源管理器";
                }
                
                @Override
                public UnifiedResourceManager.ResourceStatus getStatus() {
                    return new UnifiedResourceManager.ResourceStatus(true, 
                        "运行正常", getConfigurationMetrics());
                }
                
                @Override
                public void cleanup() {
                    // 清理资源
                }
                
                @Override
                public long getMemoryUsage() {
                    return 1024 * 512; // 估算512KB
                }
            });
    }
    
    /**
     * 获取配置指标
     */
    private java.util.Map<String, Object> getConfigurationMetrics() {
        java.util.Map<String, Object> metrics = new java.util.concurrent.ConcurrentHashMap<>();
        metrics.put("mediaQueueEnabled", mediaQueueEnabled.get());
        metrics.put("mediaQueueSize", mediaQueueSize.get());
        metrics.put("mediaThreadPoolSize", mediaThreadPoolSize.get());
        metrics.put("mediaProcessingTimeout", mediaProcessingTimeout.get());
        metrics.put("configVersion", configVersion.get());
        metrics.put("lastConfigUpdate", lastConfigUpdate.get());
        return metrics;
    }
    
    // ==================== 配置访问方法 ====================
    
    public boolean isMediaQueueEnabled() {
        return mediaQueueEnabled.get();
    }
    
    public void setMediaQueueEnabled(boolean enabled) {
        if (mediaQueueEnabled.compareAndSet(!enabled, enabled)) {
            updateConfigVersion();
            // 媒体队列状态更新
        }
    }
    
    public int getMediaQueueSize() {
        return mediaQueueSize.get();
    }
    
    public void setMediaQueueSize(int size) {
        if (size > 0 && size <= 1000) {
            int oldSize = mediaQueueSize.getAndSet(size);
            if (oldSize != size) {
                updateConfigVersion();
                // 媒体队列大小更新
            }
        }
    }
    
    public int getMediaThreadPoolSize() {
        return mediaThreadPoolSize.get();
    }
    
    public void setMediaThreadPoolSize(int size) {
        if (size > 0 && size <= 10) {
            int oldSize = mediaThreadPoolSize.getAndSet(size);
            if (oldSize != size) {
                updateConfigVersion();
                // 媒体线程池大小更新
            }
        }
    }
    
    public int getMediaProcessingTimeout() {
        return mediaProcessingTimeout.get();
    }
    
    public void setMediaProcessingTimeout(int timeout) {
        if (timeout > 0 && timeout <= 300) {
            int oldTimeout = mediaProcessingTimeout.getAndSet(timeout);
            if (oldTimeout != timeout) {
                updateConfigVersion();
                // 媒体处理超时时间更新
            }
        }
    }
    
    public boolean isPrioritizeTextMessages() {
        return prioritizeTextMessages.get();
    }
    
    public void setPrioritizeTextMessages(boolean prioritize) {
        if (prioritizeTextMessages.compareAndSet(!prioritize, prioritize)) {
            updateConfigVersion();
            // 文本消息优先级更新
        }
    }
    
    public int getMediaBatchSize() {
        return mediaBatchSize.get();
    }
    
    public void setMediaBatchSize(int size) {
        if (size > 0 && size <= 20) {
            int oldSize = mediaBatchSize.getAndSet(size);
            if (oldSize != size) {
                updateConfigVersion();
                // 媒体批处理大小更新
            }
        }
    }
    
    public int getMediaRetryAttempts() {
        return mediaRetryAttempts.get();
    }
    
    public void setMediaRetryAttempts(int attempts) {
        if (attempts >= 0 && attempts <= 10) {
            int oldAttempts = mediaRetryAttempts.getAndSet(attempts);
            if (oldAttempts != attempts) {
                updateConfigVersion();
                // 媒体重试次数更新
            }
        }
    }
    
    /**
     * 动态调整配置（基于系统负载）- 使用无锁并发优化
     */
    public void adjustConfigurationDynamically() {
        // 使用无锁方式获取负载级别，避免阻塞
        CpuLoadBalancer.LoadLevel loadLevel = loadBalancer.getCurrentLoadLevel();
        
        // 使用原子操作进行无锁配置调整
        switch (loadLevel) {
            case LOW:
                // 低负载时可以增加处理能力
                int currentLowSize = mediaThreadPoolSize.get();
                if (currentLowSize < 4) {
                    mediaThreadPoolSize.compareAndSet(currentLowSize, Math.min(4, currentLowSize + 1));
                    updateConfigVersion();
                }
                break;
            case HIGH:
            case CRITICAL:
                // 高负载时减少处理能力
                int currentHighSize = mediaThreadPoolSize.get();
                if (currentHighSize > 1) {
                    mediaThreadPoolSize.compareAndSet(currentHighSize, Math.max(1, currentHighSize - 1));
                    updateConfigVersion();
                }
                break;
            default:
                // 正常负载保持当前配置
                break;
        }
    }
    
    /**
     * 获取配置摘要
     */
    public String getConfigurationSummary() {
        return String.format(
            "口袋48资源配置 - 队列: %s(%d), 线程池: %d, 超时: %ds, 文本优先: %s, 批大小: %d, 重试: %d次",
            mediaQueueEnabled.get() ? "启用" : "禁用",
            mediaQueueSize.get(),
            mediaThreadPoolSize.get(),
            mediaProcessingTimeout.get(),
            prioritizeTextMessages.get() ? "是" : "否",
            mediaBatchSize.get(),
            mediaRetryAttempts.get()
        );
    }
    
    /**
     * 重置为默认配置 - 使用无锁并发优化
     */
    public void resetToDefaults() {
        // 使用原子操作进行无锁重置，避免阻塞
        mediaQueueEnabled.set(true);
        mediaQueueSize.set(100);
        mediaThreadPoolSize.set(2);
        mediaProcessingTimeout.set(15);  // 优化媒体处理超时为15秒
        prioritizeTextMessages.set(true);
        mediaBatchSize.set(5);
        mediaRetryAttempts.set(3);
        updateConfigVersion();
        
        // 配置已重置为默认值
    }
    
    /**
     * 更新配置版本
     */
    private void updateConfigVersion() {
        configVersion.incrementAndGet();
        lastConfigUpdate.set(System.currentTimeMillis());
    }
    
    /**
     * 获取配置版本
     */
    public int getConfigVersion() {
        return configVersion.get();
    }
    
    /**
     * 获取最后更新时间
     */
    public long getLastConfigUpdate() {
        return lastConfigUpdate.get();
    }
}