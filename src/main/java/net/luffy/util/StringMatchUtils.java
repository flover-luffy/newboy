package net.luffy.util;

import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字符串匹配优化工具类
 * 提供高效的字符串匹配方法，避免频繁的contains()调用
 * 
 * @author Luffy
 * @version 1.0
 */
public class StringMatchUtils {
    
    // URL类型匹配模式
    private static final Set<String> IMAGE_PATTERNS = Set.of(
        "/image/", "/img/", "/cover/", "cover", "thumb", 
        "emotion", "express", "emoji"
    );
    
    private static final Set<String> VIDEO_PATTERNS = Set.of(
        "/video/", "/mp4/"
    );
    
    private static final Set<String> AUDIO_PATTERNS = Set.of(
        "/audio/", "/voice/"
    );
    
    // 错误消息匹配模式
    private static final Set<String> RETRYABLE_ERROR_PATTERNS = Set.of(
        "rich media transfer failed",
        "send_group_msg",
        "ActionFailedException", 
        "EventChecker Failed",
        "timeout",
        "connection",
        "network"
    );
    
    // HTTP状态码错误匹配
    private static final Set<String> CLIENT_ERROR_PATTERNS = Set.of(
        "400", "401", "403", "404",
        "client error", "bad request", "unauthorized", 
        "forbidden", "not found"
    );
    
    private static final Set<String> SERVER_ERROR_PATTERNS = Set.of(
        "500", "502", "503", "504",
        "server error", "timeout", "connection", 
        "network", "socket"
    );
    
    // 文件扩展名匹配
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"
    );
    
    // 预编译的正则表达式缓存
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();
    
    /**
     * 检查URL是否为图片类型
     * @param url URL字符串
     * @return 是否为图片URL
     */
    public static boolean isImageUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        String lowerUrl = url.toLowerCase();
        return IMAGE_PATTERNS.stream().anyMatch(lowerUrl::contains) ||
               IMAGE_EXTENSIONS.stream().anyMatch(lowerUrl::contains);
    }
    
    /**
     * 检查URL是否为视频类型
     * @param url URL字符串
     * @return 是否为视频URL
     */
    public static boolean isVideoUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        String lowerUrl = url.toLowerCase();
        return VIDEO_PATTERNS.stream().anyMatch(lowerUrl::contains);
    }
    
    /**
     * 检查URL是否为音频类型
     * @param url URL字符串
     * @return 是否为音频URL
     */
    public static boolean isAudioUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        
        String lowerUrl = url.toLowerCase();
        return AUDIO_PATTERNS.stream().anyMatch(lowerUrl::contains);
    }
    
    /**
     * 检查错误消息是否为可重试错误
     * @param errorMessage 错误消息
     * @return 是否为可重试错误
     */
    public static boolean isRetryableError(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return false;
        }
        
        String lowerMessage = errorMessage.toLowerCase();
        return RETRYABLE_ERROR_PATTERNS.stream().anyMatch(lowerMessage::contains);
    }
    
    /**
     * 检查错误消息是否为客户端错误
     * @param errorMessage 错误消息
     * @return 是否为客户端错误
     */
    public static boolean isClientError(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return false;
        }
        
        String lowerMessage = errorMessage.toLowerCase();
        return CLIENT_ERROR_PATTERNS.stream().anyMatch(lowerMessage::contains);
    }
    
    /**
     * 检查错误消息是否为服务器错误
     * @param errorMessage 错误消息
     * @return 是否为服务器错误
     */
    public static boolean isServerError(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return false;
        }
        
        String lowerMessage = errorMessage.toLowerCase();
        return SERVER_ERROR_PATTERNS.stream().anyMatch(lowerMessage::contains);
    }
    
    /**
     * 检查响应内容是否为HTML页面
     * @param content 响应内容
     * @return 是否为HTML页面
     */
    public static boolean isHtmlContent(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        
        String trimmed = content.trim();
        String lower = trimmed.toLowerCase();
        
        return lower.startsWith("<html>") || 
               lower.startsWith("<!doctype") || 
               lower.contains("login") || 
               lower.contains("登录");
    }
    
    /**
     * 检查是否为有效的JSON数组格式
     * @param json JSON字符串
     * @return 是否为有效的JSON数组格式
     */
    public static boolean isValidJsonArray(String json) {
        if (json == null) {
            return false;
        }
        String trimmed = json.trim();
        return !trimmed.isEmpty() && trimmed.startsWith("[") && trimmed.endsWith("]");
    }
    
    /**
     * 检查字符串是否包含任意一个模式
     * @param text 待检查的文本
     * @param patterns 模式集合
     * @return 是否包含任意模式
     */
    public static boolean containsAny(String text, Set<String> patterns) {
        if (text == null || text.isEmpty() || patterns == null || patterns.isEmpty()) {
            return false;
        }
        
        String lowerText = text.toLowerCase();
        return patterns.stream().anyMatch(lowerText::contains);
    }
    
    /**
     * 使用正则表达式匹配（带缓存）
     * @param text 待匹配文本
     * @param regex 正则表达式
     * @return 是否匹配
     */
    public static boolean matchesRegex(String text, String regex) {
        if (text == null || regex == null) {
            return false;
        }
        
        Pattern pattern = PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
        return pattern.matcher(text).find();
    }
    
    /**
     * 获取文件扩展名
     * @param filename 文件名或URL
     * @return 文件扩展名（包含点号），如果没有扩展名则返回空字符串
     */
    public static String getFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        
        int lastDotIndex = filename.lastIndexOf('.');
        int lastSlashIndex = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        
        if (lastDotIndex > lastSlashIndex && lastDotIndex < filename.length() - 1) {
            return filename.substring(lastDotIndex);
        }
        
        return "";
    }
    
    /**
     * 检查文件扩展名是否为图片格式
     * @param extension 文件扩展名
     * @return 是否为图片格式
     */
    public static boolean isImageExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return false;
        }
        
        return IMAGE_EXTENSIONS.contains(extension.toLowerCase());
    }
    
    /**
     * 清理模式缓存（用于内存管理）
     */
    public static void clearPatternCache() {
        PATTERN_CACHE.clear();
    }
    
    /**
     * 获取缓存统计信息
     * @return 缓存大小
     */
    public static int getPatternCacheSize() {
        return PATTERN_CACHE.size();
    }
}