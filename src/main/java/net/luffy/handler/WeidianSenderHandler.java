package net.luffy.handler;

import cn.hutool.http.HttpRequest;
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

    public WeidianSenderHandler() {
    }

    public InputStream getRes(String resLoc) {
        return HttpRequest.get(resLoc).execute().bodyStream();
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
        if (!item.pic.equals("")) {
            try (ExternalResource resource = ExternalResource.create(getRes(item.pic))) {
                image = group.uploadImage(resource);
            } catch (Exception e) {
                // 静默处理异常，图片上传失败时image保持为null
            }
        }

        return WeidianItemMessage.construct(item.id, item.name, image, buyers, pickAmount);
    }

    // 新增方法：支持数组参数的executeItemMessages
    public void executeItemMessages(WeidianItem[] items, WeidianCookie cookie, long groupId) {
        // 这个方法用于处理多个商品的消息发送
        // 由于缺少Group对象，无法直接处理图片上传，建议使用单个商品的方法
        for (WeidianItem item : items) {
            // 处理每个商品 - 但无法上传图片，因为缺少Group对象
            // 这里可以添加具体的处理逻辑，但建议使用带Group参数的方法
        }
    }

    // 推荐使用的方法：支持数组参数并包含Group对象用于图片处理
    public List<WeidianItemMessage> executeItemMessagesWithGroup(WeidianItem[] items, Group group, int pickAmount) {
        List<WeidianItemMessage> messages = new ArrayList<>();
        for (WeidianItem item : items) {
            WeidianItemMessage message = executeItemMessages(item, group, pickAmount);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    public WeidianOrderMessage executeOrderMessage(WeidianOrder order, Group group) {
        return WeidianOrderMessage.construct(order);
    }

}