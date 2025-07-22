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

public class WeidianItemSender extends SyncSender {

    private final WeidianSenderHandler handler;

    public WeidianItemSender(Bot bot, long group, WeidianSenderHandler handler) {
        super(bot, group);
        this.handler = handler;
    }


    @Override
    public void run() {
        WeidianHandler weidian = Newboy.INSTANCE.getHandlerWeidian();
        WeidianCookie cookie = Newboy.INSTANCE.getProperties().weidian_cookie.get(group_id);
        
        if (cookie == null) {
            // 没有配置cookie，静默返回
            return;
        }

        WeidianItem[] items = weidian.getItems(cookie);
        if (items == null) {
            if (!cookie.invalid) {
                // 发送详细的错误提示
                String errorMsg = "❌ 微店Cookie已失效\n" +
                        "🔧 请使用以下命令重新设置：\n" +
                        "`/微店 " + group_id + " cookie <您的新Cookie>`\n" +
                        "💡 获取Cookie方法：\n" +
                        "1. 登录微店商家后台\n" +
                        "2. 按F12打开开发者工具\n" +
                        "3. 在Network标签页找到请求头中的Cookie\n" +
                        "4. 复制完整的Cookie值";
                group.getOwner().sendMessage(errorMsg);
                cookie.invalid = true;
            } else {
                // 即使已标记为invalid，也要记录调试信息
                Newboy.INSTANCE.getLogger().info("[微店调试] Cookie已标记为失效，但仍在尝试获取商品列表");
            }
            return;
        }

        if (cookie.invalid) {
            group.getOwner().sendMessage("✅ 微店Cookie已恢复正常，无需更换");
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
