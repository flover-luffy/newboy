package net.luffy.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import net.luffy.handler.AsyncWebHandlerBase;
import net.luffy.util.UnifiedJsonParser;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 微博API服务
 * 基于qqtools项目的微博功能重构
 * 使用m.weibo.cn的移动端API
 */
public class WeiboApiService extends AsyncWebHandlerBase {
    
    private static final String API_BASE = "https://m.weibo.cn/api/container/getIndex";
    private final UnifiedJsonParser jsonParser = UnifiedJsonParser.getInstance();
    
    public WeiboApiService() {
        super();
    }
    
    /**
     * 获取微博用户信息
     * @param uid 用户UID
     * @return 用户信息JSON对象
     */
    public JSONObject requestWeiboInfo(String uid) {
        Map<String, String> params = new HashMap<>();
        params.put("type", "uid");
        params.put("value", uid);
        
        String url = buildUrl(API_BASE, params);
        String response = get(url, getDefaultHeaders());
        
        if (response != null && !response.isEmpty()) {
            try {
                return jsonParser.parseObj(response);
            } catch (Exception e) {
                // 解析失败
                return null;
            }
        }
        return null;
    }
    
    /**
     * 获取微博容器内容
     * @param lfid 容器ID
     * @return 容器内容JSON对象
     */
    public JSONObject requestWeiboContainer(String lfid) {
        Map<String, String> params = new HashMap<>();
        params.put("containerid", lfid);
        
        String url = buildUrl(API_BASE, params);
        String response = get(url, getDefaultHeaders());
        
        if (response != null && !response.isEmpty()) {
            try {
                return jsonParser.parseObj(response);
            } catch (Exception e) {
                // 解析失败
                return null;
            }
        }
        return null;
    }
    
    /**
     * 获取容器内容（别名方法）
     * @param lfid 容器ID
     * @return 包装后的响应JSON对象
     */
    public JSONObject getContainerContent(String lfid) {
        try {
            JSONObject result = requestWeiboContainer(lfid);
            JSONObject response = new JSONObject();
            
            if (result != null) {
                response.put("success", true);
                response.put("message", "获取容器内容成功");
                response.put("data", result);
            } else {
                response.put("success", false);
                response.put("message", "获取容器内容失败");
                response.put("data", null);
            }
            
            response.put("timestamp", System.currentTimeMillis());
            return response;
        } catch (Exception e) {
            JSONObject errorResponse = new JSONObject();
            errorResponse.put("success", false);
            errorResponse.put("message", "获取容器内容异常: " + e.getMessage());
            errorResponse.put("data", null);
            errorResponse.put("timestamp", String.valueOf(System.currentTimeMillis()));
            return errorResponse;
        }
    }
    
    /**
     * 获取用户的微博容器ID (lfid)
     * @param uid 用户UID
     * @return 微博容器ID，如果获取失败返回null
     */
    public String getUserWeiboLfid(String uid) {
        JSONObject userInfo = requestWeiboInfo(uid);
        if (userInfo != null && userInfo.containsKey("data")) {
            JSONObject data = userInfo.getJSONObject("data");
            if (data.containsKey("tabsInfo")) {
                JSONObject tabsInfo = data.getJSONObject("tabsInfo");
                if (tabsInfo.containsKey("tabs")) {
                    JSONArray tabs = tabsInfo.getJSONArray("tabs");
                    for (Object tabObj : tabs) {
                        JSONObject tab = jsonParser.parseObj(tabObj.toString());
                        if ("weibo".equals(tab.getStr("tabKey"))) {
                            return tab.getStr("containerid");
                        }
                    }
                }
            }
        }
        return null;
    }
    
    /**
     * 获取用户昵称
     * @param uid 用户UID
     * @return 用户昵称，如果获取失败返回"未知用户"
     */
    public String getUserNickname(String uid) {
        JSONObject userInfo = requestWeiboInfo(uid);
        if (userInfo != null && userInfo.containsKey("data")) {
            JSONObject data = userInfo.getJSONObject("data");
            if (data.containsKey("userInfo")) {
                JSONObject userInfoObj = data.getJSONObject("userInfo");
                return userInfoObj.getStr("screen_name", "未知用户");
            }
        }
        return "未知用户";
    }
    
