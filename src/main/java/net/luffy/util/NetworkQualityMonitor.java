package net.luffy.util;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 网络质量监控器
 * 提供网络延迟测试、丢包率检测和网络质量评估功能
 */
public class NetworkQualityMonitor {
    private static final String DEFAULT_TEST_HOST = "www.baidu.com";
    private static final int DEFAULT_TEST_PORT = 80;
    private static final int DEFAULT_TIMEOUT = 2000; // 优化为2秒超时，提高响应速度
    private static final int DEFAULT_TEST_COUNT = 5;
    
    private final ExecutorService executor;
    private final AtomicLong totalLatency = new AtomicLong(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    
    // 网络质量等级
    public enum NetworkQuality {
        EXCELLENT(0, 50),    // 优秀: 0-50ms
        GOOD(51, 100),       // 良好: 51-100ms
        FAIR(101, 200),      // 一般: 101-200ms
        POOR(201, 500),      // 较差: 201-500ms
        VERY_POOR(501, Integer.MAX_VALUE); // 很差: >500ms
        
        private final int minLatency;
        private final int maxLatency;
        
        NetworkQuality(int minLatency, int maxLatency) {
            this.minLatency = minLatency;
            this.maxLatency = maxLatency;
        }
        
        public static NetworkQuality fromLatency(long avgLatency) {
            for (NetworkQuality quality : values()) {
                if (avgLatency >= quality.minLatency && avgLatency <= quality.maxLatency) {
                    return quality;
                }
            }
            return VERY_POOR;
        }
    }
    
    // 网络质量检测结果
    public static class NetworkQualityResult {
        private final long averageLatency;
        private final double packetLossRate;
        private final NetworkQuality quality;
        private final List<Long> latencies;
        
        public NetworkQualityResult(long averageLatency, double packetLossRate, 
                                  NetworkQuality quality, List<Long> latencies) {
            this.averageLatency = averageLatency;
            this.packetLossRate = packetLossRate;
            this.quality = quality;
            this.latencies = new ArrayList<>(latencies);
        }
        
        public long getAverageLatency() { return averageLatency; }
        public double getPacketLossRate() { return packetLossRate; }
        public NetworkQuality getQuality() { return quality; }
        public List<Long> getLatencies() { return Collections.unmodifiableList(latencies); }
        
        @Override
        public String toString() {
            return String.format("NetworkQuality{avgLatency=%dms, packetLoss=%.1f%%, quality=%s}",
                    averageLatency, packetLossRate * 100, quality);
        }
    }
    
    public NetworkQualityMonitor() {
        // 使用统一线程池管理器替代独立线程池
        this.executor = net.luffy.util.AdaptiveThreadPoolManager.getInstance().getExecutorService();
    }
    
    /**
     * 检测网络质量 - 已禁用，始终返回优秀网络质量
     * @return 网络质量检测结果
     */
    public NetworkQualityResult checkNetworkQuality() {
        // 禁用网络质量检测，始终返回优秀网络质量以减少系统开销
        List<Long> mockLatencies = new ArrayList<>();
        for (int i = 0; i < DEFAULT_TEST_COUNT; i++) {
            mockLatencies.add(10L); // 模拟10ms延迟
        }
        return new NetworkQualityResult(10L, 0.0, NetworkQuality.EXCELLENT, mockLatencies);
    }

    /**
     * 检测指定主机的网络质量 - 已禁用，始终返回优秀网络质量
     * @param host 目标主机
     * @param port 目标端口
     * @param testCount 测试次数
     * @return 网络质量检测结果
     */
    public NetworkQualityResult checkNetworkQuality(String host, int port, int testCount) {
        // 禁用网络质量检测，始终返回优秀网络质量以减少系统开销
        List<Long> mockLatencies = new ArrayList<>();
        for (int i = 0; i < testCount; i++) {
            mockLatencies.add(10L); // 模拟10ms延迟
        }
        return new NetworkQualityResult(10L, 0.0, NetworkQuality.EXCELLENT, mockLatencies);
    }

    /**
     * 测量到指定主机的延迟 - 已禁用
     * @param host 目标主机
     * @param port 目标端口
     * @return 延迟时间(毫秒)，-1表示连接失败
     */
    private long measureLatency(String host, int port) {
        // 禁用网络延迟测量，直接返回固定值以减少系统开销
        return 10L; // 模拟10ms延迟
    }

    /**
     * 快速检测网络连通性 - 简化版本
     * @param host 目标主机
     * @param port 目标端口
     * @param timeout 超时时间(毫秒)
     * @return 是否连通
     */
    public boolean isReachable(String host, int port, int timeout) {
        return true; // 始终返回可达状态
    }

    /**
     * 根据网络质量推荐超时时间 - 已禁用，始终返回基础超时时间
     * @param quality 网络质量
     * @param baseTimeout 基础超时时间
     * @return 推荐的超时时间
     */
    public int getRecommendedTimeout(NetworkQuality quality, int baseTimeout) {
        // 禁用网络质量调整，始终返回基础超时时间
        return baseTimeout;
    }

    /**
     * 根据网络质量推荐重试次数 - 已禁用，始终返回基础重试次数
     * @param quality 网络质量
     * @param baseRetries 基础重试次数
     * @return 推荐的重试次数
     */
    public int getRecommendedRetries(NetworkQuality quality, int baseRetries) {
        // 禁用网络质量调整，始终返回基础重试次数
        return baseRetries;
    }
    
    /**
     * 关闭监控器
     */
    public void shutdown() {
        // executor现在使用AdaptiveThreadPoolManager统一管理
        // 由AdaptiveThreadPoolManager统一处理关闭，这里不需要单独关闭
        System.out.println("[信息] NetworkQualityMonitor已切换到统一线程池管理，无需单独关闭");
    }
}