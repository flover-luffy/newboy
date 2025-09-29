package net.luffy.util.summary.nlp;

import net.luffy.util.UnifiedLogger;
import java.util.*;

/**
 * NLP算法测试运行器
 * 用于测试和验证TF-IDF和增强情感分析算法的效果
 * 
 * @author Luffy
 * @since 2024-01-20
 */
public class NlpTestRunner {
    
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final TfIdfAnalyzer tfIdfAnalyzer;
    private final EnhancedSentimentAnalyzer sentimentAnalyzer;
    
    // 测试用的停用词
    private static final Set<String> TEST_STOP_WORDS = Set.of(
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "那", "什么", "可以", "这个", "还", "比"
    );
    
    public NlpTestRunner() {
        this.tfIdfAnalyzer = new TfIdfAnalyzer(TEST_STOP_WORDS);
        this.sentimentAnalyzer = new EnhancedSentimentAnalyzer();
    }
    
    /**
     * 运行所有测试
     */
    public void runAllTests() {
        logger.info("NlpTestRunner", "开始运行NLP算法测试...");
        
        testTfIdfAnalyzer();
        testSentimentAnalyzer();
        testIntegratedAnalysis();
        
        logger.info("NlpTestRunner", "NLP算法测试完成！");
    }
    
    /**
     * 测试TF-IDF算法
     */
    public void testTfIdfAnalyzer() {
        logger.info("NlpTestRunner", "=== TF-IDF算法测试 ===");
        
        // 准备测试文档
        Map<String, String> testDocuments = new HashMap<>();
        testDocuments.put("doc1", "今天天气真好，阳光明媚，适合出去玩。大家都很开心，心情特别好。");
        testDocuments.put("doc2", "昨天下雨了，心情有点郁闷。不过雨后的空气很清新，还是挺不错的。");
        testDocuments.put("doc3", "最近工作压力很大，感觉很累。希望能够放松一下，去旅游散散心。");
        testDocuments.put("doc4", "新的项目进展顺利，团队合作很好。大家都很努力，相信会有好结果。");
        testDocuments.put("doc5", "今天看了一部电影，剧情很精彩。演员演技也很棒，推荐大家去看。");
        
        // 添加文档到分析器
        tfIdfAnalyzer.clear();
        tfIdfAnalyzer.addDocuments(testDocuments);
        
        // 获取语料库统计信息
        TfIdfAnalyzer.CorpusStats stats = tfIdfAnalyzer.getCorpusStats();
        logger.info("NlpTestRunner", "语料库统计: " + stats.toString());
        
        // 获取全局关键词
        List<TfIdfAnalyzer.KeywordScore> globalKeywords = tfIdfAnalyzer.getTopKeywords(10);
        logger.info("NlpTestRunner", "全局关键词 (TF-IDF):");
        for (TfIdfAnalyzer.KeywordScore keyword : globalKeywords) {
            logger.info("NlpTestRunner", "  " + keyword.toString());
        }
        
        // 测试单个文档的关键词
        logger.info("NlpTestRunner", "文档1关键词:");
        List<TfIdfAnalyzer.KeywordScore> doc1Keywords = tfIdfAnalyzer.getDocumentKeywords("doc1", 5);
        for (TfIdfAnalyzer.KeywordScore keyword : doc1Keywords) {
            logger.info("NlpTestRunner", "  " + keyword.toString());
        }
        
        // 测试文档相似度
        double similarity12 = tfIdfAnalyzer.calculateCosineSimilarity("doc1", "doc2");
        double similarity14 = tfIdfAnalyzer.calculateCosineSimilarity("doc1", "doc4");
        logger.info("NlpTestRunner", String.format("文档1与文档2相似度: %.4f", similarity12));
        logger.info("NlpTestRunner", String.format("文档1与文档4相似度: %.4f", similarity14));
        
        logger.info("NlpTestRunner", "TF-IDF算法测试完成\n");
    }
    
