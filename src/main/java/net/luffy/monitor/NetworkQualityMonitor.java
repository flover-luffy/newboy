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
    private static final int CONNECT_TIMEOUT = 2000; // 优化为2秒连接超时，提高响应速度
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
     * 检查网络质量 - 已禁用，始终返回优秀网络质量
     */
    public NetworkQuality checkNetworkQuality() {
        // 禁用网络质量检测，始终返回优秀网络质量以减少系统开销
        return NetworkQuality.EXCELLENT;
    }
    
    /**
     * 详细检查网络质量 - 已禁用，始终返回优秀网络质量
     */
    public NetworkQualityResult checkNetworkQualityDetailed() {
        // 禁用网络质量检测，始终返回优秀网络质量以减少系统开销
        return new NetworkQualityResult(10.0, 0.0, NetworkQuality.EXCELLENT);
    }

    /**
     * 测量到指定主机的网络延迟 - 已禁用
     */
    private long measureLatency(String host, int port) {
        // 禁用网络延迟测量，直接返回固定值以减少系统开销
        return 10; // 模拟10ms延迟
    }

    /**
     * 检查指定主机是否可达 - 简化版本
     */
    public boolean isHostReachable(String host) {
        return true; // 始终返回可达状态
    }
    
    /**
     * 检查指定主机和端口是否可达 - 简化版本
     */
    public boolean isHostReachable(String host, int port, int timeout) {
        return true; // 始终返回可达状态
    }

    /**
     * 异步检查网络质量 - 已禁用，始终返回优秀网络质量
     */
    public CompletableFuture<NetworkQualityResult> checkNetworkQualityAsync() {
        return CompletableFuture.completedFuture(
            new NetworkQualityResult(10.0, 0.0, NetworkQuality.EXCELLENT)
        );
    }
    
    /**
     * 快速网络检查（仅检查口袋48服务器）- 简化版本
     */
    public boolean isPocket48Reachable() {
        return true; // 始终返回可达状态
    }
}