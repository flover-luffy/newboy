package net.luffy.util;

import net.luffy.Newboy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Collections;

/**
 * 智能缓存管理器
 * 实现多级缓存策略，包括LRU淘汰、内存压力感知、弱引用支持
 */
public class SmartCacheManager {
    
    private static final SmartCacheManager INSTANCE = new SmartCacheManager();
    
    // 缓存配置
    private static final int DEFAULT_MAX_SIZE = 1000;
    private static final long DEFAULT_TTL_MS = 30 * 60 * 1000; // 30分钟
    private static final double MEMORY_PRESSURE_THRESHOLD = 0.8; // 80%内存使用率
    
    // 多级缓存存储
    private final Map<String, LRUCache<String, CacheEntry>> caches = new ConcurrentHashMap<>();
    private final Map<String, WeakReference<Object>> weakCache = new ConcurrentHashMap<>();
    
    // 缓存统计
    private final AtomicLong totalHits = new AtomicLong(0);
    private final AtomicLong totalMisses = new AtomicLong(0);
    private final AtomicLong totalEvictions = new AtomicLong(0);
    private final AtomicInteger activeCaches = new AtomicInteger(0);
    
    // 清理队列
    private final ConcurrentLinkedQueue<String> cleanupQueue = new ConcurrentLinkedQueue<>();
    
    private SmartCacheManager() {
        // 启动后台清理任务
        startBackgroundCleanup();
    }
    
    public static SmartCacheManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取或创建缓存
     */
    public LRUCache<String, CacheEntry> getCache(String cacheName) {
        return getCache(cacheName, DEFAULT_MAX_SIZE, DEFAULT_TTL_MS);
    }
    
    /**
     * 获取或创建指定配置的缓存
     */
    public LRUCache<String, CacheEntry> getCache(String cacheName, int maxSize, long ttlMs) {
        return caches.computeIfAbsent(cacheName, k -> {
            activeCaches.incrementAndGet();
            return new LRUCache<>(maxSize, ttlMs);
        });
    }
    
    /**
     * 存储到弱引用缓存（用于大对象）
     */
    public void putWeak(String key, Object value) {
        weakCache.put(key, new WeakReference<>(value));
    }
    
    /**
     * 从弱引用缓存获取
     */
    public Object getWeak(String key) {
        WeakReference<Object> ref = weakCache.get(key);
        if (ref != null) {
            Object value = ref.get();
            if (value != null) {
                totalHits.incrementAndGet();
                return value;
            } else {
                // 对象已被GC回收
                weakCache.remove(key);
                totalMisses.incrementAndGet();
            }
        } else {
            totalMisses.incrementAndGet();
        }
        return null;
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStats getStats() {
        long hits = totalHits.get();
        long misses = totalMisses.get();
        long total = hits + misses;
        double hitRate = total > 0 ? (double) hits / total : 0.0;
        
        return new CacheStats(
            hits, misses, hitRate, totalEvictions.get(),
            activeCaches.get(), estimateTotalMemoryUsage()
        );
    }
    
    /**
     * 执行内存压力清理
     */
    public void performMemoryPressureCleanup() {
        double memoryUsage = getMemoryUsage();
        if (memoryUsage > MEMORY_PRESSURE_THRESHOLD) {
            // 内存压力清理开始
            
            // 清理弱引用缓存中的无效引用
            cleanupWeakReferences();
            
            // 清理过期条目
            cleanupExpiredEntries();
            
            // 如果内存压力仍然很大，进行强制清理
            if (getMemoryUsage() > 0.9) {
                performAggressiveCleanup();
            }
        }
    }
    
    /**
     * 清理指定缓存
     */
    public void clearCache(String cacheName) {
        LRUCache<String, CacheEntry> cache = caches.get(cacheName);
        if (cache != null) {
            cache.clear();
            // 已清理缓存
        }
    }
    
    /**
     * 清理所有缓存
     */
    public void clearAllCaches() {
        caches.values().forEach(LRUCache::clear);
        weakCache.clear();
        // 已清理所有缓存
    }
    
    /**
     * 获取缓存报告
     */
    public String getCacheReport() {
        CacheStats stats = getStats();
        StringBuilder report = new StringBuilder();
        
        report.append("📊 智能缓存管理器报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━\n");
        report.append(String.format("🎯 命中率: %.1f%% (%d/%d)\n", 
            stats.getHitRate() * 100, stats.getHits(), stats.getHits() + stats.getMisses()));
        report.append(String.format("🗑️ 淘汰次数: %d\n", stats.getEvictions()));
        report.append(String.format("📦 活跃缓存: %d\n", stats.getActiveCaches()));
        report.append(String.format("💾 内存使用: %.1f MB\n", stats.getMemoryUsage() / 1024.0 / 1024.0));
        report.append(String.format("🔗 弱引用: %d\n", weakCache.size()));
        
        // 各缓存详情
        report.append("\n📋 缓存详情:\n");
        for (Map.Entry<String, LRUCache<String, CacheEntry>> entry : caches.entrySet()) {
            LRUCache<String, CacheEntry> cache = entry.getValue();
            report.append(String.format("  %s: %d/%d 条目\n", 
                entry.getKey(), cache.size(), cache.getMaxSize()));
        }
        
        return report.toString();
    }
    
    // 私有方法
    private void startBackgroundCleanup() {
        UnifiedSchedulerManager.getInstance().scheduleCleanupTask(
            this::backgroundCleanup, 5 * 60 * 1000, 5 * 60 * 1000); // 每5分钟清理一次
    }
    
    private void backgroundCleanup() {
        try {
            // 清理过期条目
            cleanupExpiredEntries();
            
            // 清理弱引用
            cleanupWeakReferences();
            
            // 检查内存压力
            performMemoryPressureCleanup();
            
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[智能缓存] 后台清理失败", e);
        }
    }
    
    private void cleanupExpiredEntries() {
        int cleanedCount = 0;
        for (LRUCache<String, CacheEntry> cache : caches.values()) {
            cleanedCount += cache.cleanupExpired();
        }
        if (cleanedCount > 0) {
            totalEvictions.addAndGet(cleanedCount);
        }
    }
    
    private void cleanupWeakReferences() {
        Iterator<Map.Entry<String, WeakReference<Object>>> iterator = weakCache.entrySet().iterator();
        int cleanedCount = 0;
        
        while (iterator.hasNext()) {
            Map.Entry<String, WeakReference<Object>> entry = iterator.next();
            if (entry.getValue().get() == null) {
                iterator.remove();
                cleanedCount++;
            }
        }
        
        if (cleanedCount > 0) {
            // 清理了无效弱引用
        }
    }
    
    private void performAggressiveCleanup() {
        // 执行激进清理策略
        
        // 清理一半的缓存条目
        for (LRUCache<String, CacheEntry> cache : caches.values()) {
            cache.evictHalf();
        }
        
        // 清理所有弱引用
        weakCache.clear();
        
        // 建议JVM进行垃圾回收
        System.gc();
    }
    
    private double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return (double) usedMemory / runtime.maxMemory();
    }
    
