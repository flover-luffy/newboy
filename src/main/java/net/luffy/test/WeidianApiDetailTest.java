package net.luffy.test;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/**
 * 微店API详细测试工具 - 使用项目相同的处理逻辑
 */
public class WeidianApiDetailTest {
    
    // 使用项目中完全相同的API定义
    private static final String APIOrderList = "https://thor.weidian.com/tradeview/seller.getOrderListForPC/1.0";
    private static final String APIItemList = "https://thor.weidian.com/wditem/itemList.pcListItems/1.0?param=%7B%22pageSize%22%3A5%2C%22pageNum%22%3A0%2C%22listStatus%22%3A%222%22%2C%22sorts%22%3A%5B%7B%22field%22%3A%22add_time%22%2C%22mode%22%3A%22desc%22%7D%5D%2C%22shopId%22%3A%22%22%7D&wdtoken=";
    private static final String APISkuInfo = "https://thor.weidian.com/detail/getItemSkuInfo/1.0?param=%7B%22itemId%22%3A%22123456%22%7D";
    
    // 测试用的Cookie
    private static final String TEST_COOKIE = "__spider__visitorid=814a1ee4b958e2f3; is_login=true; login_source=LOGIN_USER_SOURCE_MASTER; duid=1860198031; smart_login_type=0; login_type=SUB_ACCOUNT; uid=901922016572; sid=1847397518; hi_dxh=; hold=; cn_merchant=; Hm_lvt_f3b91484e26c0d850ada494bff4b469b=1758908656,1760282154,1760337283; login_token=_EwWqqVIQDnD_WR87tOGamFSfJ8ULd8ngZdl-jqC4g6Y1YCpSem7wwT9x1r7N1JGLk7tLJ_p9qgRw7nTUw9BWI-itJ4M2JmmSbhu4bZZC08u-2hTSA4g6gOGWPYeV1DWiuRDovdBfwLaNZxh-mybYNH00FAjZTPKHXfhK7z9GaRYvYuwQeOVvuhIx8TP9qsD7L1LO-DpyG_KLKF919JPDywCAPEJ8AfJZzYaq7HeSiFeVVySUSpegyGy8lXZ8MRCSgsakNqLh; wdtoken=df627326; __spider__sessionid=cf2dc6274ea857f6";
    private static final String WDTOKEN = "df627326";
    
    public static void main(String[] args) {
        System.out.println("=== 微店API完整返回体测试 ===");
        
        WeidianApiDetailTest tester = new WeidianApiDetailTest();
        
        System.out.println("\n商品列表API完整返回体:");
        System.out.println("==========================================");
        tester.testItemListRawResponse();
        
        System.out.println("\n订单列表API完整返回体:");
        System.out.println("==========================================");
        tester.testOrderListAPI();
    }
    
    /**
     * 设置HTTP请求头 - 使用项目相同的逻辑
     */
    private HttpRequest setHeader(HttpRequest request) {
        return request.header("Host", "thor.weidian.com")
                .header("Connection", "keep-alive")
                .header("sec-ch-ua", "\"Google Chrome\";v=\"107\", \"Chromium\";v=\"107\", \"Not=A?Brand\";v=\"24\"")
                .header("Accept", "application/json, */*")
                .header("sec-ch-ua-mobile", "?0")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36")
                .header("Sec-Fetch-Site", "same-site")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7,zh-TW;q=0.6")
                .header("Cookie", TEST_COOKIE);
    }
    
