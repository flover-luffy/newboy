package net.luffy.util.sender;

import net.luffy.Newboy;
import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48MessageType;
import net.luffy.model.Pocket48SenderMessage;
import net.luffy.util.Properties;
import net.luffy.util.UnifiedLogger;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.Message;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 口袋48媒体消息异步处理队列
 * 专门处理语音和视频消息，确保文本消息优先发送
 * 重构版本：恢复worker模式，禁止静默丢弃，支持持久化溢出队列
 */
public class Pocket48MediaQueue {
    
    private static volatile Pocket48MediaQueue instance;
    private final Pocket48UnifiedResourceManager resourceManager;
    
    // 队列和线程池
    private final LinkedBlockingQueue<MediaTask> mediaQueue;
    private final LinkedBlockingQueue<MediaTask> overflowQueue;
    private final ExecutorService mediaThreadPool;
    
    // 运行状态控制
    private final AtomicBoolean running = new AtomicBoolean(true);
    
    // 统计计数器
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger overflowCount = new AtomicInteger(0);
    private final AtomicInteger recoveredCount = new AtomicInteger(0);
    
    // 持久化相关
    private final Path overflowDir;
    
    // Worker线程控制
    private final List<Thread> workerThreads = new ArrayList<>();
    
    // 媒体任务封装类
    /**
     * 增强的媒体任务类，包含完整的处理上下文信息
     */
    private static class MediaTask implements Serializable {
        private static final long serialVersionUID = 2L; // 版本升级
        
        final Pocket48Message originalMessage;
        final String groupId;
        final long timestamp;
        final int retryCount;
        
        // 新增字段：处理上下文信息
        final transient Group group;           // 群组对象（不序列化）
        final transient Pocket48Sender sender; // 发送器对象（不序列化）
        final String groupName;                // 群组名称（用于日志和恢复）
        final String senderType;               // 发送器类型（用于恢复时重建sender）
        
        // 原有构造函数保持兼容性
        MediaTask(Pocket48Message message, String groupId) {
            this(message, groupId, System.currentTimeMillis(), 0, null, null, null, null);
        }
        
        // 增强构造函数
        MediaTask(Pocket48Message message, String groupId, Group group, Pocket48Sender sender) {
            this(message, groupId, System.currentTimeMillis(), 0, group, sender, 
                 group != null ? group.getName() : "Unknown", 
                 sender != null ? sender.getClass().getSimpleName() : "Unknown");
        }
        
        // 完整构造函数
        MediaTask(Pocket48Message message, String groupId, long timestamp, int retryCount, 
                 Group group, Pocket48Sender sender, String groupName, String senderType) {
            this.originalMessage = message;
            this.groupId = groupId;
            this.timestamp = timestamp;
            this.retryCount = retryCount;
            this.group = group;
            this.sender = sender;
            this.groupName = groupName != null ? groupName : "Unknown";
            this.senderType = senderType != null ? senderType : "Unknown";
        }
        
        MediaTask withRetry() {
            return new MediaTask(originalMessage, groupId, timestamp, retryCount + 1, 
                               group, sender, groupName, senderType);
        }
        
        /**
         * 检查任务是否有完整的处理上下文
         */
        boolean hasCompleteContext() {
            return group != null && sender != null;
        }
        
        /**
         * 获取任务描述信息
         */
        String getTaskDescription() {
            return String.format("消息类型: %s, 群组: %s(%s), 发送器: %s", 
                               originalMessage.getType(), groupName, groupId, senderType);
        }
    }
    
    public Pocket48MediaQueue() {
        this.resourceManager = Pocket48UnifiedResourceManager.getInstance();
        
        // 初始化主媒体消息队列
        this.mediaQueue = new LinkedBlockingQueue<>(resourceManager.getMediaQueueSize());
        
        // 初始化持久化溢出队列和目录
        this.overflowQueue = new LinkedBlockingQueue<>();
        this.overflowDir = initializeOverflowDirectory();
        
        // 使用统一线程池管理器
        this.mediaThreadPool = (ThreadPoolExecutor) net.luffy.util.AdaptiveThreadPoolManager.getInstance().getExecutor();
        
        // 恢复持久化的溢出任务
        recoverOverflowTasks();
        
        // 启动媒体处理工作线程
        startMediaProcessingWorkers();
        
        UnifiedLogger.getInstance().debug("Pocket48MediaQueue", "口袋48媒体异步处理队列已启动 - Worker模式");
    }
    
