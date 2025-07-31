package net.luffy.handler;

import net.luffy.util.Properties;
import java.util.HashMap;
import java.util.Map;

public class Pocket48HandlerHeader {

    private final Properties properties;
    private final String pa = "MTc1MzkwNTc1MjAwMCw1NDEsRjMyNTZGRDYwMDg4ODAzNzZFQjUyNjYyQkQyRUM2QTAs";
    private final String version = "7.1.36";
    private String token;

    public Pocket48HandlerHeader(Properties properties) {
        this.properties = properties;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    private Map<String, String> setDefaultHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json;charset=utf-8");
        headers.put("Host", "pocketapi.48.cn");
        headers.put("pa", pa);
        headers.put("User-Agent", "PocketFans201807/7.1.36 (iPhone; iOS 26.0; Scale/2.00)");
        headers.put("appInfo", String.format("{\"vendor\":\"apple\",\"deviceId\":\"8D6DDD0B-2233-4622-89AA-AABB14D4F37B\",\"appVersion\":\"%s\",\"appBuild\":\"25072401\",\"osVersion\":\"26.0.0\",\"osType\":\"ios\",\"deviceName\":\"iPhone 11\",\"os\":\"ios\"}", version));
        
        // 添加缺少的重要请求头以提高API响应速度
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "zh-Hans-SG;q=1");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Connection", "keep-alive");
        headers.put("P-Sign-Type", "V0");
        
        return headers;
    }

    public Map<String, String> getLoginHeaders() {
        return setDefaultHeaders();
    }

    public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>(setDefaultHeaders());
        headers.put("token", token);
        return headers;
    }

}
