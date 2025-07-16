package net.luffy.util;

import net.luffy.Newboy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 增强性能监控器
 * 提供全面的性能指标收集、分析和报告功能
 */
public class EnhancedPerformanceMonitor {
    
    private static final EnhancedPerformanceMonitor INSTANCE = new EnhancedPerformanceMonitor();
    
    // 监控配置
    private static final int MAX_METRICS_HISTORY = 1000;
    private static final long ALERT_THRESHOLD_CPU = 80; // CPU使用率阈值
    private static final long ALERT_THRESHOLD_MEMORY = 85; // 内存使用率阈值
    private static final long ALERT_THRESHOLD_RESPONSE_TIME = 5000; // 响应时间阈值(ms)
    
    // 性能指标存储
    private final Map<String, MetricTimeSeries> metrics = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, PerformanceTimer> timers = new ConcurrentHashMap<>();
    
    // 系统监控
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
    
    // 告警系统
    private final Set<String> activeAlerts = ConcurrentHashMap.newKeySet();
    private final AtomicReference<AlertCallback> alertCallback = new AtomicReference<>();
    
    // 性能基线
    private final Map<String, PerformanceBaseline> baselines = new ConcurrentHashMap<>();
    
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    private EnhancedPerformanceMonitor() {
        // 启动系统监控
        startSystemMonitoring();
        // 初始化基础指标
        initializeBaseMetrics();
    }
    
    public static EnhancedPerformanceMonitor getInstance() {
        return INSTANCE;
    }
    
    /**
     * 记录性能指标
     */
    public void recordMetric(String name, double value) {
        MetricTimeSeries series = metrics.computeIfAbsent(name, k -> new MetricTimeSeries(MAX_METRICS_HISTORY));
        series.addValue(value);
        
        // 检查告警条件
        checkAlertConditions(name, value);
    }
    
    /**
     * 增加计数器
     */
    public void incrementCounter(String name) {
        incrementCounter(name, 1);
    }
    
