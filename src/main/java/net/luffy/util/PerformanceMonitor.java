package net.luffy.util;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.management.OperatingSystemMXBean;
import net.luffy.Newboy;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.contact.Contact;
import net.mamoe.mirai.message.data.PlainText;

/**
 * 性能监控工具类
 * 提供实时的系统性能指标监控
 */
public class PerformanceMonitor {
    
    private static final PerformanceMonitor INSTANCE = new PerformanceMonitor();
    
    // 性能统计
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    private final AtomicLong lastReportTime = new AtomicLong(System.currentTimeMillis());
    
    // 定期报告相关
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicReference<String> adminUserId = new AtomicReference<>();
    private volatile boolean reportingEnabled = false;
    private static final long DEFAULT_REPORT_INTERVAL = 30; // 默认30分钟
    
    // 内存监控阈值
    private static final double MEMORY_WARNING_THRESHOLD = 0.8; // 80%
    private static final double MEMORY_CRITICAL_THRESHOLD = 0.9; // 90%
    
    private PerformanceMonitor() {
        // 私有构造函数
    }
    
    public static PerformanceMonitor getInstance() {
        return INSTANCE;
    }
    
    /**
     * 记录查询操作
     * @param processingTime 处理时间（毫秒）
     */
    public void recordQuery(long processingTime) {
        totalQueries.incrementAndGet();
        totalProcessingTime.addAndGet(processingTime);
    }
    
    /**
     * 增加活跃线程数
     */
    public void incrementActiveThreads() {
        activeThreads.incrementAndGet();
    }
    
    /**
     * 减少活跃线程数
     */
    public void decrementActiveThreads() {
        activeThreads.decrementAndGet();
    }
    