    /**
     * 获取用户最新微博时间
     * @param uid 用户UID
     * @return 最新微博时间字符串，格式为"yyyy-MM-dd HH:mm:ss"，如果获取失败返回"暂无微博"
     */
    public String getUserLatestWeiboTime(String uid) {
        try {
            // 先获取用户的微博容器ID
            String lfid = getUserWeiboLfid(uid);
            if (lfid == null) {
                return "暂无微博";
            }
            
            // 获取微博容器内容
            JSONObject containerData = requestWeiboContainer(lfid);
            if (containerData == null || containerData.getInt("ok", 0) != 1) {
                return "暂无微博";
            }
            
            JSONObject data = containerData.getJSONObject("data");
            if (data == null || !data.containsKey("cards")) {
                return "暂无微博";
            }
            
            JSONArray cards = data.getJSONArray("cards");
            if (cards == null || cards.isEmpty()) {
                return "暂无微博";
            }
            
            // 收集所有微博卡片并按时间排序，找到真正的最新微博
            List<JSONObject> weiboCards = new ArrayList<>();
            for (Object cardObj : cards) {
                JSONObject card = jsonParser.parseObj(cardObj.toString());
                if (card.getInt("card_type", 0) == 9 && card.containsKey("mblog")) {
                    JSONObject mblog = card.getJSONObject("mblog");
                    String createdAt = mblog.getStr("created_at");
                    if (createdAt != null && !createdAt.isEmpty()) {
                        weiboCards.add(mblog);
                    }
                }
            }
            
            if (weiboCards.isEmpty()) {
                return "暂无微博";
            }
            
            // 按时间排序，最新的在前
            weiboCards.sort((a, b) -> {
                try {
                    String timeA = a.getStr("created_at");
                    String timeB = b.getStr("created_at");
                    ZonedDateTime dateA = ZonedDateTime.parse(timeA, DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH));
                    ZonedDateTime dateB = ZonedDateTime.parse(timeB, DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH));
                    return dateB.compareTo(dateA); // 降序排列，最新的在前
                } catch (Exception e) {
                    return 0;
                }
            });
            
            // 返回最新微博的时间
            String latestCreatedAt = weiboCards.get(0).getStr("created_at");
            return formatWeiboTime(latestCreatedAt);
        } catch (Exception e) {
            return "暂无微博";
        }
    }
    
    /**
     * 获取超话最新微博时间
     * @param lfid 超话容器ID
     * @return 最新微博时间字符串，格式为"yyyy-MM-dd HH:mm:ss"，如果获取失败返回"暂无微博"
     */
    public String getSuperTopicLatestWeiboTime(String lfid) {
        try {
            // 获取超话容器内容
            JSONObject containerData = requestWeiboContainer(lfid);
            if (containerData == null || containerData.getInt("ok", 0) != 1) {
                return "暂无微博";
            }
            
            JSONObject data = containerData.getJSONObject("data");
            if (data == null || !data.containsKey("cards")) {
                return "暂无微博";
            }
            
            JSONArray cards = data.getJSONArray("cards");
            if (cards == null || cards.isEmpty()) {
                return "暂无微博";
            }
            
            // 查找最新的微博卡片，排除置顶微博
            for (Object cardObj : cards) {
                JSONObject card = jsonParser.parseObj(cardObj.toString());
                if (card.getInt("card_type", 0) == 9 && card.containsKey("mblog")) {
                    JSONObject mblog = card.getJSONObject("mblog");
                    
                    // 检查是否为置顶微博，如果是则跳过
                    boolean isTop = mblog.getBool("isTop", false) || 
                                   mblog.getBool("pinned", false) || 
                                   mblog.getBool("top", false) ||
                                   mblog.getInt("isTop", 0) == 1 ||
                                   mblog.getInt("pinned", 0) == 1 ||
                                   mblog.getInt("top", 0) == 1 ||
                                   "置顶".equals(mblog.getStr("mblogtype")) ||
                                   card.getStr("card_type_name", "").contains("置顶");
                    
                    if (isTop) {
                        continue; // 跳过置顶微博
                    }
                    
                    String createdAt = mblog.getStr("created_at");
                    if (createdAt != null && !createdAt.isEmpty()) {
                        // 转换微博时间格式
                        return formatWeiboTime(createdAt);
                    }
                }
            }
            
            return "暂无微博";
        } catch (Exception e) {
            return "暂无微博";
        }
    }
    
    /**
     * 格式化微博时间
     * @param weiboTime 微博原始时间字符串
     * @return 格式化后的时间字符串，格式为 yyyy-MM-dd HH:mm:ss
     */
    private String formatWeiboTime(String weiboTime) {
        try {
            // 目标格式
            DateTimeFormatter targetFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            // 如果是相对时间，返回当前时间
            if (weiboTime.contains("分钟前") || weiboTime.contains("小时前") || weiboTime.contains("今天") || weiboTime.contains("昨天")) {
                return LocalDateTime.now().format(targetFormatter);
            }
            
            // 尝试解析微博的绝对时间格式 "Mon Jan 01 12:00:00 +0800 2024"
            try {
                // 微博时间格式：EEE MMM dd HH:mm:ss Z yyyy
                DateTimeFormatter weiboFormatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH);
                ZonedDateTime zonedDateTime = ZonedDateTime.parse(weiboTime, weiboFormatter);
                return zonedDateTime.toLocalDateTime().format(targetFormatter);
            } catch (DateTimeParseException e) {
                // 如果解析失败，返回当前时间
                return LocalDateTime.now().format(targetFormatter);
            }
        } catch (Exception e) {
            // 发生任何异常，返回当前时间
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }
    
    /**
     * 构建URL
     * @param baseUrl 基础URL
     * @param params 参数
     * @return 完整URL
     */
    private String buildUrl(String baseUrl, Map<String, String> params) {
        StringBuilder url = new StringBuilder(baseUrl);
        if (!params.isEmpty()) {
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
     * @return 请求头Map
     */
    private Map<String, String> getDefaultHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Accept-Charset", "UTF-8");
        headers.put("Referer", "https://m.weibo.cn/");
        return headers;
    }
    

}