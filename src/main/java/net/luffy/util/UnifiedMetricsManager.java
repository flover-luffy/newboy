package net.luffy.util;

import net.luffy.util.UnifiedLogger;
// 移除错误的Logger导入

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 统一指标管理器
 * 整合所有指标收集器，实现降噪与频率自适应的统一度量体系
 */
public class UnifiedMetricsManager {
    private static final UnifiedLogger logger = UnifiedLogger.getInstance();
    private static volatile UnifiedMetricsManager instance;
    private static final Object lock = new Object();
    
    // 核心组件 - 使用MetricsCollectable接口
    private final Map<String, MetricsCollectable> metricsComponents = new ConcurrentHashMap<>();
    private final Pocket48MetricsCollector pocket48Metrics;
    
    // 报告管理
    private final ScheduledExecutorService reportScheduler;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    // 降噪与频率自适应
    private final AtomicLong reportCounter = new AtomicLong(0);
    private final AtomicBoolean isHighFrequencyMode = new AtomicBoolean(false);
    private volatile long lastReportTime = System.currentTimeMillis();
    private volatile long adaptiveReportInterval = 300000; // 默认5分钟
    
    // 报告配置
    private static final long MIN_REPORT_INTERVAL = 60000;  // 最小1分钟
    private static final long MAX_REPORT_INTERVAL = 1800000; // 最大30分钟
    private static final long HIGH_FREQUENCY_THRESHOLD = 10; // 高频阈值
    private static final String UNIFIED_REPORTS_DIR = "reports/unified-metrics";
    
    // 指标聚合缓存
    private final Map<String, MetricSnapshot> metricSnapshots = new ConcurrentHashMap<>();
    private final ScheduledExecutorService metricsScheduler;
    
    // 历史数据
    private final Queue<UnifiedReport> reportHistory = new ConcurrentLinkedQueue<>();
    private static final int MAX_HISTORY_SIZE = 100;
    private final long startTime = System.currentTimeMillis();
    
    // 系统健康等级
    private enum SystemHealthLevel {
        CRITICAL(0.0, 50.0, "严重", 60000),    // 1分钟
        WARNING(50.0, 70.0, "警告", 120000),   // 2分钟
        NORMAL(70.0, 90.0, "正常", 300000),    // 5分钟
        EXCELLENT(90.0, 100.0, "优秀", 600000); // 10分钟
        
        final double minHealth;
        final double maxHealth;
        final String description;
        final long recommendedInterval;
        
        SystemHealthLevel(double minHealth, double maxHealth, String description, long recommendedInterval) {
            this.minHealth = minHealth;
            this.maxHealth = maxHealth;
            this.description = description;
            this.recommendedInterval = recommendedInterval;
        }
        
        static SystemHealthLevel fromHealth(double health) {
            for (SystemHealthLevel level : values()) {
                if (health >= level.minHealth && health < level.maxHealth) {
                    return level;
                }
            }
            return EXCELLENT;
        }
    }
    
    // 降噪过滤器
    private final Map<String, Double> noiseThresholds = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastMetricValues = new ConcurrentHashMap<>();
    
