package net.lawaxi.handler;

import cn.hutool.http.HttpRequest;
import net.lawaxi.util.Properties;

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

    private HttpRequest setDefaultHeader(HttpRequest request) {
        return
                request.header("Content-Type", "application/json;charset=utf-8")
                        .header("Host", "pocketapi.48.cn")
                        .header("pa", pa)
                        .header("User-Agent", "PocketFans201807/7.1.34 (iPhone; iOS 19.0; Scale/2.00)")
                        .header("appInfo", String.format("{\"vendor\":\"apple\",\"deviceId\":\"8D6DDD0B-2233-4622-89AA-AABB14D4F37B\",\"appVersion\":\"%s\",\"appBuild\":\"25060602\",\"osVersion\":\"19.0\",\"osType\":\"ios\",\"deviceName\":\"iPhone 11\",\"os\":\"ios\"}", version));
    }

    public HttpRequest setLoginHeader(HttpRequest request) {
        return setDefaultHeader(request);
    }

    public HttpRequest setHeader(HttpRequest request) {
        return setDefaultHeader(request)
                .header("token", token);
    }

}
