package net.luffy.util.sender;

import net.luffy.handler.AsyncWebHandlerBase;
import net.luffy.util.UnifiedHttpClient;
import net.luffy.util.UnifiedLogger;
import net.luffy.util.Pocket48MetricsCollector;
import net.luffy.util.delay.UnifiedDelayService;
import net.luffy.handler.Pocket48Handler;
import net.luffy.Newboy;
import net.luffy.util.AdaptiveThreadPoolManager;
import net.luffy.util.StringMatchUtils;
// OkHttp imports removed - migrated to UnifiedHttpClient

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.net.URL;
import java.net.MalformedURLException;

/**
 * 口袋48资源处理器
 * 专门处理口袋48的视频、音频、图片等资源下载
 * 解决认证头缺失导致的资源无法访问问题
 */
public class Pocket48ResourceHandler extends AsyncWebHandlerBase {
    
    // 统一日志和指标收集
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final Pocket48MetricsCollector metricsCollector = Pocket48MetricsCollector.getInstance();
    private final UnifiedDelayService unifiedDelayService = UnifiedDelayService.getInstance();
    
    // 域名记忆化：记录不支持HEAD请求的域名
    private static final Set<String> HEAD_UNSUPPORTED_DOMAINS = new ConcurrentSkipListSet<>();
    // 域名HEAD请求成功记录（用于重新尝试之前失败的域名）
    private static final ConcurrentHashMap<String, Long> HEAD_SUCCESS_DOMAINS = new ConcurrentHashMap<>();
    // 域名重新检查间隔（24小时）
    private static final long DOMAIN_RECHECK_INTERVAL = 24 * 60 * 60 * 1000L;
    
    // 使用统一的自适应线程池管理器，替代独立的I/O线程池
    private static final AdaptiveThreadPoolManager THREAD_POOL_MANAGER = AdaptiveThreadPoolManager.getInstance();
    
    // Content-Type白名单
    private static final String[] ALLOWED_IMAGE_TYPES = {
        "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp"
    };
    private static final String[] ALLOWED_AUDIO_TYPES = {
        "audio/mpeg", "audio/mp3", "audio/amr", "audio/wav", "audio/ogg"
    };
    private static final String[] ALLOWED_VIDEO_TYPES = {
        "video/mp4", "video/mpeg", "video/quicktime", "video/x-msvideo"
    };
    
    /**
     * 获取口袋48资源的输入流（带缓存支持）
     * 添加必要的请求头以确保资源能够正常访问
     * 
     * @param url 资源URL
     * @return 资源输入流
     * @throws RuntimeException 当请求失败时抛出
     */
    public InputStream getPocket48InputStream(String url) {
        try {
            // 首先检查缓存
            Pocket48ResourceCache cache = Pocket48ResourceCache.getInstance();
            File cachedFile = cache.getCachedFile(url);
            if (cachedFile != null) {
                return new FileInputStream(cachedFile);
            }
            
            // 缓存未命中，从网络获取
            // 为了避免资源泄漏，我们下载到临时文件然后返回文件流
            // 根据URL推断文件扩展名，避免使用.tmp后缀
            String fileExtension = getFileExtensionFromUrl(url);
            File tempFile = downloadToTempFileInternal(url, fileExtension);
            return new FileInputStream(tempFile);
        } catch (IOException e) {
            throw new RuntimeException("获取口袋48资源流失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 从URL推断文件扩展名
     * @param url 资源URL
     * @return 文件扩展名
     */
    private String getFileExtensionFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return ".jpg"; // 默认为图片格式
        }
        
        // 移除查询参数
        String cleanUrl = url.split("\\?")[0];
        
        // 获取文件名部分
        int lastSlash = cleanUrl.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < cleanUrl.length() - 1) {
            String fileName = cleanUrl.substring(lastSlash + 1);
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0 && lastDot < fileName.length() - 1) {
                return fileName.substring(lastDot);
            }
        }
        
        // 根据URL路径推断类型
        if (StringMatchUtils.isImageUrl(url)) {
            return ".jpg";
        } else if (StringMatchUtils.isVideoUrl(url)) {
            return ".mp4";
        } else if (StringMatchUtils.isAudioUrl(url)) {
            return ".amr";
        }
        
