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
    UNKNOWN//未知类型，用于兼容新增的消息类型
}