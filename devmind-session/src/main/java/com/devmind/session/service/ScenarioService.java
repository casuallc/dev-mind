package com.devmind.session.service;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.project.model.Project;
import com.devmind.session.dto.ScenarioRequest;
import com.devmind.session.dto.ScenarioView;
import com.devmind.session.model.SessionScenarioEntity;
import com.devmind.session.model.SessionTemplateEntity;
import com.devmind.session.repo.SessionScenarioRepository;
import com.devmind.session.repo.SessionTemplateRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * CAP-33 FR-01 场景管理：session_scenarios CRUD + 骨架渲染 + 启动期模板迁移。
 * 旧 session_templates 仅作迁移数据源保留（ddl-auto 不删表，代码层停用）。
 *
 * <p>按 code 解析不查 enabled（兼容旧 templateCode：停用的模板/场景旧 code 仍可用）；
 * 列表/创建表单只出 enabled。</p>
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_PROJECT = "PROJECT";

    private final SessionScenarioRepository scenarioRepo;
    /** 仅迁移用：读取旧 session_templates 行 */
    private final SessionTemplateRepository templateRepo;
    private final ProjectService projectService;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager txManager;

    public ScenarioService(SessionScenarioRepository scenarioRepo,
                           SessionTemplateRepository templateRepo,
                           ProjectService projectService,
                           ObjectMapper mapper,
                           PlatformTransactionManager txManager) {
        this.scenarioRepo = scenarioRepo;
        this.templateRepo = templateRepo;
        this.projectService = projectService;
        this.mapper = mapper;
        this.txManager = txManager;
    }

    // ---------------- 查询 ----------------

    public List<ScenarioView> list() {
        return scenarioRepo.findAllByOrderBySortOrderAscIdAsc().stream().map(this::toView).toList();
    }

    /** 创建表单下拉：仅 enabled。 */
    public List<ScenarioView> listEnabled() {
        return scenarioRepo.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(SessionScenarioEntity::isEnabled).map(this::toView).toList();
    }

    /** 按 code 解析（不查 enabled——兼容旧 templateCode 语义）；不存在 404。 */
    public SessionScenarioEntity requireByCode(String code) {
        return scenarioRepo.findByCode(code)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "场景不存在: " + code));
    }

    // ---------------- CRUD ----------------

    @Transactional
    public ScenarioView save(Long id, ScenarioRequest req) {
        SessionScenarioEntity s;
        if (id == null) {
            if (req.code() == null || req.code().isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "场景 code 必填");
            }
            if (scenarioRepo.existsByCode(req.code().strip())) {
                throw new DevMindException(ErrorCode.CONFLICT, "场景 code 已存在: " + req.code());
            }
            s = new SessionScenarioEntity();
            s.setCode(req.code().strip());
            s.setCreatedAt(Instant.now());
        } else {
            s = scenarioRepo.findById(id)
                    .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "场景不存在: " + id));
            if (req.code() != null && !req.code().isBlank() && !req.code().strip().equals(s.getCode())) {
                if (scenarioRepo.existsByCode(req.code().strip())) {
                    throw new DevMindException(ErrorCode.CONFLICT, "场景 code 已存在: " + req.code());
                }
                s.setCode(req.code().strip());
            }
        }
        String scope = req.scope() != null && !req.scope().isBlank() ? req.scope().strip()
                : (s.getScope() != null && !s.getScope().isBlank() ? s.getScope() : SCOPE_GLOBAL);
        if (!SCOPE_GLOBAL.equals(scope) && !SCOPE_PROJECT.equals(scope)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "scope 必须是 GLOBAL 或 PROJECT");
        }
        String projectId = req.projectId() != null ? req.projectId() : s.getProjectId();
        if (SCOPE_PROJECT.equals(scope)) {
            if (projectId == null || projectId.isBlank()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "PROJECT 场景必须指定 projectId");
            }
            projectService.requireProject(projectId); // 存在性校验
        } else {
            projectId = null;
        }
        if (req.name() != null) s.setName(req.name());
        if (s.getName() == null || s.getName().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "场景名称必填");
        }
        if (req.description() != null) s.setDescription(req.description());
        if (req.promptSkeleton() != null) s.setPromptSkeleton(req.promptSkeleton());
        if (req.skillIds() != null) s.setSkillIdsJson(writeJson(req.skillIds()));
        if (req.docIds() != null) s.setDocIdsJson(writeJson(req.docIds()));
        if (req.knowledgeTags() != null) s.setKnowledgeTags(joinCsv(req.knowledgeTags()));
        if (req.extraContextMd() != null) s.setExtraContextMd(req.extraContextMd());
        if (req.model() != null) s.setModel(req.model().isBlank() ? null : req.model().strip());
        if (req.permissionMode() != null) {
            s.setPermissionMode(req.permissionMode().isBlank() ? null : req.permissionMode().strip());
        }
        if (req.agentNodeId() != null) s.setAgentNodeId(req.agentNodeId().isBlank() ? null : req.agentNodeId().strip());
        s.setScope(scope);
        s.setProjectId(projectId);
        if (req.enabled() != null) s.setEnabled(req.enabled());
        if (req.sortOrder() != null) s.setSortOrder(req.sortOrder());
        s.setUpdatedAt(Instant.now());
        return toView(scenarioRepo.save(s));
    }

    @Transactional
    public void delete(Long id) {
        scenarioRepo.deleteById(id);
    }

    // ---------------- 渲染 ----------------

    /** 渲染骨架：纯字符串替换 {{task}}/{{project}}/{{branch}}/{{requirement}}（沿用模板语义）。 */
    public String render(SessionScenarioEntity s, String task, Project project, String requirementTitle) {
        String skeleton = s.getPromptSkeleton() == null ? "" : s.getPromptSkeleton();
        return skeleton
                .replace("{{task}}", task == null ? "" : task)
                .replace("{{project}}", project != null && project.name() != null ? project.name() : "")
                .replace("{{branch}}", project != null && project.baseBranch() != null ? project.baseBranch() : "")
                .replace("{{requirement}}", requirementTitle == null ? "" : requirementTitle);
    }

    // ---------------- 资产清单解析（装配管线用） ----------------

    public List<String> skillIdsOf(SessionScenarioEntity s) {
        return readJson(s.getSkillIdsJson(), new TypeReference<>() {
        });
    }

    public List<Long> docIdsOf(SessionScenarioEntity s) {
        return readJson(s.getDocIdsJson(), new TypeReference<>() {
        });
    }

    public List<String> knowledgeTagsOf(SessionScenarioEntity s) {
        return splitCsv(s.getKnowledgeTags());
    }

    // ---------------- 视图 ----------------

    public ScenarioView toView(SessionScenarioEntity s) {
        return new ScenarioView(s.getId(), s.getCode(), s.getName(), s.getDescription(),
                s.getPromptSkeleton(), skillIdsOf(s), docIdsOf(s), knowledgeTagsOf(s),
                s.getExtraContextMd(), s.getModel(), s.getPermissionMode(), s.getAgentNodeId(),
                s.getScope(), s.getProjectId(), s.isEnabled(), s.getSortOrder(),
                s.getCreatedAt(), s.getUpdatedAt());
    }

    // ---------------- 启动期迁移（FR-01：模板行转场景行） ----------------

    /**
     * session_templates → session_scenarios 幂等迁移：逐行 existsByCode 跳过
     * （扛部分迁移/手工预建），scope=GLOBAL、资产绑定为空。@PostConstruct 不经代理，
     * 多行写用 TransactionTemplate 包事务（ProjectService.migrateProjectRepos 先例）。
     */
    @PostConstruct
    public void migrateFromTemplates() {
        List<SessionTemplateEntity> templates = templateRepo.findAll();
        if (templates.isEmpty()) {
            return;
        }
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            int migrated = 0;
            for (SessionTemplateEntity t : templates) {
                if (t.getCode() == null || t.getCode().isBlank() || scenarioRepo.existsByCode(t.getCode())) {
                    continue;
                }
                SessionScenarioEntity s = new SessionScenarioEntity();
                s.setCode(t.getCode());
                s.setName(t.getName());
                s.setPromptSkeleton(t.getPrompt());
                s.setScope(SCOPE_GLOBAL);
                s.setEnabled(t.isEnabled());
                s.setSortOrder(t.getSortOrder());
                Instant now = Instant.now();
                s.setCreatedAt(now);
                s.setUpdatedAt(now);
                scenarioRepo.save(s);
                migrated++;
            }
            if (migrated > 0) {
                log.info("会话模板迁移为场景: {} 条（session_templates 代码层已停用）", migrated);
            }
        });
    }

    // ---------------- 内部 ----------------

    private String writeJson(Object value) {
        return mapper.writeValueAsString(value);
    }

    private <T> List<T> readJson(String json, TypeReference<List<T>> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, type);
    }

    static String joinCsv(List<String> items) {
        return items == null || items.isEmpty() ? "" : String.join(",", items);
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
