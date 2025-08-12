package net.luffy.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网络质量监控器
 * 用于检测网络延迟、丢包率等指标，评估网络质量
 */
public class NetworkQualityMonitor {
    private static final Logger logger = LoggerFactory.getLogger(NetworkQualityMonitor.class);
    
    // 测试目标服务器
    private static final String[] TEST_HOSTS = {
        "pocketapi.48.cn",  // 口袋48 API服务器
        "www.baidu.com",    // 百度
        "www.qq.com"        // 腾讯
    };
    
    private static final int TEST_PORT = 80;
    private static final int CONNECT_TIMEOUT = 3000; // 3秒连接超时
    private static final int TEST_COUNT = 3; // 每个主机测试3次
    
    /**
     * 网络质量枚举
     */
    public enum NetworkQuality {
        EXCELLENT("优秀", 0, 100),      // 0-100ms
        GOOD("良好", 100, 300),         // 100-300ms
        FAIR("一般", 300, 800),         // 300-800ms
        POOR("较差", 800, 2000),        // 800-2000ms
        VERY_POOR("很差", 2000, Integer.MAX_VALUE); // >2000ms
        
        private final String description;
        private final int minLatency;
        private final int maxLatency;
        
        NetworkQuality(String description, int minLatency, int maxLatency) {
            this.description = description;
            this.minLatency = minLatency;
            this.maxLatency = maxLatency;
        }
        
        public String getDescription() {
            return description;
        }
        
        public int getMinLatency() {
            return minLatency;
        }
        
        public int getMaxLatency() {
            return maxLatency;
        }
        
        /**
         * 根据平均延迟判断网络质量
         */
        public static NetworkQuality fromLatency(double avgLatency) {
            for (NetworkQuality quality : values()) {
                if (avgLatency >= quality.minLatency && avgLatency < quality.maxLatency) {
                    return quality;
                }
            }
            return VERY_POOR; // 默认返回最差质量
        }
        
        @Override
        public String toString() {
            return description + "(" + minLatency + "-" + 
                   (maxLatency == Integer.MAX_VALUE ? "∞" : maxLatency) + "ms)";
        }
    }
    
    /**
     * 网络质量检测结果
     */
    public static class NetworkQualityResult {
        private final double averageLatency;
        private final double packetLossRate;
        private final NetworkQuality quality;
        private final long timestamp;
        
        public NetworkQualityResult(double averageLatency, double packetLossRate, NetworkQuality quality) {
            this.averageLatency = averageLatency;
            this.packetLossRate = packetLossRate;
            this.quality = quality;
            this.timestamp = System.currentTimeMillis();
        }
        
        public double getAverageLatency() {
            return averageLatency;
        }
        
        public double getPacketLossRate() {
            return packetLossRate;
        }
        
        public NetworkQuality getQuality() {
            return quality;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("NetworkQualityResult{quality=%s, avgLatency=%.2fms, packetLoss=%.2f%%}",
                    quality, averageLatency, packetLossRate * 100);
        }
    }
    
    /**
     * 检查网络质量
     */
    public NetworkQuality checkNetworkQuality() {
        NetworkQualityResult result = checkNetworkQualityDetailed();
        return result.getQuality();
    }
    
    /**
     * 详细检查网络质量
     */
    public NetworkQualityResult checkNetworkQualityDetailed() {
        long startTime = System.currentTimeMillis();
        
        double totalLatency = 0;
        int successCount = 0;
        int totalTests = TEST_HOSTS.length * TEST_COUNT;
        
        for (String host : TEST_HOSTS) {
            for (int i = 0; i < TEST_COUNT; i++) {
                try {
                    long latency = measureLatency(host, TEST_PORT);
                    if (latency >= 0) {
                        totalLatency += latency;
                        successCount++;
                    }
                } catch (Exception e) {
                    logger.debug("网络延迟测试失败: {} - {}", host, e.getMessage());
                }
            }
        }
        
        double averageLatency = successCount > 0 ? totalLatency / successCount : Double.MAX_VALUE;
        double packetLossRate = 1.0 - (double) successCount / totalTests;
        
        // 根据丢包率调整网络质量评估
        NetworkQuality quality = NetworkQuality.fromLatency(averageLatency);
        if (packetLossRate > 0.5) { // 丢包率超过50%
            quality = NetworkQuality.VERY_POOR;
        } else if (packetLossRate > 0.2) { // 丢包率超过20%
            // 降级一个等级
            switch (quality) {
                case EXCELLENT:
                    quality = NetworkQuality.GOOD;
                    break;
                case GOOD:
                    quality = NetworkQuality.FAIR;
                    break;
                case FAIR:
                    quality = NetworkQuality.POOR;
                    break;
                case POOR:
                    quality = NetworkQuality.VERY_POOR;
                    break;
            }
        }
        
        long endTime = System.currentTimeMillis();
        logger.debug("网络质量检测完成，耗时: {}ms, 结果: {}", endTime - startTime, quality);
        
        return new NetworkQualityResult(averageLatency, packetLossRate, quality);
    }
    
    /**
     * 测量到指定主机的网络延迟
     */
    private long measureLatency(String host, int port) {
        long startTime = System.nanoTime();
        
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            long endTime = System.nanoTime();
            return (endTime - startTime) / 1_000_000; // 转换为毫秒
        } catch (SocketTimeoutException e) {
            logger.debug("连接超时: {}", host);
            return CONNECT_TIMEOUT; // 返回超时时间作为延迟
        } catch (IOException e) {
            logger.debug("连接失败: {} - {}", host, e.getMessage());
            return -1; // 连接失败
        }
    }
    
    /**
     * 检查指定主机是否可达
     */
    public boolean isHostReachable(String host) {
        return isHostReachable(host, TEST_PORT, CONNECT_TIMEOUT);
    }
    
    /**
     * 检查指定主机和端口是否可达
     */
    public boolean isHostReachable(String host, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (IOException e) {
            logger.debug("主机不可达: {}:{} - {}", host, port, e.getMessage());
            return false;
        }
    }
    
    /**
     * 异步检查网络质量
     */
    public CompletableFuture<NetworkQualityResult> checkNetworkQualityAsync() {
        return CompletableFuture.supplyAsync(this::checkNetworkQualityDetailed);
    }
    
    /**
     * 快速网络检查（仅检查口袋48服务器）
     */
    public boolean isPocket48Reachable() {
        return isHostReachable("pocketapi.48.cn", 443, 2000); // HTTPS端口，2秒超时
    }
}