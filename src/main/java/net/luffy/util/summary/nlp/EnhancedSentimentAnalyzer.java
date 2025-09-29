package net.luffy.util.summary.nlp;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 增强的情感分析器
 * 实现更复杂的NLP技术进行情感分析
 * 
 * 主要特性：
 * 1. 多层次情感词典（基础情感词 + 程度副词 + 否定词）
 * 2. 上下文感知的情感计算
 * 3. 表情符号情感分析
 * 4. 句子级别的情感分析
 * 5. 情感强度计算
 * 6. 情感分布分析
 * 
 * @author Luffy
 * @since 2024-01-20
 */
public class EnhancedSentimentAnalyzer {
    
    // 基础情感词典
    private static final Map<String, Double> EMOTION_WORDS = new HashMap<>();
    
    // 程度副词词典
    private static final Map<String, Double> DEGREE_WORDS = new HashMap<>();
    
    // 否定词词典
    private static final Set<String> NEGATION_WORDS = new HashSet<>();
    
    // 表情符号情感映射
    private static final Map<String, Double> EMOJI_SENTIMENT = new HashMap<>();
    
    // 标点符号权重
    private static final Map<String, Double> PUNCTUATION_WEIGHTS = new HashMap<>();
    
    // 句子分割模式
    private static final Pattern SENTENCE_PATTERN = Pattern.compile("[。！？!?；;]");
    
    // 表情符号模式
    private static final Pattern EMOJI_PATTERN = Pattern.compile(
        "[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]|[\\u2600-\\u27FF]|[\\uD83C\\uDF00-\\uD83C\\uDFFF]|[\\uD83D\\uDC00-\\uD83D\\uDEFF]"
    );
    
    static {
        initializeEmotionWords();
        initializeDegreeWords();
        initializeNegationWords();
        initializeEmojiSentiment();
        initializePunctuationWeights();
    }
    
    /**
     * 初始化情感词典
     */
    private static void initializeEmotionWords() {
        // 强正面情感 (2.0)
        String[] strongPositive = {"非常好", "太棒了", "超级棒", "完美", "优秀", "杰出", "卓越", "惊艳", "震撼", "感动"};
        for (String word : strongPositive) {
            EMOTION_WORDS.put(word, 2.0);
        }
        
        // 正面情感 (1.0)
        String[] positive = {"好", "棒", "赞", "喜欢", "爱", "开心", "高兴", "快乐", "兴奋", "激动", "满意", "感谢", "谢谢", "厉害", "可爱", "美丽", "漂亮", "帅", "酷", "温暖", "舒服", "舒适", "安心", "放心", "希望", "期待", "幸福", "甜蜜", "浪漫"};
        for (String word : positive) {
            EMOTION_WORDS.put(word, 1.0);
        }
        
        // 轻微正面情感 (0.5)
        String[] mildPositive = {"还行", "不错", "可以", "行", "挺好", "还好", "凑合", "马马虎虎"};
        for (String word : mildPositive) {
            EMOTION_WORDS.put(word, 0.5);
        }
        
        // 轻微负面情感 (-0.5)
        String[] mildNegative = {"一般", "普通", "平常", "无聊", "累", "困", "烦", "郁闷", "不爽", "不开心"};
        for (String word : mildNegative) {
            EMOTION_WORDS.put(word, -0.5);
        }
        
        // 负面情感 (-1.0)
        String[] negative = {"差", "坏", "糟糕", "讨厌", "恨", "难过", "伤心", "生气", "愤怒", "失望", "沮丧", "痛苦", "难受", "不舒服", "害怕", "恐惧", "担心", "焦虑", "紧张", "压力", "疲惫", "疲劳", "孤独", "寂寞", "冷漠", "无助", "绝望"};
        for (String word : negative) {
            EMOTION_WORDS.put(word, -1.0);
        }
        
        // 强负面情感 (-2.0)
        String[] strongNegative = {"非常差", "太糟糕", "超级烂", "恶心", "恶劣", "可怕", "恐怖", "愤怒", "暴怒", "崩溃", "绝望", "痛恨", "厌恶"};
        for (String word : strongNegative) {
            EMOTION_WORDS.put(word, -2.0);
        }
    }
    
    /**
     * 初始化程度副词
     */
    private static void initializeDegreeWords() {
        DEGREE_WORDS.put("非常", 2.0);
        DEGREE_WORDS.put("特别", 2.0);
        DEGREE_WORDS.put("超级", 2.0);
        DEGREE_WORDS.put("极其", 2.0);
        DEGREE_WORDS.put("十分", 1.8);
        DEGREE_WORDS.put("相当", 1.6);
        DEGREE_WORDS.put("很", 1.5);
        DEGREE_WORDS.put("挺", 1.3);
        DEGREE_WORDS.put("比较", 1.2);
        DEGREE_WORDS.put("还", 1.1);
        DEGREE_WORDS.put("稍微", 0.8);
        DEGREE_WORDS.put("有点", 0.8);
        DEGREE_WORDS.put("略", 0.7);
        DEGREE_WORDS.put("稍", 0.7);
    }
    
