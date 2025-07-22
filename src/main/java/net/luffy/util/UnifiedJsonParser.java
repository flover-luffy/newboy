package net.luffy.util;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一JSON解析器
 * 整合Jackson和Hutool，提供高性能JSON解析功能
 * 兼容现有的JSONUtil接口，支持缓存和性能监控
 */
public class UnifiedJsonParser {
    
    private static volatile UnifiedJsonParser instance;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Object> parseCache;
    private final int maxCacheSize;
    
    // 性能统计
    private final AtomicLong totalParseCount = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong jacksonParseCount = new AtomicLong(0);
    private final AtomicLong hutoolParseCount = new AtomicLong(0);
    private final AtomicLong totalParseTime = new AtomicLong(0);
    
    private UnifiedJsonParser() {
        this.objectMapper = createOptimizedObjectMapper();
        this.maxCacheSize = 1000; // 缓存最大1000个解析结果
        this.parseCache = new ConcurrentHashMap<>(maxCacheSize);
    }
    
    /**
     * 获取单例实例
     */
    public static UnifiedJsonParser getInstance() {
        if (instance == null) {
            synchronized (UnifiedJsonParser.class) {
                if (instance == null) {
                    instance = new UnifiedJsonParser();
                }
            }
        }
        return instance;
    }
    
    /**
     * 创建优化的ObjectMapper
     */
    private ObjectMapper createOptimizedObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 配置Jackson以获得最佳性能
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
    
    /**
     * 解析JSON字符串为JSONObject（兼容JSONUtil.parseObj）
     * 优先使用Jackson，失败时回退到Hutool
     */
    public JSONObject parseObj(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return new JSONObject();
        }
        
        long startTime = System.nanoTime();
        totalParseCount.incrementAndGet();
        
