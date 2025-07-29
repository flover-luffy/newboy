package net.luffy.util.sender;

import cn.hutool.core.date.DateUtil;
// HttpRequest已迁移到异步处理器
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.Newboy;
import net.luffy.util.UnifiedJsonParser;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.PlainText;
import net.mamoe.mirai.utils.ExternalResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WeiboSender extends Sender {

    public static final String URLVideo = "https://video.weibo.com/show?fid=";
    private final HashMap<String, Long> endTime;
    private final UnifiedJsonParser jsonParser = UnifiedJsonParser.getInstance();

    public WeiboSender(Bot bot, long group, HashMap<String, Long> endTime) {
        super(bot, group);
        this.endTime = endTime;
    }

    @Override
    public void run() {
        List<messageWithTime> ms = new ArrayList<>();

        try {
            //超话
            List<String> superChatSubscribe = Newboy.INSTANCE.getProperties().weibo_superTopic_subscribe.get(group_id);

            for (String id : superChatSubscribe) {
                String a = Newboy.INSTANCE.getHandlerWeibo().getSuperTopicRes(id);
                if (a == null)
                    continue; //不存在超话

                a = a.substring(a.indexOf("onick']='") + "onick']='".length());
                String name = a.substring(0, a.indexOf("';"));
                Matcher b = Pattern.compile("(WB_info).*?(WB_detail)").matcher(a);

                if (!endTime.containsKey(id)) {
                    endTime.put(id, new Date().getTime());
                }

                long m = 0; //因为超话是按回复时间排序的 必须遍历完全部
                boolean find = false;

                while (b.find()) {
                    find = true;

                    String info = b.group().replace(" ", "");
                    String sender = info.substring(
                            info.indexOf("title=\\\"") + "title=\\\"".length()
                            , info.indexOf("\\\"suda-"));

                    //原博地址
                    info = info.substring(info.indexOf("WB_fromS_txt2"));
                    String link = info.substring(info.indexOf("https"), info.indexOf("\\\"title="))
                            .replace("\\/", "/");

                    try {
                        //时间
                        info = info.substring(info.indexOf("date=\\\"") + "date=\\\"".length());
                        long time = Long.valueOf(info.substring(0, info.indexOf("\\\"")));
                        if (time <= endTime.get(id))//不处理
                            continue;

                        if (time > m)//决定最终更新的endTime
                            m = time;

                        //文字
                        String textInfo = "【" + name + "超话新内容：" + sender + "】\n";
                        if (info.indexOf("WB_textW_f14") != -1) { //有文字
                            info = info.substring(info.indexOf("feed_list_content\\\">") + "feed_list_content\\\">".length());
                            textInfo += handleWeiboText(info.substring(0, info.indexOf("<\\/div>"))) + "\n";
                            info = info.substring(info.indexOf("<\\/div>") + "<\\/div>".length());
                        }

                        Message o = new PlainText(textInfo);

                        //影像
                        if (info.indexOf("WB_media_wrap") != -1) {
                            //会有一个前置div

                            info = info.substring(info.indexOf("WB_media_a") + "WB_media_a".length());

                            if (info.contains("WB_video")) {//视频获取链接(原始地址无权限访问)
                                String objectid = info.substring(info.indexOf("objectid=") + "objectid=".length(),
                                        info.indexOf("keys="));
                                String cover = info.substring(info.indexOf("cover_img=") + "cover_img=".length(), info.indexOf("&card_height="));
                                try (ExternalResource coverResource = ExternalResource.create(getRes(cover))) {
                                    o = o.plus(group.uploadImage(coverResource))
                                            .plus(URLVideo + objectid);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }

                            } else {//图片时获取原始地址
                                if (info.indexOf("&thumb_picSrc=") == -1) { //单张图（无缩略图）
                                    info = info.substring(info.indexOf("clear_picSrc=") + "clear_picSrc=".length());
                                    String src = info.substring(0, info.indexOf("\">")).replace("%2F", "/")
                                            .replace("\\", "");
                                    // 处理图片链接
                                    try (ExternalResource imageResource = ExternalResource.create(getRes("https:" + src))) {
                                        o = o.plus(group.uploadImage(imageResource));
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                } else { //多张图（有缩略图）
                                    for (String src : info.substring(
                                            info.indexOf("clear_picSrc=") + "clear_picSrc=".length(),
                                            info.indexOf("&thumb_picSrc=")).replace("%2F", "/").split(",")) {
                                        src = src.replace("\\", "");
                                        // 处理图片链接
                                        try (ExternalResource imageResource = ExternalResource.create(getRes("https:" + src))) {
                                            o = o.plus(group.uploadImage(imageResource));
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }

                        }

                        //发送
                        ms.add(new messageWithTime(o.plus("\nlink: " + link), time));

                    } catch (Exception e) {
                        Newboy.INSTANCE.getLogger().error("【超话播报处于测试版 请将以下信息提交至 https://github.com/Lawaxi/Newboy/issues/8】");
                        Newboy.INSTANCE.getLogger().error("错误微博：" + link);
                        e.printStackTrace();
                    }
                }

                if (!find) {
                    // 超话找不到任何东西
                }

                if (m > endTime.get(id))
                    endTime.put(id, m);

            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            //个人
            List<Long> userSubscribe = Newboy.INSTANCE.getProperties().weibo_user_subscribe.get(group_id);
            for (long id : userSubscribe) {
                if (!endTime.containsKey(String.valueOf(id))) {
                    endTime.put(String.valueOf(id), new Date().getTime());
                }

                String name = Newboy.INSTANCE.getHandlerWeibo().getUserName(id);
                Object[] as = Newboy.INSTANCE.getHandlerWeibo().getUserBlog(id);
                if (as == null) {
                    // 主页找不到任何东西
                    continue;
                }

                long m = 0;
                for (Object a : as) {
                    JSONObject b = jsonParser.parseObj(a.toString());
                    if (b.containsKey("title")) { //赞过的，转发的微博(暂不处理——时间无法获取 排序也不对)
                        /*
                        o = new PlainText("【"+ b.getJSONObject("title").getStr("text")
                                .replaceAll("\\(\\d*\\)","")
                                .replace("他", name)+"】\n");
                        link += b.getJSONObject("user").getInt("id");*/
                        continue;

                    }

                    long time = DateUtil.parse(b.getStr("created_at")).getTime();
                    if (time <= endTime.get(String.valueOf(id)))
                        break; //个人主页是按时间排序的
                    if (time > m)
                        m = time;

                    //发送
                    ms.add(new messageWithTime(new PlainText("【" + name + "微博更新】\n")
                            .plus(parseUserBlog(b))
                            .plus("\nlink: " + "https://weibo.com/" + id + "/" + b.getStr("mblogid")), time));
                }

                if (m > endTime.get(String.valueOf(id)))
                    endTime.put(String.valueOf(id), m);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        ms.sort((a, b) -> a.time - b.time > 0 ? 1 : -1); //按时间由小到大排序
        for (messageWithTime m : ms) {
            sendMessageWithRetry(toNotification(m.message), group, 3);
        }
    }

    private Message parseUserBlog(JSONObject b) throws IOException {
        String oriText = b.getStr("text");
        String text = handleWeiboText(oriText); //另有文本格式的text_raw(草稿中样式)，但视频提示不友好，我毅然决然选择text+处理
        Message o = new PlainText(text + "\n");

        //图片夹视频 - 优化：只推送第一张图片
        if (b.containsKey("mix_media_info")) {
            Object[] mediaItems = b.getJSONObject("mix_media_info").getJSONArray("items").stream().toArray();
            if (mediaItems.length > 0) {
                // 只处理第一个媒体项
                JSONObject media = jsonParser.parseObj(mediaItems[0].toString());
                String id = media.getStr("id");
                JSONObject data = media.getJSONObject("data");
                String cover = data.getStr("page_pic");
                if (media.getStr("type").equals("pic")) {
                    //图片
                    String src = media.getJSONObject("data")
                            .getJSONObject("original")
                            .getStr("url");
                    try (ExternalResource imageResource = ExternalResource.create(getRes(src))) {
                        o = o.plus(group.uploadImage(imageResource));
                    }

                } else {
                    //视频
                    try (ExternalResource coverResource = ExternalResource.create(getRes(cover))) {
                        o = o.plus(group.uploadImage(coverResource))
                                .plus(URLVideo + id);
                    }
                }
            }
        }

        //单独图片 - 优化：只推送第一张图片
        else if (b.containsKey("pic_infos")) {
            JSONObject pic_infos = b.getJSONObject("pic_infos");
            String[] pic_ids = b.getJSONArray("pic_ids").toArray(new String[0]);
            if (pic_ids.length > 0) {
                // 只处理第一张图片
                String pic_id = pic_ids[0];
                JSONObject pic_info = pic_infos.getJSONObject(pic_id);
                String src = pic_info.getJSONObject("original")
                        .getStr("url");
                try (ExternalResource imageResource = ExternalResource.create(getRes(src))) {
                    o = o.plus(group.uploadImage(imageResource));
                }
            }
        }

        //转发：与下方视频else if不能颠倒
        else if (b.containsKey("retweeted_status")) {
            JSONObject retweet = b.getJSONObject("retweeted_status");
            String retweet_from = retweet.getJSONObject("user").getStr("screen_name");
            o = o.plus("------\n\n")
                    .plus(retweet_from == null ? "" : "转发自 " + retweet_from + "：\n")
                    .plus(parseUserBlog(retweet));//叠呗
        }

        //单独视频
        else if (b.containsKey("page_info")) {
            JSONObject page_info = b.getJSONObject("page_info");
            if (page_info.getStr("object_type").equals("video")) {
                String id = page_info.getStr("object_id");
                String cover = page_info.getStr("page_pic");//缩略图
                try (ExternalResource coverResource = ExternalResource.create(getRes(cover))) {
                    o = o.plus(group.uploadImage(coverResource))
                            .plus(URLVideo + id);
                }
            }
        }

        return o;
    }

    @Override
    public InputStream getRes(String resLoc) {
        // 使用UnifiedHttpClient获取资源，设置Referer头
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Referer", "https://weibo.com/");
            
            return net.luffy.util.UnifiedHttpClient.getInstance().getInputStreamWithHeaders(resLoc, headers);
        } catch (Exception e) {
            throw new RuntimeException("获取资源失败: " + e.getMessage(), e);
        }
    }

    private String handleWeiboText(String oriText) {
        return oriText.replaceAll("&#xe627;", "▼")
                .replaceAll("<br>", "\n")
                .replaceAll("<.*?>", "");
    }

    /**
     * 带重试机制的消息发送方法（微博版本）
     * @param message 要发送的消息
     * @param group 目标群组
     * @param maxRetries 最大重试次数
     */
    private void sendMessageWithRetry(Message message, net.mamoe.mirai.contact.Group group, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                group.sendMessage(message);
                return; // 发送成功，直接返回
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                boolean isRetryableError = errorMsg != null && (
                    errorMsg.contains("rich media transfer failed") ||
                    errorMsg.contains("send_group_msg") ||
                    errorMsg.contains("ActionFailedException") ||
                    errorMsg.contains("EventChecker Failed")
                );
                
                if (attempt == maxRetries || !isRetryableError) {
                    Newboy.INSTANCE.getLogger().error("微博消息发送失败（已重试" + attempt + "次）: " + errorMsg);
                    return; // 达到最大重试次数或不可重试的错误，停止重试
                }
                
                // 指数退避重试
                try {
                    long delay = 1000 * (1L << (attempt - 1)); // 1s, 2s, 4s...
                    Thread.sleep(Math.min(delay, 8000)); // 最大延迟8秒
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Newboy.INSTANCE.getLogger().error("微博消息发送重试被中断: " + errorMsg);
                    return;
                }
            }
        }
    }

    private class messageWithTime {
        public final Message message;
        public final long time;

        private messageWithTime(Message message, long time) {
            this.message = message;
            this.time = time;
        }
    }
}
