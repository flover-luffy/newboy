package net.luffy.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;
import java.util.function.Consumer;
import net.luffy.Newboy;
import net.luffy.util.AdaptiveThreadPoolManager;

/**
 * 事件驱动模型管理器
 * 实现高效的事件总线机制，减少线程间通信开销
 */
public class EventBusManager {
    // 配置参数 - 必须在INSTANCE之前初始化
    private static final int PROCESSOR_THREADS;
    private volatile boolean running = true;
    
    static {
        int processors = Runtime.getRuntime().availableProcessors();
        PROCESSOR_THREADS = Math.max(2, Math.min(processors + 1, 6)); // 从最多3个增加到6个，最少从1个增加到2个
        // EventBusManager初始化完成
    }
    
    private static final EventBusManager INSTANCE = new EventBusManager();
    
    // 事件处理器注册表
    private final Map<Class<?>, List<EventHandler<?>>> eventHandlers = new ConcurrentHashMap<>();
    
    // 事件队列和处理器
    private final BlockingQueue<Event> eventQueue = new LinkedBlockingQueue<>(2000); // 从1000增加到2000
    private final ExecutorService eventProcessor;
    
    // 性能监控
    private final AtomicLong totalEvents = new AtomicLong(0);
    private final AtomicLong processedEvents = new AtomicLong(0);
    private final AtomicLong failedEvents = new AtomicLong(0);
    private final AtomicInteger queueSize = new AtomicInteger(0);
    
    private EventBusManager() {
        // 验证线程数
        if (PROCESSOR_THREADS <= 0) {
            throw new IllegalStateException("PROCESSOR_THREADS must be positive, got: " + PROCESSOR_THREADS);
        }
        
        // 使用统一的线程池管理器
        this.eventProcessor = AdaptiveThreadPoolManager.getInstance().getEventProcessorPool();
        
        // 启动事件处理循环
        startEventProcessing();
    }
    
