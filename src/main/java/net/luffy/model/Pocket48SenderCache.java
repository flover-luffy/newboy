package net.luffy.model;

import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static net.luffy.model.EndTime.newTime;

public class Pocket48SenderCache {

    public final Pocket48RoomInfo roomInfo;
    public final List<Long> voiceList;
    public Pocket48Message[] messages;

    public Pocket48SenderCache(Pocket48RoomInfo roomInfo, Pocket48Message[] messages, List<Long> voiceList) {
        this.roomInfo = roomInfo;
        this.messages = messages;
        this.voiceList = voiceList;
    }

    /**
     * 创建口袋48发送者缓存（优化版）
     * 允许部分成功的情况下仍然创建缓存，提高缓存创建成功率
     * 
     * @param roomID 房间ID
     * @param endTime 结束时间映射
     * @return 创建的缓存对象，如果关键步骤失败则返回null
     */
    public static Pocket48SenderCache create(long roomID, HashMap<Long, Long> endTime) {
        Pocket48Handler pocket = Newboy.INSTANCE.getHandlerPocket48();

        try {
            // 第一步：获取房间信息（关键步骤，失败则抛出异常）
            Pocket48RoomInfo roomInfo = pocket.getRoomInfoByChannelID(roomID);
            if (roomInfo == null) {
                throw new RuntimeException("无法获取房间信息，房间ID: " + roomID + "，可能原因：房间不存在、网络错误或API限制");
            }
            if (roomInfo.getSeverId() == 0) {
                throw new RuntimeException("服务器ID为0，房间ID: " + roomID + "，房间可能已被删除或无效");
            }

            // 第二步：初始化时间戳
            if (!endTime.containsKey(roomID)) {
                endTime.put(roomID, newTime());
            }

            // 第三步：获取消息列表（非关键步骤，失败可使用空数组）
            Pocket48Message[] messages = new Pocket48Message[0]; // 默认为空数组
            try {
                Pocket48Message[] fetchedMessages = pocket.getMessages(roomInfo, endTime);
                if (fetchedMessages != null) {
                    messages = fetchedMessages;
                }
                // 静默处理消息获取失败，使用空数组
            } catch (Exception e) {
                // 静默处理消息获取异常，使用空数组继续
            }

            // 第四步：获取语音列表（非关键步骤，失败可使用空列表）
            List<Long> voiceList = new ArrayList<>(); // 默认为空列表
            try {
                List<Long> fetchedVoiceList = pocket.getRoomVoiceList(roomID, roomInfo.getSeverId());
                if (fetchedVoiceList != null) {
                    voiceList = fetchedVoiceList;
                }
                // 静默处理语音列表获取失败，使用空列表
            } catch (Exception e) {
                // 静默处理语音列表获取异常，使用空列表继续
            }

            // 创建缓存对象
            Pocket48SenderCache cache = new Pocket48SenderCache(roomInfo, messages, voiceList);
            
            return cache;
            
        } catch (RuntimeException e) {
            // 重新抛出运行时异常
            throw e;
        } catch (Exception e) {
            // 将其他异常包装为运行时异常
            throw new RuntimeException("缓存创建失败，房间ID: " + roomID + "，异常: " + e.getMessage(), e);
        }
    }

    public void addMessage(Pocket48Message message) {
        List<Pocket48Message> messages1 = new ArrayList<>(Arrays.asList(this.messages));
        messages1.add(message);
        this.messages = messages1.toArray(new Pocket48Message[0]);
    }
}
