package com.devmind.bot.spi;

/**
 * channel 级配置提供者（CAP-75）：适配器模块把平台侧配置（如飞书集成 configJson
 * 的 bot.defaultActor）暴露给底座。身份优先级（M3 FR-11 四级链）：
 * resolveActor(senderId)（个人映射）→ binding.actor → channel defaultActor →
 * devmind.bot.default-actor。
 */
public interface BotChannelConfigProvider {

    /** 对应 {@link BotAdapter#code()}。 */
    String channel();

    /** 该 channel 的默认操作身份（可空 = 回落平台默认）。 */
    String defaultActor();

    /**
     * CAP-75 FR-11：该 channel 是否支持个人身份映射。true 时敏感操作
     * （/bind /session /build /deploy）要求 sender 已绑定个人身份（FR-12 门禁）。
     * 默认 false（如 e2e fake 适配器：纯管道测试，不做身份）。
     */
    default boolean supportsIdentity() {
        return false;
    }

    /** CAP-75 FR-11：senderId（如飞书 open_id）→ 平台用户名；未绑定返回 empty。 */
    default java.util.Optional<String> resolveActor(String senderId) {
        return java.util.Optional.empty();
    }
}
