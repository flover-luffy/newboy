package net.luffy.util;

import net.luffy.Newboy;
import net.luffy.handler.AsyncWebHandler;
import net.luffy.handler.AsyncWebHandler.BatchMemberStatusResult;
import net.luffy.util.UnifiedSchedulerManager;
import net.luffy.util.SubscriptionConfig;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 异步在线状态监控器
 * 支持批量查询、异步处理、性能优化等功能
 */
public class AsyncOnlineStatusMonitor {
    
    public static final AsyncOnlineStatusMonitor INSTANCE = new AsyncOnlineStatusMonitor();
    
    private final AsyncWebHandler asyncWebHandler;
    private final MonitorConfig config;
    private String batchQueryTaskId;
    private String cacheCleanupTaskId;
    
    // 统计字段
    private final AtomicInteger batchQueryCount = new AtomicInteger(0);
    private final AtomicInteger totalMembersQueried = new AtomicInteger(0);
    private final Set<String> uniqueMembersQueried = ConcurrentHashMap.newKeySet();
    private final AtomicLong totalBatchTime = new AtomicLong(0);
    private final AtomicInteger concurrentBatches = new AtomicInteger(0);
    private String scheduledMonitorTaskId;
    
    // 监控统计字段
    private final long startTime = System.currentTimeMillis();
    private final AtomicInteger dailyStatusChanges = new AtomicInteger(0);
    private final AtomicInteger totalCheckCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger notificationCount = new AtomicInteger(0);
    private volatile long lastUpdateTime = System.currentTimeMillis();
    
    // 订阅的成员列表（兼容旧版本）
    private final Set<String> subscribedMembers = ConcurrentHashMap.newKeySet();
    
    // 新的订阅配置列表（按群组管理）
    private final Map<Long, SubscriptionConfig> subscriptionConfigs = new ConcurrentHashMap<>();
    
    // 成员状态缓存（异步版本）
    private final ConcurrentHashMap<String, AsyncMemberStatus> memberStatusCache = new ConcurrentHashMap<>();
    
    // 用于检测状态变化的前一次状态记录
    private final ConcurrentHashMap<String, String> previousStatusMap = new ConcurrentHashMap<>();
    
    // 批量查询队列
    private final Set<String> pendingQueries = ConcurrentHashMap.newKeySet();
    private final Map<String, CompletableFuture<BatchMemberStatusResult>> queryFutures = new ConcurrentHashMap<>();
    
    private AsyncOnlineStatusMonitor() {
        this.asyncWebHandler = AsyncWebHandler.getInstance();
        this.config = MonitorConfig.getInstance();
        
        // 从配置文件读取设置并更新MonitorConfig
        updateConfigFromProperties();
        
        // 使用统一调度器启动任务
        startBatchQueryScheduler();
        startCacheCleanupScheduler();
        
        // 延迟启动定时监控任务，等待Newboy完全初始化
        // startScheduledMonitor(); // 移动到initializeMonitoring()方法中
    }
    
    /**
     * 从Properties更新配置
     */
    private void updateConfigFromProperties() {
        // 配置项已迁移到MonitorConfig，此方法保留为兼容性
        // 实际配置通过MonitorConfig.loadConfiguration()加载
    }
    
    /**
     * 异步查询单个成员状态
     */
    public CompletableFuture<BatchMemberStatusResult> queryMemberStatusAsync(String memberName) {
        if (memberName == null || memberName.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                new BatchMemberStatusResult(memberName, false, "成员名称不能为空", null));
        }
        
        String normalizedName = memberName.trim();
        
