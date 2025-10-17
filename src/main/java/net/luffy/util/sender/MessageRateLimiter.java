package net.luffy.util.sender;

import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.luffy.util.UnifiedLogger;

/**
 * 消息发送速率限制器
 * 实现每秒最多发送3条消息的限制，防止账号被风控
 */
public class MessageRateLimiter {
    
    private static final int MAX_MESSAGES_PER_SECOND = 3;
    private static final long RATE_LIMIT_WINDOW_MS = 1000; // 1秒
    
    private final Semaphore semaphore;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong lastResetTime;
    private final AtomicLong messagesSentInWindow;
    private final UnifiedLogger logger;
    
    private static volatile MessageRateLimiter instance;
    
    private MessageRateLimiter() {
        this.semaphore = new Semaphore(MAX_MESSAGES_PER_SECOND, true);
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "MessageRateLimiter-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.lastResetTime = new AtomicLong(System.currentTimeMillis());
        this.messagesSentInWindow = new AtomicLong(0);
        this.logger = UnifiedLogger.getInstance();
        
        // 每秒重置许可证
        scheduler.scheduleAtFixedRate(this::resetPermits, 1, 1, TimeUnit.SECONDS);
        
        logger.info("MessageRateLimiter", "消息速率限制器已启动，每秒最多发送 " + MAX_MESSAGES_PER_SECOND + " 条消息");
    }
    
    /**
     * 获取单例实例
     */
    public static MessageRateLimiter getInstance() {
        if (instance == null) {
            synchronized (MessageRateLimiter.class) {
                if (instance == null) {
                    instance = new MessageRateLimiter();
                }
            }
        }
        return instance;
    }
    
    /**
     * 尝试获取发送许可
     * @return true 如果获得许可，false 如果需要等待
     */
    public boolean tryAcquire() {
        return semaphore.tryAcquire();
    }
    
    /**
     * 获取发送许可（阻塞等待）
     * @throws InterruptedException 如果等待被中断
     */
    public void acquire() throws InterruptedException {
        long startTime = System.currentTimeMillis();
        semaphore.acquire();
        long waitTime = System.currentTimeMillis() - startTime;
        
        if (waitTime > 100) { // 如果等待时间超过100ms，记录日志
            logger.debug("MessageRateLimiter", "消息发送等待了 " + waitTime + "ms");
        }
        
        messagesSentInWindow.incrementAndGet();
    }
    
    /**
     * 尝试获取发送许可（带超时）
     * @param timeout 超时时间
     * @param unit 时间单位
     * @return true 如果获得许可，false 如果超时
     * @throws InterruptedException 如果等待被中断
     */
    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        boolean acquired = semaphore.tryAcquire(timeout, unit);
        if (acquired) {
            messagesSentInWindow.incrementAndGet();
        }
        return acquired;
    }
    
    /**
     * 重置许可证（每秒调用一次）
     */
    private void resetPermits() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastReset = currentTime - lastResetTime.get();
        
        if (timeSinceLastReset >= RATE_LIMIT_WINDOW_MS) {
            // 释放所有许可证，重置为最大值
            int availablePermits = semaphore.availablePermits();
            int permitsToRelease = MAX_MESSAGES_PER_SECOND - availablePermits;
            
            if (permitsToRelease > 0) {
                semaphore.release(permitsToRelease);
            }
            
            long messagesSent = messagesSentInWindow.getAndSet(0);
            lastResetTime.set(currentTime);
            
            if (messagesSent > 0) {
                logger.debug("MessageRateLimiter", 
                    "上一秒发送了 " + messagesSent + " 条消息，许可证已重置");
            }
        }
    }
    
    /**
     * 获取当前可用的许可证数量
     */
    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }
    
    /**
     * 获取当前时间窗口内已发送的消息数量
     */
    public long getMessagesSentInCurrentWindow() {
        return messagesSentInWindow.get();
    }
    
    /**
     * 获取速率限制统计信息
     */
    public String getStatistics() {
        return String.format("可用许可: %d/%d, 当前窗口已发送: %d", 
            getAvailablePermits(), MAX_MESSAGES_PER_SECOND, getMessagesSentInCurrentWindow());
    }
    
    /**
     * 关闭速率限制器
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
        logger.info("MessageRateLimiter", "消息速率限制器已关闭");
    }
}