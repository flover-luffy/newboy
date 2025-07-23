package net.luffy.util.sender;

// HttpRequest已迁移到异步处理器
import net.luffy.util.ThumbnailatorUtil;
import net.luffy.handler.AsyncWebHandlerBase;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.contact.MemberPermission;
import net.mamoe.mirai.message.data.AtAll;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.PlainText;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class Sender extends AsyncWebHandlerBase implements Runnable { //异步进程
    public final Bot bot;
    public final long group_id;
    public final Group group;

    public Sender(Bot bot, long group) {
        this.bot = bot;
        this.group_id = group;
        if (bot != null)
            this.group = bot.getGroup(group);
        else
            this.group = null;
    }

    public Sender(Bot bot, Group group) {
        this.bot = bot;
        this.group = group;
        this.group_id = group.getId();
    }

    public InputStream getRes(String resLoc) {
        return getInputStream(resLoc);
    }

    public InputStream getVideoThumbnail(InputStream video, String defaultImg) {
        try {
            return ThumbnailatorUtil.generateThumbnail(video);
        } catch (IOException e) {
            return getRes(defaultImg);
        }
    }

    public Message toNotification(Message m) {
        MemberPermission permission = this.group.getBotAsMember().getPermission();
        if (permission == MemberPermission.ADMINISTRATOR || permission == MemberPermission.OWNER)
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
     * 默认的run方法实现，子类应该重写此方法
     */
    @Override
    public void run() {
        // 默认实现为空，子类应该重写此方法
    }
}