        try {
            // 检查缓存
            String cacheKey = "obj_" + jsonStr.hashCode();
            Object cached = parseCache.get(cacheKey);
            if (cached instanceof JSONObject) {
                cacheHitCount.incrementAndGet();
                return (JSONObject) cached;
            }
            
            JSONObject result;
            
            // 优先使用Jackson解析
            try {
                JsonNode jsonNode = objectMapper.readTree(jsonStr);
                if (jsonNode.isObject()) {
                    result = convertJsonNodeToJSONObject(jsonNode);
                    jacksonParseCount.incrementAndGet();
                } else {
                    // 不是对象，回退到Hutool
                    result = JSONUtil.parseObj(jsonStr);
                    hutoolParseCount.incrementAndGet();
                }
            } catch (Exception e) {
                // Jackson解析失败，回退到Hutool
                result = JSONUtil.parseObj(jsonStr);
                hutoolParseCount.incrementAndGet();
            }
            
            // 缓存结果（如果缓存未满）
            if (parseCache.size() < maxCacheSize) {
                parseCache.put(cacheKey, result);
            }
            
            return result;
            
        } finally {
            long parseTime = System.nanoTime() - startTime;
            totalParseTime.addAndGet(parseTime);
        }
    }
    
    /**
     * 解析JSON字符串为JSONArray（兼容JSONUtil.parseArray）
     * 优先使用Jackson，失败时回退到Hutool
     */
    public JSONArray parseArray(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return new JSONArray();
        }
        
        long startTime = System.nanoTime();
        totalParseCount.incrementAndGet();
        
        try {
            // 检查缓存
            String cacheKey = "arr_" + jsonStr.hashCode();
            Object cached = parseCache.get(cacheKey);
            if (cached instanceof JSONArray) {
                cacheHitCount.incrementAndGet();
                return (JSONArray) cached;
            }
            
            JSONArray result;
            
            // 优先使用Jackson解析
            try {
                JsonNode jsonNode = objectMapper.readTree(jsonStr);
                if (jsonNode.isArray()) {
                    result = convertJsonNodeToJSONArray(jsonNode);
                    jacksonParseCount.incrementAndGet();
                } else {
                    // 不是数组，回退到Hutool
                    result = JSONUtil.parseArray(jsonStr);
                    hutoolParseCount.incrementAndGet();
                }
            } catch (Exception e) {
                // Jackson解析失败，回退到Hutool
                result = JSONUtil.parseArray(jsonStr);
                hutoolParseCount.incrementAndGet();
            }
            
            // 缓存结果（如果缓存未满）
            if (parseCache.size() < maxCacheSize) {
                parseCache.put(cacheKey, result);
            }
            
            return result;
            
        } finally {
            long parseTime = System.nanoTime() - startTime;
            totalParseTime.addAndGet(parseTime);
        }
    }
    
    /**
     * 将对象转换为JSON字符串
     */
    public String toJsonStr(Object obj) {
        if (obj == null) {
            return "null";
        }
        
        try {
            // 优先使用Jackson
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // 回退到Hutool
            return JSONUtil.toJsonStr(obj);
        }
    }
    
    /**
     * 将JsonNode转换为JSONObject
     */
    private JSONObject convertJsonNodeToJSONObject(JsonNode jsonNode) {
        JSONObject jsonObject = new JSONObject();
        
        if (jsonNode.isObject()) {
            ObjectNode objectNode = (ObjectNode) jsonNode;
            objectNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                jsonObject.put(key, convertJsonNodeToValue(value));
            });
        }
        
        return jsonObject;
    }
    
    /**
     * 将JsonNode转换为JSONArray
     */
    private JSONArray convertJsonNodeToJSONArray(JsonNode jsonNode) {
        JSONArray jsonArray = new JSONArray();
        
        if (jsonNode.isArray()) {
            ArrayNode arrayNode = (ArrayNode) jsonNode;
            for (JsonNode element : arrayNode) {
                jsonArray.add(convertJsonNodeToValue(element));
            }
        }
        
        return jsonArray;
    }
    
    /**
     * 将JsonNode转换为对应的Java值
     */
    private Object convertJsonNodeToValue(JsonNode jsonNode) {
        if (jsonNode.isNull()) {
            return null;
        } else if (jsonNode.isBoolean()) {
            return jsonNode.booleanValue();
        } else if (jsonNode.isInt()) {
            return jsonNode.intValue();
        } else if (jsonNode.isLong()) {
            return jsonNode.longValue();
        } else if (jsonNode.isDouble()) {
            return jsonNode.doubleValue();
        } else if (jsonNode.isTextual()) {
            return jsonNode.textValue();
        } else if (jsonNode.isObject()) {
            return convertJsonNodeToJSONObject(jsonNode);
        } else if (jsonNode.isArray()) {
            return convertJsonNodeToJSONArray(jsonNode);
        } else {
            return jsonNode.toString();
        }
    }
    
    /**
     * 清空缓存
     */
    public void clearCache() {
        parseCache.clear();
    }
    
    /**
     * 获取缓存大小
     */
    public int getCacheSize() {
        return parseCache.size();
    }
    
    /**
     * 获取性能统计信息
     */
    public String getPerformanceStats() {
        long totalParse = totalParseCount.get();
        long cacheHit = cacheHitCount.get();
        long jacksonParse = jacksonParseCount.get();
        long hutoolParse = hutoolParseCount.get();
        long avgParseTime = totalParse > 0 ? totalParseTime.get() / totalParse / 1000000 : 0; // 转换为毫秒
        
        double cacheHitRate = totalParse > 0 ? (double) cacheHit / totalParse * 100 : 0;
        double jacksonRate = totalParse > 0 ? (double) jacksonParse / totalParse * 100 : 0;
        
        return String.format(
            "JSON解析性能统计 - 总解析次数: %d, 缓存命中: %d (%.1f%%), Jackson解析: %d (%.1f%%), Hutool解析: %d, 平均解析时间: %dms, 缓存大小: %d",
            totalParse, cacheHit, cacheHitRate, jacksonParse, jacksonRate, hutoolParse, avgParseTime, parseCache.size()
        );
    }
    
    /**
     * 重置性能统计数据
     */
    public void resetStats() {
        totalParseCount.set(0);
        cacheHitCount.set(0);
        jacksonParseCount.set(0);
        hutoolParseCount.set(0);
        totalParseTime.set(0);
        clearCache();
    }
    
    /**
     * 获取原始ObjectMapper（用于特殊需求）
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
    
    // 静态便捷方法，兼容现有代码
    
    /**
     * 静态方法：解析JSON字符串为JSONObject
     */
    public static JSONObject parseObj(String jsonStr, boolean useUnified) {
        if (useUnified) {
            return getInstance().parseObj(jsonStr);
        } else {
            // 记录传统方法使用统计
            try {
                // 迁移助手已删除，不再记录传统方法使用
            } catch (Exception e) {
                // 忽略统计记录失败
            }
            return JSONUtil.parseObj(jsonStr);
        }
    }
    
    /**
     * 静态方法：解析JSON字符串为JSONArray
     */
    public static JSONArray parseArray(String jsonStr, boolean useUnified) {
        if (useUnified) {
            return getInstance().parseArray(jsonStr);
        } else {
            // 记录传统方法使用统计
            try {
                // 迁移助手已删除，不再记录传统方法使用
            } catch (Exception e) {
                // 忽略统计记录失败
            }
            return JSONUtil.parseArray(jsonStr);
        }
    }
    
    /**
     * 静态方法：将对象转换为JSON字符串
     */
    public static String toJsonStr(Object obj, boolean useUnified) {
        if (useUnified) {
            return getInstance().toJsonStr(obj);
        } else {
            // 记录传统方法使用统计
            try {
                // 迁移助手已删除，不再记录传统方法使用
            } catch (Exception e) {
                // 忽略统计记录失败
            }
            return JSONUtil.toJsonStr(obj);
        }
    }
}