package net.luffy.util.sender;

import net.luffy.Newboy;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.Image;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.MessageChainBuilder;
import net.mamoe.mirai.utils.ExternalResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

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
            Bot bot = Newboy.getBot();
            if (bot != null) {
                long groupIdLong = Long.parseLong(groupId);
                Group group = bot.getGroup(groupIdLong);
                if (group != null) {
                    group.sendMessage(messageText);
                }
            }
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
            Bot bot = Newboy.getBot();
            if (bot != null && imageUrl != null && !imageUrl.isEmpty()) {
                long groupIdLong = Long.parseLong(groupId);
                Group group = bot.getGroup(groupIdLong);
                if (group != null) {
                    // 从URL下载图片并发送
                    URL url = new URL(imageUrl);
                    URLConnection connection = url.openConnection();
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    
                    try (InputStream inputStream = connection.getInputStream();
                         ExternalResource resource = ExternalResource.create(inputStream)) {
                        Image image = group.uploadImage(resource);
                        group.sendMessage(image);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("发送群组图片失败: " + e.getMessage());
        }
    }
    
    /**
     * 发送群组消息（文本+图片）
     * @param groupId 群组ID字符串
     * @param messageText 消息文本
     * @param imageUrl 图片URL（可选）
     */
    public void sendGroupMessageWithImage(String groupId, String messageText, String imageUrl) {
        try {
            Bot bot = Newboy.getBot();
            if (bot != null) {
                long groupIdLong = Long.parseLong(groupId);
                Group group = bot.getGroup(groupIdLong);
                if (group != null) {
                    MessageChainBuilder builder = new MessageChainBuilder();
                    
                    // 添加文本消息
                    if (messageText != null && !messageText.isEmpty()) {
                        builder.append(messageText);
                    }
                    
                    // 如果有图片，添加图片到消息链的最后
                    if (imageUrl != null && !imageUrl.isEmpty()) {
                        try {
                            URL url = new URL(imageUrl);
                            URLConnection connection = url.openConnection();
                            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                            
                            try (InputStream inputStream = connection.getInputStream();
                                 ExternalResource resource = ExternalResource.create(inputStream)) {
                                Image image = group.uploadImage(resource);
                                builder.append(image);
                            }
                        } catch (Exception imageException) {
                            System.err.println("处理图片失败，仅发送文本: " + imageException.getMessage());
                        }
                    }
                    
                    // 发送组合消息
                    MessageChain messageChain = builder.build();
                    group.sendMessage(messageChain);
                }
            }
        } catch (Exception e) {
            System.err.println("发送群组消息（文本+图片）失败: " + e.getMessage());
        }
    }
}