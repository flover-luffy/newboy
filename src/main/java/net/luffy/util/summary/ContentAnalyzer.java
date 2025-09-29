package net.luffy.util.summary;

import net.luffy.util.UnifiedLogger;
import net.luffy.util.summary.nlp.TfIdfAnalyzer;
import net.luffy.util.summary.nlp.EnhancedSentimentAnalyzer;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 内容分析器 - 用于分析房间消息内容，提取关键信息和话题
 * 
 * 主要功能：
 * 1. 文本内容分析和关键词提取
 * 2. 话题识别和热点分析
 * 3. 情感倾向分析
 * 4. 内容摘要生成
 * 5. 图片内容分析（基于URL和元数据）
 * 
 * @author Luffy
 * @since 2024-01-20
 */
public class ContentAnalyzer {
    
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    
    // NLP组件
    private final TfIdfAnalyzer tfIdfAnalyzer;
    private final EnhancedSentimentAnalyzer sentimentAnalyzer;
    
    // 中文停用词列表
    private static final Set<String> STOP_WORDS = Set.of(
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "那", "什么", "可以", "这个", "还", "比", "啊", "哈", "呢", "吧", "哦", "嗯", "额", "呃", "诶", "咦", "哇", "哎", "唉", "嘿", "喂", "嗨", "哟", "咯", "呀", "嘛", "呗", "喔", "哼", "嘻", "咳", "嗯嗯", "哈哈", "呵呵", "嘿嘿", "嘻嘻", "哎呀", "哎哟", "哇塞", "哇哦", "哇咔", "哇靠", "哇噻", "哇哈", "哇啦", "哇呀", "哇嘎", "哇哇", "哇喔", "哇哇哇"
    );
    
    // 表情符号模式 - 修复Unicode转义序列格式
    private static final Pattern EMOJI_PATTERN = Pattern.compile("[\\uD83D\\uDE00-\\uD83D\\uDE4F]|[\\uD83C\\uDF00-\\uD83D\\uDDFF]|[\\uD83D\\uDE80-\\uD83D\\uDEFF]|[\\uD83C\\uDDE0-\\uD83C\\uDDFF]|[\\u2600-\\u26FF]|[\\u2700-\\u27BF]");
    
    // URL模式
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    
    // @提及模式
    private static final Pattern MENTION_PATTERN = Pattern.compile("@[\\w\\u4e00-\\u9fa5]+");
    
    // 话题标签模式
    private static final Pattern TOPIC_PATTERN = Pattern.compile("#[\\w\\u4e00-\\u9fa5]+#?");
    
    // 时间相关词汇
    private static final Set<String> TIME_WORDS = Set.of(
        "今天", "昨天", "明天", "现在", "刚才", "一会", "等会", "晚上", "早上", "中午", "下午", "傍晚", "深夜", "凌晨"
    );
    
    public ContentAnalyzer() {
        this.tfIdfAnalyzer = new TfIdfAnalyzer(STOP_WORDS);
        this.sentimentAnalyzer = new EnhancedSentimentAnalyzer();
    }
    
