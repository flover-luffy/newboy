package net.luffy.util.sender;

import net.luffy.Newboy;
import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48SenderMessage;
import net.luffy.util.Properties;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.Message;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;

/**
 * 口袋48媒体消息异步处理队列
 * 专门处理语音和视频消息，确保文本消息优先发送
 */
public class Pocket48MediaQueue {
    
    private static volatile Pocket48MediaQueue instance;
    private final Pocket48ResourceManager resourceManager;
    
    // 媒体消息队列
    private final BlockingQueue<Pocket48SenderMessage> mediaQueue;
    
    // 媒体处理线程池
    private final ThreadPoolExecutor mediaThreadPool;
    
    // 队列状态监控
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    
    // 运行状态
    private volatile boolean running = true;
    
    public Pocket48MediaQueue() {
        this.resourceManager = Pocket48ResourceManager.getInstance();
        
        // 初始化媒体消息队列
        this.mediaQueue = new LinkedBlockingQueue<>(resourceManager.getMediaQueueSize());
        
        // 初始化媒体处理线程池
        this.mediaThreadPool = new ThreadPoolExecutor(
            1, // 核心线程数
            resourceManager.getMediaThreadPoolSize(), // 最大线程数
            60L, TimeUnit.SECONDS, // 线程空闲时间
            new LinkedBlockingQueue<>(), // 工作队列
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Pocket48-Media-Worker-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );
        
        // 启动媒体处理工作线程
        startMediaProcessingWorkers();
        
        // 口袋48媒体异步处理队列已启动
    }
    
    private Pocket48MediaQueue(Pocket48ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        
        // 初始化媒体消息队列
        this.mediaQueue = new LinkedBlockingQueue<>(resourceManager.getMediaQueueSize());
        
        // 初始化媒体处理线程池
        this.mediaThreadPool = new ThreadPoolExecutor(
            1, // 核心线程数
            resourceManager.getMediaThreadPoolSize(), // 最大线程数
            60L, TimeUnit.SECONDS, // 线程空闲时间
            new LinkedBlockingQueue<>(), // 工作队列
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "Pocket48-Media-Worker-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
        );
        
        // 启动媒体处理工作线程
        startMediaProcessingWorkers();
        
        // 口袋48媒体异步处理队列已启动
    }
    
    public static Pocket48MediaQueue getInstance(Properties properties) {
        if (instance == null) {
            synchronized (Pocket48MediaQueue.class) {
                if (instance == null) {
                    instance = new Pocket48MediaQueue();
                }
            }
        }
        return instance;
    }
    
    /**
     * 提交媒体消息进行异步处理并立即发送
     * @param message 原始Pocket48消息
     * @param group 目标群组
     * @param sender Pocket48Sender实例
     */
    public void submitMediaMessage(Pocket48Message message, Group group, Pocket48Sender sender) {
        if (!resourceManager.isMediaQueueEnabled() || !running) {
            // 如果异步队列未启用，直接同步处理并立即发送
            try {
                Pocket48SenderMessage senderMessage = sender.pharseMessage(message, group, false);
                if (senderMessage != null) {
                    sender.sendSingleMessage(senderMessage, group);
                }
            } catch (Exception e) {
                Newboy.INSTANCE.getLogger().error("同步处理媒体消息失败: " + e.getMessage());
            }
            return;
        }
        
        // 异步处理并立即发送
        mediaThreadPool.submit(() -> {
            try {
                Pocket48SenderMessage senderMessage = sender.pharseMessage(message, group, false);
                if (senderMessage != null && isMediaMessage(senderMessage)) {
                    // 立即发送媒体消息，不再放入队列等待
                    sender.sendSingleMessage(senderMessage, group);
                    processedCount.incrementAndGet();
                }
            } catch (Exception e) {
                Newboy.INSTANCE.getLogger().error("异步处理媒体消息失败: " + e.getMessage());
                failedCount.incrementAndGet();
            }
        });
    }
    
    /**
     * 添加媒体消息到队列（已废弃，现在直接使用submitMediaMessage进行异步处理）
     * @param message 媒体消息
     * @return 是否成功添加
     * @deprecated 媒体消息现在直接通过submitMediaMessage异步处理，不再使用队列缓存
     */
    @Deprecated
    public boolean addMediaMessage(Pocket48SenderMessage message) {
        // 此方法已废弃，媒体消息现在直接通过submitMediaMessage异步处理
        // 为了兼容性，仍返回true表示"成功"
        return isMediaMessage(message);
    }
    
    /**
     * 判断是否为媒体消息
     */
    private boolean isMediaMessage(Pocket48SenderMessage message) {
        if (message == null || message.getMessage() == null) {
            return false;
        }
        
        // 检查消息数组中是否包含语音或视频
        for (net.mamoe.mirai.message.data.Message msg : message.getMessage()) {
            if (msg != null) {
                String msgStr = msg.toString();
                if (msgStr.contains("Voice") || msgStr.contains("Video") || msgStr.contains("Audio")) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 启动媒体处理工作线程
     */
    private void startMediaProcessingWorkers() {
        for (int i = 0; i < resourceManager.getMediaThreadPoolSize(); i++) {
            mediaThreadPool.submit(this::mediaProcessingWorker);
        }
    }
    
    /**
     * 媒体处理工作线程（已废弃，现在直接在submitMediaMessage中处理并发送）
     * @deprecated 媒体消息现在直接在submitMediaMessage中处理并立即发送，不再使用队列缓存
     */
    @Deprecated
    private void mediaProcessingWorker() {
        // 此方法已废弃，媒体消息现在直接在submitMediaMessage中处理并立即发送
        // 保留此方法仅为兼容性，实际不再使用队列处理逻辑
    }
    

    
    /**
     * 获取队列状态信息
     */
    public String getQueueStatus() {
        return String.format("媒体队列状态 - 队列大小: %d, 已处理: %d, 失败: %d, 线程池活跃: %d",
            queueSize.get(), processedCount.get(), failedCount.get(), mediaThreadPool.getActiveCount());
    }
    
    /**
     * 获取当前队列大小
     */
    public int getCurrentQueueSize() {
        return queueSize.get();
    }
    
    /**
     * 检查队列是否启用
     */
    public boolean isEnabled() {
        return resourceManager.isMediaQueueEnabled() && running;
    }
    
    /**
     * 关闭媒体处理队列
     */
    public void shutdown() {
        running = false;
        
        // 正在关闭口袋48媒体异步处理队列
        
        // 关闭线程池
        mediaThreadPool.shutdown();
        try {
            if (!mediaThreadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                mediaThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            mediaThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 清空队列
        mediaQueue.clear();
        
        // 口袋48媒体异步处理队列已关闭
    }
}