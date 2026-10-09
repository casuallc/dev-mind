package com.devmind.test.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.project.ProjectService;
import com.devmind.test.dto.ScriptSuiteEnv;
import com.devmind.test.dto.ScriptSuiteRequest;
import com.devmind.test.dto.ScriptSuiteView;
import com.devmind.test.model.TestSuiteEntity;
import com.devmind.test.repo.TestSuiteRepository;

/**
 * CAP-69 脚本套件 CRUD（kind=script，强制绑定项目）：自带 git 源 + 命令模板 + env（脱敏）+ 超时，
 * 触发运行见 {@link TestRunService#createScriptRun}。入口 = 项目「测试」页新建套件选 script 类型；
 * 绑定后 run 进项目运行历史、报告走 CAP-03 沉淀。
 *
 * <p>env 掩码语义（对齐 CAP-68）：secret=true 的值在视图层恒为 {@link ScriptSuiteEnv#MASK}；
 * PUT 整体替换时掩码原样回传 = 该条值不变（从旧实体取原值）。</p>
 */
@Service
public class ScriptSuiteService {

    private static final Logger log = LoggerFactory.getLogger(ScriptSuiteService.class);

    /** junitPath / workSubdir：仓库内相对路径（段白名单，禁绝对路径/反斜杠/.. 段） */
    private static final Pattern REL_PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    /** workspaceKey 进 runner 工作区目录名（对齐 RunnerWorkspace SAFE_ID 口径） */
    private static final Pattern SAFE_ID = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    private static final int DEFAULT_TIMEOUT_SEC = 7200;

    private final TestSuiteRepository suiteRepo;
    private final ProjectService projectService;
    private final ObjectMapper mapper;

    public ScriptSuiteService(TestSuiteRepository suiteRepo, ProjectService projectService, ObjectMapper mapper) {
        this.suiteRepo = suiteRepo;
        this.projectService = projectService;
        this.mapper = mapper;
    }