        // 对于口袋48的资源，默认为图片格式
        return ".jpg";
    }
    
    /**
     * 从URL中提取域名
     * @param url 完整URL
     * @return 域名，如果解析失败返回null
     */
    private String extractDomain(String url) {
        try {
            URL urlObj = new URL(url);
            return urlObj.getHost();
        } catch (MalformedURLException e) {
            logger.warn("Pocket48ResourceHandler", "无法解析URL域名: " + url);
            return null;
        }
    }
    
    /**
     * 检查域名是否应该跳过HEAD请求
     * @param domain 域名
     * @return 是否应该跳过HEAD请求
     */
    private boolean shouldSkipHeadRequest(String domain) {
        if (domain == null) {
            return false;
        }
        
        // 检查是否在不支持HEAD的域名列表中
        if (HEAD_UNSUPPORTED_DOMAINS.contains(domain)) {
            // 检查是否需要重新尝试（24小时后重新检查）
            Long lastSuccess = HEAD_SUCCESS_DOMAINS.get(domain);
            if (lastSuccess != null && (System.currentTimeMillis() - lastSuccess) < DOMAIN_RECHECK_INTERVAL) {
                return true;
            } else {
                // 超过重新检查间隔，从黑名单中移除，重新尝试
                HEAD_UNSUPPORTED_DOMAINS.remove(domain);
                HEAD_SUCCESS_DOMAINS.remove(domain);
                logger.debug("Pocket48ResourceHandler", "域名重新检查间隔已过，重新尝试HEAD请求: " + domain);
                return false;
            }
        }
        
        return false;
    }
    
    /**
     * 记录域名HEAD请求失败
     * @param domain 域名
     */
    private void recordHeadRequestFailure(String domain) {
        if (domain != null) {
            HEAD_UNSUPPORTED_DOMAINS.add(domain);
            logger.debug("Pocket48ResourceHandler", "域名添加到HEAD不支持列表: " + domain);
            metricsCollector.recordError("head_unsupported_domain_added");
        }
    }
    
    /**
     * 记录域名HEAD请求成功
     * @param domain 域名
     */
    private void recordHeadRequestSuccess(String domain) {
        if (domain != null) {
            HEAD_SUCCESS_DOMAINS.put(domain, System.currentTimeMillis());
            // 如果之前在黑名单中，移除它
            if (HEAD_UNSUPPORTED_DOMAINS.remove(domain)) {
                logger.debug("Pocket48ResourceHandler", "域名从HEAD不支持列表中移除: " + domain);
                metricsCollector.recordError("head_unsupported_domain_removed");
            }
        }
    }
    
    /**
     * 检查资源可用性（HEAD探测失败再GET，支持域名记忆化跳过）
     * 添加Content-Type白名单检查
     * 
     * @param url 资源URL
     * @return 资源信息
     */
    public Pocket48ResourceInfo checkResourceAvailability(String url) {
        long startTime = System.currentTimeMillis();
        
        try {
            UnifiedHttpClient client = UnifiedHttpClient.getInstance();
            java.util.Map<String, String> headers = getPocket48Headers();
            
            Pocket48ResourceInfo info = new Pocket48ResourceInfo();
            info.setUrl(url);
            
            // 提取域名用于记忆化判断
            String domain = extractDomain(url);
            boolean skipHead = shouldSkipHeadRequest(domain);
            
            // 根据域名记忆化决定是否跳过HEAD请求
            if (!skipHead) {
                // 首先尝试HEAD请求
                try {
                UnifiedHttpClient.HttpResponse headResponse = client.head(url, headers);
                int statusCode = headResponse.getStatusCode();
                
                if (statusCode == 200) {
                    String contentType = headResponse.getHeaders().get("Content-Type");
                    
                    // Content-Type白名单检查
                    if (isContentTypeAllowed(contentType, url)) {
                        // 记录HEAD请求成功
                        recordHeadRequestSuccess(domain);
                        
                        info.setAvailable(true);
                        info.setStatusCode(statusCode);
                        info.setContentType(contentType);
                        info.setContentLength(headResponse.getHeaders().get("Content-Length"));
                        
                        long duration = System.currentTimeMillis() - startTime;
                        metricsCollector.recordDownloadSuccess(duration);
                        logger.debug("Pocket48ResourceHandler", "HEAD探测成功: " + url + ", Content-Type: " + contentType);
                        return info;
                    } else {
                        logger.warn("Pocket48ResourceHandler", "Content-Type不在白名单中: " + contentType + ", URL: " + url);
                        metricsCollector.recordError("content_type_not_allowed");
                        info.setAvailable(false);
                        info.setErrorMessage("Content-Type不被允许: " + contentType);
                        return info;
                    }
                } else if (statusCode >= 400 && statusCode < 500) {
                    // 4xx客户端错误，不重试
                    logger.warn("Pocket48ResourceHandler", "HEAD请求客户端错误: " + statusCode + ", URL: " + url);
                    metricsCollector.recordError("client_error_" + statusCode);
                    info.setAvailable(false);
                    info.setStatusCode(statusCode);
                    info.setErrorMessage("客户端错误: " + statusCode);
                    return info;
                } else {
                    // 其他错误，记录HEAD请求失败并尝试GET请求
                    recordHeadRequestFailure(domain);
                    logger.debug("Pocket48ResourceHandler", "HEAD请求失败(" + statusCode + ")，尝试GET请求: " + url);
                }
                } catch (Exception headException) {
                    // HEAD请求异常，记录失败并尝试GET请求
                    recordHeadRequestFailure(domain);
                    logger.debug("Pocket48ResourceHandler", "HEAD请求异常，尝试GET请求: " + url + ", 异常: " + headException.getMessage());
                }
            } else {
                // 跳过HEAD请求，直接使用GET
                logger.debug("Pocket48ResourceHandler", "域名在HEAD不支持列表中，跳过HEAD请求直接GET: " + url);
                metricsCollector.recordError("head_request_skipped");
            }
            
            // HEAD失败，尝试GET请求（只获取头部信息）
            try {
                UnifiedHttpClient.HttpResponse getResponse = client.get(url, headers);
                int statusCode = getResponse.getStatusCode();
                
                if (statusCode == 200) {
                    String contentType = getResponse.getHeaders().get("Content-Type");
                    
                    // Content-Type白名单检查
                    if (isContentTypeAllowed(contentType, url)) {
                        info.setAvailable(true);
                        info.setStatusCode(statusCode);
                        info.setContentType(contentType);
                        info.setContentLength(getResponse.getHeaders().get("Content-Length"));
                        
                        long duration = System.currentTimeMillis() - startTime;
                        metricsCollector.recordDownloadSuccess(duration);
                        logger.debug("Pocket48ResourceHandler", "GET探测成功: " + url + ", Content-Type: " + contentType);
                        return info;
                    } else {
                        logger.warn("Pocket48ResourceHandler", "Content-Type不在白名单中: " + contentType + ", URL: " + url);
                        metricsCollector.recordError("content_type_not_allowed");
                        info.setAvailable(false);
                        info.setErrorMessage("Content-Type不被允许: " + contentType);
                        return info;
                    }
                } else {
                    // 区分4xx和5xx错误
                    String errorType = statusCode >= 400 && statusCode < 500 ? "client_error" : "server_error";
                    logger.warn("Pocket48ResourceHandler", "GET请求失败: " + statusCode + ", URL: " + url);
                    metricsCollector.recordError(errorType + "_" + statusCode);
                    
                    info.setAvailable(false);
                    info.setStatusCode(statusCode);
                    info.setErrorMessage(errorType + ": " + statusCode);
                    return info;
                }
            } catch (Exception getException) {
                logger.error("Pocket48ResourceHandler", "GET请求也失败: " + url, getException);
                metricsCollector.recordError("request_failed");
                
                info.setAvailable(false);
                info.setErrorMessage("请求失败: " + getException.getMessage());
                return info;
            }
            
        } catch (Exception e) {
            logger.error("Pocket48ResourceHandler", "资源可用性检查失败: " + url, e);
            metricsCollector.recordError("availability_check_failed");
            
            Pocket48ResourceInfo info = new Pocket48ResourceInfo();
            info.setUrl(url);
            info.setAvailable(false);
            info.setErrorMessage("检查失败: " + e.getMessage());
            return info;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordLatency(duration);
        }
    }
    
    /**
     * 检查Content-Type是否在白名单中
     * @param contentType HTTP响应的Content-Type
     * @param url 资源URL（用于推断类型）
     * @return 是否允许
     */
    private boolean isContentTypeAllowed(String contentType, String url) {
        if (contentType == null || contentType.trim().isEmpty()) {
            // 没有Content-Type时，根据URL推断
            return inferTypeFromUrl(url);
        }
        
        String normalizedType = contentType.toLowerCase().trim();
        
        // 检查图片类型
        for (String allowedType : ALLOWED_IMAGE_TYPES) {
            if (normalizedType.startsWith(allowedType)) {
                return true;
            }
        }
        
        // 检查音频类型
        for (String allowedType : ALLOWED_AUDIO_TYPES) {
            if (normalizedType.startsWith(allowedType)) {
                return true;
            }
        }
        
        // 检查视频类型
        for (String allowedType : ALLOWED_VIDEO_TYPES) {
            if (normalizedType.startsWith(allowedType)) {
                return true;
            }
        }
        
        // 通用类型（可能是资源服务器返回的通用类型）
        if (normalizedType.startsWith("application/octet-stream") || 
            normalizedType.startsWith("binary/octet-stream")) {
            // 根据URL推断是否允许
            return inferTypeFromUrl(url);
        }
        
        return false;
    }
    
    /**
     * 根据URL推断资源类型是否允许
     * @param url 资源URL
     * @return 是否允许
     */
    private boolean inferTypeFromUrl(String url) {
        if (url == null) {
            return false;
        }
        
        String lowerUrl = url.toLowerCase();
        
        // 图片资源路径特征
        if (StringMatchUtils.isImageUrl(url)) {
            return true;
        }
        
        // 音频资源路径特征
        if (StringMatchUtils.isAudioUrl(url)) {
            return true;
        }
        
        // 视频资源路径特征
        if (StringMatchUtils.isVideoUrl(url)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 获取口袋48专用的请求头
     * 从Pocket48Handler统一注入token和UA
     * 
     * @return 请求头Map
     */
    private java.util.Map<String, String> getPocket48Headers() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        
        try {
            // 从Pocket48Handler获取认证信息
            Pocket48Handler pocket48Handler = Newboy.INSTANCE.getHandlerPocket48();
            if (pocket48Handler != null) {
                // 获取token（如果有的话）
                String token = pocket48Handler.getToken();
                if (token != null && !token.trim().isEmpty()) {
                    headers.put("token", token);
                    headers.put("Authorization", "Bearer " + token);
                }
                
                // 获取User-Agent
                String userAgent = pocket48Handler.getUserAgent();
                if (userAgent != null && !userAgent.trim().isEmpty()) {
                    headers.put("User-Agent", userAgent);
                } else {
                    // 默认User-Agent
                    headers.put("User-Agent", "PocketFans201807/7.1.34 (iPhone; iOS 19.0; Scale/2.00)");
                }
            } else {
                // 默认User-Agent
                headers.put("User-Agent", "PocketFans201807/7.1.34 (iPhone; iOS 19.0; Scale/2.00)");
                logger.warn("Pocket48ResourceHandler", "Pocket48Handler未初始化，使用默认请求头");
            }
        } catch (Exception e) {
            logger.error("Pocket48ResourceHandler", "获取Pocket48认证信息失败，使用默认请求头", e);
            headers.put("User-Agent", "PocketFans201807/7.1.34 (iPhone; iOS 19.0; Scale/2.00)");
        }
        
        // 重要：添加Referer头，某些资源需要此头信息
        headers.put("Referer", "https://pocketapi.48.cn/");
        // 接受所有类型的内容
        headers.put("Accept", "*/*");
        // 语言偏好
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        // 连接保持
        headers.put("Connection", "keep-alive");
        // 缓存控制
        headers.put("Cache-Control", "no-cache");
        // 编码支持
        headers.put("Accept-Encoding", "gzip, deflate, br");
        
        return headers;
    }
    
    /**
     * 获取口袋48资源输入流（带重试机制）- 异步版本
     * 
     * @param url 资源URL
     * @param maxRetries 最大重试次数
     * @return CompletableFuture<InputStream> 异步输入流
     */
    public java.util.concurrent.CompletableFuture<InputStream> getPocket48InputStreamWithRetryAsync(String url, int maxRetries) {
        return getPocket48InputStreamWithRetryAsyncInternal(url, maxRetries, 0, System.currentTimeMillis(), null);
    }
    
    /**
     * 获取口袋48资源输入流（带动态重试机制）- 异步版本
     * 
     * @param url 资源URL
     * @return CompletableFuture<InputStream> 异步输入流
     */
    public java.util.concurrent.CompletableFuture<InputStream> getPocket48InputStreamWithDynamicRetryAsync(String url) {
        net.luffy.util.DynamicTimeoutManager timeoutManager = net.luffy.util.DynamicTimeoutManager.getInstance();
        int dynamicMaxRetries = timeoutManager.getDynamicRetries(3);
        return getPocket48InputStreamWithRetryAsyncInternal(url, dynamicMaxRetries, 0, System.currentTimeMillis(), null);
    }
    
    /**
     * 获取口袋48资源输入流（带重试机制）- 同步版本（保持向后兼容）
     * 
     * @param url 资源URL
     * @param maxRetries 最大重试次数
     * @return 输入流
     * @throws RuntimeException 当获取失败时抛出
     */
    public InputStream getPocket48InputStreamWithRetry(String url, int maxRetries) {
        try {
            return getPocket48InputStreamWithRetryAsync(url, maxRetries).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get input stream: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取口袋48资源输入流（带动态重试机制）- 同步版本
     * 
     * @param url 资源URL
     * @return 输入流
     * @throws RuntimeException 当获取失败时抛出
     */
    public InputStream getPocket48InputStreamWithDynamicRetry(String url) {
        try {
            net.luffy.util.DynamicTimeoutManager timeoutManager = net.luffy.util.DynamicTimeoutManager.getInstance();
            net.luffy.util.DynamicTimeoutManager.Pocket48TimeoutConfig config = timeoutManager.getPocket48DynamicConfig();
            
            return getPocket48InputStreamWithDynamicRetryAsync(url).get(config.getReadTimeout(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get input stream with dynamic retry: " + e.getMessage(), e);
        }
    }
    
    /**
     * 异步重试获取输入流的内部实现
     */
    private java.util.concurrent.CompletableFuture<InputStream> getPocket48InputStreamWithRetryAsyncInternal(
            String url, int maxRetries, int attempt, long startTime, Exception lastException) {
        
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                // 使用动态超时管理器获取超时时间
                net.luffy.util.DynamicTimeoutManager timeoutManager = net.luffy.util.DynamicTimeoutManager.getInstance();
                
                // 快速失败检查
                if (timeoutManager.shouldFastFail()) {
                    logger.warn("Pocket48ResourceHandler", "URL标记为快速失败: " + url);
                    metricsCollector.recordError("fast_fail");
                    throw new RuntimeException("URL marked for fast fail: " + url);
                }
                
                logger.debug("Pocket48ResourceHandler", "尝试获取资源流，第" + (attempt + 1) + "次: " + url);
                InputStream stream = getPocket48InputStream(url);
                
                // 成功时记录
                long duration = System.currentTimeMillis() - startTime;
                metricsCollector.recordDownloadSuccess(duration);
                metricsCollector.recordLatency(duration);
                logger.debug("Pocket48ResourceHandler", "成功获取资源流: " + url + ", 耗时: " + duration + "ms");
                
                return stream;
                
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).handle((result, throwable) -> {
            if (throwable == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            }
            
            Exception currentException = (Exception) throwable.getCause();
            
            // 记录失败
            metricsCollector.recordRetry();
            
            // 分析异常类型，判断是否应该重试
            boolean shouldRetry = shouldRetryOnException(currentException, attempt, maxRetries);
            
            if (!shouldRetry) {
                logger.warn("Pocket48ResourceHandler", "遇到不可重试的错误，停止重试: " + url + ", 错误: " + currentException.getMessage());
                metricsCollector.recordError("non_retryable_error");
                return java.util.concurrent.CompletableFuture.<InputStream>failedFuture(currentException);
            }
            
            if (attempt < maxRetries) {
                // 使用动态超时管理器获取动态重试延迟
                net.luffy.util.DynamicTimeoutManager timeoutManager = net.luffy.util.DynamicTimeoutManager.getInstance();
                long backoffMs = timeoutManager.getDynamicRetryDelay(attempt);
                
                logger.debug("Pocket48ResourceHandler", "第" + (attempt + 1) + "次尝试失败，" + backoffMs + "ms后重试: " + url + ", 错误: " + currentException.getMessage());
                
                // 异步延迟后递归重试
                return unifiedDelayService.delayAsync((int)backoffMs).thenCompose(v -> 
                    getPocket48InputStreamWithRetryAsyncInternal(url, maxRetries, attempt + 1, startTime, currentException)
                );
            } else {
                logger.error("Pocket48ResourceHandler", "达到最大重试次数，获取资源流失败: " + url, currentException);
                
                metricsCollector.recordError("max_retries_exceeded");
                long totalDuration = System.currentTimeMillis() - startTime;
                metricsCollector.recordLatency(totalDuration);
                
                RuntimeException finalException = new RuntimeException(
                    "Failed to get input stream after " + (maxRetries + 1) + " attempts, total time: " + totalDuration + "ms", 
                    currentException
                );
                return java.util.concurrent.CompletableFuture.<InputStream>failedFuture(finalException);
            }
        }).thenCompose(future -> future);
    }
    
    /**
     * 判断异常是否应该重试
     * @param exception 异常
     * @param attempt 当前尝试次数
     * @param maxRetries 最大重试次数
     * @return 是否应该重试
     */
    private boolean shouldRetryOnException(Exception exception, int attempt, int maxRetries) {
        if (attempt >= maxRetries) {
            return false;
        }
        
        String message = exception.getMessage();
        if (message == null) {
            message = "";
        }
        
        // 使用StringMatchUtils优化错误消息检查
        if (StringMatchUtils.isClientError(message)) {
            metricsCollector.recordError("client_error_no_retry");
            return false;
        }
        
        if (StringMatchUtils.isRetryableError(message)) {
            metricsCollector.recordError("retryable_error");
            return true;
        }
        
        // 默认情况下重试（保守策略）
        metricsCollector.recordError("unknown_error_retry");
        return true;
    }
    
    /**
     * 下载口袋48资源到本地临时文件（带缓存支持）- 异步版本
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名（如 ".mp4", ".jpg"）
     * @return CompletableFuture<File> 异步本地临时文件
     */
    public java.util.concurrent.CompletableFuture<File> downloadToTempFileAsync(String url, String fileExtension) {
        // 异步检查缓存，避免阻塞主线程
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Pocket48ResourceCache cache = Pocket48ResourceCache.getInstance();
            return cache.getCachedFile(url);
        }, THREAD_POOL_MANAGER.getExecutorService()).thenCompose(cachedFile -> {
            if (cachedFile != null) {
                return java.util.concurrent.CompletableFuture.completedFuture(cachedFile);
            }
            
            // 缓存未命中，异步下载文件
            return downloadToTempFileWithRetryAsync(url, fileExtension, 3);
        }).orTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .exceptionally(throwable -> {
            if (throwable instanceof java.util.concurrent.TimeoutException) {
                throw new RuntimeException("下载超时: " + url, throwable);
            } else {
                throw new RuntimeException("下载失败: " + throwable.getMessage(), throwable);
            }
        });
    }
    
    /**
     * 下载口袋48资源到本地临时文件（带动态重试和缓存支持）- 异步版本
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名（如 ".mp4", ".jpg"）
     * @return CompletableFuture<File> 异步本地临时文件
     */
    public java.util.concurrent.CompletableFuture<File> downloadToTempFileWithDynamicRetryAsync(String url, String fileExtension) {
        // 异步检查缓存，避免阻塞主线程
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Pocket48ResourceCache cache = Pocket48ResourceCache.getInstance();
            return cache.getCachedFile(url);
        }, THREAD_POOL_MANAGER.getExecutorService()).thenCompose(cachedFile -> {
            if (cachedFile != null) {
                return java.util.concurrent.CompletableFuture.completedFuture(cachedFile);
            }
            
            // 缓存未命中，使用动态重试次数异步下载文件
            net.luffy.util.DynamicTimeoutManager timeoutManager = net.luffy.util.DynamicTimeoutManager.getInstance();
            int dynamicMaxRetries = timeoutManager.getDynamicRetries(3);
            net.luffy.util.DynamicTimeoutManager.Pocket48TimeoutConfig config = timeoutManager.getPocket48DynamicConfig();
            
            return downloadToTempFileWithRetryAsync(url, fileExtension, dynamicMaxRetries);
        }).orTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .exceptionally(throwable -> {
            if (throwable instanceof java.util.concurrent.TimeoutException) {
                throw new RuntimeException("下载超时: " + url, throwable);
            } else {
                throw new RuntimeException("下载失败: " + throwable.getMessage(), throwable);
            }
        });
    }
    
    /**
     * 下载口袋48资源到本地临时文件（带缓存支持）- 同步版本（保持向后兼容）
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名（如 ".mp4", ".jpg"）
     * @return 本地临时文件
     * @throws RuntimeException 当下载失败时抛出
     */
    public File downloadToTempFile(String url, String fileExtension) {
        try {
            return downloadToTempFileAsync(url, fileExtension).get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file: " + e.getMessage(), e);
        }
    }
    
    /**
     * 下载口袋48资源到本地临时文件（带动态重试和缓存支持）- 同步版本
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名（如 ".mp4", ".jpg"）
     * @return 本地临时文件
     * @throws RuntimeException 当下载失败时抛出
     */
    public File downloadToTempFileWithDynamicRetry(String url, String fileExtension) {
        try {
            net.luffy.util.DynamicTimeoutManager timeoutManager = net.luffy.util.DynamicTimeoutManager.getInstance();
            net.luffy.util.DynamicTimeoutManager.Pocket48TimeoutConfig config = timeoutManager.getPocket48DynamicConfig();
            
            return downloadToTempFileWithDynamicRetryAsync(url, fileExtension).get(config.getReadTimeout(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to download file with dynamic retry: " + e.getMessage(), e);
        }
    }
    
    /**
     * 带重试机制下载口袋48资源到本地临时文件 - 异步版本
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名（如 ".mp4", ".jpg"）
     * @param maxRetries 最大重试次数
     * @return CompletableFuture<File> 异步本地临时文件
     */
    public java.util.concurrent.CompletableFuture<File> downloadToTempFileWithRetryAsync(String url, String fileExtension, int maxRetries) {
        return downloadToTempFileWithRetryAsyncInternal(url, fileExtension, maxRetries, 0, null);
    }
    
    /**
     * 带重试机制下载口袋48资源到本地临时文件 - 同步版本（保持向后兼容）
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名（如 ".mp4", ".jpg"）
     * @param maxRetries 最大重试次数
     * @return 本地临时文件
     * @throws RuntimeException 当下载失败时抛出
     */
    public File downloadToTempFileWithRetry(String url, String fileExtension, int maxRetries) {
        try {
            return downloadToTempFileWithRetryAsync(url, fileExtension, maxRetries).get(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("下载重试 " + maxRetries + " 次后仍然失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 异步重试下载的内部实现
     */
    private java.util.concurrent.CompletableFuture<File> downloadToTempFileWithRetryAsyncInternal(
            String url, String fileExtension, int maxRetries, int attempt, Exception lastException) {
        
        return downloadToTempFileInternalAsync(url, fileExtension).handle((result, throwable) -> {
            if (throwable == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(result);
            }
            
            Exception currentException = (Exception) throwable.getCause();
            
            if (attempt < maxRetries) {
                // 使用动态超时管理器获取动态重试延迟
                net.luffy.util.DynamicTimeoutManager timeoutManager = net.luffy.util.DynamicTimeoutManager.getInstance();
                long waitTime = timeoutManager.getDynamicRetryDelay(attempt);
                
                logger.debug("Pocket48ResourceHandler", "第" + (attempt + 1) + "次下载失败，" + waitTime + "ms后重试: " + url + ", 错误: " + currentException.getMessage());
                
                // 异步延迟后递归重试
                return unifiedDelayService.delayAsync((int)waitTime).thenCompose(v -> 
                    downloadToTempFileWithRetryAsyncInternal(url, fileExtension, maxRetries, attempt + 1, currentException)
                );
            } else {
                RuntimeException finalException = new RuntimeException(
                    "下载重试 " + maxRetries + " 次后仍然失败: " + currentException.getMessage(), 
                    currentException
                );
                return java.util.concurrent.CompletableFuture.<File>failedFuture(finalException);
            }
        }).thenCompose(future -> future);
    }
    

    
    /**
     * 内部下载方法（集成缓存，增强版）- 异步版本
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名
     * @return CompletableFuture<File> 异步本地临时文件
     */
    private java.util.concurrent.CompletableFuture<File> downloadToTempFileInternalAsync(String url, String fileExtension) {
        // 使用动态超时配置
        net.luffy.util.DynamicTimeoutManager timeoutManager = net.luffy.util.DynamicTimeoutManager.getInstance();
        net.luffy.util.DynamicTimeoutManager.Pocket48TimeoutConfig config = timeoutManager.getPocket48DynamicConfig();
        
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return downloadToTempFileInternal(url, fileExtension);
            } catch (Exception e) {
                throw new RuntimeException("下载内部错误: " + e.getMessage() + ", URL: " + url, e);
            }
        }, THREAD_POOL_MANAGER.getExecutorService()).orTimeout(config.getReadTimeout(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .exceptionally(throwable -> {
            if (throwable instanceof java.util.concurrent.TimeoutException) {
                throw new RuntimeException("单次下载超时(" + config.getReadTimeout() + "ms): " + url, throwable);
            } else {
                throw new RuntimeException(throwable.getMessage(), throwable);
            }
        });
    }
    
    /**
     * 内部下载方法（集成缓存，增强版）- 同步版本（保持向后兼容）
     * 
     * @param url 资源URL
     * @param fileExtension 文件扩展名
     * @return 本地临时文件
     * @throws IOException 当下载失败时抛出
     */
    private File downloadToTempFileInternal(String url, String fileExtension) throws IOException {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger(this.getClass().getName());
        logger.setUseParentHandlers(false); // 不在控制台显示日志
        
        logger.info("开始下载资源: " + url);
        
        Pocket48ResourceCache cache = Pocket48ResourceCache.getInstance();
        
        // 使用UnifiedHttpClient下载文件并直接缓存
        try (InputStream inputStream = UnifiedHttpClient.getInstance().getInputStreamWithHeaders(url, getPocket48Headers())) {
            if (inputStream == null) {
                throw new IOException("获取输入流失败，可能是网络问题或资源不存在: " + url);
            }
            
            // 使用BufferedInputStream提高读取性能
            try (java.io.BufferedInputStream bufferedStream = new java.io.BufferedInputStream(inputStream, 8192)) {
                // 直接从流缓存文件
                File cachedFile = cache.cacheFromStream(url, bufferedStream, fileExtension);
                if (cachedFile != null && cachedFile.exists() && cachedFile.length() > 0) {
                    logger.info("资源下载并缓存成功: " + url + ", 文件大小: " + cachedFile.length() + " bytes");
                    
                    // 验证下载的文件是否为有效图片（如果是图片文件）
                    if (fileExtension != null && (fileExtension.toLowerCase().contains("jpg") || 
                        fileExtension.toLowerCase().contains("jpeg") || 
                        fileExtension.toLowerCase().contains("png") || 
                        fileExtension.toLowerCase().contains("gif"))) {
                        
                        // 验证图片完整性
                        if (!net.luffy.util.ImageFormatDetector.validateImageIntegrity(cachedFile)) {
                            logger.warning("下载的图片文件可能损坏: " + cachedFile.getAbsolutePath());
                            // 不抛出异常，让上层处理
                        }
                    }
                    
                    return cachedFile;
                } else {
                    throw new IOException("缓存文件失败或文件为空: " + url);
                }
            }
        } catch (Exception e) {
            logger.severe("下载失败: " + e.getMessage() + ", URL: " + url);
            throw new IOException("下载失败: " + e.getMessage() + " " + url, e);
        }
    }
    
    /**
     * 获取域名记忆化统计信息
     * @return 统计信息字符串
     */
    public String getDomainMemorizationStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("HEAD不支持域名数量: ").append(HEAD_UNSUPPORTED_DOMAINS.size());
        stats.append(", HEAD成功域名数量: ").append(HEAD_SUCCESS_DOMAINS.size());
        
        if (!HEAD_UNSUPPORTED_DOMAINS.isEmpty()) {
            stats.append(", 不支持HEAD的域名: ").append(HEAD_UNSUPPORTED_DOMAINS);
        }
        
        return stats.toString();
    }
    
    /**
     * 清理过期的域名记录（用于内存管理）
     */
    public void cleanupExpiredDomainRecords() {
        long currentTime = System.currentTimeMillis();
        HEAD_SUCCESS_DOMAINS.entrySet().removeIf(entry -> 
            (currentTime - entry.getValue()) > DOMAIN_RECHECK_INTERVAL * 2); // 保留2倍间隔的记录
        
        logger.debug("Pocket48ResourceHandler", "清理过期域名记录完成，当前记录数: " + HEAD_SUCCESS_DOMAINS.size());
    }
    
    /**
     * 口袋48资源信息类
     */
    public static class Pocket48ResourceInfo {
        private String url;
        private boolean available;
        private int statusCode;
        private String contentType;
        private String contentLength;
        private String errorMessage;
        
        // Getters and Setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        
        public boolean isAvailable() { return available; }
        public void setAvailable(boolean available) { this.available = available; }
        
        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
        
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        
        public String getContentLength() { return contentLength; }
        public void setContentLength(String contentLength) { this.contentLength = contentLength; }
        
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        
        @Override
        public String toString() {
            return String.format("Pocket48ResourceInfo{url='%s', available=%s, statusCode=%d, contentType='%s', contentLength='%s', errorMessage='%s'}",
                    url, available, statusCode, contentType, contentLength, errorMessage);
        }
    }
}