package net.luffy.util.sender;

import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import net.luffy.model.Pocket48SenderMessage;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.luffy.util.CpuLoadBalancer;
import net.luffy.util.EventBusManager;
import net.luffy.util.MonitorConfig;

import net.luffy.Newboy;

import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.Message;

import java.util.concurrent.*;
import java.util.concurrent.Executors;
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
    private final ExecutorService highPriorityPool; // 高优先级消息专用通道
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
        // 文本消息处理线程池：进一步增加线程数以提高并发处理能力
        MESSAGE_THREAD_POOL_SIZE = Math.max(8, Math.min(CPU_CORES * 4, 32)); // 最多32个线程，最少8个
        
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
            
        // 使用统一线程池管理器替代自建线程池
        this.messageProcessorPool = adaptivePoolManager.getExecutorService();
        
        // 高优先级消息也使用统一线程池管理器，通过任务优先级区分
        this.highPriorityPool = adaptivePoolManager.getExecutorService();
        
        // 使用统一调度管理器替代自建延迟执行器
        this.delayExecutor = net.luffy.util.UnifiedSchedulerManager.getInstance().getScheduledExecutor();
        
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
            // 检查是否启用高优先级通道
            boolean useHighPriorityChannel = Newboy.INSTANCE.getProperties().high_priority_channel_enabled;
            ExecutorService targetPool = (useHighPriorityChannel && isTextMessage(message)) ? 
                highPriorityPool : messageProcessorPool;
            
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
                    // 只在UI期望时创建占位消息
                    if (Newboy.INSTANCE.getProperties().enable_placeholder_messages) {
                        return createPlaceholderMessage(message);
                    }
                    throw new RuntimeException("消息处理失败: " + e.getMessage(), e);
                }
            }, targetPool);
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
                    // 媒体消息处理失败时，只在UI期望时返回占位符消息
                    if (Newboy.INSTANCE.getProperties().enable_placeholder_messages) {
                        return createPlaceholderMessage(message);
                    }
                    throw new RuntimeException("媒体消息处理失败: " + e.getMessage(), e);
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
                    // 媒体消息处理失败时，只在UI期望时返回占位符消息
                    if (Newboy.INSTANCE.getProperties().enable_placeholder_messages) {
                        return createPlaceholderMessage(message);
                    }
                    throw new RuntimeException("媒体消息处理失败: " + e.getMessage(), e);
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
     * 判断是否为文本消息
     * @param message 消息对象
     * @return 是否为文本消息
     */
    private boolean isTextMessage(Pocket48Message message) {
        if (message == null || message.getType() == null) {
            return false;
        }
        
        switch (message.getType()) {
            case TEXT:
            case REPLY:
                return true;
            default:
                return false;
        }
    }
    
    /**
     * 批量等待处理结果并按顺序发送（优化版）
     * @param futures 处理结果的Future列表
     * @param group 目标群组
     * @param timeoutSeconds 超时时间（秒）
     */
    /**
     * 等待并发送消息 - 简化版本：移除延迟等待
     * @param futures 处理结果的Future列表
     * @param group 目标群组
     * @param timeoutSeconds 超时时间（秒）
     */
    public void waitAndSendMessages(List<CompletableFuture<Pocket48SenderMessage>> futures, 
                                   Group group, int timeoutSeconds) {
        // 直接处理，不等待延迟
        waitAndSendMessagesWithSmartTimeout(futures, group, timeoutSeconds, timeoutSeconds);
    }
    
    /**
     * 智能超时处理：真正异步处理，不阻塞等待 - 简化版本：移除延迟调度
     * @param futures 处理结果的Future列表
     * @param group 目标群组
     * @param textTimeoutSeconds 文本消息超时时间（秒）
     * @param mediaTimeoutSeconds 媒体消息超时时间（秒）
     */
    public void waitAndSendMessagesWithSmartTimeout(List<CompletableFuture<Pocket48SenderMessage>> futures, 
                                                   Group group, int textTimeoutSeconds, int mediaTimeoutSeconds) {
        
        // 真正异步处理：为每个Future设置异步回调，移除延迟调度
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
            
            // 移除超时处理的延迟调度，直接取消超时的Future
            long timeoutMs = config.getCoroutineTimeout();
            if (!future.isDone() && timeoutMs > 0) {
                future.cancel(true);
            }
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
                // 移除延迟配置，立即发送
        // 立即发送，无延迟
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
                
                // 消息组间延迟 - 移除延迟，立即执行
                if (i < messageGroup.size() - 1) {
                    // 立即执行下一个消息组
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
                // 高优先级消息（文本消息）立即发送，无延迟
                if (i < messages.size() - 1) {
                    // 立即发送，无延迟
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
            
            // 移除超时处理的延迟调度，直接取消超时的Future
            long timeoutMs = config.getCoroutineDefaultTimeout();
            if (!future.isDone() && timeoutMs > 0) {
                System.err.println("[警告] 消息处理超时，但将继续等待完成");
                // 不再使用延迟调度器，直接处理超时
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
     * 发送处理后的消息 - 异步版本：文本消息部分间0延迟，带重试机制
     * @param senderMessage 处理后的消息
     * @param group 目标群组
     * @return 发送完成的CompletableFuture
     */
    private CompletableFuture<Void> sendMessageAsync(Pocket48SenderMessage senderMessage, Group group) {
        try {
            Message[] unjointMessages = senderMessage.getUnjointMessage();
            if (unjointMessages.length == 0) {
                return CompletableFuture.completedFuture(null);
            }
            
            // 创建异步发送任务链
            CompletableFuture<Void> chain = sendMessageWithRetryAsync(unjointMessages[0], group, 3);
            
            for (int i = 1; i < unjointMessages.length; i++) {
                final int index = i;
                chain = chain.thenCompose(v -> sendMessageWithRetryAsync(unjointMessages[index], group, 3));
            }
            
            return chain;
        } catch (Exception e) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }
    
    /**
     * 发送处理后的消息 - 同步版本（移除超时等待）
     * @param senderMessage 处理后的消息
     * @param group 目标群组
     */
    private void sendMessage(Pocket48SenderMessage senderMessage, Group group) {
        try {
            // 完全异步发送，不阻塞当前线程
            sendMessageAsync(senderMessage, group);
        } catch (Exception e) {
            // 静默处理发送异常
        }
    }
    
    /**
     * 带重试机制的消息发送方法（异步版本）
     * @param message 要发送的消息
     * @param group 目标群组
     * @param maxRetries 最大重试次数
     * @return 发送结果的CompletableFuture
     */
    private CompletableFuture<Void> sendMessageWithRetryAsync(Message message, Group group, int maxRetries) {
        return sendMessageWithRetryAsync(message, group, maxRetries, 1);
    }
    
    /**
     * 带重试机制的消息发送方法（异步版本）- 内部递归实现
     * @param message 要发送的消息
     * @param group 目标群组
     * @param maxRetries 最大重试次数
     * @param attempt 当前尝试次数
     * @return 发送结果的CompletableFuture
     */
    private CompletableFuture<Void> sendMessageWithRetryAsync(Message message, Group group, int maxRetries, int attempt) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        try {
            group.sendMessage(message);
            future.complete(null); // 发送成功
            return future;
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            boolean isRetryableError = errorMsg != null && (
                errorMsg.contains("rich media transfer failed") ||
                errorMsg.contains("send_group_msg") ||
                errorMsg.contains("ActionFailedException") ||
                errorMsg.contains("EventChecker Failed")
            );
            
            if (attempt >= maxRetries || !isRetryableError) {
                // 记录最终失败信息
                System.err.println(String.format("[错误] 消息发送最终失败，尝试次数: %d/%d, 错误: %s", 
                    attempt, maxRetries, e.getMessage()));
                future.completeExceptionally(e);
                return future;
            }
            
            // 记录重试信息
            System.out.println(String.format("[警告] 消息发送失败，准备重试 %d/%d, 错误: %s", 
                attempt, maxRetries, e.getMessage()));
            
            // 移除重试延迟，实现快速重试
                long baseDelay = 0L; // 移除基础延迟
                double backoffMultiplier = 1.0; // 移除指数退避
                long maxDelay = 0L; // 移除最大延迟
                long finalDelay = 0; // 所有重试都使用零延迟
            
            // 直接执行重试，不使用延迟调度器
            sendMessageWithRetryAsync(message, group, maxRetries, attempt + 1)
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        future.completeExceptionally(throwable);
                    } else {
                        future.complete(result);
                    }
                });
        }
        
        return future;
    }
    
    /**
     * 同步版本的消息发送方法（保持向后兼容）
     * @param message 要发送的消息
     * @param group 目标群组
     * @param maxRetries 最大重试次数
     */
    private void sendMessageWithRetry(Message message, Group group, int maxRetries) {
        // 完全异步发送，不阻塞当前线程
        sendMessageWithRetryAsync(message, group, maxRetries);
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
     * @return 延迟完成的CompletableFuture
     */

    
    /**
     * 已弃用的同步延迟方法 - 已移除
     * @deprecated 此方法已被移除，不再使用延迟
     */
    @Deprecated
    private void delaySynchronous(int delayMs) {
        // 移除延迟逻辑，直接返回
    }

    /**
     * 关闭线程池
     */
    public void shutdown() {
        // messageProcessorPool和highPriorityPool现在都使用AdaptiveThreadPoolManager
        // 由AdaptiveThreadPoolManager统一管理关闭，这里不需要单独关闭
        
        // 注意：delayExecutor现在使用统一调度管理器，不需要单独关闭
        // 统一调度管理器将在插件关闭时统一处理
        
        System.out.println("[信息] Pocket48AsyncMessageProcessor已切换到统一线程池管理，无需单独关闭");
    }
}