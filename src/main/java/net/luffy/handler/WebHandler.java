package net.luffy.handler;

/**
 * Web处理器基类 - 已迁移到异步架构
 * 保持向后兼容性，内部使用AsyncWebHandlerBase
 * @deprecated 建议使用AsyncWebHandlerBase或其子类
 */
@Deprecated
public class WebHandler extends AsyncWebHandlerBase {

    public WebHandler() {
        super();
    }

    // 性能统计和请求头设置已在父类AsyncWebHandlerBase中实现
    // 保留一些特殊的兼容性方法供子类使用
}
