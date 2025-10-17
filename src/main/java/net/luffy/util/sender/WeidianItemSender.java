package net.luffy.util.sender;

import net.luffy.Newboy;
import net.luffy.handler.WeidianHandler;
import net.luffy.handler.WeidianSenderHandler;
import net.luffy.model.WeidianCookie;
import net.luffy.model.WeidianItem;
import net.luffy.model.WeidianItemMessage;
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
            return;
        }

        if (cookie.invalid) {
            return;
        }

        WeidianItem[] items = weidian.getItems(cookie);
        if (items == null) {
            if (!cookie.invalid) {
                // 增加延迟检查机制，避免因临时网络问题导致的误报
                // 等待5秒后再次尝试，如果仍然失败才标记为失效
                try {
                    Thread.sleep(5000);
                    WeidianItem[] retryItems = weidian.getItems(cookie);
                    if (retryItems != null) {
                        // 重试成功，继续正常流程
                        items = retryItems;
                    } else {
                        // 重试仍然失败，发送详细的错误提示
                        String errorMsg = "❌ 微店Cookie已失效\n" +
                                "🔧 请使用以下命令重新设置：\n" +
                                "`/微店 " + group_id + " cookie <您的新Cookie>`\n" +
                                "💡 获取Cookie方法：\n" +
                                "1. 登录微店商家后台\n" +
                                "2. 按F12打开开发者工具\n" +
                                "3. 在Network标签页找到请求头中的Cookie\n" +
                                "4. 复制完整的Cookie值";
                        try {
                            group.getOwner().sendMessage(errorMsg);
                        } catch (Exception e) {
                            // 静默处理异常
                        }
                        cookie.invalid = true;
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                return;
            }
        }

        if (cookie.invalid) {
            try {
                group.getOwner().sendMessage("✅ 微店Cookie已恢复正常，无需更换");
            } catch (Exception e) {
                // 静默处理异常
            }
            cookie.invalid = false;
        }

        int highlightItemCount = 0;
        int processedItemCount = 0;

        //合并发送（仅特殊链）
        List<Message> messages = new ArrayList<>();
        for (WeidianItem item : items) {
            if (cookie.highlightItem.contains(item.id)) {
                // 使用单个商品的方法来正确处理图片
                WeidianItemMessage itemMessage = handler.executeItemMessages(item, group, 5);
                if (itemMessage != null) {
                    messages.add(itemMessage.getMessage());
                }
                highlightItemCount++;
            }
            processedItemCount++;
        }

        if (!messages.isEmpty()) {
            Message combinedMessage = combine(messages);
            if (combinedMessage != null) {
                try {
                    group.sendMessage(combinedMessage);
                } catch (Exception e) {
                    // 静默处理异常
                }
            }
        }
    }

}
