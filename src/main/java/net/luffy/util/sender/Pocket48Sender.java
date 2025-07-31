package net.luffy.util.sender;

import cn.hutool.core.date.DateUtil;
import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;
import net.luffy.util.sender.Pocket48UnifiedResourceManager;
import net.luffy.util.PerformanceMonitor;
import net.luffy.util.MessageDelayConfig;
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

            //房间消息获取 - 改进的重试机制（优化：减少重试延迟）
            for (long roomID : subscribe.getRoomIDs()) {
                if (!cache.containsKey(roomID)) {
                    cache.put(roomID, Pocket48SenderCache.create(roomID, endTime));
                }
                
                // 智能重试机制：如果创建失败，进行快速重试
                if (cache.get(roomID) == null) {
                    boolean retrySuccess = false;
                    int maxRetries = 2; // 减少重试次数
                    long[] retryDelays = {500, 1500}; // 减少延迟：0.5秒、1.5秒
                    
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
                // 直播封面处理：优化版本，修复无法发送封面的问题
                File coverFile = null;
                File convertedCoverFile = null;
                
                try {
                    String coverUrl = message.getLivePush().getCover();
                    if (coverUrl == null || coverUrl.trim().isEmpty()) {
                        throw new RuntimeException("直播封面URL为空");
                    }
                    
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
                    
                    // 简化的图片处理：直接尝试上传，失败时进行格式转换
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
                                // 创建转换后的文件
                                convertedCoverFile = new File(coverFile.getParent(), "converted_" + 
                                    System.currentTimeMillis() + "_cover.jpg");
                                
                                // 保存为JPEG格式
                                javax.imageio.ImageIO.write(image, "JPEG", convertedCoverFile);
                                
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
    
    // 已移除preprocessMessages及相关方法
    // 这些方法会破坏消息的时间顺序，现在改为严格按时间戳排序发送

    /**
     * 按时间顺序发送消息，确保顺序的同时维持实时性
     * 核心策略：严格按时间戳排序，异步处理避免排队延迟累积
     * @param messages 消息列表
     * @param group 群组
     */
    private void sendMessagesInOrder(List<Pocket48Message> messages, Group group) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        
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
                // 智能延迟：仅在需要时添加延迟，避免阻塞后续消息处理
                 if (nextMessage != null) {
                     int delay = calculateOrderedSendDelay(message, nextMessage, messages.size(), index);
                     if (delay > 0) {
                        return CompletableFuture.runAsync(() -> {
                            try {
                                Thread.sleep(delay);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }, delayExecutor);
                    }
                }
                return CompletableFuture.completedFuture(null);
            });
        }
        
        // 等待所有消息处理完成，但不阻塞主线程过久
        try {
            previousTask.get(30, TimeUnit.SECONDS); // 最多等待30秒
        } catch (Exception e) {
            System.err.println("[警告] 消息发送超时或异常: " + e.getMessage());
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
     * 计算有序发送延迟，平衡顺序保证和实时性
     * 优化：减少不必要延迟，避免消息过多时累积延迟过高
     * @param currentMessage 当前消息
     * @param nextMessage 下一条消息
     * @param totalMessageCount 总消息数量
     * @param currentIndex 当前消息索引
     * @return 延迟毫秒数
     */
    private int calculateOrderedSendDelay(Pocket48Message currentMessage, Pocket48Message nextMessage, int totalMessageCount, int currentIndex) {
        if (currentMessage == null || nextMessage == null) {
            return 20; // 减少默认延迟
        }
        
        // 根据消息类型和时间间隔智能调整延迟
        boolean currentIsText = isTextMessage(currentMessage);
        boolean nextIsText = isTextMessage(nextMessage);
        
        // 计算消息间的时间差
        long timeDiff = nextMessage.getTime() - currentMessage.getTime();
        
        // 优化的基础延迟策略：大幅减少延迟时间
        int baseDelay;
        if (currentIsText && nextIsText) {
            baseDelay = 10; // 文本到文本：极小延迟，优先实时性
        } else if (currentIsText && !nextIsText) {
            baseDelay = 30; // 文本到媒体：减少延迟
        } else if (!currentIsText && nextIsText) {
            baseDelay = 20; // 媒体到文本：快速切换
        } else {
            baseDelay = 50; // 媒体到媒体：适度延迟避免过载
        }
        
        // 根据时间差调整：更激进的优化策略
        if (timeDiff < 500) { // 0.5秒内的消息
            baseDelay += 20; // 轻微增加延迟确保顺序
        } else if (timeDiff > 3000) { // 3秒以上的消息
            baseDelay = Math.max(baseDelay - 15, 5); // 大幅减少延迟
        }
        
        // 动态队列长度调整：消息越多，延迟越小，避免累积延迟
        if (totalMessageCount > 20) {
            // 大量消息时，大幅减少延迟
            baseDelay = Math.max(baseDelay / 3, 2);
        } else if (totalMessageCount > 10) {
            // 中等消息量时，适度减少延迟
            baseDelay = Math.max(baseDelay / 2, 5);
        } else if (totalMessageCount > 5) {
            // 少量消息时，轻微减少延迟
            baseDelay = Math.max(baseDelay * 3 / 4, 8);
        }
        
        // 队列后期加速：越接近队列末尾，延迟越小
        double progressRatio = (double) currentIndex / totalMessageCount;
        if (progressRatio > 0.7) { // 处理到70%后开始加速
            baseDelay = Math.max(baseDelay / 2, 3);
        }
        
        return Math.min(baseDelay, 80); // 最大延迟进一步降低到80ms
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
