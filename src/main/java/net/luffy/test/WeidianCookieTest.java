package net.luffy.test;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONArray;

/**
 * 微店Cookie有效性测试工具
 * 用于测试提供的Cookie在所有微店API中的有效性
 */
public class WeidianCookieTest {
    
    // API端点定义
    private static final String APIOrderList = "https://thor.weidian.com/tradeview/seller.getOrderListForPC/1.0";
    private static final String APIDeliver = "https://thor.weidian.com/tradeview/seller.deliverOrder/1.0";
    private static final String APIItemList = "https://thor.weidian.com/wditem/itemList.pcListItems/1.0?param=%7B%22pageSize%22%3A5%2C%22pageNum%22%3A0%2C%22listStatus%22%3A%222%22%2C%22sorts%22%3A%5B%7B%22field%22%3A%22add_time%22%2C%22mode%22%3A%22desc%22%7D%5D%2C%22shopId%22%3A%22%22%7D&wdtoken=";
    private static final String APISkuInfo = "https://thor.weidian.com/detail/getItemSkuInfo/1.0?param=%7B%22itemId%22%3A%22123456%22%7D";
    
    // 测试用的Cookie
    private static final String TEST_COOKIE = "__spider__visitorid=814a1ee4b958e2f3; is_login=true; login_source=LOGIN_USER_SOURCE_MASTER; duid=1860198031; smart_login_type=0; login_type=SUB_ACCOUNT; uid=901922016572; sid=1847397518; hi_dxh=; hold=; cn_merchant=; Hm_lvt_f3b91484e26c0d850ada494bff4b469b=1758908656,1760282154,1760337283; login_token=_EwWqqVIQDnD_WR87tOGamFSfJ8ULd8ngZdl-jqC4g6Y1YCpSem7wwT9x1r7N1JGLk7tLJ_p9qgRw7nTUw9BWI-itJ4M2JmmSbhu4bZZC08u-2hTSA4g6gOGWPYeV1DWiuRDovdBfwLaNZxh-mybYNH00FAjZTPKHXfhK7z9GaRYvYuwQeOVvuhIx8TP9qsD7L1LO-DpyG_KLKF919JPDywCAPEJ8AfJZzYaq7HeSiFeVVySUSpegyGy8lXZ8MRCSgsakNqLh; wdtoken=df627326; __spider__sessionid=cf2dc6274ea857f6";
    private static final String WDTOKEN = "df627326";
    
    public static void main(String[] args) {
        System.out.println("=== 微店Cookie有效性测试 ===\n");
        System.out.println("测试Cookie: " + TEST_COOKIE.substring(0, 50) + "...\n");
        
        WeidianCookieTest tester = new WeidianCookieTest();
        
        // 测试所有API
        tester.testItemListAPI();
        tester.testOrderListAPI();
        tester.testSkuInfoAPI();
        
        System.out.println("\n=== 测试完成 ===");
    }
    
