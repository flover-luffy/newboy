package net.luffy.util.summary;

import net.luffy.util.summary.ContentAnalyzer;
import net.luffy.util.UnifiedLogger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * 每日总结图片生成器 - 重新设计版本
 * 
 * 主要功能：
 * 1. 生成内容摘要图片而非统计图表
 * 2. 展示房间亮点内容和关键信息
 * 3. 美观的卡片式布局设计
 * 4. 支持多房间内容展示
 * 5. 自适应内容长度和布局
 * 
 * @author Luffy
 * @since 2024-01-20
 */
public class DailySummaryImageGenerator {
    
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    
    // 图片配置
    private static final int IMAGE_WIDTH = 1200;
    private static final int IMAGE_HEIGHT = 1600;
    private static final int MARGIN = 40;
    private static final int CARD_MARGIN = 20;
    private static final int CARD_PADDING = 20;
    private static final int CARD_RADIUS = 15;
    
    // 颜色配置
    private static final Color BACKGROUND_COLOR = new Color(245, 247, 250);
    private static final Color CARD_BACKGROUND = Color.WHITE;
    private static final Color PRIMARY_COLOR = new Color(74, 144, 226);
    private static final Color SECONDARY_COLOR = new Color(108, 117, 125);
    private static final Color ACCENT_COLOR = new Color(255, 193, 7);
    private static final Color SUCCESS_COLOR = new Color(40, 167, 69);
    private static final Color DANGER_COLOR = new Color(220, 53, 69);
    private static final Color TEXT_COLOR = new Color(33, 37, 41);
    private static final Color LIGHT_TEXT_COLOR = new Color(108, 117, 125);
    
    // 字体配置
    private Font titleFont;
    private Font headerFont;
    private Font bodyFont;
    private Font smallFont;
    private Font emojiFont;
    
    // 字体常量（用于向后兼容）
    private Font TITLE_FONT;
    private Font HEADER_FONT;
    private Font BODY_FONT;
    private Font SMALL_FONT;
    
    // 输出目录
    private String outputDirectory = "data/daily_images/";
    
    public DailySummaryImageGenerator() {
        initializeFonts();
    }
    
