package net.luffy.handler;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.model.*;
import net.luffy.util.StringMatchUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class WeidianHandler extends SyncWebHandler {

    private static final String APIOrderList = "https://thor.weidian.com/tradeview/seller.getOrderListForPC/1.0";
    private static final String APIDeliver = "https://thor.weidian.com/tradeview/seller.deliverOrder/1.0";
    private static final String APIItemList = "https://thor.weidian.com/wditem/itemList.pcListItems/1.0?param=%7B%22pageSize%22%3A100%2C%22pageNum%22%3A0%2C%22listStatus%22%3A%222%22%2C%22sorts%22%3A%5B%7B%22field%22%3A%22add_time%22%2C%22mode%22%3A%22desc%22%7D%5D%2C%22shopId%22%3A%22%22%7D&wdtoken=";
    //无需cookie
    private static final String APISkuInfo = "https://thor.weidian.com/detail/getItemSkuInfo/1.0?param=%7B%22itemId%22%3A%22%d%22%7D";
    
    //setDefaultHeader - 更新为匹配真实Edge请求的头信息
    protected HttpRequest setHeader(HttpRequest request) {
        return request.header("Host", "thor.weidian.com")
                .header("Connection", "keep-alive")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("DNT", "1")
                .header("Origin", "https://d.weidian.com")
                .header("Referer", "https://d.weidian.com/")
                .header("sec-ch-ua", "\"Microsoft Edge\";v=\"141\", \"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"141\"")
                .header("Accept", "application/json, */*")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0")
                .header("Sec-Fetch-Site", "same-site")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6,ja;q=0.5");
    }

    protected HttpRequest setHeader(HttpRequest request, WeidianCookie cookie) {
        return setHeader(request).header("Cookie", cookie.cookie);
    }

    protected String post(String url, String body, WeidianCookie cookie) {
        return setHeader(HttpRequest.post(url)
                .header("Referer", "https://d.weidian.com/")
                .header("Origin", "https://d.weidian.com")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"), cookie)
                .body(body).execute().body();
    }

    protected String get(String url, WeidianCookie cookie) {
        return setHeader(HttpRequest.get(url), cookie).execute().body();
    }
    
    protected String get(String url) {
        return setHeader(HttpRequest.get(url)).execute().body();
    }

    private JSONArray getOriOrderList(WeidianCookie cookie) {
        try {
            //获取【待发货】列表中的订单 - 使用与真实curl请求完全一致的参数格式
            String s = post(APIOrderList, "param={\"listType\":0,\"pageNum\":0,\"pageSize\":20,\"statusList\":[\"paid\"],\"refundStatusList\":[],\"channel\":\"pc\",\"topOrderType\":0,\"shipRole\":0,\"orderIdList\":\"\",\"itemTitle\":\"\",\"buyerName\":\"\",\"timeSearch\":{},\"orderBizType\":\"\",\"promotionType\":\"\",\"shipType\":\"\",\"newGhSearchSellerRole\":\"7\",\"memberLevel\":\"all\",\"orderSpecialType\":\"\",\"repayStatus\":\"2\",\"bSellerId\":\"\",\"itemSource\":\"\",\"shipper\":\"\",\"nSellerName\":\"\",\"partnerName\":\"\",\"noteSearchCondition\":{\"buyerNote\":\"\"},\"specialOrderSearchCondition\":{\"notShowGroupUnsuccess\":0,\"notShowFxOrder\":0,\"notShowUnRepayOrder\":0,\"notShowBuyerRepayOrder\":0,\"showAllPeriodOrder\":1,\"notShowTencentShopOrder\":0,\"notShowWithoutTimelinessOrder\":0},\"orderType\":4}&wdtoken=" + cookie.wdtoken, cookie);
            
            if (s == null || s.trim().isEmpty()) {
                return null;
            }
            
            // 检查是否是HTML重定向页面
            if (StringMatchUtils.isHtmlContent(s)) {
                cookie.invalid = true;
                System.out.println("[微店API] 检测到HTML重定向，Cookie可能已失效");
                return null;
            }
            
            if (!s.trim().startsWith("{")) {
                return null;
            }
            
            JSONObject object = JSONUtil.parseObj(s);
            JSONObject status = object.getJSONObject("status");
            if (status == null) {
                return null;
            }
            
            int code = status.getInt("code");
            String message = status.getStr("message");
            
            if (code != 0) {
                // 只有明确的认证失败错误才标记Cookie失效
                if (code == 10001 || code == 10002 || code == 401 || code == 403) {
                    cookie.invalid = true;
                    System.out.println("[微店API] 认证失败，标记Cookie失效，错误码: " + code + ", 消息: " + message);
                } else {
                    // 其他错误不标记Cookie失效，可能是临时性问题
                    System.out.println("[微店API] 临时性错误，不标记Cookie失效，错误码: " + code + ", 消息: " + message);
                }
                return null;
            }
            
            // API调用成功时，如果Cookie之前被标记为失效，现在恢复正常
            if (cookie.invalid) {
                cookie.invalid = false;
                System.out.println("[微店订单API] Cookie状态恢复正常");
            }
            
            JSONObject result = object.getJSONObject("result");
            if (result == null) {
                return null;
            }
            
            JSONArray orderList = result.getJSONArray("orderList");
            if (orderList == null) {
                return null;
            }
            
            return orderList;
            
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private JSONArray getOriItemList(WeidianCookie cookie) {
        try {
            String requestUrl = APIItemList + cookie.wdtoken;
            
            String response = get(requestUrl, cookie);
            if (response == null || response.trim().isEmpty()) {
                return null;
            }
            
            // 检查是否是HTML重定向页面
            if (StringMatchUtils.isHtmlContent(response)) {
                cookie.invalid = true;
                System.out.println("[微店商品API] 检测到HTML重定向，Cookie已失效");
                return null;
            }
            
            JSONObject object = JSONUtil.parseObj(response);
            JSONObject status = object.getJSONObject("status");
            if (status == null) {
                return null;
            }
            
            int code = status.getInt("code");
            String message = status.getStr("message");
            
            if (code != 0) {
                // 只有明确的认证失败错误才标记Cookie失效
                if (code == 10001 || code == 10002 || code == 401 || code == 403) {
                    cookie.invalid = true;
                    System.out.println("[微店商品API] 认证失败，标记Cookie失效，错误码: " + code + ", 消息: " + message);
                } else {
                    // 其他错误不标记Cookie失效，可能是临时性问题
                    System.out.println("[微店商品API] 临时性错误，不标记Cookie失效，错误码: " + code + ", 消息: " + message);
                }
                return null;
            }
            
            // API调用成功时，如果Cookie之前被标记为失效，现在恢复正常
            if (cookie.invalid) {
                cookie.invalid = false;
                System.out.println("[微店商品API] Cookie状态恢复正常");
            }
            
            JSONObject result = object.getJSONObject("result");
            if (result == null) {
                return null;
            }
            
            JSONArray dataList = result.getJSONArray("dataList");
            if (dataList == null) {
                return null;
            }
            
            return dataList;
            
        } catch (Exception e) {
            e.printStackTrace();
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
                double price = item.getInt("totalPrice");

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
        if (cookie == null) {
            return null;
        }

        if (cookie.invalid) {
            return null;
        }

        JSONArray objectList = getOriOrderList(cookie);
        if (objectList == null) {
            return null;
        }

        List<WeidianOrder> orders = new ArrayList<>();
        long lastTime = endTime.time;
        int newOrderCount = 0;
        int skippedOrderCount = 0;
        int deliveredOrderCount = 0;

        System.out.println("[微店订单检测] 开始检测新订单，当前EndTime: " + endTime.time + ", 订单总数: " + objectList.size());

        for (Object object : objectList.toArray(new Object[0])) {
            JSONObject order = JSONUtil.parseObj(object);
            
            // payTime是字符串格式，需要先解析为DateTime再转换为时间戳
            String payTimeStr = order.getStr("payTime");
            long timeValue;
            try {
                DateTime payDateTime = DateUtil.parse(payTimeStr);
                timeValue = payDateTime.getTime();
            } catch (Exception e) {
                System.out.println("[微店订单检测] 解析payTime失败: " + payTimeStr + ", 错误: " + e.getMessage());
                continue;
            }
            
            String time = String.valueOf(timeValue);

            if (timeValue <= endTime.time) {
                System.out.println("[微店订单检测] 订单时间 " + timeValue + " <= EndTime " + endTime.time + ", 跳过旧订单");
                skippedOrderCount++;
                continue; // 使用continue而不是break，确保检查所有订单
            }

            if (timeValue > lastTime) {
                lastTime = timeValue;
            }

            newOrderCount++;
            String orderId = order.getStr("orderId");
            
            // 统一使用receiver对象获取买家信息
            String buyerName;
            long buyerID;
            JSONObject receiver = order.getJSONObject("receiver");
            if (receiver != null) {
                buyerName = receiver.getStr("buyerName");
                buyerID = receiver.getLong("buyerId");
            } else {
                // 如果receiver为空，尝试从order直接获取
                buyerName = order.getStr("buyerName");
                buyerID = order.getLong("buyerId");
            }
            
            JSONArray itemList = order.getJSONArray("itemList");

            boolean contains_shielded_item = false;

            for (Object itemObject : itemList.toArray(new Object[0])) {
                JSONObject item = JSONUtil.parseObj(itemObject);
                long itemId = item.getLong("itemId");

                if (cookie.shieldedItem.contains(itemId)) {
                    contains_shielded_item = true;
                }
                String itemName = item.getStr("itemName");
                double price = item.getInt("totalPrice");

                orders.add(new WeidianOrder(itemId, itemName, buyerID, buyerName, price, time));
            }

            if (cookie.autoDeliver && !contains_shielded_item) {
                try {
                    if (deliver(orderId, cookie)) {
                        deliveredOrderCount++;
                    }
                } catch (RuntimeException e) {
                    // 静默处理发货失败
                }
            }
        }
        endTime.time = lastTime;

        System.out.println("[微店订单检测] 检测完成，新订单数: " + newOrderCount + ", 跳过订单数: " + skippedOrderCount + ", 发货订单数: " + deliveredOrderCount + ", 更新EndTime为: " + lastTime);

        return orders.toArray(new WeidianOrder[0]);
    }

    public boolean deliver(String orderId, WeidianCookie cookie) throws RuntimeException {
        String s = post(APIDeliver, "param={\"from\":\"pc\",\"orderId\":\"" + orderId + "\",\"expressNo\":\"\",\"expressType\":0,\"expressCustom\":\"\",\"fullDeliver\":true}&wdtoken=" + cookie.wdtoken, cookie);
        
        if (s == null || s.trim().isEmpty()) {
            throw new RuntimeException("发货API响应为空");
        }
        
        // 检查是否是HTML重定向页面
        if (StringMatchUtils.isHtmlContent(s)) {
            cookie.invalid = true;
            System.out.println("[微店发货API] 检测到HTML重定向，Cookie已失效");
            throw new RuntimeException("Cookie已失效，需要重新登录");
        }
        
        if (!s.trim().startsWith("{")) {
            throw new RuntimeException("发货API响应格式异常");
        }
        
        JSONObject object = JSONUtil.parseObj(s);
        JSONObject status = object.getJSONObject("status");
        if (status == null) {
            throw new RuntimeException("发货API响应缺少status字段");
        }
        
        int code = status.getInt("code");
        
        // 检查特定的错误码，这些错误码表示Cookie失效
        if (code == 10001 || code == 10002 || code == 401 || code == 403 || code == 2) {
            cookie.invalid = true;
            String message = status.getStr("message", "未知错误");
            System.out.println("[微店发货API] 认证失败，Cookie已失效，错误码: " + code + ", 消息: " + message);
            throw new RuntimeException("Cookie已失效: " + message);
        }
        
        if (code == 0) {
            // 发货成功时，如果Cookie之前被标记为失效，现在恢复正常
            if (cookie.invalid) {
                cookie.invalid = false;
                System.out.println("[微店发货API] Cookie状态恢复正常");
            }
            JSONObject result = object.getJSONObject("result");
            if (result == null) {
                throw new RuntimeException("发货API响应缺少result字段");
            }
            return result.getBool("success");
        }
        
        // 其他错误码不标记Cookie失效，可能是临时性问题
        String message = status.getStr("message", "未知错误");
        System.out.println("[微店发货API] 临时性错误，不标记Cookie失效，错误码: " + code + ", 消息: " + message);
        throw new RuntimeException(message);
    }

    private JSONArray getItemOriOrderList(WeidianCookie cookie, long itemId) {
        //获取【已付款】列表中单个商品的订单 - 与getOriOrderList保持一致的参数格式
        String s = post(APIOrderList, "param={\"listType\":0,\"pageNum\":0,\"pageSize\":20,\"statusList\":[\"paid\"],\"refundStatusList\":[],\"channel\":\"pc\",\"topOrderType\":0,\"shipRole\":0,\"orderIdList\":\"\",\"itemId\":\"" + itemId + "\",\"itemTitle\":\"\",\"buyerName\":\"\",\"timeSearch\":{},\"orderBizType\":\"\",\"promotionType\":\"\",\"shipType\":\"\",\"newGhSearchSellerRole\":\"7\",\"memberLevel\":\"all\",\"orderSpecialType\":\"\",\"repayStatus\":\"2\",\"bSellerId\":\"\",\"itemSource\":\"\",\"shipper\":\"\",\"nSellerName\":\"\",\"partnerName\":\"\",\"noteSearchCondition\":{\"buyerNote\":\"\"},\"specialOrderSearchCondition\":{\"notShowGroupUnsuccess\":0,\"notShowFxOrder\":0,\"notShowUnRepayOrder\":0,\"notShowBuyerRepayOrder\":0,\"showAllPeriodOrder\":1,\"notShowTencentShopOrder\":0,\"notShowWithoutTimelinessOrder\":0},\"orderType\":4}&wdtoken=" + cookie.wdtoken, cookie);
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
        String s = post(APIOrderList, "param={\"listType\":0,\"pageNum\":" + page + ",\"pageSize\":20,\"statusList\":[\"paid\"],\"refundStatusList\":[],\"channel\":\"pc\",\"topOrderType\":0,\"shipRole\":0,\"orderIdList\":\"\",\"itemId\":\"" + itemId + "\",\"itemTitle\":\"\",\"buyerName\":\"\",\"timeSearch\":{},\"orderBizType\":\"\",\"promotionType\":\"\",\"shipType\":\"\",\"newGhSearchSellerRole\":\"7\",\"memberLevel\":\"all\",\"orderSpecialType\":\"\",\"repayStatus\":\"2\",\"bSellerId\":\"\",\"itemSource\":\"\",\"shipper\":\"\",\"nSellerName\":\"\",\"partnerName\":\"\",\"noteSearchCondition\":{\"buyerNote\":\"\"},\"specialOrderSearchCondition\":{\"notShowGroupUnsuccess\":0,\"notShowFxOrder\":0,\"notShowUnRepayOrder\":0,\"notShowBuyerRepayOrder\":0,\"showAllPeriodOrder\":1,\"notShowTencentShopOrder\":0,\"notShowWithoutTimelinessOrder\":0},\"orderType\":4}&wdtoken=" + cookie.wdtoken, cookie);
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
        if (cookie == null) {
            return null;
        }

        if (cookie.invalid) {
            return null;
        }

        JSONArray objectList = getOriItemList(cookie);
        if (objectList == null) {
            return null;
        }

        List<WeidianItem> items = new ArrayList<>();
        int highlightedCount = 0;
        int shieldedCount = 0;
        
        for (Object object : objectList.toArray(new Object[0])) {
            JSONObject item = JSONUtil.parseObj(object);
            long itemId = item.getLong("itemId");
            String itemName = item.getStr("itemName");
            double price = item.getDouble("price");
            // 使用正确的字段名 imgHead 而不是 itemImg
            String imgHead = item.getStr("imgHead");
            
            // 清理图片URL，去除可能的空格
            String itemImg = (imgHead != null) ? imgHead.trim() : "";
            
            boolean highlighted = cookie.highlightItem.contains(itemId);
            boolean shielded = cookie.shieldedItem.contains(itemId);
            
            if (shielded) {
                shieldedCount++;
                continue;
            }
            
            if (highlighted) {
                highlightedCount++;
            }
            
            items.add(new WeidianItem(itemId, itemName, price, itemImg, highlighted));
        }

        return items.toArray(new WeidianItem[0]);
    }

    public WeidianItem searchItem(WeidianCookie cookie, long id) {
        logInfo("开始搜索商品，ID: " + id);
        WeidianItem[] items = getItems(cookie);
        if (items == null) {
            logInfo("获取商品列表失败，可能是cookie失效或网络问题");
            return null;
        }
        
        logInfo("获取到商品列表，总数: " + items.length);
        
        for (WeidianItem item : items) {
            if (item.id == id) {
                logInfo("找到目标商品: " + item.name + ", pic字段值: '" + item.pic + "'");
                return item;
            }
        }
        return null;
    }

}