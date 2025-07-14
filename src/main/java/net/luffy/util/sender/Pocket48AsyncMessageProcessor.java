package net.luffy.util.sender;

import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import net.luffy.model.Pocket48SenderMessage;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.Message;

import java.util.concurrent.*;
import java.util.List;
import java.util.ArrayList;
import java.io.IOException;

/**
 * 口袋48异步消息处理器
 * 用于并行处理媒体资源，减少消息发送延迟
 */
public class Pocket48AsyncMessageProcessor {
    
    private final ExecutorService mediaProcessorPool;
    private final ExecutorService messageProcessorPool;
    private final Pocket48Sender sender;
    
    // 媒体处理线程池：用于处理音频、视频、图片等资源密集型任务
    private static final int MEDIA_THREAD_POOL_SIZE = 4;
    // 消息处理线程池：用于处理文本消息等轻量级任务
    private static final int MESSAGE_THREAD_POOL_SIZE = 2;
    
    public Pocket48AsyncMessageProcessor(Pocket48Sender sender) {
        this.sender = sender;
        
        // 创建媒体处理线程池
        this.mediaProcessorPool = Executors.newFixedThreadPool(MEDIA_THREAD_POOL_SIZE, 
            r -> {
                Thread t = new Thread(r, "Pocket48-Media-Processor");
                t.setDaemon(true);
                return t;
            });
            
        // 创建消息处理线程池
        this.messageProcessorPool = Executors.newFixedThreadPool(MESSAGE_THREAD_POOL_SIZE,
            r -> {
                Thread t = new Thread(r, "Pocket48-Message-Processor");
                t.setDaemon(true);
                return t;
            });
    }
    
    /**
     * 异步处理消息列表
     * @param messages 待处理的消息列表
     * @param group 目标群组
     * @return 处理结果的Future列表
     */
    public List<CompletableFuture<Pocket48SenderMessage>> processMessagesAsync(
            Pocket48Message[] messages, Group group) {
        
        List<CompletableFuture<Pocket48SenderMessage>> futures = new ArrayList<>();
        
        for (Pocket48Message message : messages) {
            CompletableFuture<Pocket48SenderMessage> future = processMessageAsync(message, group);
            futures.add(future);
        }
        
        return futures;
    }
    
    /**
     * 异步处理单条消息
     * @param message 待处理的消息
     * @param group 目标群组
     * @return 处理结果的Future
     */
    public CompletableFuture<Pocket48SenderMessage> processMessageAsync(
            Pocket48Message message, Group group) {
        
        // 根据消息类型选择合适的线程池
        ExecutorService executor = isMediaMessage(message) ? mediaProcessorPool : messageProcessorPool;
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sender.pharseMessage(message, group, false);
            } catch (IOException e) {
                System.err.println("[异步处理错误] 处理消息失败: " + e.getMessage());
                return null;
            }
        }, executor);
    }
    
    /**
     * 批量等待处理结果并按顺序发送
     * @param futures 处理结果的Future列表
     * @param group 目标群组
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitAndSendMessages(List<CompletableFuture<Pocket48SenderMessage>> futures, 
                                   Group group, int timeoutSeconds) {
        
        for (CompletableFuture<Pocket48SenderMessage> future : futures) {
            try {
                Pocket48SenderMessage result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                if (result != null) {
                    sendMessage(result, group);
                }
            } catch (TimeoutException e) {
                System.err.println("[超时警告] 消息处理超时，跳过该消息");
                future.cancel(true);
            } catch (Exception e) {
                System.err.println("[发送错误] 消息发送失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 判断是否为媒体消息
     * @param message 消息对象
     * @return 是否为媒体消息
     */
    private boolean isMediaMessage(Pocket48Message message) {
        if (message == null || message.getType() == null) {
            return false;
        }
        
        switch (message.getType()) {
            case AUDIO:
            case VIDEO:
            case IMAGE:
            case EXPRESSIMAGE:
            case FLIPCARD_AUDIO:
            case FLIPCARD_VIDEO:
            case LIVEPUSH:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * 发送处理后的消息
     * @param senderMessage 处理后的消息
     * @param group 目标群组
     */
    private void sendMessage(Pocket48SenderMessage senderMessage, Group group) {
        try {
            Message[] unjointMessages = senderMessage.getUnjointMessage();
            for (Message msg : unjointMessages) {
                group.sendMessage(msg);
                // 添加短暂延迟避免发送过快
                Thread.sleep(100);
            }
        } catch (Exception e) {
            System.err.println("[发送失败] 消息发送异常: " + e.getMessage());
        }
    }
    
    /**
     * 关闭线程池
     */
    public void shutdown() {
        mediaProcessorPool.shutdown();
        messageProcessorPool.shutdown();
        
        try {
            if (!mediaProcessorPool.awaitTermination(5, TimeUnit.SECONDS)) {
                mediaProcessorPool.shutdownNow();
            }
            if (!messageProcessorPool.awaitTermination(5, TimeUnit.SECONDS)) {
                messageProcessorPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            mediaProcessorPool.shutdownNow();
            messageProcessorPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}