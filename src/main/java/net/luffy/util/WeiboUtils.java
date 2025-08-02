package net.luffy.util;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import net.luffy.model.WeiboData;
import net.luffy.service.WeiboApiService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 微博工具类
 * 基于qqtools项目的weiboUtils.ts重构
 */
public class WeiboUtils {
    
    private static final UnifiedJsonParser jsonParser = UnifiedJsonParser.getInstance();
    
    /**
     * 过滤微博卡片
     * 只保留cardType为9的卡片，并按ID降序排序
     * @param cards 原始卡片列表
     * @return 过滤后的卡片列表
     */
    public static List<WeiboData.WeiboCard> filterCards(JSONArray cards) {
        List<WeiboData.WeiboCard> filteredCards = new ArrayList<>();
        
        if (cards == null || cards.isEmpty()) {
            return filteredCards;
        }
        
        for (Object cardObj : cards) {
            JSONObject card = jsonParser.parseObj(cardObj.toString());
            int cardType = card.getInt("card_type", 0);
            
            // 只保留cardType为9的卡片
            if (cardType == 9) {
                WeiboData.WeiboCard weiboCard = new WeiboData.WeiboCard();
                weiboCard.cardType = cardType;
                weiboCard.scheme = card.getStr("scheme");
                
                // 解析mblog
                if (card.containsKey("mblog")) {
                    JSONObject mblogJson = card.getJSONObject("mblog");
                    weiboCard.mblog = parseMBlog(mblogJson);
                    
                    // 设置排序用的ID
                    if (weiboCard.mblog != null && weiboCard.mblog.id != null) {
                        try {
                            weiboCard._id = Long.parseLong(weiboCard.mblog.id);
                        } catch (NumberFormatException e) {
                            weiboCard._id = 0L;
                        }
                    }
                    
                    // 排除置顶微博
                    if (weiboCard.mblog != null && Boolean.TRUE.equals(weiboCard.mblog.isTop)) {
                        continue; // 跳过置顶微博
                    }
                }
                
                filteredCards.add(weiboCard);
            }
        }
        
        // 按ID降序排序
        filteredCards.sort((a, b) -> {
            if (a._id == null && b._id == null) return 0;
            if (a._id == null) return 1;
            if (b._id == null) return -1;
            return Long.compare(b._id, a._id);
        });
        
        return filteredCards;
    }
    
    /**
     * 过滤新微博卡片
     * 提取新微博数据用于发送
     * 基于qqtools项目的逻辑
     * @param cards 过滤后的卡片列表
     * @return 新微博发送数据列表
     */
    public static List<WeiboData.WeiboSendData> filterNewCards(List<WeiboData.WeiboCard> cards) {
        List<WeiboData.WeiboSendData> newCards = new ArrayList<>();
        
        for (WeiboData.WeiboCard card : cards) {
            if (card.mblog != null) {
                WeiboData.WeiboSendData sendData = new WeiboData.WeiboSendData();
                
                // 设置基本信息
                sendData.id = card._id;
                // 根据qqtools项目逻辑判断类型：有转发状态为"转载"，否则为"原创"
                sendData.type = (card.mblog.retweetedStatus != null) ? "转载" : "原创";
                sendData.scheme = card.scheme;
                sendData.time = formatWeiboTime(card.mblog.createdAt);
                // 清理HTML标签，与qqtools项目保持一致
                sendData.text = card.mblog.text != null ? card.mblog.text.replaceAll("<[^<>]+>", " ") : "";
                
                // 设置用户名
                if (card.mblog.user != null) {
                    sendData.name = card.mblog.user.screenName;
                } else {
                    sendData.name = "未知用户";
                }
                
                // 设置图片列表
                sendData.pics = new ArrayList<>();
                if (card.mblog.pics != null && !card.mblog.pics.isEmpty()) {
                    for (WeiboData.WeiboImage pic : card.mblog.pics) {
                        if (pic.url != null && !pic.url.isEmpty()) {
                            sendData.pics.add(pic.url);
                        }
                    }
                }
                
                newCards.add(sendData);
            }
        }
        
        return newCards;
    }
    
    /**
     * 过滤超话微博卡片
     * @param cards 原始卡片列表
     * @return 过滤后的卡片列表
     */
    public static List<WeiboData.WeiboCard> filterSuperTopicCards(JSONArray cards) {
        List<WeiboData.WeiboCard> filteredCards = new ArrayList<>();
        
        if (cards == null || cards.isEmpty()) {
            return filteredCards;
        }
        
        for (Object cardObj : cards) {
            JSONObject card = jsonParser.parseObj(cardObj.toString());
            int showType = card.getInt("show_type", 0);
            
            // 处理超话卡片
            if (showType == 1 && card.containsKey("card_group")) {
                JSONArray cardGroup = card.getJSONArray("card_group");
                List<WeiboData.WeiboCard> groupCards = filterCards(cardGroup);
                filteredCards.addAll(groupCards);
            }
        }
        
        // 按ID降序排序
        filteredCards.sort((a, b) -> {
            if (a._id == null && b._id == null) return 0;
            if (a._id == null) return 1;
            if (b._id == null) return -1;
            return Long.compare(b._id, a._id);
        });
        
        return filteredCards;
    }
    