    /**
     * 初始化否定词
     */
    private static void initializeNegationWords() {
        NEGATION_WORDS.addAll(Arrays.asList(
            "不", "没", "无", "非", "未", "否", "别", "休", "勿", "莫", "毫无", "并非", "绝非", "决不", "从不", "永不", "不要", "不能", "不会", "不是", "没有", "没什么", "一点也不", "根本不", "完全不", "绝对不"
        ));
    }
    
    /**
     * 初始化表情符号情感
     */
    private static void initializeEmojiSentiment() {
        // 正面表情
        EMOJI_SENTIMENT.put("😊", 1.5);
        EMOJI_SENTIMENT.put("😄", 2.0);
        EMOJI_SENTIMENT.put("😃", 1.8);
        EMOJI_SENTIMENT.put("😁", 1.6);
        EMOJI_SENTIMENT.put("🙂", 1.0);
        EMOJI_SENTIMENT.put("😍", 2.0);
        EMOJI_SENTIMENT.put("🥰", 2.0);
        EMOJI_SENTIMENT.put("😘", 1.8);
        EMOJI_SENTIMENT.put("👍", 1.5);
        EMOJI_SENTIMENT.put("👏", 1.5);
        EMOJI_SENTIMENT.put("❤️", 2.0);
        EMOJI_SENTIMENT.put("💕", 1.8);
        EMOJI_SENTIMENT.put("🎉", 1.8);
        EMOJI_SENTIMENT.put("🌟", 1.5);
        
        // 负面表情
        EMOJI_SENTIMENT.put("😢", -1.5);
        EMOJI_SENTIMENT.put("😭", -2.0);
        EMOJI_SENTIMENT.put("😞", -1.3);
        EMOJI_SENTIMENT.put("😔", -1.2);
        EMOJI_SENTIMENT.put("😟", -1.0);
        EMOJI_SENTIMENT.put("😠", -1.8);
        EMOJI_SENTIMENT.put("😡", -2.0);
        EMOJI_SENTIMENT.put("🤬", -2.0);
        EMOJI_SENTIMENT.put("😰", -1.5);
        EMOJI_SENTIMENT.put("😨", -1.6);
        EMOJI_SENTIMENT.put("👎", -1.5);
        EMOJI_SENTIMENT.put("💔", -2.0);
        
        // 中性表情
        EMOJI_SENTIMENT.put("😐", 0.0);
        EMOJI_SENTIMENT.put("😑", 0.0);
        EMOJI_SENTIMENT.put("🤔", 0.0);
        EMOJI_SENTIMENT.put("😶", 0.0);
    }
    
    /**
     * 初始化标点符号权重
     */
    private static void initializePunctuationWeights() {
        PUNCTUATION_WEIGHTS.put("!", 1.3);
        PUNCTUATION_WEIGHTS.put("！", 1.3);
        PUNCTUATION_WEIGHTS.put("?", 1.1);
        PUNCTUATION_WEIGHTS.put("？", 1.1);
        PUNCTUATION_WEIGHTS.put("...", 0.9);
        PUNCTUATION_WEIGHTS.put("。。。", 0.9);
    }
    
    /**
     * 分析文本情感
     */
    public SentimentResult analyzeSentiment(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new SentimentResult(0.0, SentimentType.NEUTRAL, 0.0, new ArrayList<>());
        }
        
        // 分句分析
        List<SentenceAnalysis> sentences = analyzeSentences(text);
        
        // 计算整体情感得分
        double totalScore = sentences.stream()
                .mapToDouble(s -> s.sentimentScore)
                .average()
                .orElse(0.0);
        
        // 计算情感强度
        double intensity = calculateIntensity(sentences);
        
        // 确定情感类型
        SentimentType type = determineSentimentType(totalScore, intensity);
        