    public static EventBusManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * 启动事件处理循环
     */
    private void startEventProcessing() {
        for (int i = 0; i < PROCESSOR_THREADS; i++) {
            eventProcessor.submit(() -> {
                while (running) {
                    try {
                        Event event = eventQueue.poll(1, TimeUnit.SECONDS);
                        if (event != null) {
                            queueSize.decrementAndGet();
                            processEvent(event);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        failedEvents.incrementAndGet();
                        // 避免在静态初始化时访问Newboy.INSTANCE
                        System.err.println("[事件总线] 处理事件时发生错误: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        }
    }
    
    /**
     * 注册事件处理器
     */
    @SuppressWarnings("unchecked")
    public <T> void registerHandler(Class<T> eventType, Consumer<T> handler) {
        eventHandlers.computeIfAbsent(eventType, k -> new ArrayList<>())
                    .add(new EventHandler<>((Consumer<Object>) handler));
    }
    
    /**
     * 发布事件（异步）
     */
    public <T> boolean publishAsync(T event) {
        if (event == null) return false;
        
        totalEvents.incrementAndGet();
        
        try {
            boolean offered = eventQueue.offer(new Event(event.getClass(), event), 100, TimeUnit.MILLISECONDS);
            if (offered) {
                queueSize.incrementAndGet();
            } else {
                failedEvents.incrementAndGet();
                System.err.println("[事件总线] 事件队列已满，丢弃事件: " + event.getClass().getSimpleName());
            }
            return offered;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failedEvents.incrementAndGet();
            return false;
        }
    }
    
    /**
     * 发布事件（同步）
     */
    public <T> void publishSync(T event) {
        if (event == null) return;
        
        totalEvents.incrementAndGet();
        processEvent(new Event(event.getClass(), event));
    }
    
    /**
     * 处理单个事件
     */
    @SuppressWarnings("unchecked")
    private void processEvent(Event event) {
        try {
            List<EventHandler<?>> handlers = eventHandlers.get(event.getType());
            if (handlers != null && !handlers.isEmpty()) {
                for (EventHandler<?> handler : handlers) {
                    try {
                        ((EventHandler<Object>) handler).handle(event.getData());
                    } catch (Exception e) {
                        System.err.println("[事件总线] 处理器执行失败: " + event.getType().getSimpleName() + ", 错误: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
            processedEvents.incrementAndGet();
        } catch (Exception e) {
            failedEvents.incrementAndGet();
            System.err.println("[事件总线] 事件处理失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 获取事件总线状态
     */
    public String getEventBusStatus() {
        long total = totalEvents.get();
        long processed = processedEvents.get();
        long failed = failedEvents.get();
        double successRate = total > 0 ? (double) processed / total * 100 : 0;
        
        return String.format(
            "事件总线状态:\n" +
            "总事件数: %d\n" +
            "已处理: %d\n" +
            "处理失败: %d\n" +
            "成功率: %.2f%%\n" +
            "队列大小: %d\n" +
            "注册的事件类型: %d\n" +
            "处理线程数: %d",
            total, processed, failed, successRate,
            queueSize.get(), eventHandlers.size(), PROCESSOR_THREADS
        );
    }
    
    /**
     * 清理统计数据
     */
    public void resetStats() {
        totalEvents.set(0);
        processedEvents.set(0);
        failedEvents.set(0);
        queueSize.set(eventQueue.size());
    }
    
    /**
     * 关闭事件总线
     */
    public void shutdown() {
        running = false;
        // eventProcessor现在由AdaptiveThreadPoolManager管理，不需要显式关闭
        
        // 清理剩余事件
        eventQueue.clear();
        eventHandlers.clear();
        
        System.out.println("EventBusManager已关闭，线程池由AdaptiveThreadPoolManager统一管理");
    }
    
    /**
     * 事件包装类
     */
    private static class Event {
        private final Class<?> type;
        private final Object data;
        private final long timestamp;
        
        public Event(Class<?> type, Object data) {
            this.type = type;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }
        
        public Class<?> getType() { return type; }
        public Object getData() { return data; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * 事件处理器包装类
     */
    private static class EventHandler<T> {
        private final Consumer<T> handler;
        
        public EventHandler(Consumer<T> handler) {
            this.handler = handler;
        }
        
        public void handle(T event) {
            handler.accept(event);
        }
    }
    
    // 预定义事件类型
    
    /**
     * 消息处理事件
     */
    public static class MessageProcessEvent {
        private final String messageId;
        private final String messageType;
        private final long processingTime;
        
        public MessageProcessEvent(String messageId, String messageType, long processingTime) {
            this.messageId = messageId;
            this.messageType = messageType;
            this.processingTime = processingTime;
        }
        
        public String getMessageId() { return messageId; }
        public String getMessageType() { return messageType; }
        public long getProcessingTime() { return processingTime; }
    }
    
    /**
     * 性能监控事件
     */
    public static class PerformanceEvent {
        private final String component;
        private final double cpuUsage;
        private final long memoryUsage;
        private final int threadCount;
        
        public PerformanceEvent(String component, double cpuUsage, long memoryUsage, int threadCount) {
            this.component = component;
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
            this.threadCount = threadCount;
        }
        
        public String getComponent() { return component; }
        public double getCpuUsage() { return cpuUsage; }
        public long getMemoryUsage() { return memoryUsage; }
        public int getThreadCount() { return threadCount; }
    }
    
    /**
     * 资源清理事件
     */
    public static class ResourceCleanupEvent {
        private final String resourceType;
        private final int cleanedCount;
        private final long freedMemory;
        
        public ResourceCleanupEvent(String resourceType, int cleanedCount, long freedMemory) {
            this.resourceType = resourceType;
            this.cleanedCount = cleanedCount;
            this.freedMemory = freedMemory;
        }
        
        public String getResourceType() { return resourceType; }
        public int getCleanedCount() { return cleanedCount; }
        public long getFreedMemory() { return freedMemory; }
    }
}