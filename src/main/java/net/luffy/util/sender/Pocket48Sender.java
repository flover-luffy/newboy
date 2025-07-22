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
    }

    @Override
    public void run() {
        try {
            Pocket48Subscribe subscribe = Newboy.INSTANCE.getProperties().pocket48_subscribe.get(group_id);
            Pocket48Handler pocket = Newboy.INSTANCE.getHandlerPocket48();

            //房间消息获取
            for (long roomID : subscribe.getRoomIDs()) {
                if (!cache.containsKey(roomID)) {
                    cache.put(roomID, Pocket48SenderCache.create(roomID, endTime));
                }
            }

            List<Pocket48Message[]> totalMessages = new ArrayList<>();

            for (long roomID : subscribe.getRoomIDs()) {
                if (cache.get(roomID) == null)
                    continue;

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
                // 直播封面处理：跳过缓存创建，直接下载图片文件
                File coverFile = null;
                try {
                    String coverUrl = message.getLivePush().getCover();
                    
                    // 直接下载封面图片，不使用缓存（避免.tmp扩展名导致的图片识别问题）
                    coverFile = unifiedResourceManager.downloadToTempFileWithRetry(coverUrl, ".jpg", 3);
                    
                    if (coverFile == null || !coverFile.exists()) {
                        throw new RuntimeException("直播封面下载失败");
                    }
                    
                    try (ExternalResource coverResource = ExternalResource.create(coverFile)) {
                        Image cover = group.uploadImage(coverResource);
                        String livePushContent = "【" + n + "】: 直播中快来~\n直播标题：" + message.getLivePush().getTitle();
                        // 将封面图嵌入到消息链中，确保图片在文字消息内部
                        MessageChain messageChain = new PlainText(livePushContent + "\n").plus(cover).plus("\n频道：" + r + "\n时间: " + timeStr);
                        // 直播推送自动@全体成员（如果机器人有管理权限）
                        MessageChain notificationChain = (MessageChain) toNotification(messageChain);
                        return new Pocket48SenderMessage(false, null, new Message[]{notificationChain});
                    }
                } catch (Exception e) {
                    System.err.println("[错误] 处理直播封面失败: " + e.getMessage());
                    e.printStackTrace();
                    String errorContent = "【" + n + "】: 直播封面处理失败(" + e.getMessage() + ")\n直播标题：" + message.getLivePush().getTitle() + "\n频道：" + r + "\n时间: " + timeStr;
                    // 错误消息也尝试@全体成员
                    Message errorNotification = toNotification(new PlainText(errorContent));
                    return new Pocket48SenderMessage(false, null, new Message[]{errorNotification});
                } finally {
                    // 清理临时文件
                    if (coverFile != null && coverFile.exists()) {
                        try {
                            coverFile.delete();
                        } catch (Exception e) {
                            System.err.println("[警告] 删除临时直播封面文件失败: " + e.getMessage());
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
                
                // 添加发送间隔（除了最后一条消息）
                if (i < messages.size() - 1) {
                    Thread.sleep(baseInterval);
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
     * 同步发送文本消息
     * @param textMessages 文本消息列表
     * @param group 目标群组
     */
    private void sendTextMessagesSync(List<Pocket48Message> textMessages, Group group) {
        int baseInterval = calculateSendInterval(textMessages.size());
        
        for (int i = 0; i < textMessages.size(); i++) {
            try {
                Pocket48Message message = textMessages.get(i);
                
                // 异步处理单条消息
                CompletableFuture<Pocket48SenderMessage> future = 
                    asyncProcessor.processMessageAsync(message, group);
                
                // 等待处理完成并立即发送
                Pocket48SenderMessage senderMessage = future.get(
                    delayConfig.getTextProcessingTimeout(), java.util.concurrent.TimeUnit.SECONDS);
                
                if (senderMessage != null) {
                    // 立即发送消息
                    sendSingleMessage(senderMessage, group);
                    
                    // 添加发送间隔（除了最后一条消息）
                    if (i < textMessages.size() - 1) {
                        Thread.sleep(baseInterval);
                    }
                }
                
            } catch (Exception e) {
                // 静默处理单条消息的错误，继续处理下一条
                System.err.println("[警告] 处理文本消息失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 同步发送媒体消息（备用方案）
     * @param mediaMessages 媒体消息列表
     * @param group 目标群组
     */
    private void sendMediaMessagesSync(List<Pocket48Message> mediaMessages, Group group) {
        int baseInterval = calculateSendInterval(mediaMessages.size());
        
        for (int i = 0; i < mediaMessages.size(); i++) {
            try {
                Pocket48Message message = mediaMessages.get(i);
                
                // 异步处理单条消息
                CompletableFuture<Pocket48SenderMessage> future = 
                    asyncProcessor.processMessageAsync(message, group);
                
                // 等待处理完成并立即发送
                Pocket48SenderMessage senderMessage = future.get(
                    delayConfig.getMediaProcessingTimeout(), java.util.concurrent.TimeUnit.SECONDS);
                
                if (senderMessage != null) {
                    // 立即发送消息
                    sendSingleMessage(senderMessage, group);
                    
                    // 添加发送间隔（除了最后一条消息）
                    if (i < mediaMessages.size() - 1) {
                        Thread.sleep(baseInterval);
                    }
                }
                
            } catch (Exception e) {
                // 静默处理单条消息的错误，继续处理下一条
                System.err.println("[警告] 处理媒体消息失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * 计算消息发送间隔
     * @param messageCount 消息数量
     * @return 发送间隔（毫秒）
     */
    private int calculateSendInterval(int messageCount) {
        // 基础间隔：500ms
        int baseInterval = 500;
        
        // 根据消息数量调整间隔：消息越多，间隔越短，但不低于200ms
        if (messageCount > 10) {
            baseInterval = Math.max(200, 500 - (messageCount - 10) * 20);
        } else if (messageCount > 5) {
            baseInterval = Math.max(300, 500 - (messageCount - 5) * 40);
        }
        
        // 应用CPU负载调整
        return loadBalancer.getDynamicDelay(baseInterval);
    }
    
    /**
     * 发送单条处理后的消息
     * @param senderMessage 处理后的消息
     * @param group 目标群组
     */
    public void sendSingleMessage(Pocket48SenderMessage senderMessage, Group group) {
        try {
            Message[] unjointMessages = senderMessage.getUnjointMessage();
            for (int i = 0; i < unjointMessages.length; i++) {
                group.sendMessage(unjointMessages[i]);
                // 同一消息的多个部分之间的短间隔
                if (i < unjointMessages.length - 1) {
                    Thread.sleep(100); // 100ms间隔
                }
            }
        } catch (Exception e) {
            System.err.println("[错误] 发送消息失败: " + e.getMessage());
        }
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
    }

}
