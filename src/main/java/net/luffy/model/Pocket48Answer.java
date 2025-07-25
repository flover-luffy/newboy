package net.luffy.model;

import cn.hutool.json.JSONUtil;
import net.luffy.util.UnifiedJsonParser;

public class Pocket48Answer {
    private final static String ROOT = "https://mp4.48.cn";
    private final static String ROOT_SOURCE = "https://source.48.cn";
    private final String msgTo;
    private final String bodyFrom;
    private final String answerID;
    private final String questionID;
    private final Pocket48MessageType type;

    public Pocket48Answer(String msgTo, String bodyFrom, String answerID, String questionID, Pocket48MessageType type) {
        this.msgTo = msgTo;
        this.bodyFrom = bodyFrom;
        this.answerID = answerID;
        this.questionID = questionID;
        this.type = type;
    }

    public String getMsgTo() {
        return msgTo;
    }

    private String getBodyFrom() {
        return bodyFrom;
    }

    public String getAnswer() {
        return bodyFrom;
    }

    public String getResInfo() {
        if (type == Pocket48MessageType.FLIPCARD_AUDIO || type == Pocket48MessageType.FLIPCARD_VIDEO)
            return ROOT + UnifiedJsonParser.getInstance().parseObj(getBodyFrom()).getStr("url");
        return null;
    }

    public String getExt() {
        String rec = getResInfo();
        return rec.substring(rec.lastIndexOf(".") + 1);
    }

    public long getDuration() {
        if (type == Pocket48MessageType.FLIPCARD_AUDIO || type == Pocket48MessageType.FLIPCARD_VIDEO)
            UnifiedJsonParser.getInstance().parseObj(getBodyFrom()).getLong("duration");
        return 0;
    }

    public String getPreviewImg() {
        if (type == Pocket48MessageType.FLIPCARD_VIDEO)
            return ROOT_SOURCE + UnifiedJsonParser.getInstance().parseObj(getBodyFrom()).getStr("previewImg");
        return null;
    }

    public String getQuestionID() {
        return questionID;
    }

    public String getAnswerID() {
        return answerID;
    }
}
