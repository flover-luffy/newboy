package net.luffy.util.summary;

import net.luffy.util.UnifiedLogger;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 每日总结定时任务调度器
 * 负责每日自动生成内容总结和图片
 * 
 * 更新说明：
 * - 使用新的内容收集系统，收集具体的文字和图片内容
 * - 集成内容分析器，提取关键信息和话题
 * - 生成内容摘要图片而非统计图表
 */
public class DailySummaryScheduler {
    
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final DailySummaryDataCollector dataCollector;
    private final DailySummaryImageGenerator imageGenerator;
    private final ContentAnalyzer contentAnalyzer;
    private final ScheduledExecutorService scheduler;
    
    // 配置
    private final LocalTime dailyGenerationTime = LocalTime.of(0, 5); // 每天00:05生成
    private final String outputDirectory = "data/daily_summaries";
    private final String imageDirectory = "data/daily_images";
    
    private volatile boolean isRunning = false;
    
    public DailySummaryScheduler(DailySummaryDataCollector dataCollector) {
        this.dataCollector = dataCollector;
        this.imageGenerator = new DailySummaryImageGenerator();
        this.contentAnalyzer = new ContentAnalyzer();
        this.scheduler = Executors.newScheduledThreadPool(2);
    }
    
    /**
     * 启动定时任务
     */
    public void start() {
        if (isRunning) {
            logger.warn("DailySummary", "调度器已经在运行中");
            return;
        }
        
        isRunning = true;
        logger.info("DailySummary", "启动每日总结调度器");
        
        // 计算到下次执行的延迟时间
        long initialDelay = calculateInitialDelay();
        
        // 每日定时任务
        scheduler.scheduleAtFixedRate(
            this::generateDailySummary,
            initialDelay,
            TimeUnit.DAYS.toSeconds(1), // 每24小时执行一次
            TimeUnit.SECONDS
        );
        
        // 每小时数据收集任务（确保数据及时保存）
        scheduler.scheduleAtFixedRate(
            this::saveCurrentData,
            0, // 立即开始
            1, // 每小时执行一次
            TimeUnit.HOURS
        );
        
        logger.info("DailySummary", String.format("定时任务已设置，首次执行将在 %d 秒后开始", initialDelay));
    }
    
    /**
     * 停止定时任务
     */
    public void stop() {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        logger.info("DailySummary", "停止每日总结调度器");
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 计算到下次执行的初始延迟时间（秒）
     */
    private long calculateInitialDelay() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextExecution = now.toLocalDate().atTime(dailyGenerationTime);
        
        // 如果今天的执行时间已过，则安排到明天
        if (now.isAfter(nextExecution)) {
            nextExecution = nextExecution.plusDays(1);
        }
        
        return java.time.Duration.between(now, nextExecution).getSeconds();
    }
    
