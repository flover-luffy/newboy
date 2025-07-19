package net.luffy.util;

import net.luffy.Newboy;

/**
 * 消息延迟配置管理类
 * 负责管理口袋48消息发送的延迟优化配置
 * 现在从Properties中获取配置
 */
public class MessageDelayConfig {
    
    private static MessageDelayConfig instance;
    
    // 延迟模式
    public enum DelayMode {
        CONSERVATIVE("conservative"),
        BALANCED("balanced"),
        AGGRESSIVE("aggressive");
        
        private final String value;
        
        DelayMode(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static DelayMode fromString(String value) {
            for (DelayMode mode : values()) {
                if (mode.value.equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return BALANCED; // 默认平衡模式
        }
    }
    
    // 当前配置
    private final DelayMode delayMode;
    private final int textDelay;
    private final int mediaDelay;
    private final int groupHighPriorityDelay;
    private final int groupLowPriorityDelay;
    private final int processingTimeout;
    private final int batchTimeoutBuffer;
    private final int highLoadMultiplier;
    private final int criticalLoadMultiplier;
    
    // 动态调整配置
    private final boolean dynamicDelayEnabled;
    private final int failureThreshold;
    private final double failureDelayMultiplier;
    private final double recoverySpeed;
    
    // 监控配置
    private final boolean monitoringEnabled;
    private final int monitoringReportInterval;
    private final boolean consoleOutput;
    
    private MessageDelayConfig() {
        // 从Properties获取配置
        Properties properties = Newboy.INSTANCE.getProperties();
        
        // 从Properties加载延迟模式
        String modeStr = properties.message_delay_optimization_mode;
        delayMode = DelayMode.fromString(modeStr);
        
        // 从Properties获取配置
        textDelay = properties.message_delay_text;
        mediaDelay = properties.message_delay_media;
        groupHighPriorityDelay = properties.message_delay_group_high_priority;
        groupLowPriorityDelay = properties.message_delay_group_low_priority;
        processingTimeout = properties.message_delay_processing_timeout;
        highLoadMultiplier = (int) properties.message_delay_high_load_multiplier;
        criticalLoadMultiplier = (int) properties.message_delay_critical_load_multiplier;
        
        // 使用默认值的配置项
        batchTimeoutBuffer = 5; // 默认5秒缓冲
        
        // 动态调整配置（使用默认值）
        dynamicDelayEnabled = true;
        failureThreshold = 3;
        failureDelayMultiplier = 1.5;
        recoverySpeed = 0.8;
        
        // 监控配置（使用默认值）
        monitoringEnabled = true;
        monitoringReportInterval = 10;
        consoleOutput = false;
        
        logConfigurationSummary();
    }
    

    
    /**
     * 记录配置摘要
     */
    private void logConfigurationSummary() {
        if (consoleOutput) {
            Newboy.INSTANCE.getLogger().info(
                String.format("消息延迟配置加载完成 - 模式: %s, 文本延迟: %dms, 媒体延迟: %dms, 超时: %ds",
                    delayMode.getValue(), textDelay, mediaDelay, processingTimeout));
        }
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized MessageDelayConfig getInstance() {
        if (instance == null) {
            instance = new MessageDelayConfig();
        }
        return instance;
    }
    
    // Getter方法
    public DelayMode getDelayMode() { return delayMode; }
    public int getTextDelay() { return textDelay; }
    public int getMediaDelay() { return mediaDelay; }
    public int getGroupHighPriorityDelay() { return groupHighPriorityDelay; }
    public int getGroupLowPriorityDelay() { return groupLowPriorityDelay; }
    public int getProcessingTimeout() { return processingTimeout; }
    public int getBatchTimeoutBuffer() { return batchTimeoutBuffer; }
    public int getHighLoadMultiplier() { return highLoadMultiplier; }
    public int getCriticalLoadMultiplier() { return criticalLoadMultiplier; }
    
    public boolean isDynamicDelayEnabled() { return dynamicDelayEnabled; }
    public int getFailureThreshold() { return failureThreshold; }
    public double getFailureDelayMultiplier() { return failureDelayMultiplier; }
    public double getRecoverySpeed() { return recoverySpeed; }
    
    public boolean isMonitoringEnabled() { return monitoringEnabled; }
    public int getMonitoringReportInterval() { return monitoringReportInterval; }
    public boolean isConsoleOutput() { return consoleOutput; }
    
    /**
     * 获取配置摘要
     */
    public String getConfigSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("⚡ 消息延迟优化配置\n");
        summary.append("━━━━━━━━━━━━━━━━━━━━\n");
        summary.append(String.format("🎯 延迟模式: %s\n", delayMode.getValue()));
        summary.append(String.format("📝 文本消息延迟: %d ms\n", textDelay));
        summary.append(String.format("🎬 媒体消息延迟: %d ms\n", mediaDelay));
        summary.append(String.format("⏱️ 处理超时: %d 秒\n", processingTimeout));
        summary.append(String.format("🔧 动态调整: %s\n", dynamicDelayEnabled ? "启用" : "禁用"));
        summary.append(String.format("📊 延迟监控: %s\n", monitoringEnabled ? "启用" : "禁用"));
        summary.append("━━━━━━━━━━━━━━━━━━━━");
        return summary.toString();
    }
    
    /**
     * 重新加载配置
     */
    public static void reload() {
        instance = new MessageDelayConfig();
    }
}