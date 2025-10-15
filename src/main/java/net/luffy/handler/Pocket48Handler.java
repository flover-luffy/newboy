package net.luffy.handler;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import net.luffy.util.UnifiedJsonParser;
import net.luffy.util.UnifiedHttpClient;
import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48RoomInfo;
import net.luffy.util.DynamicTimeoutManager;
import net.luffy.util.MonitorConfig;
import net.luffy.util.UnifiedLogger;
import net.luffy.util.sender.Pocket48Sender;
import net.luffy.util.ConcurrencySafetyUtils;
import net.luffy.util.StringMatchUtils;
import net.luffy.model.Pocket48RoomInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Pocket48Handler extends AsyncWebHandlerBase {

    private static final Logger logger = LoggerFactory.getLogger(Pocket48Handler.class);
    public static final String ROOT = "https://pocketapi.48.cn";
    public static final String SOURCEROOT = "https://source.48.cn/";
    public static final String APIAnswerDetail = ROOT + "/idolanswer/api/idolanswer/v1/question_answer/detail";
    public static final List<Long> voidRoomVoiceList = new ArrayList<>();
    private static final String APILogin = ROOT + "/user/api/v1/login/app/mobile";
    private static final String APIBalance = ROOT + "/user/api/v1/user/money";
    private static final String APIStar2Server = ROOT + "/im/api/v1/im/server/jump";
    private static final String APIServer2Channel = ROOT + "/im/api/v1/team/last/message/get";
    private static final String APIChannel2Server = ROOT + "/im/api/v1/im/team/room/info";
    private static final String APISearch = ROOT + "/im/api/v1/im/server/search";
    private static final String APIMsgOwner = ROOT + "/im/api/v1/team/message/list/homeowner";
    private static final String APIMsgAll = ROOT + "/im/api/v1/team/message/list/all";
    private static final String APIUserInfo = ROOT + "/user/api/v1/user/info/home";
    private static final String APIUserArchives = ROOT + "/user/api/v1/user/star/archives";
    private static final String APILiveList = ROOT + "/live/api/v1/live/getLiveList";
    private static final String APIRoomVoice = ROOT + "/im/api/v1/team/voice/operate";
    private final Pocket48HandlerHeader header;
    private final HashMap<Long, String> name = new HashMap<>();
    private final UnifiedJsonParser jsonParser = UnifiedJsonParser.getInstance();
    private final DynamicTimeoutManager timeoutManager;
    private final UnifiedHttpClient httpClient;
    // 延迟服务已移除
    private final ConcurrencySafetyUtils concurrencyUtils;
    private final StringMatchUtils stringMatchUtils;
    private final Pocket48Sender pocket48Sender;

    public Pocket48Handler() {
        super();
        this.header = new Pocket48HandlerHeader(properties);
        this.timeoutManager = DynamicTimeoutManager.getInstance();
        this.httpClient = UnifiedHttpClient.getInstance();
        // 延迟服务已移除
        this.concurrencyUtils = ConcurrencySafetyUtils.getInstance();
        this.stringMatchUtils = new StringMatchUtils();
        // 暂时注释掉 Pocket48Sender 的初始化，因为它需要特定的参数
        // this.pocket48Sender = new Pocket48Sender();
        this.pocket48Sender = null;
    }

    public static final String getOwnerOrTeamName(Pocket48RoomInfo roomInfo) {
        switch (String.valueOf(roomInfo.getSeverId())) {
            case "1148749":
                return "TEAM Z";
            case "1164313":
                return "TEAM X";
            case "1164314":
                return "TEAM NIII";
            case "1181051":
                return "TEAM HII";
            case "1181256":
                return "TEAM G";
            case "1213978":
                return "TEAM NII";
            case "1214171":
                return "TEAM SII";
            case "1115226":
                return "CKG48";
            case "1115413":
                return "BEJ48";
            default:
                return roomInfo.getOwnerName();
        }
    }

    //登陆前
    public boolean login(String account, String password) {
        try {
            String url = APILogin;
            
            JSONObject requestBody = new JSONObject();
            requestBody.set("pwd", password);
            requestBody.set("mobile", account);
            
            String s = post(url, requestBody.toString(), header.getLoginHeaders());
            
            JSONObject object = jsonParser.parseObj(s);
            if (object.getInt("status") == 200) {
                JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
                login(content.getStr("token"), true);
                return true;
            } else {
                logInfo("口袋48登陆失败：" + object.getStr("message"));
                return false;
            }
            
        } catch (Exception e) {
            logError("登录异常: " + e.getMessage());
            return false;
        }
    }

    public void login(String token, boolean save) {
        header.setToken(token);
        logInfo("口袋48登陆成功");
        if (save)
            Newboy.INSTANCE.getConfig().setAndSaveToken(token);
    }

    public boolean isLogin() {
        return header.getToken() != null;
    }

    /* ----------------------文字获取类---------------------- */

    public void logout() {
        header.setToken(null);
    }

    //掉token时重新登陆一下，因为没传入code参数，就写在这咯~
    @Override
    protected void logError(String msg) {
        if (msg.endsWith("非法授权")) {
            logout();
            String token_local = Newboy.INSTANCE.getConfig().getToken();
            if (!properties.pocket48_token.equals(token_local)) {
                login(token_local, false);
            } else if (properties.pocket48_account.equals("") || properties.pocket48_password.equals("")) {
                super.logError("口袋48 token失效请重新填写，同时填写token和账密可在token时效时登录（优先使用token）");
            } else {
                login(properties.pocket48_account, properties.pocket48_password);
            }
        }

        super.logError(msg);
    }

    protected java.util.Map<String, String> getPocket48Headers() {
        if (header.getToken() != null) {
            return header.getHeaders();
        } else {
            return header.getLoginHeaders();
        }
    }
    
    /**
     * 获取当前的token
     * @return token字符串，如果未设置则返回null
     */
    public String getToken() {
        return header != null ? header.getToken() : null;
    }
    
    /**
     * 获取User-Agent字符串
     * @return User-Agent字符串
     */
    public String getUserAgent() {
        return "PocketFans201807/7.1.36 (iPhone; iOS 26.0; Scale/2.00)";
    }

    public String getBalance() {
        try {
            String requestBody = "{}";
            String response = post(APIBalance, requestBody, getPocket48Headers());
            
            JSONObject jsonResponse = jsonParser.parseObj(response);
            if (jsonResponse.getInt("status") == 200) {
                JSONObject content = jsonResponse.getJSONObject("content");
                if (content.containsKey("moneyPay")) {
                    return content.getStr("moneyPay", "0");
                } else if (content.containsKey("moneyTotal")) {
                    return content.getStr("moneyTotal", "0");
                } else {
                    return "0";
                }
            } else {
                logError("获取余额失败: " + jsonResponse.getStr("message"));
                return "0";
            }
            
        } catch (Exception e) {
            logError("获取余额异常: " + e.getMessage());
            return "0";
        }
    }

    public String getStarNameByStarID(long starID) {
        if (name.containsKey(starID))
            return name.get(starID);

        JSONObject info = getUserInfo(starID);
        if (info == null)
            return null;

        Object starName = info.getObj("starName");
        String starName_ = starName == null ? "" : (String) starName;
        name.put(starID, starName_);
        return starName_;
    }

    public JSONObject getUserInfo(long starID) {
        String s = post(APIUserInfo, String.format("{\"userId\":%d}", starID), getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
            return jsonParser.parseObj(content.getObj("baseUserInfo").toString());

        } else {
            String message = object.getStr("message");
            if (message != null && message.contains("用户不存在")) {
                return null;
            } else {
                logError(starID + message);
            }
        }
        return null;

    }

    public JSONObject getUserArchives(long starID) {
        String s = post(APIUserArchives, String.format("{\"memberId\":%d}", starID), getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            return jsonParser.parseObj(object.getObj("content").toString());

        } else {
            logError(starID + object.getStr("message"));
        }
        return null;
    }

    public String getUserNickName(long id) {
        try {
            return getUserInfo(id).getStr("nickname");
        } catch (Exception e) {
            e.printStackTrace();
            return "null";
        }
    }

    private JSONObject getJumpContent(long starID) {
        String s = post(APIStar2Server, String.format("{\"starId\":%d,\"targetType\":1}", starID), getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            return jsonParser.parseObj(object.getObj("content").toString());

        } else {
            logError(starID + object.getStr("message"));
        }
        return null;
    }

    public long getMainChannelIDByStarID(long starID) {
        JSONObject content = getJumpContent(starID);
        if (content != null) {
            Long id = content.getLong("channelId");
            if (id != null) {
                return id;
            }
        }
        return 0;
    }

    public Long getServerIDByStarID(long starID) {
        JSONObject content = getJumpContent(starID);
        if (content != null) {
            JSONObject serverInfo = jsonParser.parseObj(content.getObj("jumpServerInfo").toString());
            if (serverInfo != null) {
                return serverInfo.getLong("serverId");
            }
        }
        return null;
    }

    public Long[] getChannelIDBySeverID(long serverID) {
        String s = post(APIServer2Channel, String.format("{\"serverId\":\"%d\"}", serverID), getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
            List<Long> rs = new ArrayList<>();
            for (Object room : content.getBeanList("lastMsgList", Object.class)) {
                rs.add(jsonParser.parseObj(room.toString()).getLong("channelId"));
            }
            return rs.toArray(new Long[0]);

        } else {
            logError(serverID + object.getStr("message"));
            return new Long[0];
        }
    }

    public Pocket48RoomInfo getRoomInfoByChannelID(long roomID) {
        try {
            String requestBody = String.format("{\"channelId\":\"%d\"}", roomID);
            String response = post(APIChannel2Server, requestBody, getPocket48Headers());
            JSONObject object = jsonParser.parseObj(response);
            if (object.getInt("status") == 200) {
                JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
                JSONObject roomInfo = jsonParser.parseObj(content.getObj("channelInfo").toString());
                return new Pocket48RoomInfo(roomInfo);

            } else if (object.getInt("status") == 2001
                    && object.getStr("message").indexOf("question") != -1) {
                // 对于加密房间，解析question信息并返回LockedRoomInfo
                JSONObject message = jsonParser.parseObj(object.getObj("message").toString());
                String question = message.getStr("question");
                return new Pocket48RoomInfo.LockedRoomInfo(question + "？",
                        null, roomID);
            } else {
                // 静默处理API错误，避免控制台噪音
            }
        } catch (Exception e) {
            // 静默处理网络异常，避免控制台噪音
        }
        return null;
    }

    public Object[] search(String content_) {
        String s = post(APISearch, String.format("{\"searchContent\":\"%s\"}", content_), getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
            if (content.containsKey("serverApiList")) {
                JSONArray a = content.getJSONArray("serverApiList");
                return a.stream().toArray();
            }

        } else {
            logError(object.getStr("message"));
        }
        return new Object[0];
    }
    
    /**
     * 搜索房间信息
     * @param keyword 搜索关键词
     * @return Pocket48RoomInfo数组
     */
    public Pocket48RoomInfo[] searchRoom(String keyword) {
        try {
            Object[] searchResults = search(keyword);
            if (searchResults == null || searchResults.length == 0) {
                return new Pocket48RoomInfo[0];
            }
            
            List<Pocket48RoomInfo> roomInfoList = new ArrayList<>();
            for (Object result : searchResults) {
                try {
                    JSONObject resultObj = jsonParser.parseObj(result.toString());
                    // 从搜索结果中提取房间信息
                    if (resultObj.containsKey("channelId")) {
                        long channelId = resultObj.getLong("channelId");
                        Pocket48RoomInfo roomInfo = getRoomInfoByChannelID(channelId);
                        if (roomInfo != null) {
                            roomInfoList.add(roomInfo);
                        }
                    }
                } catch (Exception e) {
                    // 忽略单个结果的解析错误，继续处理其他结果
                    logError("解析搜索结果时出错: " + e.getMessage());
                }
            }
            
            return roomInfoList.toArray(new Pocket48RoomInfo[0]);
        } catch (Exception e) {
            logError("搜索房间时出错: " + e.getMessage());
            return new Pocket48RoomInfo[0];
        }
    }

    public String getAnswerNameTo(String answerID, String questionID) {
        String s = post(APIAnswerDetail, String.format("{\"answerId\":\"%s\",\"questionId\":\"%s\"}", answerID, questionID), getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
            return content.getStr("userName");

        } else {
            logError(object.getStr("message"));
        }
        return null;
    }

    /* ----------------------房间类---------------------- */

    
    //异步版本：注意endTime中无key=roomInfo.getRoomId()时此方法返回null
    //采用13位时间戳
    public CompletableFuture<Pocket48Message[]> getMessagesAsync(Pocket48RoomInfo roomInfo, HashMap<Long, Long> endTime) {
        long roomID = roomInfo.getRoomId();
        if (!endTime.containsKey(roomID))
            return CompletableFuture.completedFuture(null);

        return getOriMessagesAsync(roomID, roomInfo.getSeverId()).thenApply(msgs -> {
            if (msgs != null) {
                List<Pocket48Message> rs = new ArrayList<>();
                long latest = 0;
                long currentEndTime = endTime.get(roomID);
                
                // 修复：记录处理的消息时间戳，确保endTime正确更新
                List<Long> processedTimestamps = new ArrayList<>();
                
                for (Object message : msgs) {
                    JSONObject m = jsonParser.parseObj(message.toString());
                    long time = m.getLong("msgTime");

                    // 修复：使用严格的大于比较，避免重复处理相同时间戳的消息
                    if (currentEndTime >= time)
                        continue; // 跳过已处理的消息，而不是break

                    if (latest < time) {
                        latest = time;
                    }
                    
                    processedTimestamps.add(time);
                    rs.add(Pocket48Message.construct(
                            roomInfo,
                            m
                    ));
                }
                
                // 修复：确保endTime更新逻辑的正确性
                if (latest != 0 && !processedTimestamps.isEmpty()) {
                    synchronized(endTime) {
                        // 使用处理的最新消息时间戳更新endTime
                        long maxProcessedTime = Collections.max(processedTimestamps);
                        if (maxProcessedTime > endTime.getOrDefault(roomID, 0L)) {
                            endTime.put(roomID, maxProcessedTime);
                            // 移除正常情况下的时间戳更新日志，减少日志噪音
                        }
                        // 移除正常情况下的调试信息，减少日志噪音
                    }
                }
                // 移除正常情况下的调试信息，减少日志噪音
                return rs.toArray(new Pocket48Message[0]);
            }
            return new Pocket48Message[0];
        });
    }


    
    public CompletableFuture<Pocket48Message[]> getMessagesAsync(long roomID, HashMap<Long, Long> endTime) {
        Pocket48RoomInfo roomInfo = getRoomInfoByChannelID(roomID);
        if (roomInfo != null) {
            return getMessagesAsync(roomInfo, endTime);
        }
        return CompletableFuture.completedFuture(new Pocket48Message[0]);
    }


    
    //异步获取全部消息并整理成Pocket48Message[]
    public CompletableFuture<Pocket48Message[]> getMessagesAsync(Pocket48RoomInfo roomInfo) {
        long roomID = roomInfo.getRoomId();
        return getOriMessagesAsync(roomID, roomInfo.getSeverId()).thenApply(msgs -> {
            if (msgs != null) {
                List<Pocket48Message> rs = new ArrayList<>();
                for (Object message : msgs) {
                    rs.add(Pocket48Message.construct(
                            roomInfo,
                            jsonParser.parseObj(message.toString())
                    ));
                }
                return rs.toArray(new Pocket48Message[0]);
            }
            return new Pocket48Message[0];
        });
    }


    
    public CompletableFuture<Pocket48Message[]> getMessagesAsync(long roomID) {
        Pocket48RoomInfo roomInfo = getRoomInfoByChannelID(roomID);
        if (roomInfo != null) {
            return getMessagesAsync(roomInfo);
        }
        return CompletableFuture.completedFuture(new Pocket48Message[0]);
    }


    
    /**
     * 异步获取房间最新消息的时间戳，用于正确初始化endTime
     * @param roomID 房间ID
     * @param serverID 服务器ID
     * @return 最新消息时间戳，如果没有消息则返回当前时间戳
     */
    public CompletableFuture<Long> getLatestMessageTimeAsync(long roomID, long serverID) {
        return getOriMessagesAsync(roomID, serverID).thenApply(msgs -> {
            if (msgs != null && !msgs.isEmpty()) {
                // 获取第一条消息（已按时间倒序排列）
                JSONObject firstMsg = jsonParser.parseObj(msgs.get(0).toString());
                return firstMsg.getLong("msgTime");
            }
            // 如果没有消息，返回当前时间戳
            return System.currentTimeMillis();
        });
    }




    
    //异步获取未整理的消息（集成快速失败和动态超时优化版）
    private CompletableFuture<List<Object>> getOriMessagesAsync(long roomID, long serverID) {
        // 对于加密房间（serverId为0或负数），尝试从配置中获取serverId
        if (serverID <= 0) {
            if (properties.pocket48_serverID.containsKey(roomID)) {
                serverID = properties.pocket48_serverID.get(roomID);
            } else {
                // 如果配置中没有对应的serverId，返回空列表
                return CompletableFuture.completedFuture(new ArrayList<>());
            }
        }
        
        // 快速失败检查：如果启用快速失败，直接返回
        MonitorConfig config = MonitorConfig.getInstance();
        if (config.isPocket48FastFailEnabled()) {
            // 简化快速失败逻辑，不再进行网络可达性检查
            logger.debug("[快速失败] 口袋48快速失败已启用，房间ID: {}, 服务器ID: {}", roomID, serverID);
        }
        
        // 获取动态超时配置
        DynamicTimeoutManager.Pocket48TimeoutConfig timeoutConfig = timeoutManager.getPocket48DynamicConfig();
        int maxRetries = timeoutConfig.getMaxRetries();
        long baseDelay = timeoutConfig.getRetryDelay();
        
        // 记录开始时间用于性能监控
        long startTime = System.currentTimeMillis();
        
        // 异步重试逻辑
        return attemptRequestAsync(roomID, serverID, timeoutConfig, 0, maxRetries, baseDelay, startTime);
    }
    
    /**
     * 异步重试请求方法
     */
    private CompletableFuture<List<Object>> attemptRequestAsync(long roomID, long serverID, 
                                                               DynamicTimeoutManager.Pocket48TimeoutConfig timeoutConfig,
                                                               int attempt, int maxRetries, long baseDelay, long startTime) {
        // 优化：添加更多请求参数以提高API响应速度
        String requestBody = String.format(
            "{\"nextTime\":0,\"serverId\":%d,\"channelId\":%d,\"limit\":30,\"order\":1,\"needTop\":false}", 
            serverID, roomID
        );
        
        // 使用异步HTTP请求
        return httpClient.postWithTimeoutAsync(APIMsgOwner, requestBody, getPocket48Headers(), 
                timeoutConfig.getConnectTimeout(), timeoutConfig.getReadTimeout())
            .thenCompose(response -> {
                try {
                    JSONObject object = jsonParser.parseObj(response);
                    
                    if (object.getInt("status") == 200) {
                        JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
                        List<Object> out = content.getBeanList("message", Object.class);
                        // 优化：使用更高效的排序方式
                        out.sort((a, b) -> {
                            long timeA = jsonParser.parseObj(a.toString()).getLong("msgTime");
                            long timeB = jsonParser.parseObj(b.toString()).getLong("msgTime");
                            return Long.compare(timeB, timeA);
                        });
                        
                        // 记录成功请求的性能数据（仅在异常情况下显示）
                        long duration = System.currentTimeMillis() - startTime;
                        if (duration > 500 || attempt > 0) {
                            logger.warn("[性能监控] 口袋48消息获取异常 - 房间ID: {}, 耗时: {}ms, 尝试次数: {}", 
                                roomID, duration, attempt + 1);
                        }
                        
                        return CompletableFuture.completedFuture(out);
                    } else {
                        String errorMsg = String.format("[Pocket48Handler] API错误 - 房间ID: %d, 服务器ID: %d, 状态码: %d, 尝试: %d/%d", 
                            roomID, serverID, object.getInt("status"), attempt + 1, maxRetries);
                        
                        if (attempt >= maxRetries - 1) {
                            logError(errorMsg + " - 最终失败");
                            return CompletableFuture.completedFuture(null);
                        } else {
                            logger.warn("{} - 准备重试", errorMsg);
                            // 异步延迟重试
                            return CompletableFuture.runAsync(() -> {
                                try {
                                    Thread.sleep((long)(baseDelay * (attempt + 1)));
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }).thenCompose(v -> attemptRequestAsync(roomID, serverID, timeoutConfig, attempt + 1, maxRetries, baseDelay, startTime));
                        }
                    }
                } catch (Exception e) {
                    String errorMsg = String.format("[Pocket48Handler] 解析异常 - 房间ID: %d, 服务器ID: %d, 尝试: %d/%d, 错误: %s", 
                        roomID, serverID, attempt + 1, maxRetries, e.getMessage());
                    
                    if (attempt >= maxRetries - 1) {
                        logError(errorMsg + " - 最终失败");
                        return CompletableFuture.completedFuture(null);
                    } else {
                        logger.warn("{} - 准备重试", errorMsg);
                        // 异步延迟重试
                        return CompletableFuture.runAsync(() -> {
                            try {
                                Thread.sleep((long)(baseDelay * (attempt + 1)));
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        }).thenCompose(v -> attemptRequestAsync(roomID, serverID, timeoutConfig, attempt + 1, maxRetries, baseDelay, startTime));
                    }
                }
            })
            .exceptionally(throwable -> {
                String errorMsg = String.format("[Pocket48Handler] 网络异常 - 房间ID: %d, 服务器ID: %d, 尝试: %d/%d, 错误: %s", 
                    roomID, serverID, attempt + 1, maxRetries, throwable.getMessage());
                
                if (attempt >= maxRetries - 1) {
                    logError(errorMsg + " - 最终失败");
                    long duration = System.currentTimeMillis() - startTime;
                    logger.error("[性能监控] 口袋48消息获取失败 - 房间ID: {}, 总耗时: {}ms, 总尝试次数: {}", 
                        roomID, duration, maxRetries);
                    return null;
                } else {
                    logger.warn("{} - 准备重试", errorMsg);
                    // 异步延迟重试
                    try {
                        return CompletableFuture.runAsync(() -> {
                            try {
                                Thread.sleep((long)(baseDelay * (attempt + 1)));
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        }).thenCompose(v -> attemptRequestAsync(roomID, serverID, timeoutConfig, attempt + 1, maxRetries, baseDelay, startTime))
                            .join();
                    } catch (Exception ex) {
                        return null;
                    }
                }
            });
    }

    public List<Long> getRoomVoiceList(long roomID, long serverID) {
        // 对于加密房间（serverId为0或负数），尝试从配置中获取serverId
        if (serverID <= 0) {
            if (properties.pocket48_serverID.containsKey(roomID)) {
                serverID = properties.pocket48_serverID.get(roomID);
            } else {
                // 如果配置中没有对应的serverId，返回空列表
                return new ArrayList<>();
            }
        }
        
        try {
            String requestBody = String.format("{\"channelId\":%d,\"serverId\":%d,\"operateCode\":2}", roomID, serverID);
            String s = post(APIRoomVoice, requestBody, getPocket48Headers());
            JSONObject object = jsonParser.parseObj(s);
            
            if (object.getInt("status") == 200) {
                JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
                JSONArray a = content.getJSONArray("voiceUserList");
                List<Long> l = new ArrayList<>();
                if (a.size() > 0) {
                    for (Object star_ : a.stream().toArray()) {
                        JSONObject star = jsonParser.parseObj(star_.toString());
                        long starID = star.getLong("userId");
                        l.add(starID);

                        //优化：names的另一种添加途径
                        if (!name.containsKey(starID)) {
                            name.put(starID, star.getStr("nickname"));
                        }
                    }
                    return l;
                }
                return new ArrayList<>(); // 返回空列表而不是静态列表

            } else {
                logError("[API错误] 获取语音列表失败，房间ID: " + roomID + ", 服务器ID: " + serverID + ", 状态码: " + object.getInt("status") + ", 错误信息: " + object.getStr("message"));
            }
        } catch (Exception e) {
            logError("[网络异常] 获取语音列表失败，房间ID: " + roomID + ", 服务器ID: " + serverID + ", 异常: " + e.getMessage());
        }
        return voidRoomVoiceList;
    }

    /* ----------------------直播类---------------------- */
    public List<Object> getLiveList() {
        String s = post(APILiveList, "{\"groupId\":0,\"debug\":true,\"next\":0,\"record\":false}", getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
            return content.getBeanList("liveList", Object.class);

        } else {
            logError(object.getStr("message"));
        }
        return null;

    }

    public List<Object> getRecordList() {
        String s = post(APILiveList, "{\"groupId\":0,\"debug\":true,\"next\":0,\"record\":true}", getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
            return content.getBeanList("liveList", Object.class);

        } else {
            logError(object.getStr("message"));
        }
        return null;

    }

}
