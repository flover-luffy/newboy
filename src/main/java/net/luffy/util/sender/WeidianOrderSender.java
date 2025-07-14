package net.luffy.util.sender;

import net.luffy.Newboy;
import net.luffy.handler.WeidianHandler;
import net.luffy.handler.WeidianSenderHandler;
import net.luffy.model.*;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.message.data.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class WeidianOrderSender extends SyncSender {
    private final EndTime endTime;
    private final WeidianSenderHandler handler;
    private final HashMap<WeidianCookie, WeidianOrder[]> cache;

    public WeidianOrderSender(Bot bot, long group, EndTime endTime, WeidianSenderHandler handler, HashMap<WeidianCookie, WeidianOrder[]> cache) {
        super(bot, group);
        this.endTime = endTime;
        this.handler = handler;
        this.cache = cache;
    }

    public static WeidianItem search(WeidianItem[] items, long id) {
        for (WeidianItem item : items) {
            if (item.id == id)
                return item;
        }
        return null;
    }

    @Override
    public void run() {
        WeidianHandler weidian = Newboy.INSTANCE.getHandlerWeidian();
        WeidianCookie cookie = Newboy.INSTANCE.getProperties().weidian_cookie.get(group_id);
        
        // Newboy.INSTANCE.getLogger().info("[微店订单播报] 群组 " + group_id + " 开始检查订单");

        if (!cache.containsKey(cookie)) {
            cache.put(cookie, weidian.getOrderList(cookie, endTime));
        }

        WeidianOrder[] orders = cache.get(cookie);
        if (orders == null) {
            // Newboy.INSTANCE.getLogger().warning("[微店订单播报] 群组 " + group_id + " 获取订单失败");
            return;
        }
        if (bot == null) {
            // Newboy.INSTANCE.getLogger().info("[微店订单播报] 群组 " + group_id + " 仅执行自动发货，不播报消息");
            return;
        }
        
        // Newboy.INSTANCE.getLogger().info("[微店订单播报] 群组 " + group_id + " 获取到 " + orders.length + " 个订单");

        List<WeidianMessage> messages = new ArrayList<>();
        List<Long> itemIDs = new ArrayList<>();

        for (int i = orders.length - 1; i >= 0; i--) {
            long id = orders[i].itemID;
            if (cookie.shieldedItem.contains(id)) {
                continue;
            }

            if (!itemIDs.contains(id))
                itemIDs.add(id);
            //订单信息
            messages.add(handler.executeOrderMessage(orders[i], group));
        }

        //处理排名

        HashMap<Long, WeidianBuyer[]> itemBuyers = new HashMap<>();
        WeidianItem[] items = weidian.getItems(cookie);
        for (Long id : itemIDs) {
            WeidianItem item = search(items, id);
            if (item != null) {
                if (cookie.highlightItem.contains(id)) {//特殊链
                    itemBuyers.put(id, weidian.getItemBuyer(cookie, id));
                } else { //普链
                    WeidianItemMessage itemMessage = handler.executeItemMessages(item, group); //内包含weidian.getItemBuyer(cookie, id)
                    messages.add(itemMessage); //普链商品信息附在最后
                    itemBuyers.put(id, itemMessage.buyers);
                }
            }
        }

        List<Message> messages1 = new ArrayList<>();
        for (WeidianMessage message : messages) {
            if (message instanceof WeidianOrderMessage) {
                long id = ((WeidianOrderMessage) message).itemId;
                messages1.add(((WeidianOrderMessage) message).getMessage(itemBuyers.get(id)));
            } else {
                messages1.add(message.getMessage());
            }
        }

        Message t = combine(messages1);
        if (t != null) {
            group.sendMessage(t);
            // Newboy.INSTANCE.getLogger().info("[微店订单播报] 群组 " + group_id + " 成功发送播报消息，包含 " + messages1.size() + " 条消息");
        } else {
            // Newboy.INSTANCE.getLogger().info("[微店订单播报] 群组 " + group_id + " 没有新订单需要播报");
        }
    }

}