    /**
     * 分析房间内容数据，包括文字和图片
     */
    public RoomContentAnalysis analyzeRoomContent(RoomContentData roomData) {
        if (roomData == null) {
            return null;
        }
        
        try {
            RoomContentAnalysis analysis = new RoomContentAnalysis();
            analysis.roomId = roomData.getRoomId();
            analysis.roomName = roomData.getRoomName();
            analysis.date = roomData.getDate();
            
            // 分析文字内容
            analyzeTextContent(roomData, analysis);
            
            // 分析成员活跃度
            analyzeMemberActivity(roomData, analysis);
            
            // 分析时间分布
            analyzeHourlyActivity(roomData, analysis);
            
            // 分析图片内容
            analyzeImageContent(roomData, analysis);
            
            // 生成亮点内容
            generateHighlights(roomData, analysis);
            
            logger.debug("ContentAnalyzer", 
                "分析完成: 房间=" + roomData.getRoomName() + 
                ", 消息数=" + analysis.totalMessages + 
                ", 关键词数=" + (analysis.topKeywords != null ? analysis.topKeywords.size() : 0));
            
            return analysis;
            
        } catch (Exception e) {
            logger.error("ContentAnalyzer", "内容分析失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 提取关键词
     */
    /**
     * 使用TF-IDF算法提取关键词
     */
    private List<KeywordInfo> extractKeywords(List<String> texts) {
        // 清空之前的文档
        tfIdfAnalyzer.clear();
        
        // 添加文档到TF-IDF分析器
        Map<String, String> documents = new HashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            documents.put("doc_" + i, texts.get(i));
        }
        tfIdfAnalyzer.addDocuments(documents);
        
        // 获取关键词
        List<TfIdfAnalyzer.KeywordScore> keywordScores = tfIdfAnalyzer.getTopKeywords(20);
        
        // 转换为KeywordInfo格式
        return keywordScores.stream()
                .map(score -> new KeywordInfo(score.keyword, (int)(score.score * 100))) // 将得分转换为整数
                .collect(Collectors.toList());
    }
    
    /**
     * 提取话题
     */
    private List<String> extractTopics(List<String> texts) {
        Set<String> topics = new HashSet<>();
        
        for (String text : texts) {
            // 提取#话题#格式
            java.util.regex.Matcher matcher = TOPIC_PATTERN.matcher(text);
            while (matcher.find()) {
                String topic = matcher.group().replaceAll("#", "");
                if (topic.length() > 1) {
                    topics.add(topic);
                }
            }
        }
        
        return new ArrayList<>(topics);
    }
    
    /**
     * 提取@提及
     */
    private List<String> extractMentions(List<String> texts) {
        Set<String> mentions = new HashSet<>();
        
        for (String text : texts) {
            java.util.regex.Matcher matcher = MENTION_PATTERN.matcher(text);
            while (matcher.find()) {
                String mention = matcher.group().substring(1); // 去掉@符号
                mentions.add(mention);
            }
        }
        
        return new ArrayList<>(mentions);
    }
    
    /**
     * 使用增强情感分析器计算情感得分
     */
    private double calculateEmotionScore(List<String> texts) {
        if (texts.isEmpty()) {
            return 0.0;
        }
        
        // 合并所有文本进行整体分析
        String combinedText = String.join(" ", texts);
        
        // 使用增强情感分析器
        EnhancedSentimentAnalyzer.SentimentResult result = sentimentAnalyzer.analyzeSentiment(combinedText);
        
        return result.sentimentScore;
    }
    
    /**
     * 生成内容摘要
     */
    private String generateContentSummary(List<String> texts, List<RoomContentData.MessageContent> messages) {
        if (texts.isEmpty()) {
            return "暂无内容";
        }
        
        StringBuilder summary = new StringBuilder();
        
        // 统计信息
        summary.append("共收集到 ").append(texts.size()).append(" 条文本消息。");
        
        // 活跃时段
        Map<Integer, Integer> hourlyCount = new HashMap<>();
        for (RoomContentData.MessageContent msg : messages) {
            hourlyCount.merge(msg.hour, 1, Integer::sum);
        }
        
        if (!hourlyCount.isEmpty()) {
            int peakHour = hourlyCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(0);
            summary.append(" 最活跃时段为 ").append(peakHour).append(" 点。");
        }
        
        // 内容特点
        long longMessages = texts.stream().filter(text -> text.length() > 50).count();
        if (longMessages > 0) {
            summary.append(" 包含 ").append(longMessages).append(" 条长消息。");
        }
        
        return summary.toString();
    }
    
    /**
     * 分析文字内容
     */
    private void analyzeTextContent(RoomContentData roomData, RoomContentAnalysis analysis) {
        // 收集所有文本消息
        List<String> allTexts = new ArrayList<>();
        List<RoomContentData.MessageContent> allMessages = new ArrayList<>();
        
        allMessages.addAll(roomData.getTextMessages());
        allMessages.addAll(roomData.getOtherMessages()); // 其他类型消息可能也包含文本
        
        for (RoomContentData.MessageContent msg : allMessages) {
            if (msg.content != null && !msg.content.trim().isEmpty()) {
                allTexts.add(msg.content);
            }
        }
        
        // 基础统计
        analysis.totalMessages = getTotalMessageCount(roomData);
        analysis.totalTextLength = allTexts.stream().mapToInt(String::length).sum();
        analysis.averageMessageLength = allTexts.isEmpty() ? 0 : (double) analysis.totalTextLength / allTexts.size();
        
        // 文本分析
        if (!allTexts.isEmpty()) {
            analysis.topKeywords = extractKeywords(allTexts);
            List<String> topics = extractTopics(allTexts);
            analysis.mentionedUsers = extractMentions(allTexts);
            
            // 使用增强情感分析器
            String combinedText = String.join(" ", allTexts);
            EnhancedSentimentAnalyzer.SentimentResult sentimentResult = sentimentAnalyzer.analyzeSentiment(combinedText);
            
            analysis.sentimentScore = sentimentResult.sentimentScore;
            analysis.dominantSentiment = sentimentResult.sentimentType.getDescription();
            
            // 记录详细的情感分析信息
            logger.debug("ContentAnalyzer", String.format(
                "房间 %s 情感分析结果: %s, 得分: %.2f, 强度: %.2f", 
                roomData.getRoomId(), 
                sentimentResult.sentimentType.getDescription(),
                sentimentResult.sentimentScore,
                sentimentResult.intensity
            ));
        }
    }
    
    /**
     * 分析成员活跃度
     */
    private void analyzeMemberActivity(RoomContentData roomData, RoomContentAnalysis analysis) {
        List<RoomContentData.MessageContent> allMessages = new ArrayList<>();
        allMessages.addAll(roomData.getTextMessages());
        allMessages.addAll(roomData.getImageMessages());
        allMessages.addAll(roomData.getAudioMessages());
        allMessages.addAll(roomData.getVideoMessages());
        allMessages.addAll(roomData.getLiveMessages());
        allMessages.addAll(roomData.getOtherMessages());
        
        Map<String, MemberActivityInfo> activityMap = new HashMap<>();
        
        for (RoomContentData.MessageContent msg : allMessages) {
            String key = msg.nickName + "|" + (msg.starName != null ? msg.starName : "");
            MemberActivityInfo info = activityMap.computeIfAbsent(key, k -> {
                MemberActivityInfo activity = new MemberActivityInfo();
                activity.nickName = msg.nickName;
                activity.starName = msg.starName;
                activity.displayName = msg.starName != null ? msg.starName : msg.nickName;
                activity.messageCount = 0;
                activity.totalLength = 0;
                activity.hours = new HashSet<>();
                return activity;
            });
            
            info.messageCount++;
            info.totalLength += msg.content != null ? msg.content.length() : 0;
            info.hours.add(msg.hour);
        }
        
        analysis.topActiveMembers = activityMap.values().stream()
                .sorted(Comparator.comparingInt((MemberActivityInfo a) -> a.messageCount).reversed())
                .limit(10)
                .collect(Collectors.toList());
        
        analysis.totalActiveMembers = activityMap.size();
    }
    
    /**
     * 分析时间分布
     */
    private void analyzeHourlyActivity(RoomContentData roomData, RoomContentAnalysis analysis) {
        List<RoomContentData.MessageContent> allMessages = new ArrayList<>();
        allMessages.addAll(roomData.getTextMessages());
        allMessages.addAll(roomData.getImageMessages());
        allMessages.addAll(roomData.getAudioMessages());
        allMessages.addAll(roomData.getVideoMessages());
        allMessages.addAll(roomData.getLiveMessages());
        allMessages.addAll(roomData.getOtherMessages());
        
        Map<Integer, Integer> hourlyCount = new HashMap<>();
        
        for (RoomContentData.MessageContent msg : allMessages) {
            hourlyCount.merge(msg.hour, 1, Integer::sum);
        }
        
        analysis.hourlyMessageDistribution = hourlyCount;
        
        if (!hourlyCount.isEmpty()) {
            analysis.peakHour = hourlyCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(0);
            
            analysis.quietHour = hourlyCount.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(0);
        }
    }
    
    /**
     * 生成亮点内容
     */
    private void generateHighlights(RoomContentData roomData, RoomContentAnalysis analysis) {
        List<String> highlights = new ArrayList<>();
        
        // 消息量亮点
        if (analysis.totalMessages > 100) {
            highlights.add("💬 今日消息活跃，共 " + analysis.totalMessages + " 条消息");
        }
        
        // 图片分享亮点
        if (analysis.totalImages > 10) {
            highlights.add("📸 图片分享丰富，共 " + analysis.totalImages + " 张图片");
        }
        
        // 活跃成员亮点
        if (analysis.topActiveMembers != null && !analysis.topActiveMembers.isEmpty()) {
            MemberActivityInfo topMember = analysis.topActiveMembers.get(0);
            highlights.add("👑 最活跃成员：" + topMember.displayName + " (" + topMember.messageCount + " 条消息)");
        }
        
        // 关键词亮点
        if (analysis.topKeywords != null && !analysis.topKeywords.isEmpty()) {
            KeywordInfo topKeyword = analysis.topKeywords.get(0);
            highlights.add("🔥 热门关键词：" + topKeyword.word + " (出现 " + topKeyword.count + " 次)");
        }
        
        // 情感亮点
        if (analysis.sentimentScore > 0.5) {
            highlights.add("😊 房间氛围积极正面");
        } else if (analysis.sentimentScore < -0.5) {
            highlights.add("😔 房间氛围略显低沉");
        }
        
        // 活跃时段亮点
        if (analysis.peakHour > 0) {
            highlights.add("⏰ 最活跃时段：" + analysis.peakHour + "点");
        }
        
        // 图片分析亮点
        if (analysis.totalImages > 0 && analysis.topImagePublishers != null && !analysis.topImagePublishers.isEmpty()) {
            String topPublisher = analysis.topImagePublishers.entrySet().iterator().next().getKey();
            highlights.add("📷 图片分享达人：" + topPublisher);
        }
        
        analysis.highlights = highlights;
    }
    
    /**
     * 分析图片内容
     */
    private void analyzeImageContent(RoomContentData roomData, RoomContentAnalysis analysis) {
        List<ImageContentProcessor.ImageInfo> imageInfos = roomData.getImageInfos();
        
        if (imageInfos.isEmpty()) {
            return;
        }
        
        // 统计图片基本信息
        analysis.totalImages = imageInfos.size();
        analysis.totalImageSize = imageInfos.stream().mapToLong(img -> img.fileSize).sum();
        analysis.duplicateImages = (int) imageInfos.stream().filter(img -> img.isDuplicate).count();
        
        // 按格式分类
        Map<String, Long> formatCount = imageInfos.stream()
                .collect(Collectors.groupingBy(img -> img.format, Collectors.counting()));
        analysis.imageFormatDistribution = formatCount;
        
        // 按类型分类
        Map<String, Long> typeCount = imageInfos.stream()
                .collect(Collectors.groupingBy(img -> img.imageType, Collectors.counting()));
        analysis.imageTypeDistribution = typeCount;
        
        // 分析图片尺寸分布
        Map<String, Integer> sizeDistribution = new HashMap<>();
        for (ImageContentProcessor.ImageInfo img : imageInfos) {
            String sizeCategory = categorizeImageSize(img.width, img.height);
            sizeDistribution.merge(sizeCategory, 1, Integer::sum);
        }
        analysis.imageSizeDistribution = sizeDistribution;
        
        // 找出最大的图片
        Optional<ImageContentProcessor.ImageInfo> largestImage = imageInfos.stream()
                .max(Comparator.comparingLong(img -> img.fileSize));
        if (largestImage.isPresent()) {
            analysis.largestImageInfo = createImageAnalysisInfo(largestImage.get());
        }
        
        // 找出最高分辨率的图片
        Optional<ImageContentProcessor.ImageInfo> highestResImage = imageInfos.stream()
                .max(Comparator.comparingInt(img -> img.width * img.height));
        if (highestResImage.isPresent()) {
            analysis.highestResolutionImageInfo = createImageAnalysisInfo(highestResImage.get());
        }
        
        // 分析图片发布时间分布
        Map<Integer, Integer> imageHourlyDistribution = new HashMap<>();
        for (ImageContentProcessor.ImageInfo img : imageInfos) {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(img.timestamp), 
                    java.time.ZoneId.systemDefault());
            int hour = dateTime.getHour();
            imageHourlyDistribution.merge(hour, 1, Integer::sum);
        }
        analysis.imageHourlyDistribution = imageHourlyDistribution;
        
        // 分析图片发布者
        Map<String, Integer> imagePublisherCount = new HashMap<>();
        for (ImageContentProcessor.ImageInfo img : imageInfos) {
            String publisher = img.starName != null ? img.starName : img.nickName;
            imagePublisherCount.merge(publisher, 1, Integer::sum);
        }
        analysis.topImagePublishers = imagePublisherCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new));
    }
    
    /**
     * 图片尺寸分类
     */
    private String categorizeImageSize(int width, int height) {
        int pixels = width * height;
        if (pixels < 100000) { // < 0.1MP
            return "小图";
        } else if (pixels < 500000) { // < 0.5MP
            return "中图";
        } else if (pixels < 2000000) { // < 2MP
            return "大图";
        } else {
            return "超大图";
        }
    }
    
    /**
     * 创建图片分析信息
     */
    private ImageAnalysisInfo createImageAnalysisInfo(ImageContentProcessor.ImageInfo imageInfo) {
        ImageAnalysisInfo info = new ImageAnalysisInfo();
        info.fileName = imageInfo.fileName;
        info.format = imageInfo.format;
        info.width = imageInfo.width;
        info.height = imageInfo.height;
        info.fileSize = imageInfo.fileSize;
        info.imageType = imageInfo.imageType;
        info.publisher = imageInfo.starName != null ? imageInfo.starName : imageInfo.nickName;
        info.timestamp = imageInfo.timestamp;
        info.isDuplicate = imageInfo.isDuplicate;
        return info;
    }

    /**
     * 生成亮点内容
     */
    private List<String> generateHighlights(List<RoomContentData.MessageContent> messages, RoomContentAnalysis analysis) {
        List<String> highlights = new ArrayList<>();
        
        // 消息量亮点
        if (analysis.totalMessages > 100) {
            highlights.add("💬 今日消息活跃，共 " + analysis.totalMessages + " 条消息");
        }
        
        // 图片分享亮点
        if (analysis.totalImages > 10) {
            highlights.add("📸 图片分享丰富，共 " + analysis.totalImages + " 张图片");
        }
        
        // 活跃成员亮点
        if (analysis.topActiveMembers != null && !analysis.topActiveMembers.isEmpty()) {
            MemberActivityInfo topMember = analysis.topActiveMembers.get(0);
            highlights.add("👑 最活跃成员：" + topMember.displayName + " (" + topMember.messageCount + " 条消息)");
        }
        
        // 关键词亮点
        if (analysis.topKeywords != null && !analysis.topKeywords.isEmpty()) {
            KeywordInfo topKeyword = analysis.topKeywords.get(0);
            highlights.add("🔥 热门关键词：" + topKeyword.word + " (出现 " + topKeyword.count + " 次)");
        }
        
        // 情感亮点
        if (analysis.sentimentScore > 0.5) {
            highlights.add("😊 房间氛围积极正面");
        } else if (analysis.sentimentScore < -0.5) {
            highlights.add("😔 房间氛围略显低沉");
        }
        
        // 活跃时段亮点
        if (analysis.peakHour > 0) {
            highlights.add("⏰ 最活跃时段：" + analysis.peakHour + "点");
        }
        
        return highlights;
    }
    
    /**
     * 清理文本
     */
    private String cleanText(String text) {
        if (text == null) return "";
        
        // 移除URL
        text = URL_PATTERN.matcher(text).replaceAll("");
        
        // 移除表情符号
        text = EMOJI_PATTERN.matcher(text).replaceAll("");
        
        // 移除多余空白
        text = text.replaceAll("\\s+", " ").trim();
        
        return text;
    }
    
    /**
     * 判断是否为有效关键词
     */
    private boolean isValidKeyword(String word) {
        // 过滤纯数字、单字符、特殊符号等
        return word.matches("[\\u4e00-\\u9fa5a-zA-Z]{2,}");
    }
    
    /**
     * 从URL获取图片类型
     */
    private String getImageTypeFromUrl(String url) {
        if (url == null) return "unknown";
        
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg")) return "jpg";
        if (lowerUrl.contains(".png")) return "png";
        if (lowerUrl.contains(".gif")) return "gif";
        if (lowerUrl.contains(".webp")) return "webp";
        
        return "other";
    }
    
    /**
     * 从URL获取图片来源
     */
    private String getImageSourceFromUrl(String url) {
        if (url == null) return "unknown";
        
        if (url.contains("pocket48.cn")) return "pocket48";
        if (url.contains("qpic.cn")) return "qq";
        if (url.contains("sinaimg.cn")) return "weibo";
        
        return "other";
    }
    
    /**
     * 获取总消息数
     */
    private int getTotalMessageCount(RoomContentData roomData) {
        return roomData.getTextMessages().size() + 
               roomData.getImageMessages().size() + 
               roomData.getAudioMessages().size() + 
               roomData.getVideoMessages().size() + 
               roomData.getLiveMessages().size() + 
               roomData.getOtherMessages().size();
    }
    
    // 内部数据类
    
    /**
     * 房间内容分析结果
     */
    public static class RoomContentAnalysis {
        public String roomId;
        public String roomName;
        public LocalDateTime date;
        
        // 文字内容分析
        public int totalMessages;
        public int totalTextLength;
        public double averageMessageLength;
        public List<KeywordInfo> topKeywords;
        public List<String> mentionedUsers;
        public double sentimentScore;
        public String dominantSentiment;
        
        // 成员活跃度分析
        public List<MemberActivityInfo> topActiveMembers;
        public int totalActiveMembers;
        
        // 时间分布分析
        public Map<Integer, Integer> hourlyMessageDistribution;
        public int peakHour;
        public int quietHour;
        
        // 图片内容分析
        public int totalImages;
        public long totalImageSize;
        public int duplicateImages;
        public Map<String, Long> imageFormatDistribution;
        public Map<String, Long> imageTypeDistribution;
        public Map<String, Integer> imageSizeDistribution;
        public Map<Integer, Integer> imageHourlyDistribution;
        public Map<String, Integer> topImagePublishers;
        public ImageAnalysisInfo largestImageInfo;
        public ImageAnalysisInfo highestResolutionImageInfo;
        
        // 亮点内容
        public List<String> highlights;
        
        /**
         * 生成完整的分析报告
         */
        public String generateAnalysisReport() {
            StringBuilder report = new StringBuilder();
            
            report.append("=== ").append(roomName).append(" 内容分析报告 ===\n");
            report.append("日期: ").append(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).append("\n\n");
            
            // 基础统计
            report.append("📊 基础统计:\n");
            report.append("总消息数: ").append(totalMessages).append("\n");
            report.append("总文字长度: ").append(totalTextLength).append(" 字符\n");
            report.append("平均消息长度: ").append(String.format("%.1f", averageMessageLength)).append(" 字符\n");
            report.append("活跃成员数: ").append(totalActiveMembers).append("\n");
            report.append("情感倾向: ").append(dominantSentiment).append(" (").append(String.format("%.2f", sentimentScore)).append(")\n\n");
            
            // 图片统计
            if (totalImages > 0) {
                report.append("🖼️ 图片统计:\n");
                report.append("图片总数: ").append(totalImages).append("\n");
                report.append("图片总大小: ").append(formatFileSize(totalImageSize)).append("\n");
                report.append("重复图片: ").append(duplicateImages).append("\n");
                
                if (largestImageInfo != null) {
                    report.append("最大图片: ").append(largestImageInfo.fileName)
                          .append(" (").append(formatFileSize(largestImageInfo.fileSize)).append(")\n");
                }
                
                if (highestResolutionImageInfo != null) {
                    report.append("最高分辨率: ").append(highestResolutionImageInfo.width)
                          .append("x").append(highestResolutionImageInfo.height).append("\n");
                }
                report.append("\n");
            }
            
            // 活跃时段
            report.append("⏰ 活跃时段:\n");
            report.append("最活跃时段: ").append(peakHour).append(":00\n");
            report.append("最安静时段: ").append(quietHour).append(":00\n\n");
            
            // 热门关键词
            if (topKeywords != null && !topKeywords.isEmpty()) {
                report.append("🔥 热门关键词:\n");
                for (int i = 0; i < Math.min(5, topKeywords.size()); i++) {
                    KeywordInfo keyword = topKeywords.get(i);
                    report.append((i + 1)).append(". ").append(keyword.word)
                          .append(" (").append(keyword.frequency).append("次)\n");
                }
                report.append("\n");
            }
            
            // 活跃成员
            if (topActiveMembers != null && !topActiveMembers.isEmpty()) {
                report.append("👥 活跃成员:\n");
                for (int i = 0; i < Math.min(5, topActiveMembers.size()); i++) {
                    MemberActivityInfo member = topActiveMembers.get(i);
                    report.append((i + 1)).append(". ").append(member.displayName)
                          .append(" (").append(member.messageCount).append("条消息)\n");
                }
                report.append("\n");
            }
            
            // 亮点内容
            if (highlights != null && !highlights.isEmpty()) {
                report.append("✨ 今日亮点:\n");
                for (String highlight : highlights) {
                    report.append("• ").append(highlight).append("\n");
                }
            }
            
            return report.toString();
        }
        
        /**
         * 格式化文件大小
         */
        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
        
        /**
         * 转换为JSON格式
         */
        public String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"roomId\": \"").append(roomId).append("\",\n");
            json.append("  \"roomName\": \"").append(roomName).append("\",\n");
            json.append("  \"date\": \"").append(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\",\n");
            json.append("  \"totalMessages\": ").append(totalMessages).append(",\n");
            json.append("  \"totalImages\": ").append(totalImages).append(",\n");
            json.append("  \"sentimentScore\": ").append(sentimentScore).append(",\n");
            json.append("  \"peakHour\": ").append(peakHour).append(",\n");
            json.append("  \"totalActiveMembers\": ").append(totalActiveMembers).append("\n");
            json.append("}");
            return json.toString();
        }
    }
    
    /**
      * 关键词信息
      */
     public static class KeywordInfo {
         public String word;
         public int count;
         public int frequency; // 添加frequency字段以兼容报告生成
         
         public KeywordInfo(String word, int count) {
             this.word = word;
             this.count = count;
             this.frequency = count; // 保持一致
         }
         
         public JSONObject toJson() {
             JSONObject json = new JSONObject();
             json.set("word", word);
             json.set("count", count);
             json.set("frequency", frequency);
             return json;
         }
     }
     
     /**
      * 成员活跃度信息
      */
     public static class MemberActivityInfo {
         public String nickName;
         public String starName;
         public String displayName; // 添加displayName字段
         public int messageCount;
         public int totalLength;
         public Set<Integer> hours = new HashSet<>();
         
         public JSONObject toJson() {
             JSONObject json = new JSONObject();
             json.set("nickName", nickName);
             json.set("starName", starName);
             json.set("displayName", displayName);
             json.set("messageCount", messageCount);
             json.set("totalLength", totalLength);
             json.set("activeHours", new ArrayList<>(hours));
             return json;
         }
     }
    
    /**
      * 图片分析信息
      */
     public static class ImageAnalysisInfo {
         public String fileName;
         public String format;
         public int width;
         public int height;
         public long fileSize;
         public String imageType;
         public String publisher;
         public long timestamp;
         public boolean isDuplicate;
         
         public JSONObject toJson() {
             JSONObject json = new JSONObject();
             json.set("fileName", fileName);
             json.set("format", format);
             json.set("width", width);
             json.set("height", height);
             json.set("fileSize", fileSize);
             json.set("imageType", imageType);
             json.set("publisher", publisher);
             json.set("timestamp", timestamp);
             json.set("isDuplicate", isDuplicate);
             return json;
         }
     }
}