package com.devmind.knowledge.enrich;

import com.devmind.common.model.ModelEndpointView;

/**
 * CAP-85 FR-28 增强模型调用 SPI。抽出来只为让任务编排可测：
 * 单测注入假实现即可验证"解析/锁定/失败不阻断"，无需起 HTTP 服务。
 */
public interface EnrichmentModel {

    /** 用给定 CHAT 端点完成一次结构化输出调用；失败抛异常（消息不得含密钥）。 */
    String complete(ModelEndpointView endpoint, String prompt, int timeoutSeconds);
}
