package net.luffy.util.delay;

import net.luffy.util.UnifiedLogger;
import net.luffy.util.Pocket48MetricsCollector;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.*;
import java.io.*;
import java.nio.file.*;

/**
 * 延迟系统度量报告生成器
 * 负责生成详细的性能报告、对比分析和趋势图表
 */
public class DelayMetricsReporter {
    private static volatile DelayMetricsReporter instance;
    private final UnifiedLogger logger = UnifiedLogger.getInstance();
    private final ScheduledExecutorService reportScheduler;
    
    // 报告配置
    private static final String REPORTS_DIR = "reports/delay-metrics";
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter DISPLAY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    // 历史快照存储
    private final List<DelayMetricsCollector.DelayMetricsSnapshot> historicalSnapshots = new ArrayList<>();
    private DelayMetricsCollector.DelayMetricsSnapshot baselineSnapshot;
    
    // 报告生成状态
    private volatile boolean isReportingEnabled = true;
    private volatile long lastReportTime = 0;
    
    private DelayMetricsReporter() {
        this.reportScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DelayMetrics-Reporter");
            t.setDaemon(true);
            return t;
        });
        
        initializeReportsDirectory();
        startPeriodicReporting();
    }
    
    public static DelayMetricsReporter getInstance() {
        if (instance == null) {
            synchronized (DelayMetricsReporter.class) {
                if (instance == null) {
                    instance = new DelayMetricsReporter();
                }
            }
        }
        return instance;
    }
    
    /**
     * 设置基线快照（改造前的性能基准）
     */
    public void setBaselineSnapshot(DelayMetricsCollector.DelayMetricsSnapshot snapshot) {
        this.baselineSnapshot = snapshot;
        logger.info("DelayMetricsReporter", "基线快照已设置: " + formatSnapshot(snapshot));
    }
    
    /**
     * 生成完整的性能报告
     */
    public String generateFullReport() {
        StringBuilder report = new StringBuilder();
        LocalDateTime now = LocalDateTime.now();
        
        report.append("\n");
        report.append("═══════════════════════════════════════════════════════════════\n");
        report.append("                    Pocket48 延迟系统性能报告\n");
        report.append("═══════════════════════════════════════════════════════════════\n");
        report.append("生成时间: ").append(now.format(DISPLAY_DATE_FORMAT)).append("\n");
        report.append("报告版本: v2.0 (重构后)\n");
        report.append("\n");
        
        // 1. 延迟系统概览
        report.append(generateSystemOverview());
        
        // 2. 详细度量报告
        report.append(DelayMetricsCollector.getInstance().generateDelayReport());
        
        // 3. 传统度量报告
        report.append("\n📊 传统度量指标:\n");
        report.append(Pocket48MetricsCollector.getInstance().generateReport());
        
        // 4. 改造前后对比
        if (baselineSnapshot != null) {
            report.append("\n");
            report.append(generateComparisonReport());
        }
        
        // 5. 趋势分析
        report.append("\n");
        report.append(generateTrendAnalysis());
        
        // 6. 性能建议
        report.append("\n");
        report.append(generatePerformanceRecommendations());
        
        report.append("\n═══════════════════════════════════════════════════════════════\n");
        
        return report.toString();
    }
    
    /**
     * 生成系统概览
     */
    private String generateSystemOverview() {
        StringBuilder overview = new StringBuilder();
        DelayMetricsCollector metrics = DelayMetricsCollector.getInstance();
        
        overview.append("🎯 系统概览:\n");
        overview.append(String.format("  延迟策略状态: %s\n", 
            DelayConfig.getInstance().isMetricsCollectionEnabled() ? "✅ 已启用" : "❌ 已禁用"));
        overview.append(String.format("  平均延迟: %.2fms\n", metrics.getAverageDelay()));
        overview.append(String.format("  重试成功率: %.1f%%\n", metrics.getRetrySuccessRate()));
        overview.append(String.format("  队列平均等待: %.2fms\n", metrics.getAverageQueueWaitTime()));
        
        // 系统健康度评估
        double healthScore = calculateSystemHealthScore();
        String healthStatus = getHealthStatus(healthScore);
        overview.append(String.format("  系统健康度: %.1f%% %s\n", healthScore, healthStatus));
        
        return overview.toString();
    }
    
    /**
     * 生成改造前后对比报告
     */
    private String generateComparisonReport() {
        if (baselineSnapshot == null) {
            return "\n⚠️ 无基线数据，无法生成对比报告\n";
        }
        
        DelayMetricsCollector.DelayMetricsSnapshot currentSnapshot = 
            DelayMetricsCollector.getInstance().createSnapshot();
        
        StringBuilder comparison = new StringBuilder();
        comparison.append("\n📈 改造前后性能对比:\n");
        comparison.append("┌─────────────────────────────────────────────────────────────┐\n");
        comparison.append("│                     指标对比表                              │\n");
        comparison.append("├─────────────────────────────────────────────────────────────┤\n");
        
        // 延迟对比
        double delayImprovement = calculateImprovement(
            baselineSnapshot.averageDelay, currentSnapshot.averageDelay, true);
        comparison.append(String.format("│ 平均延迟     │ %.2fms → %.2fms │ %s%.1f%% │\n",
            baselineSnapshot.averageDelay, currentSnapshot.averageDelay, 
            delayImprovement > 0 ? "↓" : "↑", Math.abs(delayImprovement)));
        
        // 重试成功率对比
        double retryImprovement = calculateImprovement(
            baselineSnapshot.retrySuccessRate, currentSnapshot.retrySuccessRate, false);
        comparison.append(String.format("│ 重试成功率   │ %.1f%% → %.1f%% │ %s%.1f%% │\n",
            baselineSnapshot.retrySuccessRate, currentSnapshot.retrySuccessRate,
            retryImprovement > 0 ? "↑" : "↓", Math.abs(retryImprovement)));
        
        // 队列等待时间对比
        double queueImprovement = calculateImprovement(
            baselineSnapshot.averageQueueWaitTime, currentSnapshot.averageQueueWaitTime, true);
        comparison.append(String.format("│ 队列等待时间 │ %.2fms → %.2fms │ %s%.1f%% │\n",
            baselineSnapshot.averageQueueWaitTime, currentSnapshot.averageQueueWaitTime,
            queueImprovement > 0 ? "↓" : "↑", Math.abs(queueImprovement)));
        
        comparison.append("└─────────────────────────────────────────────────────────────┘\n");
        
        // 总体改进评估
        double overallImprovement = (delayImprovement + retryImprovement + queueImprovement) / 3;
        String improvementStatus = getImprovementStatus(overallImprovement);
        comparison.append(String.format("\n🎯 总体改进: %.1f%% %s\n", 
            Math.abs(overallImprovement), improvementStatus));
        
        return comparison.toString();
    }
    
    /**
     * 生成趋势分析
     */
    private String generateTrendAnalysis() {
        StringBuilder trend = new StringBuilder();
        trend.append("📊 趋势分析:\n");
        
        if (historicalSnapshots.size() < 2) {
            trend.append("  数据不足，需要更多历史数据进行趋势分析\n");
            return trend.toString();
        }
        
        // 分析最近的趋势
        List<DelayMetricsCollector.DelayMetricsSnapshot> recentSnapshots = 
            historicalSnapshots.subList(Math.max(0, historicalSnapshots.size() - 10), historicalSnapshots.size());
        
        // 延迟趋势
        double delayTrend = calculateTrend(recentSnapshots, s -> s.averageDelay);
        trend.append(String.format("  延迟趋势: %s (%.2f%%/小时)\n", 
            getTrendDirection(delayTrend), Math.abs(delayTrend)));
        
        // 重试成功率趋势
        double retryTrend = calculateTrend(recentSnapshots, s -> s.retrySuccessRate);
        trend.append(String.format("  重试成功率趋势: %s (%.2f%%/小时)\n", 
            getTrendDirection(retryTrend), Math.abs(retryTrend)));
        
        // 队列等待时间趋势
        double queueTrend = calculateTrend(recentSnapshots, s -> s.averageQueueWaitTime);
        trend.append(String.format("  队列等待时间趋势: %s (%.2f%%/小时)\n", 
            getTrendDirection(queueTrend), Math.abs(queueTrend)));
        
        return trend.toString();
    }
    
    /**
     * 生成性能建议
     */
    private String generatePerformanceRecommendations() {
        StringBuilder recommendations = new StringBuilder();
        recommendations.append("💡 性能优化建议:\n");
        
        DelayMetricsCollector metrics = DelayMetricsCollector.getInstance();
        double avgDelay = metrics.getAverageDelay();
        double retrySuccessRate = metrics.getRetrySuccessRate();
        double avgQueueWait = metrics.getAverageQueueWaitTime();
        
        // 延迟优化建议
        if (avgDelay > 2000) {
            recommendations.append("  🔧 平均延迟较高，建议:\n");
            recommendations.append("     - 检查网络质量自适应配置\n");
            recommendations.append("     - 优化活跃度检测算法\n");
            recommendations.append("     - 考虑降低基础延迟参数\n");
        }
        
        // 重试优化建议
        if (retrySuccessRate < 90) {
            recommendations.append("  🔄 重试成功率偏低，建议:\n");
            recommendations.append("     - 增加最大重试次数\n");
            recommendations.append("     - 优化重试退避策略\n");
            recommendations.append("     - 检查网络连接稳定性\n");
        }
        
        // 队列优化建议
        if (avgQueueWait > 1000) {
            recommendations.append("  📋 队列等待时间较长，建议:\n");
            recommendations.append("     - 增加并发处理能力\n");
            recommendations.append("     - 优化速率限制策略\n");
            recommendations.append("     - 考虑实现消息优先级队列\n");
        }
        
        // 系统健康建议
        double healthScore = calculateSystemHealthScore();
        if (healthScore < 80) {
            recommendations.append("  ⚠️ 系统健康度偏低，建议:\n");
            recommendations.append("     - 全面检查系统配置\n");
            recommendations.append("     - 监控系统资源使用情况\n");
            recommendations.append("     - 考虑进行系统调优\n");
        }
        
        if (recommendations.length() == "💡 性能优化建议:\n".length()) {
            recommendations.append("  ✅ 系统运行良好，无需特别优化\n");
        }
        
        return recommendations.toString();
    }
    
    /**
     * 保存报告到文件
     */
    public void saveReportToFile(String report) {
        try {
            String fileName = "delay-metrics-" + LocalDateTime.now().format(FILE_DATE_FORMAT) + ".txt";
            Path filePath = Paths.get(REPORTS_DIR, fileName);
            
            Files.write(filePath, report.getBytes("UTF-8"));
            logger.info("DelayMetricsReporter", "报告已保存到: " + filePath.toString());
        } catch (IOException e) {
            logger.error("DelayMetricsReporter", "保存报告失败", e);
        }
    }
    
    /**
     * 启动定期报告
     */
    private void startPeriodicReporting() {
        DelayConfig config = DelayConfig.getInstance();
        
        // 根据配置生成定期报告
        if (config.isPeriodicReportingEnabled()) {
            int reportInterval = config.getReportIntervalMinutes();
            reportScheduler.scheduleAtFixedRate(() -> {
                try {
                    if (isReportingEnabled && config.isMetricsCollectionEnabled()) {
                        // 保存历史快照
                        DelayMetricsCollector.DelayMetricsSnapshot snapshot = 
                            DelayMetricsCollector.getInstance().createSnapshot();
                        historicalSnapshots.add(snapshot);
                        
                        // 限制历史快照数量
                        int maxSnapshots = config.getMaxHistoricalSnapshots();
                        if (historicalSnapshots.size() > maxSnapshots) {
                            historicalSnapshots.remove(0);
                        }
                        
                        // 生成并保存报告
                        if (config.isAutoSaveReportsEnabled()) {
                            String report = generateFullReport();
                            saveReportToFile(report);
                        }
                        
                        lastReportTime = System.currentTimeMillis();
                        logger.info("DelayMetricsReporter", "定期报告已生成");
                    }
                } catch (Exception e) {
                    logger.error("DelayMetricsReporter", "生成定期报告失败", e);
                }
            }, reportInterval, reportInterval, TimeUnit.MINUTES);
        }
        
        // 根据配置保存快照
        int snapshotInterval = config.getSnapshotIntervalMinutes();
        reportScheduler.scheduleAtFixedRate(() -> {
            try {
                if (config.isMetricsCollectionEnabled()) {
                    DelayMetricsCollector.DelayMetricsSnapshot snapshot = 
                        DelayMetricsCollector.getInstance().createSnapshot();
                    historicalSnapshots.add(snapshot);
                    
                    int maxSnapshots = config.getMaxHistoricalSnapshots();
                    if (historicalSnapshots.size() > maxSnapshots) {
                        historicalSnapshots.remove(0);
                    }
                }
            } catch (Exception e) {
                logger.error("DelayMetricsReporter", "保存快照失败", e);
            }
        }, snapshotInterval, snapshotInterval, TimeUnit.MINUTES);
    }
    
    // ==================== 辅助方法 ====================
    
    private void initializeReportsDirectory() {
        try {
            Files.createDirectories(Paths.get(REPORTS_DIR));
        } catch (IOException e) {
            logger.error("DelayMetricsReporter", "创建报告目录失败", e);
        }
    }
    
    private double calculateSystemHealthScore() {
        DelayMetricsCollector metrics = DelayMetricsCollector.getInstance();
        
        // 延迟健康度 (0-40分)
        double avgDelay = metrics.getAverageDelay();
        double delayScore = Math.max(0, 40 - (avgDelay / 100)); // 每100ms扣1分
        
        // 重试健康度 (0-30分)
        double retrySuccessRate = metrics.getRetrySuccessRate();
        double retryScore = (retrySuccessRate / 100) * 30;
        
        // 队列健康度 (0-30分)
        double avgQueueWait = metrics.getAverageQueueWaitTime();
        double queueScore = Math.max(0, 30 - (avgQueueWait / 50)); // 每50ms扣1分
        
        return Math.min(100, delayScore + retryScore + queueScore);
    }
    
    private String getHealthStatus(double score) {
        if (score >= 90) return "🟢 优秀";
        if (score >= 80) return "🟡 良好";
        if (score >= 70) return "🟠 一般";
        return "🔴 需要改进";
    }
    
    private double calculateImprovement(double before, double after, boolean lowerIsBetter) {
        if (before == 0) return 0;
        double change = ((before - after) / before) * 100;
        return lowerIsBetter ? change : -change;
    }
    
    private String getImprovementStatus(double improvement) {
        if (improvement > 10) return "🎉 显著改进";
        if (improvement > 5) return "✅ 明显改进";
        if (improvement > 0) return "📈 轻微改进";
        if (improvement > -5) return "📊 基本持平";
        return "⚠️ 需要关注";
    }
    
    private double calculateTrend(List<DelayMetricsCollector.DelayMetricsSnapshot> snapshots, 
                                 java.util.function.Function<DelayMetricsCollector.DelayMetricsSnapshot, Double> valueExtractor) {
        if (snapshots.size() < 2) return 0;
        
        double firstValue = valueExtractor.apply(snapshots.get(0));
        double lastValue = valueExtractor.apply(snapshots.get(snapshots.size() - 1));
        
        if (firstValue == 0) return 0;
        
        long timeSpan = snapshots.get(snapshots.size() - 1).timestamp - snapshots.get(0).timestamp;
        double hourlyChange = ((lastValue - firstValue) / firstValue) * 100 * (3600000.0 / timeSpan);
        
        return hourlyChange;
    }
    
    private String getTrendDirection(double trend) {
        if (Math.abs(trend) < 1) return "➡️ 稳定";
        return trend > 0 ? "📈 上升" : "📉 下降";
    }
    
    private String formatSnapshot(DelayMetricsCollector.DelayMetricsSnapshot snapshot) {
        return String.format("延迟: %.2fms, 重试成功率: %.1f%%, 队列等待: %.2fms",
            snapshot.averageDelay, snapshot.retrySuccessRate, snapshot.averageQueueWaitTime);
    }
    
    // ==================== 公共接口 ====================
    
    /**
     * 启用/禁用报告生成
     */
    public void setReportingEnabled(boolean enabled) {
        this.isReportingEnabled = enabled;
        logger.info("DelayMetricsReporter", "报告生成已" + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 立即生成报告
     */
    public String generateImmediateReport() {
        String report = generateFullReport();
        saveReportToFile(report);
        return report;
    }
    
    /**
     * 获取历史快照数量
     */
    public int getHistoricalSnapshotCount() {
        return historicalSnapshots.size();
    }
    
    /**
     * 清理历史数据
     */
    public void clearHistoricalData() {
        historicalSnapshots.clear();
        baselineSnapshot = null;
        logger.info("DelayMetricsReporter", "历史数据已清理");
    }
    
    /**
     * 关闭报告器
     */
    public void shutdown() {
        isReportingEnabled = false;
        reportScheduler.shutdown();
        try {
            if (!reportScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                reportScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            reportScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("DelayMetricsReporter", "报告器已关闭");
    }
}