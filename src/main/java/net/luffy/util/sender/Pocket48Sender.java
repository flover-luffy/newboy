package net.luffy.util.sender;

import cn.hutool.core.date.DateUtil;
import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;
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

    public Pocket48Sender(Bot bot, long group, HashMap<Long, Long> endTime, HashMap<Long, List<Long>> voiceStatus, HashMap<Long, Pocket48SenderCache> cache) {
        super(bot, group);
        this.endTime = endTime;
        this.voiceStatus = voiceStatus;
        this.cache = cache;
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
                        String message = "【" + roomInfo.getRoomName() + "(" + ownerName + ")房间语音】\n";

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
        String starName = message.getStarName() != null ? message.getStarName() : "";
        String n = nickName + (nickName.indexOf(starName) != -1 ? "" : "(" + starName + ")");
        String r = (message.getRoom() != null && message.getRoom().getRoomName() != null) ? message.getRoom().getRoomName() : "未知频道";
        String timeStr = cn.hutool.core.date.DateUtil.format(new java.util.Date(message.getTime()), "MM-dd HH:mm");
        String headerTemplate = "【" + n + "】:" + "\n频道：" + r + "\n时间:" + timeStr;

        switch (message.getType()) {
            case TEXT:
            case GIFT_TEXT:
                String body = message.getBody() != null ? message.getBody() : "[消息内容为空]";
                String textContent = "【" + n + "】:" + pharsePocketTextWithFace(body) + "\n频道：" + r + "\n时间:" + timeStr;
                return new Pocket48SenderMessage(false, null,
                        new Message[]{new PlainText(textContent)});
            case AUDIO: {
                try (ExternalResource audioResource = ExternalResource.create(getRes(message.getResLoc()))) {
                    Audio audio = group.uploadAudio(audioResource);
                    if (single_subscribe) {
                        return new Pocket48SenderMessage(false, null, new Message[]{audio});
                    } else {
                        String audioHeader = "【" + n + "】:发送了一条语音\n频道：" + r + "\n时间:" + timeStr;
                         return new Pocket48SenderMessage(false, null,
                                 new Message[]{new PlainText(audioHeader), audio}).setSpecific();
                    }
                }
            }
            case IMAGE: {
                try (ExternalResource imageResource = ExternalResource.create(getRes(message.getResLoc()))) {
                    Image image = group.uploadImage(imageResource);
                    // 修复图片嵌入问题：创建包含图片的消息链
                    MessageChain messageChain = new PlainText("【" + n + "】:").plus(image).plus("\n频道：" + r + "\n时间:" + timeStr);
                    return new Pocket48SenderMessage(false, null, new Message[]{messageChain});
                }
            }
            case EXPRESSIMAGE: {
                // 口袋表情处理：解析表情信息并转换为QQ表情或文本显示
                try {
                    Message expressionMessage = parseExpressImageToMessage(message.getBody());
                    MessageChain messageChain = new PlainText("【" + n + "】:").plus(expressionMessage).plus("\n频道：" + r + "\n时间:" + timeStr);
                    return new Pocket48SenderMessage(false, null, new Message[]{messageChain});
                } catch (Exception e) {
                    // 如果解析失败，回退到显示表情名称
                    String fallbackContent = "【" + n + "】:[表情]\n频道：" + r + "\n时间:" + timeStr;
                    return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(fallbackContent)});
                }
            }
            case VIDEO: {
                InputStream s = getRes(message.getResLoc());
                try (ExternalResource thumbnailResource = ExternalResource.create(getVideoThumbnail(s, message.getRoom().getBgImg()));
                     ExternalResource videoResource = ExternalResource.create(s)) {
                    ShortVideo video = group.uploadShortVideo(thumbnailResource, videoResource,
                            (single_subscribe ? "" : message.getOwnerName()) + "房间视频(" + DateUtil.format(new Date(message.getTime()), "yyyy-MM-dd HH-mm-ss") + ")." + message.getExt());
                    if (single_subscribe) {
                        return new Pocket48SenderMessage(false, null, new Message[]{video});
                    } else {
                        String videoHeader = "【" + n + "】:发送了一段视频\n频道：" + r + "\n时间:" + timeStr;
                         return new Pocket48SenderMessage(false, null,
                                 new Message[]{new PlainText(videoHeader), video}).setSpecific();
                    }
                }
            }
            case REPLY:
            case GIFTREPLY:
                if (message.getReply() == null) {
                    return new Pocket48SenderMessage(false, null,
                            new Message[]{new PlainText("【" + n + "】:回复消息(内容为空)\n频道：" + r + "\n时间:" + timeStr)});
                }
                String nameTo = message.getReply().getNameTo() != null ? message.getReply().getNameTo() : "未知用户";
                String msgTo = message.getReply().getMsgTo() != null ? message.getReply().getMsgTo() : "[消息为空]";
                String msgFrom = message.getReply().getMsgFrom() != null ? message.getReply().getMsgFrom() : "[回复为空]";
                String replyContent = "【" + n + "】:\n" + nameTo + ":" + msgTo + "\n" + msgFrom + "\n频道：" + r + "\n时间:" + timeStr;
                return new Pocket48SenderMessage(false, null,
                        new Message[]{new PlainText(replyContent)});
            case LIVEPUSH:
                try (ExternalResource coverResource = ExternalResource.create(getRes(message.getLivePush().getCover()))) {
                    Image cover = group.uploadImage(coverResource);
                    String livePushContent = "【" + n + "】:开始直播了\n" + message.getLivePush().getTitle() + "\n频道：" + r + "\n时间:" + timeStr;
                     return new Pocket48SenderMessage(false, null,
                             new Message[]{new PlainText(livePushContent), cover});
                }
            case FLIPCARD:
                String flipContent = "【" + n + "】:翻牌回复消息\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + "：" + message.getAnswer().getMsgTo() + "\n------\n" + message.getAnswer().getAnswer() + "\n频道：" + r + "\n时间:" + timeStr;
                return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(flipContent)});
            case FLIPCARD_AUDIO:
                try (ExternalResource audioResource = ExternalResource.create(getRes(message.getAnswer().getResInfo()))) {
                    Audio audio = group.uploadAudio(audioResource);
                    String flipAudioContent = "【" + n + "】:翻牌回复语音\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + "：" + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间:" + timeStr;
                     return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(flipAudioContent), audio});
                }
            case FLIPCARD_VIDEO:
                try (ExternalResource previewResource = ExternalResource.create(getRes(message.getAnswer().getPreviewImg()));
                     ExternalResource videoResource = ExternalResource.create(getRes(message.getAnswer().getResInfo()))) {
                    ShortVideo video = group.uploadShortVideo(previewResource, videoResource,
                            (single_subscribe ? "" : message.getOwnerName()) + "翻牌回复视频(" + DateUtil.format(new Date(message.getTime()), "yyyy-MM-dd HH-mm-ss") + ")." + message.getAnswer().getExt());
                    String flipVideoContent = "【" + n + "】:翻牌回复视频\n" + pocket.getAnswerNameTo(message.getAnswer().getAnswerID(), message.getAnswer().getQuestionID()) + "：" + message.getAnswer().getMsgTo() + "\n------\n频道：" + r + "\n时间:" + timeStr;
                     return new Pocket48SenderMessage(false, null, new Message[]{new PlainText(flipVideoContent), video});
                }
            case PASSWORD_REDPACKAGE:
                String redPackageBody = message.getBody() != null ? message.getBody() : "[红包内容为空]";
                String redPackageContent = "【" + n + "】:发了一个口令红包\n" + redPackageBody + "\n频道：" + r + "\n时间:" + timeStr;
                return new Pocket48SenderMessage(false, null,
                        new Message[]{new PlainText(redPackageContent)});
            case VOTE:
                String voteBody = message.getBody() != null ? message.getBody() : "[投票内容为空]";
                String voteContent = "【" + n + "】:发起了一个投票\n" + voteBody + "\n频道：" + r + "\n时间:" + timeStr;
                return new Pocket48SenderMessage(false, null,
                        new Message[]{new PlainText(voteContent)});
        }

        return new Pocket48SenderMessage(true, new PlainText("【" + n + "】:不支持的消息\n频道：" + r + "\n时间:" + timeStr),
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
     * 根据表情名称查找对应的Face对象
     * @param faceName 表情名称（如"[微笑]"）
     * @return 对应的Face对象，如果没有找到则返回null
     */
    private Message findFaceByName(String faceName) {
        for (int i = 0; i < Face.names.length; i++) {
            if (Face.names[i].equals(faceName)) {
                return new Face(i);
            }
        }
        return null;
    }

    /**
     * 解析口袋表情JSON数据并转换为QQ表情或文本显示
     * @param body 口袋表情的JSON数据
     * @return 表情的Message对象（QQ表情或文本）
     */
    private Message parseExpressImageToMessage(String body) {
        try {
            if (body == null || body.trim().isEmpty()) {
                return new PlainText("[表情]");
            }
            
            // 解析JSON获取表情信息
            cn.hutool.json.JSONObject bodyJson = cn.hutool.json.JSONUtil.parseObj(body);
            cn.hutool.json.JSONObject expressImgInfo = bodyJson.getJSONObject("expressImgInfo");
            
            if (expressImgInfo != null) {
                // 尝试获取表情名称
                String emotionName = expressImgInfo.getStr("emotionName");
                if (emotionName != null && !emotionName.trim().isEmpty()) {
                    // 尝试映射到QQ表情
                    Message qqFace = mapPocketEmotionToQQFace(emotionName);
                    if (qqFace != null) {
                        return qqFace;
                    }
                    // 如果没有对应的QQ表情，返回文本形式
                    return new PlainText("[" + emotionName + "]");
                }
                
                // 如果没有名称，尝试获取其他描述信息
                String description = expressImgInfo.getStr("description");
                if (description != null && !description.trim().isEmpty()) {
                    return new PlainText("[" + description + "]");
                }
            }
            
            return new PlainText("[表情]");
        } catch (Exception e) {
            // 解析失败时返回通用表情标识
            return new PlainText("[表情]");
        }
    }
    
    /**
     * 将口袋表情名称映射到对应的QQ表情
     * @param emotionName 口袋表情名称
     * @return 对应的QQ表情Face对象，如果没有对应表情则返回null
     */
    private Message mapPocketEmotionToQQFace(String emotionName) {
        // 使用Face.names数组来查找对应的表情
        // 将口袋表情名称转换为QQ表情格式
        String qqFaceName = "[" + emotionName + "]";
        
        // 在Face.names数组中查找匹配的表情
        for (int i = 0; i < Face.names.length; i++) {
            if (Face.names[i].equals(qqFaceName)) {
                return new Face(i);
            }
        }
        
        // 特殊映射处理
        switch (emotionName) {
            case "抓狂":
                return findFaceByName("[抓狂]");
            case "微笑":
                return findFaceByName("[微笑]");
            case "撇嘴":
                return findFaceByName("[撇嘴]");
            case "色":
                return findFaceByName("[色]");
            case "发呆":
                return findFaceByName("[发呆]");
            case "得意":
                return findFaceByName("[得意]");
            case "流泪":
                return findFaceByName("[流泪]");
            case "害羞":
                return findFaceByName("[害羞]");
            case "闭嘴":
                return findFaceByName("[闭嘴]");
            case "睡":
                return findFaceByName("[睡]");
            case "大哭":
                return findFaceByName("[大哭]");
            case "尴尬":
                return findFaceByName("[尴尬]");
            case "发怒":
                return findFaceByName("[发怒]");
            case "调皮":
                return findFaceByName("[调皮]");
            case "呲牙":
                return findFaceByName("[呲牙]");
            case "惊讶":
                return findFaceByName("[惊讶]");
            case "难过":
                return findFaceByName("[难过]");
            case "酷":
                return findFaceByName("[酷]");
            case "冷汗":
                return findFaceByName("[冷汗]");
            case "疑问":
                return findFaceByName("[疑问]");
            case "吐":
                return findFaceByName("[吐]");
            case "偷笑":
                return findFaceByName("[偷笑]");
            case "可爱":
                return findFaceByName("[可爱]");
            case "白眼":
                return findFaceByName("[白眼]");
            case "傲慢":
                return findFaceByName("[傲慢]");
            case "饥饿":
                return findFaceByName("[饥饿]");
            case "困":
                return findFaceByName("[困]");
            case "惊恐":
                return findFaceByName("[惊恐]");
            case "流汗":
                return findFaceByName("[流汗]");
            case "憨笑":
                return findFaceByName("[憨笑]");
            case "奋斗":
                return findFaceByName("[奋斗]");
            case "咒骂":
                return findFaceByName("[咒骂]");
            case "嘘":
                return findFaceByName("[嘘]");
            case "晕":
                return findFaceByName("[晕]");
            case "衰":
                return findFaceByName("[衰]");
            case "骷髅":
                return findFaceByName("[骷髅]");
            case "敲打":
                return findFaceByName("[敲打]");
            case "再见":
                return findFaceByName("[再见]");
            case "擦汗":
                return findFaceByName("[擦汗]");
            case "抠鼻":
                return findFaceByName("[抠鼻]");
            case "鼓掌":
                return findFaceByName("[鼓掌]");
            case "糗大了":
                return findFaceByName("[糗大了]");
            case "坏笑":
                return findFaceByName("[坏笑]");
            case "左哼哼":
                return findFaceByName("[左哼哼]");
            case "右哼哼":
                return findFaceByName("[右哼哼]");
            case "哈欠":
                return findFaceByName("[哈欠]");
            case "鄙视":
                return findFaceByName("[鄙视]");
            case "委屈":
                return findFaceByName("[委屈]");
            case "快哭了":
                return findFaceByName("[快哭了]");
            case "阴险":
                return findFaceByName("[阴险]");
            case "亲亲":
            case "左亲亲":
                return findFaceByName("[左亲亲]");
            case "右亲亲":
                return findFaceByName("[右亲亲]");
            case "吓":
                return findFaceByName("[吓]");
            case "可怜":
                return findFaceByName("[可怜]");
            case "菜刀":
                return findFaceByName("[菜刀]");
            case "西瓜":
                return findFaceByName("[西瓜]");
            case "啤酒":
                return findFaceByName("[啤酒]");
            case "篮球":
                return findFaceByName("[篮球]");
            case "乒乓":
                return findFaceByName("[乒乓]");
            case "咖啡":
                return findFaceByName("[咖啡]");
            case "饭":
                return findFaceByName("[饭]");
            case "猪头":
                return findFaceByName("[猪头]");
            case "玫瑰":
                return findFaceByName("[玫瑰]");
            case "凋谢":
                return findFaceByName("[凋谢]");
            case "示爱":
                return findFaceByName("[示爱]");
            case "爱心":
                return findFaceByName("[爱心]");
            case "心碎":
                return findFaceByName("[心碎]");
            case "蛋糕":
                return findFaceByName("[蛋糕]");
            case "闪电":
                return findFaceByName("[闪电]");
            case "炸弹":
                return findFaceByName("[炸弹]");
            case "刀":
                return findFaceByName("[刀]");
            case "足球":
                return findFaceByName("[足球]");
            case "瓢虫":
                return findFaceByName("[瓢虫]");
            case "便便":
                return findFaceByName("[便便]");
            case "月亮":
                return findFaceByName("[月亮]");
            case "太阳":
                return findFaceByName("[太阳]");
            case "礼物":
                return findFaceByName("[礼物]");
            case "拥抱":
                return findFaceByName("[拥抱]");
            case "强":
                return findFaceByName("[强]");
            case "弱":
                return findFaceByName("[弱]");
            case "握手":
                return findFaceByName("[握手]");
            case "胜利":
                return findFaceByName("[胜利]");
            case "抱拳":
                return findFaceByName("[抱拳]");
            case "勾引":
                return findFaceByName("[勾引]");
            case "拳头":
                return findFaceByName("[拳头]");
            case "差劲":
                return findFaceByName("[差劲]");
            case "爱你":
                return findFaceByName("[爱你]");
            case "NO":
                return findFaceByName("[NO]");
            case "OK":
                return findFaceByName("[OK]");
            default:
                // 没有对应的QQ表情，返回null
                return null;
        }
    }

}