    /**
     * 测试增强情感分析算法
     */
    public void testSentimentAnalyzer() {
        logger.info("NlpTestRunner", "=== 增强情感分析算法测试 ===");
        
        // 准备测试文本
        String[] testTexts = {
            "今天心情非常好！阳光明媚，一切都很完美😊",
            "工作压力太大了，感觉很累很烦躁😞",
            "这个电影超级棒！演技很厉害，剧情也很精彩👍",
            "天气一般般，没什么特别的感觉",
            "不是很喜欢这个设计，感觉有点普通",
            "哇！这个消息太震撼了！完全没想到会这样！！！",
            "还行吧，凑合能用，不算太差",
            "绝对不推荐！质量太糟糕了，完全是浪费钱💔"
        };
        
        for (int i = 0; i < testTexts.length; i++) {
            String text = testTexts[i];
            EnhancedSentimentAnalyzer.SentimentResult result = sentimentAnalyzer.analyzeSentiment(text);
            
            logger.info("NlpTestRunner", String.format("文本%d: %s", i + 1, text));
            logger.info("NlpTestRunner", String.format("  情感分析: %s", result.getSentimentDescription()));
            logger.info("NlpTestRunner", String.format("  情感词汇: %s", result.getEmotionWords()));
            
            // 显示句子级别分析
            if (result.sentences.size() > 1) {
                logger.info("NlpTestRunner", "  句子分析:");
                for (EnhancedSentimentAnalyzer.SentenceAnalysis sentence : result.sentences) {
                    logger.info("NlpTestRunner", String.format("    \"%s\" -> %.2f", 
                            sentence.sentence, sentence.sentimentScore));
                }
            }
            logger.info("NlpTestRunner", "");
        }
        
        // 批量分析测试
        Map<String, String> batchTexts = new HashMap<>();
        for (int i = 0; i < testTexts.length; i++) {
            batchTexts.put("text_" + (i + 1), testTexts[i]);
        }
        
        Map<String, EnhancedSentimentAnalyzer.SentimentResult> batchResults = 
                sentimentAnalyzer.batchAnalyze(batchTexts);
        
        logger.info("NlpTestRunner", "批量分析结果统计:");
        Map<EnhancedSentimentAnalyzer.SentimentType, Integer> typeCount = new HashMap<>();
        double totalScore = 0.0;
        
        for (EnhancedSentimentAnalyzer.SentimentResult result : batchResults.values()) {
            typeCount.merge(result.sentimentType, 1, Integer::sum);
            totalScore += result.sentimentScore;
        }
        
        for (Map.Entry<EnhancedSentimentAnalyzer.SentimentType, Integer> entry : typeCount.entrySet()) {
            logger.info("NlpTestRunner", String.format("  %s: %d条", 
                    entry.getKey().getDescription(), entry.getValue()));
        }
        logger.info("NlpTestRunner", String.format("  平均情感得分: %.2f", totalScore / batchResults.size()));
        
        logger.info("NlpTestRunner", "增强情感分析算法测试完成\n");
    }
    
