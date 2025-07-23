package net.luffy.util;

import cn.hutool.core.date.DateUtil;
import cn.hutool.json.JSONObject;
import net.luffy.Newboy;
import net.luffy.handler.WeiboHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微博监控服务
 * 用于跟踪微博用户的最后更新时间和昵称
 */
public class WeiboMonitorService {
    
    private static volatile WeiboMonitorService instance;
    
    // 存储用户信息：用户ID -> 用户监控信息
    private final Map<Long, UserMonitorInfo> monitoredUsers = new ConcurrentHashMap<>();
    
    /**
     * 用户监控信息
     */
    public static class UserMonitorInfo {
        public long userId;
        public String nickname;
        public long lastUpdateTime;
        public long lastCheckTime;
        
        public UserMonitorInfo(long userId) {
            this.userId = userId;
            this.lastUpdateTime = System.currentTimeMillis();
            this.lastCheckTime = System.currentTimeMillis();
        }
    }
    
    private WeiboMonitorService() {
    }
    
    /**
     * 获取单例实例
     */
    public static WeiboMonitorService getInstance() {
        if (instance == null) {
            synchronized (WeiboMonitorService.class) {
                if (instance == null) {
                    instance = new WeiboMonitorService();
                }
            }
        }
        return instance;
    }
    
    /**
     * 添加监控用户
     */
    public void addMonitorUser(long userId) {
        if (!monitoredUsers.containsKey(userId)) {
            UserMonitorInfo userInfo = new UserMonitorInfo(userId);
            
            // 初始化用户信息
            try {
                WeiboHandler handler = Newboy.INSTANCE.getHandlerWeibo();
                String nickname = handler.getUserName(userId);
                if (nickname != null && !nickname.equals("未知用户")) {
                    userInfo.nickname = nickname;
                }
                
                // 获取最新微博时间（排除置顶微博）
                Object[] blogs = handler.getUserBlog(userId);
                if (blogs != null && blogs.length > 0) {
                    long latestTime = getLatestNonPinnedBlogTime(blogs);
                    if (latestTime > 0) {
                        userInfo.lastUpdateTime = latestTime;
                    }
                }
            } catch (Exception e) {
                // 初始化失败，使用默认值
            }
            
            monitoredUsers.put(userId, userInfo);
        }
    }
    
    /**
     * 获取最新非置顶微博的时间
     */
    private long getLatestNonPinnedBlogTime(Object[] blogs) {
        try {
            for (Object blogObj : blogs) {
                JSONObject blog = net.luffy.util.UnifiedJsonParser.getInstance().parseObj(blogObj.toString());
                
                // 跳过置顶微博
                if (blog.containsKey("isTop") && blog.getInt("isTop") == 1) {
                    continue;
                }
                
                // 跳过转发、赞过的微博
                if (blog.containsKey("title")) {
                    continue;
                }
                
                String createdAt = blog.getStr("created_at");
                if (createdAt != null) {
                    return DateUtil.parse(createdAt).getTime();
                }
            }
        } catch (Exception e) {
            // 解析失败
        }
        return 0;
    }
    
    /**
     * 更新用户最后更新时间
     */
    public void updateUserLastTime(long userId, long updateTime) {
        UserMonitorInfo userInfo = monitoredUsers.get(userId);
        if (userInfo != null) {
            userInfo.lastUpdateTime = updateTime;
            userInfo.lastCheckTime = System.currentTimeMillis();
        }
    }
    
    /**
     * 获取用户昵称
     */
    public String getUserNickname(long userId) {
        UserMonitorInfo userInfo = monitoredUsers.get(userId);
        if (userInfo != null && userInfo.nickname != null) {
            return userInfo.nickname;
        }
        
        // 如果没有缓存，尝试从WeiboHandler获取
        try {
            String nickname = Newboy.INSTANCE.getHandlerWeibo().getUserName(userId);
            if (nickname != null && !nickname.equals("未知用户")) {
                // 添加到监控并缓存昵称
                addMonitorUser(userId);
                return nickname;
            }
        } catch (Exception e) {
            // 获取失败
        }
        
        return "未知用户";
    }
    
    /**
     * 获取用户最后更新时间
     */
    public long getUserLastUpdateTime(long userId) {
        UserMonitorInfo userInfo = monitoredUsers.get(userId);
        if (userInfo != null) {
            return userInfo.lastUpdateTime;
        }
        
        // 如果没有缓存，尝试添加监控
        addMonitorUser(userId);
        userInfo = monitoredUsers.get(userId);
        return userInfo != null ? userInfo.lastUpdateTime : 0;
    }
    
    /**
     * 获取格式化的最后更新时间
     */
    public String getFormattedLastUpdateTime(long userId) {
        long lastUpdateTime = getUserLastUpdateTime(userId);
        if (lastUpdateTime > 0) {
            return DateUtil.formatDateTime(new java.util.Date(lastUpdateTime));
        }
        return "未知";
    }
    
    /**
     * 移除监控用户
     */
    public void removeMonitorUser(long userId) {
        monitoredUsers.remove(userId);
    }
    
    /**
     * 检查并更新用户信息
     */
    public void checkAndUpdateUser(long userId) {
        try {
            WeiboHandler handler = Newboy.INSTANCE.getHandlerWeibo();
            
            // 更新昵称
            String nickname = handler.getUserName(userId);
            UserMonitorInfo userInfo = monitoredUsers.get(userId);
            if (userInfo != null && nickname != null && !nickname.equals("未知用户")) {
                userInfo.nickname = nickname;
            }
            
            // 更新最后更新时间
            Object[] blogs = handler.getUserBlog(userId);
            if (blogs != null && blogs.length > 0) {
                long latestTime = getLatestNonPinnedBlogTime(blogs);
                if (latestTime > 0 && userInfo != null) {
                    userInfo.lastUpdateTime = latestTime;
                    userInfo.lastCheckTime = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            // 更新失败
        }
    }
    
    /**
     * 获取监控用户信息
     * @param userId 用户ID
     * @return 用户监控信息，如果用户不存在则返回null
     */
    public UserMonitorInfo getMonitoredUserInfo(long userId) {
        return monitoredUsers.get(userId);
    }
}