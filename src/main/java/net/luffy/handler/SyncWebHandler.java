package net.luffy.handler;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import net.luffy.Newboy;
import net.luffy.util.Properties;

import java.io.InputStream;

/**
 * 同步Web处理器基类
 * 使用hutool的HTTP客户端提供同步HTTP处理能力
 * 替代异步库以解决兼容性问题
 */
public class SyncWebHandler {

    public final Properties properties;
    private String cronScheduleID;
    
    // 默认超时时间
    private static final int DEFAULT_TIMEOUT = 30000; // 30秒

    public SyncWebHandler() {
        this.properties = Newboy.INSTANCE.getProperties();
    }

    /**
     * 发送GET请求
     * @param url 请求URL
     * @return 响应内容
     */
    protected String get(String url) {
        try {
            HttpResponse response = HttpRequest.get(url)
                    .timeout(DEFAULT_TIMEOUT)
                    .execute();
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("GET请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送POST请求
     * @param url 请求URL
     * @param body 请求体
     * @return 响应内容
     */
    protected String post(String url, String body) {
        try {
            HttpResponse response = HttpRequest.post(url)
                    .body(body)
                    .timeout(DEFAULT_TIMEOUT)
                    .execute();
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("POST请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送带请求头的GET请求
     * @param url 请求URL
     * @param headers 请求头
     * @return 响应内容
     */
    protected String get(String url, java.util.Map<String, String> headers) {
        try {
            HttpRequest request = HttpRequest.get(url).timeout(DEFAULT_TIMEOUT);
            if (headers != null) {
                for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                    request.header(header.getKey(), header.getValue());
                }
            }
            HttpResponse response = request.execute();
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("GET请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送带请求头的POST请求
     * @param url 请求URL
     * @param headers 请求头
     * @param body 请求体
     * @return 响应内容
     */
    protected String post(String url, java.util.Map<String, String> headers, String body) {
        try {
            HttpRequest request = HttpRequest.post(url)
                    .body(body)
                    .timeout(DEFAULT_TIMEOUT);
            if (headers != null) {
                for (java.util.Map.Entry<String, String> header : headers.entrySet()) {
                    request.header(header.getKey(), header.getValue());
                }
            }
            HttpResponse response = request.execute();
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("POST请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取资源输入流
     * @param url 资源URL
     * @return 输入流
     */
    protected InputStream getInputStream(String url) {
        try {
            return HttpRequest.get(url)
                    .timeout(DEFAULT_TIMEOUT)
                    .execute()
                    .bodyStream();
        } catch (Exception e) {
            throw new RuntimeException("获取输入流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 记录信息日志
     * @param message 日志消息
     */
    protected void logInfo(String message) {
        // 使用Mirai日志系统而非控制台输出
        Newboy.INSTANCE.getLogger().info(message);
    }

    /**
     * 记录错误日志
     * @param message 日志消息
     * @param throwable 异常
     */
    protected void logError(String message, Throwable throwable) {
        // 使用Mirai日志系统而非控制台输出
        if (throwable != null) {
            Newboy.INSTANCE.getLogger().error(message, throwable);
        } else {
            Newboy.INSTANCE.getLogger().error(message);
        }
    }

    /**
     * 获取定时任务ID
     * @return 定时任务ID
     */
    public String getCronScheduleID() {
        return cronScheduleID;
    }

    /**
     * 设置定时任务ID
     * @param cronScheduleID 定时任务ID
     */
    public void setCronScheduleID(String cronScheduleID) {
        this.cronScheduleID = cronScheduleID;
    }
}