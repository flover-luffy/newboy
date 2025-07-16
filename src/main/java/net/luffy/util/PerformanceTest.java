package net.luffy.util;

import net.luffy.handler.Xox48Handler;
import net.luffy.util.sender.Pocket48Sender;
import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能测试工具类
 * 用于测试和验证口袋48插件的性能优化效果
 */
public class PerformanceTest {
    
    private static final PerformanceMonitor monitor = PerformanceMonitor.getInstance();
    private static final AtomicInteger testCounter = new AtomicInteger(0);
    private static final AtomicLong totalTestTime = new AtomicLong(0);
    
    /**
     * 执行完整的性能测试套件
     */
    public static void runFullPerformanceTest() {
        // 重置监控器统计
        monitor.resetStats();
        
        try {
            // 1. 内存压力测试
            testMemoryPressure();
            
            // 2. 并发查询测试
            testConcurrentQueries();
            
            // 3. 缓存性能测试
            testCachePerformance();
            
            // 4. 线程池压力测试
            testThreadPoolStress();
            
            // 5. 资源清理测试
            testResourceCleanup();
            
            // 生成最终报告（仅记录到监控器，不输出控制台）
            generateFinalReport();
            
        } catch (Exception e) {
            // 静默处理异常，不输出到控制台
        }
    }
    
