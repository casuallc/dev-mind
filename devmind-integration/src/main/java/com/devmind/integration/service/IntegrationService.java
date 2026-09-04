package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.common.audit.AuditService;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.PlatformIntegrationHook;
import com.devmind.integration.config.IntegrationCipher;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.dto.BindingRequest;
import com.devmind.integration.dto.BindingView;
import com.devmind.integration.dto.CreateMrRequest;
import com.devmind.integration.dto.ExternalLinkView;
import com.devmind.integration.dto.IntegrationCallView;
import com.devmind.integration.dto.IntegrationRequest;
import com.devmind.integration.dto.IntegrationView;
import com.devmind.integration.model.ExternalLinkEntity;
import com.devmind.integration.model.IntegrationBindingEntity;
import com.devmind.integration.model.IntegrationCallEntity;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.model.JiraSyncConfigEntity;
import com.devmind.integration.repo.ExternalLinkRepository;
import com.devmind.integration.repo.IntegrationBindingRepository;
import com.devmind.integration.repo.IntegrationCallRepository;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.integration.repo.JiraSyncConfigRepository;
import com.devmind.project.ProjectService;
import com.devmind.project.WorkItemService;
import com.devmind.project.model.ProjectRepoEntity;
import com.devmind.project.model.WorkItemEntity;
import com.devmind.project.repo.ProjectRepoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CAP-18 集成主服务：Integration CRUD（凭据加密）/ 项目绑定 / WI 分支推送 / 创建 MR /
 * 发版钩子（{@link PlatformIntegrationHook}）/ External Link 反查 / 调用审计。
 * 全部出站动作落 integration_calls + 全局审计；凭据只在调用瞬间解密于内存。
 */
@Service
public class IntegrationService implements PlatformIntegrationHook {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    private final IntegrationRepository integrationRepo;
    private final IntegrationBindingRepository bindingRepo;
    private final ExternalLinkRepository linkRepo;
    private final IntegrationCallRepository callRepo;
    private final JiraSyncConfigRepository jiraSyncConfigRepo;
    private final IntegrationCipher cipher;
    private final GitRemoteOps gitOps;
    private final ProjectService projectService;
    private final ProjectRepoRepository projectRepoRepo;
    private final WorkItemService workItemService;
    private final IdentityService identityService;
    private final AuditService auditService;
    private final UserGitCredentialService userGitCredentialService;
    private final Map<String, IntegrationConnector> connectors;

    public IntegrationService(IntegrationRepository integrationRepo,
                              IntegrationBindingRepository bindingRepo,
                              ExternalLinkRepository linkRepo,
                              IntegrationCallRepository callRepo,
                              JiraSyncConfigRepository jiraSyncConfigRepo,
                              IntegrationCipher cipher,
                              GitRemoteOps gitOps,
                              ProjectService projectService,
                              ProjectRepoRepository projectRepoRepo,
                              WorkItemService workItemService,
                              IdentityService identityService,
                              AuditService auditService,
                              UserGitCredentialService userGitCredentialService,
                              List<IntegrationConnector> connectorList) {
        this.integrationRepo = integrationRepo;
        this.bindingRepo = bindingRepo;
        this.linkRepo = linkRepo;
        this.callRepo = callRepo;
        this.jiraSyncConfigRepo = jiraSyncConfigRepo;
        this.cipher = cipher;
        this.gitOps = gitOps;
        this.projectService = projectService;
        this.projectRepoRepo = projectRepoRepo;
        this.workItemService = workItemService;
        this.identityService = identityService;
        this.auditService = auditService;
        this.userGitCredentialService = userGitCredentialService;
        this.connectors = connectorList.stream()
                .collect(Collectors.toMap(IntegrationConnector::type, Function.identity()));
    }

    // ---------------- FR-01 Integration CRUD ----------------

