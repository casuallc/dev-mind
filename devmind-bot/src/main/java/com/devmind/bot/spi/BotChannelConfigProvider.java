package com.devmind.bot.spi;

/**
 * channel 级配置提供者（CAP-75）：适配器模块把平台侧配置（如飞书集成 configJson
 * 的 bot.defaultActor）暴露给底座。绑定未指定 actor 时优先级：
 * binding.actor → channel defaultActor → devmind.bot.default-actor。
 */
public interface BotChannelConfigProvider {

    /** 对应 {@link BotAdapter#code()}。 */
    String channel();

    /** 该 channel 的默认操作身份（可空 = 回落平台默认）。 */
    String defaultActor();
}
