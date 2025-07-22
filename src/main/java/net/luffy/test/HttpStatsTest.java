package net.luffy.test;

import net.luffy.util.UnifiedHttpClient;

/**
 * 简化的HTTP性能统计测试类
 */
public class HttpStatsTest {
    
    public static void main(String[] args) {
        System.out.println("=== HTTP性能统计测试开始 ===");
        
        try {
            // 获取UnifiedHttpClient实例
            UnifiedHttpClient httpClient = UnifiedHttpClient.getInstance();
            
            // 显示初始统计
            System.out.println("\n初始统计:");
            System.out.println(httpClient.getPerformanceStats());
            
            // 执行一个简单的HTTP请求
            System.out.println("\n执行测试请求...");
            
            try {
                String result = httpClient.get("https://httpbin.org/get");
                System.out.println("GET请求成功，响应长度: " + result.length());
            } catch (Exception e) {
                System.out.println("GET请求失败: " + e.getMessage());
            }
            
            // 显示最终统计
            System.out.println("\n=== 最终统计结果 ===");
            System.out.println(httpClient.getPerformanceStats());
            
        } catch (Exception e) {
            System.err.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n=== HTTP性能统计测试结束 ===");
    }
}