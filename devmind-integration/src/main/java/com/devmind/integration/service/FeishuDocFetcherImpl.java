package com.devmind.integration.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.FeishuDocFetcher;
import com.devmind.integration.connector.feishu.DocxMarkdownConverter;
import com.devmind.integration.connector.feishu.FeishuApiClient;
import com.devmind.integration.connector.feishu.FeishuConnector;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.repo.IntegrationRepository;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * CAP-45 飞书文档拉取 SPI 实现：URL 归一化解析（/wiki//docx//docs/ 三种形态）→
 * 飞书 API 拉取 → docx blocks 转 markdown。凭证不出本模块（经 IntegrationService.tokenOf 解密）。
 */
@Service
public class FeishuDocFetcherImpl implements FeishuDocFetcher {

    private final IntegrationRepository integrationRepo;
    private final IntegrationService integrationService;
    private final FeishuConnector feishuConnector;

    public FeishuDocFetcherImpl(IntegrationRepository integrationRepo,
                                IntegrationService integrationService,
                                FeishuConnector feishuConnector) {
        this.integrationRepo = integrationRepo;
        this.integrationService = integrationService;
        this.feishuConnector = feishuConnector;
    }

    @Override
    public List<FeishuIntegration> listIntegrations() {
        return integrationRepo.findByTypeAndStatus(IntegrationEntity.TYPE_FEISHU,
                        IntegrationEntity.STATUS_ENABLED).stream()
                .map(e -> new FeishuIntegration(e.getId(), e.getName()))
                .toList();
    }

    @Override
    public FeishuDoc fetch(long integrationId, String url) {
        IntegrationEntity e = integrationService.require(integrationId);
        if (!IntegrationEntity.TYPE_FEISHU.equals(e.getType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "集成 " + integrationId + " 不是飞书类型");
        }
        if (!IntegrationEntity.STATUS_ENABLED.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "飞书集成「" + e.getName() + "」已停用");
        }
        DocRef ref = parseUrl(url);
        FeishuApiClient client = feishuConnector.apiClient(e, integrationService.tokenOf(e));
        return switch (ref.kind()) {
            case "wiki" -> fetchWiki(client, ref.token(), ref.sourceUrl());
            case "docx" -> fetchDocx(client, ref.token(), null, ref.sourceUrl());
            default -> fetchDoc(client, ref.token(), null, ref.sourceUrl());
        };
    }

    // ---------------- 文档形态 ----------------

    /** wiki 节点：解析 objType/objToken 后按真实类型拉取 */
    private FeishuDoc fetchWiki(FeishuApiClient client, String nodeToken, String sourceUrl) {
        FeishuApiClient.WikiNode node = client.getWikiNode(nodeToken);
        if (node.objToken().isBlank()) {
            throw new DevMindException(ErrorCode.INTERNAL, "飞书 wiki 节点解析失败：" + nodeToken);
        }
        return switch (node.objType()) {
            case "docx" -> fetchDocx(client, node.objToken(), node.title(), sourceUrl);
            case "doc" -> fetchDoc(client, node.objToken(), node.title(), sourceUrl);
            default -> throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "暂不支持的 wiki 节点类型：" + node.objType() + "（仅支持 docx/doc）");
        };
    }

    private FeishuDoc fetchDocx(FeishuApiClient client, String docToken, String title, String sourceUrl) {
        String realTitle = title != null && !title.isBlank() ? title : client.getDocxTitle(docToken);
        List<JsonNode> blocks = client.getAllBlocks(docToken);
        String md = DocxMarkdownConverter.convert(blocks, null);
        return new FeishuDoc(docToken, realTitle, md, "docx", sourceUrl);
    }

    /** 旧版 doc：raw_content 纯文本（无标题接口，直接用 token 兜底，wiki 来源带节点标题） */
    private FeishuDoc fetchDoc(FeishuApiClient client, String docToken, String title, String sourceUrl) {
        String content = client.getDocRawContent(docToken);
        String realTitle = title != null && !title.isBlank() ? title : "飞书文档 " + docToken;
        return new FeishuDoc(docToken, realTitle, content, "doc", sourceUrl);
    }

    // ---------------- URL 解析 ----------------

    private record DocRef(String kind, String token, String sourceUrl) {
    }

    /** 归一化（去 query/fragment）+ 提取 /wiki//docx//docs/ 后的 token */
    static DocRef parseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "飞书文档 URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "不是合法 URL：" + rawUrl);
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            String seg = segments[i];
            if (("wiki".equals(seg) || "docx".equals(seg) || "docs".equals(seg))
                    && !segments[i + 1].isBlank()) {
                String kind = "docs".equals(seg) ? "doc" : seg;
                String sourceUrl = uri.getScheme() + "://" + uri.getAuthority()
                        + "/" + seg + "/" + segments[i + 1];
                return new DocRef(kind, segments[i + 1], sourceUrl);
            }
        }
        throw new DevMindException(ErrorCode.BAD_REQUEST,
                "无法识别的飞书文档 URL（支持 /wiki/、/docx/、/docs/）：" + rawUrl);
    }
}
