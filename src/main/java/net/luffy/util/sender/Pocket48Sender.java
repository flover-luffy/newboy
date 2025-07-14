package net.luffy.util.sender;

import cn.hutool.core.date.DateUtil;
import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;
import net.luffy.util.sender.Pocket48ResourceHandler;
import net.luffy.model.*;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.*;
import net.mamoe.mirai.utils.ExternalResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Pocket48Sender extends Sender {

    //endTime是一个关于roomID的HashMap
    private final HashMap<Long, Long> endTime;
    private final HashMap<Long, List<Long>> voiceStatus;
    private final HashMap<Long, Pocket48SenderCache> cache;
    private final Pocket48ResourceHandler resourceHandler;

    public Pocket48Sender(Bot bot, long group, HashMap<Long, Long> endTime, HashMap<Long, List<Long>> voiceStatus, HashMap<Long, Pocket48SenderCache> cache) {
        super(bot, group);
        this.endTime = endTime;
        this.voiceStatus = voiceStatus;
        this.cache = cache;
        this.resourceHandler = new Pocket48ResourceHandler();
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

            //房间消息 - 移除合并逻辑，直接发送每条消息
            if (totalMessages.size() > 0) {
                for (Pocket48Message[] roomMessage : totalMessages) {
                    for (int i = roomMessage.length - 1; i >= 0; i--) { //倒序输出
                        try {
                            Pocket48SenderMessage message1 = pharseMessage(roomMessage[i], group, subscribe.getRoomIDs().size() == 1);
                            if (message1 == null) {
                                continue;
                            }
                            
                            try {
                                Message[] unjointMessages = message1.getUnjointMessage();
                                if (unjointMessages != null && unjointMessages.length > 0) {
                                    for (Message m : unjointMessages) {
                                        if (m != null) {
                                            group.sendMessage(m);
                                        }
                                    }
                                }
                            } catch (Exception ex) {
                                System.err.println("处理消息时发生错误: " + ex.getMessage());
                                ex.printStackTrace();
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
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
                
                try {
                    // 首先检查资源可用性
                    System.out.println("[音频处理] 开始处理音频: " + audioUrl);
                    
                    try (InputStream audioStream = getRes(audioUrl)) {
                        if (audioStream == null) {
                            throw new RuntimeException("无法获取音频流");
                        }
                        
                        // 读取文件头进行格式检测
                        byte[] header = new byte[16];
                        audioStream.mark(16);
                        int bytesRead = audioStream.read(header);
                        audioStream.reset();
                        
                        InputStream processedAudioStream = audioStream;
                        
                        if (bytesRead > 0) {
                            String format = net.luffy.util.AudioFormatDetector.detectFormat(header);
                            String compatibility = net.luffy.util.AudioFormatDetector.getCompatibilityDescription(format);
                            
                            // 记录音频格式信息
                            System.out.println("[音频格式检测] URL: " + audioUrl);
                            System.out.println("[音频格式检测] 格式: " + format);
                            System.out.println("[音频格式检测] 兼容性: " + compatibility);
                            System.out.println("[音频格式检测] 文件头: " + net.luffy.util.AudioFormatDetector.bytesToHex(header, bytesRead));
                            
                            if (!net.luffy.util.AudioFormatDetector.isQQCompatible(format)) {
                                System.out.println("[音频转换] 检测到不兼容格式" + format + "，尝试转换为AMR格式");
                                // 尝试转换音频格式
                                InputStream convertedStream = net.luffy.util.AudioFormatConverter.convertToAMR(audioStream, format);
                                if (convertedStream != null) {
                                    processedAudioStream = convertedStream;
                                    System.out.println("[音频转换] 音频格式转换完成");
                                } else {
                                    System.err.println("[音频转换] 音频格式转换失败，使用原始音频");
                                }
                            }
                        } else {
                            System.err.println("[警告] 无法读取音频文件头，可能是空文件或损坏的文件");
                        }
                        
                        try (ExternalResource audioResource = ExternalResource.create(processedAudioStream)) {
                            Audio audio = group.uploadAudio(audioResource);
                            System.out.println("[音频处理] 音频上传成功");
                            if (single_subscribe) {
                                return new Pocket48SenderMessage(false, null, new Message[]{audio});
                            } else {
                                String audioContent = "【" + n + "】: 发送了一条语音\n查看链接: " + (audioUrl != null ? audioUrl : "[链接获取失败]");
                                MessageChain messageChain = new PlainText(audioContent + "\n").plus(audio).plus("\n频道：" + r + "\n时间: " + timeStr);
                                return new Pocket48SenderMessage(false, null, new Message[]{messageChain});
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[错误] 处理音频消息失败: " + e.getMessage());
                    e.printStackTrace();
                    String errorContent = "【" + n + "】: 语音消息处理失败(" + e.getMessage() + ")\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText(errorContent)});
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
                
                try {
                    System.out.println("[视频处理] 开始处理视频: " + videoUrl);
                    
                    InputStream s = getRes(videoUrl);
                    if (s == null) {
                        throw new RuntimeException("无法获取视频流");
                    }
                    
                    try (ExternalResource thumbnailResource = ExternalResource.create(getVideoThumbnail(s, message.getRoom().getBgImg()));
                         ExternalResource videoResource = ExternalResource.create(s)) {
                        ShortVideo video = group.uploadShortVideo(thumbnailResource, videoResource,
                                (single_subscribe ? "" : message.getOwnerName()) + "房间视频(" + DateUtil.format(new Date(message.getTime()), "yyyy-MM-dd HH-mm-ss") + ")." + message.getExt());
                        System.out.println("[视频处理] 视频上传成功");
                        
                        if (single_subscribe) {
                            return new Pocket48SenderMessage(false, null, new Message[]{video});
                        } else {
                            String videoContent = "【" + n + "】: 发送了一个视频\n查看链接: " + videoUrl;
                            MessageChain messageChain = new PlainText(videoContent + "\n").plus(video).plus("\n频道：" + r + "\n时间: " + timeStr);
                            return new Pocket48SenderMessage(false, null, new Message[]{messageChain});
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[错误] 处理视频消息失败: " + e.getMessage());
                    e.printStackTrace();
                    String errorContent = "【" + n + "】: 视频消息处理失败(" + e.getMessage() + ")\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText(errorContent)});
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
                try (ExternalResource coverResource = ExternalResource.create(getRes(message.getLivePush().getCover()))) {
                    Image cover = group.uploadImage(coverResource);
                    String livePushContent = "【" + n + "】: 直播中快来~\n直播标题：" + message.getLivePush().getTitle();
                    // 将封面图嵌入到消息链中，确保图片在文字消息内部
                    MessageChain messageChain = new PlainText(livePushContent + "\n").plus(cover).plus("\n频道：" + r + "\n时间: " + timeStr);
                    return new Pocket48SenderMessage(false, null, new Message[]{messageChain});
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
                
                try {
                    // 首先检查资源可用性
                    System.out.println("[翻牌音频处理] 开始处理翻牌音频: " + flipcardAudioUrl);
                    
                    try (InputStream audioStream = getRes(flipcardAudioUrl)) {
                        if (audioStream == null) {
                            throw new RuntimeException("无法获取翻牌音频流");
                        }
                        
                        // 读取文件头进行格式检测
                        byte[] header = new byte[16];
                        audioStream.mark(16);
                        int bytesRead = audioStream.read(header);
                        audioStream.reset();
                        
                        InputStream processedAudioStream = audioStream;
                        
                        if (bytesRead > 0) {
                            String format = net.luffy.util.AudioFormatDetector.detectFormat(header);
                            String compatibility = net.luffy.util.AudioFormatDetector.getCompatibilityDescription(format);
                            
                            // 记录音频格式信息
                            System.out.println("[翻牌音频格式检测] URL: " + flipcardAudioUrl);
                            System.out.println("[翻牌音频格式检测] 格式: " + format);
                            System.out.println("[翻牌音频格式检测] 兼容性: " + compatibility);
                            System.out.println("[翻牌音频格式检测] 文件头: " + net.luffy.util.AudioFormatDetector.bytesToHex(header, bytesRead));
                            
                            if (!net.luffy.util.AudioFormatDetector.isQQCompatible(format)) {
                                System.out.println("[翻牌音频转换] 检测到不兼容格式" + format + "，尝试转换为AMR格式");
                                // 尝试转换音频格式
                                InputStream convertedStream = net.luffy.util.AudioFormatConverter.convertToAMR(audioStream, format);
                                if (convertedStream != null) {
                                    processedAudioStream = convertedStream;
                                    System.out.println("[翻牌音频转换] 音频格式转换完成");
                                } else {
                                    System.err.println("[翻牌音频转换] 音频格式转换失败，使用原始音频");
                                }
                            }
                        } else {
                            System.err.println("[警告] 无法读取翻牌音频文件头，可能是空文件或损坏的文件");
                        }
                        
                        try (ExternalResource audioResource = ExternalResource.create(processedAudioStream)) {
                            Audio audio = group.uploadAudio(audioResource);
                            System.out.println("[翻牌音频处理] 翻牌音频上传成功");
                            String flipAudioContent = "【" + n + "】: 翻牌回复语音\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                             return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(flipAudioContent), audio});
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[错误] 处理翻牌音频消息失败: " + e.getMessage());
                    e.printStackTrace();
                    String errorContent = "【" + n + "】: 翻牌语音消息处理失败(" + e.getMessage() + ")\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText(errorContent)});
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
                
                try {
                    System.out.println("[翻牌视频处理] 开始处理翻牌视频: " + videoUrl);
                    System.out.println("[翻牌视频处理] 预览图: " + previewUrl);
                    
                    try (ExternalResource previewResource = ExternalResource.create(getRes(previewUrl))) {
                        if (previewResource == null) {
                            throw new RuntimeException("无法获取翻牌视频预览图");
                        }
                        
                        try (ExternalResource videoResource = ExternalResource.create(getRes(videoUrl))) {
                            if (videoResource == null) {
                                throw new RuntimeException("无法获取翻牌视频文件");
                            }
                            
                            ShortVideo video = group.uploadShortVideo(previewResource, videoResource,
                                    (single_subscribe ? "" : message.getOwnerName()) + "翻牌回复视频(" + DateUtil.format(new Date(message.getTime()), "yyyy-MM-dd HH-mm-ss") + ")." + message.getAnswer().getExt());
                            System.out.println("[翻牌视频处理] 翻牌视频上传成功");
                            
                            String flipVideoContent = "【" + n + "】: 翻牌回复视频\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                             return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(flipVideoContent), video});
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[错误] 处理翻牌视频消息失败: " + e.getMessage());
                    e.printStackTrace();
                    String errorContent = "【" + n + "】: 翻牌视频消息处理失败(" + e.getMessage() + ")\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + ": " + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间: " + timeStr;
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText(errorContent)});
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
     * @param jsonBody JSON格式的消息体
     * @return 表情名称
     */
    private String parseJsonEmotionName(String jsonBody) {
        try {
            // 解析name字段
            if (jsonBody.contains("\"name\"")) {
                String[] parts = jsonBody.split("\"name\":");
                if (parts.length > 1) {
                    String namePart = parts[1].trim();
                    if (namePart.startsWith("\"")) {
                        int endIndex = namePart.indexOf("\"", 1);
                        if (endIndex > 0) {
                            String emotionName = namePart.substring(1, endIndex);
                            return "[" + emotionName + "]";
                        }
                    }
                }
            }
            
            // 解析title字段作为备选
            if (jsonBody.contains("\"title\"")) {
                String[] parts = jsonBody.split("\"title\":");
                if (parts.length > 1) {
                    String titlePart = parts[1].trim();
                    if (titlePart.startsWith("\"")) {
                        int endIndex = titlePart.indexOf("\"", 1);
                        if (endIndex > 0) {
                            String emotionTitle = titlePart.substring(1, endIndex);
                            return "[" + emotionTitle + "]";
                        }
                    }
                }
            }
            
            // 解析text字段作为最后备选
            if (jsonBody.contains("\"text\"")) {
                String[] parts = jsonBody.split("\"text\":");
                if (parts.length > 1) {
                    String textPart = parts[1].trim();
                    if (textPart.startsWith("\"")) {
                        int endIndex = textPart.indexOf("\"", 1);
                        if (endIndex > 0) {
                            String emotionText = textPart.substring(1, endIndex);
                            return "[" + emotionText + "]";
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            // JSON解析失败
        }
        return "[表情]";
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
     * 重写getRes方法，使用Pocket48ResourceHandler处理特殊的口袋48资源链接
     * @param resLoc 资源链接
     * @return 资源输入流
     */
    @Override
    public InputStream getRes(String resLoc) {
        try {
            // 检查是否为口袋48特殊链接格式
            if (resLoc != null && (resLoc.contains("kd48-nosdn.yunxinsvr.com") || 
                                  resLoc.contains("pocket48") || 
                                  resLoc.contains("yunxinsvr"))) {
                // 使用专门的口袋48资源处理器
                return resourceHandler.getPocket48InputStreamWithRetry(resLoc, 3);
            }
            
            // 对于普通链接，使用父类的默认处理方式
            return super.getRes(resLoc);
        } catch (Exception e) {
            System.err.println("获取口袋48资源失败，尝试使用默认方式: " + e.getMessage());
            // 如果专用处理器失败，回退到父类的默认处理方式
            try {
                return super.getRes(resLoc);
            } catch (Exception fallbackEx) {
                System.err.println("默认方式也失败: " + fallbackEx.getMessage());
                throw new RuntimeException("无法获取资源: " + resLoc, fallbackEx);
            }
        }
    }

}
