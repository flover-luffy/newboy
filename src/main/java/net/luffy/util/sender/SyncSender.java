package net.luffy.util.sender;

import net.luffy.util.ThumbnailatorUtil;
import net.luffy.util.UnifiedHttpClient;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.MemberPermission;
import net.mamoe.mirai.message.data.AtAll;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.PlainText;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 同步发送器基类
 * 替代异步的Sender类，提供同步实现
 */
public abstract class SyncSender implements Runnable {
    public final Bot bot;
    public final long group_id;
    public final Group group;

    public SyncSender(Bot bot, long group) {
        this.bot = bot;
        this.group_id = group;
        if (bot != null)
            this.group = bot.getGroup(group);
        else
            this.group = null;
    }

    public SyncSender(Bot bot, Group group) {
        this.bot = bot;
        this.group = group;
        this.group_id = group.getId();
    }

    public InputStream getRes(String resLoc) {
        try {
            return UnifiedHttpClient.getInstance().getInputStream(resLoc);
        } catch (Exception e) {
            throw new RuntimeException("获取资源失败: " + e.getMessage(), e);
        }
    }

    public InputStream getVideoThumbnail(InputStream video, String defaultImg) {
        try {
            return ThumbnailatorUtil.generateThumbnail(video);
        } catch (IOException e) {
            return getRes(defaultImg);
        }
    }

    public Message toNotification(Message m) {
        if (this.group.getBotAsMember().getPermission() == MemberPermission.ADMINISTRATOR)
            return AtAll.INSTANCE.plus("\n").plus(m);
        return m;
    }

    public Message combine(List<Message> messages) {
        if (messages.size() == 0) {
            return null;
        } else if (messages.size() == 1)
            return messages.get(0);
        else {
            Message t = new PlainText("");
            for (int i = 0; i < messages.size(); i++) {
                Message message = messages.get(i);
                if (message != null) {
                    if (i != 0)
                        t = t.plus("\n+++++++++\n");
                    t = t.plus(message);
                }
            }
            return t;
        }
    }

    /**
     * 子类需要实现的运行逻辑
     */
    @Override
    public abstract void run();
}