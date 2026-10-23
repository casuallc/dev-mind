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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        FeishuApiClient client = requireClient(integrationId);
        DocRef ref = parseUrl(url);
        return switch (ref.kind()) {
            case "wiki" -> fetchWiki(client, ref.token(), ref.sourceUrl());
            case "docx" -> fetchDocx(client, ref.token(), null, ref.sourceUrl());
            default -> fetchDoc(client, ref.token(), null, ref.sourceUrl());
        };
    }

    // ---------------- CAP-85 FR-27：空间/目录枚举 ----------------

    @Override
    public List<SpaceDoc> listSpaceDocs(long integrationId, String target) {
        FeishuApiClient client = requireClient(integrationId);
        SyncTarget t = parseSyncTarget(target);
        return "wiki".equals(t.kind())
                ? walkWikiSpace(client, t.id())
                : walkDriveFolder(client, t.id());
    }

    @Override
    public FeishuDoc fetchNode(long integrationId, String objType, String token) {
        FeishuApiClient client = requireClient(integrationId);
        return switch (objType == null ? "" : objType) {
            case "docx" -> fetchDocx(client, token, null, "feishu://docx/" + token);
            case "doc" -> fetchDoc(client, token, null, "feishu://doc/" + token);
            case "wiki" -> fetchWiki(client, token, "feishu://wiki/" + token);
            default -> throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "目录同步只支持 docx/doc，当前节点类型：" + objType);
        };
    }

    private static final int MAX_SYNC_DOCS = 2000;
    private static final int MAX_SYNC_FOLDERS = 500;

    private List<SpaceDoc> walkWikiSpace(FeishuApiClient client, String spaceId) {
        Map<String, SpaceDoc> docs = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> parents = new ArrayDeque<>();
        parents.add("");
        int visitedNodes = 0;
        while (!parents.isEmpty() && docs.size() < MAX_SYNC_DOCS) {
            String parent = parents.poll();
            for (FeishuApiClient.WikiSpaceNode n : client.listWikiSpaceNodes(spaceId, parent)) {
                if (++visitedNodes > MAX_SYNC_DOCS * 5 || n.nodeToken().isBlank()
                        || !visited.add(n.nodeToken())) {
                    continue;
                }
                if (isSyncableDoc(n.objType()) && !n.objToken().isBlank()) {
                    docs.putIfAbsent(n.objToken(),
                            new SpaceDoc(n.objToken(), n.title(), n.objType(), n.updatedAt()));
                }
                parents.add(n.nodeToken());
            }
        }
        return List.copyOf(docs.values());
    }

    private List<SpaceDoc> walkDriveFolder(FeishuApiClient client, String folderToken) {
        Map<String, SpaceDoc> docs = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Deque<String> folders = new ArrayDeque<>();
        folders.add(folderToken);
        while (!folders.isEmpty() && docs.size() < MAX_SYNC_DOCS) {
            String folder = folders.poll();
            if (!visited.add(folder) || visited.size() > MAX_SYNC_FOLDERS) {
                continue;
            }
            for (FeishuApiClient.DriveFile f : client.listDriveFiles(folder)) {
                if (f.token().isBlank()) {
                    continue;
                }
                if ("folder".equals(f.type())) {
                    folders.add(f.token());
                } else if (isSyncableDoc(f.type())) {
                    docs.putIfAbsent(f.token(),
                            new SpaceDoc(f.token(), f.name(), f.type(), f.updatedAt()));
                }
            }
        }
        return List.copyOf(docs.values());
    }

    private static boolean isSyncableDoc(String type) {
        return "docx".equals(type) || "doc".equals(type);
    }

    private record SyncTarget(String kind, String id) {
    }

    /**
     * target 支持：wiki 空间链接/数字 ID（默认）、`space:{id}`、云盘文件夹链接/`folder:{token}`。
     * 裸 token 按云盘文件夹处理；wiki 单篇链接不支持（应使用文档导入）。
     */
    static SyncTarget parseSyncTarget(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "同步目标不能为空（wiki 空间或云盘文件夹）");
        }
        String s = raw.strip();
        if (s.startsWith("space:")) {
            return new SyncTarget("wiki", safeToken(s.substring(6)));
        }
        if (s.startsWith("folder:")) {
            return new SyncTarget("folder", safeToken(s.substring(7)));
        }
        int spaceIdx = s.indexOf("/wiki/space/");
        if (spaceIdx >= 0) {
            return new SyncTarget("wiki", safeToken(stripQuery(s.substring(spaceIdx + 12))));
        }
        int folderIdx = s.indexOf("/folder/");
        if (folderIdx >= 0) {
            return new SyncTarget("folder", safeToken(stripQuery(s.substring(folderIdx + 8))));
        }
        if (s.matches("\\d+")) {
            return new SyncTarget("wiki", s);
        }
        if (s.contains("://") || s.contains("/")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "无法识别的同步目标（支持 wiki 空间链接/ID 或云盘文件夹链接/token）：" + raw);
        }
        return new SyncTarget("folder", safeToken(s));
    }

    private static String stripQuery(String s) {
        int idx = s.indexOf('?');
        String noQuery = idx >= 0 ? s.substring(0, idx) : s;
        int hash = noQuery.indexOf('#');
        return hash >= 0 ? noQuery.substring(0, hash) : noQuery;
    }

    private static String safeToken(String token) {
        String t = stripQuery(token).strip();
        if (t.isBlank() || !t.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "同步目标 token 非法：" + token);
        }
        return t;
    }

    private FeishuApiClient requireClient(long integrationId) {
        IntegrationEntity e = integrationService.require(integrationId);
        if (!IntegrationEntity.TYPE_FEISHU.equals(e.getType())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "集成 " + integrationId + " 不是飞书类型");
        }
        if (!IntegrationEntity.STATUS_ENABLED.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "飞书集成「" + e.getName() + "」已停用");
        }
        return feishuConnector.apiClient(e, integrationService.tokenOf(e));
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
