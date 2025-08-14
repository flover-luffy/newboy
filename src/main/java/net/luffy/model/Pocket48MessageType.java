package net.luffy.model;

public enum Pocket48MessageType {
    TEXT,
    GIFT_TEXT,
    AUDIO,
    IMAGE,
    VIDEO,
    EXPRESSIMAGE,//口袋表情
    REPLY,
    GIFTREPLY,
    LIVEPUSH,
    SHARE_LIVE,
    FLIPCARD,
    FLIPCARD_AUDIO,
    FLIPCARD_VIDEO,
    PASSWORD_REDPACKAGE,
    VOTE,
    SHARE_POSTS,
    AGENT_QCHAT_TEXT,//代理聊天文本
    AGENT_QCHAT_GIFT_REPLY,//代理聊天礼物回复
    FAIPAI_TEXT,//翻牌文本
    GIFT_SKILL_TEXT,//技能礼物文本
    GIFT_SKILL_IMG,//技能礼物图片
    AGENT_WARMUP_IMG,//代理预热图片
    AGENT_WARMUP_VIDEO,//代理预热视频
    AGENT_WARMUP_AUDIO,//代理预热音频
    AGENT_QCHAT_TEXT_REPLY,//代理聊天文本回复
    AGENT_WARMUP_TEXT,//代理预热文本
    UNKNOWN//未知类型，用于兼容新增的消息类型
}