package net.luffy.handler;

import net.luffy.Newboy;
import net.luffy.util.Properties;
import net.luffy.util.UnifiedHttpClient;

import java.io.InputStream;
import java.util.Map;

/**
 * 同步Web处理器基类
 * 使用hutool的HTTP客户端提供同步HTTP处理能力
 * 替代异步库以解决兼容性问题
 */
public class SyncWebHandler {

    public final Properties properties;
    private String cronScheduleID;
    
    // 默认超时时间 - 优化为3秒，确保快速响应
    private static final int DEFAULT_TIMEOUT = 3000; // 3秒

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
            return net.luffy.util.UnifiedHttpClient.getInstance().get(url);
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
            return net.luffy.util.UnifiedHttpClient.getInstance().post(url, body);
        } catch (Exception e) {
            throw new RuntimeException("POST请求失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送带请求头的GET请求
     * 使用统一HTTP客户端支持自定义Headers
     * @param url 请求URL
     * @param headers 请求头
     * @return 响应内容
     */
    protected String get(String url, Map<String, String> headers) {
        try {
            return UnifiedHttpClient.getInstance().get(url, headers);
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
    protected String post(String url, Map<String, String> headers, String body) {
        try {
            return UnifiedHttpClient.getInstance().post(url, body, headers);
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
            return net.luffy.util.UnifiedHttpClient.getInstance().getInputStream(url);
        } catch (Exception e) {
            throw new RuntimeException("获取输入流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 记录信息日志
     * @param message 日志消息
     */
    protected void logInfo(String message) {
        // 仅在错误时输出日志，info级别日志已禁用
        // Newboy.INSTANCE.getLogger().info(message);
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