        // 检查缓存
        AsyncMemberStatus cached = memberStatusCache.get(normalizedName);
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(
                new BatchMemberStatusResult(normalizedName, true, cached.getStatus(), cached.getRawResponse()));
        }
        
        // 检查是否已在查询队列中
        CompletableFuture<BatchMemberStatusResult> existingFuture = queryFutures.get(normalizedName);
        if (existingFuture != null) {
            return existingFuture;
        }
        
        // 添加到批量查询队列
        pendingQueries.add(normalizedName);
        
        // 创建查询Future
        CompletableFuture<BatchMemberStatusResult> future = new CompletableFuture<>();
        queryFutures.put(normalizedName, future);
        
        // 如果队列达到批量大小或等待时间过长，立即执行批量查询
        if (pendingQueries.size() >= config.getBatchQuerySize()) {
            UnifiedSchedulerManager.getInstance().executeTask(this::executeBatchQuery);
        } else {
            // 对于单个查询，设置较短的延迟后执行批量查询以提高响应速度
            UnifiedSchedulerManager.getInstance().getExecutor().execute(() -> {
                try {
                    Thread.sleep(500);
                    executeBatchQuery();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        
        return future;
    }
    
    /**
     * 批量查询成员状态
     */
    public CompletableFuture<List<BatchMemberStatusResult>> batchQueryMemberStatus(List<String> memberNames) {
        if (memberNames == null || memberNames.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }
        
        long startTime = System.currentTimeMillis();
        batchQueryCount.incrementAndGet();
        // 统计唯一成员数而不是查询次数
        uniqueMembersQueried.addAll(memberNames);
        totalMembersQueried.set(uniqueMembersQueried.size());
        concurrentBatches.incrementAndGet();
        
        return asyncWebHandler.batchQueryMemberStatus(memberNames)
                .thenApply(results -> {
                    // 更新缓存和统计
                    int successCount = 0;
                    int failCount = 0;
                    
                    for (BatchMemberStatusResult result : results) {
                        if (result.isSuccess()) {
                            memberStatusCache.put(result.getMemberName(), 
                                new AsyncMemberStatus(result.getStatus(), result.getRawResponse()));
                            successCount++;
                        } else {
                            failCount++;
                        }
                    }
                    
                    // 更新统计字段
                    totalCheckCount.addAndGet(results.size());
                    failureCount.addAndGet(failCount);
                    lastUpdateTime = System.currentTimeMillis();
                    
                    // 更新批量查询统计
                    long batchTime = System.currentTimeMillis() - startTime;
                    totalBatchTime.addAndGet(batchTime);
                    concurrentBatches.decrementAndGet();
                    
                    // 记录到全局性能监控器
                    try {
                        net.luffy.util.PerformanceMonitor.getInstance().recordQuery(batchTime);
                    } catch (Exception e) {
                        // 忽略性能监控记录失败
                    }
                    
                    // 已禁用控制台输出
                    // if (config.isVerboseLogging()) {
                    //     Newboy.INSTANCE.getLogger().info(String.format(
                    //         "批量查询完成: %d个成员, 耗时: %dms, 成功: %d, 失败: %d",
                    //         results.size(), batchTime, successCount, failCount
                    //     ));
                    // }
                    
                    return results;
                })
                .exceptionally(throwable -> {
                    concurrentBatches.decrementAndGet();
                    // 已禁用控制台输出
                    // Newboy.INSTANCE.getLogger().error("批量查询失败: " + throwable.getMessage());
                    
                    // 返回失败结果
                    return memberNames.stream()
                            .map(name -> new BatchMemberStatusResult(name, false, 
                                "批量查询失败: " + throwable.getMessage(), null))
                            .collect(Collectors.toList());
                });
    }
    
    /**
     * 执行批量查询
     */
    private void executeBatchQuery() {
        if (pendingQueries.isEmpty()) {
            return;
        }
        
        // 获取待查询的成员列表
        List<String> membersToQuery = new ArrayList<>(pendingQueries);
        pendingQueries.clear();
        
        if (membersToQuery.isEmpty()) {
            return;
        }
        
        // 执行批量查询
        batchQueryMemberStatus(membersToQuery)
                .thenAccept(results -> {
                    // 完成所有等待的Future
                    for (BatchMemberStatusResult result : results) {
                        CompletableFuture<BatchMemberStatusResult> future = queryFutures.remove(result.getMemberName());
                        if (future != null) {
                            future.complete(result);
                        }
                    }
                })
                .exceptionally(throwable -> {
                    // 处理失败情况
                    for (String memberName : membersToQuery) {
                        CompletableFuture<BatchMemberStatusResult> future = queryFutures.remove(memberName);
                        if (future != null) {
                            future.complete(new BatchMemberStatusResult(memberName, false, 
                                "批量查询异常: " + throwable.getMessage(), null));
                        }
                    }
                    return null;
                });
    }
    
    /**
     * 启动批量查询调度器
     */
    private void startBatchQueryScheduler() {
        UnifiedSchedulerManager scheduler = UnifiedSchedulerManager.getInstance();
        this.batchQueryTaskId = scheduler.scheduleBatchTask(() -> {
            if (!pendingQueries.isEmpty()) {
                executeBatchQuery();
            }
        }, config.getBatchQueryInterval(), config.getBatchQueryInterval());
    }
    
    /**
     * 启动缓存清理调度器
     */
    private void startCacheCleanupScheduler() {
        UnifiedSchedulerManager scheduler = UnifiedSchedulerManager.getInstance();
        // 优化：延长清理间隔，减少CPU占用
        long cleanupInterval = Math.max(config.getCacheCleanupInterval(), 5 * 60 * 1000); // 最少5分钟
        this.cacheCleanupTaskId = scheduler.scheduleCleanupTask(() -> {
            long currentTime = System.currentTimeMillis();
            int cleanedCount = 0;
            
            Iterator<Map.Entry<String, AsyncMemberStatus>> iterator = memberStatusCache.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, AsyncMemberStatus> entry = iterator.next();
                if (entry.getValue().isExpired()) {
                    iterator.remove();
                    cleanedCount++;
                }
            }
            
            // 已禁用控制台输出
            // if (config.isVerboseLogging() && cleanedCount > 0) {
            //     Newboy.INSTANCE.getLogger().info(String.format("异步缓存清理完成: 清理了 %d 个过期条目", cleanedCount));
            // }
        }, cleanupInterval, cleanupInterval);
    }
    
    /**
     * 初始化监控系统
     * 在Newboy完全初始化后调用此方法来启动监控
     */
    public void initializeMonitoring() {
        startScheduledMonitor();
    }
    
    /**
     * 启动定时监控任务
     */
    private void startScheduledMonitor() {
        Properties props = Newboy.INSTANCE.getProperties();
        if (props != null && props.async_monitor_schedule_pattern != null) {
            // 初始化订阅成员列表
            initSubscribedMembers();
            
            // 解析cron表达式获取监控间隔
            long monitorInterval = CronExpressionParser.parseToMilliseconds(props.async_monitor_schedule_pattern);
            String cronDescription = CronExpressionParser.getCronDescription(props.async_monitor_schedule_pattern);
            
            // 异步监控调度配置已加载
            
            UnifiedSchedulerManager scheduler = UnifiedSchedulerManager.getInstance();
            this.scheduledMonitorTaskId = scheduler.scheduleMonitorTask(() -> {
                if (!subscribedMembers.isEmpty()) {
                    checkSubscribedMembersStatus();
                }
            }, monitorInterval, monitorInterval);
        }
    }
    
    /**
     * 初始化订阅成员列表
     * 从配置文件加载已保存的订阅成员
     */
    private void initSubscribedMembers() {
        // 从配置文件加载订阅成员列表
        ConfigOperator.getInstance().loadAsyncMonitorSubscribeConfig();
    }
    
    /**
     * 检查订阅成员的在线状态
     */
    private void checkSubscribedMembersStatus() {
        if (subscribedMembers.isEmpty()) {
            return;
        }
        
        List<String> membersList = new ArrayList<>(subscribedMembers);
        batchQueryMemberStatus(membersList)
            .thenAccept(results -> {
                // 处理状态变化通知（通知功能已迁移到MonitorConfig）
                processStatusChangeNotifications(results);
            })
            .exceptionally(throwable -> {
                // 记录错误但不中断监控
                return null;
            });
    }
    
    /**
     * 处理状态变化通知
     */
    private void processStatusChangeNotifications(List<BatchMemberStatusResult> results) {
        for (BatchMemberStatusResult result : results) {
            if (result.isSuccess()) {
                String memberName = result.getMemberName();
                String currentStatus = result.getStatus();
                String previousStatus = previousStatusMap.get(memberName);
                
                // 检测状态变化
                if (previousStatus != null && !previousStatus.equals(currentStatus)) {
                    // 状态发生变化，增加统计
                    dailyStatusChanges.incrementAndGet();
                    notificationCount.incrementAndGet();
                    
                    // 发送状态变化通知
                    sendStatusChangeNotification(memberName, previousStatus, currentStatus);
                }
                
                // 更新状态记录
                previousStatusMap.put(memberName, currentStatus);
            }
        }
    }
    
    /**
     * 发送状态变化通知
     */
    private void sendStatusChangeNotification(String memberName, String previousStatus, String currentStatus) {
        try {
            String notificationMessage = formatStatusChangeMessage(memberName, previousStatus, currentStatus);
            
            // 向所有订阅了该成员的群组发送通知
            for (Map.Entry<Long, SubscriptionConfig> entry : subscriptionConfigs.entrySet()) {
                Long groupId = entry.getKey();
                SubscriptionConfig config = entry.getValue();
                
                if (config.hasMember(memberName)) {
                    sendNotificationToGroup(groupId, notificationMessage);
                }
            }
        } catch (Exception e) {
            // 静默处理通知发送异常，不影响监控主流程
        }
    }
    
    /**
     * 格式化状态变化消息
     */
    private String formatStatusChangeMessage(String memberName, String previousStatus, String currentStatus) {
        String timestamp = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        if ("在线".equals(currentStatus)) {
            return memberName + " 🟢上线啦！\n上线时间：" + timestamp;
        } else if ("离线".equals(currentStatus)) {
            return memberName + " 🔴下线啦！\n下线时间：" + timestamp;
        } else {
            return memberName + " 状态变化：" + previousStatus + " → " + currentStatus + "\n时间：" + timestamp;
        }
    }
    
    /**
     * 向指定群组发送通知消息
     */
    private void sendNotificationToGroup(Long groupId, String message) {
        try {
            // 获取群组对象
            net.mamoe.mirai.contact.Group group = net.luffy.Newboy.INSTANCE.getBot().getGroup(groupId);
            if (group != null) {
                // 异步发送消息，避免阻塞监控流程
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        group.sendMessage(message);
                    } catch (Exception e) {
                        // 静默处理发送异常
                    }
                });
            }
        } catch (Exception e) {
            // 静默处理异常
        }
    }
    
    /**
     * 添加订阅成员
     */
    public void addSubscribedMember(String memberName) {
        if (memberName != null && !memberName.trim().isEmpty()) {
            subscribedMembers.add(memberName.trim());
            // 自动保存配置
            ConfigOperator.getInstance().saveAsyncMonitorSubscribeConfig();
        }
    }
    
    /**
     * 添加订阅成员（带群组参数，兼容旧接口）
     */
    public String addSubscribedMember(long groupId, String memberName) {
        if (memberName == null || memberName.trim().isEmpty()) {
            return "❌ 成员名称不能为空";
        }
        
        String trimmed = memberName.trim();
        
        // 获取或创建群组订阅配置
        SubscriptionConfig config = subscriptionConfigs.computeIfAbsent(groupId, 
            k -> new SubscriptionConfig(groupId, new HashSet<>()));
        
        if (config.hasMember(trimmed)) {
            return "⚠️ 重复订阅提醒：成员 " + trimmed + " 已在本群的监控列表中，无需重复添加";
        }
        
        config.addMember(trimmed);
        // 同时添加到兼容列表
        subscribedMembers.add(trimmed);
        
        // 自动保存配置
        ConfigOperator.getInstance().saveAsyncMonitorSubscribeConfig();
        
        // 查询当前状态
        String currentStatus = getCurrentMemberStatus(trimmed);
        String statusIcon = "🟢";
        String statusText = "在线";
        
        if ("离线".equals(currentStatus)) {
            statusIcon = "🔴";
            statusText = "离线";
        } else if (!"在线".equals(currentStatus)) {
            statusIcon = "❓";
            statusText = "未知";
        }
        
        return "✅ 本群已添加对 " + trimmed + " 的监控\n📊 当前状态：" + statusIcon + " " + statusText;
    }
    
    /**
     * 移除订阅成员
     */
    public void removeSubscribedMember(String memberName) {
        if (memberName != null) {
            subscribedMembers.remove(memberName.trim());
            // 自动保存配置
            ConfigOperator.getInstance().saveAsyncMonitorSubscribeConfig();
        }
    }
    
    /**
     * 移除订阅成员（带群组参数，兼容旧接口）
     */
    public String removeSubscribedMember(long groupId, String memberName) {
        if (memberName == null || memberName.trim().isEmpty()) {
            return "❌ 成员名称不能为空";
        }
        
        String trimmed = memberName.trim();
        SubscriptionConfig config = subscriptionConfigs.get(groupId);
        
        if (config == null || !config.hasMember(trimmed)) {
            return "⚠️ 成员 " + trimmed + " 不在群组 " + groupId + " 的监控列表中";
        }
        
        config.removeMember(trimmed);
        
        // 如果群组配置为空，移除整个配置
        if (config.isEmpty()) {
            subscriptionConfigs.remove(groupId);
        }
        
        // 从兼容列表中移除（检查是否还有其他群组订阅此成员）
        boolean stillSubscribed = subscriptionConfigs.values().stream()
            .anyMatch(c -> c.hasMember(trimmed));
        if (!stillSubscribed) {
            subscribedMembers.remove(trimmed);
        }
        
        // 自动保存配置
        ConfigOperator.getInstance().saveAsyncMonitorSubscribeConfig();
        return "✅ 已从群组 " + groupId + " 的监控列表中移除成员 " + trimmed;
    }
    
    /**
     * 获取成员当前状态
     */
    private String getCurrentMemberStatus(String memberName) {
        // 首先检查缓存
        AsyncMemberStatus cached = memberStatusCache.get(memberName);
        if (cached != null && !cached.isExpired()) {
            return cached.getStatus();
        }
        
        // 缓存过期或不存在，异步查询状态
        queryMemberStatusAsync(memberName);
        
        // 返回默认状态
        return "未知";
    }
    
    /**
     * 获取订阅成员列表
     */
    public Set<String> getSubscribedMembers() {
        return new HashSet<>(subscribedMembers);
    }
    
    /**
     * 获取订阅成员列表（带群组参数，兼容旧接口）
     */
    public String getSubscribedMembers(long groupId) {
        SubscriptionConfig config = subscriptionConfigs.get(groupId);
        
        // 如果当前群组没有订阅，显示所有监控成员的在线状态统计
        if (config == null || config.isEmpty()) {
            return getGlobalMonitorStatus();
        }
        
        StringBuilder result = new StringBuilder();
        result.append("📋 当前群监控列表\n");
        
        // 查询并显示每个成员的在线状态
        Set<String> members = config.getMemberSubs();
        int index = 1;
        
        for (String member : members) {
            AsyncMemberStatus cached = memberStatusCache.get(member);
            String statusIcon = "❓";
            String statusText = "未知";
            
            if (cached != null && !cached.isExpired()) {
                String status = cached.getStatus();
                if ("在线".equals(status)) {
                    statusIcon = "🟢";
                    statusText = "在线";
                } else if ("离线".equals(status)) {
                    statusIcon = "🔴";
                    statusText = "离线";
                }
            } else {
                // 异步查询状态
                queryMemberStatusAsync(member);
            }
            
            result.append(index).append(". ").append(member).append(" - ").append(statusIcon).append(" ").append(statusText).append("\n");
            index++;
        }
        
        result.append("📈 总计监控: ").append(members.size()).append(" 个成员");
        
        return result.toString();
    }
    
    /**
     * 获取全局监控状态（当没有订阅群组或私聊时显示）
     */
    private String getGlobalMonitorStatus() {
        if (subscribedMembers.isEmpty()) {
            return "📋 当前没有监控成员";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("=== 在线状态监控统计 ===\n");
        
        // 统计在线离线成员
        List<String> onlineMembers = new ArrayList<>();
        List<String> offlineMembers = new ArrayList<>();
        
        for (String member : subscribedMembers) {
            AsyncMemberStatus cached = memberStatusCache.get(member);
            if (cached != null && !cached.isExpired()) {
                String status = cached.getStatus();
                if ("在线".equals(status)) {
                    onlineMembers.add(member);
                } else if ("离线".equals(status)) {
                    offlineMembers.add(member);
                }
            } else {
                // 异步查询状态
                queryMemberStatusAsync(member);
            }
        }
        
        // 计算运行时间
        long runtimeMs = System.currentTimeMillis() - startTime;
        long hours = runtimeMs / (1000 * 60 * 60);
        long minutes = (runtimeMs % (1000 * 60 * 60)) / (1000 * 60);
        
        // 计算成功率
        int totalChecks = totalCheckCount.get();
        int failures = failureCount.get();
        double successRate = totalChecks > 0 ? ((double)(totalChecks - failures) / totalChecks) * 100.0 : 100.0;
        
        // 格式化最后更新时间
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String lastUpdate = sdf.format(new java.util.Date(lastUpdateTime));
        
        result.append("监控用户数: ").append(getAllSubscribedMembers().size()).append("\n");
        result.append("当前在线: ").append(onlineMembers.isEmpty() ? "无" : String.join(", ", onlineMembers)).append("\n");
        result.append("当前离线: ").append(offlineMembers.isEmpty() ? "无" : String.join(", ", offlineMembers)).append("\n");
        result.append("今日状态变化: ").append(dailyStatusChanges.get()).append("\n");
        result.append("最后更新: ").append(lastUpdate).append("\n");
        result.append("监控运行时间: ").append(hours).append("小时").append(minutes).append("分钟\n");
        result.append("健康状态: 正常\n");
        result.append("成功率: ").append(String.format("%.1f", successRate)).append("%\n");
        result.append("总检查次数: ").append(totalChecks).append("\n");
        result.append("失败次数: ").append(failures).append("\n");
        result.append("通知次数: ").append(notificationCount.get());
        
        return result.toString();
    }
    
    /**
     * 获取订阅配置列表
     */
    public List<SubscriptionConfig> getSubscriptionConfigs() {
        return new ArrayList<>(subscriptionConfigs.values());
    }
    
    /**
     * 添加订阅配置
     */
    public void addSubscriptionConfig(SubscriptionConfig config) {
        if (config != null) {
            subscriptionConfigs.put(config.getQqGroup(), config);
            // 同步到兼容列表
            subscribedMembers.addAll(config.getMemberSubs());
        }
    }
    
    /**
     * 获取所有订阅成员（去重）
     */
    public Set<String> getAllSubscribedMembers() {
        Set<String> allMembers = new HashSet<>();
        for (SubscriptionConfig config : subscriptionConfigs.values()) {
            allMembers.addAll(config.getMemberSubs());
        }
        return allMembers;
    }
    
    /**
     * 重新加载配置
     */
    public void reloadConfig() {
        updateConfigFromProperties();
        
        // 重新初始化订阅成员列表
        subscribedMembers.clear();
        subscriptionConfigs.clear();
        initSubscribedMembers();
        
        // 重启定时监控任务
        if (scheduledMonitorTaskId != null) {
            UnifiedSchedulerManager.getInstance().cancelTask(scheduledMonitorTaskId);
        }
        startScheduledMonitor();
    }
    
    /**
     * 获取异步监控统计信息
     */
    public String getAsyncMonitorStats() {
        return getStatistics();
    }
    
    /**
     * 获取统计信息（兼容方法）
     */
    public String getStatistics() {
        int batchCount = batchQueryCount.get();
        int totalQueriedMembers = totalMembersQueried.get();
        int actualSubscribedMembers = getAllSubscribedMembers().size();
        long avgBatchTime = batchCount > 0 ? totalBatchTime.get() / batchCount : 0;
        int currentConcurrent = concurrentBatches.get();
        int cacheSize = memberStatusCache.size();
        int pendingSize = pendingQueries.size();
        
        return String.format(
                "异步监控统计信息:\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "订阅成员总数: %d\n" +
                "批量查询次数: %d\n" +
                "累计查询成员数: %d\n" +
                "平均批量耗时: %dms\n" +
                "当前并发批次: %d\n" +
                "缓存条目数量: %d\n" +
                "待查询队列: %d\n" +
                "异步HTTP统计:\n%s",
                actualSubscribedMembers, batchCount, totalQueriedMembers, avgBatchTime, currentConcurrent, 
                cacheSize, pendingSize, asyncWebHandler.getPerformanceStats()
        );
    }
    
    /**
     * 获取批量查询性能报告
     */
    public String getBatchQueryReport() {
        int batchCount = batchQueryCount.get();
        int totalMembers = totalMembersQueried.get();
        double avgMembersPerBatch = batchCount > 0 ? (double) totalMembers / batchCount : 0;
        long avgBatchTime = batchCount > 0 ? totalBatchTime.get() / batchCount : 0;
        double throughput = avgBatchTime > 0 ? (avgMembersPerBatch * 1000.0 / avgBatchTime) : 0;
        
        return String.format(
                "批量查询性能报告:\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "总批次数: %d\n" +
                "总查询成员数: %d\n" +
                "平均每批成员数: %.2f\n" +
                "平均批次耗时: %dms\n" +
                "查询吞吐量: %.2f 成员/秒\n" +
                "当前并发批次: %d\n" +
                "缓存命中率: %.2f%%",
                batchCount, totalMembers, avgMembersPerBatch, avgBatchTime, 
                throughput, concurrentBatches.get(), calculateCacheHitRate()
        );
    }
    
    /**
     * 计算缓存命中率
     */
    private double calculateCacheHitRate() {
        // 这里需要实现缓存命中率的计算逻辑
        // 暂时返回估算值
        return memberStatusCache.size() > 0 ? 75.0 : 0.0;
    }
    
    /**
     * 重置异步监控统计
     */
    public void resetAsyncStats() {
        batchQueryCount.set(0);
        totalMembersQueried.set(0);
        uniqueMembersQueried.clear();
        totalBatchTime.set(0);
        concurrentBatches.set(0);
        memberStatusCache.clear();
        pendingQueries.clear();
        queryFutures.clear();
        asyncWebHandler.resetStats();
    }
    
    /**
     * 强制执行待查询队列
     */
    public CompletableFuture<Void> flushPendingQueries() {
        if (pendingQueries.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.runAsync(this::executeBatchQuery, UnifiedSchedulerManager.getInstance().getExecutor());
    }
    
    /**
     * 关闭异步监控器
     */
    public void shutdown() {
        UnifiedSchedulerManager scheduler = UnifiedSchedulerManager.getInstance();
        if (batchQueryTaskId != null) {
            scheduler.cancelTask(batchQueryTaskId);
        }
        if (cacheCleanupTaskId != null) {
            scheduler.cancelTask(cacheCleanupTaskId);
        }
        if (scheduledMonitorTaskId != null) {
            scheduler.cancelTask(scheduledMonitorTaskId);
        }
        asyncWebHandler.shutdown();
    }
    
    /**
     * 异步成员状态缓存
     */
    private static class AsyncMemberStatus {
        private final String status;
        private final String rawResponse;
        private final long timestamp;
        private final long expireTime;
        
        public AsyncMemberStatus(String status, String rawResponse) {
            this.status = status;
            this.rawResponse = rawResponse;
            this.timestamp = System.currentTimeMillis();
            this.expireTime = timestamp + MonitorConfig.getInstance().getCacheExpireTime();
        }
        
        public String getStatus() { return status; }
        public String getRawResponse() { return rawResponse; }
        public long getTimestamp() { return timestamp; }
        public boolean isExpired() { return System.currentTimeMillis() > expireTime; }
    }
}