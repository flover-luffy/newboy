package net.luffy.test;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONArray;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 微店API原始返回体测试工具 - 输出到文件避免编码问题
 */
public class WeidianApiRawTest {
    
    // 使用项目中完全相同的API定义
    private static final String APIOrderList = "https://thor.weidian.com/tradeview/seller.getOrderListForPC/1.0";
    private static final String APIItemList = "https://thor.weidian.com/wditem/itemList.pcListItems/1.0?param=%7B%22pageSize%22%3A5%2C%22pageNum%22%3A0%2C%22listStatus%22%3A%222%22%2C%22sorts%22%3A%5B%7B%22field%22%3A%22add_time%22%2C%22mode%22%3A%22desc%22%7D%5D%2C%22shopId%22%3A%22%22%7D&wdtoken=";
    
    // 测试用的Cookie
    private static final String TEST_COOKIE = "__spider__visitorid=37b93c5c817e35ac; is_login=true; login_type=LOGIN_USER_TYPE_MASTER; login_source=LOGIN_USER_SOURCE_MASTER; uid=1613774552; duid=1613774552; sid=1877990410; smart_login_type=0; login_token=_EwWqqVIQc_7viHI-pIE_MhbkKXgBt8t-zV5--G6L3Ei_1rB56QWsBnHGu07zilKuIIm_rrcOFhl7XXzioHC25KfsxGv7_iPMiwtdXiWMd37Dgnelw1bjrhlKtTW7PXd7F3pmBfzPBunNbNP-N2EoEyMfUr3c6EUc4E1LGj9FFKqlOjSmcqhrUDYBy0njR4ure8rqRV6SL3ZZiCcblGMN6xAqIfCFJunxjbgXX82I1G3anEWIRjtnLYztezIf-Wtjw-UmFamY; hi_dxh=; hold=; cn_merchant=; wdtoken=cfa7e1a7; __spider__sessionid=2766c08379eaef7f; Hm_lvt_f3b91484e26c0d850ada494bff4b469b=1760414813; Hm_lpvt_f3b91484e26c0d850ada494bff4b469b=1760414813; HMACCOUNT=20DD82A1A81BBC35";
    private static final String WDTOKEN = "cfa7e1a7";
    
    public static void main(String[] args) {
        System.out.println("=== 微店API原始返回体测试 - 输出到文件 ===");
        
        WeidianApiRawTest tester = new WeidianApiRawTest();
        
        // 测试商品列表API
        System.out.println("正在获取商品列表API返回体...");
        String itemResponse = tester.getItemListResponse();
        tester.saveToFile("item_list_response.json", itemResponse);
        tester.parseAndDisplayItems(itemResponse);
        
        // 测试订单列表API
        System.out.println("\n正在获取订单列表API返回体...");
        String orderResponse = tester.getOrderListResponse();
        tester.saveToFile("order_list_response.json", orderResponse);
        
        System.out.println("\n完整返回体已保存到文件:");
        System.out.println("- item_list_response.json (商品列表)");
        System.out.println("- order_list_response.json (订单列表)");
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
     * 获取商品列表API响应
     */
    public String getItemListResponse() {
        try {
            return setHeader(HttpRequest.get(APIItemList + WDTOKEN)).execute().body();
        } catch (Exception e) {
            System.out.println("请求异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取订单列表API响应
     */
    public String getOrderListResponse() {
        String requestBody = "param={\"listType\":0,\"pageNum\":0,\"pageSize\":40,\"statusList\":[\"paid\"],\"refundStatusList\":[],\"channel\":\"pc\",\"shipRole\":0,\"orderIdList\":\"\",\"itemTitle\":\"\",\"buyerName\":\"\",\"timeSearch\":{},\"orderBizType\":\"\",\"promotionType\":\"\",\"shipType\":\"\",\"newGhSearchSellerRole\":7,\"memberLevel\":\"all\",\"repayStatus\":2,\"bSellerId\":\"\",\"itemSource\":\"\",\"shipper\":\"\",\"nSellerName\":\"\",\"partnerName\":\"\",\"noteSearchCondition\":{\"buyerNote\":\"\"},\"specialOrderSearchCondition\":{\"notShowGroupUnsuccess\":0,\"notShowFxOrder\":0,\"notShowUnRepayOrder\":0,\"notShowBuyerRepayOrder\":0,\"showAllPeriodOrder\":0,\"notShowTencentShopOrder\":0,\"notShowWithoutTimelinessOrder\":0},\"orderType\":4}&wdtoken=" + WDTOKEN;
        
        try {
            return setHeader(HttpRequest.post(APIOrderList))
                    .header("Referer", "https://d.weidian.com/")
                    .body(requestBody)
                    .execute().body();
        } catch (Exception e) {
            System.out.println("请求异常: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 保存响应到文件
     */
    private void saveToFile(String filename, String content) {
        if (content == null) {
            System.out.println("内容为空，无法保存到文件: " + filename);
            return;
        }
        
        try (FileWriter writer = new FileWriter(filename, StandardCharsets.UTF_8)) {
            writer.write(content);
            System.out.println("已保存到文件: " + filename + " (长度: " + content.length() + " 字符)");
        } catch (IOException e) {
            System.out.println("保存文件失败: " + e.getMessage());
        }
    }
    
    /**
     * 解析并显示商品信息
     */
    private void parseAndDisplayItems(String response) {
        if (response == null) {
            System.out.println("响应为空，无法解析");
            return;
        }
        
        try {
            JSONObject object = JSONUtil.parseObj(response);
            JSONObject status = object.getJSONObject("status");
            int code = status.getInt("code");
            
            System.out.println("API状态码: " + code);
            
            if (code == 0) {
                JSONObject result = object.getJSONObject("result");
                JSONArray data = result.getJSONArray("dataList");
                int totalNum = result.getInt("totalNum");
                
                System.out.println("总商品数量: " + totalNum);
                System.out.println("返回商品数量: " + data.size());
                System.out.println("\n商品详情:");
                
                for (int i = 0; i < data.size(); i++) {
                    JSONObject item = data.getJSONObject(i);
                    long itemId = item.getLong("itemId");
                    String itemName = item.getStr("itemName");
                    String price = item.getStr("price");
                    int stock = item.getInt("stock");
                    int sold = item.getInt("sold");
                    int itemStatus = item.getInt("status");
                    
                    System.out.println((i + 1) + ". 商品ID: " + itemId);
                    System.out.println("   商品名称: " + itemName);
                    System.out.println("   价格: " + price);
                    System.out.println("   库存: " + stock);
                    System.out.println("   已售: " + sold);
                    System.out.println("   状态: " + itemStatus);
                    System.out.println();
                }
            } else {
                System.out.println("API返回错误: " + status.getStr("message"));
            }
        } catch (Exception e) {
            System.out.println("解析响应失败: " + e.getMessage());
        }
    }
}