package net.luffy.util;

import net.luffy.Newboy;
import net.luffy.handler.Xox48Handler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 监控系统测试类
 * 用于验证优化后的监控系统功能
 */
public class MonitorSystemTest {
    
    private OnlineStatusMonitor monitor;
    private MonitorConfig config;
    private Xox48Handler handler;
    
    @BeforeEach
    void setUp() {
        // 初始化测试环境
        monitor = OnlineStatusMonitor.INSTANCE;
        config = MonitorConfig.getInstance();
        handler = new Xox48Handler();
    }
    
    @Test
    @DisplayName("测试配置加载")
    void testConfigurationLoading() {
        assertNotNull(config, "配置管理器应该成功初始化");
        
        // 验证网络配置
        assertTrue(config.getConnectTimeout() > 0, "连接超时应该大于0");
        assertTrue(config.getReadTimeout() > 0, "读取超时应该大于0");
        assertTrue(config.getMaxRetries() > 0, "最大重试次数应该大于0");
        
        // 验证健康检查配置
        assertTrue(config.getMaxConsecutiveFailures() > 0, "最大连续失败次数应该大于0");
        assertTrue(config.getHealthCheckInterval() > 0, "健康检查间隔应该大于0");
        assertTrue(config.getFailureRateThreshold() >= 0 && config.getFailureRateThreshold() <= 1, 
                  "失败率阈值应该在0-1之间");
        
        // 验证缓存配置
        assertTrue(config.getCacheExpireTime() > 0, "缓存过期时间应该大于0");
        
        System.out.println("✅ 配置加载测试通过");
        System.out.println(config.getConfigSummary());
    }
    
    @Test
    @DisplayName("测试监控统计功能")
    void testMonitoringStats() {
        assertNotNull(monitor, "监控器应该成功初始化");
        
        // 测试统计信息获取
        String stats = monitor.getMonitorStats();
        assertNotNull(stats, "监控统计信息不应为空");
        assertTrue(stats.contains("监控统计信息"), "统计信息应包含标题");
        
        // 测试健康报告
        String healthReport = monitor.getHealthReport();
        assertNotNull(healthReport, "健康报告不应为空");
        assertTrue(healthReport.contains("监控系统健康报告"), "健康报告应包含标题");
        
        System.out.println("✅ 监控统计功能测试通过");
        System.out.println(stats);
    }
    
    @Test
    @DisplayName("测试缓存功能")
    void testCachingMechanism() {
        // 测试缓存统计
        String cacheStats = handler.getCacheStats();
        assertNotNull(cacheStats, "缓存统计信息不应为空");
        assertTrue(cacheStats.contains("缓存统计"), "缓存统计应包含标题");
        
        // 测试缓存重置
        assertDoesNotThrow(() -> handler.resetCache(), "缓存重置不应抛出异常");
        
        System.out.println("✅ 缓存功能测试通过");
        System.out.println(cacheStats);
    }
    
    @Test
    @DisplayName("测试性能监控")
    void testPerformanceMonitoring() {
        // 测试WebHandler性能统计
        String perfStats = handler.getPerformanceStats();
        assertNotNull(perfStats, "性能统计信息不应为空");
        
        // 测试统计重置
        assertDoesNotThrow(() -> handler.resetStats(), "统计重置不应抛出异常");
        
        System.out.println("✅ 性能监控测试通过");
        System.out.println(perfStats);
    }
    
    @Test
    @DisplayName("测试健康检查机制")
    void testHealthCheckMechanism() {
        // 测试成员健康信息获取
        String memberHealth = monitor.getMemberHealthInfo("测试成员");
        assertNotNull(memberHealth, "成员健康信息不应为空");
        
        // 测试统计重置
        assertDoesNotThrow(() -> monitor.resetStats(), "监控统计重置不应抛出异常");
        
        System.out.println("✅ 健康检查机制测试通过");
        System.out.println(memberHealth);
    }
    
    @Test
    @DisplayName("测试配置验证")
    void testConfigurationValidation() {
        // 验证关键配置的合理性
        assertTrue(config.getConnectTimeout() <= 60000, "连接超时不应超过60秒");
        assertTrue(config.getReadTimeout() <= 120000, "读取超时不应超过120秒");
        assertTrue(config.getMaxRetries() <= 10, "最大重试次数不应超过10次");
        assertTrue(config.getCacheExpireTime() >= 10000, "缓存时间不应少于10秒");
        assertTrue(config.getMaxConsecutiveFailures() >= 1, "最大连续失败次数至少为1");
        
        System.out.println("✅ 配置验证测试通过");
    }
    
    @Test
    @DisplayName("集成测试 - 完整监控流程")
    void testCompleteMonitoringFlow() {
        System.out.println("🚀 开始集成测试...");
        
        // 1. 验证配置加载
        assertNotNull(config, "配置应该正确加载");
        System.out.println("✓ 配置加载成功");
        
        // 2. 验证监控器初始化
        assertNotNull(monitor, "监控器应该正确初始化");
        System.out.println("✓ 监控器初始化成功");
        
        // 3. 验证处理器初始化
        assertNotNull(handler, "处理器应该正确初始化");
        System.out.println("✓ 处理器初始化成功");
        
        // 4. 测试统计功能
        assertDoesNotThrow(() -> {
            monitor.getMonitorStats();
            monitor.getHealthReport();
            handler.getCacheStats();
            handler.getPerformanceStats();
        }, "统计功能应该正常工作");
        System.out.println("✓ 统计功能正常");
        
        // 5. 测试重置功能
        assertDoesNotThrow(() -> {
            monitor.resetStats();
            handler.resetStats();
            handler.resetCache();
        }, "重置功能应该正常工作");
        System.out.println("✓ 重置功能正常");
        
        System.out.println("🎉 集成测试完成！监控系统优化成功！");
        
        // 输出最终状态
        System.out.println("\n📊 最终系统状态:");
        System.out.println(config.getConfigSummary());
        System.out.println("\n" + monitor.getHealthReport());
    }
}