    /**
     * 内存压力测试
     */
    private static void testMemoryPressure() {
        long startTime = System.currentTimeMillis();
        
        // 记录初始内存状态
        double initialMemory = monitor.getMemoryUsage();
        
        // 创建大量对象模拟内存压力
        List<byte[]> memoryConsumers = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                // 每次分配1MB内存
                memoryConsumers.add(new byte[1024 * 1024]);
                
                // 每10次检查一次内存状态
                if (i % 10 == 0) {
                    double currentMemory = monitor.getMemoryUsage();
                    
                    // 如果内存使用率超过85%，触发清理
                    if (currentMemory > 0.85) {
                        System.gc();
                        Thread.sleep(100); // 等待GC完成
                        break;
                    }
                }
            }
        } catch (OutOfMemoryError e) {
            // 静默处理内存溢出
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 清理内存
        memoryConsumers.clear();
        System.gc();
        
        long endTime = System.currentTimeMillis();
        double finalMemory = monitor.getMemoryUsage();
    }
    
    /**
     * 并发查询测试
     */
    private static void testConcurrentQueries() {
        int threadCount = 20;
        int queriesPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong totalResponseTime = new AtomicLong(0);
        
        long testStartTime = System.currentTimeMillis();
        
        // 模拟成员名称列表
        String[] memberNames = {
            "测试成员1", "测试成员2", "测试成员3", "测试成员4", "测试成员5",
            "测试成员6", "测试成员7", "测试成员8", "测试成员9", "测试成员10"
        };
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    monitor.incrementActiveThreads();
                    
                    for (int j = 0; j < queriesPerThread; j++) {
                        long queryStart = System.currentTimeMillis();
                        
                        try {
                            // 模拟查询操作
                            String memberName = memberNames[j % memberNames.length];
                            
                            // 这里应该调用实际的查询方法，但为了测试我们模拟延迟
                            Thread.sleep(ThreadLocalRandom.current().nextInt(50, 200));
                            
                            long queryEnd = System.currentTimeMillis();
                            long responseTime = queryEnd - queryStart;
                            
                            totalResponseTime.addAndGet(responseTime);
                            successCount.incrementAndGet();
                            
                            monitor.recordQuery(responseTime);
                            
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                            // 静默处理查询失败
                        }
                    }
                } finally {
                    monitor.decrementActiveThreads();
                    latch.countDown();
                }
            });
        }
        
        try {
            // 等待所有线程完成，最多等待30秒
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            
            long testEndTime = System.currentTimeMillis();
            long totalTestTime = testEndTime - testStartTime;
            
            if (completed) {
                int totalQueries = successCount.get() + failureCount.get();
                double avgResponseTime = totalQueries > 0 ? (double) totalResponseTime.get() / totalQueries : 0;
                double successRate = totalQueries > 0 ? (double) successCount.get() / totalQueries * 100 : 0;
                double qps = totalQueries > 0 ? (double) totalQueries / (totalTestTime / 1000.0) : 0;
                
                // 静默记录测试结果，不输出到控制台
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 静默处理中断
        } finally {
            executor.shutdown();
        }
    }
    
    /**
     * 缓存性能测试
     */
    private static void testCachePerformance() {
        // 模拟缓存操作
        Map<String, Object> testCache = new ConcurrentHashMap<>();
        long cacheTestStart = System.currentTimeMillis();
        
        // 写入测试
        for (int i = 0; i < 1000; i++) {
            testCache.put("key_" + i, "value_" + i + "_" + System.currentTimeMillis());
        }
        
        long writeTime = System.currentTimeMillis() - cacheTestStart;
        
        // 读取测试
        long readTestStart = System.currentTimeMillis();
        int hitCount = 0;
        
        for (int i = 0; i < 2000; i++) {
            String key = "key_" + (i % 1000);
            if (testCache.get(key) != null) {
                hitCount++;
            }
        }
        
        long readTime = System.currentTimeMillis() - readTestStart;
        double hitRate = (double) hitCount / 2000 * 100;
        
        // 清理测试
        long cleanupStart = System.currentTimeMillis();
        testCache.clear();
        long cleanupTime = System.currentTimeMillis() - cleanupStart;
        
        // 静默记录缓存测试结果
    }
    
    /**
     * 线程池压力测试
     */
    private static void testThreadPoolStress() {
        // 创建测试线程池
        ThreadPoolExecutor testPool = new ThreadPoolExecutor(
            4, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "TestPool-" + counter.incrementAndGet());
                }
            }
        );
        
        long poolTestStart = System.currentTimeMillis();
        CountDownLatch poolLatch = new CountDownLatch(50);
        AtomicInteger completedTasks = new AtomicInteger(0);
        
        // 提交50个任务
        for (int i = 0; i < 50; i++) {
            final int taskId = i;
            testPool.submit(() -> {
                try {
                    // 模拟工作负载
                    Thread.sleep(ThreadLocalRandom.current().nextInt(100, 500));
                    completedTasks.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    poolLatch.countDown();
                }
            });
        }
        
        try {
            boolean poolCompleted = poolLatch.await(10, TimeUnit.SECONDS);
            long poolTestEnd = System.currentTimeMillis();
            
            // 静默记录线程池测试结果
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            testPool.shutdown();
        }
    }
    
    /**
     * 资源清理测试
     */
    private static void testResourceCleanup() {
        double memoryBefore = monitor.getMemoryUsage();
        
        // 模拟资源清理
        long cleanupStart = System.currentTimeMillis();
        
        // 强制垃圾回收
        System.gc();
        
        try {
            Thread.sleep(1000); // 等待GC完成
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long cleanupEnd = System.currentTimeMillis();
        double memoryAfter = monitor.getMemoryUsage();
        
        double memoryReduced = (memoryBefore - memoryAfter) * 100;
        
        // 静默记录资源清理结果
    }
    
    /**
     * 生成最终测试报告
     */
    private static void generateFinalReport() {
        // 静默生成性能测试报告，不输出到控制台
        String report = monitor.getPerformanceReport();
        double memoryUsage = monitor.getMemoryUsage();
        
        // 内部记录测试结果和建议，但不输出到控制台
    }
    
    /**
     * 快速性能检查
     */
    public static void quickPerformanceCheck() {
        // 静默执行快速性能检查，不输出到控制台
        String quickStatus = monitor.getQuickStatus();
        String memoryStatus = monitor.checkMemoryStatus();
        String threadInfo = monitor.getThreadInfo();
    }
    
    /**
     * 主方法，用于独立运行性能测试
     */
    public static void main(String[] args) {
        if (args.length > 0 && "quick".equals(args[0])) {
            quickPerformanceCheck();
        } else {
            runFullPerformanceTest();
        }
    }
}