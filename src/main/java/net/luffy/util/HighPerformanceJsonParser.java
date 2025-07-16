package net.luffy.util;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * 高性能JSON解析器
 * 结合Jackson的高性能和Hutool的易用性
 */
public class HighPerformanceJsonParser {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    
    // 解析结果缓存
    private static final Map<String, Object> PARSE_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 500;
    
    /**
     * 高性能JSON对象解析
     * 对于大型JSON使用Jackson，小型JSON使用Hutool
     * @param jsonStr JSON字符串
     * @return JSONObject
     */
    public static JSONObject parseObject(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        
        // 检查缓存
        Object cached = PARSE_CACHE.get(jsonStr);
        if (cached instanceof JSONObject) {
            return (JSONObject) cached;
        }
        
        JSONObject result;
        
        // 根据JSON大小选择解析策略
        if (jsonStr.length() > 1024) { // 大于1KB使用Jackson
            result = parseWithJackson(jsonStr);
        } else { // 小JSON使用Hutool
            result = JSONUtil.parseObj(jsonStr);
        }
        
        // 缓存结果
        if (PARSE_CACHE.size() < MAX_CACHE_SIZE && result != null) {
            PARSE_CACHE.put(jsonStr, result);
        }
        
        return result;
    }
    
    /**
     * 使用Jackson解析JSON并转换为Hutool JSONObject
     * @param jsonStr JSON字符串
     * @return JSONObject
     */
    private static JSONObject parseWithJackson(String jsonStr) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(jsonStr);
            return convertJsonNodeToJSONObject(node);
        } catch (IOException e) {
            // Jackson解析失败，回退到Hutool
            return JSONUtil.parseObj(jsonStr);
        }
    }
    
    /**
     * 将Jackson JsonNode转换为Hutool JSONObject
     * @param node JsonNode
     * @return JSONObject
     */
    private static JSONObject convertJsonNodeToJSONObject(JsonNode node) {
        JSONObject result = new JSONObject();
        
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            
            if (value.isObject()) {
                result.set(key, convertJsonNodeToJSONObject(value));
            } else if (value.isArray()) {
                result.set(key, convertJsonNodeToJSONArray(value));
            } else if (value.isTextual()) {
                result.set(key, value.asText());
            } else if (value.isNumber()) {
                if (value.isInt()) {
                    result.set(key, value.asInt());
                } else if (value.isLong()) {
                    result.set(key, value.asLong());
                } else {
                    result.set(key, value.asDouble());
                }
            } else if (value.isBoolean()) {
                result.set(key, value.asBoolean());
            } else if (value.isNull()) {
                result.set(key, null);
            }
        });
        
        return result;
    }
    
    /**
     * 将Jackson JsonNode转换为Hutool JSONArray
     * @param node JsonNode
     * @return JSONArray
     */
    private static JSONArray convertJsonNodeToJSONArray(JsonNode node) {
        JSONArray result = new JSONArray();
        
        for (JsonNode item : node) {
            if (item.isObject()) {
                result.add(convertJsonNodeToJSONObject(item));
            } else if (item.isArray()) {
                result.add(convertJsonNodeToJSONArray(item));
            } else if (item.isTextual()) {
                result.add(item.asText());
            } else if (item.isNumber()) {
                if (item.isInt()) {
                    result.add(item.asInt());
                } else if (item.isLong()) {
                    result.add(item.asLong());
                } else {
                    result.add(item.asDouble());
                }
            } else if (item.isBoolean()) {
                result.add(item.asBoolean());
            } else if (item.isNull()) {
                result.add(null);
            }
        }
        
        return result;
    }
    
    /**
     * 流式解析大型JSON，提取特定字段
     * 适用于只需要部分字段的场景
     * @param jsonStr JSON字符串
     * @param fieldNames 需要提取的字段名
     * @return 字段值映射
     */
    public static Map<String, Object> streamParseFields(String jsonStr, String... fieldNames) {
        Map<String, Object> result = new ConcurrentHashMap<>();
        
        try (JsonParser parser = JSON_FACTORY.createParser(jsonStr)) {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.getCurrentToken() == JsonToken.FIELD_NAME) {
                    String fieldName = parser.getCurrentName();
                    
                    // 检查是否是我们需要的字段
                    for (String targetField : fieldNames) {
                        if (targetField.equals(fieldName)) {
                            parser.nextToken(); // 移动到值
                            Object value = extractValue(parser);
                            result.put(fieldName, value);
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            // 流式解析失败，回退到普通解析
            JSONObject obj = JSONUtil.parseObj(jsonStr);
            for (String field : fieldNames) {
                if (obj.containsKey(field)) {
                    result.put(field, obj.get(field));
                }
            }
        }
        
        return result;
    }
    
    /**
     * 从JsonParser提取值
     * @param parser JsonParser
     * @return 提取的值
     * @throws IOException IO异常
     */
    private static Object extractValue(JsonParser parser) throws IOException {
        JsonToken token = parser.getCurrentToken();
        
        switch (token) {
            case VALUE_STRING:
                return parser.getValueAsString();
            case VALUE_NUMBER_INT:
                return parser.getValueAsInt();
            case VALUE_NUMBER_FLOAT:
                return parser.getValueAsDouble();
            case VALUE_TRUE:
            case VALUE_FALSE:
                return parser.getValueAsBoolean();
            case VALUE_NULL:
                return null;
            case START_OBJECT:
                return OBJECT_MAPPER.readTree(parser);
            case START_ARRAY:
                return OBJECT_MAPPER.readTree(parser);
            default:
                return parser.getValueAsString();
        }
    }
    
    /**
     * 批量解析JSON字符串
     * 使用并行处理提高性能
     * @param jsonStrings JSON字符串列表
     * @return 解析结果列表
     */
    public static List<JSONObject> batchParse(List<String> jsonStrings) {
        if (jsonStrings == null || jsonStrings.isEmpty()) {
            return new ArrayList<>();
        }
        
        return jsonStrings.parallelStream()
                .map(HighPerformanceJsonParser::parseObject)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }
    
    /**
     * 快速检查JSON字符串是否包含特定字段和值
     * 避免完整解析
     * @param jsonStr JSON字符串
     * @param fieldName 字段名
     * @param expectedValue 期望值
     * @return 是否匹配
     */
    public static boolean quickCheck(String jsonStr, String fieldName, String expectedValue) {
        if (jsonStr == null || fieldName == null || expectedValue == null) {
            return false;
        }
        
        // 简单的字符串匹配检查
        String pattern = "\"" + fieldName + "\"\\s*:\\s*\"" + expectedValue + "\"";
        return jsonStr.matches(".*" + pattern + ".*");
    }
    
    /**
     * 获取解析器性能统计
     * @return 统计信息
     */
    public static String getPerformanceStats() {
        return String.format("高性能JSON解析器 - 缓存大小: %d", PARSE_CACHE.size());
    }
    
    /**
     * 清理缓存
     */
    public static void clearCache() {
        PARSE_CACHE.clear();
    }
    
    /**
     * 预热解析器
     * 在应用启动时调用，提高首次解析性能
     */
    public static void warmUp() {
        // 预热Jackson
        try {
            String testJson = "{\"test\":\"value\",\"number\":123}";
            OBJECT_MAPPER.readTree(testJson);
        } catch (IOException e) {
            // 忽略预热错误
        }
        
        // 预热Hutool
        JSONUtil.parseObj("{\"warmup\":true}");
    }
}