    /**
     * 模拟项目中get方法的逻辑
     */
    private String get(String url) {
        try {
            return setHeader(HttpRequest.get(url)).execute().body();
        } catch (Exception e) {
            System.out.println("HTTP请求异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 使用项目中getItems()完全相同的逻辑进行测试
     */
    public void testItemsWithProjectLogic() {
        System.out.println("使用项目WeidianHandler.getItems()相同的处理逻辑:");
        System.out.println("API URL: " + APIItemList + WDTOKEN);
        
        // 完全按照项目逻辑处理
        int maxRetries = 2;
        Exception lastException = null;
        
        for (int retry = 0; retry <= maxRetries; retry++) {
            try {
                String s = get(APIItemList + WDTOKEN);
                
                System.out.println("\n第" + (retry + 1) + "次请求结果:");
                System.out.println("响应长度: " + (s != null ? s.length() : "null") + " 字符");
                
                // 检查响应是否为空或无效
                if (s == null || s.trim().isEmpty()) {
                    System.out.println("响应为空，准备重试...");
                    if (retry < maxRetries) {
                        Thread.sleep(1000); // 等待1秒后重试
                        continue;
                    }
                    System.out.println("❌ 最终结果: 返回null (响应为空)");
                    return;
                }

                System.out.println("原始响应体:");
                System.out.println(s);
                System.out.println("\n解析响应:");

                JSONObject object = JSONUtil.parseObj(s);
                JSONObject status = object.getJSONObject("status");
                int code = status.getInt("code");
                System.out.println("状态码: " + code);
                
                if (code == 0) {
                    JSONObject result = object.getJSONObject("result");
                    JSONArray data = result.getJSONArray("dataList");
                    System.out.println("商品数组长度: " + data.size());
                    System.out.println("总商品数量(totalNum): " + result.getInt("totalNum"));
                    
                    List<String> items = new ArrayList<>();
                    for (Object item_ : data.toArray(new Object[0])) {
                        JSONObject item = JSONUtil.parseObj(item_);
                        long id = item.getLong("itemId");
                        String name = item.getStr("itemName");
                        String pic = item.getStr("imgHead");
                        items.add("ID:" + id + ", 名称:" + name);
                    }
                    
                    System.out.println("✅ 成功解析到 " + items.size() + " 个商品:");
                    for (int i = 0; i < items.size(); i++) {
                        System.out.println("  " + (i + 1) + ". " + items.get(i));
                    }
                    return;
                } else {
                    System.out.println("❌ API返回错误状态码: " + code);
                    System.out.println("错误信息: " + status.getStr("message"));
                    return;
                }
            } catch (Exception e) {
                lastException = e;
                System.out.println("❌ 第" + (retry + 1) + "次请求异常: " + e.getMessage());
                if (retry < maxRetries) {
                    try {
                        System.out.println("等待1秒后重试...");
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        System.out.println("❌ 线程被中断");
                        return;
                    }
                } else {
                    System.out.println("❌ 重试" + maxRetries + "次后仍然失败: " + e.getMessage());
                }
            }
        }
        System.out.println("❌ 最终结果: 返回null (重试失败)");
    }
    
    /**
     * 获取商品列表API的原始返回体
     */
    public void testItemListRawResponse() {
        try {
            String response = get(APIItemList + WDTOKEN);
            if (response != null) {
                System.out.println("原始返回体:");
                System.out.println(response);
            } else {
                System.out.println("请求失败，返回null");
            }
        } catch (Exception e) {
            System.out.println("请求异常: " + e.getMessage());
        }
    }
    
    /**
     * 测试订单列表API
     */
    public void testOrderListAPI() {
        String requestBody = "param={\"listType\":0,\"pageNum\":0,\"pageSize\":40,\"statusList\":[\"paid\"],\"refundStatusList\":[],\"channel\":\"pc\",\"shipRole\":0,\"orderIdList\":\"\",\"itemTitle\":\"\",\"buyerName\":\"\",\"timeSearch\":{},\"orderBizType\":\"\",\"promotionType\":\"\",\"shipType\":\"\",\"newGhSearchSellerRole\":7,\"memberLevel\":\"all\",\"repayStatus\":2,\"bSellerId\":\"\",\"itemSource\":\"\",\"shipper\":\"\",\"nSellerName\":\"\",\"partnerName\":\"\",\"noteSearchCondition\":{\"buyerNote\":\"\"},\"specialOrderSearchCondition\":{\"notShowGroupUnsuccess\":0,\"notShowFxOrder\":0,\"notShowUnRepayOrder\":0,\"notShowBuyerRepayOrder\":0,\"showAllPeriodOrder\":0,\"notShowTencentShopOrder\":0,\"notShowWithoutTimelinessOrder\":0},\"orderType\":4}&wdtoken=" + WDTOKEN;
        
        try {
            String response = setHeader(HttpRequest.post(APIOrderList))
                    .header("Referer", "https://d.weidian.com/")
                    .body(requestBody)
                    .execute().body();
            System.out.println(response);
        } catch (Exception e) {
            System.out.println("请求异常: " + e.getMessage());
        }
    }
}