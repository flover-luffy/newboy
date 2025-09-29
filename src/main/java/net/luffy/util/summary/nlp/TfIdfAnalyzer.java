package net.luffy.util.summary.nlp;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TF-IDF (Term Frequency-Inverse Document Frequency) 算法实现
 * 用于更准确的关键词提取和文档相似度计算
 * 
 * TF-IDF = TF(t,d) × IDF(t,D)
 * 其中：
 * - TF(t,d) = 词频：词t在文档d中出现的次数 / 文档d的总词数
 * - IDF(t,D) = 逆文档频率：log(文档总数 / 包含词t的文档数)
 * 
 * @author Luffy
 * @since 2024-01-20
 */
public class TfIdfAnalyzer {
    
    // 停用词集合
    private final Set<String> stopWords;
    
    // 文档集合
    private List<Document> documents;
    
    // 词汇表
    private Set<String> vocabulary;
    
    // IDF缓存
    private Map<String, Double> idfCache;
    
    public TfIdfAnalyzer(Set<String> stopWords) {
        this.stopWords = stopWords != null ? stopWords : new HashSet<>();
        this.documents = new ArrayList<>();
        this.vocabulary = new HashSet<>();
        this.idfCache = new HashMap<>();
    }
    
    /**
     * 添加文档到语料库
     */
    public void addDocument(String id, String content) {
        Document doc = new Document(id, content, stopWords);
        documents.add(doc);
        vocabulary.addAll(doc.getTerms());
        // 清空IDF缓存，需要重新计算
        idfCache.clear();
    }
    
