package net.luffy.util.sender;

import cn.hutool.core.date.DateUtil;
import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;
import net.luffy.util.sender.Pocket48UnifiedResourceManager;
import net.luffy.util.sender.Pocket48ActivityMonitor;
import net.luffy.util.PerformanceMonitor;
import net.luffy.util.MessageIntegrityChecker;
import net.luffy.util.UnifiedLogger;
import net.luffy.util.Pocket48MetricsCollector;
import net.luffy.util.delay.UnifiedDelayService;
import net.luffy.util.delay.DelayPolicy;
import net.luffy.util.delay.GroupRateLimiter;
import net.luffy.util.delay.DelayMetricsCollector;
import net.luffy.util.delay.DelayConfig;
import net.luffy.util.StringMatchUtils;

import net.luffy.model.*;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.*;
import net.mamoe.mirai.message.data.ShortVideo;
import net.mamoe.mirai.utils.ExternalResource;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Pocket48Sender extends Sender {

    //endTime是一个关于roomID的HashMap
    private final HashMap<Long, Long> endTime;
    private final HashMap<Long, List<Long>> voiceStatus;
    private final HashMap<Long, Pocket48SenderCache> cache;
    private final Pocket48UnifiedResourceManager unifiedResourceManager;
    private final Pocket48AsyncMessageProcessor asyncProcessor;

    private final net.luffy.util.CpuLoadBalancer loadBalancer;
    private final Pocket48MediaQueue mediaQueue;
    private final Pocket48ActivityMonitor activityMonitor;
    private final ScheduledExecutorService delayExecutor;
    private final UnifiedDelayService unifiedDelayService;
    private final DelayPolicy delayPolicy;
    private final GroupRateLimiter rateLimiter;
    
    // 统一日志和指标收集
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final Pocket48MetricsCollector metricsCollector = Pocket48MetricsCollector.getInstance();

    public Pocket48Sender(Bot bot, long group, HashMap<Long, Long> endTime, HashMap<Long, List<Long>> voiceStatus, HashMap<Long, Pocket48SenderCache> cache) {
        super(bot, group);
        this.endTime = endTime;
        this.voiceStatus = voiceStatus;
        this.cache = cache;
        this.unifiedResourceManager = Pocket48UnifiedResourceManager.getInstance();
        this.asyncProcessor = new Pocket48AsyncMessageProcessor(this);

        this.loadBalancer = net.luffy.util.CpuLoadBalancer.getInstance();
        this.mediaQueue = new Pocket48MediaQueue();
        this.activityMonitor = Pocket48ActivityMonitor.getInstance();
        // 使用统一调度管理器替代自建线程池
        this.delayExecutor = net.luffy.util.UnifiedSchedulerManager.getInstance().getScheduledExecutor();
        this.unifiedDelayService = UnifiedDelayService.getInstance();
        this.delayPolicy = new DelayPolicy();
        this.rateLimiter = new GroupRateLimiter();
        
        // 初始化缓存设置（从配置文件读取）
        initializeCacheSettings();
        
        logger.debug("Pocket48Sender", "Pocket48Sender初始化完成，群组: " + group);
    }
    
    /**
     * 初始化缓存设置
     */
    private void initializeCacheSettings() {
        try {
            // 从配置文件读取缓存启用状态，默认启用
            boolean cacheEnabled = Newboy.INSTANCE.getConfig().getSetting().getBool("pocket48", "cache.enabled", true);
            setCacheEnabled(cacheEnabled);
        } catch (Exception e) {
            // 配置读取失败时默认启用缓存
            setCacheEnabled(true);
        }
    }
    
    /**
     * 设置缓存启用状态
     * @param enabled true启用缓存，false禁用缓存
     */
    public void setCacheEnabled(boolean enabled) {
        if (unifiedResourceManager != null) {
            unifiedResourceManager.setCacheEnabled(enabled);
        }
    }
    
    /**
     * 检查缓存是否启用
     * @return true如果缓存启用
     */
    public boolean isCacheEnabled() {
        return unifiedResourceManager != null && unifiedResourceManager.isCacheEnabled();
    }
    
    /**
     * 清空所有缓存
     */
    public void clearAllCache() {
        if (unifiedResourceManager != null) {
            unifiedResourceManager.clearAllCache();
        }
    }

    @Override
    public void run() {
        try {
            Pocket48Subscribe subscribe = Newboy.INSTANCE.getProperties().pocket48_subscribe.get(group_id);
            Pocket48Handler pocket = Newboy.INSTANCE.getHandlerPocket48();

            //房间消息获取 - 改进的重试机制和缓存刷新（优化：减少重试延迟）
            for (long roomID : subscribe.getRoomIDs()) {
                Pocket48SenderCache currentCache = cache.get(roomID);
                
                // 检查缓存是否存在或过期
                boolean needsRefresh = currentCache == null || currentCache.isExpired();
                
                if (needsRefresh) {
                    // 移除缓存过期的信息日志，减少日志噪音
                    cache.put(roomID, Pocket48SenderCache.create(roomID, endTime));
                }
                
                // 智能重试机制：如果创建失败，进行异步重试
                if (cache.get(roomID) == null) {
                    createCacheWithAsyncRetry(roomID, endTime);
                }
            }

            List<Pocket48Message[]> totalMessages = new ArrayList<>();

            for (long roomID : subscribe.getRoomIDs()) {
                if (cache.get(roomID) == null) {
                    continue;
                }

                Pocket48RoomInfo roomInfo = cache.get(roomID).roomInfo;

                //房间消息预处理
                Pocket48Message[] a = cache.get(roomID).messages;
                if (a.length > 0) {
                    // 消息完整性检查
                    MessageIntegrityChecker.IntegrityCheckResult integrityResult = 
                        MessageIntegrityChecker.validateMessageBatch(roomID, a);
                    
                    if (!integrityResult.isValid()) {
                        System.err.println(String.format("[完整性警告] 房间 %d: %s", 
                            roomID, integrityResult.toString()));
                        
                        // 如果有重复消息，过滤掉它们
                        if (integrityResult.getDuplicateCount() > 0) {
                            List<Pocket48Message> filteredMessages = new ArrayList<>();
                            for (Pocket48Message msg : a) {
                                if (!MessageIntegrityChecker.isDuplicateMessage(roomID, msg)) {
                                    filteredMessages.add(msg);
                                }
                            }
                            a = filteredMessages.toArray(new Pocket48Message[0]);
                            System.out.println(String.format("[去重] 房间 %d 过滤后消息数: %d", 
                                roomID, a.length));
                        }
                    }
                    
                    if (a.length > 0) {
                        // 记录消息活跃度
                        activityMonitor.recordBatchMessageActivity(roomID, java.util.Arrays.asList(a));
                        totalMessages.add(a);
                    }
                }

                //房间语音
                List<Long> n = cache.get(roomID).voiceList;
                if (voiceStatus.containsKey(roomID)) {
                    String[] r = handleVoiceList(voiceStatus.get(roomID), n);
                    if (r[0] != null || r[1] != null) {
                        boolean private_ = Pocket48Handler.getOwnerOrTeamName(roomInfo).equals(roomInfo.getOwnerName());
                        String currentTime = cn.hutool.core.date.DateUtil.format(new java.util.Date(), "yyyy-MM-dd HH:mm:ss");
                        
                        // 使用与消息推送一致的格式模板
                        if (r[0] != null) {
                            String upMessage;
                            if (private_) {
                                // 成员房间：使用统一的消息格式
                                upMessage = "【" + roomInfo.getOwnerName() + "】: 上麦啦~\n频道：" + roomInfo.getRoomName() + "\n时间: " + currentTime;
                            } else {
                                // 队伍房间：显示具体上麦成员
                                upMessage = "【" + r[0] + "】: 上麦啦~\n频道：" + roomInfo.getRoomName() + "\n时间: " + currentTime;
                            }
                            Message upMsg = new PlainText(upMessage);
                            group.sendMessage(toNotification(upMsg)); // 上麦时@全体
                        }
                        
                        if (r[1] != null) {
                            String downMessage;
                            if (private_) {
                                // 成员房间：使用统一的消息格式
                                downMessage = "【" + roomInfo.getOwnerName() + "】: 下麦了捏~\n频道：" + roomInfo.getRoomName() + "\n时间: " + currentTime;
                            } else {
                                // 队伍房间：显示具体下麦成员
                                downMessage = "【" + r[1] + "】: 下麦了捏~\n频道：" + roomInfo.getRoomName() + "\n时间: " + currentTime;
                            }
                            Message downMsg = new PlainText(downMessage);
                            group.sendMessage(downMsg); // 下麦时不@全体
                        }
                    }
                }
                voiceStatus.put(roomID, n);
            }

            //房间消息 - 优化：快速发送模式，减少延迟
            if (totalMessages.size() > 0) {
                // 将所有房间的消息合并并按时间排序
                List<Pocket48Message> allMessages = new ArrayList<>();
                for (Pocket48Message[] roomMessage : totalMessages) {
                    for (Pocket48Message msg : roomMessage) {
                        allMessages.add(msg);
                    }
                }
                
                // 按消息时间戳排序（从旧到新）
                allMessages.sort((a, b) -> Long.compare(a.getTime(), b.getTime()));
                
                // 使用现有的消息发送方法
                sendMessages(allMessages, group);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String[] handleVoiceList(List<Long> a, List<Long> b) {
        String zengjia = "";
        String jianshao = "";
        Pocket48Handler handler = Newboy.INSTANCE.getHandlerPocket48();
        
        for (Long b0 : b) {
            if (!a.contains(b0)) {
                String nickname = handler.getUserNickName(b0);
                zengjia += "，" + (nickname != null ? nickname : String.valueOf(b0));
            }
        }
        for (Long a0 : a) {
            if (!b.contains(a0)) {
                String nickname = handler.getUserNickName(a0);
                jianshao += "，" + (nickname != null ? nickname : String.valueOf(a0));
            }
        }
        return new String[]{(zengjia.length() > 0 ? zengjia.substring(1) : null),
                (jianshao.length() > 0 ? jianshao.substring(1) : null)};
    }

    /**
     * 快速解析消息（优先处理文本类消息，媒体消息返回占位符）
     * 解决同步阻塞问题：文本消息立即处理，媒体消息异步处理
     */
    public Pocket48SenderMessage pharseMessageFast(Pocket48Message message, Group group, boolean single_subscribe) throws IOException {
        if (message == null) {
            return null;
        }
        
        String nickName = message.getNickName() != null ? message.getNickName() : "未知用户";
        String n = nickName;
        String r = (message.getRoom() != null && message.getRoom().getRoomName() != null) ? message.getRoom().getRoomName() : "未知频道";
        String timeStr = cn.hutool.core.date.DateUtil.format(new java.util.Date(message.getTime()), "yyyy-MM-dd HH:mm:ss");

        switch (message.getType()) {
            case TEXT:
            case GIFT_TEXT:
                String body = message.getBody() != null ? message.getBody() : "[消息内容为空]";
                String textContent = "【" + n + "】: " + pharsePocketTextWithFace(body) + "\n频道：" + r + "\n时间: " + timeStr;
                return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(textContent)});
            
            case REPLY:
            case GIFTREPLY:
                if (message.getReply() == null) {
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText("【" + n + "】: 回复消息(内容为空)\n频道：" + r + "\n时间: " + timeStr)});
                }
                String nameTo = message.getReply().getNameTo() != null ? message.getReply().getNameTo() : "未知用户";
                String msgTo = message.getReply().getMsgTo() != null ? message.getReply().getMsgTo() : "[消息为空]";
                String msgFrom = message.getReply().getMsgFrom() != null ? message.getReply().getMsgFrom() : "[回复为空]";
                String replyContent = "【" + n + "】: \n" + nameTo + ": " + msgTo + "\n" + msgFrom + "\n频道：" + r + "\n时间: " + timeStr;
                return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(replyContent)});
            
            case FLIPCARD:
                Pocket48Handler pocket = Newboy.INSTANCE.getHandlerPocket48();
                String flipContent = "【" + n + "】: 翻牌回复消息\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n" + message.getAnswer().getAnswer() + "\n频道：" + r + "\n时间: " + timeStr;
                return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(flipContent)});
            
            // 媒体消息返回占位符，稍后异步处理
            case AUDIO:
            case IMAGE:
            case EXPRESSIMAGE:
            case VIDEO:
            case LIVEPUSH:
            case FLIPCARD_AUDIO:
            case FLIPCARD_VIDEO:
                // 媒体消息不显示占位符，直接返回null让异步处理器处理
                return null;
            
            default:
                String defaultContent = "【" + n + "】: [未知消息类型: " + message.getType() + "]\n频道：" + r + "\n时间: " + timeStr;
                return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(defaultContent)});
        }
    }
    
    /**
     * 原始同步消息解析方法（保留用于兼容性）
     */
    public Pocket48SenderMessage pharseMessage(Pocket48Message message, Group group, boolean single_subscribe) throws IOException {
        if (message == null) {
            logger.warn("Pocket48Sender", "接收到空消息，跳过处理");
            return null;
        }
        
        // 统一消息完整性检查
        try {
            long roomId = message.getRoom() != null ? message.getRoom().getRoomId() : 0L;
            MessageIntegrityChecker.assignSequenceNumber(roomId, message);
            if (MessageIntegrityChecker.isDuplicateMessage(roomId, message)) {
                logger.debug("Pocket48Sender", "检测到重复消息，跳过: " + message.getMessageId());
                metricsCollector.recordMessageDropped("duplicate");
                return null;
            }
            MessageIntegrityChecker.checkTimeContinuity(roomId, message);
        } catch (Exception e) {
            logger.error("Pocket48Sender", "消息完整性检查失败", e);
            metricsCollector.recordError("integrity_check_failed");
        }
        
        Pocket48Handler pocket = Newboy.INSTANCE.getHandlerPocket48();
        String nickName = message.getNickName() != null ? message.getNickName() : "未知用户";
        String n = sanitizeUserName(nickName); // 脱敏处理
        String r = (message.getRoom() != null && message.getRoom().getRoomName() != null) ? message.getRoom().getRoomName() : "未知频道";
        String timeStr = cn.hutool.core.date.DateUtil.format(new java.util.Date(message.getTime()), "yyyy-MM-dd HH:mm:ss");
        String headerTemplate = "【" + n + "】: " + "\n频道：" + r + "\n时间: " + timeStr;
        
        logger.debug("Pocket48Sender", "开始解析消息: 类型=" + message.getType() + ", 用户=" + n + ", 房间=" + r);

        switch (message.getType()) {
            case TEXT:
            case GIFT_TEXT:
                String body = message.getBody() != null ? message.getBody() : "[消息内容为空]";
                String textContent = "【" + n + "】: " + pharsePocketTextWithFace(body) + "\n频道：" + r + "\n时间: " + timeStr;
                return new Pocket48SenderMessage(false, null,
                        new Message[]{new PlainText(textContent)});
            case AUDIO: {
                String audioUrl = message.getResLoc();
                if (audioUrl == null || audioUrl.trim().isEmpty()) {
                    System.err.println("[错误] 音频URL为空");
                    String errorContent = "【" + n + "】: 语音消息URL为空\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(errorContent)});
                }
                
                File audioFile = null;
                
                try {
                    // 智能获取音频文件（优先使用缓存）
                    String audioExt = message.getExt() != null && !message.getExt().isEmpty() ? "." + message.getExt() : ".amr";
                    audioFile = unifiedResourceManager.getResourceSmart(audioUrl);
                    
                    // 如果缓存未命中，使用传统下载方式
                    if (audioFile == null) {
                        audioFile = unifiedResourceManager.downloadToTempFile(audioUrl, audioExt);
                    }
                    
                    if (audioFile == null || !audioFile.exists()) {
                        throw new RuntimeException("音频文件获取失败");
                    }
                    
                    // 读取文件头进行格式检测
                    byte[] header = new byte[16];
                    try (InputStream fileStream = java.nio.file.Files.newInputStream(audioFile.toPath())) {
                        int bytesRead = fileStream.read(header);
                        
                        if (bytesRead > 0) {
                            String format = net.luffy.util.AudioFormatDetector.detectFormat(header);
                            String compatibility = net.luffy.util.AudioFormatDetector.getCompatibilityDescription(format);
                            
                            // 记录音频格式信息（仅在调试时使用）
                            
                            if (!net.luffy.util.AudioFormatDetector.isQQCompatible(format)) {
                                // 检测到不兼容格式，尝试转换为AMR格式
                                // 尝试转换音频格式
                                try (InputStream audioInputStream = java.nio.file.Files.newInputStream(audioFile.toPath());
                                     BufferedInputStream bufferedAudioStream = new BufferedInputStream(audioInputStream)) {
                                    InputStream convertedStream = net.luffy.util.AudioFormatConverter.convertToAMR(bufferedAudioStream, format);
                                    if (convertedStream != null) {
                                        // 将转换后的音频保存到新的临时文件
                                        File convertedFile = new File(audioFile.getParent(), "converted_" + audioFile.getName().replaceAll("\\.[^.]+$", ".amr"));
                                        try (FileOutputStream fos = new FileOutputStream(convertedFile)) {
                                            byte[] buffer = new byte[8192];
                                            int bytesReadConv;
                                            while ((bytesReadConv = convertedStream.read(buffer)) != -1) {
                                                fos.write(buffer, 0, bytesReadConv);
                                            }
                                        }
                                        convertedFile.deleteOnExit();
                                        
                                        // 删除原文件，使用转换后的文件
                                        audioFile.delete();
                                        audioFile = convertedFile;
                                        // 音频格式转换完成
                                    } else {
                                        System.err.println("[音频转换] 音频格式转换失败，使用原始音频");
                                    }
                                }
                            }
                        } else {
                            System.err.println("[警告] 无法读取音频文件头，可能是空文件或损坏的文件");
                        }
                    }
                    
                    // 上传音频
                    try (ExternalResource audioResource = ExternalResource.create(audioFile)) {
                        Audio audio = group.uploadAudio(audioResource);
                        // 音频上传成功
                        String audioContent = "【" + n + "】: 发送了一条语音\n查看链接: " + (audioUrl != null ? audioUrl : "[链接获取失败]") + "\n频道：" + r + "\n时间: " + timeStr;
                        return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(audioContent), audio});
                    }
                } catch (Exception e) {
                    System.err.println("[错误] 处理音频消息失败: " + e.getMessage());
                    e.printStackTrace();
                    String errorContent = "【" + n + "】: 语音消息处理失败(" + e.getMessage() + ")\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText(errorContent)});
                } finally {
                    // 清理临时文件
                    if (audioFile != null && audioFile.exists()) {
                        try {
                            audioFile.delete();
                            // 删除临时音频文件
                        } catch (Exception e) {
                            System.err.println("[警告] 删除临时音频文件失败: " + e.getMessage());
                        }
                    }
                }
            }
            case IMAGE: {
                // 推断文件扩展名并下载到临时文件
                String inferredExt = inferImageExtensionFromUrl(message.getResLoc());
                File imageFile = unifiedResourceManager.downloadToTempFileWithRetry(message.getResLoc(), inferredExt, 3);
                
                try (ExternalResource imageResource = ExternalResource.create(imageFile)) {
                    Image image = group.uploadImage(imageResource);
                    // 创建包含图片的消息链，图片嵌入到消息中
                    MessageChain messageChain = new PlainText("【" + n + "】: 发送了一张图片\n").plus(image).plus("\n频道：" + r + "\n时间: " + timeStr);
                    return new Pocket48SenderMessage(false, null, new Message[]{messageChain});
                }
            }
            case EXPRESSIMAGE: {
                // 口袋表情处理：解析表情信息并优化显示
                try {
                    String emotionName = parseEmotionName(message);
                    String resUrl = message.getResLoc();
                    
                    // 尝试获取表情图片资源
                    if (resUrl != null && !resUrl.trim().isEmpty()) {
                        Newboy.INSTANCE.getLogger().info("开始处理口袋48表情图片: " + resUrl);
                        
                        try {
                            // 首先验证资源可用性
                             Pocket48ResourceHandler.Pocket48ResourceInfo resourceInfo = 
                                 unifiedResourceManager.checkResourceAvailability(resUrl);
                            
                            if (!resourceInfo.isAvailable()) {
                                Newboy.INSTANCE.getLogger().warning("口袋48表情资源不可用: " + resUrl + 
                                    ", 状态码: " + resourceInfo.getStatusCode() + 
                                    ", 错误: " + resourceInfo.getErrorMessage());
                                throw new RuntimeException("资源不可用: " + resourceInfo.getErrorMessage());
                            }
                            
                            // 推断文件扩展名并下载到临时文件
                            String inferredExt = inferImageExtensionFromUrl(resUrl);
                            File emotionFile = unifiedResourceManager.downloadToTempFileWithRetry(resUrl, inferredExt, 3);
                            
                            // 获取资源流
                            try (ExternalResource emotionResource = ExternalResource.create(emotionFile)) {
                                // 使用带重试机制的图片上传方法
                                Image emotionImage = uploadImageWithRetry(emotionResource, 3);
                                // 创建包含表情图片的消息链
                                MessageChain messageChain = new PlainText("【" + n + "】: " + emotionName + "\n")
                                        .plus(emotionImage)
                                        .plus("\n频道：" + r + "\n时间: " + timeStr);
                                Newboy.INSTANCE.getLogger().info("口袋48表情图片处理成功: " + resUrl);
                                return new Pocket48SenderMessage(false, null, new Message[]{messageChain});
                            }
                        } catch (Exception imageEx) {
                            // 图片加载或上传失败，仅显示文本
                            String errorMsg = "表情图片处理失败: " + imageEx.getMessage();
                            System.err.println(errorMsg);
                            Newboy.INSTANCE.getLogger().warning("口袋48表情图片处理失败: " + resUrl + 
                                ", 表情名: " + emotionName + 
                                ", 错误类型: " + imageEx.getClass().getSimpleName() + 
                                ", 错误信息: " + imageEx.getMessage());
                            
                            // 如果是rich media transfer failed错误，记录更详细信息
                            if (imageEx.getMessage() != null && StringMatchUtils.isRetryableError(imageEx.getMessage())) {
                                Newboy.INSTANCE.getLogger().warning("检测到OneBot富媒体传输失败，可能的原因: " +
                                    "1. 图片文件损坏或格式不支持; " +
                                    "2. 网络连接问题; " +
                                    "3. OneBot服务异常; " +
                                    "4. 图片尺寸过大或过小");
                            }
                        }
                    } else {
                        Newboy.INSTANCE.getLogger().info("口袋48表情消息无图片资源URL，仅显示文本: " + emotionName);
                    }
                    
                    // 如果没有图片资源或图片处理失败，仅显示文本
                    String expressContent = "【" + n + "】: " + emotionName + "\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(expressContent)});
                } catch (Exception e) {
                    // 异常情况下的兜底处理
                    String fallbackContent = "【" + n + "】: [表情]\n频道：" + r + "\n时间: " + timeStr;
                    Newboy.INSTANCE.getLogger().warning("口袋48表情处理异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(fallbackContent)});
                }
            }
            case VIDEO: {
                String videoUrl = message.getResLoc();
                
                if (videoUrl == null || videoUrl.trim().isEmpty()) {
                    System.err.println("[错误] 视频URL为空");
                    String errorContent = "【" + n + "】: 视频消息URL为空\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(errorContent)});
                }
                
                File videoFile = null;
                File thumbnailFile = null;
                
                try {
                    // 开始处理视频
                    
                    // 智能获取视频文件（优先使用缓存）
                    String videoExt = message.getExt() != null && !message.getExt().isEmpty() ? "." + message.getExt() : ".mp4";
                    videoFile = unifiedResourceManager.getResourceSmart(videoUrl);
                    
                    // 如果缓存未命中，使用传统下载方式
                    if (videoFile == null) {
                        videoFile = unifiedResourceManager.downloadToTempFile(videoUrl, videoExt);
                    }
                    
                    if (videoFile == null || !videoFile.exists()) {
                        throw new RuntimeException("视频文件获取失败");
                    }
                    
                    // 视频下载完成
                    
                    // 生成缩略图
                    try (InputStream videoInputStream = java.nio.file.Files.newInputStream(videoFile.toPath())) {
                        InputStream thumbnailStream = getVideoThumbnail(videoInputStream, message.getRoom().getBgImg());
                        
                        // 如果缩略图是从视频生成的，保存到临时文件
                        if (thumbnailStream != null) {
                            thumbnailFile = new File(videoFile.getParent(), "thumb_" + videoFile.getName().replaceAll("\\.[^.]+$", ".jpg"));
                            try (FileOutputStream fos = new FileOutputStream(thumbnailFile)) {
                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                while ((bytesRead = thumbnailStream.read(buffer)) != -1) {
                                    fos.write(buffer, 0, bytesRead);
                                }
                            }
                            thumbnailFile.deleteOnExit();
                            // 缩略图生成完成
                        }
                    }
                    
                    // 上传视频
                    try (ExternalResource videoResource = ExternalResource.create(videoFile)) {
                        
                        if (thumbnailFile != null && thumbnailFile.exists()) {
                            try (ExternalResource thumbnailResource = ExternalResource.create(thumbnailFile)) {
                                ShortVideo video = group.uploadShortVideo(thumbnailResource, videoResource,
                                    message.getOwnerName() + "房间视频(" + DateUtil.format(new Date(message.getTime()), "yyyy-MM-dd HH-mm-ss") + ")." + message.getExt());
                                // 视频上传成功
                                
                                String videoContent = "【" + n + "】: 发送了一个视频\n查看链接: " + videoUrl + "\n频道：" + r + "\n时间: " + timeStr;
                                return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(videoContent), video});
                            }
                        } else {
                            // 如果缩略图生成失败，使用默认图片
                            try (InputStream defaultThumb = getRes(message.getRoom().getBgImg());
                                 ExternalResource thumbnailResource = ExternalResource.create(defaultThumb)) {
                                
                                ShortVideo video = group.uploadShortVideo(thumbnailResource, videoResource,
                                    message.getOwnerName() + "房间视频(" + DateUtil.format(new Date(message.getTime()), "yyyy-MM-dd HH-mm-ss") + ")." + message.getExt());
                                // 视频上传成功
                                
                                String videoContent = "【" + n + "】: 发送了一个视频\n查看链接: " + videoUrl + "\n频道：" + r + "\n时间: " + timeStr;
                                return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(videoContent), video});
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[错误] 处理视频消息失败: " + e.getMessage());
                    e.printStackTrace();
                    String errorContent = "【" + n + "】: 视频消息处理失败(" + e.getMessage() + ")\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText(errorContent)});
                } finally {
                    // 清理临时文件
                    if (videoFile != null && videoFile.exists()) {
                        try {
                            videoFile.delete();
                            // 删除临时视频文件
                        } catch (Exception e) {
                            System.err.println("[警告] 删除临时视频文件失败: " + e.getMessage());
                        }
                    }
                    if (thumbnailFile != null && thumbnailFile.exists()) {
                        try {
                            thumbnailFile.delete();
                            // 删除临时缩略图文件
                        } catch (Exception e) {
                            System.err.println("[警告] 删除临时缩略图文件失败: " + e.getMessage());
                        }
                    }
                }
            }
            case REPLY:
            case GIFTREPLY:
                if (message.getReply() == null) {
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText("【" + n + "】: 回复消息(内容为空)\n频道：" + r + "\n时间: " + timeStr)});
                }
                String nameTo = message.getReply().getNameTo() != null ? message.getReply().getNameTo() : "未知用户";
                String msgTo = message.getReply().getMsgTo() != null ? message.getReply().getMsgTo() : "[消息为空]";
                String msgFrom = message.getReply().getMsgFrom() != null ? message.getReply().getMsgFrom() : "[回复为空]";
                String replyContent = "【" + n + "】: \n" + nameTo + ": " + msgTo + "\n" + msgFrom + "\n频道：" + r + "\n时间: " + timeStr;
                return new Pocket48SenderMessage(false, null,
                        new Message[]{new PlainText(replyContent)});
            case LIVEPUSH:
                // 直播封面处理：优化版本，修复无法发送封面的问题
                File coverFile = null;
                File convertedCoverFile = null;
                
                try {
                    String coverUrl = message.getLivePush().getCover();
                    if (coverUrl == null || coverUrl.trim().isEmpty()) {
                        throw new RuntimeException("直播封面URL为空");
                    }
                    
                    // 直播封面优化：对于source.48.cn的jpg图片，尝试直接使用URL
                    if (coverUrl.contains("source.48.cn") && coverUrl.toLowerCase().endsWith(".jpg")) {
                        try {
                            java.util.logging.Logger.getLogger("Pocket48Sender").info(
                                "尝试直接使用直播封面URL: " + coverUrl);
                            
                            // 直接使用URL创建图片资源
                            try (InputStream urlStream = new java.net.URL(coverUrl).openStream();
                                 ExternalResource coverResource = ExternalResource.create(urlStream)) {
                                Image cover = group.uploadImage(coverResource);
                                String livePushContent = "【" + n + "】: 直播中快来~\n直播标题：" + message.getLivePush().getTitle();
                                
                                // 构建消息链：文本 + 图片 + 补充信息
                                MessageChain messageChain = new PlainText(livePushContent + "\n")
                                        .plus(cover)
                                        .plus("\n频道：" + r + "\n时间: " + timeStr);
                                
                                // 直播推送自动@全体成员
                                Message finalMessage = toNotification(messageChain);
                                
                                java.util.logging.Logger.getLogger("Pocket48Sender").info(
                                    "直接使用URL创建直播封面成功");
                                return new Pocket48SenderMessage(false, null, new Message[]{finalMessage});
                            }
                        } catch (Exception urlException) {
                            // 直接使用URL失败，记录日志并回退到下载方式
                            java.util.logging.Logger.getLogger("Pocket48Sender").warning(
                                "直接使用URL创建图片资源失败，回退到下载方式: " + urlException.getMessage());
                        }
                    }
                    
                    // 回退方案：使用原有的下载处理方式
                    // 优先使用缓存，缓存未命中时下载
                    coverFile = unifiedResourceManager.getResourceSmart(coverUrl);
                    
                    // 如果智能获取失败，使用带重试的下载方式
                    if (coverFile == null || !coverFile.exists()) {
                        // 根据URL推断文件扩展名
                        String inferredExt = inferImageExtensionFromUrl(coverUrl);
                        coverFile = unifiedResourceManager.downloadToTempFileWithRetry(coverUrl, inferredExt, 3);
                    }
                    
                    if (coverFile == null || !coverFile.exists()) {
                        throw new RuntimeException("直播封面下载失败");
                    }
                    
                    // 主动检测图片格式并修正文件扩展名
                    String detectedFormat = net.luffy.util.ImageFormatDetector.detectFormat(coverFile);
                    if (!"UNKNOWN".equals(detectedFormat)) {
                        // 获取正确的扩展名
                        String correctExtension = getCorrectExtension(detectedFormat);
                        
                        // 如果检测到的格式与当前文件扩展名不匹配，重命名文件
                        String currentExt = coverFile.getName().substring(coverFile.getName().lastIndexOf('.'));
                        if (!correctExtension.equalsIgnoreCase(currentExt)) {
                            File correctedFile = new File(coverFile.getParent(), 
                                coverFile.getName().substring(0, coverFile.getName().lastIndexOf('.')) + correctExtension);
                            if (coverFile.renameTo(correctedFile)) {
                                coverFile = correctedFile;
                            }
                        }
                    }
                    
                    // 图片处理：直接尝试上传，失败时进行格式转换
                    try (ExternalResource coverResource = ExternalResource.create(coverFile)) {
                        Image cover = uploadImageWithRetry(coverResource, 2);
                        String livePushContent = "【" + n + "】: 直播中快来~\n直播标题：" + message.getLivePush().getTitle();
                        
                        // 构建消息链：文本 + 图片 + 补充信息
                        MessageChain messageChain = new PlainText(livePushContent + "\n")
                                .plus(cover)
                                .plus("\n频道：" + r + "\n时间: " + timeStr);
                        
                        // 直播推送自动@全体成员
                        Message finalMessage = toNotification(messageChain);
                        return new Pocket48SenderMessage(false, null, new Message[]{finalMessage});
                    } catch (Exception uploadException) {
                        // 上传失败，尝试格式转换
                        try {
                            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(coverFile);
                            if (image != null) {
                                // 创建转换后的文件 - 使用JPEG格式
                                convertedCoverFile = new File(coverFile.getParent(), "converted_" + 
                                    System.currentTimeMillis() + "_cover.jpg");
                                
                                // 保存为JPEG格式
                                javax.imageio.ImageIO.write(image, "JPEG", convertedCoverFile);
                                
                                // 验证转换后的图片格式
                                String convertedFormat = net.luffy.util.ImageFormatDetector.detectFormat(convertedCoverFile);
                                if (!"UNKNOWN".equals(convertedFormat) && !"JPEG".equals(convertedFormat.toUpperCase())) {
                                    // 如果检测到的格式不是JPEG，记录警告日志
                                    java.util.logging.Logger.getLogger("Pocket48Sender").warning(
                                        "转换后的图片格式不是JPEG: " + convertedFormat);
                                }
                                
                                // 重新尝试上传转换后的图片
                                try (ExternalResource convertedResource = ExternalResource.create(convertedCoverFile)) {
                                    Image cover = uploadImageWithRetry(convertedResource, 2);
                                    String livePushContent = "【" + n + "】: 直播中快来~\n直播标题：" + message.getLivePush().getTitle();
                                    
                                    MessageChain messageChain = new PlainText(livePushContent + "\n")
                                            .plus(cover)
                                            .plus("\n频道：" + r + "\n时间: " + timeStr);
                                    
                                    Message finalMessage = toNotification(messageChain);
                                    return new Pocket48SenderMessage(false, null, new Message[]{finalMessage});
                                }
                            } else {
                                throw new RuntimeException("图片无法读取");
                            }
                        } catch (Exception conversionException) {
                            throw new RuntimeException("图片格式转换失败: " + conversionException.getMessage());
                        }
                    }
                } catch (Exception e) {
                    
                    // 封面处理失败时，发送纯文本消息（不暴露异常信息给用户）
                    String fallbackContent = "【" + n + "】: 直播中快来~\n直播标题：" + message.getLivePush().getTitle() + "\n频道：" + r + "\n时间: " + timeStr;
                    Message fallbackMessage = toNotification(new PlainText(fallbackContent));
                    return new Pocket48SenderMessage(false, null, new Message[]{fallbackMessage});
                } finally {
                    // 清理临时文件（如果不是缓存文件）
                    if (coverFile != null && coverFile.exists() && !coverFile.getAbsolutePath().contains("cache")) {
                        try {
                            coverFile.delete();
                        } catch (Exception e) {
                            // 静默处理文件删除失败
                        }
                    }
                    
                    // 清理转换后的临时文件
                    if (convertedCoverFile != null && convertedCoverFile.exists()) {
                        try {
                            convertedCoverFile.delete();
                        } catch (Exception e) {
                            // 静默处理文件删除失败
                        }
                    }
                }
            case FLIPCARD:
                String flipContent = "【" + n + "】: 翻牌回复消息\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n" + message.getAnswer().getAnswer() + "\n频道：" + r + "\n时间: " + timeStr;
                return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(flipContent)});
            case FLIPCARD_AUDIO: {
                String flipcardAudioUrl = message.getAnswer().getResInfo();
                if (flipcardAudioUrl == null || flipcardAudioUrl.trim().isEmpty()) {
                    System.err.println("[错误] 翻牌音频URL为空");
                    String errorContent = "【" + n + "】: 翻牌语音消息URL为空\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(errorContent)});
                }
                
                File audioFile = null;
                
                try {
                    // 开始处理翻牌音频
                    
                    // 智能获取翻牌音频文件（优先使用缓存）
                    String audioExt = message.getAnswer().getExt() != null && !message.getAnswer().getExt().isEmpty() ? "." + message.getAnswer().getExt() : ".amr";
                    audioFile = unifiedResourceManager.getResourceSmart(flipcardAudioUrl);
                    
                    // 如果缓存未命中，使用传统下载方式
                    if (audioFile == null) {
                        audioFile = unifiedResourceManager.downloadToTempFile(flipcardAudioUrl, audioExt);
                    }
                    
                    if (audioFile == null || !audioFile.exists()) {
                        throw new RuntimeException("翻牌音频文件获取失败");
                    }
                    
                    // 翻牌音频下载完成
                    
                    // 读取文件头进行格式检测
                    byte[] header = new byte[16];
                    try (InputStream fileStream = java.nio.file.Files.newInputStream(audioFile.toPath())) {
                        int bytesRead = fileStream.read(header);
                        
                        if (bytesRead > 0) {
                            String format = net.luffy.util.AudioFormatDetector.detectFormat(header);
                            String compatibility = net.luffy.util.AudioFormatDetector.getCompatibilityDescription(format);
                            
                            // 记录音频格式信息（仅在调试时使用）
                            
                            if (!net.luffy.util.AudioFormatDetector.isQQCompatible(format)) {
                                // 检测到不兼容格式，尝试转换为AMR格式
                                // 尝试转换音频格式
                                try (InputStream audioInputStream = java.nio.file.Files.newInputStream(audioFile.toPath());
                                     BufferedInputStream bufferedAudioStream = new BufferedInputStream(audioInputStream)) {
                                    InputStream convertedStream = net.luffy.util.AudioFormatConverter.convertToAMR(bufferedAudioStream, format);
                                    if (convertedStream != null) {
                                        // 将转换后的音频保存到新的临时文件
                                        File convertedFile = new File(audioFile.getParent(), "converted_" + audioFile.getName().replaceAll("\\.[^.]+$", ".amr"));
                                        try (FileOutputStream fos = new FileOutputStream(convertedFile)) {
                                            byte[] buffer = new byte[8192];
                                            int bytesReadConv;
                                            while ((bytesReadConv = convertedStream.read(buffer)) != -1) {
                                                fos.write(buffer, 0, bytesReadConv);
                                            }
                                        }
                                        convertedFile.deleteOnExit();
                                        
                                        // 删除原文件，使用转换后的文件
                                        audioFile.delete();
                                        audioFile = convertedFile;
                                        // 翻牌音频格式转换完成
                                    } else {
                                        System.err.println("[翻牌音频转换] 音频格式转换失败，使用原始音频");
                                    }
                                }
                            }
                        } else {
                            System.err.println("[警告] 无法读取翻牌音频文件头，可能是空文件或损坏的文件");
                        }
                    }
                    
                    // 上传音频
                    try (ExternalResource audioResource = ExternalResource.create(audioFile)) {
                        Audio audio = group.uploadAudio(audioResource);
                        // 翻牌音频上传成功
                        String flipAudioContent = "【" + n + "】: 翻牌回复语音\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                        return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(flipAudioContent), audio});
                    }
                } catch (Exception e) {
                    System.err.println("[错误] 处理翻牌音频消息失败: " + e.getMessage());
                    e.printStackTrace();
                    String errorContent = "【" + n + "】: 翻牌语音消息处理失败(" + e.getMessage() + ")\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText(errorContent)});
                } finally {
                    // 清理临时文件
                    if (audioFile != null && audioFile.exists()) {
                        try {
                            audioFile.delete();
                            // 删除临时翻牌音频文件
                        } catch (Exception e) {
                            System.err.println("[警告] 删除临时翻牌音频文件失败: " + e.getMessage());
                        }
                    }
                }
            }
            case FLIPCARD_VIDEO: {
                String videoUrl = message.getAnswer().getResInfo();
                String previewUrl = message.getAnswer().getPreviewImg();
                
                if (videoUrl == null || videoUrl.trim().isEmpty()) {
                    System.err.println("[错误] 翻牌视频URL为空");
                    String errorContent = "【" + n + "】: 翻牌视频消息URL为空\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(errorContent)});
                }
                
                if (previewUrl == null || previewUrl.trim().isEmpty()) {
                    System.err.println("[错误] 翻牌视频预览图URL为空");
                    String errorContent = "【" + n + "】: 翻牌视频预览图URL为空\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(errorContent)});
                }
                
                File videoFile = null;
                File previewFile = null;
                
                try {
                    // 开始处理翻牌视频
                    
                    // 下载视频文件到本地
                    String videoExt = message.getAnswer().getExt() != null && !message.getAnswer().getExt().isEmpty() ? "." + message.getAnswer().getExt() : ".mp4";
                    videoFile = unifiedResourceManager.downloadToTempFile(videoUrl, videoExt);
                    
                    if (videoFile == null || !videoFile.exists()) {
                        throw new RuntimeException("翻牌视频文件下载失败");
                    }
                    
                    // 翻牌视频下载完成
                    
                    // 下载预览图到本地
                    previewFile = unifiedResourceManager.downloadToTempFile(previewUrl, ".jpg");
                    
                    if (previewFile == null || !previewFile.exists()) {
                        throw new RuntimeException("翻牌视频预览图下载失败");
                    }
                    
                    // 翻牌预览图下载完成
                    
                    // 上传视频
                    try (ExternalResource previewResource = ExternalResource.create(previewFile);
                         ExternalResource videoResource = ExternalResource.create(videoFile)) {
                        
                        ShortVideo video = group.uploadShortVideo(previewResource, videoResource,
                                message.getOwnerName() + "翻牌回复视频(" + DateUtil.format(new Date(message.getTime()), "yyyy-MM-dd HH-mm-ss") + ")." + message.getAnswer().getExt());
                        // 翻牌视频上传成功
                        
                        String flipVideoContent = "【" + n + "】: 翻牌回复视频\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                        return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(flipVideoContent), video});
                    }
                } catch (Exception e) {
                    System.err.println("[错误] 处理翻牌视频消息失败: " + e.getMessage());
                    e.printStackTrace();
                    String errorContent = "【" + n + "】: 翻牌视频消息处理失败(" + e.getMessage() + ")\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText(errorContent)});
                } finally {
                    // 清理临时文件
                    if (videoFile != null && videoFile.exists()) {
                        try {
                            videoFile.delete();
                            // 删除临时翻牌视频文件
                        } catch (Exception e) {
                            System.err.println("[警告] 删除临时翻牌视频文件失败: " + e.getMessage());
                        }
                    }
                    if (previewFile != null && previewFile.exists()) {
                        try {
                            previewFile.delete();
                            // 删除临时翻牌预览图文件
                        } catch (Exception e) {
                            System.err.println("[警告] 删除临时翻牌预览图文件失败: " + e.getMessage());
                        }
                    }
                }
            }
            case PASSWORD_REDPACKAGE:
                String redPackageBody = message.getBody() != null ? message.getBody() : "[红包内容为空]";
                String redPackageContent = "【" + n + "】: 发了一个口令红包\n" + redPackageBody + "\n频道：" + r + "\n时间: " + timeStr;
                return new Pocket48SenderMessage(false, null,
                        new Message[]{new PlainText(redPackageContent)});
            case VOTE:
                String voteBody = message.getBody() != null ? message.getBody() : "[投票内容为空]";
                String voteContent = "【" + n + "】: 发起了一个投票\n" + voteBody + "\n频道：" + r + "\n时间: " + timeStr;
                return new Pocket48SenderMessage(false, null,
                        new Message[]{new PlainText(voteContent)});
        }

        return new Pocket48SenderMessage(true, new PlainText("【" + n + "】: 不支持的消息\n频道：" + r + "\n时间: " + timeStr),
                new Message[]{});
    }

    public Message pharsePocketTextWithFace(String body) {
        if (body == null) {
            return new PlainText("[消息内容为空]");
        }
        
        // 快速检查是否包含表情符号，避免不必要的正则处理
        if (!body.contains("[") || !body.contains("]")) {
            return new PlainText(body);
        }
        
        String[] a = body.split("\\[.*?\\]", -1);//其余部分，-1使其产生空字符串
        if (a.length < 2)
            return new PlainText(body);

        Message out = new PlainText(a[0]);
        int count = 1;//从第1个表情后a[1]开始
        Matcher b = Pattern.compile("\\[.*?\\]").matcher(body);
        while (b.find()) {
            out = out.plus(pharsePocketFace(b.group()));
            out = out.plus(a[count]);
            count++;
        }

        return out;
    }

    public Message pharsePocketFace(String face) {
        if (face.equals("[亲亲]"))
            face = "[左亲亲]";

        for (int i = 0; i < Face.names.length; i++) {
            if (Face.names[i].equals(face))
                return new Face(i);
        }
        return new PlainText(face);
    }

    /**
     * 解析口袋表情名称
     * @param message 口袋48消息对象
     * @return 表情显示文本
     */
    private String parseEmotionName(Pocket48Message message) {
        try {
            // 尝试从消息体中解析表情信息
            String body = message.getBody();
            if (body != null && !body.trim().isEmpty()) {
                // 如果body包含JSON格式的表情信息，尝试解析
                if (body.startsWith("{") && body.endsWith("}")) {
                    return parseJsonEmotionName(body);
                }
                // 如果body本身就是表情名称格式
                if (body.startsWith("[") && body.endsWith("]")) {
                    return body;
                }
                // 其他情况，包装为表情格式
                return "[" + body + "]";
            }
            
            // 如果没有body信息，尝试从其他字段获取表情信息
            String fallbackName = getEmotionFallbackName(message);
            return "[" + fallbackName + "]";
            
        } catch (Exception e) {
            // 异常情况下返回默认表情文本
            return "[表情]";
        }
    }
    
    /**
     * 从JSON格式的body中解析表情名称
     * 使用优化的JSON解析器提高性能
     * @param jsonBody JSON格式的消息体
     * @return 表情名称
     */
    private String parseJsonEmotionName(String jsonBody) {
        // 使用优化的表情名称解析方法
        return net.luffy.util.JsonOptimizer.parseEmotionNameOptimized(jsonBody);
    }
    
    /**
     * 获取表情的备用名称
     * @param message 消息对象
     * @return 备用表情名称
     */
    private String getEmotionFallbackName(Pocket48Message message) {
        // 可以根据消息的其他属性来推断表情类型
        // 例如根据时间、用户等信息
        if (message.getTime() > 0) {
            // 根据时间判断可能的表情类型
            long hour = (message.getTime() / (1000 * 60 * 60)) % 24;
            if (hour >= 6 && hour < 12) {
                return "早安表情";
            } else if (hour >= 12 && hour < 18) {
                return "午安表情";
            } else if (hour >= 18 && hour < 24) {
                return "晚安表情";
            } else {
                return "夜间表情";
            }
        }
        return "表情";
    }

    /**
     * 重写getRes方法，统一使用Pocket48UnifiedResourceManager处理所有资源链接
     * @param resLoc 资源链接
     * @return 资源输入流
     */
    @Override
    public InputStream getRes(String resLoc) {
        try {
            // 统一使用口袋48资源处理器处理所有链接
            return unifiedResourceManager.getPocket48InputStreamWithRetry(resLoc, 3);
        } catch (Exception e) {
            System.err.println("获取资源失败，尝试使用默认方式: " + e.getMessage());
            // 如果专用处理器失败，回退到父类的默认处理方式
            try {
                return super.getRes(resLoc);
            } catch (Exception fallbackEx) {
                System.err.println("默认方式也失败: " + fallbackEx.getMessage());
                throw new RuntimeException("无法获取资源: " + resLoc, fallbackEx);
            }
        }
    }

    /**
     * 发送消息列表（顺序保证版）- 确保消息按时间顺序发送的同时维持实时性
     * 核心改进：按时间戳严格排序，使用队列机制确保顺序发送
     * @param messages 消息列表
     * @param group 目标群组
     */
    public void sendMessages(List<Pocket48Message> messages, Group group) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        // 按时间戳严格排序，确保消息顺序
        sendMessagesInOrder(messages, group);
        
        long endTime = System.currentTimeMillis();
        PerformanceMonitor.getInstance().recordQuery(endTime - startTime);
        
        // 智能缓存清理：基于活跃度和消息数量动态调整
        if (messages.size() > 30) {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            
            // 根据活跃度调整清理策略 - 优化版
            if (activityMonitor.isGlobalActive()) {
                // 活跃期：适度清理，避免过度清理影响性能
                if (monitor.shouldForceCleanup()) {
                    unifiedResourceManager.cleanupExpiredCache(5); // 5分钟内的缓存（延长）
                    // 减少GC调用频率
                    if (monitor.getTotalQueries() % 100 == 0) {
                        System.gc();
                    }
                } else if (monitor.getTotalQueries() % 100 == 0) { // 降低清理频率
                    unifiedResourceManager.cleanupExpiredCache(10); // 10分钟内的缓存（延长）
                }
            } else {
                // 非活跃期：更宽松的清理策略
                if (monitor.shouldForceCleanup()) {
                    unifiedResourceManager.cleanupExpiredCache(15); // 15分钟（延长）
                    // 减少GC调用频率
                    if (monitor.getTotalQueries() % 200 == 0) {
                        System.gc();
                    }
                } else if (monitor.shouldCleanup() && monitor.getTotalQueries() % 200 == 0) { // 降低频率
                    unifiedResourceManager.cleanupExpiredCache(60); // 60分钟
                } else if (monitor.getTotalQueries() % 500 == 0) { // 大幅降低频率
                    unifiedResourceManager.cleanupExpiredCache(120); // 2小时
                }
            }
        }
    }
    
    // 已移除preprocessMessages及相关方法
    // 这些方法会破坏消息的时间顺序，现在改为严格按时间戳排序发送

    /**
     * 按时间顺序发送消息，确保顺序的同时维持实时性
     * 核心策略：严格按时间戳排序，智能批次分组发送
     * @param messages 消息列表
     * @param group 群组
     */
    private void sendMessagesInOrder(List<Pocket48Message> messages, Group group) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        // 智能选择发送策略
        if (messages.size() > 8) {
            // 大量消息：使用批次分组发送
            sendMessagesInBatches(messages, group);
        } else {
            // 少量消息：使用原有的顺序发送
            sendMessagesSequentially(messages, group);
        }
        
        // 清理缓存
        if (messages.size() > 10) {
            unifiedResourceManager.cleanupExpiredCache(60);
        }
    }
    
    /**
     * 智能分批发送：基于消息类型和系统负载优化批次策略
     * @param messages 消息列表
     * @param group 群组
     */
    private void sendMessagesInBatches(List<Pocket48Message> messages, Group group) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        // 按时间戳排序
        messages.sort((m1, m2) -> Long.compare(m1.getTime(), m2.getTime()));
        
        // 智能分批：根据消息类型和数量动态调整
        List<List<Pocket48Message>> batches = createSmartBatches(messages);
        
        // 异步处理所有批次
        CompletableFuture<Void> batchChain = CompletableFuture.completedFuture(null);
        
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            final List<Pocket48Message> batch = batches.get(batchIndex);
            final int currentBatchIndex = batchIndex;
            final boolean isLastBatch = batchIndex == batches.size() - 1;
            
            batchChain = batchChain.thenCompose(v -> {
                return sendBatchWithSmoothing(batch, group, currentBatchIndex)
                    .thenCompose(result -> {
                        if (!isLastBatch) {
                            // 批次间智能延迟：根据批次内容调整
                            long delay = calculateBatchInterval(batch, batches.get(currentBatchIndex + 1));
                            return unifiedDelayService.delayAsync((int) delay);
                        }
                        return CompletableFuture.completedFuture(null);
                    });
            });
        }
    }
    
    /**
     * 创建智能批次：根据消息类型分组
     * @param messages 消息列表
     * @return 批次列表
     */
    private List<List<Pocket48Message>> createSmartBatches(List<Pocket48Message> messages) {
        List<List<Pocket48Message>> batches = new ArrayList<>();
        List<Pocket48Message> currentBatch = new ArrayList<>();
        
        int textCount = 0;
        int mediaCount = 0;
        
        for (Pocket48Message message : messages) {
            boolean isText = isTextMessage(message);
            
            // 批次规则：
            // 1. 文本消息：最多8条一批
            // 2. 媒体消息：最多3条一批
            // 3. 混合批次：最多5条一批
            boolean shouldStartNewBatch = false;
            
            if (isText) {
                textCount++;
                if (textCount > 8 || (mediaCount > 0 && currentBatch.size() >= 5)) {
                    shouldStartNewBatch = true;
                }
            } else {
                mediaCount++;
                if (mediaCount > 3 || currentBatch.size() >= 5) {
                    shouldStartNewBatch = true;
                }
            }
            
            if (shouldStartNewBatch && !currentBatch.isEmpty()) {
                batches.add(new ArrayList<>(currentBatch));
                currentBatch.clear();
                textCount = isText ? 1 : 0;
                mediaCount = isText ? 0 : 1;
            }
            
            currentBatch.add(message);
        }
        
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        
        return batches;
    }
    
    /**
     * 计算批次间延迟
     * @param currentBatch 当前批次
     * @param nextBatch 下一批次
     * @return 延迟时间（毫秒）
     */
    private long calculateBatchInterval(List<Pocket48Message> currentBatch, List<Pocket48Message> nextBatch) {
        boolean currentHasMedia = currentBatch.stream().anyMatch(m -> !isTextMessage(m));
        boolean nextHasMedia = nextBatch.stream().anyMatch(m -> !isTextMessage(m));
        
        // 优化后的批次间延迟策略（实时性能优化）：
        // 文本到文本：5ms
        // 文本到媒体：15ms
        // 媒体到文本：10ms
        // 媒体到媒体：20ms
        if (!currentHasMedia && !nextHasMedia) {
            return 5;
        } else if (!currentHasMedia && nextHasMedia) {
            return 15;
        } else if (currentHasMedia && !nextHasMedia) {
            return 10;
        } else {
            return 20;
        }
    }
    
    /**
     * 批次内智能平滑发送
     * @param batch 批次消息
     * @param group 群组
     * @param batchIndex 批次索引
     */
    private CompletableFuture<Void> sendBatchWithSmoothing(List<Pocket48Message> batch, Group group, int batchIndex) {
        return sendBatchWithSmoothingAsync(batch, group, 0);
    }
    
    private CompletableFuture<Void> sendBatchWithSmoothingAsync(List<Pocket48Message> batch, Group group, int index) {
        if (index >= batch.size()) {
            return CompletableFuture.completedFuture(null);
        }
        
        try {
            Pocket48Message message = batch.get(index);
            Pocket48Message nextMessage = (index < batch.size() - 1) ? batch.get(index + 1) : null;
            
            // 根据消息类型选择处理策略
            Pocket48SenderMessage senderMessage;
            if (isTextMessage(message)) {
                senderMessage = pharseMessageFast(message, group, false);
            } else {
                senderMessage = pharseMessage(message, group, false);
            }
            
            // 立即发送处理完的消息
            if (senderMessage != null) {
                sendSingleMessageOptimized(senderMessage, group);
            }
            
            // 批次内消息间的智能延迟
            if (index < batch.size() - 1) {
                long delay = calculateOrderedSendDelay(message, nextMessage, batch.size(), index);
                return unifiedDelayService.delayAsync((int) delay)
                    .thenCompose(v -> sendBatchWithSmoothingAsync(batch, group, index + 1));
            } else {
                return CompletableFuture.completedFuture(null);
            }
            
        } catch (Exception e) {
            logger.warn("Pocket48Sender", "批次消息处理失败: " + e.getMessage());
            // 继续处理下一条消息，不中断整个批次
            if (index < batch.size() - 1) {
                return unifiedDelayService.delayAsync(100)
                    .thenCompose(v -> sendBatchWithSmoothingAsync(batch, group, index + 1));
            }
            return CompletableFuture.completedFuture(null);
        }
    }
    
    /**
     * 顺序发送消息（原有逻辑，用于少量消息）
     * @param messages 消息列表
     * @param group 群组
     */
    private void sendMessagesSequentially(List<Pocket48Message> messages, Group group) {
        // 按时间戳严格排序，确保消息顺序
        messages.sort((m1, m2) -> Long.compare(m1.getTime(), m2.getTime()));
        
        // 异步处理机制：避免消息过多时排队延迟累积
        CompletableFuture<Void> previousTask = CompletableFuture.completedFuture(null);
        
        for (int i = 0; i < messages.size(); i++) {
            final int index = i;
            final Pocket48Message message = messages.get(i);
            final Pocket48Message nextMessage = (i < messages.size() - 1) ? messages.get(i + 1) : null;
            
            // 链式异步处理：每条消息处理完立即发送，避免排队累积
            previousTask = previousTask.thenCompose(v -> 
                CompletableFuture.supplyAsync(() -> {
                    try {
                        // 根据消息类型选择处理策略
                        Pocket48SenderMessage senderMessage;
                        if (isTextMessage(message)) {
                            // 文本消息：快速处理和发送
                            senderMessage = pharseMessageFast(message, group, false);
                        } else {
                            // 媒体消息：完整处理但保持顺序
                            senderMessage = pharseMessage(message, group, false);
                        }
                        
                        // 立即发送处理完的消息
                        if (senderMessage != null) {
                            sendSingleMessage(senderMessage, group);
                        }
                        
                        return senderMessage;
                    } catch (Exception e) {
                        System.err.println("[警告] 消息处理失败: " + e.getMessage());
                        return null;
                    }
                })
            ).thenCompose(senderMessage -> {
                // 使用GroupRateLimiter进行速率限制
                String groupId = String.valueOf(group.getId());
                long waitTime = rateLimiter.getWaitTimeMs(groupId);
                
                if (waitTime > 0) {
                    // 记录队列等待度量
                    if (DelayConfig.getInstance().isMetricsCollectionEnabled()) {
                        DelayMetricsCollector.getInstance().recordQueueStatus(0, waitTime); // 使用默认队列深度0
                    }
                    // 使用UnifiedDelayService进行异步延迟
                    return unifiedDelayService.delayAsync((int) waitTime, "rate_limit");
                } else if (nextMessage != null) {
                    // 使用DelayPolicy计算延迟
                    boolean isMedia = !isTextMessage(nextMessage);
                    long delay = delayPolicy.calculateSendDelay(isMedia);
                    if (delay > 0) {
                        return unifiedDelayService.delayAsync(delay, isMedia ? "media" : "text");
                    }
                }
                return CompletableFuture.completedFuture(null);
            });
        }
        
        // 等待所有消息处理完成，但不阻塞主线程过久
        try {
            previousTask.get(60, TimeUnit.SECONDS); // 最多等待60秒，给重试更多时间
        } catch (Exception e) {
            System.err.println("[警告] 消息发送超时或异常: " + e.getMessage());
        }
    }
    
    /**
     * 判断是否为文本类消息
     * @param message 消息对象
     * @return 是否为文本消息
     */
    private boolean isTextMessage(Pocket48Message message) {
        if (message == null || message.getType() == null) {
            return false;
        }
        
        String type = message.getType().toString();
        return "TEXT".equals(type) ||
               "GIFT_TEXT".equals(type) ||
               "FLIPCARD".equals(type) ||
               "PASSWORD_REDPACKAGE".equals(type) ||
               "VOTE".equals(type) ||
               "REPLY".equals(type) ||
               "GIFTREPLY".equals(type);
    }
    
    /**
     * 超级优化的文本消息处理：文本消息间0延迟发送
     * @param textMessages 文本消息列表
     * @param group 目标群组
     */
    private void sendTextMessagesSync(List<Pocket48Message> textMessages, Group group) {
        // 文本消息使用0延迟，实现最快发送
        int baseInterval = 0; // 文本消息间完全无延迟
        
        for (int i = 0; i < textMessages.size(); i++) {
            try {
                Pocket48Message message = textMessages.get(i);
                
                // 直接同步处理文本消息，避免异步等待开销
                Pocket48SenderMessage senderMessage = pharseMessage(message, group, false);
                
                if (senderMessage != null) {
                    // 立即发送消息
                    sendSingleMessage(senderMessage, group);
                    
                    // 文本消息间无延迟，实现连续发送
                    // if (i < textMessages.size() - 1) {
                    //     delayAsync(baseInterval); // 0延迟，不需要等待
                    // }
                }
                
            } catch (Exception e) {
                // 静默处理单条消息的错误，继续处理下一条
                System.err.println("[警告] 处理文本消息失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 同步发送媒体消息（优化版）- 直接同步处理，避免异步等待
     * @param mediaMessages 媒体消息列表
     * @param group 目标群组
     */
    private void sendMediaMessagesSync(List<Pocket48Message> mediaMessages, Group group) {
        long baseInterval = calculateSendInterval(mediaMessages.size(), true);
        
        for (int i = 0; i < mediaMessages.size(); i++) {
            try {
                Pocket48Message message = mediaMessages.get(i);
                
                // 直接同步处理媒体消息，避免异步等待开销
                Pocket48SenderMessage senderMessage = pharseMessage(message, group, false);
                
                if (senderMessage != null) {
                    // 立即发送消息
                    sendSingleMessage(senderMessage, group);
                    
                    // 移除延迟配置，无延迟发送
                    // 无延迟发送
                }
                
            } catch (Exception e) {
                // 静默处理单条消息的错误，继续处理下一条
                System.err.println("[警告] 处理媒体消息失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 智能发送间隔计算 - 基于消息类型和系统负载动态调整 - 优化版本
     * @param messageCount 消息数量
     * @param isMediaMessage 是否为媒体消息
     * @return 延迟时间（毫秒）
     */
    private long calculateSendInterval(int messageCount, boolean isMediaMessage) {
        // 直接返回最小延迟，移除所有复杂的计算逻辑
        return isMediaMessage ? 10 : 5; // 媒体消息10ms，文本消息5ms
    }
    
    /**
     * 智能有序发送延迟计算 - 基于消息类型、时间间隔和系统状态
     * @param currentMessage 当前消息
     * @param nextMessage 下一条消息
     * @param totalMessageCount 总消息数量
     * @param currentIndex 当前消息索引
     * @return 延迟毫秒数
     */
    private long calculateOrderedSendDelay(Pocket48Message currentMessage, Pocket48Message nextMessage, int totalMessageCount, int currentIndex) {
        // 直接返回最小延迟，移除所有复杂的计算逻辑
        return 5; // 最小5ms延迟
    }
    
    /**
     * 智能延迟计算：根据消息类型动态调整延迟
     * @param currentMessage 当前消息
     * @param nextMessage 下一条消息
     * @return 延迟时间（毫秒）
     */

    
    /**
     * 发送单条处理后的消息 - 优化版本：智能重试和错误处理
     * @param senderMessage 处理后的消息
     * @param group 目标群组
     */
    public void sendSingleMessage(Pocket48SenderMessage senderMessage, Group group) {
        sendSingleMessageOptimized(senderMessage, group);
    }
    
    /**
     * 优化的单消息发送实现
     * @param senderMessage 处理后的消息
     * @param group 目标群组
     */
    private void sendSingleMessageOptimized(Pocket48SenderMessage senderMessage, Group group) {
        if (senderMessage == null || senderMessage.getUnjointMessage() == null) {
            return;
        }
        
        Message[] unjointMessages = senderMessage.getUnjointMessage();
        DelayConfig config = DelayConfig.getInstance();
        
        // 并行发送消息部分（如果有多个部分）
        if (unjointMessages.length == 1) {
            // 单部分消息：直接发送
            sendMessageWithRetryAsync(unjointMessages[0], group, config.getMaxRetries());
        } else {
            // 多部分消息：串行发送保证顺序，但减少延迟
            CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
            
            for (int i = 0; i < unjointMessages.length; i++) {
                final Message message = unjointMessages[i];
                final boolean isLast = i == unjointMessages.length - 1;
                
                sendChain = sendChain.thenCompose(v -> {
                    return sendMessageWithRetryAsync(message, group, config.getMaxRetries())
                        .thenCompose(result -> {
                            // 消息部分间最小延迟（仅在非最后一部分时）- 优化版本
                            if (!isLast) {
                                // 根据系统负载动态调整部分间延迟
                                double loadFactor = Math.max(0.5, Math.min(2.0, config.getCurrentSystemLoad() + 0.5)); // 转换为0.5-2.0的调整因子
                                long partDelay = Math.max(3, (long)(5 * loadFactor)); // 减少到5ms基础延迟，最小3ms
                                return unifiedDelayService.delayAsync(partDelay);
                            }
                            return CompletableFuture.completedFuture(null);
                        });
                });
            }
        }
    }
    
    /**
     * 带重试机制的消息发送方法
     * @param message 要发送的消息
     * @param group 目标群组
     * @param maxRetries 最大重试次数
     */
    private CompletableFuture<Void> sendMessageWithRetryAsync(Message message, Group group, int maxRetries) {
        return sendMessageWithRetryAsync(message, group, maxRetries, 1);
    }
    
    private CompletableFuture<Void> sendMessageWithRetryAsync(Message message, Group group, int maxRetries, int attempt) {
        return CompletableFuture.runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();
                
                // 应用速率限制 - 优化版本
                if (!rateLimiter.tryAcquire(String.valueOf(group.getId()))) {
                    long waitTime = rateLimiter.getWaitTimeMs(String.valueOf(group.getId()));
                    // 根据系统负载动态调整最大等待时间
                    DelayConfig delayConfig = DelayConfig.getInstance();
                    double loadFactor = Math.max(0.5, Math.min(2.0, delayConfig.getCurrentSystemLoad() + 0.5)); // 转换为0.5-2.0的调整因子
                    long maxWaitTime = (long)(3000 * loadFactor); // 基础3秒，根据负载调整
                    
                    if (waitTime > 0 && waitTime < maxWaitTime) {
                        Thread.sleep(waitTime);
                    } else if (waitTime >= maxWaitTime) {
                        // 等待时间过长时，使用较短的固定延迟避免长时间阻塞
                        Thread.sleep(Math.min(1000, maxWaitTime / 2));
                    }
                }
                
                group.sendMessage(message);
                
                // 记录发送成功度量
                DelayConfig config = DelayConfig.getInstance();
                if (config.isMetricsCollectionEnabled()) {
                    long sendTime = System.currentTimeMillis() - startTime;
                    DelayMetricsCollector.getInstance().recordSendRate("message", 1);
                    metricsCollector.recordDownloadSuccess(sendTime);
                    if (attempt > 1) {
                        DelayMetricsCollector.getInstance().recordRetryResult(true);
                    }
                }
                
                logger.debug("Pocket48Sender", "消息发送成功，耗时: " + (System.currentTimeMillis() - startTime) + "ms");
                
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).exceptionallyCompose(throwable -> {
            Exception e = (Exception) throwable.getCause();
            String errorMsg = e != null ? e.getMessage() : "未知错误";
            
            // 增强的错误分类
            boolean isRetryableError = isRetryableError(errorMsg);
            boolean isRateLimitError = isRateLimitError(errorMsg);
            
            // 记录错误度量
            metricsCollector.recordDownloadFailure("message_send_failed");
            
            if (attempt >= maxRetries || (!isRetryableError && !isRateLimitError)) {
                logger.error("Pocket48Sender", "发送消息失败（已重试" + attempt + "次）: " + errorMsg);
                return CompletableFuture.completedFuture(null);
            }
            
            // 智能重试延迟计算
            String retryReason = isRateLimitError ? "rate_limit" : 
                               isRetryableError ? "network_error" : "unknown_error";
            long delay = calculateSmartRetryDelay(attempt - 1, retryReason, isRateLimitError);
            
            // 记录重试结果
            DelayConfig config = DelayConfig.getInstance();
            if (config.isMetricsCollectionEnabled()) {
                DelayMetricsCollector.getInstance().recordRetryResult(false);
            }
            
            logger.warn("Pocket48Sender", String.format("消息发送失败，准备重试 %d/%d，延迟 %dms，原因: %s", 
                attempt, maxRetries, delay, retryReason));
            
            return unifiedDelayService.delayWithExponentialBackoff(delay, attempt - 1, 30000, retryReason)
                .thenCompose(v -> sendMessageWithRetryAsync(message, group, maxRetries, attempt + 1));
        });
    }
    
    /**
     * 判断是否为可重试错误
     */
    private boolean isRetryableError(String errorMsg) {
        return StringMatchUtils.isRetryableError(errorMsg);
    }
    
    /**
     * 判断是否为限流错误
     */
    private boolean isRateLimitError(String errorMsg) {
        return StringMatchUtils.isRateLimitError(errorMsg);
    }
    
    /**
     * 智能重试延迟计算 - 优化版本（禁用延迟）
     */
    private long calculateSmartRetryDelay(int retryCount, String retryReason, boolean isRateLimit) {
        // 直接返回最小延迟，不进行复杂计算
        return 10; // 最小10ms延迟，几乎无延迟
    }
    
    /**
     * 同步版本的重试发送（保持向后兼容）
     * @deprecated 建议使用 sendMessageWithRetryAsync 避免阻塞
     */
    @Deprecated
    private void sendMessageWithRetry(Message message, Group group, int maxRetries) {
        sendMessageWithRetryAsync(message, group, maxRetries)
            .exceptionally(throwable -> {
                System.err.println("[警告] 异步重试发送失败: " + throwable.getMessage());
                return null;
            });
    }

    /**
     * 异步延迟方法，使用统一延迟服务
     * @param delayMs 延迟时间（毫秒）
     * @return CompletableFuture用于异步等待
     */


    /**
     * 根据URL推断图片文件扩展名
     * @param url 图片URL
     * @return 推断的文件扩展名（包含点号）
     */
    private String inferImageExtensionFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return ".jpg"; // 默认扩展名
        }
        
        // 移除查询参数
        String cleanUrl = url.split("\\?")[0].toLowerCase();
        
        // 从URL路径推断扩展名 - 优先检查文件名中的扩展名
        int lastSlash = cleanUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < cleanUrl.length() - 1) {
            String fileName = cleanUrl.substring(lastSlash + 1);
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0 && lastDot < fileName.length() - 1) {
                String ext = fileName.substring(lastDot).toLowerCase();
                // 验证是否为有效的图片扩展名
                if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png") || 
                    ext.equals(".gif") || ext.equals(".bmp") || ext.equals(".webp")) {
                    return ext.equals(".jpeg") ? ".jpg" : ext;
                }
            }
        }
        
        // 如果文件名中没有有效扩展名，则从URL路径中查找扩展名关键词
        if (cleanUrl.contains(".jpg") || cleanUrl.contains(".jpeg")) {
            return ".jpg";
        } else if (cleanUrl.contains(".png")) {
            return ".png";
        } else if (cleanUrl.contains(".gif")) {
            return ".gif";
        } else if (cleanUrl.contains(".bmp")) {
            return ".bmp";
        } else if (cleanUrl.contains(".webp")) {
            return ".webp";
        } else if (cleanUrl.contains("/image/") || cleanUrl.contains("cover") || cleanUrl.contains("thumb") ||
                   cleanUrl.contains("emotion") || cleanUrl.contains("express") || cleanUrl.contains("emoji") ||
                   cleanUrl.contains("/img/") || cleanUrl.contains("live")) {
            // 如果URL路径包含图片相关关键词（包括表情和直播封面），默认为jpg
            return ".jpg";
        }
        
        // 默认返回jpg扩展名
        return ".jpg";
    }
    
    /**
     * 根据检测到的图片格式获取正确的文件扩展名
     * @param detectedFormat 检测到的图片格式
     * @return 正确的文件扩展名
     */
    private String getCorrectExtension(String detectedFormat) {
        if (detectedFormat == null || "UNKNOWN".equals(detectedFormat)) {
            return ".jpg"; // 默认为jpg
        }
        
        switch (detectedFormat.toUpperCase()) {
            case "JPEG":
            case "JPG":
                return ".jpg";
            case "PNG":
                return ".png";
            case "GIF":
                return ".gif";
            case "BMP":
                return ".bmp";
            case "WEBP":
                return ".webp";
            default:
                return ".jpg"; // 默认为jpg
        }
    }
    
    /**
     * 带重试机制的图片上传方法，解决网络超时问题
     * @param resource 图片资源
     * @param maxRetries 最大重试次数
     * @return 上传的图片对象
     * @throws RuntimeException 上传失败时抛出异常
     */
    private Image uploadImageWithRetry(ExternalResource resource, int maxRetries) {
        try {
            return uploadImageWithRetryAsync(resource, maxRetries).get();
        } catch (Exception e) {
            throw new RuntimeException("图片上传失败", e);
        }
    }
    
    private CompletableFuture<Image> uploadImageWithRetryAsync(ExternalResource resource, int maxRetries) {
        return uploadImageWithRetryAsync(resource, maxRetries, 0, null);
    }
    
    private CompletableFuture<Image> uploadImageWithRetryAsync(ExternalResource resource, int maxRetries, int retryCount, Exception lastException) {
        java.util.logging.Logger uploadLogger = java.util.logging.Logger.getLogger("Pocket48Sender.Upload");
        uploadLogger.setUseParentHandlers(false); // 不在控制台显示日志
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 尝试上传图片
                uploadLogger.info("尝试上传图片，第" + (retryCount + 1) + "次");
                Image result = group.uploadImage(resource);
                uploadLogger.info("图片上传成功");
                return result;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).handle((result, throwable) -> {
            if (throwable == null) {
                return CompletableFuture.completedFuture(result);
            }
            
            Exception currentException = (Exception) throwable.getCause();
            if (retryCount < maxRetries) {
                // 使用DelayPolicy的指数退避延迟
                long delayMs = delayPolicy.calculateRetryDelay(retryCount);
                uploadLogger.warning("图片上传失败，第" + (retryCount + 1) + "次重试，延迟" + delayMs + "ms: " + currentException.getMessage());
                
                return unifiedDelayService.delayAsync(delayMs)
                    .thenCompose(v -> uploadImageWithRetryAsync(resource, maxRetries, retryCount + 1, currentException));
            } else {
                uploadLogger.severe("图片上传失败，已达到最大重试次数: " + currentException.getMessage());
                CompletableFuture<Image> failedFuture = new CompletableFuture<>();
                failedFuture.completeExceptionally(new RuntimeException("图片上传失败，重试" + maxRetries + "次后仍然失败", currentException));
                return failedFuture;
            }
        }).thenCompose(future -> future);
    }
    
    /**
     * 自适应延迟调节：根据活跃度和系统负载动态调整延迟 - 优化版本
     * @param baseDelay 基础延迟时间
     * @return 调整后的延迟时间
     */
    private int getAdaptiveDelay(int baseDelay) {
        DelayConfig config = DelayConfig.getInstance();
         boolean isActive = activityMonitor.isGlobalActive();
         double loadFactor = Math.max(0.5, Math.min(2.0, config.getCurrentSystemLoad() + 0.5)); // 转换为0.5-2.0的调整因子
        
        // 综合考虑活跃度和系统负载
        double adjustmentFactor = loadFactor;
        
        if (isActive) {
            // 活跃期：根据负载适度调整，避免刷屏
            adjustmentFactor *= (loadFactor > 1.0) ? 1.15 : 1.05; // 高负载时更保守
        } else {
            // 非活跃期：可以更积极地加快发送
            adjustmentFactor *= (loadFactor < 0.8) ? 0.7 : 0.85; // 低负载时更激进
        }
        
        // 确保延迟在合理范围内
        int adjustedDelay = (int)(baseDelay * adjustmentFactor);
        return Math.max(3, Math.min(adjustedDelay, baseDelay * 2)); // 最小3ms，最大不超过基础延迟的2倍
    }
    
    /**
     * 用户名脱敏处理
     * @param userName 原始用户名
     * @return 脱敏后的用户名
     */
    private String sanitizeUserName(String userName) {
        if (userName == null || userName.trim().isEmpty()) {
            return "未知用户";
        }
        
        // 移除敏感信息，保留显示效果
        String sanitized = userName.trim();
        
        // 如果用户名过长，进行截断
        if (sanitized.length() > 20) {
            sanitized = sanitized.substring(0, 17) + "...";
        }
        
        return sanitized;
    }
    
    /**
     * 创建媒体资源失败时的文本占位消息
     * @param message 原始消息
     * @param errorReason 失败原因
     * @return 占位消息内容
     */
    private Pocket48SenderMessage createMediaFallbackMessage(Pocket48Message message, String errorReason) {
        String nickName = message.getNickName() != null ? message.getNickName() : "未知用户";
        String n = sanitizeUserName(nickName);
        String r = (message.getRoom() != null && message.getRoom().getRoomName() != null) ? message.getRoom().getRoomName() : "未知频道";
        String timeStr = cn.hutool.core.date.DateUtil.format(new java.util.Date(message.getTime()), "yyyy-MM-dd HH:mm:ss");
        
        String mediaType = "";
        switch (message.getType()) {
            case IMAGE:
                mediaType = "图片";
                break;
            case AUDIO:
                mediaType = "语音";
                break;
            case VIDEO:
                mediaType = "视频";
                break;
            default:
                mediaType = "媒体";
        }
        
        String fallbackContent = "【" + n + "】: [" + mediaType + "加载失败: " + errorReason + "]\n频道：" + r + "\n时间: " + timeStr;
        
        logger.warn("Pocket48Sender", "媒体资源降级: " + mediaType + ", 原因: " + errorReason);
        metricsCollector.recordError("media_fallback_" + message.getType().toString().toLowerCase());
        
        return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(fallbackContent)});
    }
    
    /**
     * 检查Content-Type是否在白名单中
     * @param contentType HTTP响应的Content-Type
     * @param expectedTypes 期望的类型列表
     * @return 是否匹配
     */
    private boolean isContentTypeAllowed(String contentType, String... expectedTypes) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return false;
        }
        
        String normalizedType = contentType.toLowerCase().trim();
        for (String expectedType : expectedTypes) {
            if (normalizedType.startsWith(expectedType.toLowerCase())) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 异步重试创建缓存
     * @param roomID 房间ID
     * @param endTime 结束时间映射
     */
    private void createCacheWithAsyncRetry(long roomID, HashMap<Long, Long> endTime) {
        int maxRetries = 2;
        long[] retryDelays = {500, 1500}; // 0.5秒、1.5秒
        
        createCacheWithAsyncRetryInternal(roomID, endTime, 0, maxRetries, retryDelays);
    }
    
    /**
     * 异步重试创建缓存的内部实现
     * @param roomID 房间ID
     * @param endTime 结束时间映射
     * @param attempt 当前尝试次数
     * @param maxRetries 最大重试次数
     * @param retryDelays 重试延迟数组
     */
    private void createCacheWithAsyncRetryInternal(long roomID, HashMap<Long, Long> endTime, 
                                                   int attempt, int maxRetries, long[] retryDelays) {
        if (attempt >= maxRetries) {
            System.err.println(String.format("[错误] 房间 %d 缓存创建最终失败，跳过此房间", roomID));
            return;
        }
        
        // 如果是第一次尝试，直接执行；否则延迟执行
        if (attempt == 0) {
            executeCreateCache(roomID, endTime, attempt, maxRetries, retryDelays);
        } else {
            // 使用异步延迟
            delayExecutor.schedule(() -> {
                executeCreateCache(roomID, endTime, attempt, maxRetries, retryDelays);
            }, retryDelays[attempt - 1], TimeUnit.MILLISECONDS);
        }
    }
    
    /**
     * 执行缓存创建
     * @param roomID 房间ID
     * @param endTime 结束时间映射
     * @param attempt 当前尝试次数
     * @param maxRetries 最大重试次数
     * @param retryDelays 重试延迟数组
     */
    private void executeCreateCache(long roomID, HashMap<Long, Long> endTime, 
                                   int attempt, int maxRetries, long[] retryDelays) {
        try {
            Pocket48SenderCache newCache = Pocket48SenderCache.create(roomID, endTime);
            if (newCache != null) {
                cache.put(roomID, newCache);
                // 缓存创建成功，不需要日志
            } else {
                // 缓存创建失败，继续重试
                throw new RuntimeException("缓存创建返回null");
            }
        } catch (RuntimeException e) {
            System.err.println(String.format("[错误] 房间 %d 缓存创建重试 %d 失败: %s", 
                roomID, attempt + 1, e.getMessage()));
            
            // 继续下一次重试
            createCacheWithAsyncRetryInternal(roomID, endTime, attempt + 1, maxRetries, retryDelays);
        }
    }

    /**
     * 关闭发送器，释放资源
     */
    public void shutdown() {
        logger.info("Pocket48Sender", "开始关闭Pocket48Sender，群组: " + group);
        
        try {
            // 关闭异步处理器
            if (asyncProcessor != null) {
                asyncProcessor.shutdown();
                logger.debug("Pocket48Sender", "异步处理器已关闭");
            }
            
            // 关闭统一资源管理器
            if (unifiedResourceManager != null) {
                unifiedResourceManager.shutdown();
                logger.debug("Pocket48Sender", "统一资源管理器已关闭");
            }
            
            // 关闭媒体队列
            if (mediaQueue != null) {
                mediaQueue.shutdown();
                logger.debug("Pocket48Sender", "媒体队列已关闭");
            }
            
            logger.info("Pocket48Sender", "Pocket48Sender关闭完成，群组: " + group);
            
            // 关闭缓存刷新执行器
            Pocket48SenderCache.shutdownCacheRefreshExecutor();
            
            // 清理消息完整性检查器缓存
            MessageIntegrityChecker.clearAllCache();
            
            // 注意：delayExecutor现在使用统一调度管理器，不需要单独关闭
            // 统一调度管理器将在插件关闭时统一处理
            
            System.out.println("[Pocket48Sender] 资源释放完成");
        } catch (Exception e) {
            logger.error("Pocket48Sender", "关闭Pocket48Sender时发生错误", e);
        }
    }

}
