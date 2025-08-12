package net.luffy.util.sender;

import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import net.luffy.model.Pocket48SenderMessage;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.luffy.util.CpuLoadBalancer;
import net.luffy.util.EventBusManager;
import net.luffy.util.MonitorConfig;

import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.Message;

import java.util.concurrent.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.IOException;

/**
 * 口袋48异步消息处理器
 * 用于并行处理媒体资源，减少消息发送延迟
 */
public class Pocket48AsyncMessageProcessor {
    
    private final ExecutorService messageProcessorPool; // 专门处理文本消息
    private final Pocket48Sender sender;
    private final AdaptiveThreadPoolManager adaptivePoolManager;
    private final CpuLoadBalancer loadBalancer;
    private final MonitorConfig config;

    private final ScheduledExecutorService delayExecutor;
    
    // 文本消息处理线程池：专门处理文本消息等轻量级任务
    private static final int CPU_CORES;
    private static final int MESSAGE_THREAD_POOL_SIZE;
    
    static {
        int cores = Runtime.getRuntime().availableProcessors();
        CPU_CORES = Math.max(1, cores); // 确保至少为1
        // 文本消息处理线程池：大幅增加线程数以提高并发处理能力
        MESSAGE_THREAD_POOL_SIZE = Math.max(4, Math.min(CPU_CORES * 2, 16)); // 最多16个线程，最少4个
        
        // 静态初始化完成，不输出调试信息
    }
    
    public Pocket48AsyncMessageProcessor(Pocket48Sender sender) {
        this.sender = sender;
        this.adaptivePoolManager = AdaptiveThreadPoolManager.getInstance();
        this.loadBalancer = CpuLoadBalancer.getInstance();
        this.config = MonitorConfig.getInstance();

        
        // 验证线程池参数
        if (MESSAGE_THREAD_POOL_SIZE <= 0) {
            throw new IllegalStateException("MESSAGE_THREAD_POOL_SIZE must be positive, got: " + MESSAGE_THREAD_POOL_SIZE);
        }
            
        // 创建文本消息处理线程池（优化：降低线程优先级）
        this.messageProcessorPool = Executors.newFixedThreadPool(MESSAGE_THREAD_POOL_SIZE,
            r -> {
                Thread t = new Thread(r, "Pocket48-Text-Processor");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1); // 降低优先级
                return t;
            });
        
