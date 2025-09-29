package net.luffy.service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.luffy.util.UnifiedLogger;

/**
 * 微博API Cookie管理器
 * 负责动态获取和管理微博API所需的Cookie
 */
public class WeiboApiCookieManager {
    
    // 使用System.out替代日志记录
    
    // 微博相关URL
    private static final String WEIBO_HOMEPAGE = "https://m.weibo.cn/";
    private static final String WEIBO_API_BASE = "https://m.weibo.cn/api/";
    
    // Cookie存储
    private final Map<String, String> cookieStore = new ConcurrentHashMap<>();
    private LocalDateTime lastUpdateTime;
    
    // Cookie有效期（分钟）
    private static final int COOKIE_VALIDITY_MINUTES = 30;
    
    // 标记是否为首次初始化
    private boolean isFirstInitialization = true;
    
    // 单例实例
    private static volatile WeiboApiCookieManager instance;
    
    private WeiboApiCookieManager() {
        // 私有构造函数
    }
    
    /**
     * 获取单例实例
     */
    public static WeiboApiCookieManager getInstance() {
        if (instance == null) {
            synchronized (WeiboApiCookieManager.class) {
                if (instance == null) {
                    instance = new WeiboApiCookieManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 获取有效的Cookie字符串
     * 如果Cookie过期或不存在，会自动刷新
     */
    public String getValidCookies() {
        try {
            if (needsRefresh()) {
                refreshCookies();
            }
            return formatCookiesForRequest();
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("WeiboApiCookieManager", 
                "获取Cookie失败: " + e.getMessage(), e);
            return getDefaultCookies();
        }
    }
    
    /**
     * 检查是否需要刷新Cookie
     */
    private boolean needsRefresh() {
        if (cookieStore.isEmpty() || lastUpdateTime == null) {
            return true;
        }
        
        LocalDateTime expireTime = lastUpdateTime.plusMinutes(COOKIE_VALIDITY_MINUTES);
        return LocalDateTime.now().isAfter(expireTime);
    }
    
    /**
     * 刷新Cookie
     */
    public synchronized void refreshCookies() {
        try {
            // 只在首次初始化时显示开始信息
            if (isFirstInitialization) {
                System.out.println("[INFO] 开始获取微博Cookie...");
            }
            
            // 清空现有Cookie
            cookieStore.clear();
            
            // 访问微博首页获取Cookie
            Map<String, String> homepageCookies = getCookiesFromUrl(WEIBO_HOMEPAGE);
            cookieStore.putAll(homepageCookies);
            
            // 更新时间戳
            lastUpdateTime = LocalDateTime.now();
            
            // 只在首次初始化时显示成功信息
            if (isFirstInitialization) {
                System.out.println("[INFO] 微博Cookie获取成功，获取到" + cookieStore.size() + "个Cookie");
                isFirstInitialization = false;
            }
            
        } catch (Exception e) {
            UnifiedLogger.getInstance().error("WeiboApiCookieManager", 
                "刷新Cookie失败: " + e.getMessage(), e);
            // 如果刷新失败，使用默认Cookie
            setDefaultCookies();
        }
    }
    
    /**
     * 从指定URL获取Cookie
     */
    private Map<String, String> getCookiesFromUrl(String urlString) throws IOException {
        Map<String, String> cookies = new HashMap<>();
        
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            // 设置请求头，模拟真实浏览器
            setRequestHeaders(connection);
            
            // 设置不自动重定向
            connection.setInstanceFollowRedirects(false);
            
            int responseCode = connection.getResponseCode();
            
            // 获取Set-Cookie头
            Map<String, List<String>> headers = connection.getHeaderFields();
            List<String> setCookieHeaders = headers.get("Set-Cookie");
            
            if (setCookieHeaders != null) {
                for (String setCookieHeader : setCookieHeaders) {
                    parseCookieHeader(setCookieHeader, cookies);
                }
            }
            
            // 处理重定向
            String location = connection.getHeaderField("Location");
            if (location != null && (responseCode == 301 || responseCode == 302)) {
                Map<String, String> redirectCookies = getCookiesFromUrl(location);
                cookies.putAll(redirectCookies);
            }
            
        } finally {
            connection.disconnect();
        }
        
        return cookies;
    }
    
    /**
     * 设置请求头，模拟真实浏览器
     */
    private void setRequestHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("User-Agent", 
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        connection.setRequestProperty("Accept", 
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        // 移除受限制的请求头 Accept-Encoding 和 Connection
        // connection.setRequestProperty("Accept-Encoding", "gzip, deflate, br");
        // connection.setRequestProperty("Connection", "keep-alive");
        connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
        connection.setRequestProperty("Cache-Control", "max-age=0");
    }
    
    /**
     * 解析Cookie头
     */
    private void parseCookieHeader(String setCookieHeader, Map<String, String> cookies) {
        if (setCookieHeader == null || setCookieHeader.trim().isEmpty()) {
            return;
        }
        
        // 分割Cookie属性
        String[] parts = setCookieHeader.split(";");
        if (parts.length > 0) {
            String[] nameValue = parts[0].trim().split("=", 2);
            if (nameValue.length == 2) {
                String name = nameValue[0].trim();
                String value = nameValue[1].trim();
                
                // 只保存有效的Cookie名称
                if (isValidCookieName(name)) {
                    cookies.put(name, value);
                }
            }
        }
    }
    
    /**
     * 检查Cookie名称是否有效
     */
    private boolean isValidCookieName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        
        // 微博相关的重要Cookie名称
        Set<String> importantCookies = Set.of(
            "_T_WM", "SUB", "SUBP", "SSOLoginState", 
            "XSRF-TOKEN", "WEIBOCN_FROM", "mweibo_short_token",
            "M_WEIBOCN_PARAMS", "MLOGIN", "X-CSRF-TOKEN"
        );
        
        return importantCookies.contains(name) || name.startsWith("_") || name.startsWith("weibo");
    }
    
    /**
     * 将Cookie格式化为请求头格式
     */
    private String formatCookiesForRequest() {
        if (cookieStore.isEmpty()) {
            return getDefaultCookies();
        }
        
        StringBuilder sb = new StringBuilder();
        cookieStore.forEach((name, value) -> {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(name).append("=").append(value);
        });
        
        return sb.toString();
    }
    
    /**
     * 获取默认Cookie（备用方案）
     */
    private String getDefaultCookies() {
        return "_T_WM=placeholder; SUB=placeholder; SUBP=placeholder";
    }
    
    /**
     * 设置默认Cookie
     */
    private void setDefaultCookies() {
        cookieStore.clear();
        cookieStore.put("_T_WM", "placeholder");
        cookieStore.put("SUB", "placeholder");
        cookieStore.put("SUBP", "placeholder");
        lastUpdateTime = LocalDateTime.now();
        
        System.out.println("[WARN] 使用默认Cookie作为备用方案");
    }
    
    /**
     * 手动设置Cookie
     */
    public void setCookies(Map<String, String> cookies) {
        cookieStore.clear();
        cookieStore.putAll(cookies);
        lastUpdateTime = LocalDateTime.now();
        
        System.out.println("[INFO] 手动设置Cookie完成，共" + cookies.size() + "个");
    }
    
    /**
     * 获取Cookie状态信息
     */
    public String getCookieStatus() {
        StringBuilder status = new StringBuilder();
        status.append("Cookie状态:\n");
        status.append("- Cookie数量: ").append(cookieStore.size()).append("\n");
        status.append("- 最后更新: ").append(
            lastUpdateTime != null ? 
            lastUpdateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : 
            "未更新"
        ).append("\n");
        status.append("- 是否需要刷新: ").append(needsRefresh() ? "是" : "否").append("\n");
        status.append("- Cookie列表: ").append(String.join(", ", cookieStore.keySet()));
        
        return status.toString();
    }
    
    /**
     * 清空Cookie
     */
    public void clearCookies() {
        cookieStore.clear();
        lastUpdateTime = null;
        System.out.println("[INFO] Cookie已清空");
    }
}