    /**
     * 初始化字体
     */
    private void initializeFonts() {
        try {
            // 使用系统字体，确保中文显示正常
            titleFont = new Font("Microsoft YaHei", Font.BOLD, 32);
            headerFont = new Font("Microsoft YaHei", Font.BOLD, 20);
            bodyFont = new Font("Microsoft YaHei", Font.PLAIN, 16);
            smallFont = new Font("Microsoft YaHei", Font.PLAIN, 14);
            emojiFont = new Font("Segoe UI Emoji", Font.PLAIN, 16);
            
            // 设置常量字体（向后兼容）
            TITLE_FONT = titleFont;
            HEADER_FONT = headerFont;
            BODY_FONT = bodyFont;
            SMALL_FONT = smallFont;
        } catch (Exception e) {
            // 降级到默认字体
            titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 32);
            headerFont = new Font(Font.SANS_SERIF, Font.BOLD, 20);
            bodyFont = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
            smallFont = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
            emojiFont = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
            
            // 设置常量字体（向后兼容）
            TITLE_FONT = titleFont;
            HEADER_FONT = headerFont;
            BODY_FONT = bodyFont;
            SMALL_FONT = smallFont;
        }
    }
    
    /**
     * 生成内容摘要图片（多房间版本，保持兼容性）
     */
    public String generateContentSummaryImage(Map<String, ContentAnalyzer.RoomContentAnalysis> roomAnalyses, LocalDate date) {
        return generateDailyContentSummary(date, roomAnalyses);
    }
    
    /**
     * 生成单个房间的内容摘要图片
     */
    public String generateRoomContentSummaryImage(ContentAnalyzer.RoomContentAnalysis roomAnalysis, LocalDate date) {
        if (roomAnalysis == null) {
            logger.error("DailySummaryImageGenerator", "房间分析数据为空，无法生成图片");
            return null;
        }
        
        try {
            String fileName = String.format("room_summary_%s_%s.png", 
                    roomAnalysis.roomId, 
                    date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            String outputPath = Paths.get(outputDirectory, fileName).toString();
            
            // 创建图片
            BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();
            
            // 设置抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            
            // 绘制背景
            g2d.setColor(BACKGROUND_COLOR);
            g2d.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
            
            int currentY = MARGIN;
            
            // 绘制标题
            currentY = drawSingleRoomTitle(g2d, currentY, roomAnalysis, date);
            
            // 绘制房间内容卡片
            currentY = drawSingleRoomContentCard(g2d, currentY, roomAnalysis);
            
            // 绘制底部信息
            drawFooter(g2d, IMAGE_HEIGHT - 60);
            
            g2d.dispose();
            
            // 保存图片
            File outputFile = new File(outputPath);
            outputFile.getParentFile().mkdirs();
            ImageIO.write(image, "PNG", outputFile);
            
            logger.info("DailySummaryImageGenerator", "房间内容摘要图片生成成功: " + outputPath);
            return outputPath;
            
        } catch (Exception e) {
            logger.error("DailySummaryImageGenerator", "生成房间内容摘要图片失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 生成测试图片
     */
    public File generateTestImage(String imagePath) {
        try {
            // 创建测试数据
            Map<String, ContentAnalyzer.RoomContentAnalysis> testData = new HashMap<>();
            ContentAnalyzer.RoomContentAnalysis testAnalysis = createTestAnalysis();
            testData.put("test_room", testAnalysis);
            
            // 生成图片
            String generatedPath = generateDailyContentSummary(LocalDate.now(), testData);
            return new File(generatedPath);
        } catch (Exception e) {
            logger.error("DailySummaryImageGenerator", "生成测试图片失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 生成每日内容摘要图片
     */
    public String generateDailyContentSummary(LocalDate date, Map<String, ContentAnalyzer.RoomContentAnalysis> roomAnalyses) {
        if (roomAnalyses == null || roomAnalyses.isEmpty()) {
            logger.warn("DailySummaryImageGenerator", "没有房间分析数据，无法生成图片");
            return null;
        }
        
        try {
            BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();
            
            // 启用抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            
            // 绘制背景
            drawBackground(g2d);
            
            // 绘制标题
            int currentY = drawTitle(g2d, date);
            
            // 绘制总体概览
            currentY = drawOverview(g2d, currentY, roomAnalyses);
            
            // 绘制房间内容卡片
            currentY = drawRoomContentCards(g2d, currentY, roomAnalyses);
            
            // 绘制底部信息
            drawFooter(g2d);
            
            g2d.dispose();
            
            // 保存图片
            String filename = "daily_content_summary_" + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".png";
            String outputPath = "data/daily_images/" + filename;
            
            File outputDir = new File("data/daily_images/");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            
            File outputFile = new File(outputPath);
            ImageIO.write(image, "PNG", outputFile);
            
            logger.info("DailySummaryImageGenerator", "生成内容摘要图片: " + outputPath);
            return outputPath;
            
        } catch (Exception e) {
            logger.error("DailySummaryImageGenerator", "生成图片失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 绘制背景
     */
    private void drawBackground(Graphics2D g2d) {
        // 渐变背景
        GradientPaint gradient = new GradientPaint(
            0, 0, BACKGROUND_COLOR,
            0, IMAGE_HEIGHT, new Color(235, 240, 245)
        );
        g2d.setPaint(gradient);
        g2d.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
    }
    
    /**
     * 绘制标题
     */
    private int drawTitle(Graphics2D g2d, LocalDate date) {
        g2d.setFont(titleFont);
        g2d.setColor(TEXT_COLOR);
        
        String title = "📊 每日内容摘要";
        String dateStr = date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        
        FontMetrics fm = g2d.getFontMetrics();
        int titleWidth = fm.stringWidth(title);
        int titleX = (IMAGE_WIDTH - titleWidth) / 2;
        int titleY = MARGIN + fm.getAscent();
        
        g2d.drawString(title, titleX, titleY);
        
        // 绘制日期
        g2d.setFont(headerFont);
        g2d.setColor(SECONDARY_COLOR);
        FontMetrics dateFm = g2d.getFontMetrics();
        int dateWidth = dateFm.stringWidth(dateStr);
        int dateX = (IMAGE_WIDTH - dateWidth) / 2;
        int dateY = titleY + dateFm.getHeight() + 10;
        
        g2d.drawString(dateStr, dateX, dateY);
        
        return dateY + 30;
    }
    
    /**
     * 绘制总体概览
     */
    private int drawOverview(Graphics2D g2d, int startY, Map<String, ContentAnalyzer.RoomContentAnalysis> roomAnalyses) {
        // 计算总体统计
        int totalRooms = roomAnalyses.size();
        int totalMessages = roomAnalyses.values().stream().mapToInt(a -> a.totalMessages).sum();
        int totalImages = roomAnalyses.values().stream().mapToInt(a -> a.totalImages).sum();
        
        // 绘制概览卡片
        int cardWidth = IMAGE_WIDTH - 2 * MARGIN;
        int cardHeight = 120;
        
        drawCard(g2d, MARGIN, startY, cardWidth, cardHeight, CARD_BACKGROUND);
        
        // 绘制概览内容
        g2d.setFont(headerFont);
        g2d.setColor(TEXT_COLOR);
        g2d.drawString("📈 今日概览", MARGIN + CARD_PADDING, startY + CARD_PADDING + 20);
        
        // 统计数据
        int statsY = startY + CARD_PADDING + 50;
        int statsSpacing = (cardWidth - 2 * CARD_PADDING) / 3;
        
        drawStatItem(g2d, MARGIN + CARD_PADDING, statsY, "监控房间", totalRooms + "个", PRIMARY_COLOR);
        drawStatItem(g2d, MARGIN + CARD_PADDING + statsSpacing, statsY, "收集消息", totalMessages + "条", SUCCESS_COLOR);
        drawStatItem(g2d, MARGIN + CARD_PADDING + 2 * statsSpacing, statsY, "图片分享", totalImages + "张", ACCENT_COLOR);
        
        return startY + cardHeight + CARD_MARGIN;
    }
    
    /**
     * 绘制单房间标题
     */
    private int drawSingleRoomTitle(Graphics2D g2d, int startY, ContentAnalyzer.RoomContentAnalysis roomAnalysis, LocalDate date) {
        // 绘制主标题
        g2d.setFont(TITLE_FONT);
        g2d.setColor(PRIMARY_COLOR);
        String title = roomAnalysis.roomName + " 每日总结";
        FontMetrics fm = g2d.getFontMetrics();
        int titleWidth = fm.stringWidth(title);
        g2d.drawString(title, (IMAGE_WIDTH - titleWidth) / 2, startY + fm.getAscent());
        
        int currentY = startY + fm.getHeight() + 10;
        
        // 绘制日期
        g2d.setFont(HEADER_FONT);
        g2d.setColor(SECONDARY_COLOR);
        String dateStr = date.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
        fm = g2d.getFontMetrics();
        int dateWidth = fm.stringWidth(dateStr);
        g2d.drawString(dateStr, (IMAGE_WIDTH - dateWidth) / 2, currentY + fm.getAscent());
        
        return currentY + fm.getHeight() + 20;
    }
    
    /**
     * 绘制单房间内容卡片
     */
    private int drawSingleRoomContentCard(Graphics2D g2d, int startY, ContentAnalyzer.RoomContentAnalysis roomAnalysis) {
        int cardWidth = IMAGE_WIDTH - 2 * MARGIN;
        int cardHeight = calculateSingleRoomCardHeight(roomAnalysis);
        
        // 绘制卡片背景
        g2d.setColor(CARD_BACKGROUND);
        g2d.fillRoundRect(MARGIN, startY, cardWidth, cardHeight, CARD_RADIUS, CARD_RADIUS);
        
        // 绘制卡片边框
        g2d.setColor(SECONDARY_COLOR);
        g2d.setStroke(new BasicStroke(1));
        g2d.drawRoundRect(MARGIN, startY, cardWidth, cardHeight, CARD_RADIUS, CARD_RADIUS);
        
        int currentY = startY + 20;
        int leftX = MARGIN + 20;
        int rightX = MARGIN + cardWidth / 2 + 10;
        
        // 左侧：基本统计
        g2d.setFont(HEADER_FONT);
        g2d.setColor(PRIMARY_COLOR);
        g2d.drawString("📊 基本统计", leftX, currentY);
        currentY += 25;
        
        g2d.setFont(BODY_FONT);
        g2d.setColor(TEXT_COLOR);
        g2d.drawString("总消息数: " + roomAnalysis.totalMessages, leftX, currentY);
        currentY += 20;
        g2d.drawString("图片数量: " + roomAnalysis.totalImages, leftX, currentY);
        currentY += 20;
        g2d.drawString("活跃成员: " + roomAnalysis.totalActiveMembers, leftX, currentY);
        currentY += 20;
        
        // 情感分析
        String sentiment = getSentimentText(roomAnalysis.sentimentScore);
        g2d.drawString("房间氛围: " + sentiment, leftX, currentY);
        currentY += 30;
        
        // 右侧：热门关键词
        int rightY = startY + 20;
        g2d.setFont(HEADER_FONT);
        g2d.setColor(PRIMARY_COLOR);
        g2d.drawString("🔥 热门关键词", rightX, rightY);
        rightY += 25;
        
        g2d.setFont(BODY_FONT);
        g2d.setColor(TEXT_COLOR);
        if (roomAnalysis.topKeywords != null && !roomAnalysis.topKeywords.isEmpty()) {
            int count = 0;
            for (ContentAnalyzer.KeywordInfo keyword : roomAnalysis.topKeywords) {
                if (count >= 5) break;
                g2d.drawString("? " + keyword.word + " (" + keyword.count + ")", rightX, rightY);
                rightY += 20;
                count++;
            }
        } else {
            g2d.drawString("暂无关键词数据", rightX, rightY);
        }
        
        // 亮点内容
        currentY = Math.max(currentY, rightY) + 10;
        g2d.setFont(HEADER_FONT);
        g2d.setColor(PRIMARY_COLOR);
        g2d.drawString("✨ 今日亮点", leftX, currentY);
        currentY += 25;
        
        g2d.setFont(BODY_FONT);
        g2d.setColor(TEXT_COLOR);
        if (roomAnalysis.highlights != null && !roomAnalysis.highlights.isEmpty()) {
            for (String highlight : roomAnalysis.highlights) {
                if (currentY > startY + cardHeight - 30) break;
                g2d.drawString("• " + highlight, leftX, currentY);
                currentY += 20;
            }
        } else {
            g2d.drawString("• 房间保持正常活跃状态", leftX, currentY);
        }
        
        return startY + cardHeight + CARD_MARGIN;
    }
    
    /**
     * 计算单房间卡片高度
     */
    private int calculateSingleRoomCardHeight(ContentAnalyzer.RoomContentAnalysis roomAnalysis) {
        int baseHeight = 200; // 基础高度
        
        // 根据关键词数量调整
        if (roomAnalysis.topKeywords != null) {
            baseHeight += Math.min(roomAnalysis.topKeywords.size(), 5) * 20;
        }
        
        // 根据亮点数量调整
        if (roomAnalysis.highlights != null) {
            baseHeight += Math.min(roomAnalysis.highlights.size(), 6) * 20;
        }
        
        return Math.max(baseHeight, 250);
    }
    
    /**
     * 获取情感分析文本
     */
    private String getSentimentText(double sentimentScore) {
        if (sentimentScore > 0.6) {
            return "积极正面 😊";
        } else if (sentimentScore > 0.3) {
            return "中性平和 😐";
        } else if (sentimentScore > -0.3) {
            return "略显消极 😕";
        } else {
            return "较为消极 😞";
        }
    }
    private int drawRoomContentCards(Graphics2D g2d, int startY, Map<String, ContentAnalyzer.RoomContentAnalysis> roomAnalyses) {
        int currentY = startY;
        int cardWidth = IMAGE_WIDTH - 2 * MARGIN;
        
        // 按消息数量排序，显示前4个最活跃的房间
        List<ContentAnalyzer.RoomContentAnalysis> sortedRooms = roomAnalyses.values().stream()
                .sorted((a, b) -> Integer.compare(b.totalMessages, a.totalMessages))
                .limit(4)
                .toList();
        
        for (ContentAnalyzer.RoomContentAnalysis analysis : sortedRooms) {
            int cardHeight = calculateRoomCardHeight(analysis);
            
            // 检查是否需要换页（简单处理，实际可以更复杂）
            if (currentY + cardHeight > IMAGE_HEIGHT - 100) {
                break;
            }
            
            drawRoomContentCard(g2d, MARGIN, currentY, cardWidth, cardHeight, analysis);
            currentY += cardHeight + CARD_MARGIN;
        }
        
        return currentY;
    }
    
    /**
     * 绘制单个房间内容卡片
     */
    private void drawRoomContentCard(Graphics2D g2d, int x, int y, int width, int height, ContentAnalyzer.RoomContentAnalysis analysis) {
        // 绘制卡片背景
        drawCard(g2d, x, y, width, height, CARD_BACKGROUND);
        
        int contentX = x + CARD_PADDING;
        int contentY = y + CARD_PADDING;
        int contentWidth = width - 2 * CARD_PADDING;
        
        // 房间标题
        g2d.setFont(headerFont);
        g2d.setColor(TEXT_COLOR);
        String roomTitle = "🏠 " + analysis.roomName;
        g2d.drawString(roomTitle, contentX, contentY + 20);
        
        // 消息统计
        g2d.setFont(smallFont);
        g2d.setColor(LIGHT_TEXT_COLOR);
        String stats = String.format("共 %d 条消息 | %d 张图片 | %d 条文本", 
                analysis.totalMessages, analysis.totalImages, analysis.totalMessages);
        g2d.drawString(stats, contentX, contentY + 45);
        
        // 亮点内容
        int highlightY = contentY + 70;
        if (!analysis.highlights.isEmpty()) {
            g2d.setFont(bodyFont);
            g2d.setColor(TEXT_COLOR);
            
            for (int i = 0; i < Math.min(analysis.highlights.size(), 3); i++) {
                String highlight = analysis.highlights.get(i);
                if (highlight.length() > 50) {
                    highlight = highlight.substring(0, 47) + "...";
                }
                g2d.drawString("• " + highlight, contentX, highlightY);
                highlightY += 25;
            }
        }
        
        // 关键词标签
        if (!analysis.topKeywords.isEmpty()) {
            int tagY = highlightY + 10;
            int tagX = contentX;
            
            g2d.setFont(smallFont);
            for (int i = 0; i < Math.min(analysis.topKeywords.size(), 5); i++) {
                ContentAnalyzer.KeywordInfo keyword = analysis.topKeywords.get(i);
                String tagText = "#" + keyword.word;
                
                FontMetrics fm = g2d.getFontMetrics();
                int tagWidth = fm.stringWidth(tagText) + 16;
                int tagHeight = 24;
                
                // 检查是否需要换行
                if (tagX + tagWidth > contentX + contentWidth) {
                    tagX = contentX;
                    tagY += tagHeight + 5;
                }
                
                // 绘制标签背景
                g2d.setColor(new Color(PRIMARY_COLOR.getRed(), PRIMARY_COLOR.getGreen(), PRIMARY_COLOR.getBlue(), 30));
                g2d.fillRoundRect(tagX, tagY - 15, tagWidth, tagHeight, 12, 12);
                
                // 绘制标签文本
                g2d.setColor(PRIMARY_COLOR);
                g2d.drawString(tagText, tagX + 8, tagY);
                
                tagX += tagWidth + 8;
            }
        }
        
        // 情感指示器
        if (analysis.sentimentScore != 0) {
            int emotionX = x + width - 60;
            int emotionY = y + 30;
            
            if (analysis.sentimentScore > 0.3) {
                g2d.setColor(SUCCESS_COLOR);
                g2d.setFont(emojiFont);
                g2d.drawString("😊", emotionX, emotionY);
            } else if (analysis.sentimentScore < -0.3) {
                g2d.setColor(DANGER_COLOR);
                g2d.setFont(emojiFont);
                g2d.drawString("😔", emotionX, emotionY);
            } else {
                g2d.setColor(SECONDARY_COLOR);
                g2d.setFont(emojiFont);
                g2d.drawString("😐", emotionX, emotionY);
            }
        }
    }
    
    /**
     * 计算房间卡片高度
     */
    private int calculateRoomCardHeight(ContentAnalyzer.RoomContentAnalysis analysis) {
        int baseHeight = 120; // 基础高度
        int highlightLines = Math.min(analysis.highlights.size(), 3);
        int keywordLines = (int) Math.ceil(Math.min(analysis.topKeywords.size(), 5) / 5.0); // 假设每行5个关键词
        
        return baseHeight + highlightLines * 25 + keywordLines * 30;
    }
    
    /**
     * 绘制卡片
     */
    private void drawCard(Graphics2D g2d, int x, int y, int width, int height, Color backgroundColor) {
        // 绘制阴影
        g2d.setColor(new Color(0, 0, 0, 20));
        g2d.fillRoundRect(x + 2, y + 2, width, height, CARD_RADIUS, CARD_RADIUS);
        
        // 绘制卡片背景
        g2d.setColor(backgroundColor);
        g2d.fillRoundRect(x, y, width, height, CARD_RADIUS, CARD_RADIUS);
        
        // 绘制边框
        g2d.setColor(new Color(0, 0, 0, 10));
        g2d.setStroke(new BasicStroke(1));
        g2d.drawRoundRect(x, y, width, height, CARD_RADIUS, CARD_RADIUS);
    }
    
    /**
     * 绘制统计项
     */
    private void drawStatItem(Graphics2D g2d, int x, int y, String label, String value, Color color) {
        g2d.setFont(smallFont);
        g2d.setColor(LIGHT_TEXT_COLOR);
        g2d.drawString(label, x, y);
        
        g2d.setFont(headerFont);
        g2d.setColor(color);
        FontMetrics fm = g2d.getFontMetrics();
        g2d.drawString(value, x, y + fm.getHeight() + 5);
    }
    
    /**
     * 绘制底部信息
     */
    private void drawFooter(Graphics2D g2d, int y) {
        g2d.setFont(smallFont);
        g2d.setColor(LIGHT_TEXT_COLOR);
        
        String footerText = "Generated by Pocket48 Monitor • " + 
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(footerText);
        int textX = (IMAGE_WIDTH - textWidth) / 2;
        
        g2d.drawString(footerText, textX, y);
    }
    
    /**
     * 绘制底部信息（重载方法，保持兼容性）
     */
    private void drawFooter(Graphics2D g2d) {
        drawFooter(g2d, IMAGE_HEIGHT - 20);
    }
    
    /**
     * 生成测试图片
     */
    public String generateTestContentImage() {
        try {
            // 创建测试数据
            ContentAnalyzer.RoomContentAnalysis testAnalysis = createTestAnalysis();
            Map<String, ContentAnalyzer.RoomContentAnalysis> testData = Map.of("test", testAnalysis);
            
            return generateDailyContentSummary(LocalDate.now(), testData);
            
        } catch (Exception e) {
            logger.error("DailySummaryImageGenerator", "生成测试图片失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 创建测试分析数据
     */
    private ContentAnalyzer.RoomContentAnalysis createTestAnalysis() {
        ContentAnalyzer.RoomContentAnalysis analysis = new ContentAnalyzer.RoomContentAnalysis();
        analysis.roomId = "test";
        analysis.roomName = "测试房间";
        analysis.totalMessages = 156;
        analysis.totalImages = 25;
        analysis.sentimentScore = 0.6;
        
        // 初始化列表
        analysis.topKeywords = new ArrayList<>();
        analysis.highlights = new ArrayList<>();
        
        // 添加关键词
        analysis.topKeywords.add(new ContentAnalyzer.KeywordInfo("演出", 15));
        analysis.topKeywords.add(new ContentAnalyzer.KeywordInfo("生日", 12));
        analysis.topKeywords.add(new ContentAnalyzer.KeywordInfo("可爱", 10));
        analysis.topKeywords.add(new ContentAnalyzer.KeywordInfo("加油", 8));
        analysis.topKeywords.add(new ContentAnalyzer.KeywordInfo("期待", 6));
        
        // 添加亮点
        analysis.highlights.add("💬 今日消息活跃，共 156 条消息");
        analysis.highlights.add("📸 图片分享丰富，共 25 张图片");
        analysis.highlights.add("👑 最活跃成员：小可爱 (28 条消息)");
        analysis.highlights.add("🔥 热门关键词：演出 (出现 15 次)");
        analysis.highlights.add("😊 房间氛围积极正面");
        
        return analysis;
    }
}