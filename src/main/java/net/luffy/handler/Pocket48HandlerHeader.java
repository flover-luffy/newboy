package net.luffy.handler;

import net.luffy.util.Properties;
import java.util.HashMap;
import java.util.Map;

public class Pocket48HandlerHeader {

    private final Properties properties;
    private final String pa = "MTc1MTg5NTgzMjAwMCwzODMzLEQ3ODVBRENBM0U3QTkzRDVFNTJCMjVDQUJDRUY4NDczLA==";
    private final String version = "7.1.34";
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
        headers.put("User-Agent", "PocketFans201807/7.1.34 (iPhone; iOS 19.0; Scale/2.00)");
        headers.put("appInfo", String.format("{\"vendor\":\"apple\",\"deviceId\":\"8D6DDD0B-2233-4622-89AA-AABB14D4F37B\",\"appVersion\":\"%s\",\"appBuild\":\"25060602\",\"osVersion\":\"19.0\",\"osType\":\"ios\",\"deviceName\":\"iPhone 11\",\"os\":\"ios\"}", version));
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
