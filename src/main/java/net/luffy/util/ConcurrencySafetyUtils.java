package net.luffy.util;

import net.luffy.Newboy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.*;
import java.util.function.*;
import java.lang.ref.WeakReference;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.management.ThreadInfo;
import java.util.stream.Collectors;
import net.luffy.util.UnifiedSchedulerManager;

/**
 * 并发安全工具类
 * 提供线程安全的数据结构、锁管理和并发控制机制
 */
public class ConcurrencySafetyUtils {
    
    private static final ConcurrencySafetyUtils INSTANCE = new ConcurrencySafetyUtils();
    
    // 锁管理
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> namedLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> namedSemaphores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CountDownLatch> namedLatches = new ConcurrentHashMap<>();
    
    // 并发控制
    private final AtomicInteger lockCounter = new AtomicInteger(0);
    private final AtomicLong totalWaitTime = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> lockUsageStats = new ConcurrentHashMap<>();
    
    // 死锁检测 - 使用统一调度器管理
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final ScheduledExecutorService deadlockDetector = UnifiedSchedulerManager.getInstance().getScheduledExecutor();
    
    private ConcurrencySafetyUtils() {
        // 启动死锁检测
        startDeadlockDetection();
    }
    
    public static ConcurrencySafetyUtils getInstance() {
        return INSTANCE;
    }
    
    /**
     * 获取命名读写锁
     */
    public ReentrantReadWriteLock getNamedLock(String name) {
        return namedLocks.computeIfAbsent(name, k -> {
            lockCounter.incrementAndGet();
            lockUsageStats.put(name, new AtomicLong(0));
            return new ReentrantReadWriteLock(true); // 公平锁
        });
    }
    
    /**
     * 获取命名信号量
     */
    public Semaphore getNamedSemaphore(String name, int permits) {
        return namedSemaphores.computeIfAbsent(name, k -> new Semaphore(permits, true));
    }
    
    /**
     * 获取命名倒计时锁存器
     */
    public CountDownLatch getNamedLatch(String name, int count) {
        return namedLatches.computeIfAbsent(name, k -> new CountDownLatch(count));
    }
    
