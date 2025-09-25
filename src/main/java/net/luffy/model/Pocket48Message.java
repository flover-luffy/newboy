package net.luffy.model;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;
import net.luffy.util.UnifiedJsonParser;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

public class Pocket48Message {
    private final Pocket48RoomInfo room;
    private final String nickName;
    private final String starName;
    private final Pocket48MessageType type;
    private final String body;
    private final long time;
    
    // 新增字段：资源链接和元数据
    private final String resourceUrl;
    private final String thumbnailUrl;
    private final Map<String, Object> metadata;
    private final List<String> resourceUrls;
    private final String contentType;
    private final long fileSize;
    private final String messageId;
    private final int sequenceNumber;
    
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
        
        // 初始化新增字段
        this.resourceUrl = extractResourceUrl(body, type);
        this.thumbnailUrl = extractThumbnailUrl(body, type);
        this.metadata = extractMetadata(body, type);
        this.resourceUrls = extractAllResourceUrls(body, type);
        this.contentType = extractContentType(body, type);
        this.fileSize = extractFileSize(body, type);
        this.messageId = generateMessageId(room, time, nickName);
        this.sequenceNumber = 0; // 将由MessageIntegrityChecker设置
    }
    
    // 新增构造函数，支持完整字段初始化
    public Pocket48Message(Pocket48RoomInfo room, String nickName, String starName, String type, String body, long time,
                          String resourceUrl, String thumbnailUrl, Map<String, Object> metadata, 
                          List<String> resourceUrls, String contentType, long fileSize, String messageId, int sequenceNumber) {
        this.room = room;
        this.nickName = nickName;
        this.starName = starName;
        this.type = Pocket48MessageType.valueOf(type);
        this.body = body;
        this.time = time;
        this.resourceUrl = resourceUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        this.resourceUrls = resourceUrls != null ? new ArrayList<>(resourceUrls) : new ArrayList<>();
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.messageId = messageId;
        this.sequenceNumber = sequenceNumber;
    }

    public static Pocket48Message construct(Pocket48RoomInfo roomInfo, JSONObject m) {
        JSONObject extInfo = getJsonParser().parseObj(m.getObj("extInfo").toString());
        JSONObject user = getJsonParser().parseObj(extInfo.getObj("user").toString());
        
        String nickName = user.getStr("nickName");
        String msgType = m.getStr("msgType");
        String bodys = m.getStr("bodys");
        long msgTime = m.getLong("msgTime");
        int userId = user.getInt("userId");
        String starName = Newboy.INSTANCE.getHandlerPocket48().getStarNameByStarID(userId);
        
        // 创建基础消息对象，构造函数会自动提取资源信息
        Pocket48Message message = new Pocket48Message(
                roomInfo.setStarId(userId),
                nickName,
                starName,
                msgType,
                bodys,
                msgTime);
        
        // 从原始JSON中提取额外的元数据
        Map<String, Object> additionalMetadata = new HashMap<>(message.getMetadata());
        
        // 提取消息ID（如果JSON中有的话）
        String msgId = m.getStr("msgIdClient");
        if (msgId != null && !msgId.isEmpty()) {
            additionalMetadata.put("originalMsgId", msgId);
        }
        
        // 提取发送者ID
        additionalMetadata.put("userId", userId);
        
        // 提取扩展信息
        if (extInfo != null) {
            additionalMetadata.put("extInfo", extInfo.toString());
        }
        
        // 如果有额外元数据，创建新的消息副本
        if (!additionalMetadata.equals(message.getMetadata())) {
            message = message.withMetadata(additionalMetadata);
        }
        
        return message;
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
    
    // 新增字段的getter方法
    public String getResourceUrl() {
        return resourceUrl;
    }
    
    public String getThumbnailUrl() {
        return thumbnailUrl;
    }
    
    public Map<String, Object> getMetadata() {
        return new HashMap<>(metadata);
    }
    
    public List<String> getResourceUrls() {
        return new ArrayList<>(resourceUrls);
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public long getFileSize() {
        return fileSize;
    }
    
    public String getMessageId() {
        return messageId;
    }
    
    public int getSequenceNumber() {
        return sequenceNumber;
    }
    
    // 创建带有新序列号的消息副本（用于MessageIntegrityChecker）
    public Pocket48Message withSequenceNumber(int newSequenceNumber) {
        return new Pocket48Message(room, nickName, starName, type.name(), body, time,
                                 resourceUrl, thumbnailUrl, metadata, resourceUrls, 
                                 contentType, fileSize, messageId, newSequenceNumber);
    }
    
    // 创建带有更新元数据的消息副本
    public Pocket48Message withMetadata(Map<String, Object> newMetadata) {
        return new Pocket48Message(room, nickName, starName, type.name(), body, time,
                                 resourceUrl, thumbnailUrl, newMetadata, resourceUrls, 
                                 contentType, fileSize, messageId, sequenceNumber);
    }
    
    // 创建带有资源URL的消息副本（用于资源优化器）
    public Pocket48Message withResourceUrl(String newResourceUrl) {
        return new Pocket48Message(room, nickName, starName, type.name(), body, time,
                                 newResourceUrl, thumbnailUrl, metadata, resourceUrls, 
                                 contentType, fileSize, messageId, sequenceNumber);
    }
    
    // 判断是否包含媒体资源
    public boolean hasMediaResource() {
        return resourceUrl != null && !resourceUrl.isEmpty() || 
               (resourceUrls != null && !resourceUrls.isEmpty());
    }
    
    // 判断是否为媒体消息类型
    public boolean isMediaMessage() {
        return type == Pocket48MessageType.IMAGE || 
               type == Pocket48MessageType.AUDIO || 
               type == Pocket48MessageType.VIDEO;
    }
    
    // 获取主要资源URL（优先返回resourceUrl，否则返回resourceUrls的第一个）
    public String getPrimaryResourceUrl() {
        if (resourceUrl != null && !resourceUrl.isEmpty()) {
            return resourceUrl;
        }
        if (resourceUrls != null && !resourceUrls.isEmpty()) {
            return resourceUrls.get(0);
        }
        return null;
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
    
    // 资源提取辅助方法
    private String extractResourceUrl(String body, String type) {
        try {
            Pocket48MessageType msgType = Pocket48MessageType.valueOf(type);
            if (msgType == Pocket48MessageType.IMAGE || msgType == Pocket48MessageType.AUDIO || msgType == Pocket48MessageType.VIDEO) {
                JSONObject bodyObj = getJsonParser().parseObj(body);
                return bodyObj.getStr("url");
            } else if (msgType == Pocket48MessageType.EXPRESSIMAGE) {
                JSONObject bodyObj = getJsonParser().parseObj(body);
                JSONObject expressInfo = getJsonParser().parseObj(bodyObj.getObj("expressImgInfo").toString());
                return expressInfo.getStr("emotionRemote");
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return null;
    }
    
    private String extractThumbnailUrl(String body, String type) {
        try {
            Pocket48MessageType msgType = Pocket48MessageType.valueOf(type);
            if (msgType == Pocket48MessageType.VIDEO) {
                JSONObject bodyObj = getJsonParser().parseObj(body);
                return bodyObj.getStr("thumbnailUrl");
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return null;
    }
    
    private Map<String, Object> extractMetadata(String body, String type) {
        Map<String, Object> metadata = new HashMap<>();
        try {
            JSONObject bodyObj = getJsonParser().parseObj(body);
            Pocket48MessageType msgType = Pocket48MessageType.valueOf(type);
            
            if (msgType == Pocket48MessageType.AUDIO || msgType == Pocket48MessageType.VIDEO) {
                Long duration = bodyObj.getLong("dur");
                if (duration != null) {
                    metadata.put("duration", duration);
                }
            }
            
            if (msgType == Pocket48MessageType.IMAGE || msgType == Pocket48MessageType.AUDIO || msgType == Pocket48MessageType.VIDEO) {
                String ext = bodyObj.getStr("ext");
                if (ext != null) {
                    metadata.put("extension", ext);
                }
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return metadata;
    }
    
    private List<String> extractAllResourceUrls(String body, String type) {
        List<String> urls = new ArrayList<>();
        String primaryUrl = extractResourceUrl(body, type);
        if (primaryUrl != null) {
            urls.add(primaryUrl);
        }
        String thumbnailUrl = extractThumbnailUrl(body, type);
        if (thumbnailUrl != null && !thumbnailUrl.equals(primaryUrl)) {
            urls.add(thumbnailUrl);
        }
        return urls;
    }
    
    private String extractContentType(String body, String type) {
        try {
            Pocket48MessageType msgType = Pocket48MessageType.valueOf(type);
            switch (msgType) {
                case IMAGE:
                case EXPRESSIMAGE:
                    return "image";
                case AUDIO:
                    return "audio";
                case VIDEO:
                    return "video";
                case TEXT:
                case REPLY:
                case GIFT_TEXT:
                case GIFTREPLY:
                    return "text";
                default:
                    return "unknown";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    private long extractFileSize(String body, String type) {
        try {
            JSONObject bodyObj = getJsonParser().parseObj(body);
            Long size = bodyObj.getLong("size");
            return size != null ? size : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
    
    private String generateMessageId(Pocket48RoomInfo room, long time, String nickName) {
        return String.format("%s_%d_%s_%d", 
            room.getRoomId(), 
            time, 
            nickName.hashCode(), 
            System.nanoTime() % 1000000);
    }
}
