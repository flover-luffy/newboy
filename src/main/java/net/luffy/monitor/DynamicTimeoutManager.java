package net.luffy.monitor;

import net.luffy.util.MonitorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 动态超时管理器
 * 根据网络质量动态调整超时时间和重试策略
 */
public class DynamicTimeoutManager {
    private static final Logger logger = LoggerFactory.getLogger(DynamicTimeoutManager.class);
    
    private final MonitorConfig config;
    private final ScheduledExecutorService scheduler;
    
    public DynamicTimeoutManager(MonitorConfig config) {
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DynamicTimeoutManager-Monitor");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * 根据基础超时时间计算动态超时时间
     */
    public int getDynamicTimeout(int baseTimeout) {
        // 简化超时计算，不再依赖网络质量
        return baseTimeout;
    }
    
    /**
     * 根据基础重试次数计算动态重试次数
     */
    public int getDynamicRetries(int baseRetries) {
        // 简化重试次数计算，不再依赖网络质量
        return baseRetries;
    }
    
    /**
     * 根据基础延迟计算动态重试延迟
     */
    public long getDynamicDelay(long baseDelay) {
        // 简化延迟计算，不再依赖网络质量
        return baseDelay;
    }
    
    /**
     * 获取口袋48 API的动态超时配置
     */
    public TimeoutConfig getPocket48TimeoutConfig() {
        // 获取口袋48专用的基础配置
        int baseConnectTimeout = config.getPocket48ConnectTimeout();
        int baseReadTimeout = config.getPocket48ReadTimeout();
        int baseRetries = config.getPocket48MaxRetries();
        long baseDelay = config.getPocket48RetryBaseDelay();
        
        // 使用基础配置，不再进行动态调整
        return new TimeoutConfig(baseConnectTimeout, baseReadTimeout, baseRetries, baseDelay);
    }
    
    /**
     * 超时配置类
     */
    public static class TimeoutConfig {
        private final int connectTimeout;
        private final int readTimeout;
        private final int maxRetries;
        private final long baseDelay;
        
        public TimeoutConfig(int connectTimeout, int readTimeout, int maxRetries, long baseDelay) {
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.maxRetries = maxRetries;
            this.baseDelay = baseDelay;
        }
        
        public int getConnectTimeout() {
            return connectTimeout;
        }
        
        public int getReadTimeout() {
            return readTimeout;
        }
        
        public int getMaxRetries() {
            return maxRetries;
        }
        
        public long getBaseDelay() {
            return baseDelay;
        }
        
        @Override
        public String toString() {
            return String.format("TimeoutConfig{connectTimeout=%d, readTimeout=%d, maxRetries=%d, baseDelay=%d}",
                    connectTimeout, readTimeout, maxRetries, baseDelay);
        }
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}