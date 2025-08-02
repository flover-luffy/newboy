package net.luffy.util.sender;

import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.Message;
import org.springframework.stereotype.Component;

/**
 * 消息发送器服务类
 * 提供统一的消息发送接口
 */
@Component
public class MessageSender {
    
    /**
     * 发送消息到指定群组
     * @param bot 机器人实例
     * @param groupId 群组ID
     * @param message 要发送的消息
     */
    public void sendMessage(Bot bot, long groupId, Message message) {
        try {
            if (bot != null) {
                Group group = bot.getGroup(groupId);
                if (group != null) {
                    group.sendMessage(message);
                }
            }
        } catch (Exception e) {
            System.err.println("发送消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送消息到指定群组
     * @param group 群组对象
     * @param message 要发送的消息
     */
    public void sendMessage(Group group, Message message) {
        try {
            if (group != null) {
                group.sendMessage(message);
            }
        } catch (Exception e) {
            System.err.println("发送消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送文本消息到指定群组
     * @param bot 机器人实例
     * @param groupId 群组ID
     * @param text 要发送的文本
     */
    public void sendTextMessage(Bot bot, long groupId, String text) {
        try {
            if (bot != null) {
                Group group = bot.getGroup(groupId);
                if (group != null) {
                    group.sendMessage(text);
                }
            }
        } catch (Exception e) {
            System.err.println("发送文本消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送文本消息到指定群组
     * @param group 群组对象
     * @param text 要发送的文本
     */
    public void sendTextMessage(Group group, String text) {
        try {
            if (group != null) {
                group.sendMessage(text);
            }
        } catch (Exception e) {
            System.err.println("发送文本消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送群组消息
     * @param groupId 群组ID字符串
     * @param messageText 消息文本
     */
    public void sendGroupMessage(String groupId, String messageText) {
        try {
            // 这里需要获取Bot实例，暂时使用占位符实现
            System.out.println("发送群组消息到 " + groupId + ": " + messageText);
        } catch (Exception e) {
            System.err.println("发送群组消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送群组图片
     * @param groupId 群组ID字符串
     * @param imageUrl 图片URL
     */
    public void sendGroupImage(String groupId, String imageUrl) {
        try {
            // 这里需要获取Bot实例，暂时使用占位符实现
            System.out.println("发送群组图片到 " + groupId + ": " + imageUrl);
        } catch (Exception e) {
            System.err.println("发送群组图片失败: " + e.getMessage());
        }
    }
}