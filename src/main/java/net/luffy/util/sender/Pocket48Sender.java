package net.luffy.util.sender;

import cn.hutool.core.date.DateUtil;
import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;
import net.luffy.util.sender.Pocket48UnifiedResourceManager;
import net.luffy.util.PerformanceMonitor;
import net.luffy.util.MessageDelayConfig;
// UnifiedClientMigrationHelper已删除，直接使用UnifiedHttpClient
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
    private final MessageDelayConfig delayConfig;
    private final net.luffy.util.CpuLoadBalancer loadBalancer;
    private final Pocket48MediaQueue mediaQueue;
    private final ScheduledExecutorService delayExecutor;

    public Pocket48Sender(Bot bot, long group, HashMap<Long, Long> endTime, HashMap<Long, List<Long>> voiceStatus, HashMap<Long, Pocket48SenderCache> cache) {
        super(bot, group);
        this.endTime = endTime;
        this.voiceStatus = voiceStatus;
        this.cache = cache;
        this.unifiedResourceManager = Pocket48UnifiedResourceManager.getInstance();
        this.asyncProcessor = new Pocket48AsyncMessageProcessor(this);
        this.delayConfig = MessageDelayConfig.getInstance();
        this.loadBalancer = net.luffy.util.CpuLoadBalancer.getInstance();
        this.mediaQueue = new Pocket48MediaQueue();
        this.delayExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "Pocket48-Delay-Executor");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void run() {
        try {
            Pocket48Subscribe subscribe = Newboy.INSTANCE.getProperties().pocket48_subscribe.get(group_id);
            Pocket48Handler pocket = Newboy.INSTANCE.getHandlerPocket48();

            //房间消息获取 - 改进的重试机制
            for (long roomID : subscribe.getRoomIDs()) {
                if (!cache.containsKey(roomID)) {
                    cache.put(roomID, Pocket48SenderCache.create(roomID, endTime));
                }
                
                // 智能重试机制：如果创建失败，进行多次重试
                if (cache.get(roomID) == null) {
                    // 开始重试创建房间缓存
                    
                    boolean retrySuccess = false;
                    int maxRetries = 3;
                    long[] retryDelays = {2000, 5000, 10000}; // 递增延迟：2秒、5秒、10秒
                    
                    for (int attempt = 1; attempt <= maxRetries && !retrySuccess; attempt++) {
                        try {
                            Thread.sleep(retryDelays[attempt-1]);
                            
                            Pocket48SenderCache newCache = Pocket48SenderCache.create(roomID, endTime);
                            cache.put(roomID, newCache);
                            retrySuccess = true;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (RuntimeException e) {
                            // 静默处理缓存创建失败，继续重试
                        }
                    }
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
                    totalMessages.add(a);
                }

                //房间语音
                List<Long> n = cache.get(roomID).voiceList;
                if (voiceStatus.containsKey(roomID)) {
                    String[] r = handleVoiceList(voiceStatus.get(roomID), n);
                    if (r[0] != null || r[1] != null) {
                        String ownerName = Pocket48Handler.getOwnerOrTeamName(roomInfo);
                        boolean private_ = ownerName.equals(roomInfo.getOwnerName());
                        String message = "【" + roomInfo.getRoomName() + "(" + ownerName + ")房间语音】: \n";

                        if (r[0] != null) {
                            message += private_ ?
                                    "上麦啦~" //成员房间
                                    : "★ 上麦：\n" + r[0] + "\n"; //队伍房间
                        }
                        if (r[1] != null) {
                            message += private_ ?
                                    "下麦了捏~"
                                    : "☆ 下麦：\n" + r[1];
                        }
                        Message m = new PlainText(message);
                        group.sendMessage(r[0] != null ? toNotification(m) : m); //有人上麦时才@all
                    }
                }
                voiceStatus.put(roomID, n);
            }

            //房间消息 - 优化：按时间统一排序后逐个发送，避免批量发送造成的延迟
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
                
                // 使用优化的发送方法：按时间顺序逐个发送
                sendMessagesInTimeOrder(allMessages, group);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String[] handleVoiceList(List<Long> a, List<Long> b) {
        String zengjia = "";
        String jianshao = "";
        for (Long b0 : b) {
            if (!a.contains(b0))
                zengjia += "，" + b0;
        }
        for (Long a0 : a) {
            if (!b.contains(a0))
                jianshao += "，" + a0;
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
            return null;
        }
        
        Pocket48Handler pocket = Newboy.INSTANCE.getHandlerPocket48();
        String nickName = message.getNickName() != null ? message.getNickName() : "未知用户";
        String n = nickName; // 去掉明星名显示
        String r = (message.getRoom() != null && message.getRoom().getRoomName() != null) ? message.getRoom().getRoomName() : "未知频道";
        String timeStr = cn.hutool.core.date.DateUtil.format(new java.util.Date(message.getTime()), "yyyy-MM-dd HH:mm:ss");
        String headerTemplate = "【" + n + "】: " + "\n频道：" + r + "\n时间: " + timeStr;

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
                try (ExternalResource imageResource = ExternalResource.create(getRes(message.getResLoc()))) {
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
                    
                    // 尝试获取表情图片资源
                    if (message.getResLoc() != null && !message.getResLoc().trim().isEmpty()) {
                        try (ExternalResource emotionResource = ExternalResource.create(getRes(message.getResLoc()))) {
                            Image emotionImage = group.uploadImage(emotionResource);
                            // 创建包含表情图片的消息链
                            MessageChain messageChain = new PlainText("【" + n + "】: " + emotionName + "\n")
                                    .plus(emotionImage)
                                    .plus("\n频道：" + r + "\n时间: " + timeStr);
                            return new Pocket48SenderMessage(false, null, new Message[]{messageChain});
                        } catch (Exception imageEx) {
                            // 图片加载失败，仅显示文本
                            System.err.println("表情图片加载失败: " + imageEx.getMessage());
                        }
                    }
                    
                    // 如果没有图片资源或图片加载失败，仅显示文本
                    String expressContent = "【" + n + "】: " + emotionName + "\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(expressContent)});
                } catch (Exception e) {
                    // 异常情况下的兜底处理
                    String fallbackContent = "【" + n + "】: [表情]\n频道：" + r + "\n时间: " + timeStr;
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
                // 直播封面处理：启用缓存，优化下载机制，增强格式检测和转换（改进版）
                File coverFile = null;
                File convertedCoverFile = null;
                java.util.logging.Logger livePushLogger = java.util.logging.Logger.getLogger("Pocket48Sender.LIVEPUSH");
                livePushLogger.setUseParentHandlers(false); // 不在控制台显示日志
                
                try {
                    String coverUrl = message.getLivePush().getCover();
                    livePushLogger.info("开始处理直播封面: " + coverUrl);
                    
                    // 优先使用缓存，缓存未命中时下载（启用缓存机制）
                    coverFile = unifiedResourceManager.getResourceSmart(coverUrl);
                    
                    // 如果智能获取失败，使用带重试的下载方式
                    if (coverFile == null || !coverFile.exists()) {
                        livePushLogger.info("缓存未命中，开始下载直播封面");
                        // 根据URL推断文件扩展名
                        String inferredExt = inferImageExtensionFromUrl(coverUrl);
                        coverFile = unifiedResourceManager.downloadToTempFileWithRetry(coverUrl, inferredExt, 5);
                    }
                    
                    if (coverFile == null || !coverFile.exists()) {
                        throw new RuntimeException("直播封面获取失败");
                    }
                    
                    livePushLogger.info("直播封面文件获取成功: " + coverFile.getAbsolutePath() + ", 大小: " + coverFile.length() + " bytes");
                    
                    // 检测图片格式
                    String imageFormat = net.luffy.util.ImageFormatDetector.detectFormat(coverFile);
                    livePushLogger.info("检测到图片格式: " + imageFormat);
                    
                    // 验证图片完整性
                    if (!net.luffy.util.ImageFormatDetector.validateImageIntegrity(coverFile)) {
                        livePushLogger.warning("图片完整性验证失败，尝试重新下载");
                        // 尝试重新下载一次
                        coverFile = unifiedResourceManager.downloadToTempFileWithRetry(coverUrl, ".jpg", 2);
                        if (coverFile == null || !net.luffy.util.ImageFormatDetector.validateImageIntegrity(coverFile)) {
                            throw new RuntimeException("图片下载后仍然损坏");
                        }
                        imageFormat = net.luffy.util.ImageFormatDetector.detectFormat(coverFile);
                    }
                    
                    // 如果图片格式无法识别或不兼容，尝试使用ImageIO读取并重新保存
                    if ("UNKNOWN".equals(imageFormat) || !net.luffy.util.ImageFormatDetector.isQQCompatible(imageFormat)) {
                        livePushLogger.info("图片格式不兼容，开始转换: " + imageFormat + " -> JPEG");
                        try {
                            // 尝试读取图片并转换为兼容格式
                            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(coverFile);
                            if (image != null) {
                                // 创建转换后的文件
                                convertedCoverFile = new File(coverFile.getParent(), "converted_" + 
                                    System.currentTimeMillis() + "_" + 
                                    coverFile.getName().replaceAll("\\.[^.]+$", ".jpg"));
                                
                                // 保存为JPEG格式，设置高质量
                                javax.imageio.ImageWriter writer = javax.imageio.ImageIO.getImageWritersByFormatName("JPEG").next();
                                javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
                                param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                                param.setCompressionQuality(0.9f); // 高质量
                                
                                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(convertedCoverFile);
                                     javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(fos)) {
                                    writer.setOutput(ios);
                                    writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
                                    writer.dispose();
                                }
                                
                                livePushLogger.info("图片转换成功: " + convertedCoverFile.getAbsolutePath());
                                // 使用转换后的文件
                                coverFile = convertedCoverFile;
                                imageFormat = "JPEG";
                            } else {
                                livePushLogger.severe("图片无法读取，可能是损坏的图片或不支持的格式");
                                throw new RuntimeException("图片无法读取");
                            }
                        } catch (Exception e) {
                            livePushLogger.severe("图片格式转换失败: " + e.getMessage());
                            throw new RuntimeException("图片格式转换失败: " + e.getMessage(), e);
                        }
                    }
                    
                    // 最终验证转换后的图片
                    if (!net.luffy.util.ImageFormatDetector.validateImageIntegrity(coverFile)) {
                        throw new RuntimeException("转换后的图片仍然无效");
                    }
                    
                    livePushLogger.info("准备上传图片，最终格式: " + imageFormat);
                    
                    try (ExternalResource coverResource = ExternalResource.create(coverFile)) {
                        // 带重试机制的图片上传，解决网络超时问题
                        Image cover = uploadImageWithRetry(coverResource, 3);
                        String livePushContent = "【" + n + "】: 直播中快来~\n直播标题：" + message.getLivePush().getTitle();
                        
                        // 构建消息链：文本 + 图片 + 补充信息
                        MessageChain messageChain = new PlainText(livePushContent + "\n")
                                .plus(cover)
                                .plus("\n频道：" + r + "\n时间: " + timeStr);
                        
                        // 直播推送自动@全体成员（如果机器人有管理权限）
                        Message finalMessage = toNotification(messageChain);
                        livePushLogger.info("直播封面处理完成，消息发送成功");
                        return new Pocket48SenderMessage(false, null, new Message[]{finalMessage});
                    }
                } catch (Exception e) {
                    livePushLogger.severe("处理直播封面失败: " + e.getMessage());
                    
                    // 封面处理失败时，发送纯文本消息（不暴露异常信息给用户）
                    String fallbackContent = "【" + n + "】: 直播中快来~\n直播标题：" + message.getLivePush().getTitle() + "\n频道：" + r + "\n时间: " + timeStr;
                    Message fallbackMessage = toNotification(new PlainText(fallbackContent));
                    return new Pocket48SenderMessage(false, null, new Message[]{fallbackMessage});
                } finally {
                    // 清理临时文件（如果不是缓存文件）
                    if (coverFile != null && coverFile.exists() && !coverFile.getAbsolutePath().contains("cache")) {
                        try {
                            coverFile.delete();
                            livePushLogger.info("清理临时文件: " + coverFile.getAbsolutePath());
                        } catch (Exception e) {
                            livePushLogger.warning("删除临时直播封面文件失败: " + e.getMessage());
                        }
                    }
                    
                    // 清理转换后的临时文件
                    if (convertedCoverFile != null && convertedCoverFile.exists()) {
                        try {
                            convertedCoverFile.delete();
                            livePushLogger.info("清理转换后的临时文件: " + convertedCoverFile.getAbsolutePath());
                        } catch (Exception e) {
                            livePushLogger.warning("删除转换后的临时直播封面文件失败: " + e.getMessage());
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
     * 发送消息列表（流式处理版）- 解决批量等待导致的延迟问题
     * 改为流式处理：处理完一条立即发送一条，而不是等待所有消息处理完毕
     * @param messages 消息列表
     * @param group 目标群组
     */
    public void sendMessages(List<Pocket48Message> messages, Group group) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        long startTime = System.currentTimeMillis();
        
        // 直接按时间顺序流式处理，避免批量等待
        sendMessagesInTimeOrder(messages, group);
        
        long endTime = System.currentTimeMillis();
        PerformanceMonitor.getInstance().recordQuery(endTime - startTime);
        
        // 智能缓存清理：减少清理频率
        if (messages.size() > 30) {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            
            if (monitor.shouldForceCleanup()) {
                unifiedResourceManager.cleanupExpiredCache(5);
                System.gc();
            } else if (monitor.shouldCleanup() && monitor.getTotalQueries() % 50 == 0) {
                unifiedResourceManager.cleanupExpiredCache(30);
            } else if (monitor.getTotalQueries() % 100 == 0) {
                unifiedResourceManager.cleanupExpiredCache(60);
            }
        }
    }
    
    /**
     * 预处理消息列表，优化发送顺序和合并相似消息
     * @param messages 原始消息列表
     * @return 优化后的消息列表
     */
    private List<Pocket48Message> preprocessMessages(List<Pocket48Message> messages) {
        // 按消息类型分组
        Map<String, List<Pocket48Message>> grouped = new HashMap<>();
        for (Pocket48Message msg : messages) {
            String type = msg.getType() != null ? msg.getType().toString() : "UNKNOWN";
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(msg);
        }
        
        List<Pocket48Message> result = new ArrayList<>();
        
        // 优先处理文本类消息（响应更快）
        addMessagesInOrder(result, grouped, "TEXT");
        addMessagesInOrder(result, grouped, "GIFT_TEXT");
        addMessagesInOrder(result, grouped, "FLIPCARD");
        addMessagesInOrder(result, grouped, "PASSWORD_REDPACKAGE");
        addMessagesInOrder(result, grouped, "VOTE");
        addMessagesInOrder(result, grouped, "REPLY");
        addMessagesInOrder(result, grouped, "GIFTREPLY");
        
        // 然后处理媒体类消息
        addMessagesInOrder(result, grouped, "IMAGE");
        addMessagesInOrder(result, grouped, "EXPRESSIMAGE");
        addMessagesInOrder(result, grouped, "AUDIO");
        addMessagesInOrder(result, grouped, "FLIPCARD_AUDIO");
        addMessagesInOrder(result, grouped, "VIDEO");
        addMessagesInOrder(result, grouped, "FLIPCARD_VIDEO");
        addMessagesInOrder(result, grouped, "LIVEPUSH");
        
        // 添加其他类型的消息
        for (Map.Entry<String, List<Pocket48Message>> entry : grouped.entrySet()) {
            if (!isProcessedType(entry.getKey())) {
                result.addAll(entry.getValue());
            }
        }
        
        return result;
    }
    
    /**
     * 按顺序添加指定类型的消息
     * @param result 结果列表
     * @param grouped 分组的消息
     * @param type 消息类型
     */
    private void addMessagesInOrder(List<Pocket48Message> result, 
                                   Map<String, List<Pocket48Message>> grouped, 
                                   String type) {
        List<Pocket48Message> typeMessages = grouped.get(type);
        if (typeMessages != null && !typeMessages.isEmpty()) {
            result.addAll(typeMessages);
        }
    }
    
    /**
     * 检查消息类型是否已被处理
     * @param type 消息类型
     * @return 是否已处理
     */
    private boolean isProcessedType(String type) {
        return "TEXT".equals(type) ||
               "GIFT_TEXT".equals(type) ||
               "FLIPCARD".equals(type) ||
               "PASSWORD_REDPACKAGE".equals(type) ||
               "VOTE".equals(type) ||
               "REPLY".equals(type) ||
               "GIFTREPLY".equals(type) ||
               "IMAGE".equals(type) ||
               "EXPRESSIMAGE".equals(type) ||
               "AUDIO".equals(type) ||
               "FLIPCARD_AUDIO".equals(type) ||
               "VIDEO".equals(type) ||
               "FLIPCARD_VIDEO".equals(type) ||
               "LIVEPUSH".equals(type);
    }

    /**
     * 按时间顺序发送消息（流式处理版）- 彻底解决批量等待导致的延迟问题
     * 逐条处理并立即发送，最大化降低延迟
     * @param messages 按时间排序的消息列表
     * @param group 目标群组
     */
    private void sendMessagesInTimeOrder(List<Pocket48Message> messages, Group group) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
        int baseInterval = calculateSendInterval(messages.size());
        
        // 流式处理：逐条处理并立即发送
        for (int i = 0; i < messages.size(); i++) {
            Pocket48Message message = messages.get(i);
            
            try {
                // 立即处理当前消息
                Pocket48SenderMessage senderMessage = pharseMessage(message, group, false);
                
                if (senderMessage != null) {
                    // 立即发送处理后的消息
                    sendSingleMessage(senderMessage, group);
                }
                
                // 智能延迟：文本消息几乎无延迟，媒体消息最小延迟
                if (i < messages.size() - 1) {
                    int smartDelay = calculateSmartDelay(message, messages.get(i + 1));
                    if (smartDelay > 0) {
                        delayAsync(smartDelay);
                    }
                }
                
            } catch (Exception e) {
                // 静默处理单条消息的错误，继续处理下一条
                System.err.println("[警告] 处理消息失败: " + e.getMessage());
            }
        }
        
        // 清理缓存
        if (messages.size() > 10) {
            unifiedResourceManager.cleanupExpiredCache(60);
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
        int baseInterval = calculateSendInterval(mediaMessages.size());
        
        for (int i = 0; i < mediaMessages.size(); i++) {
            try {
                Pocket48Message message = mediaMessages.get(i);
                
                // 直接同步处理媒体消息，避免异步等待开销
                Pocket48SenderMessage senderMessage = pharseMessage(message, group, false);
                
                if (senderMessage != null) {
                    // 立即发送消息
                    sendSingleMessage(senderMessage, group);
                    
                    // 添加发送间隔（除了最后一条消息）
                    if (i < mediaMessages.size() - 1) {
                        delayAsync(baseInterval);
                    }
                }
                
            } catch (Exception e) {
                // 静默处理单条消息的错误，继续处理下一条
                System.err.println("[警告] 处理媒体消息失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 计算消息发送间隔 - 超级优化版本：文本消息几乎无延迟
     * @param messageCount 消息数量
     * @return 发送间隔（毫秒）
     */
    private int calculateSendInterval(int messageCount) {
        // 对于文本消息使用极小的基础间隔，接近0延迟
        int baseInterval = Math.max(0, delayConfig.getTextDelay() / 10); // 使用配置值的1/10，允许0延迟
        
        // 根据消息数量调整间隔：消息越多，间隔越短，最小0ms
        int minInterval = 0; // 最小间隔0ms，实现真正的无延迟
        
        if (messageCount > 10) {
            baseInterval = Math.max(minInterval, baseInterval - (messageCount - 10));
        } else if (messageCount > 5) {
            baseInterval = Math.max(minInterval, baseInterval - (messageCount - 5));
        }
        
        // 应用CPU负载调整，但确保文本消息延迟极小
        int dynamicDelay = loadBalancer.getDynamicDelay(baseInterval);
        return Math.max(minInterval, Math.min(dynamicDelay, Math.max(1, baseInterval * 2))); // 最大延迟不超过baseInterval*2，但至少1ms
    }
    
    /**
     * 智能延迟计算：根据消息类型动态调整延迟
     * @param currentMessage 当前消息
     * @param nextMessage 下一条消息
     * @return 延迟时间（毫秒）
     */
    private int calculateSmartDelay(Pocket48Message currentMessage, Pocket48Message nextMessage) {
        // 文本消息之间几乎无延迟
        if (isTextMessage(currentMessage) && isTextMessage(nextMessage)) {
            return 0; // 文本消息间无延迟
        }
        
        // 文本消息到媒体消息：极小延迟
        if (isTextMessage(currentMessage) && !isTextMessage(nextMessage)) {
            return 1; // 1ms延迟
        }
        
        // 媒体消息到文本消息：极小延迟
        if (!isTextMessage(currentMessage) && isTextMessage(nextMessage)) {
            return 1; // 1ms延迟
        }
        
        // 媒体消息之间：使用配置的最小延迟
        return Math.max(1, delayConfig.getMediaDelay() / 4); // 媒体延迟的1/4
    }
    
    /**
     * 发送单条处理后的消息 - 超级优化版本：消息部分间无延迟，带重试机制
     * @param senderMessage 处理后的消息
     * @param group 目标群组
     */
    public void sendSingleMessage(Pocket48SenderMessage senderMessage, Group group) {
        Message[] unjointMessages = senderMessage.getUnjointMessage();
        for (int i = 0; i < unjointMessages.length; i++) {
            sendMessageWithRetry(unjointMessages[i], group, 3);
            // 同一消息的多个部分之间无延迟，实现最快发送
            if (i < unjointMessages.length - 1) {
                // 完全去除延迟，实现真正的无延迟发送
                // delayAsync(0); // 不再需要任何延迟
            }
        }
    }
    
    /**
     * 带重试机制的消息发送方法
     * @param message 要发送的消息
     * @param group 目标群组
     * @param maxRetries 最大重试次数
     */
    private void sendMessageWithRetry(Message message, Group group, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                group.sendMessage(message);
                return; // 发送成功，直接返回
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isRetryableError = errorMsg != null && (
                    errorMsg.contains("rich media transfer failed") ||
                    errorMsg.contains("send_group_msg") ||
                    errorMsg.contains("ActionFailedException") ||
                    errorMsg.contains("EventChecker Failed")
                );
                
                if (attempt == maxRetries || !isRetryableError) {
                    // 静默处理发送失败，不推送错误消息到群组
                    System.err.println("[错误] 发送消息失败（已重试" + attempt + "次）: " + errorMsg);
                    return; // 达到最大重试次数或不可重试的错误，停止重试
                }
                
                // 指数退避重试
                try {
                    long delay = 1000 * (1L << (attempt - 1)); // 1s, 2s, 4s...
                    Thread.sleep(Math.min(delay, 8000)); // 最大延迟8秒
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    // 静默处理中断异常，不推送错误消息到群组
                    System.err.println("[错误] 消息发送重试被中断: " + errorMsg);
                    return;
                }
            }
        }
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
        
        // 从URL路径推断扩展名
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
        } else if (cleanUrl.contains("/image/") || cleanUrl.contains("cover") || cleanUrl.contains("thumb")) {
            // 如果URL路径包含图片相关关键词，默认为jpg
            return ".jpg";
        }
        
        // 默认返回jpg扩展名
        return ".jpg";
    }
    
    /**
     * 带重试机制的图片上传方法，解决网络超时问题
     * @param resource 图片资源
     * @param maxRetries 最大重试次数
     * @return 上传的图片对象
     * @throws RuntimeException 上传失败时抛出异常
     */
    private Image uploadImageWithRetry(ExternalResource resource, int maxRetries) {
        int retryCount = 0;
        Exception lastException = null;
        java.util.logging.Logger uploadLogger = java.util.logging.Logger.getLogger("Pocket48Sender.Upload");
        uploadLogger.setUseParentHandlers(false); // 不在控制台显示日志
        
        while (retryCount <= maxRetries) {
            try {
                // 尝试上传图片
                uploadLogger.info("尝试上传图片，第" + (retryCount + 1) + "次");
                Image result = group.uploadImage(resource);
                uploadLogger.info("图片上传成功");
                return result;
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                
                if (retryCount <= maxRetries) {
                    // 指数退避延迟：1秒、2秒、4秒
                    long delayMs = 1000L * (1L << (retryCount - 1));
                    uploadLogger.warning("图片上传失败，第" + retryCount + "次重试，延迟" + delayMs + "ms: " + e.getMessage());
                    
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("图片上传被中断", ie);
                    }
                } else {
                    uploadLogger.severe("图片上传失败，已达到最大重试次数: " + e.getMessage());
                }
            }
        }
        
        throw new RuntimeException("图片上传失败，重试" + maxRetries + "次后仍然失败", lastException);
    }
    
    /**
     * 关闭发送器，释放资源
     */
    public void shutdown() {
        if (asyncProcessor != null) {
            asyncProcessor.shutdown();
        }
        if (unifiedResourceManager != null) {
            unifiedResourceManager.shutdown();
        }
        if (mediaQueue != null) {
            mediaQueue.shutdown();
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
