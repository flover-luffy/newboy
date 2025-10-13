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

        if (cookie == null) {
            return;
        }

        if (cookie.invalid) {
            return;
        }

        // 每次都重新获取订单，确保能检测到新订单
        WeidianOrder[] orders = weidian.getOrderList(cookie, endTime);
        if (orders != null) {
            cache.put(cookie, orders);
        } else {
            // 如果获取失败，尝试使用缓存的订单
            orders = cache.get(cookie);
        }
        if (orders == null) {
            return;
        }
        
        if (bot == null) {
            return;
        }

        List<WeidianMessage> messages = new ArrayList<>();
        List<Long> itemIDs = new ArrayList<>();
        int processedOrderCount = 0;
        int shieldedOrderCount = 0;



        for (int i = orders.length - 1; i >= 0; i--) {
            long id = orders[i].itemID;
            if (cookie.shieldedItem.contains(id)) {
                shieldedOrderCount++;
                continue;
            }

            if (!itemIDs.contains(id))
                itemIDs.add(id);
            //订单信息 - 创建订单消息并添加到列表中
            WeidianOrderMessage orderMessage = WeidianOrderMessage.construct(orders[i]);
            messages.add(orderMessage);
            processedOrderCount++;
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
                    // 使用单个商品的方法来正确处理图片
                    WeidianItemMessage itemMessage = handler.executeItemMessages(item, group, 5);
                    if (itemMessage != null) {
                        messages.add(itemMessage);
                    }
                    // 获取商品买家信息
                    itemBuyers.put(id, weidian.getItemBuyer(cookie, id));
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
            try {
                group.sendMessage(t);
            } catch (Exception e) {
                // 静默处理异常
            }
        }
    }

}
