package net.luffy.handler;

import cn.hutool.core.date.DateUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.model.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class WeidianHandler extends SyncWebHandler {

    private static final String APIOrderList = "https://thor.weidian.com/tradeview/seller.getOrderListForPC/1.0";
    private static final String APIDeliver = "https://thor.weidian.com/tradeview/seller.deliverOrder/1.0";
    private static final String APIItemList = "https://thor.weidian.com/wditem/itemList.pcListItems/1.0?param=%7B%22pageSize%22%3A5%2C%22pageNum%22%3A0%2C%22listStatus%22%3A%222%22%2C%22sorts%22%3A%5B%7B%22field%22%3A%22add_time%22%2C%22mode%22%3A%22desc%22%7D%5D%2C%22shopId%22%3A%22%22%7D&wdtoken=";
    //无需cookie
    private static final String APISkuInfo = "https://thor.weidian.com/detail/getItemSkuInfo/1.0?param=%7B%22itemId%22%3A%22%d%22%7D";
    
    //setDefaultHeader
    protected HttpRequest setHeader(HttpRequest request) {
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
                .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7,zh-TW;q=0.6");
    }

    protected HttpRequest setHeader(HttpRequest request, WeidianCookie cookie) {
        return setHeader(request).header("Cookie", cookie.cookie);
    }

    protected String post(String url, String body, WeidianCookie cookie) {
        return setHeader(HttpRequest.post(url).header("Referer", "https://d.weidian.com/"), cookie)
                .body(body).execute().body();
    }

    protected String get(String url, WeidianCookie cookie) {
        return setHeader(HttpRequest.get(url), cookie).execute().body();
    }
    
    protected String get(String url) {
        return setHeader(HttpRequest.get(url)).execute().body();
    }

    private JSONArray getOriOrderList(WeidianCookie cookie) {
        //获取【待发货】列表中的订单
        String s = post(APIOrderList, "param={\"listType\":0,\"pageNum\":0,\"pageSize\":40,\"statusList\":[\"paid\"],\"refundStatusList\":[],\"channel\":\"pc\",\"shipRole\":0,\"orderIdList\":\"\",\"itemTitle\":\"\",\"buyerName\":\"\",\"timeSearch\":{},\"orderBizType\":\"\",\"promotionType\":\"\",\"shipType\":\"\",\"newGhSearchSellerRole\":7,\"memberLevel\":\"all\",\"repayStatus\":2,\"bSellerId\":\"\",\"itemSource\":\"\",\"shipper\":\"\",\"nSellerName\":\"\",\"partnerName\":\"\",\"noteSearchCondition\":{\"buyerNote\":\"\"},\"specialOrderSearchCondition\":{\"notShowGroupUnsuccess\":0,\"notShowFxOrder\":0,\"notShowUnRepayOrder\":0,\"notShowBuyerRepayOrder\":0,\"showAllPeriodOrder\":0,\"notShowTencentShopOrder\":0,\"notShowWithoutTimelinessOrder\":0},\"orderType\":4}&wdtoken=" + cookie.wdtoken, cookie);
        
        if (s == null || s.trim().isEmpty()) {
            logInfo("获取订单列表API响应为空，可能是cookie失效");
            return null;
        }
        
        // 检查是否是登录页面重定向
        if (s.contains("<html>") || s.contains("<!DOCTYPE") || s.contains("login") || s.contains("登录")) {
            logInfo("获取订单列表时检测到登录页面重定向，cookie已失效");
            return null;
        }
        
        if (!s.trim().startsWith("{")) {
            logInfo("获取订单列表响应不是有效JSON，可能是cookie失效: " + s.substring(0, Math.min(100, s.length())));
            return null;
        }
        
        JSONObject object = JSONUtil.parseObj(s);
        
        JSONObject status = object.getJSONObject("status");
        if (status == null) {
            logInfo("订单列表API响应缺少status字段");
            return null;
        }
        
        int code = status.getInt("code");
        String message = status.getStr("message");
        
        if (code == 0) {
            JSONObject result = object.getJSONObject("result");
            if (result == null) {
                logInfo("订单列表API响应缺少result字段");
                return null;
            }
            return result.getJSONArray("orderList");
        } else {
            logInfo("获取订单列表失败，code: " + code + ", message: " + message + "，可能是cookie失效");
            return null;
        }
    }

    public WeidianOrder[] getOrderList(WeidianCookie cookie) {
        if (cookie == null)
            return null;

        JSONArray objectList = getOriOrderList(cookie);
        if (objectList == null)
            return null;

        List<WeidianOrder> orders = new ArrayList<>();
        for (Object object : objectList.toArray(new Object[0])) {
            JSONObject order = JSONUtil.parseObj(object);
            String payTime = order.getStr("payTime");

            JSONObject receiver = order.getJSONObject("receiver");
            long buyerID = receiver.getLong("buyerId");
            String buyerName = receiver.getStr("buyerName");

            JSONArray itemList = order.getJSONArray("itemList");
            boolean contains_shielded_item = false;
            for (Object itemObject : itemList.toArray(new Object[0])) {
                JSONObject item = JSONUtil.parseObj(itemObject);
                long itemId = item.getLong("itemId");
                if (cookie.shieldedItem.contains(itemId)) {
                    contains_shielded_item = true;
                }
                String itemName = item.getStr("itemName");
                int price = item.getInt("totalPrice");

                orders.add(new WeidianOrder(itemId, itemName, buyerID, buyerName, price, payTime));
            }

            if (cookie.autoDeliver && !contains_shielded_item) {
                try {
                    if (!deliver(order.getStr("orderId"), cookie)) {
                        logInfo(buyerName + "的订单发货失败");
                    }
                } catch (RuntimeException e) {
                    logInfo(buyerName + "的订单发货失败：" + e.getMessage());
                }
            }
        }

        return orders.toArray(new WeidianOrder[0]);
    }

    //采用13位时间戳
    public WeidianOrder[] getOrderList(WeidianCookie cookie, EndTime endTime) {
        if (cookie == null)
            return null;

        JSONArray objectList = getOriOrderList(cookie);
        if (objectList == null) {
            logInfo("获取原始订单列表失败");
            return null;
        }

        long lastTime = endTime.time;
        List<WeidianOrder> orders = new ArrayList<>(); //拆分订单表：按商品将订单分开
        // logInfo("开始检查订单，当前时间戳: " + lastTime + ", 获取到 " + objectList.size() + " 个订单");
        //复合订单
        int newOrderCount = 0;
        for (Object object : objectList.toArray(new Object[0])) {
            JSONObject order = JSONUtil.parseObj(object);
            String payTime = order.getStr("payTime");
            long time = DateUtil.parse(payTime).getTime();
            if (time <= endTime.time) {
                logInfo("订单时间戳 " + time + " <= 上次检查时间 " + endTime.time + "，停止检查");
                break;
            }

            newOrderCount++;
            if (time > lastTime)
                lastTime = time;

            JSONObject receiver = order.getJSONObject("receiver");
            long buyerID = receiver.getLong("buyerId");
            String buyerName = receiver.getStr("buyerName");

            boolean contains_shielded_item = false;
            JSONArray itemList = order.getJSONArray("itemList");
            for (Object itemObject : itemList.toArray(new Object[0])) {
                JSONObject item = JSONUtil.parseObj(itemObject);
                long itemId = item.getLong("itemId");
                if (cookie.shieldedItem.contains(itemId)) {
                    contains_shielded_item = true;
                }
                String itemName = item.getStr("itemName");
                double price = Double.valueOf(item.getStr("totalPrice"));
                orders.add(new WeidianOrder(itemId, itemName, buyerID, buyerName, price, payTime));
            }

            if (cookie.autoDeliver && !contains_shielded_item) {
                try {
                    if (!deliver(order.getStr("orderId"), cookie)) {
                        logInfo(buyerName + "的订单发货失败");
                    }
                } catch (RuntimeException e) {
                    logInfo(buyerName + "的订单发货失败：" + e.getMessage());
                }
            }
        }
        endTime.time = lastTime;
        
        // logInfo("订单检查完成，发现 " + newOrderCount + " 个新订单，处理了 " + orders.size() + " 个订单项，更新时间戳为: " + lastTime);

        return orders.toArray(new WeidianOrder[0]);
    }

    public boolean deliver(String orderId, WeidianCookie cookie) throws RuntimeException {
        String s = post(APIDeliver, "param={\"from\":\"pc\",\"orderId\":\"" + orderId + "\",\"expressNo\":\"\",\"expressType\":0,\"expressCustom\":\"\",\"fullDeliver\":true}&wdtoken=" + cookie.cookie, cookie);
        JSONObject object = JSONUtil.parseObj(s);
        if (object.getJSONObject("status").getInt("code") == 0) {
            JSONObject result = object.getJSONObject("result");
            return result.getBool("success");
        }
        throw new RuntimeException(object.getJSONObject("status").getStr("message"));
    }

    private JSONArray getItemOriOrderList(WeidianCookie cookie, long itemId) {
        //获取【全部】列表中单个商品的订单（不保证付款）
        String s = post(APIOrderList, "param={\"listType\":0,\"pageNum\":0,\"pageSize\":20,\"statusList\":[\"all\"],\"refundStatusList\":[],\"channel\":\"pc\",\"shipRole\":0,\"orderIdList\":\"\",\"itemId\":\"" + itemId + "\",\"buyerName\":\"\",\"timeSearch\":{},\"orderBizType\":\"\",\"promotionType\":\"\",\"shipType\":\"\",\"newGhSearchSellerRole\":7,\"memberLevel\":\"all\",\"repayStatus\":2,\"bSellerId\":\"\",\"itemSource\":\"\",\"shipper\":\"\",\"nSellerName\":\"\",\"partnerName\":\"\",\"noteSearchCondition\":{\"buyerNote\":\"\"},\"specialOrderSearchCondition\":{\"notShowGroupUnsuccess\":0,\"notShowFxOrder\":0,\"notShowUnRepayOrder\":0,\"notShowBuyerRepayOrder\":0,\"showAllPeriodOrder\":0,\"notShowTencentShopOrder\":0,\"notShowWithoutTimelinessOrder\":0},\"orderType\":2}&wdtoken=" + cookie.wdtoken, cookie);
        JSONObject object = JSONUtil.parseObj(s);
        if (object.getJSONObject("status").getInt("code") == 0) {
            JSONObject result = object.getJSONObject("result");
            int totalNum = result.getInt("total");
            int pageN = (totalNum + 19) / 20;
            if (pageN < 2) {
                return result.getJSONArray("orderList");
            } else {
                String page0 = result.getJSONArray("orderList").toString();
                page0 = page0.substring(0, page0.length() - 1);

                for (int i = 1; i < pageN; i++) {
                    String pagei = getItemOriOrderList(cookie, itemId, i).toString();
                    pagei = pagei.substring(1, pagei.length() - 1);
                    page0 += "," + pagei;
                }
                return JSONUtil.parseArray(page0 + "]");
            }
        }
        return null;
    }

    private JSONArray getItemOriOrderList(WeidianCookie cookie, long itemId, int page) {
        String s = post(APIOrderList, "param={\"listType\":0,\"pageNum\":" + page + ",\"pageSize\":20,\"statusList\":[\"all\"],\"refundStatusList\":[],\"channel\":\"pc\",\"shipRole\":0,\"orderIdList\":\"\",\"itemId\":\"" + itemId + "\",\"buyerName\":\"\",\"timeSearch\":{},\"orderBizType\":\"\",\"promotionType\":\"\",\"shipType\":\"\",\"newGhSearchSellerRole\":7,\"memberLevel\":\"all\",\"repayStatus\":2,\"bSellerId\":\"\",\"itemSource\":\"\",\"shipper\":\"\",\"nSellerName\":\"\",\"partnerName\":\"\",\"noteSearchCondition\":{\"buyerNote\":\"\"},\"specialOrderSearchCondition\":{\"notShowGroupUnsuccess\":0,\"notShowFxOrder\":0,\"notShowUnRepayOrder\":0,\"notShowBuyerRepayOrder\":0,\"showAllPeriodOrder\":0,\"notShowTencentShopOrder\":0,\"notShowWithoutTimelinessOrder\":0},\"orderType\":2}&wdtoken=" + cookie.wdtoken, cookie);
        JSONObject object = JSONUtil.parseObj(s);
        if (object.getJSONObject("status").getInt("code") == 0) {
            JSONObject result = object.getJSONObject("result");
            return result.getJSONArray("orderList");
        }
        return null;
    }

    public WeidianBuyer[] getItemBuyer(WeidianCookie cookie, long itemId) {
        if (cookie == null)
            return null;

        JSONArray objectList = getItemOriOrderList(cookie, itemId);
        if (objectList == null)
            return null;

        List<WeidianBuyer> buyers = new ArrayList<>();
        for (Object object : objectList.toArray(new Object[0])) {
            JSONObject order = JSONUtil.parseObj(object);
            if (order.getStr("statusDesc").equals("已关闭")
                    || order.getStr("statusDesc").equals("待付款"))
                continue;

            JSONObject receiver = order.getJSONObject("receiver");
            addOrderToBuyer(receiver.getLong("buyerId"),
                    receiver.getStr("buyerName"),
                    new BigDecimal(order.getStr("totalPrice")).multiply(new BigDecimal(100)).intValue(),
                    buyers);
        }

        buyers.sort((a, b) -> b.contribution - a.contribution);
        return buyers.toArray(new WeidianBuyer[0]);
    }

    private void addOrderToBuyer(long buyerID, String buyerName, int contribution, List<WeidianBuyer> buyers) {
        for (WeidianBuyer buyer : buyers) {
            if (buyer.id == buyerID) {
                buyer.add(contribution);
                return;
            }
        }
        buyers.add(new WeidianBuyer(buyerID, buyerName, contribution));
    }

    public WeidianItem getItemWithSkus(long itemId) {
        String s = get(String.format(APISkuInfo, itemId));
        JSONObject object = JSONUtil.parseObj(s);
        if (object.getJSONObject("status").getInt("code") == 0) {
            JSONObject result = object.getJSONObject("result");
            String name = result.getStr("itemTitle");
            String pic = result.getStr("itemMainPic");
            WeidianItem item = new WeidianItem(itemId, name, pic);

            JSONArray skus = result.getJSONArray("skuInfos");
            for (Object sku_ : skus.toArray(new Object[0])) {
                JSONObject sku = JSONUtil.parseObj(sku_);
                item.addSkus(
                        sku.getLong("id"),
                        sku.getStr("title"),
                        sku.getStr("img")
                );
            }
            return item;

        }
        return null;
    }

    //包括屏蔽的商品
    public WeidianItem[] getItems(WeidianCookie cookie) {
        //【出售中】 仅提取pageSize=5个
        String requestUrl = APIItemList + cookie.wdtoken;
        logInfo("[微店API调试] 请求URL: " + requestUrl);
        logInfo("[微店API调试] 使用的wdtoken: " + cookie.wdtoken);
        logInfo("[微店API调试] Cookie长度: " + cookie.cookie.length());
        
        String s = get(requestUrl, cookie);
        
        // 添加调试日志，检查HTTP响应内容
        if (s == null || s.trim().isEmpty()) {
            logInfo("[微店API调试] 响应为空，可能是网络问题或cookie失效");
            return null;
        }
        
        // 记录响应内容的前500个字符用于调试
        String debugResponse = s.length() > 500 ? s.substring(0, 500) + "..." : s;
        logInfo("[微店API调试] 响应内容: " + debugResponse);
        logInfo("[微店API调试] 响应长度: " + s.length());
        
        // 检查是否是登录页面重定向（常见的cookie失效表现）
        if (s.contains("<html>") || s.contains("<!DOCTYPE") || s.contains("login") || s.contains("登录")) {
            logInfo("[微店API调试] 检测到登录页面重定向，cookie已失效");
            return null;
        }
        
        // 检查响应是否以{开头（有效JSON对象）
        if (!s.trim().startsWith("{")) {
            logInfo("[微店API调试] 响应不是有效的JSON对象，可能是cookie失效导致的重定向，完整内容: " + s);
            return null;
        }

        JSONObject object;
        try {
            object = JSONUtil.parseObj(s);
            logInfo("[微店API调试] JSON解析成功");
        } catch (Exception e) {
            logInfo("[微店API调试] JSON解析失败: " + e.getMessage());
            logInfo("[微店API调试] 完整响应内容: " + s);
            logInfo("[微店API调试] 响应内容类型检查: startsWith('<'): " + s.trim().startsWith("<") + ", contains('html'): " + s.toLowerCase().contains("html"));
            return null;
        }
        
        // 检查API返回状态
        JSONObject status = object.getJSONObject("status");
        if (status == null) {
            logInfo("[微店API调试] API响应缺少status字段，响应格式异常");
            return null;
        }
        
        int code = status.getInt("code");
        String message = status.getStr("message");
        logInfo("[微店API调试] API返回状态 - code: " + code + ", message: " + message);
        
        if (code == 0) {
            // 成功获取数据
            JSONObject result = object.getJSONObject("result");
            if (result == null) {
                logInfo("API响应缺少result字段");
                return null;
            }
            
            JSONArray data = result.getJSONArray("dataList");
            if (data == null) {
                logInfo("API响应缺少dataList字段");
                return null;
            }
            
            List<WeidianItem> items = new ArrayList<>();
            for (Object item_ : data.toArray(new Object[0])) {
                JSONObject item = JSONUtil.parseObj(item_);
                long id = item.getLong("itemId");
                String name = item.getStr("itemName");
                String pic = item.getStr("imgHead");
                items.add(new WeidianItem(id, name, pic));
            }
            logInfo("成功获取到 " + items.size() + " 个商品");
            return items.toArray(new WeidianItem[0]);
        } else {
            // API返回错误，通常表示cookie失效或权限问题
            logInfo("微店API返回错误，code: " + code + ", message: " + message + "，可能是cookie失效");
            
            // 常见的cookie失效错误码
            if (code == 10001 || code == 10002 || code == 401 || code == 403 || 
                (message != null && (message.contains("登录") || message.contains("权限") || 
                 message.contains("token") || message.contains("cookie")))) {
                logInfo("确认cookie已失效，错误信息: " + message);
            }
            
            return null;
        }
    }

    public WeidianItem searchItem(WeidianCookie cookie, long id) {
        for (WeidianItem item : getItems(cookie)) {
            if (item.id == id)
                return item;
        }
        return null;
    }

}