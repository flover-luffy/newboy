package net.luffy.model;

import java.util.List;

/**
 * 微博数据模型
 * 基于qqtools项目的数据结构定义
 */
public class WeiboData {
    
    /**
     * 微博卡片信息
     */
    public static class WeiboCard {
        public int cardType;
        public String scheme;
        public WeiboMBlog mblog;
        public Long _id; // 用于排序的ID
        
        public WeiboCard() {}
        
        public WeiboCard(int cardType, String scheme, WeiboMBlog mblog) {
            this.cardType = cardType;
            this.scheme = scheme;
            this.mblog = mblog;
            if (mblog != null && mblog.id != null) {
                this._id = Long.parseLong(mblog.id);
            }
        }
    }
    
    /**
     * 微博内容信息
     */
    public static class WeiboMBlog {
        public String id;
        public String text;
        public String createdAt;
        public WeiboUser user;
        public List<WeiboImage> pics;
        public WeiboMBlog retweetedStatus; // 转发的微博
        public Boolean isTop; // 是否为置顶微博
        
        public WeiboMBlog() {}
    }
    
    /**
     * 微博用户信息
     */
    public static class WeiboUser {
        public String screenName;
        public String id;
        
        public WeiboUser() {}
        
        public WeiboUser(String screenName, String id) {
            this.screenName = screenName;
            this.id = id;
        }
    }
    
    /**
     * 微博图片信息
     */
    public static class WeiboImage {
        public String url;
        
        public WeiboImage() {}
        
        public WeiboImage(String url) {
            this.url = url;
        }
    }
    
    /**
     * 发送数据结构
     */
    public static class WeiboSendData {
        public Long id;
        public String name;
        public String type;
        public String scheme;
        public String time;
        public String text;
        public List<String> pics;
        
        public WeiboSendData() {}
        
        public WeiboSendData(Long id, String name, String type, String scheme, String time, String text, List<String> pics) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.scheme = scheme;
            this.time = time;
            this.text = text;
            this.pics = pics;
        }
    }
    
    /**
     * 微博容器列表
     */
    public static class WeiboContainerList {
        public int ok;
        public WeiboContainerData data;
        
        public WeiboContainerList() {}
    }
    
    /**
     * 微博容器数据
     */
    public static class WeiboContainerData {
        public List<WeiboCard> cards;
        public WeiboPageInfo pageInfo;
        
        public WeiboContainerData() {}
    }
    
    /**
     * 页面信息
     */
    public static class WeiboPageInfo {
        public String nick; // 超话名称
        
        public WeiboPageInfo() {}
    }
    
    /**
     * 超话容器卡片
     */
    public static class WeiboSuperTopicContainerCard {
        public int showType;
        public List<WeiboCard> cardGroup;
        
        public WeiboSuperTopicContainerCard() {}
    }
    
    /**
     * 超话容器列表
     */
    public static class WeiboSuperTopicContainerList {
        public int ok;
        public WeiboSuperTopicContainerData data;
        
        public WeiboSuperTopicContainerList() {}
    }
    
    /**
     * 超话容器数据
     */
    public static class WeiboSuperTopicContainerData {
        public List<WeiboSuperTopicContainerCard> cards;
        public WeiboPageInfo pageInfo;
        
        public WeiboSuperTopicContainerData() {}
    }
}