package net.luffy.handler;

import net.luffy.Newboy;
import net.luffy.model.*;
import net.luffy.util.sender.MessageSender;
import net.luffy.util.UnifiedHttpClient;
import net.mamoe.mirai.contact.Group;
import net.mamoe.mirai.message.data.Image;
import net.mamoe.mirai.utils.ExternalResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class WeidianSenderHandler {
    
    private MessageSender messageSender;

    public WeidianSenderHandler() {
        this.messageSender = new MessageSender();
    }

    private void logInfo(String message) {
        Newboy.INSTANCE.getLogger().info(message);
    }

    public InputStream getRes(String resLoc) {
        try {
            return UnifiedHttpClient.getInstance().getInputStream(resLoc);
        } catch (Exception e) {
            throw new RuntimeException("获取资源失败: " + e.getMessage(), e);
        }
    }

    //普链订单播报, pickAmount = 5
    public WeidianItemMessage executeItemMessages(WeidianItem item, Group group) {
        return this.executeItemMessages(item, group, 5);
    }

    public WeidianItemMessage executeItemMessages(WeidianItem item, Group group, int pickAmount) {
        WeidianHandler weidian = Newboy.INSTANCE.getHandlerWeidian();
        WeidianCookie cookie = Newboy.INSTANCE.getProperties().weidian_cookie.get(group.getId());

        //统计总值
        long id = item.id;
        WeidianBuyer[] buyers = weidian.getItemBuyer(cookie, id);

        Image image = null;
        if (item.pic != null && !item.pic.equals("")) {
            try (ExternalResource imageResource = ExternalResource.create(getRes(item.pic))) {
                image = group.uploadImage(imageResource);
            } catch (Exception e) {
                // 忽略图片上传失败
            }
        }

        return WeidianItemMessage.construct(item.id, item.name, image, buyers, pickAmount);
    }

    public void executeOrderMessage(WeidianOrder[] orders, WeidianCookie cookie, long groupId) {
        if (orders == null || orders.length == 0) {
            return;
        }

        Map<Long, List<WeidianOrder>> buyerOrderMap = new HashMap<>();
        for (WeidianOrder order : orders) {
            buyerOrderMap.computeIfAbsent(order.buyerID, k -> new ArrayList<>()).add(order);
        }

        for (Map.Entry<Long, List<WeidianOrder>> entry : buyerOrderMap.entrySet()) {
            Long buyerId = entry.getKey();
            List<WeidianOrder> buyerOrders = entry.getValue();

            StringBuilder messageBuilder = new StringBuilder();
            messageBuilder.append("微店新订单播报\n");
            messageBuilder.append("买家：").append(buyerOrders.get(0).buyerName).append("\n");
            messageBuilder.append("商品：\n");

            double totalAmount = 0;
            for (WeidianOrder order : buyerOrders) {
                messageBuilder.append("  ").append(order.itemName).append(" - ¥").append(order.price).append("\n");
                totalAmount += order.price;
            }

            messageBuilder.append("总金额：¥").append(totalAmount).append("\n");
            messageBuilder.append("支付时间：").append(buyerOrders.get(0).getPayTimeStr());

            String message = messageBuilder.toString();

            try {
                messageSender.sendGroupMessage(String.valueOf(groupId), message);
            } catch (Exception e) {
                // 静默处理发送失败
            }
        }
    }

    public void executeItemMessages(WeidianItem[] items, WeidianCookie cookie, long groupId) {
        if (items == null || items.length == 0) {
            return;
        }

        // 分离高亮商品和普通商品
        List<WeidianItem> highlightedItems = new ArrayList<>();
        List<WeidianItem> normalItems = new ArrayList<>();
        
        for (WeidianItem item : items) {
            if (item.highlighted) {
                highlightedItems.add(item);
            } else {
                normalItems.add(item);
            }
        }

        // 发送高亮商品
        if (!highlightedItems.isEmpty()) {
            for (WeidianItem item : highlightedItems) {
                try {
                    String message = buildItemMessage(item, true);
                    messageSender.sendGroupMessage(String.valueOf(groupId), message);
                    
                    // 上传商品图片
                    if (item.pic != null && !item.pic.isEmpty()) {
                        try {
                            messageSender.sendGroupImage(String.valueOf(groupId), item.pic);
                        } catch (Exception e) {
                            // 静默处理图片发送失败
                        }
                    }
                } catch (Exception e) {
                    // 静默处理消息发送失败
                }
            }
        }

        // 发送普通商品（批量）
        if (!normalItems.isEmpty()) {
            try {
                StringBuilder messageBuilder = new StringBuilder();
                messageBuilder.append("微店商品播报\n");
                messageBuilder.append("共 ").append(normalItems.size()).append(" 件商品：\n");
                
                for (int i = 0; i < Math.min(normalItems.size(), 10); i++) { // 限制显示前10个商品
                    WeidianItem item = normalItems.get(i);
                    messageBuilder.append((i + 1)).append(". ").append(item.name).append(" - ¥").append(item.price).append("\n");
                }
                
                if (normalItems.size() > 10) {
                    messageBuilder.append("... 还有 ").append(normalItems.size() - 10).append(" 件商品");
                }
                
                String message = messageBuilder.toString();
                messageSender.sendGroupMessage(String.valueOf(groupId), message);
            } catch (Exception e) {
                // 静默处理批量消息发送失败
            }
        }
    }

    private String buildItemMessage(WeidianItem item, boolean isHighlighted) {
        StringBuilder messageBuilder = new StringBuilder();
        if (isHighlighted) {
            messageBuilder.append("⭐ 推荐商品 ⭐\n");
        } else {
            messageBuilder.append("微店商品播报\n");
        }
        messageBuilder.append("商品名称：").append(item.name).append("\n");
        messageBuilder.append("价格：¥").append(item.price).append("\n");
        messageBuilder.append("商品ID：").append(item.id);
        return messageBuilder.toString();
    }

    public void sendOrderMessage(long groupId, WeidianOrder[] orders) {
        if (orders == null || orders.length == 0) {
            return;
        }

        // 按买家分组订单
        Map<Long, List<WeidianOrder>> buyerOrderMap = new HashMap<>();
        for (WeidianOrder order : orders) {
            buyerOrderMap.computeIfAbsent(order.buyerID, k -> new ArrayList<>()).add(order);
        }

        for (Map.Entry<Long, List<WeidianOrder>> entry : buyerOrderMap.entrySet()) {
            Long buyerId = entry.getKey();
            List<WeidianOrder> buyerOrders = entry.getValue();

            // 构建订单消息
            StringBuilder message = new StringBuilder();
            message.append("🛒 新订单通知\n");
            message.append("👤 买家：").append(buyerOrders.get(0).buyerName).append("\n");
            message.append("📦 商品：\n");

            double totalAmount = 0;
            for (WeidianOrder order : buyerOrders) {
                message.append("  • ").append(order.itemName).append(" - ¥").append(order.price).append("\n");
                totalAmount += order.price;
            }

            message.append("💰 总金额：¥").append(totalAmount);

            try {
                messageSender.sendGroupMessage(String.valueOf(groupId), message.toString());
            } catch (Exception e) {
                // 静默处理发送失败
            }
        }
    }

    public void sendItemMessage(long groupId, WeidianItem[] items) {
        if (items == null || items.length == 0) {
            return;
        }

        // 分离高亮商品和普通商品
        List<WeidianItem> highlightedItems = new ArrayList<>();
        List<WeidianItem> normalItems = new ArrayList<>();

        for (WeidianItem item : items) {
            if (item.highlighted) {
                highlightedItems.add(item);
            } else {
                normalItems.add(item);
            }
        }

        // 发送高亮商品（单独发送）
        for (WeidianItem item : highlightedItems) {
            try {
                String message = "⭐ 特殊商品更新\n📦 " + item.name + "\n💰 价格：¥" + item.price + "\n🆔 ID：" + item.id;
                messageSender.sendGroupMessage(String.valueOf(groupId), message);

                // 尝试发送商品图片
                if (item.pic != null && !item.pic.isEmpty()) {
                    try {
                        messageSender.sendGroupImage(String.valueOf(groupId), item.pic);
                    } catch (Exception e) {
                        // 静默处理图片发送失败
                    }
                }
            } catch (Exception e) {
                // 静默处理消息发送失败
            }
        }

        // 发送普通商品（批量发送）
        if (!normalItems.isEmpty()) {
            try {
                StringBuilder message = new StringBuilder();
                message.append("📦 商品更新通知\n");
                message.append("共 ").append(normalItems.size()).append(" 个商品：\n");

                for (int i = 0; i < normalItems.size(); i++) {
                    WeidianItem item = normalItems.get(i);
                    message.append(i + 1).append(". ").append(item.name).append(" - ¥").append(item.price).append("\n");
                }

                messageSender.sendGroupMessage(String.valueOf(groupId), message.toString());
            } catch (Exception e) {
                // 静默处理批量消息发送失败
            }
        }
    }
}