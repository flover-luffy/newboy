package net.luffy.model;

import net.mamoe.mirai.message.data.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Pocket48SenderMessage {

    private static final Logger logger = LoggerFactory.getLogger(Pocket48SenderMessage.class);
    private final boolean canJoin;
    private final Message title;
    private final Message[] message;
    private boolean specific = false;//第一条消息可以合并


    public Pocket48SenderMessage(boolean canJoin, Message title, Message[] message) {
        this.canJoin = canJoin;
        this.title = title;
        this.message = message;
    }

    public boolean canJoin() {
        return canJoin;
    }

    public Message[] getMessage() {
        return message;
    }

    public Message getTitle() {
        return title;
    }

    public Message[] getUnjointMessage() {
        if (message == null) {
            return new Message[0];
        }
        
        // 创建副本以避免修改原数组
        Message[] result = new Message[message.length];
        System.arraycopy(message, 0, result, 0, message.length);
        
        if (title != null && result.length > 0 && result[0] != null) {
            try {
                result[0] = title.plus(result[0]);
            } catch (Exception e) {
                logger.error("合并消息时发生错误: {}", e.getMessage());
                // 如果合并失败，保持原消息不变
            }
        }
        return result;
    }

    public boolean isSpecific() {
        return specific;
    }

    public Pocket48SenderMessage setSpecific() {
        this.specific = true;
        return this;
    }
}
