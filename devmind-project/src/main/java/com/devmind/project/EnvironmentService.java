package com.devmind.project;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.EnvironmentRequest;
import com.devmind.project.dto.EnvironmentView;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.project.repo.EnvironmentRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P1-1 Environment 模型：项目内环境 CRUD（DEV/TEST/STAGING/PROD）。
 * 部署/测试目标 = runner 节点（CAP-36：nodeIds 引用 agent_nodes）——本服务只管数据，
 * deploy/test 的切换在各自模块接入（requireEnvironment 提供校验入口）。
 */
@Service
public class EnvironmentService {

    private static final Set<String> KNOWN = Set.of(EnvironmentEntity.DEV, EnvironmentEntity.TEST,
            EnvironmentEntity.STAGING, EnvironmentEntity.PROD);

    private final ProjectService projectService;
    private final EnvironmentRepository envRepo;
    private final ObjectMapper mapper;

    public EnvironmentService(ProjectService projectService,
                              EnvironmentRepository envRepo,
                              ObjectMapper mapper) {
        this.projectService = projectService;
        this.envRepo = envRepo;
        this.mapper = mapper;
    }

    public List<EnvironmentView> list(String projectId) {
        projectService.requireProject(projectId);
        return envRepo.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(this::toView).toList();
    }

    public EnvironmentView get(String projectId, Long envId) {
        return toView(requireEnvironment(projectId, envId));
    }

    /** 校验并取出环境（供 deploy/test 等执行器按环境定位目标节点/变量） */
    public EnvironmentEntity requireEnvironment(String projectId, Long envId) {
        projectService.requireProject(projectId);
        EnvironmentEntity e = envRepo.findById(envId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "环境不存在: " + envId));
        if (!projectId.equals(e.getProjectId())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "环境 " + envId + " 不属于项目 " + projectId);
        }
        return e;
    }

    public EnvironmentView create(String projectId, EnvironmentRequest req) {
        projectService.requireProject(projectId);
        String name = normalizeName(req.name());
        if (envRepo.findByProjectIdAndName(projectId, name).isPresent()) {
            throw new DevMindException(ErrorCode.CONFLICT, "环境 " + name + " 已存在");
        }
        EnvironmentEntity e = new EnvironmentEntity();
        e.setProjectId(projectId);
        apply(e, req, name);
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        return toView(envRepo.save(e));
    }

    public EnvironmentView update(String projectId, Long envId, EnvironmentRequest req) {
        EnvironmentEntity e = requireEnvironment(projectId, envId);
        String name = normalizeName(req.name());
        envRepo.findByProjectIdAndName(projectId, name)
                .filter(other -> !other.getId().equals(envId))
                .ifPresent(other -> {
                    throw new DevMindException(ErrorCode.CONFLICT, "环境 " + name + " 已存在");
                });
        apply(e, req, name);
        e.setUpdatedAt(Instant.now());
        return toView(envRepo.save(e));
    }

    public void delete(String projectId, Long envId) {
        envRepo.delete(requireEnvironment(projectId, envId));
    }

    // ---------------- 内部 ----------------

    private void apply(EnvironmentEntity e, EnvironmentRequest req, String name) {
        e.setName(name);
        e.setDescription(blankToNull(req.description()));
        e.setNodeIdsJson(json(normalizeNodeIds(req.nodeIds())));
        e.setVariablesJson(json(req.variables() == null ? Map.of() : req.variables()));
        e.setSecretsJson(json(req.secrets() == null ? List.of() : req.secrets()));
    }

    /** 节点 id 轻量校验（非空、去空白、去重）；节点存在性/在线状态由执行期路由校验。 */
    private List<String> normalizeNodeIds(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return List.of();
        }
        List<String> out = nodeIds.stream().filter(n -> n != null && !n.isBlank())
                .map(String::trim).distinct().toList();
        if (out.size() != nodeIds.size()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "节点 id 存在空白项");
        }
        return out;
    }

    private String normalizeName(String name) {
        String n = name == null ? "" : name.trim().toUpperCase();
        if (n.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "环境名称不能为空");
        }
        if (!KNOWN.contains(n)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "环境名称限定 DEV/TEST/STAGING/PROD（收到 " + n + "）");
        }
        return n;
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new DevMindException(ErrorCode.INTERNAL, "环境配置序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T parse(String json, Class<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 环境的目标节点 id 列表（deploy/test 按环境定位执行节点；CAP-36） */
    @SuppressWarnings("unchecked")
    public List<String> nodeIdsOf(EnvironmentEntity e) {
        List<Object> ids = parse(e.getNodeIdsJson(), List.class, List.of());
        return ids.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }

    /** 环境变量（注入执行参数；secret 仅为名字引用，此处不取值） */
    @SuppressWarnings("unchecked")
    public Map<String, String> variablesOf(EnvironmentEntity e) {
        return parse(e.getVariablesJson(), Map.class, Map.of());
    }

    public EnvironmentView toView(EnvironmentEntity e) {
        Map<String, String> vars = parse(e.getVariablesJson(), Map.class, Map.of());
        List<String> secrets = parse(e.getSecretsJson(), List.class, List.of());
        return new EnvironmentView(e.getId(), e.getProjectId(), e.getName(), e.getDescription(),
                nodeIdsOf(e), vars, secrets,
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