    private UnifiedMetricsManager() {
        this.pocket48Metrics = Pocket48MetricsCollector.getInstance();
        
        this.reportScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "UnifiedMetrics-Reporter");
            t.setDaemon(true);
            return t;
        });
        
        this.metricsScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "UnifiedMetrics-Scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // 注册默认组件
        registerDefaultComponents();
        initializeNoiseThresholds();
        initializeReportsDirectory();
        startAdaptiveReporting();
        startMetricsAggregation();
    }
    
    /**
     * 注册默认的指标收集组件
     */
    private void registerDefaultComponents() {
        try {
            // 注册Pocket48指标收集器
            registerComponent(pocket48Metrics);
            
            // 延迟系统已移除，不再注册delayMetrics
            // registerComponent(delayMetrics);
            
            logger.info("UnifiedMetrics", "默认指标收集组件注册完成");
        } catch (Exception e) {
            logger.error("UnifiedMetrics", "注册默认组件失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 注册指标收集组件
     */
    public void registerComponent(MetricsCollectable component) {
        if (component != null && component.isMetricsEnabled()) {
            String componentName = component.getComponentName();
            metricsComponents.put(componentName, component);
            logger.info("UnifiedMetrics", "已注册指标收集组件: " + componentName);
        }
    }
    
    /**
     * 注销指标收集组件
     */
    public void unregisterComponent(String componentName) {
        MetricsCollectable removed = metricsComponents.remove(componentName);
        if (removed != null) {
            logger.info("UnifiedMetrics", "已注销指标收集组件: " + componentName);
        }
    }
    
    public static UnifiedMetricsManager getInstance() {
        if (instance == null) {
            synchronized (UnifiedMetricsManager.class) {
                if (instance == null) {
                    instance = new UnifiedMetricsManager();
                }
            }
        }
        return instance;
    }
    
    // ==================== 统一指标收集 ====================
    
    /**
     * 记录统一指标
     */
    public void recordMetric(String category, String name, double value) {
        String metricKey = category + "." + name;
        
        // 降噪过滤
        if (shouldFilterNoise(metricKey, value)) {
            return;
        }
        
        // 更新指标快照
        metricSnapshots.compute(metricKey, (key, snapshot) -> {
            if (snapshot == null) {
                return new MetricSnapshot(value, System.currentTimeMillis());
            } else {
                snapshot.update(value);
                return snapshot;
            }
        });
        
        // 分发到具体的收集器
        distributeMetric(category, name, value);
        
        // 检查是否需要调整报告频率
        checkAndAdjustReportFrequency();
    }
    
    /**
     * 分发指标到具体收集器
     */
    private void distributeMetric(String category, String name, double value) {
        switch (category.toLowerCase()) {
            case "download":
                if ("attempt".equals(name)) {
                    pocket48Metrics.recordDownloadAttempt();
                } else if ("success".equals(name)) {
                    pocket48Metrics.recordDownloadSuccess((long) value);
                } else if ("failure".equals(name)) {
                    pocket48Metrics.recordDownloadFailure("unified_metric");
                }
                break;
                
            case "delay":
                // 延迟系统已移除，记录为自定义指标
                pocket48Metrics.recordCustomMetric("delay_" + name, (long) value);
                break;
                
            case "cache":
                if ("hit".equals(name)) {
                    pocket48Metrics.recordCacheHit();
                } else if ("miss".equals(name)) {
                    pocket48Metrics.recordCacheMiss();
                }
                break;
                
            case "queue":
                if ("size".equals(name)) {
                    pocket48Metrics.updateQueueSize((int) value);
                } else if ("offer".equals(name)) {
                    pocket48Metrics.recordQueueOffer();
                } else if ("poll".equals(name)) {
                    pocket48Metrics.recordQueuePoll();
                }
                break;
                
            default:
                // 记录为自定义指标
                pocket48Metrics.recordCustomMetric(category + "_" + name, (long) value);
                break;
        }
    }
    
    // ==================== 降噪与频率自适应 ====================
    
    /**
     * 降噪过滤
     */
    private boolean shouldFilterNoise(String metricKey, double value) {
        Double threshold = noiseThresholds.get(metricKey);
        if (threshold == null) {
            return false;
        }
        
        AtomicLong lastValue = lastMetricValues.get(metricKey);
        if (lastValue == null) {
            lastMetricValues.put(metricKey, new AtomicLong((long) value));
            return false;
        }
        
        double change = Math.abs(value - lastValue.get()) / Math.max(lastValue.get(), 1.0);
        if (change < threshold) {
            return true; // 过滤噪声
        }
        
        lastValue.set((long) value);
        return false;
    }
    
    /**
     * 检查并调整报告频率
     */
    private void checkAndAdjustReportFrequency() {
        long currentCount = reportCounter.incrementAndGet();
        long currentTime = System.currentTimeMillis();
        long timeSinceLastReport = currentTime - lastReportTime;
        
        // 计算当前指标生成频率
        double metricsPerSecond = (double) currentCount / (timeSinceLastReport / 1000.0);
        
        // 高频模式判断
        if (metricsPerSecond > HIGH_FREQUENCY_THRESHOLD) {
            if (!isHighFrequencyMode.get()) {
                isHighFrequencyMode.set(true);
                adaptiveReportInterval = Math.max(MIN_REPORT_INTERVAL, adaptiveReportInterval / 2);
                logger.info("UnifiedMetrics", "切换到高频模式，报告间隔: " + adaptiveReportInterval + "ms");
            }
        } else {
            if (isHighFrequencyMode.get()) {
                isHighFrequencyMode.set(false);
                adaptiveReportInterval = Math.min(MAX_REPORT_INTERVAL, adaptiveReportInterval * 2);
                logger.info("UnifiedMetrics", "切换到低频模式，报告间隔: " + adaptiveReportInterval + "ms");
            }
        }
    }
    
    // ==================== 统一报告生成 ====================
    
    /**
     * 生成统一性能报告
     */
    public UnifiedReport generateUnifiedReport() {
        try {
            UnifiedReport report = new UnifiedReport();
            report.timestamp = System.currentTimeMillis();
            report.reportId = reportCounter.incrementAndGet();
            
            // 收集所有组件指标
            Map<String, Map<String, Object>> allMetrics = new HashMap<>();
            Map<String, Double> componentHealthScores = new HashMap<>();
            Map<String, String> componentStatuses = new HashMap<>();
            
            for (Map.Entry<String, MetricsCollectable> entry : metricsComponents.entrySet()) {
                String componentName = entry.getKey();
                MetricsCollectable component = entry.getValue();
                
                try {
                    if (component.isMetricsEnabled()) {
                        allMetrics.put(componentName, component.collectMetrics());
                        componentHealthScores.put(componentName, component.getHealthScore());
                        componentStatuses.put(componentName, component.getStatusDescription());
                    }
                } catch (Exception e) {
                    logger.error("UnifiedMetrics", "收集组件指标失败: " + componentName + ", " + e.getMessage(), e);
                }
            }
            
            // 收集系统概览
            report.systemOverview = generateSystemOverview(componentHealthScores);
            
            // 收集核心指标
            report.coreMetrics = generateCoreMetrics(allMetrics);
            
            // 收集详细组件报告
            report.componentReports = new HashMap<>();
            for (Map.Entry<String, String> entry : componentStatuses.entrySet()) {
                report.componentReports.put(entry.getKey(), entry.getValue());
            }
            
            // 延迟系统已移除，设置为空报告
            report.delaySystemReport = "延迟系统已移除以提升性能";
            
            // 收集缓存性能
            report.cachePerformance = generateCachePerformance(allMetrics);
            
            // 生成统一性能建议
            report.unifiedRecommendations = generateUnifiedRecommendations(report, allMetrics, componentHealthScores);
            
            // 更新历史记录
            updateReportHistory(report);
            
            logger.info("UnifiedMetrics", String.format("生成统一报告 #%d，系统健康度: %.1f%%", 
                report.reportId, report.systemOverview.overallHealth));
            
            return report;
        } catch (Exception e) {
            logger.error("UnifiedMetrics", "生成统一报告失败: " + e.getMessage(), e);
            return createEmptyReport();
        }
    }
    
    /**
     * 生成系统概览
     */
    private SystemOverview generateSystemOverview(Map<String, Double> componentHealthScores) {
        SystemOverview overview = new SystemOverview();
        
        // 计算整体健康度
        overview.overallHealth = calculateOverallHealth(componentHealthScores);
        overview.healthStatus = getHealthStatus(overview.overallHealth);
        overview.uptime = System.currentTimeMillis() - startTime;
        overview.reportCount = reportCounter.get();
        overview.componentCount = metricsComponents.size();
        overview.enabledComponents = (int) metricsComponents.values().stream()
            .mapToLong(c -> c.isMetricsEnabled() ? 1 : 0).sum();
        
        return overview;
    }
    
    /**
     * 生成核心指标汇总
     */
    private String generateCoreMetricsSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("\n📈 核心指标汇总:\n");
        
        // 按类别汇总指标
        Map<String, List<MetricSnapshot>> categorizedMetrics = categorizeCachedMetrics();
        
        categorizedMetrics.forEach((category, snapshots) -> {
            summary.append(String.format("\n  %s 类别:\n", category));
            snapshots.forEach(snapshot -> {
                summary.append(String.format("    %s: %.2f (最近更新: %s)\n", 
                    snapshot.name, snapshot.currentValue, 
                    formatTimestamp(snapshot.lastUpdateTime)));
            });
        });
        
        return summary.toString();
    }
    
    /**
     * 生成缓存性能报告
     */
    private String generateCachePerformanceReport() {
        return "";
    }
    
    /**
     * 生成统一性能建议
     */
    private String generateUnifiedRecommendations() {
        StringBuilder recommendations = new StringBuilder();
        recommendations.append("\n💡 统一性能建议:\n");
        
        // 基于整体指标的建议
        double downloadSuccessRate = pocket48Metrics.getDownloadSuccessRate();
        if (downloadSuccessRate < 95.0) {
            recommendations.append("  ⚠️ 下载成功率偏低，建议检查网络连接和重试策略\n");
        }
        
        double cacheHitRate = pocket48Metrics.getCacheHitRate();
        if (cacheHitRate < 80.0) {
            recommendations.append("  ⚠️ 缓存命中率偏低，建议优化缓存策略或增加缓存容量\n");
        }
        
        double dropRate = pocket48Metrics.getDropRate();
        if (dropRate > 5.0) {
            recommendations.append("  ⚠️ 消息丢弃率过高，建议增加队列容量或优化处理速度\n");
        }
        
        // 延迟系统已移除，跳过延迟检查
        // double avgDelay = delayMetrics.getAverageDelay();
        // if (avgDelay > 1000.0) {
        //     recommendations.append("  ⚠️ 平均延迟过高，建议优化延迟策略或网络配置\n");
        // }
        
        // 频率自适应建议
        if (isHighFrequencyMode.get()) {
            recommendations.append("  📊 当前处于高频监控模式，系统负载较高\n");
        } else {
            recommendations.append("  📊 当前处于低频监控模式，系统运行平稳\n");
        }
        
        return recommendations.toString();
    }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 初始化降噪阈值
     */
    private void initializeNoiseThresholds() {
        noiseThresholds.put("download.latency", 0.05); // 5%变化阈值
        noiseThresholds.put("cache.hit_rate", 0.02);   // 2%变化阈值
        noiseThresholds.put("queue.size", 0.1);        // 10%变化阈值
        noiseThresholds.put("delay.average", 0.05);    // 5%变化阈值
    }
    
    /**
     * 初始化报告目录
     */
    private void initializeReportsDirectory() {
        try {
            Path reportsPath = Paths.get(UNIFIED_REPORTS_DIR);
            if (!Files.exists(reportsPath)) {
                Files.createDirectories(reportsPath);
            }
        } catch (IOException e) {
            logger.warn("UnifiedMetrics", "创建报告目录失败: " + e.getMessage());
        }
    }
    
    /**
     * 启动自适应报告
     */
    private void startAdaptiveReporting() {
        metricsScheduler.scheduleWithFixedDelay(() -> {
            try {
                UnifiedReport unifiedReport = generateUnifiedReport();
                String reportText = formatReportAsText(unifiedReport);
                saveReportToFile(reportText);
                lastReportTime = System.currentTimeMillis();
                reportCounter.set(0);
            } catch (Exception e) {
                logger.error("UnifiedMetrics", "生成报告失败: " + e.getMessage());
            }
        }, adaptiveReportInterval, adaptiveReportInterval, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 启动指标聚合
     */
    private void startMetricsAggregation() {
        metricsScheduler.scheduleWithFixedDelay(() -> {
            try {
                aggregateMetrics();
            } catch (Exception e) {
                logger.error("UnifiedMetrics", "指标聚合失败: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * 聚合指标
     */
    private void aggregateMetrics() {
        // 清理过期的指标快照
        long currentTime = System.currentTimeMillis();
        metricSnapshots.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().lastUpdateTime > 3600000); // 1小时过期
    }
    
    /**
     * 保存报告到文件
     */
    private void saveReportToFile(String report) {
        try {
            String fileName = "unified-metrics-" + 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt";
            Path filePath = Paths.get(UNIFIED_REPORTS_DIR, fileName);
            Files.write(filePath, report.getBytes());
        } catch (IOException e) {
            logger.warn("UnifiedMetrics", "保存报告文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 计算整体健康度
     */
    private double calculateOverallHealth(Map<String, Double> componentHealthScores) {
        try {
            if (componentHealthScores.isEmpty()) {
                return 50.0; // 默认中等健康度
            }
            
            // 计算所有组件的平均健康度
            double totalHealth = 0.0;
            int count = 0;
            
            for (Double health : componentHealthScores.values()) {
                if (health != null && health >= 0) {
                    totalHealth += health;
                    count++;
                }
            }
            
            return count > 0 ? totalHealth / count : 50.0;
        } catch (Exception e) {
            logger.error("UnifiedMetrics", "计算整体健康度失败: " + e.getMessage(), e);
            return 50.0; // 默认中等健康度
        }
    }
    
    /**
     * 获取健康状态描述
     */
    private String getHealthStatus(double health) {
        if (health >= 90) return "🟢 优秀";
        if (health >= 80) return "🟡 良好";
        if (health >= 70) return "🟠 一般";
        return "🔴 需要关注";
    }
    
    /**
     * 生成核心指标
     */
    private Map<String, Object> generateCoreMetrics(Map<String, Map<String, Object>> allMetrics) {
        Map<String, Object> coreMetrics = new HashMap<>();
        
        try {
            // 汇总所有组件的核心指标
            for (Map.Entry<String, Map<String, Object>> entry : allMetrics.entrySet()) {
                String componentName = entry.getKey();
                Map<String, Object> metrics = entry.getValue();
                
                // 为每个组件的指标添加前缀
                for (Map.Entry<String, Object> metricEntry : metrics.entrySet()) {
                    String key = componentName.toLowerCase() + "." + metricEntry.getKey();
                    coreMetrics.put(key, metricEntry.getValue());
                }
            }
            
            // 添加系统级指标
            coreMetrics.put("system.component_count", metricsComponents.size());
            coreMetrics.put("system.enabled_components", metricsComponents.values().stream()
                .mapToLong(c -> c.isMetricsEnabled() ? 1 : 0).sum());
            coreMetrics.put("system.uptime", System.currentTimeMillis() - startTime);
            coreMetrics.put("system.report_count", reportCounter.get());
            
        } catch (Exception e) {
            logger.error("UnifiedMetrics", "生成核心指标失败: " + e.getMessage(), e);
        }
        
        return coreMetrics;
    }
    
    /**
     * 生成缓存性能报告
     */
    private Map<String, Object> generateCachePerformance(Map<String, Map<String, Object>> allMetrics) {
        Map<String, Object> cachePerformance = new HashMap<>();
        
        try {
            // 从各组件中提取缓存相关指标
            for (Map.Entry<String, Map<String, Object>> entry : allMetrics.entrySet()) {
                String componentName = entry.getKey();
                Map<String, Object> metrics = entry.getValue();
                
                // 查找缓存相关指标
                for (Map.Entry<String, Object> metricEntry : metrics.entrySet()) {
                    String key = metricEntry.getKey();
                    if (key.contains("cache") || key.contains("hit") || key.contains("miss")) {
                        cachePerformance.put(componentName + "." + key, metricEntry.getValue());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("UnifiedMetrics", "生成缓存性能报告失败: " + e.getMessage(), e);
        }
        
        return cachePerformance;
    }
    
    /**
     * 生成统一性能建议
     */
    private List<String> generateUnifiedRecommendations(UnifiedReport report, 
            Map<String, Map<String, Object>> allMetrics, Map<String, Double> componentHealthScores) {
        List<String> recommendations = new ArrayList<>();
        
        try {
            // 基于整体健康度的建议
            double overallHealth = report.systemOverview.overallHealth;
            if (overallHealth < 60) {
                recommendations.add("🚨 系统健康度较低(" + String.format("%.1f%%", overallHealth) + ")，建议立即检查异常组件");
            } else if (overallHealth < 80) {
                recommendations.add("⚠️ 系统健康度一般(" + String.format("%.1f%%", overallHealth) + ")，建议优化性能瓶颈");
            }
            
            // 基于组件健康度的建议
            for (Map.Entry<String, Double> entry : componentHealthScores.entrySet()) {
                String componentName = entry.getKey();
                Double health = entry.getValue();
                
                if (health != null && health < 70) {
                    recommendations.add("📉 " + componentName + "组件健康度较低(" + 
                        String.format("%.1f%%", health) + ")，建议检查该组件状态");
                }
            }
            
            // 基于指标趋势的建议
            if (recommendations.isEmpty()) {
                recommendations.add("✅ 系统运行良好，继续保持当前配置");
            }
            
        } catch (Exception e) {
            logger.error("UnifiedMetrics", "生成统一建议失败: " + e.getMessage(), e);
            recommendations.add("❌ 生成建议时发生错误，请检查系统状态");
        }
        
        return recommendations;
    }
    
    /**
     * 更新报告历史
     */
    private void updateReportHistory(UnifiedReport report) {
        reportHistory.offer(report);
        while (reportHistory.size() > MAX_HISTORY_SIZE) {
            reportHistory.poll();
        }
    }
    
    /**
     * 将UnifiedReport对象格式化为文本
     */
    private String formatReportAsText(UnifiedReport report) {
        StringBuilder text = new StringBuilder();
        
        try {
            // 报告头部
            text.append("=== 统一指标报告 #").append(report.reportId).append(" ===").append("\n");
            text.append("生成时间: ").append(formatTimestamp(report.timestamp)).append("\n\n");
            
            // 系统概览
            if (report.systemOverview != null) {
                text.append("📊 系统概览:\n");
                text.append("  整体健康度: ").append(String.format("%.1f%%", report.systemOverview.overallHealth)).append("\n");
                text.append("  健康状态: ").append(report.systemOverview.healthStatus).append("\n");
                text.append("  运行时间: ").append(formatDuration(report.systemOverview.uptime)).append("\n");
                text.append("  组件总数: ").append(report.systemOverview.componentCount).append("\n");
                text.append("  启用组件: ").append(report.systemOverview.enabledComponents).append("\n\n");
            }
            
            // 核心指标
            if (report.coreMetrics != null && !report.coreMetrics.isEmpty()) {
                text.append("📈 核心指标:\n");
                for (Map.Entry<String, Object> entry : report.coreMetrics.entrySet()) {
                    text.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                text.append("\n");
            }
            
            // 组件报告
            if (report.componentReports != null && !report.componentReports.isEmpty()) {
                text.append("🔧 组件状态:\n");
                for (Map.Entry<String, String> entry : report.componentReports.entrySet()) {
                    text.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                text.append("\n");
            }
            
            // 缓存性能
            if (report.cachePerformance != null && !report.cachePerformance.isEmpty()) {
                text.append("💾 缓存性能:\n");
                for (Map.Entry<String, Object> entry : report.cachePerformance.entrySet()) {
                    text.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
                text.append("\n");
            }
            
            // 延迟系统报告
            if (report.delaySystemReport != null && !report.delaySystemReport.trim().isEmpty()) {
                text.append("⏱️ 延迟系统报告:\n");
                text.append(report.delaySystemReport).append("\n\n");
            }
            
            // 统一建议
            if (report.unifiedRecommendations != null && !report.unifiedRecommendations.isEmpty()) {
                text.append("💡 性能建议:\n");
                for (String recommendation : report.unifiedRecommendations) {
                    text.append("  ").append(recommendation).append("\n");
                }
                text.append("\n");
            }
            
            text.append("=== 报告结束 ===").append("\n");
            
        } catch (Exception e) {
            logger.error("UnifiedMetrics", "格式化报告文本失败: " + e.getMessage(), e);
            return "报告格式化失败: " + e.getMessage();
        }
        
        return text.toString();
    }
    
    /**
      * 格式化时间戳
      */
     private String formatTimestamp(long timestamp) {
         return LocalDateTime.ofInstant(
             java.time.Instant.ofEpochMilli(timestamp), 
             java.time.ZoneId.systemDefault()
         ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
     }
     
     /**
      * 格式化持续时间
      */
     private String formatDuration(long durationMs) {
         long seconds = durationMs / 1000;
         long minutes = seconds / 60;
         long hours = minutes / 60;
         long days = hours / 24;
         
         if (days > 0) {
             return String.format("%d天%d小时%d分钟", days, hours % 24, minutes % 60);
         } else if (hours > 0) {
             return String.format("%d小时%d分钟", hours, minutes % 60);
         } else if (minutes > 0) {
             return String.format("%d分钟%d秒", minutes, seconds % 60);
         } else {
             return String.format("%d秒", seconds);
         }
     }
     
     /**
      * 创建空报告
      */
    private UnifiedReport createEmptyReport() {
        UnifiedReport report = new UnifiedReport();
        report.timestamp = System.currentTimeMillis();
        report.reportId = reportCounter.incrementAndGet();
        report.systemOverview = new SystemOverview();
        report.systemOverview.overallHealth = 0.0;
        report.systemOverview.healthStatus = "未知";
        report.coreMetrics = new HashMap<>();
        report.componentReports = new HashMap<>();
        report.delaySystemReport = "报告生成失败";
        report.cachePerformance = new HashMap<>();
        report.unifiedRecommendations = Arrays.asList("❌ 报告生成失败，请检查系统状态");
        return report;
    }
    
    /**
     * 获取当前队列大小
     */
    private int getCurrentQueueSize() {
        // 这里可以从各个组件获取实际队列大小
        return 0; // 占位符
    }
    
    /**
     * 分类缓存的指标
     */
    private Map<String, List<MetricSnapshot>> categorizeCachedMetrics() {
        Map<String, List<MetricSnapshot>> categorized = new HashMap<>();
        
        metricSnapshots.forEach((key, snapshot) -> {
            String category = key.contains(".") ? key.split("\\.")[0] : "其他";
            categorized.computeIfAbsent(category, k -> new ArrayList<>()).add(
                new MetricSnapshot(snapshot.name, snapshot.currentValue, snapshot.lastUpdateTime)
            );
        });
        
        return categorized;
    }
    

    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        if (metricsScheduler != null && !metricsScheduler.isShutdown()) {
            metricsScheduler.shutdown();
            try {
                if (!metricsScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    metricsScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                metricsScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        logger.info("UnifiedMetrics", "统一指标管理器已关闭");
    }
    
    // ==================== 数据结构定义 ====================
    
    /**
     * 统一报告数据结构
     */
    public static class UnifiedReport {
        public long timestamp;
        public long reportId;
        public SystemOverview systemOverview;
        public Map<String, Object> coreMetrics;
        public Map<String, String> componentReports;
        public String delaySystemReport;
        public Map<String, Object> cachePerformance;
        public List<String> unifiedRecommendations;
    }
    
    /**
     * 系统概览数据结构
     */
    public static class SystemOverview {
        public double overallHealth;
        public String healthStatus;
        public long uptime;
        public long reportCount;
        public int componentCount;
        public int enabledComponents;
    }
    

    
    // ==================== 内部类 ====================
    
    /**
     * 指标快照
     */
    private static class MetricSnapshot {
        String name;
        double currentValue;
        long lastUpdateTime;
        double minValue;
        double maxValue;
        long updateCount;
        
        MetricSnapshot(double value, long timestamp) {
            this.currentValue = value;
            this.lastUpdateTime = timestamp;
            this.minValue = value;
            this.maxValue = value;
            this.updateCount = 1;
        }
        
        MetricSnapshot(String name, double value, long timestamp) {
            this(value, timestamp);
            this.name = name;
        }
        
        void update(double value) {
            this.currentValue = value;
            this.lastUpdateTime = System.currentTimeMillis();
            this.minValue = Math.min(this.minValue, value);
            this.maxValue = Math.max(this.maxValue, value);
            this.updateCount++;
        }
    }
}