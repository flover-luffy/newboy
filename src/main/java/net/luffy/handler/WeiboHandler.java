package net.luffy.handler;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import net.luffy.Newboy;
import net.luffy.service.WeiboApiService;
import net.luffy.service.WeiboMonitorService;
import net.luffy.util.Properties;
import net.luffy.util.UnifiedJsonParser;
import net.luffy.util.sender.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;

/**
 * 微博处理器
 * 基于qqtools项目的微博功能重构
 * 提供微博监控和管理的API接口
 */
@RestController
@RequestMapping("/weibo")
public class WeiboHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(WeiboHandler.class);
    
    private MessageSender messageSender;
    
    private WeiboApiService weiboApiService;
    private WeiboMonitorService weiboMonitorService;
    private final UnifiedJsonParser jsonParser = UnifiedJsonParser.getInstance();
    
    @PostConstruct
    public void init() {
        logger.info("=== 开始初始化微博处理器 ===");
        
        try {
            // 初始化MessageSender
            this.messageSender = new MessageSender();
            
            // 初始化微博API服务
            weiboApiService = new WeiboApiService();
            
            // 初始化微博监控服务
            weiboMonitorService = new WeiboMonitorService(weiboApiService, messageSender);
            
            // 从Properties加载现有订阅数据
            loadExistingSubscriptions();
            
            // 启动监控服务
            weiboMonitorService.startMonitoring();
            
            logger.info("=== 微博处理器初始化完成 ===");
        } catch (Exception e) {
            logger.error("微博处理器初始化失败", e);
        }
    }
    
    /**
     * 从Properties加载现有的微博订阅数据
     */
    private void loadExistingSubscriptions() {
        try {
            Properties properties = Newboy.INSTANCE.getProperties();
            if (properties == null) {
                logger.warn("Properties为空，跳过微博订阅数据加载");
                return;
            }
            
            int loadedUserSubscriptions = 0;
            int loadedTopicSubscriptions = 0;
            
            // 加载微博用户订阅
            if (properties.weibo_user_subscribe != null) {
                for (Map.Entry<Long, List<Long>> entry : properties.weibo_user_subscribe.entrySet()) {
                    Long groupId = entry.getKey();
                    List<Long> userIds = entry.getValue();
                    
                    for (Long userId : userIds) {
                        Set<String> groupIds = new HashSet<>();
                        groupIds.add(String.valueOf(groupId));
                        weiboMonitorService.addUserMonitor(String.valueOf(userId), groupIds);
                        loadedUserSubscriptions++;
                    }
                }
            }
            
            // 加载微博超话订阅
            if (properties.weibo_superTopic_subscribe != null) {
                for (Map.Entry<Long, List<String>> entry : properties.weibo_superTopic_subscribe.entrySet()) {
                    Long groupId = entry.getKey();
                    List<String> topicIds = entry.getValue();
                    
                    for (String topicId : topicIds) {
                        Set<String> groupIds = new HashSet<>();
                        groupIds.add(String.valueOf(groupId));
                        weiboMonitorService.addSuperTopicMonitor(topicId, groupIds);
                        loadedTopicSubscriptions++;
                    }
                }
            }
            
            logger.info("微博订阅数据加载完成 - 用户订阅: {}, 超话订阅: {}", 
                       loadedUserSubscriptions, loadedTopicSubscriptions);
                       
        } catch (Exception e) {
            logger.error("加载微博订阅数据失败", e);
        }
    }
    
    @PreDestroy
    public void destroy() {
        logger.info("销毁微博处理器");
        if (weiboMonitorService != null) {
            weiboMonitorService.stopMonitoring();
        }
    }
    
    /**
     * 获取微博用户信息
     * @param uid 用户UID
     * @return 用户信息
     */
    @GetMapping("/user/{uid}")
    public JSONObject getUserInfo(@PathVariable String uid) {
        try {
            JSONObject userInfo = weiboApiService.requestWeiboInfo(uid);
            if (userInfo != null) {
                return createSuccessResponse("获取用户信息成功", userInfo);
            } else {
                return createErrorResponse("获取用户信息失败");
            }
        } catch (Exception e) {
            logger.error("获取用户信息异常: {}", uid, e);
            return createErrorResponse("获取用户信息异常: " + e.getMessage());
        }
    }
    
    /**
     * 获取微博容器内容
     * @param lfid 容器ID
     * @return 容器内容
     */
    @GetMapping("/container/{lfid}")
    public JSONObject getContainerContent(@PathVariable String lfid) {
        try {
            JSONObject containerData = weiboApiService.requestWeiboContainer(lfid);
            if (containerData != null) {
                return createSuccessResponse("获取容器内容成功", containerData);
            } else {
                return createErrorResponse("获取容器内容失败");
            }
        } catch (Exception e) {
            logger.error("获取容器内容异常: {}", lfid, e);
            return createErrorResponse("获取容器内容异常: " + e.getMessage());
        }
    }
    
    /**
     * 添加用户监控
     * @param request 请求参数
     * @return 操作结果
     */
    @PostMapping("/monitor/user")
    public JSONObject addUserMonitor(@RequestBody JSONObject request) {
        try {
            String uid = request.getStr("uid");
            JSONArray groupIdsArray = request.getJSONArray("groupIds");
            
            if (uid == null || uid.isEmpty()) {
                return createErrorResponse("用户UID不能为空");
            }
            
            if (groupIdsArray == null || groupIdsArray.isEmpty()) {
                return createErrorResponse("群组ID列表不能为空");
            }
            
            Set<String> groupIds = new HashSet<>();
            for (Object groupIdObj : groupIdsArray) {
                groupIds.add(groupIdObj.toString());
            }
            
            weiboMonitorService.addUserMonitor(uid, groupIds);
            
            return createSuccessResponse("添加用户监控成功", null);
        } catch (Exception e) {
            logger.error("添加用户监控异常", e);
            return createErrorResponse("添加用户监控异常: " + e.getMessage());
        }
    }
    
    /**
     * 移除用户监控
     * @param uid 用户UID
     * @return 操作结果
     */
    @DeleteMapping("/monitor/user/{uid}")
    public JSONObject removeUserMonitor(@PathVariable String uid) {
        try {
            weiboMonitorService.removeUserMonitor(uid);
            return createSuccessResponse("移除用户监控成功", null);
        } catch (Exception e) {
            logger.error("移除用户监控异常: {}", uid, e);
            return createErrorResponse("移除用户监控异常: " + e.getMessage());
        }
    }
    
    /**
     * 添加超话监控
     * @param request 请求参数
     * @return 操作结果
     */
    @PostMapping("/monitor/supertopic")
    public JSONObject addSuperTopicMonitor(@RequestBody JSONObject request) {
        try {
            String lfid = request.getStr("lfid");
            JSONArray groupIdsArray = request.getJSONArray("groupIds");
            
            if (lfid == null || lfid.isEmpty()) {
                return createErrorResponse("超话容器ID不能为空");
            }
            
            if (groupIdsArray == null || groupIdsArray.isEmpty()) {
                return createErrorResponse("群组ID列表不能为空");
            }
            
            Set<String> groupIds = new HashSet<>();
            for (Object groupIdObj : groupIdsArray) {
                groupIds.add(groupIdObj.toString());
            }
            
            weiboMonitorService.addSuperTopicMonitor(lfid, groupIds);
            
            return createSuccessResponse("添加超话监控成功", null);
        } catch (Exception e) {
            logger.error("添加超话监控异常", e);
            return createErrorResponse("添加超话监控异常: " + e.getMessage());
        }
    }
    
    /**
     * 移除超话监控
     * @param lfid 超话容器ID
     * @return 操作结果
     */
    @DeleteMapping("/monitor/supertopic/{lfid}")
    public JSONObject removeSuperTopicMonitor(@PathVariable String lfid) {
        try {
            weiboMonitorService.removeSuperTopicMonitor(lfid);
            return createSuccessResponse("移除超话监控成功", null);
        } catch (Exception e) {
            logger.error("移除超话监控异常: {}", lfid, e);
            return createErrorResponse("移除超话监控异常: " + e.getMessage());
        }
    }
    
    /**
     * 获取监控状态
     * @return 监控状态信息
     */
    @GetMapping("/monitor/status")
    public JSONObject getMonitorStatus() {
        try {
            Map<String, Object> status = weiboMonitorService.getMonitorStatus();
            return createSuccessResponse("获取监控状态成功", status);
        } catch (Exception e) {
            logger.error("获取监控状态异常", e);
            return createErrorResponse("获取监控状态异常: " + e.getMessage());
        }
    }
    
    /**
     * 获取用户昵称
     * @param uid 用户UID
     * @return 用户昵称
     */
    @GetMapping("/user/{uid}/nickname")
    public JSONObject getUserNickname(@PathVariable String uid) {
        try {
            String nickname = weiboApiService.getUserNickname(uid);
            Map<String, String> data = new HashMap<>();
            data.put("uid", uid);
            data.put("nickname", nickname);
            return createSuccessResponse("获取用户昵称成功", data);
        } catch (Exception e) {
            logger.error("获取用户昵称异常: {}", uid, e);
            return createErrorResponse("获取用户昵称异常: " + e.getMessage());
        }
    }
    
    /**
     * 获取用户微博容器ID
     * @param uid 用户UID
     * @return 微博容器ID
     */
    @GetMapping("/user/{uid}/lfid")
    public JSONObject getUserLfid(@PathVariable String uid) {
        try {
            String lfid = weiboApiService.getUserWeiboLfid(uid);
            Map<String, String> data = new HashMap<>();
            data.put("uid", uid);
            data.put("lfid", lfid);
            return createSuccessResponse("获取用户微博容器ID成功", data);
        } catch (Exception e) {
            logger.error("获取用户微博容器ID异常: {}", uid, e);
            return createErrorResponse("获取用户微博容器ID异常: " + e.getMessage());
        }
    }
    
    /**
     * 健康检查
     * @return 健康状态
     */
    @GetMapping("/health")
    public JSONObject health() {
        Map<String, Object> data = new HashMap<>();
        data.put("status", "healthy");
        data.put("timestamp", System.currentTimeMillis());
        data.put("service", "weibo-handler");
        return createSuccessResponse("微博服务运行正常", data);
    }
    
    /**
     * 创建成功响应
     * @param message 消息
     * @param data 数据
     * @return 响应对象
     */
    private JSONObject createSuccessResponse(String message, Object data) {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", message);
        response.put("data", data);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
    
    /**
     * 创建错误响应
     * @param message 错误消息
     * @return 响应对象
     */
    private JSONObject createErrorResponse(String message) {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("message", message);
        response.put("data", null);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
    
    /**
     * 获取超话资源信息
     * @param lfid 超话ID
     * @return 超话资源信息字符串
     */
    public String getSuperTopicRes(String lfid) {
        try {
            if (lfid == null || lfid.trim().isEmpty()) {
                return "超话ID不能为空";
            }
            
            // 调用微博API服务获取超话信息
            JSONObject containerInfo = weiboApiService.requestWeiboContainer(lfid);
            if (containerInfo != null && containerInfo.getBool("success", false)) {
                JSONObject data = containerInfo.getJSONObject("data");
                if (data != null) {
                    return "超话信息获取成功: " + data.toString();
                }
            }
            
            return "获取超话信息失败";
        } catch (Exception e) {
            logger.error("获取超话资源失败: " + e.getMessage(), e);
            return "获取超话资源失败: " + e.getMessage();
        }
    }
    
    /**
     * 获取用户昵称
     * @param uid 用户UID
     * @return 用户昵称
     */
    public String getUserName(Long uid) {
        try {
            if (uid == null) {
                return "未知用户";
            }
            
            String nickname = weiboApiService.getUserNickname(uid.toString());
            return nickname != null ? nickname : "未知用户";
        } catch (Exception e) {
            logger.error("获取用户昵称失败: " + e.getMessage(), e);
            return "未知用户";
        }
    }
    
    /**
     * 获取微博用户信息
     * @param uid 用户UID字符串
     * @return 用户信息JSON字符串
     */
    public String getWeiboUserInfo(String uid) {
        try {
            if (uid == null || uid.trim().isEmpty()) {
                return "{\"error\": \"用户UID不能为空\"}";
            }
            
            JSONObject userInfo = weiboApiService.requestWeiboInfo(uid);
            if (userInfo != null) {
                return userInfo.toString();
            } else {
                return "{\"error\": \"获取用户信息失败\"}";
            }
        } catch (Exception e) {
            logger.error("获取微博用户信息失败: " + e.getMessage(), e);
            return "{\"error\": \"获取用户信息异常: " + e.getMessage() + "\"}";
        }
    }
    
    /**
     * 获取微博监控状态
     * @return 监控状态信息
     */
    public String getWeiboMonitorStatus() {
        try {
            Map<String, Object> status = weiboMonitorService.getMonitorStatus();
            if (status != null) {
                JSONObject jsonStatus = new JSONObject(status);
                return jsonStatus.toString();
            } else {
                return "{\"error\": \"获取监控状态失败\"}";
            }
        } catch (Exception e) {
            logger.error("获取微博监控状态异常", e);
            return "{\"error\": \"获取监控状态异常: \" + e.getMessage() + \"}\"";
        }
    }
}