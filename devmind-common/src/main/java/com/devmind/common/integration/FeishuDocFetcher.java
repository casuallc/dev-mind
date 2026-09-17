package com.devmind.common.integration;

import java.util.List;

/**
 * CAP-45 飞书文档拉取 SPI：devmind-integration 实现（FEISHU 类型集成），
 * 消费方（devmind-knowledge 导入服务）以 {@code ObjectProvider<FeishuDocFetcher>} 探测注入，
 * 未装配时按「未配置飞书集成」降级。
 *
 * <p>凭证不出实现模块边界——SPI 只暴露文档内容与集成清单（id/name），不含 appId/appSecret。</p>
 */
public interface FeishuDocFetcher {

    /** 可用（ENABLED）的 FEISHU 集成清单（下拉选择用） */
    List<FeishuIntegration> listIntegrations();

    /**
     * 解析飞书文档 URL（/wiki/{token}、/docx/{token}、/docs/{token}）并拉取转 markdown。
     * 失败抛 DevMindException（BAD_REQUEST=URL 不识别/集成不存在；UPSTREAM=飞书侧错误）。
     */
    FeishuDoc fetch(long integrationId, String url);

    /**
     * @param externalId 判重键中的文档部分（wiki=objToken，docx/doc=docToken），
     *                   完整判重键 = integrationId + ":" + externalId
     * @param docType    wiki | docx | doc
     * @param sourceUrl  归一化后的来源 URL（去 query/fragment），重同步用
     */
    record FeishuDoc(String externalId, String title, String contentMd, String docType, String sourceUrl) {
    }

    record FeishuIntegration(Long id, String name) {
    }
}
