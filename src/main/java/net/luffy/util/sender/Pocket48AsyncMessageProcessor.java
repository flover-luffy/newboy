package net.luffy.util.sender;

import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import net.luffy.model.Pocket48SenderMessage;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.luffy.util.CpuLoadBalancer;
import net.luffy.util.EventBusManager;
import net.luffy.util.MessageDelayConfig;
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
    private final MessageDelayConfig delayConfig;
    
    // 文本消息处理线程池：专门处理文本消息等轻量级任务
    private static final int CPU_CORES;
    private static final int MESSAGE_THREAD_POOL_SIZE;
    
    static {
        int cores = Runtime.getRuntime().availableProcessors();
        CPU_CORES = Math.max(1, cores); // 确保至少为1
        // 文本消息处理线程池：用于处理文本消息等轻量级任务
        MESSAGE_THREAD_POOL_SIZE = Math.max(1, Math.min(CPU_CORES / 2 + 1, 3)); // 最多3个线程，最少1个
        
        // 静态初始化完成，不输出调试信息
    }
    
    public Pocket48AsyncMessageProcessor(Pocket48Sender sender) {
        this.sender = sender;
        this.adaptivePoolManager = AdaptiveThreadPoolManager.getInstance();
        this.loadBalancer = CpuLoadBalancer.getInstance();
        this.delayConfig = MessageDelayConfig.getInstance();
        
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
     * 异步处理单条消息（专注于文本消息，媒体消息由Pocket48MediaQueue处理）
     * @param message 待处理的消息
     * @param group 目标群组
     * @return 处理结果的Future
     */
    public CompletableFuture<Pocket48SenderMessage> processMessageAsync(
            Pocket48Message message, Group group) {
        
        // 媒体消息交给专门的媒体队列处理，这里只处理文本消息
        if (isMediaMessage(message)) {
            // 返回一个立即完成的Future，表示媒体消息已交给媒体队列处理
            return CompletableFuture.completedFuture(null);
        }
        
        // 根据CPU负载选择处理策略（仅处理文本消息）
        CpuLoadBalancer.LoadLevel loadLevel = loadBalancer.getCurrentLoadLevel();
        
        // 高负载时优先使用自适应线程池
        if (loadLevel == CpuLoadBalancer.LoadLevel.HIGH || loadLevel == CpuLoadBalancer.LoadLevel.CRITICAL) {
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
                    // 静默处理错误
                    return null;
                }
            });
        } else {
            // 正常负载时使用消息处理线程池（专门处理文本消息）
            return CompletableFuture.supplyAsync(() -> {
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
                    // 静默处理错误
                    return null;
                }
            }, messageProcessorPool);
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
     * 智能超时处理：为不同类型消息设置不同超时时间
     * @param futures 处理结果的Future列表
     * @param group 目标群组
     * @param textTimeoutSeconds 文本消息超时时间（秒）
     * @param mediaTimeoutSeconds 媒体消息超时时间（秒）
     */
    public void waitAndSendMessagesWithSmartTimeout(List<CompletableFuture<Pocket48SenderMessage>> futures, 
                                                   Group group, int textTimeoutSeconds, int mediaTimeoutSeconds) {
        
        // 分离文本和媒体消息的Future
        List<CompletableFuture<Pocket48SenderMessage>> textFutures = new ArrayList<>();
        List<CompletableFuture<Pocket48SenderMessage>> mediaFutures = new ArrayList<>();
        
        // 由于无法直接判断Future对应的消息类型，我们采用分阶段处理策略
        // 第一阶段：快速处理文本消息（较短超时）
        List<Pocket48SenderMessage> quickResults = new ArrayList<>();
        List<CompletableFuture<Pocket48SenderMessage>> remainingFutures = new ArrayList<>();
        
        for (CompletableFuture<Pocket48SenderMessage> future : futures) {
            try {
                // 尝试快速获取结果（文本消息通常处理很快）
                Pocket48SenderMessage result = future.get(textTimeoutSeconds, TimeUnit.SECONDS);
                if (result != null) {
                    quickResults.add(result);
                }
            } catch (TimeoutException e) {
                // 超时的任务可能是媒体消息，加入待处理列表
                remainingFutures.add(future);
            } catch (Exception e) {
                // 处理错误，取消任务
                future.cancel(true);
            }
        }
        
        // 第二阶段：处理剩余的媒体消息（较长超时）
        List<Pocket48SenderMessage> mediaResults = new ArrayList<>();
        for (CompletableFuture<Pocket48SenderMessage> future : remainingFutures) {
            try {
                Pocket48SenderMessage result = future.get(mediaTimeoutSeconds, TimeUnit.SECONDS);
                if (result != null) {
                    mediaResults.add(result);
                }
            } catch (TimeoutException e) {
                // 媒体消息也超时，取消任务
                future.cancel(true);
            } catch (Exception e) {
                // 处理错误
                future.cancel(true);
            }
        }
        
        // 合并结果并发送
        List<Pocket48SenderMessage> allResults = new ArrayList<>();
        allResults.addAll(quickResults);
        allResults.addAll(mediaResults);
        
        if (!allResults.isEmpty()) {
            sendMessagesInBatch(allResults, group);
        }
    }
    
    /**
     * 批量发送消息（按优先级分组）
     * @param messages 待发送的消息列表
     * @param group 目标群组
     */
    private void sendMessagesInBatch(List<Pocket48SenderMessage> messages, Group group) {
        if (messages.isEmpty()) return;
        
        // 按消息类型分组：文本消息优先发送
        Map<Boolean, List<Pocket48SenderMessage>> grouped = messages.stream()
            .collect(Collectors.partitioningBy(this::isHighPriorityMessage));
        
        List<Pocket48SenderMessage> highPriority = grouped.get(true);
        List<Pocket48SenderMessage> lowPriority = grouped.get(false);
        
        // 使用配置化的延迟时间
        int highPriorityDelay = loadBalancer.getDynamicDelay(delayConfig.getGroupHighPriorityDelay());
        int lowPriorityDelay = loadBalancer.getDynamicDelay(delayConfig.getGroupLowPriorityDelay());
        
        // 先发送高优先级消息（文本类）
        sendMessageGroup(highPriority, group, highPriorityDelay);
        
        // 再发送低优先级消息（媒体类）
        sendMessageGroup(lowPriority, group, lowPriorityDelay);
    }
    
    /**
     * 发送消息组
     * @param messages 消息组
     * @param group 目标群组
     * @param baseDelay 基础延迟时间
     */
    private void sendMessageGroup(List<Pocket48SenderMessage> messages, Group group, int baseDelay) {
        for (int i = 0; i < messages.size(); i++) {
            try {
                sendMessage(messages.get(i), group);
                // 组内消息间隔
                if (i < messages.size() - 1) {
                    Thread.sleep(baseDelay);
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
     * 降级方案：逐个发送消息
     * @param futures 处理结果的Future列表
     * @param group 目标群组
     * @param timeoutSeconds 超时时间
     */
    private void sendMessagesIndividually(List<CompletableFuture<Pocket48SenderMessage>> futures, 
                                         Group group, int timeoutSeconds) {
        for (CompletableFuture<Pocket48SenderMessage> future : futures) {
            try {
                Pocket48SenderMessage result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                if (result != null) {
                    sendMessage(result, group);
                }
            } catch (TimeoutException e) {
                // 静默处理超时，取消任务
                future.cancel(true);
            } catch (Exception e) {
                // 静默处理发送错误
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
            for (int i = 0; i < unjointMessages.length; i++) {
                group.sendMessage(unjointMessages[i]);
                // 使用配置化的消息间延迟时间
                if (i < unjointMessages.length - 1) {
                    int baseDelay = isMediaMessage(unjointMessages[i]) ? 
                        delayConfig.getMediaDelay() : delayConfig.getTextDelay();
                    int dynamicDelay = loadBalancer.getDynamicDelay(baseDelay);
                    Thread.sleep(dynamicDelay);
                }
            }
        } catch (Exception e) {
            // 静默处理发送异常
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
    }
}