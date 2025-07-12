package net.luffy.util.sender;

import net.luffy.Newboy;
import net.luffy.handler.WeidianHandler;
import net.luffy.handler.WeidianSenderHandler;
import net.luffy.model.WeidianCookie;
import net.luffy.model.WeidianItem;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.message.data.Message;

import java.util.ArrayList;
import java.util.List;

public class WeidianItemSender extends Sender {

    private final WeidianSenderHandler handler;

    public WeidianItemSender(Bot bot, long group, WeidianSenderHandler handler) {
        super(bot, group);
        this.handler = handler;
    }


    @Override
    public void run() {
        WeidianHandler weidian = Newboy.INSTANCE.getHandlerWeidian();
        WeidianCookie cookie = Newboy.INSTANCE.getProperties().weidian_cookie.get(group_id);

        WeidianItem[] items = weidian.getItems(cookie);
        if (items == null) {
            if (!cookie.invalid) {
                group.getOwner().sendMessage("微店Cookie失效，请尽快更换：“/微店 " + group_id + " cookie <Cookie>”");
                cookie.invalid = true;
            }
            return;
        }

        if (cookie.invalid) {
            group.getOwner().sendMessage("微店Cookie有效，无需更换");
            cookie.invalid = false;
        }

        //合并发送（仅特殊链）
        List<Message> messages = new ArrayList<>();
        for (WeidianItem item : items) {
            if (cookie.highlightItem.contains(item.id) && !cookie.shieldedItem.contains(item.id)) {
                messages.add(handler.executeItemMessages(item, group, 10).getMessage());
            }
        }
        Message t = combine(messages);
        if (t != null)
            group.sendMessage(t);
    }

}
