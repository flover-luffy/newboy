package net.luffy.util.summary;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 每日总结数据
 * 封装完整的每日统计信息，用于生成总结报告和图片
 */
public class DailySummaryData {
    
    private final LocalDate date;
    private final long totalMessages;
    private final List<RoomDailyStats> roomStats;
    private final Map<Integer, Integer> activeHours;
    private final RoomDailyStats mostActiveRoom;
    private final Map<String, Integer> messageTypes;
    
    public DailySummaryData(LocalDate date, long totalMessages, List<RoomDailyStats> roomStats,
                           Map<Integer, Integer> activeHours, RoomDailyStats mostActiveRoom,
                           Map<String, Integer> messageTypes) {
        this.date = date;
        this.totalMessages = totalMessages;
        this.roomStats = roomStats != null ? new ArrayList<>(roomStats) : new ArrayList<>();
        this.activeHours = activeHours != null ? new HashMap<>(activeHours) : new HashMap<>();
        this.mostActiveRoom = mostActiveRoom;
        this.messageTypes = messageTypes != null ? new HashMap<>(messageTypes) : new HashMap<>();
    }
    
    /**
     * 生成文本总结
     */
    public String generateTextSummary() {
        StringBuilder summary = new StringBuilder();
        
        // 标题
        summary.append("📊 ").append(date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")))
               .append(" 口袋48监控总结\n\n");
        
        // 总体统计
        summary.append("📈 总体数据:\n");
        summary.append("  • 总消息数: ").append(totalMessages).append(" 条\n");
        summary.append("  • 活跃房间数: ").append(roomStats.size()).append(" 个\n");
        
        if (mostActiveRoom != null) {
            summary.append("  • 最活跃房间: ").append(mostActiveRoom.getRoomName())
                   .append(" (").append(mostActiveRoom.getTotalMessages()).append(" 条)\n");
        }
        
        // 消息类型分布
        if (!messageTypes.isEmpty()) {
            summary.append("\n📝 消息类型分布:\n");
            messageTypes.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(entry -> {
                        double percentage = (double) entry.getValue() / totalMessages * 100;
                        summary.append(String.format("  • %s: %d 条 (%.1f%%)\n", 
                                entry.getKey(), entry.getValue(), percentage));
                    });
        }
        
        // 活跃时段
        if (!activeHours.isEmpty()) {
            summary.append("\n⏰ 活跃时段分析:\n");
            String peakHour = findPeakHour();
            summary.append("  • 最活跃时段: ").append(peakHour).append("\n");
            
            // 显示前3个活跃时段
            activeHours.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                    .limit(3)
                    .forEach(entry -> {
                        summary.append(String.format("  • %02d:00-%02d:00: %d 条消息\n",
                                entry.getKey(), (entry.getKey() + 1) % 24, entry.getValue()));
                    });
        }
        
        // 房间排行榜
        summary.append("\n🏆 房间活跃度排行:\n");
        roomStats.stream()
                .limit(5)
                .forEach(room -> {
                    double percentage = totalMessages > 0 ? (double) room.getTotalMessages() / totalMessages * 100 : 0;
                    summary.append(String.format("  %d. %s (%s): %d 条 (%.1f%%)\n",
                            roomStats.indexOf(room) + 1,
                            room.getRoomName(),
                            room.getOwnerName(),
                            room.getTotalMessages(),
                            percentage));
                });
        
        // 详细房间信息
        if (roomStats.size() > 0) {
            summary.append("\n📋 详细房间信息:\n");
            for (RoomDailyStats room : roomStats) {
                if (room.getTotalMessages() > 0) {
                    summary.append(String.format("\n🏠 %s (%s):\n", room.getRoomName(), room.getOwnerName()));
                    summary.append(String.format("  • 总消息: %d 条\n", room.getTotalMessages()));
                    summary.append(String.format("  • 文本: %d, 图片: %d, 音频: %d, 视频: %d\n",
                            room.getTextMessages(), room.getImageMessages(),
                            room.getAudioMessages(), room.getVideoMessages()));
                    summary.append("  • 活跃时段: ").append(room.getActiveTimeDescription()).append("\n");
                    
                    // 显示活跃成员
                    List<RoomDailyStats.MemberActivity> topMembers = room.getTopActiveMembers(3);
                    if (!topMembers.isEmpty()) {
                        summary.append("  • 活跃成员: ");
                        for (int i = 0; i < topMembers.size(); i++) {
                            RoomDailyStats.MemberActivity member = topMembers.get(i);
                            if (i > 0) summary.append(", ");
                            summary.append(member.getMemberName()).append("(").append(member.getMessageCount()).append(")");
                        }
                        summary.append("\n");
                    }
                    
                    // 显示热门关键词
                    List<RoomDailyStats.KeywordFrequency> topKeywords = room.getTopKeywords(5);
                    if (!topKeywords.isEmpty()) {
                        summary.append("  • 热门话题: ");
                        for (int i = 0; i < topKeywords.size(); i++) {
                            RoomDailyStats.KeywordFrequency keyword = topKeywords.get(i);
                            if (i > 0) summary.append(", ");
                            summary.append(keyword.getKeyword()).append("(").append(keyword.getFrequency()).append(")");
                        }
                        summary.append("\n");
                    }
                }
            }
        }
        
        // 总结
        summary.append("\n💡 今日亮点:\n");
        if (totalMessages > 100) {
            summary.append("  • 今日消息量较高，粉丝互动活跃\n");
        }
        if (roomStats.size() > 3) {
            summary.append("  • 多个房间同时活跃，内容丰富多样\n");
        }
        if (mostActiveRoom != null && mostActiveRoom.getTotalMessages() > totalMessages * 0.5) {
            summary.append("  • ").append(mostActiveRoom.getRoomName()).append(" 成为今日焦点房间\n");
        }
        
        summary.append("\n📅 报告生成时间: ").append(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        
        return summary.toString();
    }
    
    /**
     * 找到最活跃的时段
     */
    private String findPeakHour() {
        if (activeHours.isEmpty()) return "无数据";
        
        int peakHour = activeHours.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(12);
        
        String timeRange;
        if (peakHour >= 6 && peakHour < 12) {
            timeRange = "上午";
        } else if (peakHour >= 12 && peakHour < 18) {
            timeRange = "下午";
        } else if (peakHour >= 18 && peakHour < 24) {
            timeRange = "晚上";
        } else {
            timeRange = "深夜";
        }
        
        return String.format("%s %02d:00-%02d:00", timeRange, peakHour, (peakHour + 1) % 24);
    }
    
    /**
     * 获取图表数据
     */
    public ChartData getChartData() {
        return new ChartData(this);
    }
    
    // Getters
    public LocalDate getDate() { return date; }
    public long getTotalMessages() { return totalMessages; }
    public List<RoomDailyStats> getRoomStats() { return new ArrayList<>(roomStats); }
    public Map<Integer, Integer> getActiveHours() { return new HashMap<>(activeHours); }
    public RoomDailyStats getMostActiveRoom() { return mostActiveRoom; }
    public Map<String, Integer> getMessageTypes() { return new HashMap<>(messageTypes); }
    
    /**
     * 转换为JSON
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("date", date.format(DateTimeFormatter.ISO_LOCAL_DATE));
        json.put("totalMessages", totalMessages);
        json.put("activeHours", activeHours);
        json.put("messageTypes", messageTypes);
        
        if (mostActiveRoom != null) {
            json.put("mostActiveRoom", mostActiveRoom.toJson());
        }
        
        JSONArray roomArray = new JSONArray();
        for (RoomDailyStats room : roomStats) {
            roomArray.add(room.toJson());
        }
        json.put("roomStats", roomArray);
        
        return json;
    }
    
    /**
     * 从JSON创建对象
     */
    public static DailySummaryData fromJson(JSONObject json) {
        LocalDate date = LocalDate.parse(json.getStr("date"), DateTimeFormatter.ISO_LOCAL_DATE);
        long totalMessages = json.getLong("totalMessages", 0L);
        
        // 解析活跃时段
        Map<Integer, Integer> activeHours = new HashMap<>();
        JSONObject hoursJson = json.getJSONObject("activeHours");
        if (hoursJson != null) {
            for (String key : hoursJson.keySet()) {
                activeHours.put(Integer.parseInt(key), hoursJson.getInt(key));
            }
        }
        
        // 解析消息类型
        Map<String, Integer> messageTypes = new HashMap<>();
        JSONObject typesJson = json.getJSONObject("messageTypes");
        if (typesJson != null) {
            for (String key : typesJson.keySet()) {
                messageTypes.put(key, typesJson.getInt(key));
            }
        }
        
        // 解析最活跃房间
        RoomDailyStats mostActiveRoom = null;
        JSONObject mostActiveJson = json.getJSONObject("mostActiveRoom");
        if (mostActiveJson != null) {
            mostActiveRoom = RoomDailyStats.fromJson(mostActiveJson);
        }
        
        // 解析房间统计
        List<RoomDailyStats> roomStats = new ArrayList<>();
        JSONArray roomArray = json.getJSONArray("roomStats");
        if (roomArray != null) {
            for (Object obj : roomArray) {
                if (obj instanceof JSONObject) {
                    roomStats.add(RoomDailyStats.fromJson((JSONObject) obj));
                }
            }
        }
        
        return new DailySummaryData(date, totalMessages, roomStats, activeHours, mostActiveRoom, messageTypes);
    }
    
    /**
     * 图表数据类
     */
    public static class ChartData {
        private final DailySummaryData summaryData;
        
        public ChartData(DailySummaryData summaryData) {
            this.summaryData = summaryData;
        }
        
        /**
         * 获取小时活跃度数据（用于折线图）
         */
        public Map<String, Integer> getHourlyActivityData() {
            Map<String, Integer> data = new LinkedHashMap<>();
            for (int i = 0; i < 24; i++) {
                String timeLabel = String.format("%02d:00", i);
                data.put(timeLabel, summaryData.activeHours.getOrDefault(i, 0));
            }
            return data;
        }
        
        /**
         * 获取消息类型分布数据（用于饼图）
         */
        public Map<String, Integer> getMessageTypeData() {
            return new HashMap<>(summaryData.messageTypes);
        }
        
        /**
         * 获取房间排行数据（用于柱状图）
         */
        public Map<String, Long> getRoomRankingData() {
            Map<String, Long> data = new LinkedHashMap<>();
            summaryData.roomStats.stream()
                    .limit(10) // 只显示前10个房间
                    .forEach(room -> {
                        String roomLabel = room.getRoomName().length() > 8 ? 
                                room.getRoomName().substring(0, 8) + "..." : room.getRoomName();
                        data.put(roomLabel, room.getTotalMessages());
                    });
            return data;
        }
        
        /**
         * 获取成员活跃度数据
         */
        public Map<String, Integer> getTopMembersData() {
            Map<String, Integer> data = new LinkedHashMap<>();
            
            // 收集所有房间的活跃成员
            Map<String, Integer> allMembers = new HashMap<>();
            for (RoomDailyStats room : summaryData.roomStats) {
                List<RoomDailyStats.MemberActivity> members = room.getTopActiveMembers(5);
                for (RoomDailyStats.MemberActivity member : members) {
                    allMembers.merge(member.getMemberName(), member.getMessageCount(), Integer::sum);
                }
            }
            
            // 排序并取前10
            allMembers.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(entry -> data.put(entry.getKey(), entry.getValue()));
            
            return data;
        }
    }
}