    private long estimateTotalMemoryUsage() {
        long total = 0;
        for (LRUCache<String, CacheEntry> cache : caches.values()) {
            total += cache.estimateMemoryUsage();
        }
        // 弱引用缓存的内存使用量较难估算，使用固定值
        total += weakCache.size() * 100; // 每个引用估算100字节
        return total;
    }
    
    // 内部类：LRU缓存实现
    public static class LRUCache<K, V> {
        private final int maxSize;
        private final long ttlMs;
        private final Map<K, V> cache;
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        
        public LRUCache(int maxSize, long ttlMs) {
            this.maxSize = maxSize;
            this.ttlMs = ttlMs;
            this.cache = Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > maxSize;
                }
            });
        }
        
        public V get(K key) {
            lock.readLock().lock();
            try {
                V value = cache.get(key);
                if (value instanceof CacheEntry) {
                    CacheEntry entry = (CacheEntry) value;
                    if (entry.isExpired()) {
                        cache.remove(key);
                        return null;
                    }
                }
                return value;
            } finally {
                lock.readLock().unlock();
            }
        }
        
        public void put(K key, V value) {
            lock.writeLock().lock();
            try {
                cache.put(key, value);
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        public void clear() {
            lock.writeLock().lock();
            try {
                cache.clear();
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        public int size() {
            return cache.size();
        }
        
        public int getMaxSize() {
            return maxSize;
        }
        
        public int cleanupExpired() {
            lock.writeLock().lock();
            try {
                int cleanedCount = 0;
                Iterator<Map.Entry<K, V>> iterator = cache.entrySet().iterator();
                
                while (iterator.hasNext()) {
                    Map.Entry<K, V> entry = iterator.next();
                    if (entry.getValue() instanceof CacheEntry) {
                        CacheEntry cacheEntry = (CacheEntry) entry.getValue();
                        if (cacheEntry.isExpired()) {
                            iterator.remove();
                            cleanedCount++;
                        }
                    }
                }
                return cleanedCount;
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        public void evictHalf() {
            lock.writeLock().lock();
            try {
                int targetSize = cache.size() / 2;
                Iterator<K> iterator = cache.keySet().iterator();
                
                while (iterator.hasNext() && cache.size() > targetSize) {
                    iterator.next();
                    iterator.remove();
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        public long estimateMemoryUsage() {
            // 简单估算：每个条目平均1KB
            return cache.size() * 1024L;
        }
    }
    
    // 缓存条目类
    public static class CacheEntry {
        private final Object value;
        private final long createTime;
        private final long ttl;
        
        public CacheEntry(Object value, long ttl) {
            this.value = value;
            this.createTime = System.currentTimeMillis();
            this.ttl = ttl;
        }
        
        public Object getValue() {
            return value;
        }
        
        public boolean isExpired() {
            return ttl > 0 && (System.currentTimeMillis() - createTime) > ttl;
        }
        
        public long getAge() {
            return System.currentTimeMillis() - createTime;
        }
    }
    
    // 缓存统计类
    public static class CacheStats {
        private final long hits;
        private final long misses;
        private final double hitRate;
        private final long evictions;
        private final int activeCaches;
        private final long memoryUsage;
        
        public CacheStats(long hits, long misses, double hitRate, 
                         long evictions, int activeCaches, long memoryUsage) {
            this.hits = hits;
            this.misses = misses;
            this.hitRate = hitRate;
            this.evictions = evictions;
            this.activeCaches = activeCaches;
            this.memoryUsage = memoryUsage;
        }
        
        public long getHits() { return hits; }
        public long getMisses() { return misses; }
        public double getHitRate() { return hitRate; }
        public long getEvictions() { return evictions; }
        public int getActiveCaches() { return activeCaches; }
        public long getMemoryUsage() { return memoryUsage; }
    }
}