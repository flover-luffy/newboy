package net.luffy.model;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;
import net.luffy.util.UnifiedJsonParser;

public class Pocket48Message {
    private final Pocket48RoomInfo room;
    private final String nickName;
    private final String starName;
    private final Pocket48MessageType type;
    private final String body;
    private final long time;
    private static UnifiedJsonParser jsonParser;

    private static UnifiedJsonParser getJsonParser() {
        if (jsonParser == null) {
            jsonParser = UnifiedJsonParser.getInstance();
        }
        return jsonParser;
    }

    public Pocket48Message(Pocket48RoomInfo room, String nickName, String starName, String type, String body, long time) {
        this.room = room;
        this.nickName = nickName;
        this.starName = starName;
        this.type = Pocket48MessageType.valueOf(type);
        this.body = body;
        this.time = time;
    }

    public static final Pocket48Message construct(Pocket48RoomInfo roomInfo, JSONObject m) {
        JSONObject extInfo = getJsonParser().parseObj(m.getObj("extInfo").toString());
        JSONObject user = getJsonParser().parseObj(extInfo.getObj("user").toString());
        return new Pocket48Message(
                roomInfo.setStarId(user.getInt("userId")),
                user.getStr("nickName"),
                Newboy.INSTANCE.getHandlerPocket48().getStarNameByStarID(user.getInt("userId")),
                m.getStr("msgType"),
                m.getStr("bodys"),
                m.getLong("msgTime"));
    }

    public Pocket48RoomInfo getRoom() {
        return room;
    }

    public String getOwnerName() {
        return Pocket48Handler.getOwnerOrTeamName(room);
    }

    public String getNickName() {
        return nickName;
    }

    public String getStarName() {
        return starName;
    }

    public String getBody() {
        return body;
    }

    public String getText() {
        if (getType() == Pocket48MessageType.GIFT_TEXT) {
            JSONObject info = getJsonParser().parseObj(getJsonParser().parseObj(getBody()).getObj("giftInfo").toString());
            return "送给 " + info.getStr("userName") + " " + info.getInt("giftNum") + "个" + info.getStr("giftName");
        }
        return getBody();
    }

    public Pocket48MessageType getType() {
        return type;
    }

    public long getTime() {
        return time;
    }

    //IMAGE,EXPRESSIMAGE,AUDIO,VIDEO
    public String getResLoc() {
        if (getType() == Pocket48MessageType.EXPRESSIMAGE) {
            return jsonParser.parseObj(jsonParser.parseObj(getBody()).getObj("expressImgInfo").toString()).getStr("emotionRemote");
        }
        if (getType() == Pocket48MessageType.IMAGE || getType() == Pocket48MessageType.AUDIO
                || getType() == Pocket48MessageType.VIDEO) {
            return jsonParser.parseObj(getBody()).getStr("url");
        }

        return null;
    }

    //网易资源有ext IMAGE,AUDIO,VIDEO
    public String getExt() {
        if (getType() == Pocket48MessageType.IMAGE || getType() == Pocket48MessageType.AUDIO
                || getType() == Pocket48MessageType.VIDEO) {
            return jsonParser.parseObj(getBody()).getStr("ext");
        }
        return null;
    }

    public long getDuration() {
        if (getType() == Pocket48MessageType.AUDIO
                || getType() == Pocket48MessageType.VIDEO) {
            return jsonParser.parseObj(getBody()).getLong("dur");
        }
        return 0;
    }

    //REPLY,GIFTREPLY
    public Pocket48Reply getReply() {
        boolean isGift = getType() == Pocket48MessageType.GIFTREPLY;
        if (!isGift && getType() != Pocket48MessageType.REPLY)//非回复消息
            return null;

        JSONObject object = jsonParser.parseObj(getBody());
        JSONObject content = jsonParser.parseObj(object.getObj(
                isGift ? "giftReplyInfo" : "replyInfo").toString());
        return new Pocket48Reply(
                content.getStr("replyName"),
                content.getStr("replyText"),
                content.getStr("text"),
                isGift
        );
    }

    public Pocket48LivePush getLivePush() {
        if (getType() != Pocket48MessageType.LIVEPUSH)
            return null;

        JSONObject object = jsonParser.parseObj(getBody());
        JSONObject content = jsonParser.parseObj(object.getObj(
                "livePushInfo").toString());
        return new Pocket48LivePush(
                Pocket48Handler.SOURCEROOT + content.getStr("liveCover").substring(1),
                content.getStr("liveTitle"),
                content.getStr("liveId")
        );
    }

    public Pocket48Answer getAnswer() {
        if (getType() != Pocket48MessageType.FLIPCARD
                && getType() != Pocket48MessageType.FLIPCARD_AUDIO
                && getType() != Pocket48MessageType.FLIPCARD_VIDEO)
            return null;

        JSONObject object = jsonParser.parseObj(getBody());
        JSONObject content = jsonParser.parseObj(object.getObj(
                "filpCardInfo").toString());
        return new Pocket48Answer(
                content.getStr("question"),
                content.getStr("answer"),
                content.getStr("answerId"),
                content.getStr("questionId"),
                getType()
        );
    }
}
