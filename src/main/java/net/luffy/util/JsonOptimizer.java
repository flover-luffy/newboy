package net.luffy.util;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.util.UnifiedJsonParser;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;

/**
 * JSON解析优化工具类
 * 提供缓存、快速字段提取等优化功能
 */
public class JsonOptimizer {
    
    // JSON解析结果缓存
    private static final ConcurrentHashMap<String, JSONObject> JSON_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, JSONArray> ARRAY_CACHE = new ConcurrentHashMap<>();
    
    // 缓存大小限制
    private static final int MAX_CACHE_SIZE = 1000;
    
    // 预编译的正则表达式，用于快速字段提取
    private static final Pattern NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TITLE_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TEXT_PATTERN = Pattern.compile("\"text\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern STATUS_PATTERN = Pattern.compile("\"status\"\\s*:\\s*(\\d+)");
    private static final Pattern MSG_PATTERN = Pattern.compile("\"msg\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ERROR_PATTERN = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    
    /**
     * 带缓存的JSON对象解析
     * @param jsonStr JSON字符串
     * @return JSONObject
     */
    public static JSONObject parseObjectWithCache(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        
        // 检查缓存
        JSONObject cached = JSON_CACHE.get(jsonStr);
        if (cached != null) {
            return cached;
        }
        
        // 解析并缓存
        JSONObject result = UnifiedJsonParser.getInstance().parseObj(jsonStr);
        if (JSON_CACHE.size() < MAX_CACHE_SIZE) {
            JSON_CACHE.put(jsonStr, result);
        }
        
        return result;
    }
    
    /**
     * 带缓存的JSON数组解析
     * @param jsonStr JSON字符串
     * @return JSONArray
     */
    public static JSONArray parseArrayWithCache(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return null;
        }
        
        // 检查缓存
        JSONArray cached = ARRAY_CACHE.get(jsonStr);
        if (cached != null) {
            return cached;
        }
        
        // 解析并缓存
        JSONArray result = UnifiedJsonParser.getInstance().parseArray(jsonStr);
        if (ARRAY_CACHE.size() < MAX_CACHE_SIZE) {
            ARRAY_CACHE.put(jsonStr, result);
        }
        
        return result;
    }
    
    /**
     * 快速提取JSON字符串中的name字段
     * 避免完整JSON解析，适用于简单字段提取
     * @param jsonStr JSON字符串
     * @return name字段值
     */
    public static String fastExtractName(String jsonStr) {
        if (jsonStr == null) return null;
        
        Matcher matcher = NAME_PATTERN.matcher(jsonStr);
        return matcher.find() ? matcher.group(1) : null;
    }
    
    /**
     * 快速提取JSON字符串中的title字段
     * @param jsonStr JSON字符串
     * @return title字段值
     */
    public static String fastExtractTitle(String jsonStr) {
        if (jsonStr == null) return null;
        
        Matcher matcher = TITLE_PATTERN.matcher(jsonStr);
        return matcher.find() ? matcher.group(1) : null;
    }
    
    /**
     * 快速提取JSON字符串中的text字段
     * @param jsonStr JSON字符串
     * @return text字段值
     */
    public static String fastExtractText(String jsonStr) {
        if (jsonStr == null) return null;
        
        Matcher matcher = TEXT_PATTERN.matcher(jsonStr);
        return matcher.find() ? matcher.group(1) : null;
    }
    
    /**
     * 快速提取JSON字符串中的status字段
     * @param jsonStr JSON字符串
     * @return status字段值
     */
    public static Integer fastExtractStatus(String jsonStr) {
        if (jsonStr == null) return null;
        
        Matcher matcher = STATUS_PATTERN.matcher(jsonStr);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * 快速提取JSON字符串中的msg字段
     * @param jsonStr JSON字符串
     * @return msg字段值
     */
    public static String fastExtractMsg(String jsonStr) {
        if (jsonStr == null) return null;
        
        Matcher matcher = MSG_PATTERN.matcher(jsonStr);
        return matcher.find() ? matcher.group(1) : null;
    }
    
    /**
     * 快速提取JSON字符串中的error字段
     * @param jsonStr JSON字符串
     * @return error字段值
     */
    public static String fastExtractError(String jsonStr) {
        if (jsonStr == null) return null;
        
        Matcher matcher = ERROR_PATTERN.matcher(jsonStr);
        return matcher.find() ? matcher.group(1) : null;
    }
    
    /**
     * 优化的表情名称解析方法
     * 使用预编译正则表达式，性能比字符串分割更好
     * @param jsonBody JSON格式的消息体
     * @return 表情名称
     */
    public static String parseEmotionNameOptimized(String jsonBody) {
        if (jsonBody == null || jsonBody.isEmpty()) {
            return "[表情]";
        }
        
        try {
            // 优先尝试name字段
            String name = fastExtractName(jsonBody);
            if (name != null && !name.isEmpty()) {
                return "[" + name + "]";
            }
            
            // 尝试title字段
            String title = fastExtractTitle(jsonBody);
            if (title != null && !title.isEmpty()) {
                return "[" + title + "]";
            }
            
            // 最后尝试text字段
            String text = fastExtractText(jsonBody);
            if (text != null && !text.isEmpty()) {
                return "[" + text + "]";
            }
            
        } catch (Exception e) {
            // 解析失败，返回默认值
        }
        
        return "[表情]";
    }
    
    /**
     * 快速检查API响应状态
     * 避免完整JSON解析，仅检查关键字段
     * @param responseStr 响应字符串
     * @return 是否成功
     */
    public static boolean isApiResponseSuccess(String responseStr) {
        if (responseStr == null || responseStr.isEmpty()) {
            return false;
        }
        
        // 快速检查msg字段
        String msg = fastExtractMsg(responseStr);
        if (msg != null && "success".equals(msg)) {
            return true;
        }
        
        // 检查status字段
        Integer status = fastExtractStatus(responseStr);
        if (status != null && status == 200) {
            return true;
        }
        
        // 检查error字段
        String error = fastExtractError(responseStr);
        return error != null && "0".equals(error);
    }
    
    /**
     * 快速检查JSON字符串是否包含指定字段和值
     */
    public static boolean containsFieldValue(String json, String fieldName, String expectedValue) {
        if (json == null || fieldName == null || expectedValue == null) {
            return false;
        }
        
        try {
            String pattern = "\"" + fieldName + "\"\\s*:\\s*\"" + expectedValue + "\"";
            return Pattern.compile(pattern).matcher(json).find();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 批量解析JSON对象
     * @param jsonList JSON字符串列表
     * @return 解析结果列表
     */
    public static List<JSONObject> batchParseObjects(List<String> jsonList) {
        if (jsonList == null || jsonList.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<JSONObject> results = new ArrayList<>(jsonList.size());
        for (String json : jsonList) {
            try {
                JSONObject obj = parseObjectWithCache(json);
                if (obj != null) {
                    results.add(obj);
                }
            } catch (Exception e) {
                // 跳过解析失败的项
            }
        }
        
        return results;
    }
    
    /**
     * 清理缓存
     */
    public static void clearCache() {
        JSON_CACHE.clear();
        ARRAY_CACHE.clear();
    }
    
    /**
     * 获取缓存统计信息
     * @return 缓存统计字符串
     */
    public static String getCacheStats() {
        return String.format("JSON缓存: %d个对象, %d个数组", 
                           JSON_CACHE.size(), ARRAY_CACHE.size());
    }
}