    /**
     * 测试集成分析
     */
    public void testIntegratedAnalysis() {
        logger.info("NlpTestRunner", "=== 集成分析测试 ===");
        
        // 模拟房间消息数据
        List<String> roomMessages = Arrays.asList(
            "早上好大家！今天天气真不错😊",
            "昨天的直播太精彩了！感谢小姐姐的精彩表演👏",
            "工作好累啊，希望能早点下班",
            "这个新歌真的很好听，循环播放中🎵",
            "有点想家了，好久没回去看看了",
            "今天心情特别好，什么都很顺利✨",
            "这个活动安排得很棒，期待下次",
            "天气预报说明天要下雨，记得带伞",
            "新的周边设计很可爱，已经下单了💕",
            "最近压力有点大，需要放松一下"
        );
        
        // TF-IDF关键词分析
        tfIdfAnalyzer.clear();
        Map<String, String> messageMap = new HashMap<>();
        for (int i = 0; i < roomMessages.size(); i++) {
            messageMap.put("msg_" + i, roomMessages.get(i));
        }
        tfIdfAnalyzer.addDocuments(messageMap);
        
        List<TfIdfAnalyzer.KeywordScore> keywords = tfIdfAnalyzer.getTopKeywords(8);
        logger.info("NlpTestRunner", "房间消息关键词分析:");
        for (TfIdfAnalyzer.KeywordScore keyword : keywords) {
            logger.info("NlpTestRunner", "  " + keyword.toString());
        }
        
        // 整体情感分析
        String combinedText = String.join(" ", roomMessages);
        EnhancedSentimentAnalyzer.SentimentResult overallSentiment = 
                sentimentAnalyzer.analyzeSentiment(combinedText);
        
        logger.info("NlpTestRunner", "房间整体情感分析:");
        logger.info("NlpTestRunner", "  " + overallSentiment.getSentimentDescription());
        logger.info("NlpTestRunner", "  主要情感词汇: " + overallSentiment.getEmotionWords());
        
        // 消息情感分布
        Map<EnhancedSentimentAnalyzer.SentimentType, Integer> sentimentDistribution = new HashMap<>();
        double totalSentimentScore = 0.0;
        
        for (String message : roomMessages) {
            EnhancedSentimentAnalyzer.SentimentResult result = sentimentAnalyzer.analyzeSentiment(message);
            sentimentDistribution.merge(result.sentimentType, 1, Integer::sum);
            totalSentimentScore += result.sentimentScore;
        }
        
        logger.info("NlpTestRunner", "消息情感分布:");
        for (Map.Entry<EnhancedSentimentAnalyzer.SentimentType, Integer> entry : sentimentDistribution.entrySet()) {
            double percentage = (double) entry.getValue() / roomMessages.size() * 100;
            logger.info("NlpTestRunner", String.format("  %s: %d条 (%.1f%%)", 
                    entry.getKey().getDescription(), entry.getValue(), percentage));
        }
        
        logger.info("NlpTestRunner", String.format("平均情感得分: %.2f", totalSentimentScore / roomMessages.size()));
        
        logger.info("NlpTestRunner", "集成分析测试完成\n");
    }
    
    /**
     * 性能测试
     */
    public void performanceTest() {
        logger.info("NlpTestRunner", "=== 性能测试 ===");
        
        // 生成大量测试数据
        List<String> largeDataset = new ArrayList<>();
        String[] templates = {
            "今天心情很好，天气也不错",
            "工作有点累，但是很充实",
            "这个活动很有趣，大家都很开心",
            "最近压力比较大，需要调整一下",
            "新的项目进展顺利，团队合作很好"
        };
        
        for (int i = 0; i < 1000; i++) {
            largeDataset.add(templates[i % templates.length] + " " + i);
        }
        
        // TF-IDF性能测试
        long startTime = System.currentTimeMillis();
        tfIdfAnalyzer.clear();
        Map<String, String> largeDocuments = new HashMap<>();
        for (int i = 0; i < largeDataset.size(); i++) {
            largeDocuments.put("doc_" + i, largeDataset.get(i));
        }
        tfIdfAnalyzer.addDocuments(largeDocuments);
        List<TfIdfAnalyzer.KeywordScore> perfKeywords = tfIdfAnalyzer.getTopKeywords(20);
        long tfIdfTime = System.currentTimeMillis() - startTime;
        
        logger.info("NlpTestRunner", String.format("TF-IDF处理%d条文档耗时: %d毫秒", 
                largeDataset.size(), tfIdfTime));
        
        // 情感分析性能测试
        startTime = System.currentTimeMillis();
        Map<String, String> sentimentTexts = new HashMap<>();
        for (int i = 0; i < Math.min(100, largeDataset.size()); i++) {
            sentimentTexts.put("text_" + i, largeDataset.get(i));
        }
        Map<String, EnhancedSentimentAnalyzer.SentimentResult> perfSentiments = 
                sentimentAnalyzer.batchAnalyze(sentimentTexts);
        long sentimentTime = System.currentTimeMillis() - startTime;
        
        logger.info("NlpTestRunner", String.format("情感分析处理%d条文本耗时: %d毫秒", 
                sentimentTexts.size(), sentimentTime));
        
        logger.info("NlpTestRunner", "性能测试完成\n");
    }
    
    /**
     * 主测试方法
     */
    public static void main(String[] args) {
        NlpTestRunner testRunner = new NlpTestRunner();
        testRunner.runAllTests();
        testRunner.performanceTest();
    }
}