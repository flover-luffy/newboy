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
     * 静默处理错误，减少控制台噪音
     * 
     * @param roomID 房间ID
     * @param endTime 结束时间映射
     * @return 创建的缓存对象，如果关键步骤失败则返回null
     */
    public static Pocket48SenderCache create(long roomID, HashMap<Long, Long> endTime) {
        Pocket48Handler pocket = Newboy.INSTANCE.getHandlerPocket48();

        try {
            // 第一步：获取房间信息（关键步骤，失败则静默返回null）
            Pocket48RoomInfo roomInfo = pocket.getRoomInfoByChannelID(roomID);
            if (roomInfo == null) {
                // 静默处理房间不存在的情况，不输出错误信息
                return null;
            }
            // 移除对serverId的检查，允许加密房间（serverId为0或null）正常创建缓存
            // 加密房间现在可以正常处理，只是消息和语音列表会为空

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
            } catch (Exception e) {
                // 静默处理消息获取异常
            }

            // 第四步：获取语音列表（非关键步骤，失败可使用空列表）
            List<Long> voiceList = new ArrayList<>(); // 默认为空列表
            try {
                List<Long> fetchedVoiceList = pocket.getRoomVoiceList(roomID, roomInfo.getSeverId());
                if (fetchedVoiceList != null) {
                    voiceList = fetchedVoiceList;
                }
            } catch (Exception e) {
                // 静默处理语音列表获取异常
            }

            // 创建缓存对象
            return new Pocket48SenderCache(roomInfo, messages, voiceList);
            
        } catch (Exception e) {
            // 静默处理所有异常，避免控制台噪音
            return null;
        }
    }

    public void addMessage(Pocket48Message message) {
        List<Pocket48Message> messages1 = new ArrayList<>(Arrays.asList(this.messages));
        messages1.add(message);
        this.messages = messages1.toArray(new Pocket48Message[0]);
    }
}