    /**
     * 批量添加文档
     */
    public void addDocuments(Map<String, String> docs) {
        for (Map.Entry<String, String> entry : docs.entrySet()) {
            addDocument(entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * 计算指定文档的TF-IDF向量
     */
    public Map<String, Double> calculateTfIdf(String documentId) {
        Document doc = findDocument(documentId);
        if (doc == null) {
            return new HashMap<>();
        }
        
        Map<String, Double> tfIdfVector = new HashMap<>();
        
        for (String term : doc.getTerms()) {
            double tf = doc.getTermFrequency(term);
            double idf = getIdf(term);
            double tfIdf = tf * idf;
            
            if (tfIdf > 0) {
                tfIdfVector.put(term, tfIdf);
            }
        }
        
        return tfIdfVector;
    }
    
    /**
     * 获取所有文档的关键词（按TF-IDF值排序）
     */
    public List<KeywordScore> getTopKeywords(int topN) {
        Map<String, Double> globalTfIdf = new HashMap<>();
        
        // 计算所有文档的TF-IDF平均值
        for (Document doc : documents) {
            Map<String, Double> docTfIdf = calculateTfIdf(doc.getId());
            for (Map.Entry<String, Double> entry : docTfIdf.entrySet()) {
                globalTfIdf.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        
        // 计算平均值并排序
        return globalTfIdf.entrySet().stream()
                .map(entry -> new KeywordScore(entry.getKey(), entry.getValue() / documents.size()))
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topN)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取指定文档的关键词
     */
    public List<KeywordScore> getDocumentKeywords(String documentId, int topN) {
        Map<String, Double> tfIdfVector = calculateTfIdf(documentId);
        
        return tfIdfVector.entrySet().stream()
                .map(entry -> new KeywordScore(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topN)
                .collect(Collectors.toList());
    }
    
    /**
     * 计算两个文档的余弦相似度
     */
    public double calculateCosineSimilarity(String docId1, String docId2) {
        Map<String, Double> vector1 = calculateTfIdf(docId1);
        Map<String, Double> vector2 = calculateTfIdf(docId2);
        
        if (vector1.isEmpty() || vector2.isEmpty()) {
            return 0.0;
        }
        
        // 计算点积
        double dotProduct = 0.0;
        for (String term : vector1.keySet()) {
            if (vector2.containsKey(term)) {
                dotProduct += vector1.get(term) * vector2.get(term);
            }
        }
        
        // 计算向量长度
        double norm1 = Math.sqrt(vector1.values().stream().mapToDouble(v -> v * v).sum());
        double norm2 = Math.sqrt(vector2.values().stream().mapToDouble(v -> v * v).sum());
        
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        
        return dotProduct / (norm1 * norm2);
    }
    
    /**
     * 获取IDF值
     */
    private double getIdf(String term) {
        if (idfCache.containsKey(term)) {
            return idfCache.get(term);
        }
        
        long docCount = documents.stream()
                .mapToLong(doc -> doc.containsTerm(term) ? 1 : 0)
                .sum();
        
        double idf = docCount > 0 ? Math.log((double) documents.size() / docCount) : 0.0;
        idfCache.put(term, idf);
        
        return idf;
    }
    
    /**
     * 查找文档
     */
    private Document findDocument(String documentId) {
        return documents.stream()
                .filter(doc -> doc.getId().equals(documentId))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 清空语料库
     */
    public void clear() {
        documents.clear();
        vocabulary.clear();
        idfCache.clear();
    }
    
    /**
     * 获取语料库统计信息
     */
    public CorpusStats getCorpusStats() {
        return new CorpusStats(documents.size(), vocabulary.size(), 
                documents.stream().mapToInt(doc -> doc.getTerms().size()).sum());
    }
    
    /**
     * 文档类
     */
    private static class Document {
        private final String id;
        private final String content;
        private final Map<String, Integer> termCount;
        private final Set<String> terms;
        private final int totalTerms;
        
        public Document(String id, String content, Set<String> stopWords) {
            this.id = id;
            this.content = content;
            this.termCount = new HashMap<>();
            this.terms = new HashSet<>();
            
            // 简单的中文分词和词频统计
            String[] words = content.toLowerCase()
                    .replaceAll("[\\p{Punct}\\s]+", " ")
                    .split("\\s+");
            
            for (String word : words) {
                word = word.trim();
                if (word.length() >= 2 && !stopWords.contains(word) && isValidTerm(word)) {
                    termCount.merge(word, 1, Integer::sum);
                    terms.add(word);
                }
            }
            
            this.totalTerms = termCount.values().stream().mapToInt(Integer::intValue).sum();
        }
        
        private boolean isValidTerm(String term) {
            // 检查是否为有效词汇（中文、英文、数字）
            return term.matches("[\\u4e00-\\u9fa5a-zA-Z0-9]+");
        }
        
        public String getId() {
            return id;
        }
        
        public Set<String> getTerms() {
            return terms;
        }
        
        public boolean containsTerm(String term) {
            return termCount.containsKey(term);
        }
        
        public double getTermFrequency(String term) {
            if (totalTerms == 0) {
                return 0.0;
            }
            return (double) termCount.getOrDefault(term, 0) / totalTerms;
        }
        
        public int getTermCount(String term) {
            return termCount.getOrDefault(term, 0);
        }
    }
    
    /**
     * 关键词得分类
     */
    public static class KeywordScore {
        public final String keyword;
        public final double score;
        
        public KeywordScore(String keyword, double score) {
            this.keyword = keyword;
            this.score = score;
        }
        
        @Override
        public String toString() {
            return String.format("%s: %.4f", keyword, score);
        }
    }
    
    /**
     * 语料库统计信息
     */
    public static class CorpusStats {
        public final int documentCount;
        public final int vocabularySize;
        public final int totalTerms;
        
        public CorpusStats(int documentCount, int vocabularySize, int totalTerms) {
            this.documentCount = documentCount;
            this.vocabularySize = vocabularySize;
            this.totalTerms = totalTerms;
        }
        
        @Override
        public String toString() {
            return String.format("Documents: %d, Vocabulary: %d, Total Terms: %d", 
                    documentCount, vocabularySize, totalTerms);
        }
    }
}