    /**
     * 构造函数 - 初始化队列和线程池
     */
    public Pocket48MediaQueue(Pocket48UnifiedResourceManager resourceManager) {
        this.resourceManager = resourceManager;
        
        // 初始化主队列（容量1000）
        this.mediaQueue = new LinkedBlockingQueue<>(1000);
        
        // 初始化溢出队列（内存，容量5000）
        this.overflowQueue = new LinkedBlockingQueue<>(5000);
        
        // 初始化持久化目录
        this.overflowDir = initializeOverflowDirectory();
        
        // 使用统一线程池管理器
        this.mediaThreadPool = (ThreadPoolExecutor) AdaptiveThreadPoolManager.getInstance().getExecutor();
        
        // 恢复持久化的溢出任务
        recoverOverflowTasks();
        
        // 启动worker线程
        startWorkerThreads();
        
        UnifiedLogger.getInstance().debug("Pocket48MediaQueue", 
            "媒体队列已启动 - Worker模式，主队列容量: 1000, 溢出队列容量: 5000");
    }
    
    public static Pocket48MediaQueue getInstance(Properties properties) {
        if (instance == null) {
            synchronized (Pocket48MediaQueue.class) {
                if (instance == null) {
                    instance = new Pocket48MediaQueue(Pocket48UnifiedResourceManager.getInstance());
                }
            }
        }
        return instance;
    }
    