    /**
     * 设置HTTP请求头
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
     * 测试商品列表API
     */
    public void testItemListAPI() {
        System.out.println("1. 测试商品列表API (getItems)");
        System.out.println("URL: " + APIItemList + WDTOKEN);
        
        try {
            String response = setHeader(HttpRequest.get(APIItemList + WDTOKEN)).execute().body();
            System.out.println("响应长度: " + response.length() + " 字符");
            
            JSONObject object = JSONUtil.parseObj(response);
            JSONObject status = object.getJSONObject("status");
            int code = status.getInt("code");
            String message = status.getStr("message");
            
            System.out.println("状态码: " + code);
            System.out.println("状态消息: " + message);
            
            if (code == 0) {
                JSONObject result = object.getJSONObject("result");
                JSONArray dataList = result.getJSONArray("dataList");
                System.out.println("✅ API调用成功");
                System.out.println("商品数量: " + dataList.size());
                
                if (dataList.size() > 0) {
                    JSONObject firstItem = dataList.getJSONObject(0);
                    System.out.println("第一个商品:");
                    System.out.println("  - ID: " + firstItem.getLong("itemId"));
                    System.out.println("  - 名称: " + firstItem.getStr("itemName"));
                    System.out.println("  - 图片: " + firstItem.getStr("imgHead"));
                }
            } else {
                System.out.println("❌ API调用失败");
            }
            
            System.out.println("完整响应:");
            System.out.println(response);
            
        } catch (Exception e) {
            System.out.println("❌ 请求异常: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n" + "=".repeat(60) + "\n");
    }
    
    /**
     * 测试订单列表API
     */
    public void testOrderListAPI() {
        System.out.println("2. 测试订单列表API (getOrderList)");
        System.out.println("URL: " + APIOrderList);
        
        String requestBody = "param={\"listType\":0,\"pageNum\":0,\"pageSize\":40,\"statusList\":[\"paid\"],\"refundStatusList\":[],\"channel\":\"pc\",\"shipRole\":0,\"orderIdList\":\"\",\"itemTitle\":\"\",\"buyerName\":\"\",\"timeSearch\":{},\"orderBizType\":\"\",\"promotionType\":\"\",\"shipType\":\"\",\"newGhSearchSellerRole\":7,\"memberLevel\":\"all\",\"repayStatus\":2,\"bSellerId\":\"\",\"itemSource\":\"\",\"shipper\":\"\",\"nSellerName\":\"\",\"partnerName\":\"\",\"noteSearchCondition\":{\"buyerNote\":\"\"},\"specialOrderSearchCondition\":{\"notShowGroupUnsuccess\":0,\"notShowFxOrder\":0,\"notShowUnRepayOrder\":0,\"notShowBuyerRepayOrder\":0,\"showAllPeriodOrder\":0,\"notShowTencentShopOrder\":0,\"notShowWithoutTimelinessOrder\":0},\"orderType\":4}&wdtoken=" + WDTOKEN;
        
        try {
            String response = setHeader(HttpRequest.post(APIOrderList))
                    .header("Referer", "https://d.weidian.com/")
                    .body(requestBody)
                    .execute().body();
            
            System.out.println("响应长度: " + response.length() + " 字符");
            
            JSONObject object = JSONUtil.parseObj(response);
            JSONObject status = object.getJSONObject("status");
            int code = status.getInt("code");
            String message = status.getStr("message");
            
            System.out.println("状态码: " + code);
            System.out.println("状态消息: " + message);
            
            if (code == 0) {
                JSONObject result = object.getJSONObject("result");
                JSONArray orderList = result.getJSONArray("orderList");
                System.out.println("✅ API调用成功");
                System.out.println("订单数量: " + orderList.size());
                
                if (orderList.size() > 0) {
                    JSONObject firstOrder = orderList.getJSONObject(0);
                    System.out.println("第一个订单:");
                    System.out.println("  - 订单号: " + firstOrder.getStr("orderNo"));
                    System.out.println("  - 状态: " + firstOrder.getStr("orderStatus"));
                    System.out.println("  - 金额: " + firstOrder.getStr("totalPrice"));
                }
            } else {
                System.out.println("❌ API调用失败");
            }
            
            System.out.println("完整响应:");
            System.out.println(response);
            
        } catch (Exception e) {
            System.out.println("❌ 请求异常: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n" + "=".repeat(60) + "\n");
    }
    
    /**
     * 测试SKU信息API (无需Cookie)
     */
    public void testSkuInfoAPI() {
        System.out.println("3. 测试SKU信息API (getSkuInfo) - 无需Cookie");
        System.out.println("URL: " + APISkuInfo);
        
        try {
            // 这个API不需要Cookie，但我们仍然测试一下
            String response = HttpRequest.get(APISkuInfo)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .execute().body();
            
            System.out.println("响应长度: " + response.length() + " 字符");
            
            JSONObject object = JSONUtil.parseObj(response);
            JSONObject status = object.getJSONObject("status");
            int code = status.getInt("code");
            String message = status.getStr("message");
            
            System.out.println("状态码: " + code);
            System.out.println("状态消息: " + message);
            
            if (code == 0) {
                System.out.println("✅ API调用成功");
                JSONObject result = object.getJSONObject("result");
                if (result != null) {
                    System.out.println("返回结果: " + result.toString());
                }
            } else {
                System.out.println("❌ API调用失败 (可能是测试商品ID不存在)");
            }
            
            System.out.println("完整响应:");
            System.out.println(response);
            
        } catch (Exception e) {
            System.out.println("❌ 请求异常: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("\n" + "=".repeat(60) + "\n");
    }
}