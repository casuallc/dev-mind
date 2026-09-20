package com.devmind.common.model;

import java.util.Optional;

/**
 * CAP-48 FR-04 模型端点解析 SPI：devmind-model 实现，消费方（devmind-knowledge 索引/检索）
 * 以 {@code ObjectProvider<ModelEndpointProvider>} 探测注入。
 *
 * <p>「哪个库用哪个端点」这条映射存在 knowledge_bases（knowledge 模块的自有列），
 * 所以解析链由调用方传入库级覆盖值，本 SPI 只负责"取端点"这一步：</p>
 *
 * <pre>
 * 库级 modelEndpointId（active）→ 平台默认端点（is_default 且 active）→ 皆无 → empty（调用方降级）
 * </pre>
 *
 * <p>未装配实现（模块未上线/滚动升级期）= 无端点 = 调用方退回 CAP-44 的配置化单例路径或降级，
 * 零反向依赖。</p>
 *
 * <p><b>kind 由消费方过滤</b>（FR-11）：本 SPI 只负责"取端点"，不替调用方判断类型——
 * 返回的 {@link ModelEndpointView} 带 {@code kind}，向量消费方必须先
 * {@link ModelEndpointView#embedding()} 再使用，否则会把通用对话端点当向量端点用。
 * 未来对话消费方（本能力不实现）的接入点是新增 {@code defaultEndpoint(String kind)}，届时在下方的
 * {@link #defaultEndpoint()} 旁并列实现。</p>
 */
public interface ModelEndpointProvider {

    /**
     * 解析生效端点：库级覆盖优先，回落平台默认。
     *
     * @param kbEndpointId 库级覆盖端点 ID（可空 = 该库跟随平台默认）
     * @return 可用端点；存不存在/已停用/无默认 → empty（调用方按降级处理，不回落 mock）
     */
    Optional<ModelEndpointView> resolve(Long kbEndpointId);

    /**
     * 平台默认<b>向量</b>端点（kind=EMBEDDING 且 is_default 且 active）；无 → empty。
     * 默认端点按类型各自唯一，本方法只承担向量一侧。
     */
    Optional<ModelEndpointView> defaultEndpoint();

    /** 按 ID 取 active 端点；不存在/已停用 → empty */
    Optional<ModelEndpointView> activeEndpoint(long id);
}
