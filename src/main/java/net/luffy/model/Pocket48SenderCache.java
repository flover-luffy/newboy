package net.luffy.model;

import net.luffy.Newboy;
import net.luffy.handler.Pocket48Handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.luffy.util.UnifiedSchedulerManager;

import static net.luffy.model.EndTime.newTime;

public class Pocket48SenderCache {

    public final Pocket48RoomInfo roomInfo;
    public final List<Long> voiceList;
    public Pocket48Message[] messages;
    private final HashMap<Long, Long> endTime;
    private final long creationTime;
    private static final ScheduledExecutorService cacheRefreshExecutor = UnifiedSchedulerManager.getInstance().getScheduledExecutor();
    private static final long CACHE_REFRESH_INTERVAL_MINUTES = 3; // 优化为3分钟刷新一次，提升实时性

    public Pocket48SenderCache(Pocket48RoomInfo roomInfo, Pocket48Message[] messages, List<Long> voiceList) {
        this.roomInfo = roomInfo;
        this.messages = messages;
        this.voiceList = voiceList;
        this.endTime = new HashMap<>();
        this.endTime.put(roomInfo.getRoomId(), newTime());
        this.creationTime = System.currentTimeMillis();
    }/**
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

            // 第二步：初始化时间戳 - 回退：使用当前时间避免反复推送最后一条消息
            if (!endTime.containsKey(roomID)) {
                // 使用当前时间戳，避免处理历史消息和反复推送最新消息
                endTime.put(roomID, System.currentTimeMillis());
            }

            // 第三步：获取消息列表（非关键步骤，失败可使用空数组）
            Pocket48Message[] messages = new Pocket48Message[0]; // 默认为空数组
            try {
                Pocket48Message[] fetchedMessages = pocket.getMessagesAsync(roomInfo, endTime).get();
                if (fetchedMessages != null) {
                    messages = fetchedMessages;
                    // 移除正常情况下的信息日志，减少日志噪音
                } else {
                    System.out.println(String.format("[警告] 房间 %d 消息获取返回null", roomID));
                }
            } catch (Exception e) {
                // 记录消息获取异常而不是静默处理
                System.err.println(String.format("[错误] 房间 %d 消息获取失败: %s", 
                    roomID, e.getMessage()));
            }

            // 第四步：获取语音列表（非关键步骤，失败可使用空列表）
            List<Long> voiceList = new ArrayList<>(); // 默认为空列表
            try {
                List<Long> fetchedVoiceList = pocket.getRoomVoiceList(roomID, roomInfo.getSeverId());
                if (fetchedVoiceList != null) {
                    voiceList = fetchedVoiceList;
                    // 移除正常情况下的信息日志，减少日志噪音
                } else {
                    System.out.println(String.format("[警告] 房间 %d 语音列表获取返回null", roomID));
                }
            } catch (Exception e) {
                // 记录语音列表获取异常而不是静默处理
                System.err.println(String.format("[错误] 房间 %d 语音列表获取失败: %s", 
                    roomID, e.getMessage()));
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
    
    /**
     * 检查缓存是否过期
     * @return 如果缓存超过刷新间隔时间则返回true
     */
    public boolean isExpired() {
        long currentTime = System.currentTimeMillis();
        long ageMinutes = (currentTime - creationTime) / (1000 * 60);
        return ageMinutes >= CACHE_REFRESH_INTERVAL_MINUTES;
    }
    
    /**
     * 启动缓存刷新任务
     * @param pocket Pocket48Handler实例
     * @param roomInfo 房间信息
     * @param cacheUpdateCallback 缓存更新回调
     */
    public static void startCacheRefreshTask(Pocket48Handler pocket, Pocket48RoomInfo roomInfo, 
                                           CacheUpdateCallback cacheUpdateCallback) {
        cacheRefreshExecutor.scheduleAtFixedRate(() -> {
            try {
                // 移除正常情况下的缓存刷新开始日志，减少日志噪音
                HashMap<Long, Long> endTimeMap = new HashMap<>();
                endTimeMap.put(roomInfo.getRoomId(), System.currentTimeMillis());
                Pocket48SenderCache newCache = create(roomInfo.getRoomId(), endTimeMap);
                if (newCache != null) {
                    cacheUpdateCallback.onCacheUpdated(newCache);
                    // 移除正常情况下的缓存刷新成功日志，减少日志噪音
                } else {
                    System.err.println(String.format("[错误] 房间 %d 缓存刷新失败", roomInfo.getRoomId()));
                }
            } catch (Exception e) {
                System.err.println(String.format("[错误] 房间 %d 缓存刷新异常: %s", 
                    roomInfo.getRoomId(), e.getMessage()));
            }
        }, CACHE_REFRESH_INTERVAL_MINUTES, CACHE_REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }
    
    /**
     * 关闭缓存刷新执行器
     * 注意：现在由UnifiedSchedulerManager统一管理，无需显式关闭
     */
    public static void shutdownCacheRefreshExecutor() {
        System.out.println("[INFO] Pocket48SenderCache线程池现由UnifiedSchedulerManager统一管理，无需显式关闭");
        // cacheRefreshExecutor现在由UnifiedSchedulerManager管理，在系统关闭时会自动处理
    }
    
    /**
     * 缓存更新回调接口
     */
    public interface CacheUpdateCallback {
        void onCacheUpdated(Pocket48SenderCache newCache);
    }
}
