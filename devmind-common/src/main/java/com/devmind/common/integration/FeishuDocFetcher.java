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

    /**
     * CAP-85 FR-27：目录同步用的轻量引用（不含正文）。objType 为 docx/doc，
     * externalId 即内容 token；updatedAt 为 epoch millis（0 = 源端未给，调用方按需全拉）。
     */
    record SpaceDoc(String externalId, String title, String objType, long updatedAt) {
    }

    /**
     * CAP-85 FR-27：列出飞书空间/目录下的可同步文档（递归子目录，只收 docx/doc）。
     * target 支持 wiki 空间链接/ID、云盘文件夹链接/token。
     * 未实现目录枚举的集成保持默认抛错，知识模块按「当前集成不支持目录同步」提示。
     */
    default List<SpaceDoc> listSpaceDocs(long integrationId, String target) {
        throw new UnsupportedOperationException("当前飞书集成不支持目录枚举");
    }

    /**
     * CAP-85 FR-27：按内容 token 直接拉取（目录同步已拿到 objType/token，避免再绕 URL）。
     * 默认回落到 {@link #fetch(long, String)} 的 URL 形态。
     */
    default FeishuDoc fetchNode(long integrationId, String objType, String token) {
        String path = switch (objType == null ? "" : objType) {
            case "wiki" -> "/wiki/";
            case "docx" -> "/docx/";
            default -> "/docs/";
        };
        return fetch(integrationId, "https://open.feishu.cn" + path + token);
    }
}
