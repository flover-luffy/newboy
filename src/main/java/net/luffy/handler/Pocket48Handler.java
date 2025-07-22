package net.luffy.handler;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import net.luffy.util.UnifiedJsonParser;
// OkHttp imports removed - migrated to UnifiedHttpClient
import net.luffy.model.Pocket48Message;
import net.luffy.model.Pocket48RoomInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Pocket48Handler extends AsyncWebHandlerBase {

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

    public Pocket48Handler() {
        super();
        this.header = new Pocket48HandlerHeader(properties);
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

    // 获取Pocket48专用请求头（优化版）
    protected java.util.Map<String, String> getPocket48Headers() {
        // 直接使用header类的方法，避免重复设置
        if (header.getToken() != null) {
            return header.getHeaders();
        } else {
            return header.getLoginHeaders();
        }
    }

    public String getBalance() {
        try {
            String url = "https://pocketapi.48.cn/user/api/v1/user/info/pfid";
            
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("token", header.getToken());
            headers.put("User-Agent", "PocketFans201807/6.0.16 (iPhone; iOS 13.5.1; Scale/2.00)");
            headers.put("Accept", "application/json");
            headers.put("Accept-Language", "zh-Hans-CN;q=1");
            
            String response = get(url, headers);
            
            JSONObject jsonResponse = jsonParser.parseObj(response);
            if (jsonResponse.getInt("status") == 200) {
                JSONObject content = jsonResponse.getJSONObject("content");
                return content.getStr("pfid", "0");
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
            logError(starID + object.getStr("message"));
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
        String s = post(APIChannel2Server, String.format("{\"channelId\":\"%d\"}", roomID), getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
            JSONObject roomInfo = jsonParser.parseObj(content.getObj("channelInfo").toString());
            return new Pocket48RoomInfo(roomInfo);

        } else if (object.getInt("status") == 2001
                && object.getStr("message").indexOf("question") != -1) { //只有配置中存有severID的加密房间会被解析
            JSONObject message = jsonParser.parseObj(object.getObj("message").toString());
            return new Pocket48RoomInfo.LockedRoomInfo(message.getStr("question") + "？",
                    properties.pocket48_serverID.get(roomID), roomID);
        } else {
            logError(roomID + object.getStr("message"));
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
    //获取最新消息并整理成Pocket48Message[]
    //注意endTime中无key=roomInfo.getRoomId()时此方法返回null
    //采用13位时间戳
    public Pocket48Message[] getMessages(Pocket48RoomInfo roomInfo, HashMap<Long, Long> endTime) {
        long roomID = roomInfo.getRoomId();
        if (!endTime.containsKey(roomID))
            return null;

        List<Object> msgs = getOriMessages(roomID, roomInfo.getSeverId());
        if (msgs != null) {
            List<Pocket48Message> rs = new ArrayList<>();
            long latest = 0;
            for (Object message : msgs) {
                JSONObject m = jsonParser.parseObj(message.toString());
                long time = m.getLong("msgTime");

                if (endTime.get(roomID) >= time)
                    break; //api有时间次序

                if (latest < time) {
                    latest = time;
                }

                rs.add(Pocket48Message.construct(
                        roomInfo,
                        m
                ));
            }
            if (latest != 0)
                endTime.put(roomID, latest);
            return rs.toArray(new Pocket48Message[0]);
        }
        return new Pocket48Message[0];
    }

    public Pocket48Message[] getMessages(long roomID, HashMap<Long, Long> endTime) {
        Pocket48RoomInfo roomInfo = getRoomInfoByChannelID(roomID);
        if (roomInfo != null) {
            return getMessages(roomInfo, endTime);
        }
        return new Pocket48Message[0];
    }

    //获取全部消息并整理成Pocket48Message[]
    public Pocket48Message[] getMessages(Pocket48RoomInfo roomInfo) {
        long roomID = roomInfo.getRoomId();
        List<Object> msgs = getOriMessages(roomID, roomInfo.getSeverId());
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
    }

    public Pocket48Message[] getMessages(long roomID) {
        Pocket48RoomInfo roomInfo = getRoomInfoByChannelID(roomID);
        if (roomInfo != null) {
            return getMessages(roomInfo);
        }

        return new Pocket48Message[0];
    }

    //获取未整理的消息（优化版）
    private List<Object> getOriMessages(long roomID, long serverID) {
        // 优化：添加更多请求参数以提高API响应速度
        String requestBody = String.format(
            "{\"nextTime\":0,\"serverId\":%d,\"channelId\":%d,\"limit\":30,\"order\":1,\"needTop\":false}", 
            serverID, roomID
        );
        
        String s = post(APIMsgOwner, requestBody, getPocket48Headers());
        JSONObject object = jsonParser.parseObj(s);

        if (object.getInt("status") == 200) {
            JSONObject content = jsonParser.parseObj(object.getObj("content").toString());
            List<Object> out = content.getBeanList("message", Object.class);
            // 优化：使用更高效的排序方式
            out.sort((a, b) -> {
                long timeA = jsonParser.parseObj(a.toString()).getLong("msgTime");
                long timeB = jsonParser.parseObj(b.toString()).getLong("msgTime");
                return Long.compare(timeB, timeA);
            });
            return out;

        } else {
            logError(roomID + object.getStr("message"));

        }
        return null;
    }

    public List<Long> getRoomVoiceList(long roomID, long serverID) {
        String s = post(APIRoomVoice, String.format("{\"channelId\":%d,\"serverId\":%d,\"operateCode\":2}", roomID, serverID), getPocket48Headers());
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

        } else {
            logError(roomID + object.getStr("message"));
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
