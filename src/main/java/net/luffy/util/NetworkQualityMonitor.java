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
    private static final int DEFAULT_TIMEOUT = 3000; // 3秒超时
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
        this.executor = Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r, "NetworkQualityMonitor-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * 检测网络质量
     * @return 网络质量检测结果
     */
    public NetworkQualityResult checkNetworkQuality() {
        return checkNetworkQuality(DEFAULT_TEST_HOST, DEFAULT_TEST_PORT, DEFAULT_TEST_COUNT);
    }
    
    /**
     * 检测指定主机的网络质量
     * @param host 目标主机
     * @param port 目标端口
     * @param testCount 测试次数
     * @return 网络质量检测结果
     */
    public NetworkQualityResult checkNetworkQuality(String host, int port, int testCount) {
        List<Future<Long>> futures = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        
        // 并发执行延迟测试
        for (int i = 0; i < testCount; i++) {
            futures.add(executor.submit(() -> measureLatency(host, port)));
        }
        
        // 收集结果
        int successfulTests = 0;
        long totalLatency = 0;
        
        for (Future<Long> future : futures) {
            try {
                Long latency = future.get(DEFAULT_TIMEOUT + 1000, TimeUnit.MILLISECONDS);
                if (latency >= 0) {
                    latencies.add(latency);
                    totalLatency += latency;
                    successfulTests++;
                }
            } catch (Exception e) {
                // 测试失败，记录为丢包
                latencies.add(-1L);
            }
        }
        
        // 计算结果
        long avgLatency = successfulTests > 0 ? totalLatency / successfulTests : Long.MAX_VALUE;
        double packetLossRate = (double) (testCount - successfulTests) / testCount;
        NetworkQuality quality = NetworkQuality.fromLatency(avgLatency);
        
        // 如果丢包率过高，降低网络质量等级
        if (packetLossRate > 0.2) { // 丢包率超过20%
            quality = NetworkQuality.VERY_POOR;
        } else if (packetLossRate > 0.1) { // 丢包率超过10%
            if (quality.ordinal() < NetworkQuality.POOR.ordinal()) {
                quality = NetworkQuality.POOR;
            }
        }
        
        return new NetworkQualityResult(avgLatency, packetLossRate, quality, latencies);
    }
    
    /**
     * 测量到指定主机的延迟
     * @param host 目标主机
     * @param port 目标端口
     * @return 延迟时间(毫秒)，-1表示连接失败
     */
    private long measureLatency(String host, int port) {
        long startTime = System.currentTimeMillis();
        
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), DEFAULT_TIMEOUT);
            long endTime = System.currentTimeMillis();
            return endTime - startTime;
        } catch (IOException e) {
            return -1; // 连接失败
        }
    }
    
    /**
     * 快速检测网络连通性
     * @param host 目标主机
     * @param port 目标端口
     * @param timeout 超时时间(毫秒)
     * @return 是否连通
     */
    public boolean isReachable(String host, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * 根据网络质量推荐超时时间
     * @param quality 网络质量
     * @param baseTimeout 基础超时时间
     * @return 推荐的超时时间
     */
    public int getRecommendedTimeout(NetworkQuality quality, int baseTimeout) {
        switch (quality) {
            case EXCELLENT:
                return Math.max(baseTimeout / 2, 1000); // 最少1秒
            case GOOD:
                return (int) (baseTimeout * 0.8);
            case FAIR:
                return baseTimeout;
            case POOR:
                return (int) (baseTimeout * 1.5);
            case VERY_POOR:
                return baseTimeout * 2;
            default:
                return baseTimeout;
        }
    }
    
    /**
     * 根据网络质量推荐重试次数
     * @param quality 网络质量
     * @param baseRetries 基础重试次数
     * @return 推荐的重试次数
     */
    public int getRecommendedRetries(NetworkQuality quality, int baseRetries) {
        switch (quality) {
            case EXCELLENT:
            case GOOD:
                return Math.max(baseRetries - 1, 1); // 最少1次
            case FAIR:
                return baseRetries;
            case POOR:
                return baseRetries + 1;
            case VERY_POOR:
                return baseRetries + 2;
            default:
                return baseRetries;
        }
    }
    
    /**
     * 关闭监控器
     */
    public void shutdown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}