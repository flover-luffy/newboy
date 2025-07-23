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

    public static Pocket48SenderCache create(long roomID, HashMap<Long, Long> endTime) {
        Pocket48Handler pocket = Newboy.INSTANCE.getHandlerPocket48();

        try {
            // 第一步：获取房间信息
            Pocket48RoomInfo roomInfo = pocket.getRoomInfoByChannelID(roomID);
            if (roomInfo == null) {
                // System.err.println("[缓存创建失败] 房间ID: " + roomID + " - 无法获取房间信息，可能原因：房间不存在、网络错误或API限制");
                return null;
            }
            if (roomInfo.getSeverId() == 0) {
                // System.err.println("[缓存创建失败] 房间ID: " + roomID + " - 服务器ID为0，房间可能已被删除或无效");
                return null;
            }

            // 第二步：初始化时间戳
            if (!endTime.containsKey(roomID)) {
                endTime.put(roomID, newTime());
            }

            // 第三步：获取消息列表
            Pocket48Message[] messages;
            try {
                messages = pocket.getMessages(roomInfo, endTime);
                if (messages == null) {
                    // System.err.println("[缓存创建警告] 房间ID: " + roomID + " - 消息获取返回null，使用空数组");
                    messages = new Pocket48Message[0];
                }
            } catch (Exception e) {
                // System.err.println("[缓存创建失败] 房间ID: " + roomID + " - 消息获取异常: " + e.getMessage());
                return null;
            }

            // 第四步：获取语音列表
            List<Long> voiceList;
            try {
                voiceList = pocket.getRoomVoiceList(roomID, roomInfo.getSeverId());
                if (voiceList == null) {
                    // System.err.println("[缓存创建警告] 房间ID: " + roomID + " - 语音列表获取返回null，使用空列表");
                    voiceList = new ArrayList<>();
                }
            } catch (Exception e) {
                // System.err.println("[缓存创建失败] 房间ID: " + roomID + " - 语音列表获取异常: " + e.getMessage());
                return null;
            }

            // 已禁用缓存创建成功的控制台输出
            // System.out.println("[缓存创建成功] 房间ID: " + roomID + " - 房间名: " + roomInfo.getRoomName() + ", 消息数: " + messages.length + ", 语音用户数: " + voiceList.size());
            return new Pocket48SenderCache(roomInfo, messages, voiceList);
            
        } catch (Exception e) {
            // System.err.println("[缓存创建失败] 房间ID: " + roomID + " - 未知异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            // e.printStackTrace();
            return null;
        }
    }

    public void addMessage(Pocket48Message message) {
        List<Pocket48Message> messages1 = new ArrayList<>(Arrays.asList(this.messages));
        messages1.add(message);
        this.messages = messages1.toArray(new Pocket48Message[0]);
    }
}