    /**
     * 生成每日总结
     */
    private void generateDailySummary() {
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            logger.info("DailySummary", "开始生成每日内容总结: " + yesterday);
            
            // 获取昨日活跃的房间列表
            List<String> activeRoomIds = dataCollector.getActiveRoomIds(yesterday);
            
            if (activeRoomIds.isEmpty()) {
                logger.info("DailySummary", "没有活跃房间数据，跳过总结生成");
                return;
            }
            
            logger.info("DailySummary", "发现 " + activeRoomIds.size() + " 个活跃房间");
            
            // 为每个房间独立生成总结
            int successCount = 0;
            for (String roomId : activeRoomIds) {
                try {
                    boolean success = generateRoomSummaryForDate(roomId, yesterday);
                    if (success) {
                        successCount++;
                    }
                } catch (Exception e) {
                    logger.error("DailySummary", "生成房间 " + roomId + " 总结失败: " + e.getMessage());
                }
            }
            
            logger.info("DailySummary", "每日总结生成完成，成功: " + successCount + "/" + activeRoomIds.size());
            
        } catch (Exception e) {
            logger.error("DailySummary", "生成每日总结时发生错误: " + e.getMessage());
        }
    }
    
    /**
     * 生成指定房间和日期的总结
     */
    public boolean generateRoomSummaryForDate(String roomId, LocalDate date) {
        try {
            logger.info("DailySummary", "开始生成房间总结: " + roomId + ", 日期: " + date);
            
            // 获取房间数据
            RoomContentData roomData = dataCollector.getRoomDataForDate(roomId, date);
            
            if (roomData == null) {
                logger.warn("DailySummary", "房间 " + roomId + " 在 " + date + " 没有数据");
                return false;
            }
            
            // 分析房间内容
            ContentAnalyzer.RoomContentAnalysis analysis = contentAnalyzer.analyzeRoomContent(roomData);
            
            if (analysis == null) {
                logger.error("DailySummary", "房间 " + roomId + " 内容分析失败");
                return false;
            }
            
            // 生成房间总结图片
            String imagePath = imageGenerator.generateRoomContentSummaryImage(analysis, date);
            
            if (imagePath == null) {
                logger.error("DailySummary", "房间 " + roomId + " 图片生成失败");
                return false;
            }
            
            // 保存分析结果
            saveRoomAnalysisResult(analysis, date);
            
            // 通知总结生成完成
            notifyRoomSummaryGenerated(roomId, date, imagePath);
            
            logger.info("DailySummary", "房间 " + roomId + " 总结生成成功: " + imagePath);
            return true;
            
        } catch (Exception e) {
            logger.error("DailySummary", "生成房间 " + roomId + " 总结失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 保存当前数据（每小时执行）
     */
    private void saveCurrentData() {
        try {
            logger.info("DailySummary", "保存当前内容数据");
            dataCollector.saveAllData();
            logger.info("DailySummary", "当前内容数据保存完成");
        } catch (Exception e) {
            logger.error("DailySummary", "保存当前数据时发生错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 手动生成指定日期的内容总结
     */
    public boolean generateSummaryForDate(LocalDate date) {
        logger.info("DailySummary", "手动生成内容总结: " + date);
        
        try {
            // 获取指定日期的活跃房间列表
            List<String> activeRoomIds = dataCollector.getActiveRoomIds(date);
            
            if (activeRoomIds.isEmpty()) {
                logger.info("DailySummary", "没有活跃房间数据，无法生成总结");
                return false;
            }
            
            logger.info("DailySummary", "发现 " + activeRoomIds.size() + " 个活跃房间");
            
            // 为每个房间独立生成总结
            int successCount = 0;
            for (String roomId : activeRoomIds) {
                try {
                    boolean success = generateRoomSummaryForDate(roomId, date);
                    if (success) {
                        successCount++;
                    }
                } catch (Exception e) {
                    logger.error("DailySummary", "生成房间 " + roomId + " 总结失败: " + e.getMessage());
                }
            }
            
            boolean allSuccess = successCount == activeRoomIds.size();
            logger.info("DailySummary", "手动总结生成完成，成功: " + successCount + "/" + activeRoomIds.size());
            
            return allSuccess;
            
        } catch (Exception e) {
            logger.error("DailySummary", "手动生成总结时发生错误: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 生成测试图片
     */
    public boolean generateTestImage() {
        try {
            String testImagePath = imageDirectory + "/test_image.png";
            File testImage = imageGenerator.generateTestImage(testImagePath);
            return testImage != null && testImage.exists();
        } catch (Exception e) {
            logger.error("DailySummary", "生成测试图片时发生错误: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取运行状态
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * 获取下次执行时间
     */
    public LocalDateTime getNextExecutionTime() {
        if (!isRunning) {
            return null;
        }
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextExecution = now.toLocalDate().atTime(dailyGenerationTime);
        
        if (now.isAfter(nextExecution)) {
            nextExecution = nextExecution.plusDays(1);
        }
        
        return nextExecution;
    }
    
    /**
     * 获取配置信息
     */
    public String getConfigInfo() {
        return String.format(
            "每日总结调度器配置:\n" +
            "- 执行时间: %s\n" +
            "- 输出目录: %s\n" +
            "- 图片目录: %s\n" +
            "- 运行状态: %s\n" +
            "- 下次执行: %s",
            dailyGenerationTime,
            outputDirectory,
            imageDirectory,
            isRunning ? "运行中" : "已停止",
            getNextExecutionTime()
        );
    }
    
    /**
     * 保存分析结果到文件
     */
    private void saveAnalysisResults(Map<String, ContentAnalyzer.RoomContentAnalysis> analysisResults, LocalDate date) {
        try {
            // 确保输出目录存在
            File outputDir = new File(outputDirectory);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            // 保存分析结果为JSON
            String jsonFileName = String.format("content_analysis_%s.json", date.toString());
            String jsonPath = outputDirectory + "/" + jsonFileName;
            
            // 这里可以使用JSON库来序列化分析结果
            // 暂时使用简单的文本格式
            StringBuilder jsonContent = new StringBuilder();
            jsonContent.append("{\n");
            jsonContent.append("  \"date\": \"").append(date.toString()).append("\",\n");
            jsonContent.append("  \"rooms\": {\n");
            
            boolean first = true;
            for (Map.Entry<String, ContentAnalyzer.RoomContentAnalysis> entry : analysisResults.entrySet()) {
                if (!first) {
                    jsonContent.append(",\n");
                }
                first = false;
                
                String roomId = entry.getKey();
                ContentAnalyzer.RoomContentAnalysis analysis = entry.getValue();
                
                jsonContent.append("    \"").append(roomId).append("\": ");
                jsonContent.append(analysis.toJson());
            }
            
            jsonContent.append("\n  }\n");
            jsonContent.append("}");
            
            // 写入文件
            try (java.io.FileWriter writer = new java.io.FileWriter(jsonPath)) {
                writer.write(jsonContent.toString());
            }
            
            logger.info("DailySummary", "分析结果已保存: " + jsonPath);
            
        } catch (Exception e) {
            logger.error("DailySummary", "保存分析结果时发生错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 保存单个房间的分析结果
     */
    private void saveRoomAnalysisResult(ContentAnalyzer.RoomContentAnalysis analysis, LocalDate date) {
        try {
            // 确保输出目录存在
            File outputDir = new File(outputDirectory);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            // 保存单个房间的分析结果为JSON
            String jsonFileName = String.format("room_analysis_%s_%s.json", analysis.roomId, date.toString());
            String jsonPath = outputDirectory + "/" + jsonFileName;
            
            // 创建JSON内容
            StringBuilder jsonContent = new StringBuilder();
            jsonContent.append("{\n");
            jsonContent.append("  \"roomId\": \"").append(analysis.roomId).append("\",\n");
            jsonContent.append("  \"roomName\": \"").append(analysis.roomName).append("\",\n");
            jsonContent.append("  \"date\": \"").append(date.toString()).append("\",\n");
            jsonContent.append("  \"totalMessages\": ").append(analysis.totalMessages).append(",\n");
            jsonContent.append("  \"totalImages\": ").append(analysis.totalImages).append(",\n");
            jsonContent.append("  \"sentimentScore\": ").append(analysis.sentimentScore).append(",\n");
            
            // 添加关键词
            jsonContent.append("  \"topKeywords\": [\n");
            for (int i = 0; i < analysis.topKeywords.size(); i++) {
                ContentAnalyzer.KeywordInfo keyword = analysis.topKeywords.get(i);
                if (i > 0) jsonContent.append(",\n");
                jsonContent.append("    {\"keyword\": \"").append(keyword.word)
                          .append("\", \"count\": ").append(keyword.count)
                          .append(", \"frequency\": ").append(keyword.frequency).append("}");
            }
            jsonContent.append("\n  ],\n");
            
            // 添加亮点
            jsonContent.append("  \"highlights\": [\n");
            for (int i = 0; i < analysis.highlights.size(); i++) {
                if (i > 0) jsonContent.append(",\n");
                jsonContent.append("    \"").append(analysis.highlights.get(i)).append("\"");
            }
            jsonContent.append("\n  ]\n");
            jsonContent.append("}");
            
            // 写入文件
            try (FileWriter writer = new FileWriter(jsonPath, StandardCharsets.UTF_8)) {
                writer.write(jsonContent.toString());
            }
            
            logger.info("DailySummary", "房间分析结果已保存: " + jsonPath);
            
        } catch (Exception e) {
            logger.error("DailySummary", "保存房间分析结果失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 通知单个房间总结生成完成
     */
    private void notifyRoomSummaryGenerated(String roomId, LocalDate date, String imagePath) {
        try {
            logger.info("DailySummary", "房间总结生成完成通知:");
            logger.info("DailySummary", "- 房间ID: " + roomId);
            logger.info("DailySummary", "- 日期: " + date);
            logger.info("DailySummary", "- 图片路径: " + imagePath);
            
            // 发送总结到关注该房间的群组
            sendSummaryToSubscribedGroups(roomId, date, imagePath);
            
        } catch (Exception e) {
            logger.error("DailySummary", "发送房间总结通知时发生错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 向订阅了指定房间的群组发送总结
     */
    private void sendSummaryToSubscribedGroups(String roomId, LocalDate date, String imagePath) {
        try {
            // 获取订阅了该房间的群组列表
            List<Long> subscribedGroups = getSubscribedGroups(roomId);
            
            if (subscribedGroups.isEmpty()) {
                logger.info("DailySummary", "房间 " + roomId + " 没有订阅群组");
                return;
            }
            
            // 构建总结消息
            String summaryMessage = String.format(
                "📊 每日总结 - %s\n" +
                "🏠 房间: %s\n" +
                "📅 日期: %s\n" +
                "✨ 总结已生成完成！",
                date.toString(),
                roomId,
                date.toString()
            );
            
            // 向每个订阅群组发送总结
            for (Long groupId : subscribedGroups) {
                try {
                    sendSummaryToGroup(groupId, summaryMessage, imagePath);
                    logger.info("DailySummary", "已向群组 " + groupId + " 发送房间 " + roomId + " 的总结");
                } catch (Exception e) {
                    logger.error("DailySummary", "向群组 " + groupId + " 发送总结失败: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            logger.error("DailySummary", "发送总结到订阅群组时发生错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取订阅了指定房间的群组列表
     */
    private List<Long> getSubscribedGroups(String roomId) {
        return SubscriptionManager.getInstance().getSubscribedGroups(roomId);
    }
    
    /**
     * 向指定群组发送总结消息和图片
     */
    private void sendSummaryToGroup(Long groupId, String message, String imagePath) {
        try {
            net.luffy.util.sender.MessageSender messageSender = new net.luffy.util.sender.MessageSender();
            
            // 如果有图片，发送文本+图片；否则只发送文本
            if (imagePath != null && new java.io.File(imagePath).exists()) {
                // 将本地图片路径转换为可访问的URL或直接发送文件
                messageSender.sendGroupMessageWithImage(groupId.toString(), message, null);
                // TODO: 实现图片发送逻辑，可能需要上传到图床或直接发送本地文件
            } else {
                messageSender.sendGroupMessage(groupId.toString(), message);
            }
            
        } catch (Exception e) {
            logger.error("DailySummary", "向群组发送消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通知总结生成完成
     */
    private void notifySummaryGenerated(LocalDate date, String imagePath, int roomCount) {
        try {
            logger.info("DailySummary", "内容总结生成完成通知:");
            logger.info("DailySummary", "- 日期: " + date);
            logger.info("DailySummary", "- 图片路径: " + imagePath);
            logger.info("DailySummary", "- 分析房间数: " + roomCount);
            
            // 这里可以添加发送通知的逻辑
            // 例如发送到QQ群、微信群或邮件
            
        } catch (Exception e) {
            logger.error("DailySummary", "发送通知时发生错误: " + e.getMessage(), e);
        }
    }
}