    /**
     * 解析微博内容
     * @param mblogJson 微博JSON对象
     * @return 微博内容对象
     */
    private static WeiboData.WeiboMBlog parseMBlog(JSONObject mblogJson) {
        if (mblogJson == null) {
            return null;
        }
        
        WeiboData.WeiboMBlog mblog = new WeiboData.WeiboMBlog();
        mblog.id = mblogJson.getStr("id");
        mblog.text = mblogJson.getStr("text");
        mblog.createdAt = mblogJson.getStr("created_at");
        
        // 解析置顶标识，微博API中可能使用不同的字段名
        mblog.isTop = mblogJson.getBool("isTop", false) || 
                     mblogJson.getBool("pinned", false) || 
                     mblogJson.getBool("top", false) ||
                     mblogJson.getInt("isTop", 0) == 1 ||
                     mblogJson.getInt("pinned", 0) == 1 ||
                     mblogJson.getInt("top", 0) == 1 ||
                     "置顶".equals(mblogJson.getStr("mblogtype"));
        
        // 解析用户信息
        if (mblogJson.containsKey("user")) {
            JSONObject userJson = mblogJson.getJSONObject("user");
            mblog.user = new WeiboData.WeiboUser();
            mblog.user.screenName = userJson.getStr("screen_name");
            mblog.user.id = userJson.getStr("id");
        }
        
        // 解析图片信息
        if (mblogJson.containsKey("pics")) {
            JSONArray picsJson = mblogJson.getJSONArray("pics");
            mblog.pics = new ArrayList<>();
            for (Object picObj : picsJson) {
                JSONObject pic = jsonParser.parseObj(picObj.toString());
                WeiboData.WeiboImage image = new WeiboData.WeiboImage();
                image.url = pic.getStr("url");
                mblog.pics.add(image);
            }
        }
        
        // 解析转发微博
        if (mblogJson.containsKey("retweeted_status")) {
            JSONObject retweetedJson = mblogJson.getJSONObject("retweeted_status");
            mblog.retweetedStatus = parseMBlog(retweetedJson);
        }
        
        return mblog;
    }
    
    /**
     * 构建微博消息文本
     * 基于qqtools项目的消息格式
     * @param sendData 发送数据
     * @param superTopicName 超话名称（可选）
     * @return 格式化的消息文本
     */
    public static String buildWeiboMessage(WeiboData.WeiboSendData sendData, String superTopicName) {
        StringBuilder message = new StringBuilder();
        
        // 根据qqtools项目的格式构建消息
        if (superTopicName != null && !superTopicName.isEmpty()) {
            // 超话格式：{用户名} 在{时间}，在超话#{超话名}#发送了一条微博：{内容}
            message.append(sendData.name)
                   .append(" 在").append(sendData.time)
                   .append("，在超话#").append(superTopicName).append("#发送了一条微博：")
                   .append(sendData.text).append("\n");
        } else {
            // 普通微博格式：{用户名} 在{时间}发送了一条微博：{内容}
            message.append(sendData.name)
                   .append(" 在").append(sendData.time)
                   .append("发送了一条微博：")
                   .append(sendData.text).append("\n");
        }
        
        // 添加类型信息
        message.append("类型：").append(sendData.type).append("\n");
        
        // 添加地址
        if (sendData.scheme != null && !sendData.scheme.isEmpty()) {
            message.append("地址：").append(sendData.scheme);
        }
        
        return message.toString();
    }
    
    /**
     * 处理图片URL，添加代理前缀
     * @param imageUrl 原始图片URL
     * @param proxyPrefix 代理前缀
     * @return 处理后的图片URL
     */
    public static String processImageUrl(String imageUrl, String proxyPrefix) {
        if (imageUrl == null || imageUrl.isEmpty()) {
            return imageUrl;
        }
        
        if (proxyPrefix != null && !proxyPrefix.isEmpty()) {
            return proxyPrefix + imageUrl;
        }
        
        return imageUrl;
    }
    
    /**
     * 格式化微博时间
     * 基于qqtools项目的时间格式化逻辑
     * @param weiboTime 微博原始时间字符串
     * @return 格式化后的时间字符串，格式为 yyyy-MM-dd HH:mm:ss
     */
    private static String formatWeiboTime(String weiboTime) {
        if (weiboTime == null || weiboTime.isEmpty()) {
            return "";
        }
        
        try {
            // 使用WeiboApiService的时间格式化逻辑
            WeiboApiService apiService = new WeiboApiService();
            // 由于WeiboApiService的formatWeiboTime是私有方法，我们在这里实现相同的逻辑
            
            java.time.format.DateTimeFormatter targetFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            // 如果是相对时间，返回当前时间
            if (weiboTime.contains("分钟前") || weiboTime.contains("小时前") || weiboTime.contains("今天") || weiboTime.contains("昨天")) {
                return java.time.LocalDateTime.now().format(targetFormatter);
            }
            
            // 尝试解析微博的绝对时间格式 "Mon Jan 01 12:00:00 +0800 2024"
            try {
                // 微博时间格式：EEE MMM dd HH:mm:ss Z yyyy
                java.time.format.DateTimeFormatter weiboFormatter = java.time.format.DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", java.util.Locale.ENGLISH);
                java.time.ZonedDateTime zonedDateTime = java.time.ZonedDateTime.parse(weiboTime, weiboFormatter);
                return zonedDateTime.toLocalDateTime().format(targetFormatter);
            } catch (java.time.format.DateTimeParseException e) {
                // 如果解析失败，返回当前时间
                return java.time.LocalDateTime.now().format(targetFormatter);
            }
        } catch (Exception e) {
            // 发生任何异常，返回当前时间
            return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }
}