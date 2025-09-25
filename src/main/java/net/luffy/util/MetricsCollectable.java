package net.luffy.util;

import java.util.Map;

/**
 * 指标收集接口
 * 定义统一的指标收集标准，所有需要收集指标的组件都应实现此接口
 */
public interface MetricsCollectable {
    
    /**
     * 获取组件名称
     * @return 组件名称，用于指标分类
     */
    String getComponentName();
    
    /**
     * 收集当前组件的指标
     * @return 指标键值对，键为指标名称，值为指标值
     */
    Map<String, Object> collectMetrics();
    
    /**
     * 获取组件健康状态
     * @return 健康状态，0-100的数值，100表示完全健康
     */
    default double getHealthScore() {
        return 100.0;
    }
    
    /**
     * 获取组件状态描述
     * @return 状态描述字符串
     */
    default String getStatusDescription() {
        double health = getHealthScore();
        if (health >= 90) return "优秀";
        if (health >= 80) return "良好";
        if (health >= 70) return "一般";
        if (health >= 60) return "需要关注";
        return "异常";
    }
    
    /**
     * 重置指标统计
     */
    default void resetMetrics() {
        // 默认实现为空，子类可以重写
    }
    
    /**
     * 是否启用指标收集
     * @return true表示启用，false表示禁用
     */
    default boolean isMetricsEnabled() {
        return true;
    }
}