    /** 项目绑定的脚本套件列表（强制项目口径，无全局列表） */
    public List<ScriptSuiteView> list(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "projectId 必填（脚本套件强制绑定项目）");
        }
        return suiteRepo.findByProjectIdAndKindOrderByCreatedAtDesc(projectId.strip(), "script")
                .stream().map(this::toView).toList();
    }

    public ScriptSuiteView get(Long id) {
        return toView(require(id));
    }

    public ScriptSuiteView create(ScriptSuiteRequest req) {
        validate(req);
        TestSuiteEntity e = new TestSuiteEntity();
        apply(e, req, null);
        e.setKind("script");
        e.setSource("manual");
        e.setCreatedAt(Instant.now());
        suiteRepo.save(e);
        log.info("CAP-69 创建脚本套件: id={} name={} repo={}@{}", e.getId(), e.getName(), e.getRepoUrl(), e.getBranch());
        return toView(e);
    }

    public ScriptSuiteView update(Long id, ScriptSuiteRequest req) {
        TestSuiteEntity e = require(id);
        validate(req);
        apply(e, req, e.getEnvJson());
        suiteRepo.save(e);
        return toView(e);
    }

    @Transactional
    public void delete(Long id) {
        TestSuiteEntity e = require(id);
        suiteRepo.delete(e);
        log.info("CAP-69 删除脚本套件: id={} name={}", id, e.getName());
    }

    public TestSuiteEntity require(Long id) {
        TestSuiteEntity e = suiteRepo.findById(id)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "脚本套件不存在: " + id));
        if (!"script".equals(e.getKind())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "套件 " + id + " 不是脚本套件（kind=" + e.getKind() + "）");
        }
        return e;
    }

    /** 套件 env 解出真实键值（含 secret 原值）——触发运行时注入 exec 帧用 */
    public List<ScriptSuiteEnv> envOf(TestSuiteEntity e) {
        return readEnv(e.getEnvJson());
    }

    // ---------------- 内部 ----------------

    private void validate(ScriptSuiteRequest req) {
        if (req == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "请求体为空");
        }
        require(req.projectId(), "归属项目 projectId");
        require(req.name(), "套件名称");
        require(req.repoUrl(), "git 仓库地址 repoUrl");
        require(req.branch(), "分支 branch");
        require(req.command(), "执行命令 command");
        require(req.junitPath(), "JUnit 产出路径 junitPath");
        checkRelPath(req.junitPath(), "junitPath");
        if (req.workSubdir() != null && !req.workSubdir().isBlank()) {
            checkRelPath(req.workSubdir(), "workSubdir");
        }
        if (req.workspaceKey() != null && !req.workspaceKey().isBlank()
                && !SAFE_ID.matcher(req.workspaceKey().strip()).matches()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "workspaceKey 只能含字母数字 ._-（≤64 字符）: " + req.workspaceKey());
        }
        if (req.timeoutSec() != null && req.timeoutSec() <= 0) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "timeoutSec 必须为正整数");
        }
        if (req.env() != null) {
            for (ScriptSuiteEnv env : req.env()) {
                if (env == null || env.key() == null || env.key().isBlank()
                        || !env.key().strip().matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                    throw new DevMindException(ErrorCode.BAD_REQUEST,
                            "env 键必须是合法环境变量名: " + (env == null ? null : env.key()));
                }
            }
        }
    }

    private void apply(TestSuiteEntity e, ScriptSuiteRequest req, String oldEnvJson) {
        String projectId = req.projectId().strip(); // validate() 已保非空
        projectService.requireProject(projectId); // 绑定前校验项目存在（404）
        e.setProjectId(projectId);
        e.setName(req.name().strip());
        e.setRepoUrl(req.repoUrl().strip());
        e.setBranch(req.branch().strip());
        e.setWorkSubdir(blankToNull(req.workSubdir()));
        e.setCommand(req.command());
        e.setJunitPath(req.junitPath().strip());
        e.setAgentNodeId(blankToNull(req.agentNodeId()));
        e.setTimeoutSec(req.timeoutSec() != null ? req.timeoutSec() : DEFAULT_TIMEOUT_SEC);
        e.setWorkspaceKey(blankToNull(req.workspaceKey()));
        e.setEnvJson(writeEnv(resolveEnv(req.env(), oldEnvJson)));
    }

    /** PUT 掩码回传 = 该条值不变（按 key 从旧值取）；create 时无旧值，掩码条目按空值存 */
    private List<ScriptSuiteEnv> resolveEnv(List<ScriptSuiteEnv> in, String oldEnvJson) {
        if (in == null) {
            return List.of();
        }
        List<ScriptSuiteEnv> old = readEnv(oldEnvJson);
        List<ScriptSuiteEnv> out = new ArrayList<>();
        for (ScriptSuiteEnv env : in) {
            String value = env.value();
            if (env.isSecret() && ScriptSuiteEnv.MASK.equals(value)) {
                value = old.stream().filter(o -> o.key().equals(env.key())).map(ScriptSuiteEnv::value)
                        .findFirst().orElse(null);
            }
            out.add(new ScriptSuiteEnv(env.key().strip(), value, env.isSecret()));
        }
        return out;
    }

    private ScriptSuiteView toView(TestSuiteEntity e) {
        List<ScriptSuiteEnv> env = readEnv(e.getEnvJson()).stream()
                .map(v -> v.isSecret() ? new ScriptSuiteEnv(v.key(), ScriptSuiteEnv.MASK, true) : v)
                .toList();
        return new ScriptSuiteView(e.getId(), e.getProjectId(), e.getName(), e.getRepoUrl(), e.getBranch(), e.getWorkSubdir(),
                e.getCommand(), e.getJunitPath(), env, e.getAgentNodeId(), e.getTimeoutSec(),
                e.getWorkspaceKey(), e.getCreatedAt());
    }

    private List<ScriptSuiteEnv> readEnv(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(List.class, ScriptSuiteEnv.class));
        } catch (Exception e) {
            log.warn("套件 envJson 解析失败（按空处理）: {}", e.getMessage());
            return List.of();
        }
    }

    private String writeEnv(List<ScriptSuiteEnv> env) {
        try {
            return env == null || env.isEmpty() ? null : mapper.writeValueAsString(env);
        } catch (Exception e) {
            return null;
        }
    }

    private void checkRelPath(String v, String field) {
        String s = v.strip();
        // 段字符集含 `.`，单靠正则拦不住 ".." 段——必须逐段排掉 . / ..（junitPath 会拼进 shell 尾段）
        boolean ok = REL_PATH.matcher(s).matches();
        for (String seg : s.split("/")) {
            if (seg.equals(".") || seg.equals("..")) {
                ok = false;
                break;
            }
        }
        if (!ok) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    field + " 必须是仓库内相对路径（段仅限字母数字 ._-，禁绝对路径/../反斜杠）: " + v);
        }
    }

    private void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, field + " 必填");
        }
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
