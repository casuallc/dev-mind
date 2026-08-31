package com.devmind.project;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.dto.EnvironmentRequest;
import com.devmind.project.dto.EnvironmentView;
import com.devmind.project.model.EnvironmentEntity;
import com.devmind.project.model.ProjectServerEntity;
import com.devmind.project.repo.EnvironmentRepository;
import com.devmind.project.repo.ProjectServerRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P1-1 Environment 模型：项目内环境 CRUD（DEV/TEST/STAGING/PROD）。
 * 部署/测试目标从「服务器」升级为「环境」的承载体——本服务只管数据，
 * deploy/test 的切换在各自模块接入（requireEnvironment 提供校验入口）。
 */
@Service
public class EnvironmentService {

    private static final Set<String> KNOWN = Set.of(EnvironmentEntity.DEV, EnvironmentEntity.TEST,
            EnvironmentEntity.STAGING, EnvironmentEntity.PROD);

    private final ProjectService projectService;
    private final EnvironmentRepository envRepo;
    private final ProjectServerRepository serverRepo;
    private final ObjectMapper mapper;

    public EnvironmentService(ProjectService projectService,
                              EnvironmentRepository envRepo,
                              ProjectServerRepository serverRepo,
                              ObjectMapper mapper) {
        this.projectService = projectService;
        this.envRepo = envRepo;
        this.serverRepo = serverRepo;
        this.mapper = mapper;
    }

    public List<EnvironmentView> list(String projectId) {
        projectService.requireProject(projectId);
        return envRepo.findByProjectIdOrderByIdAsc(projectId).stream().map(this::toView).toList();
    }

    public EnvironmentView get(String projectId, Long envId) {
        return toView(requireEnvironment(projectId, envId));
    }

    /** 校验并取出环境（供 deploy/test 等执行器按环境定位目标服务器/变量） */
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
        validateServers(e.getProjectId(), req.serverIds());
        e.setServerIdsJson(json(req.serverIds() == null ? List.of() : req.serverIds()));
        e.setVariablesJson(json(req.variables() == null ? Map.of() : req.variables()));
        e.setSecretsJson(json(req.secrets() == null ? List.of() : req.secrets()));
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

    private void validateServers(String projectId, List<Long> serverIds) {
        if (serverIds == null || serverIds.isEmpty()) {
            return;
        }
        Set<Long> owned = serverRepo.findByProjectIdOrderByIdAsc(projectId).stream()
                .map(ProjectServerEntity::getId).collect(Collectors.toSet());
        List<Long> foreign = serverIds.stream().filter(sid -> !owned.contains(sid)).toList();
        if (!foreign.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "服务器不属于本项目: " + foreign);
        }
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

    /** 环境的目标服务器 id 列表（deploy/test 按环境定位执行目标） */
    @SuppressWarnings("unchecked")
    public List<Long> serverIdsOf(EnvironmentEntity e) {
        List<Object> ids = parse(e.getServerIdsJson(), List.class, List.of());
        return ids.stream().map(n -> ((Number) n).longValue()).toList();
    }

    /** 环境变量（注入执行参数；secret 仅为名字引用，此处不取值） */
    @SuppressWarnings("unchecked")
    public Map<String, String> variablesOf(EnvironmentEntity e) {
        return parse(e.getVariablesJson(), Map.class, Map.of());
    }

    public EnvironmentView toView(EnvironmentEntity e) {
        List<Object> ids = parse(e.getServerIdsJson(), List.class, List.of());
        Map<String, String> vars = parse(e.getVariablesJson(), Map.class, Map.of());
        List<String> secrets = parse(e.getSecretsJson(), List.class, List.of());
        return new EnvironmentView(e.getId(), e.getProjectId(), e.getName(), e.getDescription(),
                ids.stream().map(n -> ((Number) n).longValue()).toList(),
                vars, secrets,
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