        return new SentimentResult(totalScore, type, intensity, sentences);
    }
    
    /**
     * 分析句子情感
     */
    private List<SentenceAnalysis> analyzeSentences(String text) {
        List<SentenceAnalysis> results = new ArrayList<>();
        
        // 分句
        String[] sentences = SENTENCE_PATTERN.split(text);
        
        for (String sentence : sentences) {
            sentence = sentence.trim();
            if (!sentence.isEmpty()) {
                SentenceAnalysis analysis = analyzeSingleSentence(sentence);
                results.add(analysis);
            }
        }
        
        return results;
    }
    
    /**
     * 分析单个句子
     */
    private SentenceAnalysis analyzeSingleSentence(String sentence) {
        double score = 0.0;
        List<String> emotionWords = new ArrayList<>();
        
        // 表情符号分析
        score += analyzeEmojis(sentence);
        
        // 词汇情感分析
        String[] words = sentence.split("[\\s\\p{Punct}]+");
        
        for (int i = 0; i < words.length; i++) {
            String word = words[i].trim();
            
            if (EMOTION_WORDS.containsKey(word)) {
                double wordScore = EMOTION_WORDS.get(word);
                emotionWords.add(word);
                
                // 检查程度副词
                double degreeMultiplier = 1.0;
                if (i > 0 && DEGREE_WORDS.containsKey(words[i-1])) {
                    degreeMultiplier = DEGREE_WORDS.get(words[i-1]);
                }
                
                // 检查否定词
                boolean isNegated = false;
                for (int j = Math.max(0, i-3); j < i; j++) {
                    if (NEGATION_WORDS.contains(words[j])) {
                        isNegated = true;
                        break;
                    }
                }
                
                // 计算最终得分
                double finalScore = wordScore * degreeMultiplier;
                if (isNegated) {
                    finalScore = -finalScore;
                }
                
                score += finalScore;
            }
        }
        
        // 标点符号权重
        for (Map.Entry<String, Double> entry : PUNCTUATION_WEIGHTS.entrySet()) {
            if (sentence.contains(entry.getKey())) {
                score *= entry.getValue();
            }
        }
        
        return new SentenceAnalysis(sentence, score, emotionWords);
    }
    
    /**
     * 分析表情符号
     */
    private double analyzeEmojis(String text) {
        double emojiScore = 0.0;
        
        for (Map.Entry<String, Double> entry : EMOJI_SENTIMENT.entrySet()) {
            String emoji = entry.getKey();
            double score = entry.getValue();
            
            // 计算表情符号出现次数
            int count = 0;
            int index = 0;
            while ((index = text.indexOf(emoji, index)) != -1) {
                count++;
                index += emoji.length();
            }
            
            emojiScore += score * count;
        }
        
        return emojiScore;
    }
    
    /**
     * 计算情感强度
     */
    private double calculateIntensity(List<SentenceAnalysis> sentences) {
        if (sentences.isEmpty()) {
            return 0.0;
        }
        
        // 计算得分的标准差作为强度指标
        double mean = sentences.stream()
                .mapToDouble(s -> s.sentimentScore)
                .average()
                .orElse(0.0);
        
        double variance = sentences.stream()
                .mapToDouble(s -> Math.pow(s.sentimentScore - mean, 2))
                .average()
                .orElse(0.0);
        
        return Math.sqrt(variance);
    }
    
    /**
     * 确定情感类型
     */
    private SentimentType determineSentimentType(double score, double intensity) {
        if (Math.abs(score) < 0.1) {
            return SentimentType.NEUTRAL;
        } else if (score > 0) {
            return intensity > 1.0 ? SentimentType.VERY_POSITIVE : SentimentType.POSITIVE;
        } else {
            return intensity > 1.0 ? SentimentType.VERY_NEGATIVE : SentimentType.NEGATIVE;
        }
    }
    
    /**
     * 批量分析文本情感
     */
    public Map<String, SentimentResult> batchAnalyze(Map<String, String> texts) {
        Map<String, SentimentResult> results = new HashMap<>();
        
        for (Map.Entry<String, String> entry : texts.entrySet()) {
            results.put(entry.getKey(), analyzeSentiment(entry.getValue()));
        }
        
        return results;
    }
    
    /**
     * 情感分析结果
     */
    public static class SentimentResult {
        public final double sentimentScore;
        public final SentimentType sentimentType;
        public final double intensity;
        public final List<SentenceAnalysis> sentences;
        
        public SentimentResult(double sentimentScore, SentimentType sentimentType, 
                             double intensity, List<SentenceAnalysis> sentences) {
            this.sentimentScore = sentimentScore;
            this.sentimentType = sentimentType;
            this.intensity = intensity;
            this.sentences = sentences;
        }
        
        public String getSentimentDescription() {
            return String.format("%s (%.2f, 强度: %.2f)", 
                    sentimentType.getDescription(), sentimentScore, intensity);
        }
        
        public List<String> getEmotionWords() {
            return sentences.stream()
                    .flatMap(s -> s.emotionWords.stream())
                    .distinct()
                    .collect(Collectors.toList());
        }
    }
    
    /**
     * 句子分析结果
     */
    public static class SentenceAnalysis {
        public final String sentence;
        public final double sentimentScore;
        public final List<String> emotionWords;
        
        public SentenceAnalysis(String sentence, double sentimentScore, List<String> emotionWords) {
            this.sentence = sentence;
            this.sentimentScore = sentimentScore;
            this.emotionWords = emotionWords;
        }
    }
    
    /**
     * 情感类型枚举
     */
    public enum SentimentType {
        VERY_POSITIVE("非常积极"),
        POSITIVE("积极"),
        NEUTRAL("中性"),
        NEGATIVE("消极"),
        VERY_NEGATIVE("非常消极");
        
        private final String description;
        
        SentimentType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
}