        // 创建延迟执行器
        this.delayExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Pocket48-AsyncProcessor-Delay");
            t.setDaemon(true);
            return t;
        });
        
        // 注册事件处理器
        registerEventHandlers();
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
        
        // 文本消息使用快速处理
        if (!isMediaMessage(message)) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    Pocket48SenderMessage result = sender.pharseMessageFast(message, group, false);
                    long processingTime = System.currentTimeMillis() - startTime;
                    
                    // 发布消息处理事件
                    EventBusManager.getInstance().publishAsync(
                        new EventBusManager.MessageProcessEvent(
                            String.valueOf(message.getTime()), message.getType().toString(), processingTime));
                    
                    return result;
                } catch (IOException e) {
                    // 静默处理错误
                    return createPlaceholderMessage(message);
                }
            }, messageProcessorPool);
        }
        
        // 媒体消息使用异步处理
        return processMediaMessageAsync(message, group);
    }
    
    /**
     * 异步处理媒体消息
     * @param message 媒体消息
     * @param group 目标群组
     * @return 处理结果的Future
     */
    private CompletableFuture<Pocket48SenderMessage> processMediaMessageAsync(
            Pocket48Message message, Group group) {
        
        // 根据CPU负载选择处理策略
        CpuLoadBalancer.LoadLevel loadLevel = loadBalancer.getCurrentLoadLevel();
        
        // 高负载时优先使用自适应线程池
        if (loadLevel == CpuLoadBalancer.LoadLevel.HIGH || loadLevel == CpuLoadBalancer.LoadLevel.CRITICAL) {
            return adaptivePoolManager.submitTask(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    // 媒体消息使用完整的处理方法
                    Pocket48SenderMessage result = sender.pharseMessage(message, group, false);
                    long processingTime = System.currentTimeMillis() - startTime;
                    
                    // 发布消息处理事件
                    EventBusManager.getInstance().publishAsync(
                        new EventBusManager.MessageProcessEvent(
                            String.valueOf(message.getTime()), message.getType().toString(), processingTime));
                    
                    return result;
                } catch (IOException e) {
                    // 媒体消息处理失败时，返回占位符消息
                    return createPlaceholderMessage(message);
                }
            });
        } else {
            // 正常负载时使用自适应线程池处理媒体消息
            return adaptivePoolManager.submitTask(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    Pocket48SenderMessage result = sender.pharseMessage(message, group, false);
                    long processingTime = System.currentTimeMillis() - startTime;
                    
                    // 发布消息处理事件
                    EventBusManager.getInstance().publishAsync(
                        new EventBusManager.MessageProcessEvent(
                            String.valueOf(message.getTime()), message.getType().toString(), processingTime));
                    
                    return result;
                } catch (IOException e) {
                    // 媒体消息处理失败时，返回占位符消息
                    return createPlaceholderMessage(message);
                }
            });
        }
    }
    
    /**
     * 创建占位符消息（当消息处理失败时使用）
     * @param message 原始消息
     * @return 占位符消息
     */
    private Pocket48SenderMessage createPlaceholderMessage(Pocket48Message message) {
        try {
            return sender.pharseMessageFast(message, null, false);
        } catch (IOException e) {
            // 如果连占位符都创建失败，返回null
            return null;
        }
    }
    
    /**
     * 批量等待处理结果并按顺序发送（优化版）
     * @param futures 处理结果的Future列表
     * @param group 目标群组
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitAndSendMessages(List<CompletableFuture<Pocket48SenderMessage>> futures, 
                                   Group group, int timeoutSeconds) {
        waitAndSendMessagesWithSmartTimeout(futures, group, timeoutSeconds, timeoutSeconds);
    }
    
    /**
     * 智能超时处理：真正异步处理，不阻塞等待
     * @param futures 处理结果的Future列表
     * @param group 目标群组
     * @param textTimeoutSeconds 文本消息超时时间（秒）
     * @param mediaTimeoutSeconds 媒体消息超时时间（秒）
     */
    public void waitAndSendMessagesWithSmartTimeout(List<CompletableFuture<Pocket48SenderMessage>> futures, 
                                                   Group group, int textTimeoutSeconds, int mediaTimeoutSeconds) {
        
        // 真正异步处理：为每个Future设置异步回调
        for (CompletableFuture<Pocket48SenderMessage> future : futures) {
            // 设置异步回调，避免阻塞等待
            future.whenComplete((result, throwable) -> {
                if (throwable == null && result != null) {
                    try {
                        // 异步发送消息
                        sendMessage(result, group);
                    } catch (Exception e) {
                        // 静默处理发送错误
                    }
                }
            });
            
            // 设置超时处理 - 使用配置的协程超时时间
            long timeoutMs = config.getCoroutineTimeout();
            delayExecutor.schedule(() -> {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * 批量发送消息（已弃用，保留用于兼容性）
     * 现在推荐使用流式处理，逐条发送
     * @param messages 待发送的消息列表
     * @param group 目标群组
     * @deprecated 使用流式处理替代批量发送
     */
    @Deprecated
    private void sendMessagesInBatch(List<Pocket48SenderMessage> messages, Group group) {
        if (messages.isEmpty()) return;
        
        // 改为逐条发送，避免批量等待
        for (Pocket48SenderMessage message : messages) {
            try {
                sendMessage(message, group);
                // 移除延迟配置
                // 无延迟发送
            } catch (Exception e) {
                // 静默处理发送错误
            }
        }
    }
    
    /**
     * 发送消息组（异步处理）- 带重试机制
     * @param messageGroup 消息组
     * @param group 目标群组
     */
    private void sendMessageGroup(List<Pocket48SenderMessage> messageGroup, Group group) {
        try {
            for (int i = 0; i < messageGroup.size(); i++) {
                Pocket48SenderMessage senderMessage = messageGroup.get(i);
                Message[] unjointMessages = senderMessage.getUnjointMessage();
                
                for (Message message : unjointMessages) {
                    sendMessageWithRetry(message, group, 3);
                }
                
                // 消息组间延迟
                if (i < messageGroup.size() - 1) {
                    Thread.sleep(500);
                }
            }
        } catch (Exception e) {
            // 静默处理异常
        }
    }
    
    /**
     * 发送消息组 - 超级优化版本：高优先级消息0延迟（已弃用，保留用于兼容性）
     * @param messages 消息组
     * @param group 目标群组
     * @param baseDelay 基础延迟时间
     * @deprecated 使用流式处理替代分组发送
     */
    @Deprecated
    private void sendMessageGroupDeprecated(List<Pocket48SenderMessage> messages, Group group, int baseDelay) {
        for (int i = 0; i < messages.size(); i++) {
            try {
                sendMessage(messages.get(i), group);
                // 高优先级消息（文本消息）间无延迟，低优先级消息使用最小延迟
                if (i < messages.size() - 1) {
                    // 移除延迟配置，所有消息无延迟发送
                    // 无延迟发送
                }
            } catch (Exception e) {
                // 静默处理发送错误
            }
        }
    }
    
    /**
     * 判断是否为高优先级消息（文本类消息）
     * @param message 消息对象
     * @return 是否为高优先级
     */
    private boolean isHighPriorityMessage(Pocket48SenderMessage message) {
        if (message == null || message.getUnjointMessage() == null) {
            return false;
        }
        
        // 检查消息内容，文本消息优先级更高
        for (Message msg : message.getUnjointMessage()) {
            if (isMediaMessage(msg)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * 降级方案：异步发送消息
     * @param futures 处理结果的Future列表
     * @param group 目标群组
     * @param timeoutSeconds 超时时间
     */
    private void sendMessagesIndividually(List<CompletableFuture<Pocket48SenderMessage>> futures, 
                                         Group group, int timeoutSeconds) {
        for (CompletableFuture<Pocket48SenderMessage> future : futures) {
            // 异步处理，避免阻塞
            future.whenComplete((result, throwable) -> {
                if (throwable == null && result != null) {
                    try {
                        sendMessage(result, group);
                    } catch (Exception e) {
                        // 记录发送错误而不是静默处理
                        System.err.println("[错误] 消息发送失败: " + e.getMessage());
                    }
                } else if (throwable != null) {
                    // 记录处理异常
                    System.err.println("[错误] 消息处理异常: " + throwable.getMessage());
                }
            });
            
            // 改进的超时处理：记录超时但不强制取消 - 使用配置的协程超时时间
            long timeoutMs = config.getCoroutineDefaultTimeout();
            delayExecutor.schedule(() -> {
                if (!future.isDone()) {
                    System.err.println("[警告] 消息处理超时，但将继续等待完成");
                    // 不再强制取消，让消息有机会完成处理
                    // future.cancel(true); // 移除强制取消
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
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
     * 发送处理后的消息 - 超级优化版本：文本消息部分间0延迟，带重试机制
     * @param senderMessage 处理后的消息
     * @param group 目标群组
     */
    private void sendMessage(Pocket48SenderMessage senderMessage, Group group) {
        try {
            Message[] unjointMessages = senderMessage.getUnjointMessage();
            for (int i = 0; i < unjointMessages.length; i++) {
                sendMessageWithRetry(unjointMessages[i], group, 3);
                // 文本消息部分间0延迟，媒体消息使用最小延迟
                if (i < unjointMessages.length - 1) {
                    // 移除延迟配置，所有消息无延迟发送
                    // 无延迟发送
                }
            }
        } catch (Exception e) {
            // 静默处理发送异常
        }
    }
    
    /**
     * 带重试机制的消息发送方法（异步版本）
     * @param message 要发送的消息
     * @param group 目标群组
     * @param maxRetries 最大重试次数
     */
    private void sendMessageWithRetry(Message message, Group group, int maxRetries) {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                group.sendMessage(message);
                // 移除重试成功的信息日志，减少日志噪音
                return; // 发送成功，直接返回
            } catch (Exception e) {
                lastException = e;
                String errorMsg = e.getMessage();
                boolean isRetryableError = errorMsg != null && (
                    errorMsg.contains("rich media transfer failed") ||
                    errorMsg.contains("send_group_msg") ||
                    errorMsg.contains("ActionFailedException") ||
                    errorMsg.contains("EventChecker Failed")
                );
                
                if (attempt == maxRetries || !isRetryableError) {
                    // 记录最终失败信息而不是静默处理
                    System.err.println(String.format("[错误] 消息发送最终失败，尝试次数: %d/%d, 错误: %s", 
                        attempt, maxRetries, e.getMessage()));
                    return;
                }
                
                // 记录重试信息
                System.out.println(String.format("[警告] 消息发送失败，准备重试 %d/%d, 错误: %s", 
                    attempt, maxRetries, e.getMessage()));
                
                // 指数退避重试
                try {
                    long delay = 1000 * (1L << (attempt - 1)); // 1s, 2s, 4s...
                    Thread.sleep(Math.min(delay, 8000)); // 最大延迟8秒
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.err.println("[错误] 消息重试被中断");
                    return;
                }
            }
        }
    }
    
    /**
     * 判断是否为媒体消息
     * @param message 消息对象
     * @return 是否为媒体消息
     */
    private boolean isMediaMessage(Message message) {
        return message.toString().contains("Audio") || 
               message.toString().contains("Image") || 
               message.toString().contains("Video");
    }
    
    /**
     * 注册事件处理器
     */
    private void registerEventHandlers() {
        EventBusManager eventBus = EventBusManager.getInstance();
        
        // 注册消息处理事件处理器
        eventBus.registerHandler(EventBusManager.MessageProcessEvent.class, event -> {
            // 记录消息处理性能
            if (event.getProcessingTime() > 1000) {
                // 处理时间超过1秒的消息需要关注
                // 这里可以添加具体的处理逻辑
            }
        });
        
        // 注册性能事件处理器
        eventBus.registerHandler(EventBusManager.PerformanceEvent.class, event -> {
            // 根据性能事件调整处理策略
            if (event.getCpuUsage() > 0.8) {
                // 高CPU使用率时的处理逻辑
            }
        });
        
        // 注册资源清理事件处理器
        eventBus.registerHandler(EventBusManager.ResourceCleanupEvent.class, event -> {
            // 资源清理完成后的处理逻辑
        });
    }
    
    /**
     * 获取处理器状态信息
     */
    public String getProcessorStatus() {
        return String.format(
            "异步文本消息处理器状态:\n" +
            "文本消息处理线程池 - 活跃: %d, 队列: %d\n" +
            "当前负载级别: %s\n" +
            "CPU使用率: %.1f%%",
            ((ThreadPoolExecutor) messageProcessorPool).getActiveCount(),
            ((ThreadPoolExecutor) messageProcessorPool).getQueue().size(),
            loadBalancer.getCurrentLoadLevel(),
            loadBalancer.getCurrentCpuUsage() * 100
        );
    }
    
    /**
     * 异步延迟方法，替代Thread.sleep避免阻塞
     * @param delayMs 延迟时间（毫秒）
     */
    private void delayAsync(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        
        try {
            // 使用同步延迟，因为这里需要等待
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭线程池
     */
    public void shutdown() {
        messageProcessorPool.shutdown();
        
        try {
            if (!messageProcessorPool.awaitTermination(5, TimeUnit.SECONDS)) {
                messageProcessorPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            messageProcessorPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        if (delayExecutor != null) {
            delayExecutor.shutdown();
            try {
                if (!delayExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    delayExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                delayExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}