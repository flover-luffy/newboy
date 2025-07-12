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
            case IMAGE:
            case EXPRESSIMAGE: {
                try (ExternalResource imageResource = ExternalResource.create(getRes(message.getResLoc()))) {
                    Image image = group.uploadImage(imageResource);
                    String imageContent = "【" + n + "】:图片\n频道：" + r + "\n时间:" + timeStr;
                     return new Pocket48SenderMessage(false, null,
                             new Message[]{new PlainText(imageContent), image});
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

}