    /**
     * 获取当前内存使用情况
     * @return 内存使用率（0.0-1.0）
     */
    public double getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        return (double) usedMemory / maxMemory;
    }
    
    /**
     * 检查内存使用情况并返回状态
     * @return 内存状态描述
     */
    public String checkMemoryStatus() {
        double usage = getMemoryUsage();
        if (usage >= MEMORY_CRITICAL_THRESHOLD) {
            return String.format("🔴 严重警告: 内存使用率 %.1f%%", usage * 100);
        } else if (usage >= MEMORY_WARNING_THRESHOLD) {
            return String.format("🟡 警告: 内存使用率 %.1f%%", usage * 100);
        } else {
            return String.format("🟢 正常: 内存使用率 %.1f%%", usage * 100);
        }
    }
    
    /**
     * 获取线程信息
     * @return 线程状态描述
     */
    public String getThreadInfo() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int totalThreads = threadBean.getThreadCount();
        int daemonThreads = threadBean.getDaemonThreadCount();
        
        return String.format("线程总数: %d (守护线程: %d, 活跃业务线程: %d)", 
                           totalThreads, daemonThreads, activeThreads.get());
    }
    
    /**
     * 获取性能统计信息
     * @return 性能报告
     */
    public String getPerformanceReport() {
        long queries = totalQueries.get();
        long totalTime = totalProcessingTime.get();
        double avgTime = queries > 0 ? (double) totalTime / queries : 0;
        
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        
        StringBuilder report = new StringBuilder();
        report.append("📊 系统性能监控报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        report.append(String.format("💾 内存使用: %.1f MB / %.1f MB (%.1f%%)\n", 
                     usedMemory / 1024.0 / 1024.0, 
                     maxMemory / 1024.0 / 1024.0, 
                     getMemoryUsage() * 100));
        report.append(String.format("🖥️ %s\n", checkCpuStatus()));
        report.append(String.format("🧵 %s\n", getThreadInfo()));
        report.append(String.format("📈 查询统计: %d 次查询, 平均耗时 %.2f ms\n", queries, avgTime));
        report.append(String.format("⏱️ 总处理时间: %.2f 秒\n", totalTime / 1000.0));
        report.append(String.format("🔍 %s\n", checkMemoryStatus()));
        
        // 计算QPS（每秒查询数）
        long currentTime = System.currentTimeMillis();
        long lastReport = lastReportTime.get();
        if (currentTime > lastReport) {
            double timeSpanSeconds = (currentTime - lastReport) / 1000.0;
            double qps = queries / timeSpanSeconds;
            report.append(String.format("⚡ 查询速率: %.2f QPS\n", qps));
        }
        
        report.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        return report.toString();
    }
    
    /**
     * 重置统计信息
     */
    public void resetStats() {
        totalQueries.set(0);
        totalProcessingTime.set(0);
        activeThreads.set(0);
        lastReportTime.set(System.currentTimeMillis());
    }
    
    /**
     * 检查是否需要执行内存清理
     * @return 是否需要清理
     */
    public boolean shouldCleanup() {
        return getMemoryUsage() > MEMORY_WARNING_THRESHOLD;
    }
    
    /**
     * 检查是否需要强制清理
     * @return 是否需要强制清理
     */
    public boolean shouldForceCleanup() {
        return getMemoryUsage() > MEMORY_CRITICAL_THRESHOLD;
    }
    
    /**
     * 获取总查询次数
     * @return 总查询次数
     */
    public long getTotalQueries() {
        return totalQueries.get();
    }
    
    /**
     * 检查是否需要清理
     * @return 是否需要清理
     */
    public boolean needsCleanup() {
        return getMemoryUsage() > MEMORY_WARNING_THRESHOLD;
    }
    
    /**
     * 检查是否需要强制清理
     * @return 是否需要强制清理
     */
    public boolean needsForceCleanup() {
        return getMemoryUsage() > MEMORY_CRITICAL_THRESHOLD;
    }
    
    /**
     * 获取平均QPS
     * @return 平均QPS
     */
    public double getAverageQPS() {
        long currentTime = System.currentTimeMillis();
        long lastReport = lastReportTime.get();
        if (currentTime > lastReport) {
            double timeSpanSeconds = (currentTime - lastReport) / 1000.0;
            return totalQueries.get() / timeSpanSeconds;
        }
        return 0.0;
    }
    
    /**
     * 获取内存使用百分比
     * @return 内存使用百分比
     */
    public double getMemoryUsagePercentage() {
        return getMemoryUsage() * 100;
    }
    
    /**
     * 获取活跃线程数
     * @return 活跃线程数
     */
    public int getActiveThreadCount() {
        return activeThreads.get();
    }
    
    /**
     * 获取CPU使用率
     * @return CPU使用率（0.0-1.0）
     */
    public double getCpuUsage() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            return sunOsBean.getProcessCpuLoad();
        }
        return -1.0; // 无法获取CPU使用率
    }
    
    /**
     * 获取CPU使用百分比
     * @return CPU使用百分比
     */
    public double getCpuUsagePercentage() {
        double cpuUsage = getCpuUsage();
        return cpuUsage >= 0 ? cpuUsage * 100 : -1;
    }
    
    /**
     * 检查CPU使用情况并返回状态
     * @return CPU状态描述
     */
    public String checkCpuStatus() {
        double usage = getCpuUsage();
        if (usage < 0) {
            return "❓ 无法获取CPU使用率";
        } else if (usage >= 0.8) {
            return String.format("🔴 CPU高负载: %.1f%%", usage * 100);
        } else if (usage >= 0.6) {
            return String.format("🟡 CPU中等负载: %.1f%%", usage * 100);
        } else {
            return String.format("🟢 CPU正常: %.1f%%", usage * 100);
        }
    }
    
    /**
     * 获取简化的状态信息
     * @return 简化状态
     */
    public String getQuickStatus() {
        double cpuUsage = getCpuUsage();
        String cpuInfo = cpuUsage >= 0 ? String.format("CPU: %.1f%%", cpuUsage * 100) : "CPU: N/A";
        return String.format("%s | 内存: %.1f%% | 线程: %d | 查询: %d", 
                           cpuInfo,
                           getMemoryUsage() * 100, 
                           activeThreads.get(), 
                           totalQueries.get());
    }
    
    /**
     * 设置管理员用户ID并启用定期报告
     * @param userId 管理员用户ID
     * @param intervalMinutes 报告间隔（分钟）
     */
    public void enablePeriodicReporting(String userId, long intervalMinutes) {
        this.adminUserId.set(userId);
        this.reportingEnabled = true;
        
        // 启动定期报告任务
        scheduler.scheduleAtFixedRate(this::sendPerformanceReport, 
                                    intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }
    
    /**
     * 启用默认间隔的定期报告
     * @param userId 管理员用户ID
     */
    public void enablePeriodicReporting(String userId) {
        enablePeriodicReporting(userId, DEFAULT_REPORT_INTERVAL);
    }
    
    /**
     * 禁用定期报告
     */
    public void disablePeriodicReporting() {
        this.reportingEnabled = false;
        this.adminUserId.set(null);
        scheduler.shutdown();
    }
    
    /**
     * 发送性能报告给管理员
     */
    private void sendPerformanceReport() {
        if (!reportingEnabled || adminUserId.get() == null) {
            return;
        }
        
        try {
            String report = getPerformanceReport();
            String userId = adminUserId.get();
            
            // 获取Bot实例并发送消息
            Bot bot = Newboy.getBot();
            if (bot != null) {
                Contact contact = bot.getFriend(Long.parseLong(userId));
                if (contact != null) {
                    contact.sendMessage(new PlainText("📊 定期性能监控报告\n\n" + report));
                } else {
                    System.err.println("无法找到管理员用户: " + userId);
                }
            } else {
                System.err.println("Bot实例未初始化，无法发送性能报告");
            }
        } catch (Exception e) {
            System.err.println("发送性能报告失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 手动发送性能报告
     */
    public void sendManualReport() {
        sendPerformanceReport();
    }
    
    /**
     * 检查是否启用了定期报告
     * @return 是否启用
     */
    public boolean isReportingEnabled() {
        return reportingEnabled;
    }
    
    /**
     * 获取当前管理员用户ID
     * @return 管理员用户ID
     */
    public String getAdminUserId() {
        return adminUserId.get();
    }
    
    /**
     * 发送警告消息给管理员
     * @param message 警告消息
     */
    public void sendWarningToAdmin(String message) {
        if (!reportingEnabled || adminUserId.get() == null) {
            return;
        }
        
        try {
            String userId = adminUserId.get();
            Bot bot = Newboy.getBot();
            if (bot != null) {
                Contact contact = bot.getFriend(Long.parseLong(userId));
                if (contact != null) {
                    contact.sendMessage(new PlainText("⚠️ 系统警告\n\n" + message));
                }
            }
        } catch (Exception e) {
            System.err.println("发送警告消息失败: " + e.getMessage());
        }
    }
    
    /**
     * 检查并发送内存警告
     */
    public void checkAndSendMemoryWarning() {
        double usage = getMemoryUsage();
        if (usage >= MEMORY_CRITICAL_THRESHOLD) {
            sendWarningToAdmin(String.format("🔴 严重警告: 内存使用率已达到 %.1f%%，请立即处理！", usage * 100));
        } else if (usage >= MEMORY_WARNING_THRESHOLD) {
            sendWarningToAdmin(String.format("🟡 警告: 内存使用率已达到 %.1f%%，建议进行清理。", usage * 100));
        }
    }
}