    public IntegrationView create(IntegrationRequest req) {
        String type = normalizeType(req.type());
        if (!connectors.containsKey(type)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "暂不支持的平台类型 " + type + "（当前可用：" + String.join("/", connectors.keySet()) + "）");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "name 不能为空");
        }
        String baseUrl = validateBaseUrl(req.baseUrl());
        String authType = normalizeAuthType(req.authType());
        if (req.token() == null || req.token().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    IntegrationEntity.AUTH_BASIC.equals(authType) ? "密码不能为空" : "token 不能为空");
        }
        String username = null;
        if (IntegrationEntity.AUTH_BASIC.equals(authType)) {
            if (req.username() == null || req.username().isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "Basic Auth 需要填写用户名");
            }
            username = req.username().trim();
        }
        IntegrationEntity e = new IntegrationEntity();
        e.setType(type);
        e.setName(req.name().trim());
        e.setBaseUrl(baseUrl);
        e.setAuthType(authType);
        e.setSecretEnc(cipher.encrypt(encodeSecret(authType, username, req.token().trim())));
        e.setStatus(IntegrationEntity.STATUS_ENABLED);
        e.setConfigJson(blankToNull(req.configJson()));
        e.setCreatedBy(identityService.currentActor());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        IntegrationEntity saved = integrationRepo.save(e);
        audit("create", saved.getId(), null, true, "创建集成 " + type + " " + baseUrl);
        return toView(saved);
    }

    public IntegrationView update(Long id, IntegrationRequest req) {
        IntegrationEntity e = require(id);
        if (req.name() != null && !req.name().isBlank()) {
            e.setName(req.name().trim());
        }
        if (req.baseUrl() != null && !req.baseUrl().isBlank()) {
            e.setBaseUrl(validateBaseUrl(req.baseUrl()));
        }
        // token 空白=保持不变；非空=换凭据（BASIC 时 username 留空沿用原用户名）
        if (req.token() != null && !req.token().isBlank()) {
            if (IntegrationEntity.AUTH_BASIC.equals(e.getAuthType())) {
                String username = req.username() != null && !req.username().isBlank()
                        ? req.username().trim() : basicUsername(cipher.decrypt(e.getSecretEnc()));
                if (username == null || username.isBlank()) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST, "更换 Basic 凭据需填写用户名");
                }
                e.setSecretEnc(cipher.encrypt(encodeSecret(e.getAuthType(), username, req.token().trim())));
            } else {
                e.setSecretEnc(cipher.encrypt(req.token().trim()));
            }
        }
        if (req.configJson() != null) {
            e.setConfigJson(blankToNull(req.configJson()));
        }
        e.setUpdatedAt(Instant.now());
        IntegrationEntity saved = integrationRepo.save(e);
        audit("update", id, null, true, "更新集成 " + e.getType() + " " + e.getBaseUrl());
        return toView(saved);
    }

    public IntegrationView changeStatus(Long id, String status) {
        IntegrationEntity e = require(id);
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!IntegrationEntity.STATUS_ENABLED.equals(s) && !IntegrationEntity.STATUS_DISABLED.equals(s)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "status 仅支持 ENABLED / DISABLED");
        }
        e.setStatus(s);
        e.setUpdatedAt(Instant.now());
        IntegrationEntity saved = integrationRepo.save(e);
        audit("status", id, null, true, "集成状态 → " + s);
        return toView(saved);
    }

    public List<IntegrationView> list() {
        return integrationRepo.findAllByOrderByIdAsc().stream().map(this::toView).toList();
    }

    public IntegrationView get(Long id) {
        return toView(require(id));
    }

    public IntegrationEntity require(Long id) {
        return integrationRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "集成不存在: " + id));
    }

    // ---------------- FR-02 连接测试 ----------------

    public IntegrationConnector.TestResult testConnection(Long id) {
        IntegrationEntity e = require(id);
        IntegrationConnector connector = connectorFor(e);
        IntegrationConnector.TestResult result;
        try {
            result = connector.testConnection(e, tokenOf(e));
        } catch (Exception ex) {
            result = new IntegrationConnector.TestResult(false, "连接异常：" + ex.getMessage(), e.getBaseUrl());
        }
        recordCall(e.getId(), "test", null, null, result.ok(), result.ok() ? null : result.message());
        audit("test", id, null, result.ok(), result.message());
        return result;
    }

    /** FR-02 未保存配置的连接测试（新建/编辑表单内预检）：凭据只在内存使用，不落库、不写调用日志 */
    public IntegrationConnector.TestResult testConnectionDraft(IntegrationRequest req) {
        String type = normalizeType(req.type());
        IntegrationConnector connector = connectors.get(type);
        if (connector == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "暂不支持的平台类型 " + type + "（当前可用：" + String.join("/", connectors.keySet()) + "）");
        }
        String authType = normalizeAuthType(req.authType());
        if (req.token() == null || req.token().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    IntegrationEntity.AUTH_BASIC.equals(authType) ? "密码不能为空" : "token 不能为空");
        }
        String username = null;
        if (IntegrationEntity.AUTH_BASIC.equals(authType)) {
            if (req.username() == null || req.username().isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "Basic Auth 需要填写用户名");
            }
            username = req.username().trim();
        }
        IntegrationEntity e = new IntegrationEntity();
        e.setType(type);
        e.setBaseUrl(validateBaseUrl(req.baseUrl()));
        e.setAuthType(authType);
        IntegrationConnector.TestResult result;
        try {
            result = connector.testConnection(e, encodeSecret(authType, username, req.token().trim()));
        } catch (Exception ex) {
            result = new IntegrationConnector.TestResult(false, "连接异常：" + ex.getMessage(), e.getBaseUrl());
        }
        audit("test_draft", null, null, result.ok(), type + " " + e.getBaseUrl() + "：" + result.message());
        return result;
    }

    /** FR-03 绑定辅助：列出 token 可见的平台项目 */
    public List<IntegrationConnector.ExternalProject> listExternalProjects(Long id) {
        IntegrationEntity e = require(id);
        return connectorFor(e).listProjects(e, tokenOf(e));
    }

    // ---------------- FR-03 项目绑定 ----------------

    public BindingView bind(String projectId, BindingRequest req) {
        projectService.requireProject(projectId);
        if (req.integrationId() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "integrationId 不能为空");
        }
        IntegrationEntity integration = require(req.integrationId());
        ProjectRepoEntity repo = req.repoId() != null
                ? projectRepoRepo.findById(req.repoId())
                        .filter(r -> projectId.equals(r.getProjectId()))
                        .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                                "仓库 " + req.repoId() + " 不属于项目 " + projectId))
                : projectService.primaryRepo(projectId);

        String key = blankToNull(req.externalProjectKey());
        if (key == null) {
            key = inferProjectKey(integration, repo);
        }
        // 同项目同类型仅允许一个 ENABLED 绑定
        for (IntegrationBindingEntity b : bindingRepo.findByProjectIdOrderByIdAsc(projectId)) {
            if (IntegrationBindingEntity.STATUS_ENABLED.equals(b.getStatus())) {
                IntegrationEntity other = integrationRepo.findById(b.getIntegrationId()).orElse(null);
                if (other != null && other.getType().equals(integration.getType())) {
                    throw new DevMindException(ErrorCode.CONFLICT,
                            "项目已绑定 " + integration.getType() + " 集成「" + other.getName()
                                    + "」（绑定 #" + b.getId() + "），请先解绑或禁用");
                }
            }
        }
        IntegrationBindingEntity b = new IntegrationBindingEntity();
        b.setIntegrationId(integration.getId());
        b.setProjectId(projectId);
        b.setRepoId(repo.getId());
        b.setExternalProjectKey(key);
        b.setStatus(IntegrationBindingEntity.STATUS_ENABLED);
        b.setCreatedAt(Instant.now());
        IntegrationBindingEntity saved = bindingRepo.save(b);
        audit("bind", integration.getId(), projectId, true,
                "项目 " + projectId + " 绑定 " + integration.getType() + " → " + key);
        return toBindingView(saved);
    }

    public void unbind(String projectId, Long bindingId) {
        IntegrationBindingEntity b = bindingRepo.findById(bindingId)
                .filter(x -> projectId.equals(x.getProjectId()))
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "绑定不存在: " + bindingId));
        bindingRepo.delete(b);
        audit("unbind", b.getIntegrationId(), projectId, true, "解绑 #" + bindingId);
    }

    public List<BindingView> listBindings(String projectId) {
        projectService.requireProject(projectId);
        return bindingRepo.findByProjectIdOrderByIdAsc(projectId).stream().map(this::toBindingView).toList();
    }

    // ---------------- FR-04 推送 WI 分支 ----------------

    /** CAP-24 FR-04：push 结果（branch + 实际所用身份来源 PERSONAL/INTEGRATION）。 */
    public record PushResult(String branch, String identitySource) {}

    public PushResult pushWorkItemBranch(String projectId, String workItemId) {
        WorkItemEntity wi = workItemService.requireEntity(projectId, workItemId);
        String branch = workItemService.branchName(wi);
        ResolvedBinding rb = requireGitBinding(projectId);
        // CAP-24 FR-04 凭证优先级：触发用户个人 PAT（remoteUrl host 匹配）→ 项目绑定 Integration
        String repoHost = UserGitCredentialService.hostOf(rb.repo.getRemoteUrl());
        String actor = identityService.currentActor();
        java.util.Optional<String> personal = userGitCredentialService.personalTokenFor(actor, repoHost);
        String token = personal.orElseGet(() -> tokenOf(rb.integration));
        String identitySource = personal.isPresent() ? "PERSONAL" : "INTEGRATION";
        GitRemoteOps.GitResult result = gitOps.pushBranch(
                rb.repo.getPath(), branch, rb.repo.getRemoteUrl(), token);
        recordCall(rb.integration.getId(), "push_branch",
                ExternalLinkEntity.INTERNAL_WORK_ITEM, workItemId, result.ok(),
                result.ok() ? null : tail(result.output()));
        audit("push_branch", rb.integration.getId(), projectId, result.ok(),
                "WI-" + wi.getSeq() + " 分支 " + branch + "（身份 " + identitySource + "）"
                        + (result.ok() ? " 已推送" : " 推送失败"));
        if (!result.ok()) {
            throw new DevMindException(ErrorCode.INTERNAL, "分支推送失败：" + tail(result.output()));
        }
        return new PushResult(branch, identitySource);
    }

    // ---------------- FR-05 创建 MR（幂等） ----------------

    public ExternalLinkView createMergeRequest(String projectId, String workItemId, CreateMrRequest req) {
        WorkItemEntity wi = workItemService.requireEntity(projectId, workItemId);
        ResolvedBinding rb = requireGitBinding(projectId);
        IntegrationConnector connector = connectorFor(rb.integration);

        // 幂等：已登记过 MR 直接返回既有链接
        Optional<ExternalLinkEntity> existing = linkRepo
                .findFirstByIntegrationIdAndInternalTypeAndInternalIdAndExternalTypeOrderByIdDesc(
                        rb.integration.getId(), ExternalLinkEntity.INTERNAL_WORK_ITEM,
                        workItemId, ExternalLinkEntity.EXTERNAL_MR);
        if (existing.isPresent()) {
            return toLinkView(existing.get());
        }

        String branch = workItemService.branchName(wi);
        String target = req != null && req.targetBranch() != null && !req.targetBranch().isBlank()
                ? req.targetBranch().trim()
                : (rb.repo.getDefaultBranch() != null && !rb.repo.getDefaultBranch().isBlank()
                        ? rb.repo.getDefaultBranch() : "master");
        String title = req != null && req.title() != null && !req.title().isBlank()
                ? req.title().trim() : "WI-" + wi.getSeq() + " " + wi.getTitle();
        String description = "Work Item: WI-" + wi.getSeq() + "（" + wi.getId() + "）\n\n"
                + (wi.getSpec() == null ? "" : wi.getSpec());

        IntegrationConnector.MergeRequestRef mr = connector.createMergeRequest(rb.integration,
                tokenOf(rb.integration),
                new IntegrationConnector.MrSpec(rb.binding.getExternalProjectKey(), branch, target,
                        title, description));
        ExternalLinkEntity link = registerLink(rb, ExternalLinkEntity.INTERNAL_WORK_ITEM, workItemId,
                ExternalLinkEntity.EXTERNAL_MR, mr.iid(), mr.url(),
                mr.state() == null ? "OPEN" : mr.state().toUpperCase());
        recordCall(rb.integration.getId(), "create_mr",
                ExternalLinkEntity.INTERNAL_WORK_ITEM, workItemId, true, null);
        audit("create_mr", rb.integration.getId(), projectId, true,
                "WI-" + wi.getSeq() + " MR !" + mr.iid() + (mr.reused() ? "（复用既有）" : ""));
        return toLinkView(link);
    }

    // ---------------- FR-06 发版钩子（push tag + 平台 Release） ----------------

    @Override
    public String onReleaseSuccess(String projectId, Long releaseId, String tagName,
                                   String version, String summary) {
        Optional<ResolvedBinding> found = findGitBinding(projectId);
        if (found.isEmpty() || tagName == null || tagName.isBlank()) {
            return null; // 未绑定/无 tag：静默跳过（文档口径）
        }
        ResolvedBinding rb = found.get();
        // push tag
        GitRemoteOps.GitResult push = gitOps.pushTag(
                rb.repo.getPath(), tagName, rb.repo.getRemoteUrl(), tokenOf(rb.integration));
        recordCall(rb.integration.getId(), "push_tag",
                ExternalLinkEntity.INTERNAL_RELEASE, String.valueOf(releaseId), push.ok(),
                push.ok() ? null : tail(push.output()));
        if (!push.ok()) {
            audit("release_hook", rb.integration.getId(), projectId, false,
                    "tag " + tagName + " 推送失败");
            return "[集成] tag " + tagName + " 推送失败：" + tail(push.output());
        }
        // 创建平台 Release
        try {
            IntegrationConnector.ReleaseRef rel = connectorFor(rb.integration).createRelease(
                    rb.integration, tokenOf(rb.integration),
                    new IntegrationConnector.ReleaseSpec(rb.binding.getExternalProjectKey(),
                            tagName, "v" + version, summary));
            registerLink(rb, ExternalLinkEntity.INTERNAL_RELEASE, String.valueOf(releaseId),
                    ExternalLinkEntity.EXTERNAL_TAG_RELEASE, rel.tagName(), rel.url(), "CREATED");
            recordCall(rb.integration.getId(), "create_release",
                    ExternalLinkEntity.INTERNAL_RELEASE, String.valueOf(releaseId), true, null);
            audit("release_hook", rb.integration.getId(), projectId, true,
                    "tag " + tagName + " 已推送并创建平台 Release" + (rel.reused() ? "（复用既有）" : ""));
            return "[集成] tag " + tagName + " 已推送，平台 Release 已创建"
                    + (rel.reused() ? "（复用既有）" : "");
        } catch (Exception e) {
            recordCall(rb.integration.getId(), "create_release",
                    ExternalLinkEntity.INTERNAL_RELEASE, String.valueOf(releaseId), false,
                    e.getMessage());
            audit("release_hook", rb.integration.getId(), projectId, false,
                    "tag 已推送但平台 Release 创建失败: " + e.getMessage());
            return "[集成] tag " + tagName + " 已推送；平台 Release 创建失败：" + e.getMessage();
        }
    }

    // ---------------- FR-07/08 查询 ----------------

    public List<ExternalLinkView> links(String projectId, String internalType, String internalId) {
        projectService.requireProject(projectId);
        if (internalType == null || internalType.isBlank() || internalId == null || internalId.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "internalType 与 internalId 必填");
        }
        return linkRepo.findByProjectIdAndInternalTypeAndInternalId(
                        projectId, internalType.trim().toUpperCase(), internalId.trim())
                .stream().map(this::toLinkView).toList();
    }

    /** 项目内某类内部实体的全部外部链接（需求列表 Jira 来源徽标批量反查用） */
    public List<ExternalLinkView> linksByType(String projectId, String internalType) {
        projectService.requireProject(projectId);
        if (internalType == null || internalType.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "internalType 必填");
        }
        return linkRepo.findByProjectIdAndInternalType(projectId, internalType.trim().toUpperCase())
                .stream().map(this::toLinkView).toList();
    }

    public List<IntegrationCallView> calls(String projectId) {
        projectService.requireProject(projectId);
        // 项目维度：经绑定（git 类）与 Jira 同步配置（issue 类）反查涉及的 integration
        List<Long> ids = java.util.stream.Stream.concat(
                        bindingRepo.findByProjectIdOrderByIdAsc(projectId).stream()
                                .map(IntegrationBindingEntity::getIntegrationId),
                        jiraSyncConfigRepo.findByProjectIdOrderByIdAsc(projectId).stream()
                                .map(JiraSyncConfigEntity::getIntegrationId))
                .distinct().toList();
        return ids.stream()
                .flatMap(id -> callRepo.findByIntegrationIdOrderByIdDesc(id).stream())
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .limit(200)
                .map(this::toCallView).toList();
    }

    // ---------------- 内部 ----------------

    /** 已解析的绑定上下文：Integration + Binding + 仓库实体 */
    private record ResolvedBinding(IntegrationEntity integration, IntegrationBindingEntity binding,
                                   ProjectRepoEntity repo) {}

    /** 找项目的 git 类绑定（GITLAB/GITHUB，双 ENABLED）；无则空 */
    private Optional<ResolvedBinding> findGitBinding(String projectId) {
        for (IntegrationBindingEntity b : bindingRepo.findByProjectIdOrderByIdAsc(projectId)) {
            if (!IntegrationBindingEntity.STATUS_ENABLED.equals(b.getStatus())) {
                continue;
            }
            IntegrationEntity integration = integrationRepo.findById(b.getIntegrationId()).orElse(null);
            if (integration == null || !IntegrationEntity.STATUS_ENABLED.equals(integration.getStatus())) {
                continue;
            }
            if (!IntegrationEntity.TYPE_GITLAB.equals(integration.getType())
                    && !IntegrationEntity.TYPE_GITHUB.equals(integration.getType())) {
                continue; // Jira 等无 git 能力
            }
            ProjectRepoEntity repo = projectRepoRepo.findById(b.getRepoId()).orElse(null);
            if (repo == null) {
                continue;
            }
            return Optional.of(new ResolvedBinding(integration, b, repo));
        }
        return Optional.empty();
    }

    private ResolvedBinding requireGitBinding(String projectId) {
        return findGitBinding(projectId).orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST,
                "项目未绑定可用的代码平台集成（请先在项目设置绑定 GitLab/GitHub Integration）"));
    }

    private IntegrationConnector connectorFor(IntegrationEntity e) {
        IntegrationConnector c = connectors.get(e.getType());
        if (c == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "平台类型 " + e.getType() + " 无可用连接器实现");
        }
        return c;
    }

    /** 解密凭据（仅内存使用，不进日志）；public 供 JiraSyncService 等站内服务复用 */
    public String tokenOf(IntegrationEntity e) {
        String token = cipher.decrypt(e.getSecretEnc());
        if (token == null || token.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "集成 " + e.getId() + " 未配置凭据");
        }
        return token;
    }

    /** 从 remote_url 推断平台项目标识（https://host/group/proj.git → group/proj，GitHub 即 owner/repo） */
    private String inferProjectKey(IntegrationEntity integration, ProjectRepoEntity repo) {
        String url = repo.getRemoteUrl();
        if (url != null && !url.isBlank()) {
            try {
                String path = java.net.URI.create(url.trim()).getPath();
                if (path != null && path.length() > 1) {
                    String p = path.substring(1);
                    if (p.endsWith(".git")) {
                        p = p.substring(0, p.length() - 4);
                    }
                    if (!p.isBlank()) {
                        return p;
                    }
                }
            } catch (Exception ignored) {
                // 落到报错
            }
        }
        throw new DevMindException(ErrorCode.BAD_REQUEST,
                "无法从 remote_url 推断平台项目标识，请显式填写 externalProjectKey"
                        + (IntegrationEntity.TYPE_GITLAB.equals(integration.getType())
                                ? "（GitLab project id 或 group/path）"
                                : IntegrationEntity.TYPE_GITHUB.equals(integration.getType())
                                        ? "（GitHub owner/repo）" : ""));
    }

    /** base_url 校验：仅 http/https（防 SSRF 指到 file: 等协议；内网地址属合法场景，后续可加白名单） */
    private String validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空");
        }
        String url = baseUrl.trim().replaceAll("/+$", "");
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "baseUrl 不是合法 URL：" + baseUrl);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "baseUrl 协议仅支持 http/https");
        }
        if (uri.getHost() == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "baseUrl 缺少主机名：" + baseUrl);
        }
        return url;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "type 不能为空（GITLAB/GITHUB/JIRA）");
        }
        return type.trim().toUpperCase();
    }

    /** authType 归一：空白默认 PAT；仅支持 PAT / BASIC */
    static String normalizeAuthType(String authType) {
        if (authType == null || authType.isBlank()) {
            return IntegrationEntity.AUTH_PAT;
        }
        String a = authType.trim().toUpperCase();
        if (!IntegrationEntity.AUTH_PAT.equals(a) && !IntegrationEntity.AUTH_BASIC.equals(a)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "authType 仅支持 PAT / BASIC");
        }
        return a;
    }

    /** 凭据存储格式：PAT=原样 token；BASIC="username\npassword"（换行分隔，连接器按首行分割） */
    static String encodeSecret(String authType, String username, String token) {
        if (IntegrationEntity.AUTH_BASIC.equals(authType)) {
            if (username.contains("\n")) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "用户名不能包含换行");
            }
            return username + "\n" + token;
        }
        return token;
    }

    /** 从 BASIC 密文明文中取用户名（更新时留空沿用）；非 BASIC 格式返回 null */
    static String basicUsername(String secret) {
        if (secret == null) {
            return null;
        }
        int i = secret.indexOf('\n');
        return i > 0 ? secret.substring(0, i) : null;
    }

    private ExternalLinkEntity registerLink(ResolvedBinding rb, String internalType, String internalId,
                                            String externalType, String key, String url, String status) {
        ExternalLinkEntity link = new ExternalLinkEntity();
        link.setProjectId(rb.binding.getProjectId());
        link.setIntegrationId(rb.integration.getId());
        link.setInternalType(internalType);
        link.setInternalId(internalId);
        link.setExternalType(externalType);
        link.setExternalKey(key);
        link.setExternalUrl(url);
        link.setStatus(status);
        link.setCreatedAt(Instant.now());
        return linkRepo.save(link);
    }

    /** 调用日志（FR-08）：旁路，失败不阻断；public 供 JiraSyncService 等站内服务复用 */
    public void recordCall(Long integrationId, String action, String internalType, String internalId,
                           boolean ok, String error) {
        try {
            IntegrationCallEntity c = new IntegrationCallEntity();
            c.setIntegrationId(integrationId);
            c.setAction(action);
            c.setInternalType(internalType);
            c.setInternalId(internalId);
            c.setResult(ok ? IntegrationCallEntity.RESULT_SUCCESS : IntegrationCallEntity.RESULT_FAILED);
            c.setError(truncate(error, 1900));
            c.setActor(identityService.currentActor());
            c.setCreatedAt(Instant.now());
            callRepo.save(c);
        } catch (Exception e) {
            log.warn("集成调用日志写入失败: {}", e.getMessage());
        }
    }

    private void audit(String action, Long integrationId, String projectId,
                       boolean success, String detail) {
        auditService.record("integration", action, identityService.currentActor(),
                projectId, success, integrationId != null ? "[#" + integrationId + "] " + detail : detail);
    }

    private IntegrationView toView(IntegrationEntity e) {
        return new IntegrationView(e.getId(), e.getType(), e.getName(), e.getBaseUrl(), e.getAuthType(),
                e.getSecretEnc() != null && !e.getSecretEnc().isBlank(),
                e.getStatus(), e.getConfigJson(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private BindingView toBindingView(IntegrationBindingEntity b) {
        IntegrationEntity integration = integrationRepo.findById(b.getIntegrationId()).orElse(null);
        ProjectRepoEntity repo = projectRepoRepo.findById(b.getRepoId()).orElse(null);
        return new BindingView(b.getId(), b.getIntegrationId(),
                integration != null ? integration.getName() : null,
                integration != null ? integration.getType() : null,
                b.getProjectId(), b.getRepoId(), repo != null ? repo.getName() : null,
                b.getExternalProjectKey(), b.getStatus(), b.getCreatedAt());
    }

    private ExternalLinkView toLinkView(ExternalLinkEntity e) {
        return new ExternalLinkView(e.getId(), e.getIntegrationId(), e.getInternalType(), e.getInternalId(),
                e.getExternalType(), e.getExternalKey(), e.getExternalUrl(), e.getStatus(), e.getCreatedAt());
    }

    private IntegrationCallView toCallView(IntegrationCallEntity e) {
        return new IntegrationCallView(e.getId(), e.getIntegrationId(), e.getAction(),
                e.getInternalType(), e.getInternalId(), e.getResult(), e.getError(),
                e.getActor(), e.getCreatedAt());
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** git 输出的失败原因摘要：优先最后一条 fatal: 行（真实原因），否则最后非空行 */
    private String tail(String s) {
        if (s == null) {
            return null;
        }
        String last = null;
        String lastFatal = null;
        for (String l : s.split("\\R")) {
            if (!l.isBlank()) {
                last = l.trim();
                if (last.startsWith("fatal:")) {
                    lastFatal = last;
                }
            }
        }
        return truncate(lastFatal != null ? lastFatal : last, 500);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…[截断]";
    }
}