    public void incrementCounter(String name, long delta) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(delta);
    }
    
    /**
     * 获取计数器值
     */
    public long getCounterValue(String name) {
        AtomicLong counter = counters.get(name);
        return counter != null ? counter.get() : 0;
    }
    
    /**
     * 开始性能计时
     */
    public PerformanceTimer startTimer(String name) {
        PerformanceTimer timer = new PerformanceTimer(name);
        timers.put(name + "_" + System.nanoTime(), timer);
        return timer;
    }
    
    /**
     * 记录方法执行时间
     */
    public void recordExecutionTime(String methodName, long executionTimeMs) {
        recordMetric("execution_time_" + methodName, executionTimeMs);
        
        // 检查响应时间告警
        if (executionTimeMs > ALERT_THRESHOLD_RESPONSE_TIME) {
            triggerAlert("SLOW_RESPONSE", 
                String.format("方法 %s 执行时间过长: %dms", methodName, executionTimeMs));
        }
    }
    
    /**
     * 获取系统性能快照
     */
    public SystemPerformanceSnapshot getSystemSnapshot() {
        return new SystemPerformanceSnapshot(
            getCpuUsage(),
            getMemoryUsage(),
            getThreadInfo(),
            getGcInfo(),
            getDiskInfo()
        );
    }
    
    /**
     * 获取性能报告
     */
    public String getPerformanceReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("📊 增强性能监控报告\n");
        report.append("━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        
        // 系统概览
        SystemPerformanceSnapshot snapshot = getSystemSnapshot();
        report.append("🖥️ 系统概览:\n");
        report.append(String.format("  CPU使用率: %.1f%%\n", snapshot.getCpuUsage()));
        report.append(String.format("  内存使用: %.1f%% (%.1f/%.1f MB)\n", 
            snapshot.getMemoryUsage().getUsagePercent(),
            snapshot.getMemoryUsage().getUsedMB(),
            snapshot.getMemoryUsage().getTotalMB()));
        report.append(String.format("  活跃线程: %d\n", snapshot.getThreadInfo().getActiveThreads()));
        
        // GC信息
        report.append("\n🗑️ 垃圾回收:\n");
        for (GcInfo gc : snapshot.getGcInfo()) {
            report.append(String.format("  %s: %d次, %.1fs\n", 
                gc.getName(), gc.getCollectionCount(), gc.getCollectionTime() / 1000.0));
        }
        
        // 性能指标Top 10
        report.append("\n📈 关键指标 (最近10个值):\n");
        List<Map.Entry<String, MetricTimeSeries>> topMetrics = metrics.entrySet().stream()
            .sorted((e1, e2) -> Double.compare(e2.getValue().getLatestValue(), e1.getValue().getLatestValue()))
            .limit(10)
            .collect(Collectors.toList());
            
        for (Map.Entry<String, MetricTimeSeries> entry : topMetrics) {
            MetricTimeSeries series = entry.getValue();
            report.append(String.format("  %s: %.2f (avg: %.2f, max: %.2f)\n",
                entry.getKey(),
                series.getLatestValue(),
                series.getAverage(),
                series.getMaxValue()));
        }
        
        // 计数器
        report.append("\n🔢 计数器:\n");
        counters.entrySet().stream()
            .sorted((e1, e2) -> Long.compare(e2.getValue().get(), e1.getValue().get()))
            .limit(10)
            .forEach(entry -> report.append(String.format("  %s: %d\n", 
                entry.getKey(), entry.getValue().get())));
        
        // 活跃告警
        if (!activeAlerts.isEmpty()) {
            report.append("\n⚠️ 活跃告警:\n");
            activeAlerts.forEach(alert -> report.append("  ").append(alert).append("\n"));
        }
        
        return report.toString();
    }
    
    /**
     * 设置告警回调
     */
    public void setAlertCallback(AlertCallback callback) {
        this.alertCallback.set(callback);
    }
    
    /**
     * 设置性能基线
     */
    public void setBaseline(String metric, double expectedValue, double tolerance) {
        baselines.put(metric, new PerformanceBaseline(expectedValue, tolerance));
    }
    
    /**
     * 检查性能基线偏差
     */
    public Map<String, Double> checkBaselineDeviations() {
        Map<String, Double> deviations = new HashMap<>();
        
        for (Map.Entry<String, PerformanceBaseline> entry : baselines.entrySet()) {
            String metric = entry.getKey();
            PerformanceBaseline baseline = entry.getValue();
            MetricTimeSeries series = metrics.get(metric);
            
            if (series != null) {
                double currentValue = series.getLatestValue();
                double deviation = baseline.calculateDeviation(currentValue);
                if (Math.abs(deviation) > baseline.getTolerance()) {
                    deviations.put(metric, deviation);
                }
            }
        }
        
        return deviations;
    }
    
    /**
     * 清理历史数据
     */
    public void cleanup() {
        lock.writeLock().lock();
        try {
            // 清理过期的计时器
            long cutoff = System.nanoTime() - 3600_000_000_000L; // 1小时前
            timers.entrySet().removeIf(entry -> {
                String key = entry.getKey();
                long timestamp = Long.parseLong(key.substring(key.lastIndexOf('_') + 1));
                return timestamp < cutoff;
            });
            
            // 清理过期告警
            activeAlerts.removeIf(alert -> alert.contains("[已解决]"));
            
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // 私有方法
    private void startSystemMonitoring() {
        UnifiedSchedulerManager.getInstance().scheduleMonitorTask(
            this::collectSystemMetrics, 10000, 10000); // 每10秒收集一次
    }
    
    private void initializeBaseMetrics() {
        // 初始化基础性能指标
        recordMetric("system_startup_time", System.currentTimeMillis());
        incrementCounter("monitor_initialized");
    }
    
    private void collectSystemMetrics() {
        try {
            // CPU使用率
            double cpuUsage = getCpuUsage();
            recordMetric("system_cpu_usage", cpuUsage);
            
            // 内存使用率
            MemoryUsage memUsage = getMemoryUsage();
            recordMetric("system_memory_usage", memUsage.getUsagePercent());
            recordMetric("system_memory_used_mb", memUsage.getUsedMB());
            
            // 线程信息
            ThreadInfo threadInfo = getThreadInfo();
            recordMetric("system_thread_count", threadInfo.getActiveThreads());
            recordMetric("system_daemon_threads", threadInfo.getDaemonThreads());
            
            // GC信息
            for (GcInfo gc : getGcInfo()) {
                recordMetric("gc_" + gc.getName().toLowerCase() + "_count", gc.getCollectionCount());
                recordMetric("gc_" + gc.getName().toLowerCase() + "_time", gc.getCollectionTime());
            }
            
        } catch (Exception e) {
            Newboy.INSTANCE.getLogger().error("[性能监控] 系统指标收集失败", e);
        }
    }
    
    private double getCpuUsage() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = 
                (com.sun.management.OperatingSystemMXBean) osBean;
            return sunOsBean.getProcessCpuLoad() * 100;
        }
        return osBean.getSystemLoadAverage();
    }
    
    private MemoryUsage getMemoryUsage() {
        java.lang.management.MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        double usagePercent = max > 0 ? (double) used / max * 100 : 0;
        
        return new MemoryUsage(
            used / 1024.0 / 1024.0,  // MB
            max / 1024.0 / 1024.0,   // MB
            usagePercent
        );
    }
    
    private ThreadInfo getThreadInfo() {
        return new ThreadInfo(
            threadBean.getThreadCount(),
            threadBean.getDaemonThreadCount(),
            threadBean.getPeakThreadCount()
        );
    }
    
    private List<GcInfo> getGcInfo() {
        return gcBeans.stream()
            .map(gc -> new GcInfo(
                gc.getName(),
                gc.getCollectionCount(),
                gc.getCollectionTime()))
            .collect(Collectors.toList());
    }
    
    private DiskInfo getDiskInfo() {
        // 简化的磁盘信息
        return new DiskInfo(0, 0, 0);
    }
    
    private void checkAlertConditions(String metricName, double value) {
        // CPU告警
        if (metricName.equals("system_cpu_usage") && value > ALERT_THRESHOLD_CPU) {
            triggerAlert("HIGH_CPU", String.format("CPU使用率过高: %.1f%%", value));
        }
        
        // 内存告警
        if (metricName.equals("system_memory_usage") && value > ALERT_THRESHOLD_MEMORY) {
            triggerAlert("HIGH_MEMORY", String.format("内存使用率过高: %.1f%%", value));
        }
    }
    
    private void triggerAlert(String alertType, String message) {
        String alertKey = alertType + "_" + System.currentTimeMillis();
        activeAlerts.add(alertKey + ": " + message);
        
        // 调用告警回调
        AlertCallback callback = alertCallback.get();
        if (callback != null) {
            callback.onAlert(alertType, message);
        }
        
        // 记录告警
        Newboy.INSTANCE.getLogger().info(String.format("[性能告警] %s: %s", alertType, message));
        incrementCounter("alerts_triggered");
    }
    
    // 内部类定义
    public static class MetricTimeSeries {
        private final Queue<Double> values;
        private final int maxSize;
        private double sum = 0;
        private double max = Double.MIN_VALUE;
        private double min = Double.MAX_VALUE;
        
        public MetricTimeSeries(int maxSize) {
            this.maxSize = maxSize;
            this.values = new LinkedList<>();
        }
        
        public synchronized void addValue(double value) {
            if (values.size() >= maxSize) {
                Double removed = values.poll();
                if (removed != null) {
                    sum -= removed;
                }
            }
            
            values.offer(value);
            sum += value;
            max = Math.max(max, value);
            min = Math.min(min, value);
        }
        
        public synchronized double getLatestValue() {
            return values.isEmpty() ? 0 : ((LinkedList<Double>) values).peekLast();
        }
        
        public synchronized double getAverage() {
            return values.isEmpty() ? 0 : sum / values.size();
        }
        
        public synchronized double getMaxValue() {
            return max == Double.MIN_VALUE ? 0 : max;
        }
        
        public synchronized double getMinValue() {
            return min == Double.MAX_VALUE ? 0 : min;
        }
        
        public synchronized int getSize() {
            return values.size();
        }
    }
    
    public static class PerformanceTimer {
        private final String name;
        private final long startTime;
        
        public PerformanceTimer(String name) {
            this.name = name;
            this.startTime = System.currentTimeMillis();
        }
        
        public long stop() {
            long duration = System.currentTimeMillis() - startTime;
            EnhancedPerformanceMonitor.getInstance().recordExecutionTime(name, duration);
            return duration;
        }
        
        public String getName() {
            return name;
        }
    }
    
    // 数据类
    public static class SystemPerformanceSnapshot {
        private final double cpuUsage;
        private final MemoryUsage memoryUsage;
        private final ThreadInfo threadInfo;
        private final List<GcInfo> gcInfo;
        private final DiskInfo diskInfo;
        
        public SystemPerformanceSnapshot(double cpuUsage, MemoryUsage memoryUsage, 
                                       ThreadInfo threadInfo, List<GcInfo> gcInfo, DiskInfo diskInfo) {
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
            this.threadInfo = threadInfo;
            this.gcInfo = gcInfo;
            this.diskInfo = diskInfo;
        }
        
        public double getCpuUsage() { return cpuUsage; }
        public MemoryUsage getMemoryUsage() { return memoryUsage; }
        public ThreadInfo getThreadInfo() { return threadInfo; }
        public List<GcInfo> getGcInfo() { return gcInfo; }
        public DiskInfo getDiskInfo() { return diskInfo; }
    }
    
    public static class MemoryUsage {
        private final double usedMB;
        private final double totalMB;
        private final double usagePercent;
        
        public MemoryUsage(double usedMB, double totalMB, double usagePercent) {
            this.usedMB = usedMB;
            this.totalMB = totalMB;
            this.usagePercent = usagePercent;
        }
        
        public double getUsedMB() { return usedMB; }
        public double getTotalMB() { return totalMB; }
        public double getUsagePercent() { return usagePercent; }
    }
    
    public static class ThreadInfo {
        private final int activeThreads;
        private final int daemonThreads;
        private final int peakThreads;
        
        public ThreadInfo(int activeThreads, int daemonThreads, int peakThreads) {
            this.activeThreads = activeThreads;
            this.daemonThreads = daemonThreads;
            this.peakThreads = peakThreads;
        }
        
        public int getActiveThreads() { return activeThreads; }
        public int getDaemonThreads() { return daemonThreads; }
        public int getPeakThreads() { return peakThreads; }
    }
    
    public static class GcInfo {
        private final String name;
        private final long collectionCount;
        private final long collectionTime;
        
        public GcInfo(String name, long collectionCount, long collectionTime) {
            this.name = name;
            this.collectionCount = collectionCount;
            this.collectionTime = collectionTime;
        }
        
        public String getName() { return name; }
        public long getCollectionCount() { return collectionCount; }
        public long getCollectionTime() { return collectionTime; }
    }
    
    public static class DiskInfo {
        private final long totalSpace;
        private final long freeSpace;
        private final long usedSpace;
        
        public DiskInfo(long totalSpace, long freeSpace, long usedSpace) {
            this.totalSpace = totalSpace;
            this.freeSpace = freeSpace;
            this.usedSpace = usedSpace;
        }
        
        public long getTotalSpace() { return totalSpace; }
        public long getFreeSpace() { return freeSpace; }
        public long getUsedSpace() { return usedSpace; }
    }
    
    public static class PerformanceBaseline {
        private final double expectedValue;
        private final double tolerance;
        
        public PerformanceBaseline(double expectedValue, double tolerance) {
            this.expectedValue = expectedValue;
            this.tolerance = tolerance;
        }
        
        public double calculateDeviation(double actualValue) {
            return ((actualValue - expectedValue) / expectedValue) * 100;
        }
        
        public double getExpectedValue() { return expectedValue; }
        public double getTolerance() { return tolerance; }
    }
    
    @FunctionalInterface
    public interface AlertCallback {
        void onAlert(String alertType, String message);
    }
}