    /**
     * 带超时的倒计时锁存器等待
     */
    public boolean awaitLatch(String name, int count, long timeout, TimeUnit unit) {
        CountDownLatch latch = getNamedLatch(name, count);
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 安全的信号量获取（带超时）
     */
    public boolean tryAcquireSemaphore(String semaphoreName, int permits, long timeout, TimeUnit unit) {
        Semaphore semaphore = getNamedSemaphore(semaphoreName, permits);
        try {
            return semaphore.tryAcquire(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    /**
     * 释放信号量
     */
    public void releaseSemaphore(String semaphoreName, int permits) {
        Semaphore semaphore = namedSemaphores.get(semaphoreName);
        if (semaphore != null) {
            semaphore.release();
        }
    }
    
    /**
     * 安全执行读操作
     */
    public <T> T safeRead(String lockName, Supplier<T> operation) {
        ReentrantReadWriteLock lock = getNamedLock(lockName);
        Lock readLock = lock.readLock();
        
        long startTime = System.currentTimeMillis();
        readLock.lock();
        try {
            lockUsageStats.get(lockName).incrementAndGet();
            return operation.get();
        } finally {
            readLock.unlock();
            totalWaitTime.addAndGet(System.currentTimeMillis() - startTime);
        }
    }
    
    /**
     * 安全执行写操作
     */
    public <T> T safeWrite(String lockName, Supplier<T> operation) {
        ReentrantReadWriteLock lock = getNamedLock(lockName);
        Lock writeLock = lock.writeLock();
        
        long startTime = System.currentTimeMillis();
        writeLock.lock();
        try {
            lockUsageStats.get(lockName).incrementAndGet();
            return operation.get();
        } finally {
            writeLock.unlock();
            totalWaitTime.addAndGet(System.currentTimeMillis() - startTime);
        }
    }
    
    /**
     * 安全执行写操作（无返回值）
     */
    public void safeWrite(String lockName, Runnable operation) {
        safeWrite(lockName, () -> {
            operation.run();
            return null;
        });
    }
    
    /**
     * 带超时的安全读操作
     */
    public <T> Optional<T> safeReadWithTimeout(String lockName, Supplier<T> operation, long timeout, TimeUnit unit) {
        ReentrantReadWriteLock lock = getNamedLock(lockName);
        Lock readLock = lock.readLock();
        
        long startTime = System.currentTimeMillis();
        try {
            if (readLock.tryLock(timeout, unit)) {
                try {
                    lockUsageStats.get(lockName).incrementAndGet();
                    return Optional.of(operation.get());
                } finally {
                    readLock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            totalWaitTime.addAndGet(System.currentTimeMillis() - startTime);
        }
        return Optional.empty();
    }
    
    /**
     * 带超时的安全写操作
     */
    public <T> Optional<T> safeWriteWithTimeout(String lockName, Supplier<T> operation, long timeout, TimeUnit unit) {
        ReentrantReadWriteLock lock = getNamedLock(lockName);
        Lock writeLock = lock.writeLock();
        
        long startTime = System.currentTimeMillis();
        try {
            if (writeLock.tryLock(timeout, unit)) {
                try {
                    lockUsageStats.get(lockName).incrementAndGet();
                    return Optional.of(operation.get());
                } finally {
                    writeLock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            totalWaitTime.addAndGet(System.currentTimeMillis() - startTime);
        }
        return Optional.empty();
    }
    
    /**
     * 限流执行（带默认超时）
     */
    public <T> Optional<T> rateLimitedExecution(String semaphoreName, int permits, Supplier<T> operation) {
        return rateLimitedExecutionWithTimeout(semaphoreName, permits, operation, 30, TimeUnit.SECONDS);
    }
    
    /**
     * 限流执行（兼容旧版本，已废弃）
     * @deprecated 使用 rateLimitedExecutionWithTimeout 替代以避免无限期阻塞
     */
    @Deprecated
    public <T> T rateLimitedExecutionBlocking(String semaphoreName, int permits, Supplier<T> operation) throws InterruptedException {
        Semaphore semaphore = getNamedSemaphore(semaphoreName, permits);
        
        semaphore.acquire();
        try {
            return operation.get();
        } finally {
            semaphore.release();
        }
    }
    
    /**
     * 带超时的限流执行
     */
    public <T> Optional<T> rateLimitedExecutionWithTimeout(String semaphoreName, int permits, 
                                                          Supplier<T> operation, long timeout, TimeUnit unit) {
        Semaphore semaphore = getNamedSemaphore(semaphoreName, permits);
        
        try {
            if (semaphore.tryAcquire(timeout, unit)) {
                try {
                    return Optional.of(operation.get());
                } finally {
                    semaphore.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }
    
    /**
     * 创建线程安全的计数器
     */
    public ThreadSafeCounter createCounter(String name) {
        return new ThreadSafeCounter(name);
    }
    
    /**
     * 创建线程安全的累加器
     */
    public ThreadSafeAccumulator createAccumulator(String name) {
        return new ThreadSafeAccumulator(name);
    }
    
    /**
     * 创建线程安全的缓存
     */
    public <K, V> ThreadSafeCache<K, V> createCache(String name, int maxSize) {
        return new ThreadSafeCache<>(name, maxSize);
    }
    
    /**
     * 创建线程安全的队列
     */
    public <T> ThreadSafeQueue<T> createQueue(String name) {
        return new ThreadSafeQueue<>(name);
    }
    
    /**
     * 获取并发统计信息
     */
    public ConcurrencyStats getStats() {
        return new ConcurrencyStats(
            lockCounter.get(),
            totalWaitTime.get(),
            namedSemaphores.size(),
            namedLatches.size(),
            new HashMap<>(lockUsageStats.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().get()
                )))
        );
    }
    
    /**
     * 检测死锁
     */
    public List<DeadlockInfo> detectDeadlocks() {
        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        if (deadlockedThreads == null) {
            return Collections.emptyList();
        }
        
        List<DeadlockInfo> deadlocks = new ArrayList<>();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(deadlockedThreads);
        
        for (ThreadInfo threadInfo : threadInfos) {
            if (threadInfo != null) {
                deadlocks.add(new DeadlockInfo(
                    threadInfo.getThreadId(),
                    threadInfo.getThreadName(),
                    threadInfo.getThreadState(),
                    threadInfo.getLockName(),
                    threadInfo.getLockOwnerId(),
                    threadInfo.getLockOwnerName()
                ));
            }
        }
        
        return deadlocks;
    }
    
    /**
     * 获取并发安全报告
     */
    public String getConcurrencyReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("🔒 并发安全监控报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━━━━━\n");
        
        ConcurrencyStats stats = getStats();
        report.append(String.format("📊 总体统计:\n"));
        report.append(String.format("  活跃锁数量: %d\n", stats.getActiveLocks()));
        report.append(String.format("  信号量数量: %d\n", stats.getActiveSemaphores()));
        report.append(String.format("  倒计时锁存器: %d\n", stats.getActiveLatches()));
        report.append(String.format("  总等待时间: %dms\n", stats.getTotalWaitTime()));
        
        // 锁使用统计
        report.append("\n🔐 锁使用统计:\n");
        stats.getLockUsage().entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
            .limit(10)
            .forEach(entry -> report.append(String.format("  %s: %d次\n", 
                entry.getKey(), entry.getValue())));
        
        // 死锁检测
        List<DeadlockInfo> deadlocks = detectDeadlocks();
        if (!deadlocks.isEmpty()) {
            report.append("\n⚠️ 检测到死锁:\n");
            for (DeadlockInfo deadlock : deadlocks) {
                report.append(String.format("  线程 %s (ID: %d) 状态: %s\n",
                    deadlock.getThreadName(), deadlock.getThreadId(), deadlock.getThreadState()));
                report.append(String.format("    等待锁: %s\n", deadlock.getLockName()));
                report.append(String.format("    锁持有者: %s (ID: %d)\n",
                    deadlock.getLockOwnerName(), deadlock.getLockOwnerId()));
            }
        } else {
            report.append("\n✅ 未检测到死锁\n");
        }
        
        return report.toString();
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        // 清理已完成的倒计时锁存器
        namedLatches.entrySet().removeIf(entry -> entry.getValue().getCount() == 0);
        
        // 清理未使用的锁（使用弱引用检查）
        namedLocks.entrySet().removeIf(entry -> {
            ReentrantReadWriteLock lock = entry.getValue();
            return !lock.hasQueuedThreads() && !lock.isWriteLocked() && lock.getReadLockCount() == 0;
        });
    }
    
    /**
     * 关闭并发安全工具
     */
    public void shutdown() {
        try {
            // deadlockDetector现在由UnifiedSchedulerManager管理，不需要显式关闭
            
            // 清理所有资源
            namedLocks.clear();
            namedSemaphores.clear();
            namedLatches.clear();
            
            // 重置统计计数器
            lockCounter.set(0);
            totalWaitTime.set(0);
            
            Newboy.INSTANCE.getLogger().info("[ConcurrencySafetyUtils] 资源清理完成，调度器由UnifiedSchedulerManager统一管理");
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[ConcurrencySafetyUtils] 关闭时发生错误", e);
        }
    }
    
    // 私有方法
    private void startDeadlockDetection() {
        deadlockDetector.scheduleAtFixedRate(() -> {
            try {
                List<DeadlockInfo> deadlocks = detectDeadlocks();
                if (!deadlocks.isEmpty()) {
                    Newboy.INSTANCE.getLogger().error(
                        String.format("[并发安全] 检测到 %d 个死锁", deadlocks.size()));
                    
                    for (DeadlockInfo deadlock : deadlocks) {
                        Newboy.INSTANCE.getLogger().error(
                            String.format("[死锁] 线程: %s, 状态: %s, 等待锁: %s",
                                deadlock.getThreadName(), deadlock.getThreadState(), deadlock.getLockName()));
                    }
                }
            } catch (Exception e) {
                Newboy.INSTANCE.getLogger().error("[并发安全] 死锁检测失败", e);
            }
        }, 30, 30, TimeUnit.SECONDS); // 每30秒检测一次
    }
    
    // 内部类：线程安全计数器
    public static class ThreadSafeCounter {
        private final String name;
        private final AtomicLong value = new AtomicLong(0);
        
        public ThreadSafeCounter(String name) {
            this.name = name;
        }
        
        public long increment() {
            return value.incrementAndGet();
        }
        
        public long decrement() {
            return value.decrementAndGet();
        }
        
        public long addAndGet(long delta) {
            return value.addAndGet(delta);
        }
        
        public long get() {
            return value.get();
        }
        
        public void set(long newValue) {
            value.set(newValue);
        }
        
        public boolean compareAndSet(long expect, long update) {
            return value.compareAndSet(expect, update);
        }
        
        public String getName() {
            return name;
        }
    }
    
    // 内部类：线程安全累加器
    public static class ThreadSafeAccumulator {
        private final String name;
        private final LongAdder adder = new LongAdder();
        
        public ThreadSafeAccumulator(String name) {
            this.name = name;
        }
        
        public void add(long value) {
            adder.add(value);
        }
        
        public void increment() {
            adder.increment();
        }
        
        public void decrement() {
            adder.decrement();
        }
        
        public long sum() {
            return adder.sum();
        }
        
        public void reset() {
            adder.reset();
        }
        
        public String getName() {
            return name;
        }
    }
    
    // 内部类：线程安全缓存
    public static class ThreadSafeCache<K, V> {
        private final String name;
        private final ConcurrentHashMap<K, V> cache;
        private final int maxSize;
        private final AtomicInteger size = new AtomicInteger(0);
        
        public ThreadSafeCache(String name, int maxSize) {
            this.name = name;
            this.maxSize = maxSize;
            this.cache = new ConcurrentHashMap<>(Math.min(maxSize, 16));
        }
        
        public V get(K key) {
            return cache.get(key);
        }
        
        public V put(K key, V value) {
            if (size.get() >= maxSize && !cache.containsKey(key)) {
                // 简单的LRU策略：随机移除一个元素
                K randomKey = cache.keys().nextElement();
                cache.remove(randomKey);
                size.decrementAndGet();
            }
            
            V oldValue = cache.put(key, value);
            if (oldValue == null) {
                size.incrementAndGet();
            }
            return oldValue;
        }
        
        public V remove(K key) {
            V removed = cache.remove(key);
            if (removed != null) {
                size.decrementAndGet();
            }
            return removed;
        }
        
        public void clear() {
            cache.clear();
            size.set(0);
        }
        
        public int size() {
            return size.get();
        }
        
        public boolean containsKey(K key) {
            return cache.containsKey(key);
        }
        
        public String getName() {
            return name;
        }
    }
    
    // 内部类：线程安全队列
    public static class ThreadSafeQueue<T> {
        private final String name;
        private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger(0);
        
        public ThreadSafeQueue(String name) {
            this.name = name;
        }
        
        public boolean offer(T item) {
            boolean added = queue.offer(item);
            if (added) {
                size.incrementAndGet();
            }
            return added;
        }
        
        public T poll() {
            T item = queue.poll();
            if (item != null) {
                size.decrementAndGet();
            }
            return item;
        }
        
        public T peek() {
            return queue.peek();
        }
        
        public boolean isEmpty() {
            return queue.isEmpty();
        }
        
        public int size() {
            return size.get();
        }
        
        public void clear() {
            queue.clear();
            size.set(0);
        }
        
        public String getName() {
            return name;
        }
    }
    
    // 数据类
    public static class ConcurrencyStats {
        private final int activeLocks;
        private final long totalWaitTime;
        private final int activeSemaphores;
        private final int activeLatches;
        private final Map<String, Long> lockUsage;
        
        public ConcurrencyStats(int activeLocks, long totalWaitTime, int activeSemaphores, 
                               int activeLatches, Map<String, Long> lockUsage) {
            this.activeLocks = activeLocks;
            this.totalWaitTime = totalWaitTime;
            this.activeSemaphores = activeSemaphores;
            this.activeLatches = activeLatches;
            this.lockUsage = lockUsage;
        }
        
        public int getActiveLocks() { return activeLocks; }
        public long getTotalWaitTime() { return totalWaitTime; }
        public int getActiveSemaphores() { return activeSemaphores; }
        public int getActiveLatches() { return activeLatches; }
        public Map<String, Long> getLockUsage() { return lockUsage; }
    }
    
    public static class DeadlockInfo {
        private final long threadId;
        private final String threadName;
        private final Thread.State threadState;
        private final String lockName;
        private final long lockOwnerId;
        private final String lockOwnerName;
        
        public DeadlockInfo(long threadId, String threadName, Thread.State threadState,
                           String lockName, long lockOwnerId, String lockOwnerName) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.threadState = threadState;
            this.lockName = lockName;
            this.lockOwnerId = lockOwnerId;
            this.lockOwnerName = lockOwnerName;
        }
        
        public long getThreadId() { return threadId; }
        public String getThreadName() { return threadName; }
        public Thread.State getThreadState() { return threadState; }
        public String getLockName() { return lockName; }
        public long getLockOwnerId() { return lockOwnerId; }
        public String getLockOwnerName() { return lockOwnerName; }
    }
    
    // 辅助方法：带超时的CountDownLatch等待
    public static boolean awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            return latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    // 辅助方法：带超时的信号量获取
    public static boolean tryAcquireSemaphore(Semaphore semaphore, long timeout, TimeUnit unit) {
        try {
            return semaphore.tryAcquire(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    
    // 辅助方法：安全释放信号量
    public static void releaseSemaphore(Semaphore semaphore) {
        try {
            semaphore.release();
        } catch (Exception e) {
            // 记录错误但不抛出异常
            Newboy.INSTANCE.getLogger().warning("[ConcurrencySafetyUtils] 释放信号量时发生错误: " + e.getMessage());
        }
    }
}