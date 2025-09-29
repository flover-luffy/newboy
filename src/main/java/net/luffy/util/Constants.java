package net.luffy.util;

/**
 * 系统常量定义类
 * 用于集中管理系统中使用的魔法数字和常量值
 */
public final class Constants {
    
    // 防止实例化
    private Constants() {}
    
    // ========== 时间相关常量 ==========
    public static final class Time {
        public static final long SECOND_MS = 1000L;
        public static final long MINUTE_MS = 60 * SECOND_MS;
        public static final long HOUR_MS = 60 * MINUTE_MS;
        public static final long DAY_MS = 24 * HOUR_MS;
        
        // 常用超时时间
        public static final int DEFAULT_CONNECT_TIMEOUT = 5000;
        public static final int DEFAULT_READ_TIMEOUT = 30000;
        public static final int SHORT_TIMEOUT = 2000;
        public static final int LONG_TIMEOUT = 60000;
    }
    
    // ========== 网络相关常量 ==========
    public static final class Network {
        public static final int HTTP_OK = 200;
        public static final int HTTP_BAD_REQUEST = 400;
        public static final int HTTP_INTERNAL_ERROR = 500;
        public static final int HTTP_BAD_GATEWAY = 502;
        public static final int HTTP_SERVICE_UNAVAILABLE = 503;
        public static final int HTTP_GATEWAY_TIMEOUT = 504;
        
        // 重试相关
        public static final long BASE_RETRY_DELAY = 1000L;
        public static final long MAX_RETRY_DELAY = 30000L;
        public static final int DEFAULT_MAX_RETRIES = 3;
    }
    
    // ========== 缓存相关常量 ==========
    public static final class Cache {
        public static final int DEFAULT_CACHE_SIZE = 1000;
        public static final int LARGE_CACHE_SIZE = 2000;
        public static final int SMALL_CACHE_SIZE = 500;
        
        public static final long DEFAULT_TTL = 15 * Time.MINUTE_MS;
        public static final long SHORT_TTL = 5 * Time.MINUTE_MS;
        public static final long LONG_TTL = Time.HOUR_MS;
    }
    
    // ========== 队列相关常量 ==========
    public static final class Queue {
        public static final int DEFAULT_QUEUE_SIZE = 1000;
        public static final int LARGE_QUEUE_SIZE = 2000;
        public static final int OVERFLOW_QUEUE_SIZE = 5000;
        
        public static final int QUEUE_OFFER_TIMEOUT = 100;
        public static final int QUEUE_POLL_TIMEOUT = 50;
    }
    
    // ========== 线程池相关常量 ==========
    public static final class ThreadPool {
        public static final int DEFAULT_CORE_SIZE = 10;
        public static final int DEFAULT_MAX_SIZE = 50;
        public static final int LARGE_POOL_SIZE = 200;
        
        public static final long KEEP_ALIVE_TIME = 60L;
        public static final long ADJUSTMENT_INTERVAL = 30000L;
    }
    
    // ========== 性能监控相关常量 ==========
    public static final class Performance {
        public static final double HIGH_CPU_THRESHOLD = 0.8;
        public static final double MEDIUM_CPU_THRESHOLD = 0.6;
        public static final double HIGH_MEMORY_THRESHOLD = 0.9;
        public static final double MEDIUM_MEMORY_THRESHOLD = 0.7;
        
        public static final int MAX_METRICS_HISTORY = 1000;
        public static final long METRICS_COLLECTION_INTERVAL = 10000L;
        public static final long ALERT_RESPONSE_TIME_THRESHOLD = 5000L;
    }
    
    // ========== 文件大小相关常量 ==========
    public static final class FileSize {
        public static final long MIN_FILE_SIZE = 100L;
        public static final int PREVIEW_LENGTH = 100;
        public static final int MAX_PREVIEW_LENGTH = 500;
        public static final int MAX_DEBUG_RESPONSE_LENGTH = 1000;
    }
    
    // ========== 百分比计算相关常量 ==========
    public static final class Percentage {
        public static final double HUNDRED_PERCENT = 100.0;
        public static final double ZERO_PERCENT = 0.0;
        public static final double EXCELLENT_THRESHOLD = 90.0;
        public static final double GOOD_THRESHOLD = 70.0;
        public static final double POOR_THRESHOLD = 50.0;
    }
}