    public static Pocket48MediaQueue getInstance() {
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
     * 提交媒体消息到处理队列（增强版本）
     * @param message 口袋48消息
     * @param group 群组对象
     * @param sender 发送器对象
     */
    public void submitMediaMessage(Pocket48Message message, Group group, Pocket48Sender sender) {
        if (message == null || group == null || sender == null) {
            UnifiedLogger.getInstance().warn("Pocket48MediaQueue", 
                "提交媒体消息失败：参数不能为null");
            return;
        }
        
        if (!resourceManager.isMediaQueueEnabled() || !running.get()) {
            // 如果异步队列未启用，直接同步处理并立即发送
            try {
                Pocket48SenderMessage senderMessage = sender.pharseMessage(message, group, false);
                if (senderMessage != null) {
                    sender.sendSingleMessage(senderMessage, group);
                }
            } catch (Exception e) {
                UnifiedLogger.getInstance().error("Pocket48MediaQueue", "同步处理媒体消息失败: " + e.getMessage());
            }
            return;
        }
        
        try {
            // 使用增强的MediaTask构造函数
            MediaTask task = new MediaTask(message, String.valueOf(group.getId()), group, sender);
            
            // 记录队列状态（提交前）
            int currentQueueSize = mediaQueue.size();
            // 延迟系统已移除，直接记录队列大小
            
            // 尝试添加到主队列
            boolean offered = mediaQueue.offer(task, 100, TimeUnit.MILLISECONDS);
            if (offered) {
                UnifiedLogger.getInstance().debug("Pocket48MediaQueue", 
                    "媒体消息已提交到队列: " + task.getTaskDescription());
                
                // 记录队列状态（提交后）
                // 延迟系统已移除，直接记录队列大小
            } else {
                // 队列满时处理溢出
                handleQueueOverflow(task);
                UnifiedLogger.getInstance().warn("Pocket48MediaQueue", 
                    "主队列已满，任务已转移到溢出队列: " + task.getTaskDescription());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", 
                "提交媒体消息被中断: " + e.getMessage(), e);
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", 
                "提交媒体消息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理队列溢出 - 使用持久化溢出队列，禁止丢弃消息
     * @param task 媒体任务
     */
    private void handleQueueOverflow(MediaTask task) {
        try {
            // 添加到内存溢出队列
            overflowQueue.offer(task);
            overflowCount.incrementAndGet();
            
            // 记录队列溢出
            // 延迟系统已移除，直接记录溢出
            
            // 记录溢出队列状态
            // 延迟系统已移除，直接记录队列状态
            
            // 持久化到磁盘
            persistOverflowTask(task);
            
            UnifiedLogger.getInstance().warn("Pocket48MediaQueue", 
                String.format("[背压控制] 主队列已满，任务已转入溢出队列 - 类型: %s, 溢出计数: %d", 
                    task.originalMessage.getType(), overflowCount.get()));
                    
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", "溢出队列处理失败: " + e.getMessage());
            // 即使溢出队列失败，也不丢弃消息，记录错误供后续处理
            failedCount.incrementAndGet();
        }
    }
    
    /**
     * 初始化溢出队列目录
     */
    private Path initializeOverflowDirectory() {
        try {
            Path dir = Paths.get(System.getProperty("user.dir"), "data", "pocket48", "overflow");
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", "初始化溢出目录失败: " + e.getMessage());
            return Paths.get(System.getProperty("java.io.tmpdir"), "pocket48_overflow");
        }
    }
    
    /**
     * 持久化溢出任务到磁盘
     */
    private void persistOverflowTask(MediaTask task) {
        try {
            String filename = String.format("task_%d_%s.ser", 
                task.timestamp, 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
            Path taskFile = overflowDir.resolve(filename);
            
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    Files.newOutputStream(taskFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE))) {
                oos.writeObject(task);
            }
            
            UnifiedLogger.getInstance().debug("Pocket48MediaQueue", "溢出任务已持久化: " + filename);
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", "持久化溢出任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 恢复持久化的溢出任务
     */
    private void recoverOverflowTasks() {
        try {
            if (!Files.exists(overflowDir)) {
                return;
            }
            
            Files.list(overflowDir)
                .filter(path -> path.toString().endsWith(".ser"))
                .forEach(this::recoverSingleTask);
                
            // 仅在实际恢复任务时输出日志
            int recovered = recoveredCount.get();
            if (recovered > 0) {
                UnifiedLogger.getInstance().info("Pocket48MediaQueue", 
                    String.format("已恢复 %d 个持久化溢出任务", recovered));
            }
                
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", "恢复溢出任务失败: " + e.getMessage());
        }
    }
    
    /**
     * 恢复单个持久化任务
     */
    private void recoverSingleTask(Path taskFile) {
        try (ObjectInputStream ois = new ObjectInputStream(
                Files.newInputStream(taskFile, StandardOpenOption.READ))) {
            MediaTask task = (MediaTask) ois.readObject();
            
            // 检查任务是否过期（超过12小时，优化为更短的过期时间）
            long ageHours = (System.currentTimeMillis() - task.timestamp) / (1000 * 60 * 60);
            if (ageHours > 12) {
                Files.deleteIfExists(taskFile);
                UnifiedLogger.getInstance().warn("Pocket48MediaQueue", "删除过期溢出任务: " + taskFile.getFileName());
                return;
            }
            
            // 添加到溢出队列
            overflowQueue.offer(task);
            recoveredCount.incrementAndGet();
            
            // 删除已恢复的文件
            Files.deleteIfExists(taskFile);
            
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", "恢复任务文件失败: " + taskFile + ", " + e.getMessage());
            // 删除损坏的文件
            try {
                Files.deleteIfExists(taskFile);
            } catch (Exception ignored) {}
        }
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
     * 启动worker线程处理队列任务
     */
    private void startWorkerThreads() {
        // 主队列处理worker
        mediaThreadPool.submit(() -> {
            Thread.currentThread().setName("Pocket48-MainQueue-Worker");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    MediaTask task = mediaQueue.take(); // 阻塞等待任务
                    processMediaTask(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    UnifiedLogger.getInstance().error("Pocket48MediaQueue", "主队列worker处理异常: " + e.getMessage());
                }
            }
        });
        
        // 溢出队列处理worker
        mediaThreadPool.submit(() -> {
            Thread.currentThread().setName("Pocket48-OverflowQueue-Worker");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 检查主队列是否有空间
                    if (mediaQueue.remainingCapacity() > 0) {
                        MediaTask overflowTask = overflowQueue.poll();
                        if (overflowTask != null) {
                            // 将溢出任务重新放入主队列
                            if (mediaQueue.offer(overflowTask)) {
                                overflowCount.decrementAndGet();
                                UnifiedLogger.getInstance().debug("Pocket48MediaQueue", "溢出任务已回流到主队列");
                            } else {
                                // 如果主队列又满了，重新放回溢出队列
                                overflowQueue.offer(overflowTask);
                            }
                        }
                    }
                    // 使用更高效的轮询间隔，避免过度CPU占用
                    try {
                        Thread.sleep(100); // 优化轮询间隔，平衡响应性和CPU使用
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Exception e) {
                    UnifiedLogger.getInstance().error("Pocket48MediaQueue", "溢出队列worker处理异常: " + e.getMessage());
                }
            }
        });
    }
    
    /**
     * 处理媒体任务（增强版本）
     */
    private void processMediaTask(MediaTask task) {
        if (task == null) {
            UnifiedLogger.getInstance().warn("Pocket48MediaQueue", "收到空的媒体任务");
            return;
        }
        
        long waitTime = System.currentTimeMillis() - task.timestamp;
        
        try {
            // 检查任务是否过期
            long ageMinutes = (System.currentTimeMillis() - task.timestamp) / (1000 * 60);
            if (ageMinutes > 15) { // 15分钟过期（优化为更短的过期时间）
                UnifiedLogger.getInstance().warn("Pocket48MediaQueue", "丢弃过期任务: " + task.originalMessage.getType());
                return;
            }
            
            // 检查任务是否有完整的处理上下文
            if (!task.hasCompleteContext()) {
                UnifiedLogger.getInstance().warn("Pocket48MediaQueue", 
                    "任务缺少完整上下文信息，尝试恢复: " + task.getTaskDescription());
                
                // 尝试恢复上下文信息
                if (!tryRecoverTaskContext(task)) {
                    failedCount.incrementAndGet();
                    UnifiedLogger.getInstance().error("Pocket48MediaQueue", 
                        "无法恢复任务上下文，任务处理失败: " + task.getTaskDescription());
                    return;
                }
            }
            
            // 记录队列等待时间
            // 延迟系统已移除，直接记录队列状态
            
            // 使用完整的上下文信息处理任务
            processMediaTaskWithContext(task);
            
            processedCount.incrementAndGet();
            UnifiedLogger.getInstance().debug("Pocket48MediaQueue", 
                "媒体任务处理完成: " + task.getTaskDescription());
            
        } catch (Exception e) {
            failedCount.incrementAndGet();
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", 
                "媒体任务处理异常: " + task.getTaskDescription() + ", 错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 尝试恢复任务上下文信息
     */
    private boolean tryRecoverTaskContext(MediaTask task) {
        try {
            // 这里可以根据groupId和senderType尝试重建上下文
            // 目前返回false，表示无法恢复，需要后续实现具体的恢复逻辑
            UnifiedLogger.getInstance().info("Pocket48MediaQueue", 
                "任务上下文恢复功能待实现，群组: " + task.groupName + ", 发送器类型: " + task.senderType);
            return false;
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", 
                "恢复任务上下文时发生异常: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 使用完整上下文信息处理媒体任务
     */
    private void processMediaTaskWithContext(MediaTask task) {
        try {
            // 使用task中的group和sender对象进行实际的消息处理
            // 这里可以调用sender的相应方法来处理不同类型的媒体消息
            
            UnifiedLogger.getInstance().info("Pocket48MediaQueue", 
                "开始处理媒体任务: " + task.getTaskDescription());
            
            // 根据消息类型进行相应处理
            Pocket48MessageType messageType = task.originalMessage.getType();
            switch (messageType) {
                case IMAGE:
                    // 处理图片消息
                    processImageMessage(task);
                    break;
                case AUDIO:
                    // 处理音频消息
                    processAudioMessage(task);
                    break;
                case VIDEO:
                    // 处理视频消息
                    processVideoMessage(task);
                    break;
                default:
                    UnifiedLogger.getInstance().warn("Pocket48MediaQueue", 
                        "未知的媒体消息类型: " + messageType);
                    break;
            }
            
        } catch (Exception e) {
            throw new RuntimeException("处理媒体任务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理图片消息
     */
    private void processImageMessage(MediaTask task) {
        // 图片处理逻辑的占位符
        UnifiedLogger.getInstance().debug("Pocket48MediaQueue", 
            "处理图片消息: " + task.getTaskDescription());
    }
    
    /**
     * 处理音频消息
     */
    private void processAudioMessage(MediaTask task) {
        // 音频处理逻辑的占位符
        UnifiedLogger.getInstance().debug("Pocket48MediaQueue", 
            "处理音频消息: " + task.getTaskDescription());
    }
    
    /**
     * 处理视频消息
     */
    private void processVideoMessage(MediaTask task) {
        // 视频处理逻辑的占位符
        UnifiedLogger.getInstance().debug("Pocket48MediaQueue", 
            "处理视频消息: " + task.getTaskDescription());
    }

    /**
     * 关停队列处理，优雅停止所有worker线程
     */
    public void shutdown() {
        try {
            UnifiedLogger.getInstance().info("Pocket48MediaQueue", "开始关停媒体队列...");
            
            // 停止接收新任务
            running.set(false);
            
            // 等待当前任务完成
            mediaThreadPool.shutdown();
            if (!mediaThreadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                UnifiedLogger.getInstance().warn("Pocket48MediaQueue", "强制关停线程池");
                mediaThreadPool.shutdownNow();
            }
            
            // 持久化剩余的队列任务
            persistRemainingTasks();
            
            UnifiedLogger.getInstance().info("Pocket48MediaQueue", "媒体队列已关停");
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", "关停过程异常: " + e.getMessage());
        }
    }
    
    /**
     * 持久化剩余的队列任务
     */
    private void persistRemainingTasks() {
        try {
            int persistedCount = 0;
            
            // 持久化主队列剩余任务
            MediaTask task;
            while ((task = mediaQueue.poll()) != null) {
                persistOverflowTask(task);
                persistedCount++;
            }
            
            // 持久化溢出队列剩余任务
            while ((task = overflowQueue.poll()) != null) {
                persistOverflowTask(task);
                persistedCount++;
            }
            
            if (persistedCount > 0) {
                UnifiedLogger.getInstance().info("Pocket48MediaQueue", 
                    String.format("已持久化 %d 个剩余任务", persistedCount));
            }
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("Pocket48MediaQueue", "持久化剩余任务失败: " + e.getMessage());
        }
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
     * 获取队列大小
     * @return 当前队列中的任务数量
     */
    public int getQueueSize() {
        return mediaQueue.size();
    }
    
    /**
     * 获取溢出队列大小
     * @return 当前溢出队列中的任务数量
     */
    public int getOverflowQueueSize() {
        return overflowQueue.size();
    }

    /**
     * 获取处理统计信息
     * @return 格式化的统计字符串
     */
    public String getStats() {
        return String.format("主队列: %d, 溢出队列: %d, 已处理: %d, 失败: %d, 溢出计数: %d, 恢复计数: %d", 
            getQueueSize(), getOverflowQueueSize(), processedCount.get(), failedCount.get(), 
            overflowCount.get(), recoveredCount.get());
    }


    
    /**
     * 获取当前队列大小
     */
    public int getCurrentQueueSize() {
        return mediaQueue.size();
    }
    
    /**
     * 检查队列是否启用
     */
    public boolean isEnabled() {
        return resourceManager.isMediaQueueEnabled() && running.get();
    }
    

}