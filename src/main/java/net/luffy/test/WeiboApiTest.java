package net.luffy.test;

import cn.hutool.json.JSONObject;
import net.luffy.util.UnifiedHttpClient;
import net.luffy.util.UnifiedJsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * 微博API测试类
 * 用于测试HTTP 432错误修复后的功能
 */
public class WeiboApiTest {
    
    private static final String API_BASE = "https://m.weibo.cn/api/container/getIndex";
    private static final UnifiedHttpClient httpClient = UnifiedHttpClient.getInstance();
    private static final UnifiedJsonParser jsonParser = UnifiedJsonParser.getInstance();
    
    public static void main(String[] args) {
        System.out.println("=== 微博API测试开始 ===");
        
        String testUid = "1404376560"; // 使用官方API文档中的示例UID
        
        try {
            // 测试1: 获取用户信息
            System.out.println("\n1. 测试获取用户信息 (UID: " + testUid + ")");
            JSONObject userInfo = requestWeiboInfo(testUid);
            if (userInfo != null) {
                System.out.println("✓ 用户信息获取成功");
                System.out.println("响应数据: " + userInfo.toString());
            } else {
                System.out.println("✗ 用户信息获取失败");
            }
            
            // 测试2: 获取用户微博lfid
            System.out.println("\n2. 测试获取用户微博lfid");
            String lfid = getUserWeiboLfid(testUid);
            if (lfid != null && !lfid.isEmpty()) {
                System.out.println("✓ 用户lfid获取成功: " + lfid);
                
                // 测试3: 获取微博容器内容
                System.out.println("\n3. 测试获取微博容器内容");
                JSONObject containerData = requestWeiboContainer(lfid);
                if (containerData != null) {
                    System.out.println("✓ 微博容器数据获取成功");
                    System.out.println("容器状态: " + containerData.getInt("ok", -1));
                    
                    JSONObject data = containerData.getJSONObject("data");
                    if (data != null && data.containsKey("cards")) {
                        System.out.println("✓ 微博卡片数据存在");
                    } else {
                        System.out.println("✗ 微博卡片数据不存在");
                    }
                } else {
                    System.out.println("✗ 微博容器数据获取失败");
                }
            } else {
                System.out.println("✗ 用户lfid获取失败");
            }
            
            // 测试4: 获取用户昵称
            System.out.println("\n4. 测试获取用户昵称");
            String nickname = getUserNickname(testUid);
            if (nickname != null && !nickname.isEmpty()) {
                System.out.println("✓ 用户昵称获取成功: " + nickname);
            } else {
                System.out.println("✗ 用户昵称获取失败");
            }
            
        } catch (Exception e) {
            System.err.println("\n❌ 测试过程中发生异常: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n=== 微博API测试结束 ===");
    }
    
    /**
     * 获取微博用户信息
     */
    private static JSONObject requestWeiboInfo(String uid) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("type", "uid");
            params.put("value", uid);
            
            String url = buildUrl(API_BASE, params);
            Map<String, String> headers = getDefaultHeaders();
            
            String response = httpClient.get(url, headers);
            return jsonParser.parseObj(response);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("432")) {
                System.err.println("HTTP 432错误: 可能是请求频率过高或认证问题");
                return null;
            }
            throw new RuntimeException("获取用户信息失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户微博lfid
     */
    private static String getUserWeiboLfid(String uid) {
        try {
            JSONObject userInfo = requestWeiboInfo(uid);
            if (userInfo != null && userInfo.getInt("ok", 0) == 1) {
                JSONObject data = userInfo.getJSONObject("data");
                if (data != null) {
                    JSONObject userInfoData = data.getJSONObject("userInfo");
                    if (userInfoData != null) {
                        return "107603" + uid;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            System.err.println("获取用户lfid失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取微博容器内容
     */
    private static JSONObject requestWeiboContainer(String lfid) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("containerid", lfid);
            
            String url = buildUrl(API_BASE, params);
            Map<String, String> headers = getDefaultHeaders();
            
            String response = httpClient.get(url, headers);
            return jsonParser.parseObj(response);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("432")) {
                System.err.println("HTTP 432错误: 可能是请求频率过高或认证问题");
                return null;
            }
            throw new RuntimeException("获取微博容器失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取用户昵称
     */
    private static String getUserNickname(String uid) {
        try {
            JSONObject userInfo = requestWeiboInfo(uid);
            if (userInfo != null && userInfo.getInt("ok", 0) == 1) {
                JSONObject data = userInfo.getJSONObject("data");
                if (data != null) {
                    JSONObject userInfoData = data.getJSONObject("userInfo");
                    if (userInfoData != null) {
                        return userInfoData.getStr("screen_name");
                    }
                }
            }
            return null;
        } catch (Exception e) {
            System.err.println("获取用户昵称失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 构建URL
     */
    private static String buildUrl(String baseUrl, Map<String, String> params) {
        StringBuilder url = new StringBuilder(baseUrl);
        if (params != null && !params.isEmpty()) {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    url.append("&");
                }
                url.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }
        return url.toString();
    }
    
    /**
     * 获取默认请求头
     */
    private static Map<String, String> getDefaultHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Referer", "https://m.weibo.cn/");
        headers.put("X-Requested-With", "XMLHttpRequest");
        headers.put("Connection", "keep-alive");
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");
        return headers;
    }
}